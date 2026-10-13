package com.laybalt.features.impl.dungeon.solvers.puzzles.water

import com.laybalt.features.impl.dungeon.solvers.puzzles.water.PlanFollower.Kind
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/**
 * Drives the Water Board from the real board state. Pure: it reads a [BoardView], reacts to [BoardEvent]s and answers
 * with [Guidance], chat messages and requests for the planner thread.
 *
 * Plan: the shipped plays whose door sets XOR to the doors that are closed right now ([DoorAlgebra]); one play for an
 * untouched board. Each play: flip the levers whose parity differs from the play's start ([Stage.PREPARE]), pull the
 * water, follow the timed flips ([Stage.RUNNING]), then wait for the water to reach the play's doors ([Stage.AWAIT]).
 *
 * A run goes wrong when the board says so - a door outside the play toggles, the water settles or the planned time
 * runs out with doors still missing - or when an observed flip leaves the play's timing (tolerance as in 3.2.2), or
 * (with a trusted simulator, [sim]) when simulating the rest of the run from the board says it will miss. Flips off the
 * schedule that are a teammate's (their own timings, another mod's solution) are not failures: the run becomes theirs
 * ([Stage.FOREIGN]) and is only taken over if it stalls. Then:
 * - with simulator support, it asks the planner ([requests]) for a way to finish from the board as it is
 *   ([Stage.PLANNING]) and follows that plan's clicks ([Stage.CONTINUE]);
 * - otherwise, or when no plan beats a restart: turn the water off ([Stage.STOP_WATER]), wait for the board to drain
 *   ([Stage.DRAIN]), read the doors again and plan new plays from them. Doors keep their state when the board drains, so
 *   only the doors read after the drain matter. With a trusted simulator, door states that need two or three shipped
 *   plays can be opened by one static lever setting instead ([SimSupport.staticPlay]).
 *
 * [guidance] is total: every stage maps to labels, a headline or a status, and only [Stage.SOLVED] is silent.
 */
class WaterSupervisor(val table: ShippedTable) {

    enum class Stage { READING, NO_PLAN, PREPARE, RUNNING, AWAIT, PLANNING, CONTINUE, STOP_WATER, DRAIN, FOREIGN, SOLVED }

    enum class Cause { DIVERGENCE, WRONG_CHANNEL, SETTLED_UNSOLVED, EXHAUSTED, FOREIGN_FAILED, WATER_UNCLEAR, PREDICTED_FAIL }

    data class Incident(val serial: Int, val cause: Cause, val text: String, val tick: Int, val divergence: PlanFollower.Divergence? = null)

    /** Notified when a play's water starts and when the run is over (for the recorder and the simulator self-check). */
    interface RunListener {
        fun runStarted(play: ShippedPlay, tick: Int) {}
        fun runEnded(reason: String, tick: Int) {}
    }

    /** What the simulator side offers right now (set by the session once the calibration gate has run). */
    interface SimSupport {
        /** Plans from the live board may be requested. */
        fun continuationsAllowed(): Boolean

        /** The run may be checked against the simulation. */
        fun checksAllowed(): Boolean

        /** One static lever setting (as a synthetic play) that opens exactly [closed] from a dry board, or null. */
        fun staticPlay(closed: Int, parity: Int): ShippedPlay?
    }

    /** Work for the planner thread. Answers come back through [onPlanResult] / [onCheckResult] with the same [id]. */
    sealed interface Request {
        val id: Int
    }

    /** Find a way to finish from the board as it is. [remaining]: clicks of the plan being followed (click frame). */
    data class PlanNeeded(override val id: Int, val remaining: List<PlanStep>?, val fault: PlanFault?) : Request

    /** Does the rest of the current plan still open every closed door? */
    data class CheckNeeded(override val id: Int, val remaining: List<PlanStep>) : Request

    /** False: the 3.2.2 legacy display - follow the first play, never stop the water or restart. */
    var recovery = true
    var toleranceTicks = 20
    var listener: RunListener? = null
    var sim: SimSupport? = null

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

    /** The plan being followed in [Stage.CONTINUE]. */
    var continuation: ContinuationPlan? = null
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

    /** Water cells that stayed on the board through a whole drain (stuck water): later drains stop at this count. */
    private var residualWater = 0

    /** First tick the board read with every door open before anything else happened (-1: not so far). */
    private var allOpenSince = - 1

    /** A static one-run play (simulator-made) failed in this room: restarts use the shipped plays from then on. */
    var staticFailed = false
        private set

    /** The current run's water was started by someone else (a teammate may be playing their own timings). */
    private var teamRun = false

    /** Flips of ours seen in the current run (we take part in it). */
    private var ownFlips = 0

    /** Whether the water is being turned off between two plays of a chain (not because something went wrong). */
    private var betweenPlays = false

    // planning and continuation
    private var planId = 0
    private var planSince = - 1
    private var planText = ""
    private var planHint = ""
    private var replans = 0
    private var lateRetries = 0
    private var stepFollower: StepFollower? = null
    private var contToggled = 0
    private var contStart = - 1
    private var checkId = 0
    private var checkTick = Int.MIN_VALUE / 2
    private var checkFails = 0
    private var lastBoardEvent = Int.MIN_VALUE / 2

    /** What the pending plan request is about: the clicks still to come and the fault, kept up to date while planning. */
    private var planRemaining: MutableList<PlanStep>? = null
    private var planFault: PlanFault? = null

    private val outbox = ArrayList<String>()
    private val requests = ArrayList<Request>()

    /** Chat messages produced since the last call. */
    fun drainMessages(): List<String> {
        if (outbox.isEmpty()) return emptyList()
        val out = outbox.toList()
        outbox.clear()
        return out
    }

    /** Planner work produced since the last call. */
    fun drainRequests(): List<Request> {
        if (requests.isEmpty()) return emptyList()
        val out = requests.toList()
        requests.clear()
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
            lastBoardEvent = now
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
        continuation = null
        stepFollower = null
        chainValid = false
        normalRun = false
        replans = 0
        betweenPlays = false
        when {
            view.doors == 0 -> solved()
            tryPlan(view, "planning again on request", "", null) -> {}
            view.waterOn -> stage = Stage.STOP_WATER
            view.boardWater > residualWater -> toDrain()
            else -> newChain(view, boardReady = true)
        }
    }

    /** The planner's answer to [PlanNeeded] [id] (null: nothing beats a restart). */
    fun onPlanResult(id: Int, plan: ContinuationPlan?, view: BoardView) {
        if (stage != Stage.PLANNING || id != planId || ! view.ready) return
        now = view.now
        val first = plan?.steps?.firstOrNull()
        if (plan != null && first != null && first.clickTick < now - LATE_START_TICKS && lateRetries < MAX_LATE_RETRIES) {
            // The plan came too late for its own first click: plan again from the board as it is now.
            lateRetries ++
            planSince = now
            replanRequest()
            return
        }
        if (plan == null || first != null && first.clickTick < now - LATE_START_TICKS) {
            fallback(view)
            return
        }
        continuation = plan
        stepFollower = StepFollower(plan.steps, toleranceTicks)
        contToggled = 0
        contStart = now
        follower = null
        checkFails = 0
        checkTick = now
        stage = Stage.CONTINUE
        outbox += "${messagePrefix()}$planText$planHint§7. §fFix: §a${plan.summary}§7."
    }

    /** The planner's answer to [CheckNeeded] [id]: [ok] = the rest of the plan still works, else why not. */
    fun onCheckResult(id: Int, ok: Boolean, reason: String?, view: BoardView) {
        if (id != checkId || ! view.ready) return
        if (stage != Stage.RUNNING && stage != Stage.AWAIT && stage != Stage.CONTINUE) return
        // A flip or door change since the check was asked for: the board it simulated is gone.
        if (lastBoardEvent > checkTick) return
        now = view.now
        if (ok) {
            checkFails = 0
            return
        }
        if (++ checkFails < CHECK_FAILS_NEEDED) return
        checkFails = 0
        incident(view, Cause.PREDICTED_FAIL, reason ?: "the water is not going to reach ${Door.names(view.doors)}")
    }

    private fun start(view: BoardView) {
        if (view.doors == 0) {
            // A first read with every door open may just be a room whose doors are not shown yet (3.2.2 and other
            // solvers wait for three closed doors): read on for a while before calling the puzzle solved.
            if (allOpenSince < 0) allOpenSince = now
            if (now - allOpenSince >= ALL_OPEN_CONFIRM_TICKS) solved()
            return
        }
        when {
            view.waterOn || view.boardWater > 0 -> toForeign()
            else -> {
                newChain(view, boardReady = true)
                normalRun = chain.size == 1 && ! chain[0].synthetic && Door.count(view.doors) == 3 && view.parityUncertain == 0
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
                if (e.source != FlipSource.OTHER) ownFlips ++
                val div = f.onFlip(WaterLever.WATER, e.clickTick, e.source) ?: return
                if (! recovery) f.forceConsume(WaterLever.WATER)
                divergence(view, div)
            }

            Stage.CONTINUE -> {
                if (e.correction) {
                    incident(view, Cause.WATER_UNCLEAR, "the water lever and the board disagree about the water")
                    return
                }
                val f = stepFollower ?: return
                f.onFlip(WaterLever.WATER, e.clickTick, e.source)?.let { divergence(view, it) }
            }

            Stage.PLANNING -> {
                if (! e.correction) planFlipSeen(WaterLever.WATER, e.clickTick)
                replanRequest()
            }

            Stage.STOP_WATER -> if (! e.on) toDrain()
            Stage.DRAIN -> if (e.on) stage = Stage.STOP_WATER
            else -> {}
        }
    }

    private fun onLever(view: BoardView, e: BoardEvent.LeverFlipped) {
        when (stage) {
            Stage.RUNNING, Stage.AWAIT -> {
                // A lever that was already moving when the water started belongs to the start state.
                if (e.tick < runStart) return
                val f = follower ?: return
                if (e.source != FlipSource.OTHER) ownFlips ++
                val div = f.onFlip(e.lever, e.clickTick, e.source) ?: return
                if (! recovery) f.forceConsume(e.lever)
                divergence(view, div)
            }

            Stage.CONTINUE -> {
                val f = stepFollower ?: return
                f.onFlip(e.lever, e.clickTick, e.source)?.let { divergence(view, it) }
            }

            Stage.PLANNING -> {
                planFlipSeen(e.lever, e.clickTick)
                replanRequest()
            }

            else -> {}
        }
    }

    private fun onDoor(view: BoardView, e: BoardEvent.DoorToggled) {
        when (stage) {
            Stage.RUNNING, Stage.AWAIT -> {
                val play = play ?: return
                val d = e.door.digit
                toggles[d] ++
                runToggled = runToggled xor e.door.bit
                if (! recovery) {
                    // The legacy display does not judge the run, but a chain still moves on to its next play.
                    if (runToggled == play.doors) playDone(view)
                    return
                }
                when {
                    play.doors and e.door.bit == 0 ->
                        incident(view, Cause.WRONG_CHANNEL, "water reached the ${e.door.colored}§f channel, which had to stay dry")

                    toggles[d] > 1 -> incident(view, Cause.WRONG_CHANNEL, "water reached the ${e.door.colored}§f channel a second time")
                    runToggled == play.doors -> playDone(view)
                }
            }

            Stage.CONTINUE -> {
                val plan = continuation ?: return
                if (plan.doors and e.door.bit != 0 && contToggled and e.door.bit == 0) {
                    contToggled = contToggled or e.door.bit
                    replans = 0
                }
                else incident(view, Cause.WRONG_CHANNEL, "water reached the ${e.door.colored}§f channel, which had to stay dry")
            }

            Stage.PLANNING -> replanRequest()
            Stage.PREPARE -> if (! view.waterOn && view.boardWater <= residualWater) {
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
                // Our clicks show on the board [BoardView.flipLag] ticks later: judge the plan only up to the clicks we can see.
                if (recovery) f.onTick(now - view.flipLag)?.let {
                    divergence(view, it)
                    return
                }
                if (f.finished) {
                    stage = Stage.AWAIT
                    awaitSince = now
                }
                else maybeCheck()
            }

            Stage.AWAIT -> if (recovery) {
                val f = follower ?: return
                val play = play ?: return
                val missing = Door.names(play.doors and runToggled.inv())
                val deadline = awaitDeadline(f, play)
                when {
                    now - view.lastWaterChange >= SETTLE_TICKS && now - awaitSince >= SETTLE_TICKS / 2 ->
                        incident(view, Cause.SETTLED_UNSOLVED, "the water stopped before reaching $missing")

                    now > deadline -> incident(view, Cause.EXHAUSTED, "all flips are done but the water did not reach $missing")
                    else -> maybeCheck()
                }
            }

            Stage.PLANNING -> if (now - planSince > PLAN_WAIT_TICKS) fallback(view)
            Stage.CONTINUE -> tickContinue(view)
            Stage.STOP_WATER -> if (! view.waterOn) toDrain()
            Stage.DRAIN -> tickDrain(view)
            Stage.FOREIGN -> tickForeign(view)
            Stage.PREPARE -> if (view.boardWater > residualWater && ! view.waterOn) toDrain()
            else -> {}
        }
    }

    private fun tickContinue(view: BoardView) {
        val plan = continuation ?: return fallback(view)
        val f = stepFollower ?: return fallback(view)
        f.toleranceTicks = toleranceTicks
        f.onTick(now - view.flipLag)?.let {
            divergence(view, it)
            return
        }
        val deadline = max(plan.predictedEnd, (f.lastClick ?: contStart) + view.flipLag) + CONTINUE_GRACE
        when {
            f.finished && now > deadline ->
                incident(view, Cause.EXHAUSTED, "the fix ran out of time with ${Door.names(view.doors)} still closed")

            f.finished && now - view.lastWaterChange >= SETTLE_TICKS && now - contStart >= SETTLE_TICKS ->
                incident(view, Cause.SETTLED_UNSOLVED, "the water stopped before reaching ${Door.names(view.doors)}")

            else -> maybeCheck()
        }
    }

    private fun tickDrain(view: BoardView) {
        if (view.waterOn) {
            stage = Stage.STOP_WATER
            return
        }
        val w = view.boardWater
        if (w == 0) residualWater = 0
        if (w <= residualWater) {
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
        if (! view.waterOn && view.boardWater <= residualWater) {
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
    // Planning
    // ================================================================================================================

    /** The clicks still to come in the current plan (click frame). */
    private fun currentRemaining(): List<PlanStep> = when (stage) {
        Stage.CONTINUE -> stepFollower?.remaining().orEmpty()
        Stage.RUNNING, Stage.AWAIT -> follower?.let { f -> f.pending().map { PlanStep(it.lever, f.anchor + it.tick) } }.orEmpty()
        else -> emptyList()
    }

    /** After this the run is out of time: every planned flip is done and the doors had long enough to toggle. */
    private fun awaitDeadline(f: PlanFollower, play: ShippedPlay) = f.anchor + max(play.lastClickTick, play.expectedTicks ?: 0) + EXHAUST_TICKS

    /** Asks the planner for a way to finish from the board as it is; false when that is not possible here. */
    private fun tryPlan(view: BoardView, text: String, hint: String, fault: PlanFault?): Boolean {
        val s = sim ?: return false
        if (! recovery || ! s.continuationsAllowed()) return false
        // Nothing moving on the board: there is nothing to steer, a restart it is.
        if (! view.waterOn && view.boardWater <= residualWater) return false
        if (replans >= MAX_REPLANS) return false
        replans ++
        lateRetries = 0
        val remaining = currentRemaining()
        follower = null
        continuation = null
        stepFollower = null
        stage = Stage.PLANNING
        planSince = now
        planText = text
        planHint = hint
        planId ++
        planRemaining = remaining.toMutableList()
        planFault = fault
        requests += PlanNeeded(planId, remaining, fault)
        return true
    }

    /**
     * The board changed while the planner was working: ask again for the board as it is now, still with the plan being
     * repaired and its fault (adjusted for the flips seen since), so repairs of the run stay on the table.
     */
    private fun replanRequest() {
        planId ++
        requests += PlanNeeded(planId, planRemaining?.toList(), planFault)
    }

    /**
     * A flip of [lever] (click at [click]) seen while planning: a planned click it matches is done, and so is the faulty
     * lever's next click once that lever moves (a late click of a missed flip); the fault is then settled.
     */
    private fun planFlipSeen(lever: WaterLever, click: Int) {
        val steps = planRemaining ?: return
        val fault = planFault
        val faulty = fault?.lever == lever
        val i = steps.indexOfFirst {
            it.lever == lever && (abs(it.clickTick - click) <= toleranceTicks || faulty && fault.kind == Kind.MISSED)
        }
        if (i >= 0) steps.removeAt(i)
        if (faulty) planFault = null
    }

    /** No plan (or none in time): turn the water off, drain and restart. */
    private fun fallback(view: BoardView) {
        betweenPlays = false
        continuation = null
        stepFollower = null
        announce(view, planText.ifEmpty { "the run went off track" }, planHint)
        stopWater(view)
    }

    private fun maybeCheck() {
        val s = sim ?: return
        if (! recovery || ! s.checksAllowed()) return
        if (now - checkTick < CHECK_INTERVAL) return
        checkTick = now
        checkId ++
        requests += CheckNeeded(checkId, currentRemaining())
    }

    // ================================================================================================================
    // Transitions
    // ================================================================================================================

    private fun newChain(view: BoardView, boardReady: Boolean) {
        // A static play is the simulator's prediction; once one failed here, only the shipped plays (known to work) are used.
        val static = if (recovery && ! staticFailed && Door.count(view.doors) != 3) sim?.staticPlay(view.doors, view.parity) else null
        chain = if (static != null) listOf(static) else DoorAlgebra.best(view.doors, table, view.parity, boardReady)
        chainIndex = 0
        chainValid = chain.isNotEmpty()
        continuation = null
        stepFollower = null
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
        teamRun = e.source == FlipSource.OTHER
        ownFlips = 0
        runDoors = view.doors
        runToggled = 0
        toggles.fill(0)
        runStart = e.tick
        diverged = false
        checkTick = now
        checkFails = 0
        listener?.runStarted(play, e.tick)
    }

    private fun playDone(view: BoardView) {
        listener?.runEnded("doors", now)
        silentFailures = 0
        replans = 0
        follower = null
        if (view.doors == 0) {
            solved()
            return
        }
        chainIndex ++
        stopWater(view)
        betweenPlays = true
    }

    private fun divergence(view: BoardView, div: PlanFollower.Divergence) {
        diverged = true
        if (! recovery) return
        if ((stage == Stage.RUNNING || stage == Stage.AWAIT) && teammates(div)) {
            // Someone else is playing this run on their own timings (another mod's solution, a memorised one): a flip
            // off our schedule is not a failure. Their run is watched like any other and taken over if it fails.
            listener?.runEnded("teammate", now)
            toForeign()
            return
        }
        incident(view, Cause.DIVERGENCE, describe(div), div)
    }

    /**
     * The divergence comes from a teammate's play, not ours: a flip that is not ours, or a missed flip in a run a teammate
     * started and we never flipped in.
     */
    private fun teammates(div: PlanFollower.Divergence) = when (div.kind) {
        Kind.MISSED -> teamRun && ownFlips == 0
        Kind.START_STATE -> false
        else -> div.source == FlipSource.OTHER
    }

    private fun incident(view: BoardView, cause: Cause, text: String, div: PlanFollower.Divergence? = null) {
        if (play?.synthetic == true && (stage == Stage.RUNNING || stage == Stage.AWAIT || stage == Stage.CONTINUE)) staticFailed = true
        betweenPlays = false
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
        listener?.runEnded(cause.name.lowercase(Locale.ROOT), now)
        if (tryPlan(view, text, hint, div?.let { PlanFault(it.lever, it.kind) })) return
        announce(view, text, hint)
        stopWater(view)
    }

    private fun announce(view: BoardView, text: String, hint: String) {
        val fix = if (view.waterOn) "turn the water §cOFF§f, let the board drain" else "let the board drain"
        outbox += "${messagePrefix()}$text$hint§7. §fFix: $fix, then follow the new markers."
    }

    /** A run the simulation says will fail has not gone wrong yet: it is off track. */
    private fun messagePrefix() =
        if (lastIncident?.cause == Cause.PREDICTED_FAIL) "§eWater Board off track: §f" else "§cWater Board went wrong: §f"

    private fun stopWater(view: BoardView) {
        follower = null
        continuation = null
        stepFollower = null
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
        if (stalled) residualWater = view.boardWater
        replans = 0
        if (view.doors == 0) {
            solved()
            return
        }
        val rest = chain.drop(chainIndex)
        if (chainValid && rest.isNotEmpty() && rest.fold(0) { acc, p -> acc xor p.doors } == view.doors) prepare()
        else newChain(view, boardReady = ! stalled)
    }

    private fun solved() {
        if (stage == Stage.RUNNING || stage == Stage.AWAIT || stage == Stage.CONTINUE) listener?.runEnded("solved", now)
        stage = Stage.SOLVED
        follower = null
        continuation = null
        stepFollower = null
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
        Stage.AWAIT -> awaitGuidance(view)

        Stage.PLANNING -> Guidance.Status("§eWater Board: §fworking out a fix from the board…")
        Stage.CONTINUE -> continueGuidance(view)
        Stage.STOP_WATER ->
            if (view.waterClickPending) Guidance.Status("§7Water Board: turning the water off…")
            else Guidance.Act(
                listOf(LeverLabel(WaterLever.WATER, "§lWATER OFF", LabelStyle.FIX)), WaterLever.WATER, null,
                if (betweenPlays) "§dRun $chainIndex/${chain.size} done §7- §fturn the water §cOFF§f for the next run"
                else "§cWater Board off track §7- §fturn the water §cOFF"
            )

        Stage.DRAIN -> {
            val eta = drainEta?.takeIf { it > 0 }?.let { " ~${seconds(it)}s" } ?: ""
            Guidance.Act(listOf(LeverLabel(WaterLever.WATER, "§lDRAINING…$eta", LabelStyle.FIX_NO_BOX)), null, null, "§eDraining…§f$eta")
        }
    }

    private fun awaitGuidance(view: BoardView): Guidance {
        val play = play
        val missingDoors = play?.let { it.doors and runToggled.inv() } ?: 0
        val f = follower
        // The legacy display does not judge runs: once the run is out of time it says so instead of waiting forever.
        if (! recovery && play != null && f != null && view.now > awaitDeadline(f, play)) {
            return Guidance.Status("§7Water Board: the run ended with ${Door.names(view.doors)} §7closed §8- §7Reset Water to start over")
        }
        val target = if (missingDoors != 0) Door.names(missingDoors) else "the doors"
        return Guidance.Act(emptyList(), null, null, "§bWater Board: §fwaiting for the water to reach $target")
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
                play.synthetic -> LeverLabel(lever, "§lPULL", LabelStyle.FIX)
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
            play.synthetic -> "§d" + (if (fixes != 0) "Flip the marked levers, then pull the water" else "Pull the water") +
                " §7- one run opens ${Door.names(play.doors)}"

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

    private fun continueGuidance(view: BoardView): Guidance {
        val plan = continuation ?: return Guidance.Status("§eWater Board: §fworking out a fix from the board…")
        val f = stepFollower ?: return Guidance.Status("§eWater Board: §fworking out a fix from the board…")
        val remaining = f.remaining()
        val labels = ArrayList<LeverLabel>()
        val stack = IntArray(WaterLever.entries.size)
        for (lever in WaterLever.entries) stack[lever.ordinal] = f.consumed(lever)
        for (step in remaining) {
            val left = step.clickTick - view.now
            val text = if (left > 0) timeColor(left) + "${seconds(left)}s" else "§a§lCLICK"
            labels += LeverLabel(step.lever, text, LabelStyle.SOLUTION, stack[step.lever.ordinal] ++)
        }
        val tracer = remaining.firstOrNull()?.lever
        val second = remaining.firstOrNull { it.lever != tracer }?.lever
        val line = if (tracer != null && second != null) tracer to second else null
        val missing = Door.names(plan.doors and contToggled.inv())
        val headline = if (remaining.isEmpty()) "§bWater Board: §fwaiting for the water to reach $missing"
        else "§eFixing the run: §f${plan.summary}"
        return Guidance.Act(labels, tracer, line, headline)
    }

    companion object {
        /** Board unchanged this long (with no planned flips left): the water settled. */
        const val SETTLE_TICKS = 40

        /** A first read with every door open must hold this long before the puzzle counts as solved. */
        const val ALL_OPEN_CONFIRM_TICKS = 100

        /** After the last planned flip: the doors must have toggled by then. */
        const val EXHAUST_TICKS = 160

        /** A run we did not start is given this long without any water movement before we take over. */
        const val FOREIGN_SETTLE_TICKS = 200

        /** Ticks the board has to stay dry before the next play is prepared (3.2.2 value). */
        const val DRY_TICKS = 10

        /** Drain without progress for this long: go on anyway (3.2.2 value). */
        const val STALLED_DRAIN_TICKS = 300

        /** The planner gets this long before the drain-and-restart fallback is shown instead. */
        const val PLAN_WAIT_TICKS = 30

        /** A plan whose first click is this far in the past when it arrives is not used. */
        const val LATE_START_TICKS = 4

        /** A plan that arrives too late is asked for again this often before the fallback is used. */
        const val MAX_LATE_RETRIES = 3

        /** Plans in a row without a door opening before the fallback is used for good. */
        const val MAX_REPLANS = 3

        /** A computed plan's doors must have opened this long after its predicted end. */
        const val CONTINUE_GRACE = 60

        /** Ticks between two checks of the run against the simulation. */
        const val CHECK_INTERVAL = 10

        /** Failed checks in a row before the run is called off. */
        const val CHECK_FAILS_NEEDED = 2

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
