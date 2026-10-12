package com.laybalt.features.impl.dungeon.solvers.puzzles.water

import com.laybalt.features.impl.dungeon.solvers.puzzles.water.PlanFollower.Kind
import java.util.Locale

/**
 * Drives the Water Board from the real board state. Pure: it reads a [BoardView], reacts to [BoardEvent]s and answers
 * with [Guidance] and chat messages.
 *
 * Plan: the shipped plays whose door sets XOR to the doors that are closed right now ([DoorAlgebra]); one play for an
 * untouched board. Each play: flip the levers whose parity differs from the play's start ([Stage.PREPARE]), pull the
 * water, follow the timed flips ([Stage.RUNNING]), then wait for the water to reach the play's doors ([Stage.AWAIT]).
 *
 * A run goes wrong when the board says so - a door outside the play toggles, the water settles or the planned time
 * runs out with doors still missing - or when an observed flip leaves the play's timing (tolerance as in 3.2.2). Then:
 * turn the water off ([Stage.STOP_WATER]), wait for the board to drain ([Stage.DRAIN]), read the doors again and plan
 * new plays from them. Doors keep their state when the board drains, so only the doors read after the drain matter.
 *
 * [guidance] is total: every stage maps to labels, a headline or a status, and only [Stage.SOLVED] is silent.
 */
class WaterSupervisor(val table: ShippedTable) {

    enum class Stage { READING, NO_PLAN, PREPARE, RUNNING, AWAIT, STOP_WATER, DRAIN, FOREIGN, SOLVED }

    enum class Cause { DIVERGENCE, WRONG_CHANNEL, SETTLED_UNSOLVED, EXHAUSTED, FOREIGN_FAILED, WATER_UNCLEAR }

    data class Incident(val serial: Int, val cause: Cause, val text: String, val tick: Int, val divergence: PlanFollower.Divergence? = null)

    /** Notified when a play's water starts and when the run is over (for the recorder and the simulator self-check). */
    interface RunListener {
        fun runStarted(play: ShippedPlay, tick: Int) {}
        fun runEnded(reason: String, tick: Int) {}
    }

    /** False: the 3.2.2 legacy display - follow the first play, never stop the water or restart. */
    var recovery = true
    var toleranceTicks = 20
    var listener: RunListener? = null

    var stage = Stage.READING
        private set
    var chain: List<ShippedPlay> = emptyList()
        private set
    var chainIndex = 0
        private set
    val play: ShippedPlay? get() = chain.getOrNull(chainIndex)
    var follower: PlanFollower? = null
        private set
    var restarts = 0
        private set
    var lastIncident: Incident? = null
        private set
    var drainEta: Int? = null
        private set

    /** Failed plays in a row although no flip left the timing (a sign the levers did not start where we think). */
    var silentFailures = 0
        private set

    private var chainValid = false
    private var normalRun = false
    private var runDoors = 0
    private var runToggled = 0
    private val toggles = IntArray(Door.entries.size)
    private var runStart = - 1
    private var awaitSince = - 1
    private var diverged = false
    private var incidentSerial = 0
    private var now = 0

    private var drainSince = - 1
    private var dryTicks = 0
    private var drainStartWater = - 1
    private var lastDrainWater = - 1
    private var lastDrainProgress = - 1
    private var foreignDry = 0

    private val outbox = ArrayList<String>()

    /** Chat messages produced since the last call. */
    fun drainMessages(): List<String> {
        if (outbox.isEmpty()) return emptyList()
        val out = outbox.toList()
        outbox.clear()
        return out
    }

    // ================================================================================================================
    // Input
    // ================================================================================================================

    /** Processes [events] (in order) and advances to [view]'s tick. Does nothing while the board is not readable. */
    fun update(view: BoardView, events: List<BoardEvent>) {
        if (stage == Stage.SOLVED || ! view.ready) return
        now = view.now
        if (stage == Stage.READING) {
            start(view)
            return
        }
        for (e in events) {
            if (stage == Stage.SOLVED) return
            when (e) {
                is BoardEvent.DoorToggled -> onDoor(view, e)
                is BoardEvent.LeverFlipped -> onLever(view, e)
                is BoardEvent.WaterSwitched -> onWater(view, e)
            }
        }
        if (stage != Stage.SOLVED) tick(view)
    }

    /** Throws away the plan and plans again from the board as it is now (`/lbq water replan`). */
    fun replan(view: BoardView) {
        if (! view.ready || stage == Stage.SOLVED) return
        now = view.now
        follower = null
        chainValid = false
        normalRun = false
        when {
            view.doors == 0 -> solved()
            view.waterOn -> stage = Stage.STOP_WATER
            view.boardWater > 0 -> toDrain()
            else -> newChain(view, boardReady = true)
        }
    }

    private fun start(view: BoardView) {
        when {
            view.doors == 0 -> solved()
            view.waterOn || view.boardWater > 0 -> toForeign()
            else -> {
                newChain(view, boardReady = true)
                normalRun = chain.size == 1 && Door.count(view.doors) == 3 && view.parityUncertain == 0
            }
        }
    }

    private fun onWater(view: BoardView, e: BoardEvent.WaterSwitched) {
        when (stage) {
            Stage.PREPARE -> if (e.on) startRun(view, e)
            Stage.RUNNING, Stage.AWAIT -> {
                if (e.correction) {
                    if (recovery) incident(view, Cause.WATER_UNCLEAR, "the water lever and the board disagree about the water")
                    return
                }
                val f = follower ?: return
                val div = f.onFlip(WaterLever.WATER, e.clickTick, e.source) ?: return
                if (! recovery) f.forceConsume(WaterLever.WATER)
                divergence(view, div)
            }

            Stage.STOP_WATER -> if (! e.on) toDrain()
            Stage.DRAIN -> if (e.on) stage = Stage.STOP_WATER
            else -> {}
        }
    }

    private fun onLever(view: BoardView, e: BoardEvent.LeverFlipped) {
        if (stage != Stage.RUNNING && stage != Stage.AWAIT) return
        // A lever that was already moving when the water started belongs to the start state.
        if (e.tick < runStart) return
        val f = follower ?: return
        val div = f.onFlip(e.lever, e.clickTick, e.source) ?: return
        if (! recovery) f.forceConsume(e.lever)
        divergence(view, div)
    }

    private fun onDoor(view: BoardView, e: BoardEvent.DoorToggled) {
        when (stage) {
            Stage.RUNNING, Stage.AWAIT -> {
                val play = play ?: return
                val d = e.door.digit
                toggles[d] ++
                runToggled = runToggled xor e.door.bit
                if (! recovery) return
                when {
                    play.doors and e.door.bit == 0 ->
                        incident(view, Cause.WRONG_CHANNEL, "water reached the ${e.door.colored}§f channel, which had to stay dry")

                    toggles[d] > 1 -> incident(view, Cause.WRONG_CHANNEL, "water reached the ${e.door.colored}§f channel a second time")
                    runToggled == play.doors -> playDone(view)
                }
            }

            Stage.PREPARE -> if (! view.waterOn && view.boardWater == 0) {
                chainValid = false
                normalRun = false
                newChain(view, boardReady = true)
            }

            else -> {}
        }
    }

    // ================================================================================================================
    // Per tick
    // ================================================================================================================

    private fun tick(view: BoardView) {
        if (view.doors == 0) {
            solved()
            return
        }
        when (stage) {
            Stage.RUNNING -> {
                val f = follower ?: return
                f.toleranceTicks = toleranceTicks
                if (recovery) f.onTick(now)?.let {
                    divergence(view, it)
                    return
                }
                if (f.finished) {
                    stage = Stage.AWAIT
                    awaitSince = now
                }
            }

            Stage.AWAIT -> if (recovery) {
                val f = follower ?: return
                val play = play ?: return
                val missing = Door.names(play.doors and runToggled.inv())
                when {
                    now - view.lastWaterChange >= SETTLE_TICKS && now - awaitSince >= SETTLE_TICKS / 2 ->
                        incident(view, Cause.SETTLED_UNSOLVED, "the water stopped before reaching $missing")

                    now - f.lastPlannedTick > EXHAUST_TICKS ->
                        incident(view, Cause.EXHAUSTED, "all flips are done but the water did not reach $missing")
                }
            }

            Stage.STOP_WATER -> if (! view.waterOn) toDrain()
            Stage.DRAIN -> tickDrain(view)
            Stage.FOREIGN -> tickForeign(view)
            Stage.PREPARE -> if (view.boardWater > 0 && ! view.waterOn) toDrain()
            else -> {}
        }
    }

    private fun tickDrain(view: BoardView) {
        if (view.waterOn) {
            stage = Stage.STOP_WATER
            return
        }
        val w = view.boardWater
        if (w == 0) {
            drainEta = 0
            if (++ dryTicks >= DRY_TICKS) drained(view, stalled = false)
            return
        }
        dryTicks = 0
        if (drainStartWater < 0) drainStartWater = w
        if (lastDrainWater < 0 || w < lastDrainWater) lastDrainProgress = now
        lastDrainWater = w
        val elapsed = now - drainSince
        val done = drainStartWater - w
        drainEta = if (elapsed >= 10 && done > 0) w * elapsed / done else null
        // Water that stopped receding must not block the recovery forever.
        if (now - lastDrainProgress >= STALLED_DRAIN_TICKS) drained(view, stalled = true)
    }

    private fun tickForeign(view: BoardView) {
        if (! view.waterOn && view.boardWater == 0) {
            if (++ foreignDry >= DRY_TICKS) newChain(view, boardReady = true)
            return
        }
        foreignDry = 0
        if (now - view.lastWaterChange < FOREIGN_SETTLE_TICKS) return
        if (view.waterOn) {
            if (recovery) incident(view, Cause.FOREIGN_FAILED, "the run in progress stopped with ${Door.names(view.doors)} still closed")
        }
        else toDrain()
    }

    // ================================================================================================================
    // Transitions
    // ================================================================================================================

    private fun newChain(view: BoardView, boardReady: Boolean) {
        chain = DoorAlgebra.best(view.doors, table, view.parity, boardReady)
        chainIndex = 0
        chainValid = chain.isNotEmpty()
        if (chain.isEmpty()) {
            stage = if (view.doors == 0) Stage.SOLVED else Stage.NO_PLAN
            return
        }
        prepare()
    }

    private fun prepare() {
        stage = Stage.PREPARE
        follower = null
        runStart = - 1
    }

    private fun startRun(view: BoardView, e: BoardEvent.WaterSwitched) {
        val play = play ?: return
        val f = PlanFollower(play, e.clickTick, toleranceTicks)
        // Levers still moving when the water starts count as already moved.
        val div = f.start(view.parity xor view.transit, e.tick)
        if (div != null && recovery) {
            if (e.source != FlipSource.OWN) {
                // A teammate started the water from another lever state: their run, not ours.
                toForeign()
                return
            }
            diverged = true
            follower = f
            stage = Stage.RUNNING
            runStart = e.tick
            listener?.runStarted(play, e.tick)
            divergence(view, div)
            return
        }
        follower = f
        stage = Stage.RUNNING
        runDoors = view.doors
        runToggled = 0
        toggles.fill(0)
        runStart = e.tick
        diverged = false
        listener?.runStarted(play, e.tick)
    }

    private fun playDone(view: BoardView) {
        listener?.runEnded("doors", now)
        silentFailures = 0
        follower = null
        if (view.doors == 0) {
            solved()
            return
        }
        chainIndex ++
        stopWater(view)
    }

    private fun divergence(view: BoardView, div: PlanFollower.Divergence) {
        diverged = true
        if (! recovery) return
        incident(view, Cause.DIVERGENCE, describe(div), div)
    }

    private fun incident(view: BoardView, cause: Cause, text: String, div: PlanFollower.Divergence? = null) {
        val serial = ++ incidentSerial
        lastIncident = Incident(serial, cause, text, now, div)
        restarts ++
        normalRun = false
        chainValid = false
        val boardCause = cause == Cause.SETTLED_UNSOLVED || cause == Cause.EXHAUSTED || cause == Cause.WRONG_CHANNEL
        if (boardCause && ! diverged) silentFailures ++ else if (cause == Cause.DIVERGENCE) silentFailures = 0
        val hint = if (boardCause && ! diverged && view.parityUncertain != 0) {
            " §7(every flip was on time - ${WaterLever.names(view.parityUncertain)} may not have started in their default position)"
        }
        else ""
        val fix = if (view.waterOn) "turn the water §cOFF§f, let the board drain" else "let the board drain"
        outbox += "§cWater Board went wrong: §f$text$hint§7. §fFix: $fix, then follow the new markers."
        listener?.runEnded(cause.name.lowercase(Locale.ROOT), now)
        stopWater(view)
    }

    private fun stopWater(view: BoardView) {
        follower = null
        if (view.waterOn) stage = Stage.STOP_WATER else toDrain()
    }

    private fun toDrain() {
        stage = Stage.DRAIN
        follower = null
        drainSince = now
        dryTicks = 0
        drainStartWater = - 1
        lastDrainWater = - 1
        lastDrainProgress = now
        drainEta = null
    }

    private fun toForeign() {
        stage = Stage.FOREIGN
        follower = null
        foreignDry = 0
    }

    private fun drained(view: BoardView, stalled: Boolean) {
        if (view.doors == 0) {
            solved()
            return
        }
        val rest = chain.drop(chainIndex)
        if (chainValid && rest.isNotEmpty() && rest.fold(0) { acc, p -> acc xor p.doors } == view.doors) prepare()
        else newChain(view, boardReady = ! stalled)
    }

    private fun solved() {
        if (stage == Stage.RUNNING || stage == Stage.AWAIT) listener?.runEnded("solved", now)
        stage = Stage.SOLVED
        follower = null
    }

    // ================================================================================================================
    // Output
    // ================================================================================================================

    /** What to show at the view's tick. Never silent unless solved. */
    fun guidance(view: BoardView): Guidance = when (stage) {
        Stage.SOLVED -> Guidance.Solved
        Stage.READING -> Guidance.Status("§7Water Board: reading the board…")
        Stage.NO_PLAN -> Guidance.Status("§cWater Board: §fno solution for ${Door.names(view.doors)} §7- /lbq water status")
        Stage.FOREIGN -> Guidance.Status("§7Water Board: a run is in progress §8- §7LayBalt takes over if it fails")
        Stage.PREPARE -> prepareGuidance(view)
        Stage.RUNNING -> runningGuidance(view)
        Stage.AWAIT -> {
            val missing = play?.let { Door.names(it.doors and runToggled.inv()) } ?: "the doors"
            Guidance.Act(emptyList(), null, null, "§bWater Board: §fwaiting for the water to reach $missing")
        }

        Stage.STOP_WATER ->
            if (view.waterClickPending) Guidance.Status("§7Water Board: turning the water off…")
            else Guidance.Act(
                listOf(LeverLabel(WaterLever.WATER, "§lWATER OFF", LabelStyle.FIX)), WaterLever.WATER, null,
                "§cWater Board off track §7- §fturn the water §cOFF"
            )

        Stage.DRAIN -> {
            val eta = drainEta?.takeIf { it > 0 }?.let { " ~${seconds(it)}s" } ?: ""
            Guidance.Act(listOf(LeverLabel(WaterLever.WATER, "§lDRAINING…$eta", LabelStyle.FIX_NO_BOX)), null, null, "§eDraining…§f$eta")
        }
    }

    private fun prepareGuidance(view: BoardView): Guidance {
        val play = play ?: return Guidance.Status("§7Water Board: planning…")
        val fixes = (view.parity xor play.startParity) and view.transit.inv() and BoardTracker.ALL_LEVERS
        val n = chain.size
        val labels = ArrayList<LeverLabel>()
        var flipBack = 0
        for (lever in WaterLever.entries) {
            var stack = 0
            if (lever.isBlockLever && fixes and lever.bit != 0) {
                val inSolution = play.preWater[lever.ordinal] % 2 == 1
                labels += when {
                    normalRun && inSolution -> LeverLabel(lever, "§a§lCLICK", LabelStyle.SOLUTION, stack ++)
                    normalRun -> LeverLabel(lever, "§lFLIP BACK", LabelStyle.FIX).also { flipBack = flipBack or lever.bit }
                    else -> LeverLabel(lever, "§lFLIP", LabelStyle.FIX)
                }
            }
            if (lever == WaterLever.WATER && fixes == 0) labels += when {
                normalRun -> LeverLabel(lever, "§a§lCLICK", LabelStyle.SOLUTION, stack ++)
                n > 1 -> LeverLabel(lever, "§lPULL (${chainIndex + 1}/$n)", LabelStyle.FIX)
                else -> LeverLabel(lever, "§lPULL TO RESTART", LabelStyle.FIX)
            }
            // Preview of the timed flips that follow the water.
            val ticks = play.schedule.getValue(lever)
            val timed = if (lever == WaterLever.WATER) ticks.drop(1) else ticks.filter { it > 0 }
            for (t in timed) labels += LeverLabel(lever, timeColor(t) + "${seconds(t)}s", LabelStyle.SOLUTION, stack ++)
        }

        // Tracer to the first lever to flip, line from it to the next one (the water lever once nothing is left).
        val order = WaterLever.blockLevers.filter { fixes and it.bit != 0 } + WaterLever.WATER
        val tracer = order.first()
        val line = order.getOrNull(1)?.let { tracer to it }

        val reclosed = play.doors and view.doors.inv()
        val headline = when {
            normalRun && flipBack != 0 -> "§dFlip back ${WaterLever.names(flipBack, "§7, §d")} §7before pulling the water"
            normalRun -> null
            n > 1 -> "§dRun ${chainIndex + 1}/$n §7(${Door.names(play.doors)}§7)§f: " +
                (if (fixes != 0) "flip the marked levers, then pull the water" else "pull the water") +
                (if (reclosed != 0) " §7- it closes ${Door.names(reclosed)} §7again, that's expected" else "")

            fixes != 0 -> "§dFlip the marked levers§7, then pull the water to restart"
            else -> "§aPull the water lever to restart"
        }
        val warning = if (silentFailures >= 2 && view.parityUncertain != 0) {
            "§eThe last runs failed with every flip on time §7- check ${WaterLever.names(view.parityUncertain)}, or /lbq water dump"
        }
        else null
        return Guidance.Act(labels, tracer, line, listOfNotNull(warning, headline).joinToString(" §8| ").ifEmpty { null })
    }

    private fun runningGuidance(view: BoardView): Guidance {
        val f = follower ?: return Guidance.Status("§7Water Board: following the run…")
        val pending = f.pending()
        val labels = ArrayList<LeverLabel>()
        for (lever in WaterLever.entries) {
            val done = f.consumed(lever)
            f.remaining(lever).forEachIndexed { i, t ->
                val left = f.anchor + t - view.now
                val text = if (left > 0) timeColor(left) + "${seconds(left)}s" else "§a§lCLICK"
                labels += LeverLabel(lever, text, LabelStyle.SOLUTION, done + i)
            }
        }
        val tracer = pending.firstOrNull()?.lever
        val second = pending.getOrNull(1)?.lever
        val line = if (tracer != null && second != null && second != tracer) tracer to second else null
        val headline = if (chain.size > 1) "§dRun ${chainIndex + 1}/${chain.size} §7- follow the timers" else null
        if (labels.isEmpty() && headline == null) return Guidance.Act(emptyList(), null, null, "§bWater Board: §fwaiting for the water")
        return Guidance.Act(labels, tracer, line, headline)
    }

    companion object {
        /** Board unchanged this long (with no planned flips left): the water settled. */
        const val SETTLE_TICKS = 40

        /** After the last planned flip: the doors must have toggled by then. */
        const val EXHAUST_TICKS = 160

        /** A run we did not start is given this long without any water movement before we take over. */
        const val FOREIGN_SETTLE_TICKS = 200

        /** Ticks the board has to stay dry before the next play is prepared (3.2.2 value). */
        const val DRY_TICKS = 10

        /** Drain without progress for this long: go on anyway (3.2.2 value). */
        const val STALLED_DRAIN_TICKS = 300

        fun seconds(ticks: Int): String = String.format(Locale.ROOT, "%.1f", ticks / 20.0)

        fun timeColor(ticks: Int) = when {
            ticks < 40 -> "§c"
            ticks < 120 -> "§e"
            else -> "§a"
        }

        fun describe(div: PlanFollower.Divergence): String {
            val water = div.lever == WaterLever.WATER
            val name = "§d${div.lever.displayName}§f"
            val offset = seconds(div.offset)
            val text = when (div.kind) {
                Kind.WRONG_LEVER -> "$name was flipped, but this solution never uses it"
                Kind.EXTRA -> if (water) "the water lever was flipped once too often" else "$name was flipped once too often"
                Kind.EARLY -> if (water) "the water was turned off §e${offset}s§f too early" else "$name was flipped §e${offset}s§f too early"
                Kind.MISSED -> if (water) "the water was not turned off in time (§e${offset}s§f late)" else "$name was not flipped in time (§e${offset}s§f late)"
                Kind.START_STATE -> "the water was started with $name in the wrong position"
            }
            val who = when (div.source) {
                FlipSource.OWN -> ""
                FlipSource.OTHER -> if (div.kind == Kind.MISSED || div.kind == Kind.START_STATE) "" else " §7(by a teammate)§f"
                FlipSource.DOUBLE -> " §7(one click flipped it twice - avoid shift-clicking levers)§f"
            }
            return text + who
        }
    }
}
