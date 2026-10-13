package com.laybalt.features.impl.dungeon.solvers.puzzles.water

/**
 * One Water Board room without Minecraft (client thread only): the board-truth side ([tracker], [supervisor] and the
 * simulator side behind [Hooks]), the 3.2.3 baseline ([classic]) and the choice of what the player sees ([display]).
 *
 * - **Baseline first.** The classic display runs every tick on its own reads of the door blocks and on our own clicks,
 *   and it leads: the first run of a room is always the shipped timed solution, as in 3.2.3 (the board-truth run
 *   following has not been proven on Hypixel yet). The board-truth guidance may take over only for recovery, once the
 *   classic display's own door reads show something it cannot follow ([ClassicDisplay.departures]: our run over with
 *   doors still closed, or doors changed by a run we did not start), when the classic display has no play, after
 *   `/lbq water replan`, or with "Water Board-Reading First" on - and never with "Water Recovery Guidance" off. Even
 *   then it is shown only while its inputs agree with the door blocks and nothing is in doubt ([advancedProblem] is
 *   null): the board is loaded and read, a door has closed, the layout and the doors match what the classic display
 *   reads. Otherwise the classic display is shown - never nothing. The supervisor's chat messages reach the player only
 *   while its guidance is the one shown.
 * - **Never silent.** While the room is unsolved the display is labels, a headline or a status.
 * - **Isolation.** An exception anywhere in the board-truth side is reported once ([Hooks.failed]) and switches that
 *   side off for the room; the baseline goes on. The baseline has its own guard.
 * - **Trace.** What the board did and what was shown goes to [trace] (the recorder writes it to a file).
 */
class WaterRoomCore(private val tables: Map<Int, ShippedTable>?, private val hooks: Hooks = Hooks.NONE) : WaterSupervisor.RunListener {

    /** What the Minecraft side plugs in: the simulator bridge, the recorder and error logging. */
    interface Hooks {
        /** The board's layout was read (the supervisor exists if [table] does): calibrate, dump the room. */
        fun layoutCaptured(core: WaterRoomCore, table: ShippedTable?) {}

        /** The tracker's events of this tick, before the supervisor sees them. */
        fun events(core: WaterRoomCore, events: List<BoardEvent>) {}

        /** Planner work the supervisor asked for this tick. */
        fun requests(core: WaterRoomCore, requests: List<WaterSupervisor.Request>, sup: WaterSupervisor) {}

        /** After the supervisor's update, while the board is readable (shadow check, re-calibration). */
        fun afterUpdate(core: WaterRoomCore, solved: Boolean) {}

        /** What the simulator side offers the supervisor (null: board truth only). */
        val simSupport: WaterSupervisor.SimSupport? get() = null

        /** Something threw; [what] says where. Called once per side and room. */
        fun failed(what: String, error: Throwable) {}

        companion object {
            val NONE = object : Hooks {}
        }
    }

    enum class Mode { STATUS, CLASSIC, ADVANCED, SOLVED }

    val tracker = BoardTracker()
    var supervisor: WaterSupervisor? = null
        private set
    val classic = ClassicDisplay(tables)
    val trace = WaterTrace()

    /** Settings, set by the session every tick. */
    var recovery = true
    var toleranceTicks = 20

    /** "Water Board-Reading First": the board-truth guidance may lead from the first run on (with [recovery]). */
    var advancedFirst = false

    /** `/lbq water replan` was used: the player asked for the board-truth guidance. */
    private var leadRequested = false

    var now = 0
        private set

    /** Room frame known and every chunk of the board loaded. */
    var available = false
        private set

    /** What to show while the player is in or near the room. */
    var display: Guidance = Guidance.Status(WaterSupervisor.WAITING_FOR_DOORS)
        private set
    var mode = Mode.STATUS
        private set

    /** Why the board-truth guidance is not shown right now (null: it is). */
    var advancedProblem: String? = "not started"
        private set

    /** The board-truth side threw and is off for this room. */
    var advancedFailed = false
        private set
    private var classicFailed = false

    /** The map or the reward chest said the puzzle is done. */
    var confirmedSolved = false
        private set

    /** The supervisor's guidance of this tick (null without a supervisor or after a failure). */
    var advancedGuidance: Guidance? = null
        private set

    private var doorsDisagreeSince = - 1
    private var lastStage: WaterSupervisor.Stage? = null
    private var entryWet = false
    private var outletWet = 0
    private val messages = ArrayList<String>()
    private val supervisorMessages = ArrayList<String>()
    private val logLines = ArrayList<String>()

    // ================================================================================================================
    // Input
    // ================================================================================================================

    /**
     * One server tick. The session has read the board into [tracker] already (when [available]); [gates] is this tick's
     * own read of the door blocks for the classic display (null when not loaded) and [indicator] the variant from the
     * indicator blocks (null when not read).
     */
    fun tick(now: Int, available: Boolean, gates: GateRead?, indicator: Int?) {
        this.now = now
        if (available != this.available) trace.add(now, if (available) "board readable" else "board not readable (its chunks are not loaded)")
        this.available = available
        if (! advancedFailed) advanced("tick") { advancedTick(available) }
        if (advancedFailed) advancedGuidance = null
        val classicGuidance = classicPart { classicTick(gates, indicator) }
        choose(classicGuidance)
        forwardMessages()
    }

    /** The supervisor's messages go to chat only while its guidance is shown; otherwise they are only logged. */
    private fun forwardMessages() {
        if (supervisorMessages.isEmpty()) return
        for (m in supervisorMessages) {
            if (mode == Mode.ADVANCED) messages += m
            else logLines += "not shown (display ${mode.name.lowercase()}): ${plain(m)}"
        }
        supervisorMessages.clear()
    }

    /** The room's rotation is not known yet: nothing can be read. */
    fun noFrame(now: Int) {
        this.now = now
        mode = Mode.STATUS
        display = Guidance.Status("§7Water Board: waiting for the room scan…")
    }

    /** One of our clicks on [lever] (both sides use it: attribution on the board, click counting in the classic). */
    fun onClick(lever: WaterLever, tick: Int) {
        trace.add(tick, "our click on ${lever.displayName}")
        if (! advancedFailed) advanced("click") { tracker.ownClick(lever, tick) }
        classicPart {
            classic.onClick(lever, tick)
            null
        }
    }

    /** The map turned the room green or the reward chest opened: done for good. */
    fun markSolved(reason: String) {
        if (confirmedSolved) return
        confirmedSolved = true
        trace.add(now, "solved ($reason)")
        logLines += "solved ($reason)"
        if (! advancedFailed) advanced("solved") { supervisor?.confirmSolved() }
        mode = Mode.SOLVED
        display = Guidance.Solved
    }

    /** `/lbq water replan`: forget the plan and plan again from the board as it is. */
    fun replan() {
        if (advancedFailed) return
        leadRequested = true
        advanced("replan") {
            val sup = supervisor ?: return@advanced
            sup.replan(tracker)
            hooks.requests(this, sup.drainRequests(), sup)
            advancedGuidance = sup.guidance(tracker)
        }
        trace.add(now, "re-plan on request")
    }

    /** Chat messages of the recovery since the last call. */
    fun drainMessages(): List<String> = drain(messages)

    /** Lines for the game log since the last call. */
    fun drainLog(): List<String> = drain(logLines)

    /** Runs a piece of the board-truth side; an exception switches that side off for the room (reported once). */
    fun advanced(what: String, block: () -> Unit) {
        if (advancedFailed) return
        try {
            block()
        }
        catch (e: Exception) {
            advancedFailed = true
            advancedGuidance = null
            trace.add(now, "board-truth side failed in $what: $e - the classic display takes over")
            hooks.failed("board-truth $what", e)
        }
    }

    // ================================================================================================================
    // Per tick
    // ================================================================================================================

    private fun advancedTick(available: Boolean) {
        tracker.available = available
        if (available && ! tracker.layoutCaptured && tracker.captureLayout()) onLayoutCaptured()
        tracker.update(now)
        val events = tracker.drainEvents()
        for (note in tracker.drainNotes()) {
            logLines += note
            trace.add(now, "note: $note")
        }
        for (e in events) trace.add(now, WaterTrace.describe(e))
        if (available && tracker.layoutCaptured) traceWater()
        hooks.events(this, events)

        val sup = supervisor
        if (sup != null) {
            sup.recovery = recovery
            sup.toleranceTicks = toleranceTicks
            sup.update(tracker, events)
            hooks.requests(this, sup.drainRequests(), sup)
            for (m in sup.drainMessages()) {
                supervisorMessages += m
                trace.add(now, "message: ${plain(m)}")
            }
            if (sup.stage != lastStage) {
                val detail = sup.play?.let { " (play $it" + (if (sup.chain.size > 1) ", ${sup.chainIndex + 1}/${sup.chain.size}" else "") + ")" } ?: ""
                trace.add(now, "stage ${lastStage ?: "-"} -> ${sup.stage}$detail, doors ${WaterTrace.key(tracker.doors)}")
                lastStage = sup.stage
            }
        }
        if (available && tracker.layoutCaptured) hooks.afterUpdate(this, solved = confirmedSolved || sup?.stage == WaterSupervisor.Stage.SOLVED)
        advancedGuidance = sup?.guidance(tracker)
    }

    private fun onLayoutCaptured() {
        val table = tables?.get(tracker.variant)
        trace.add(
            now, "layout v${tracker.variant}, ${tracker.toggleLever.count { it >= 0 }} toggle blocks, untouched state from ${tracker.startSource}, " +
                "doors ${WaterTrace.key(tracker.doors)}, water ${if (tracker.entryWet()) "at the entry" else "off"}"
        )
        if (table != null) supervisor = WaterSupervisor(table).also {
            it.listener = this
            it.sim = hooks.simSupport
        }
        hooks.layoutCaptured(this, table)
    }

    /** Water reaching or leaving the entry hole and the outlet columns (x, 58..61), as edges. */
    private fun traceWater() {
        val entry = tracker.entryWet()
        if (entry != entryWet) {
            entryWet = entry
            trace.add(now, "entry ${if (entry) "wet" else "dry"}")
        }
        var wet = 0
        for ((i, x) in OUTLET_XS.withIndex()) {
            if (OUTLET_YS.any { y -> tracker.water[BoardGeometry.cell(x, y)] >= 0 }) wet = wet or (1 shl i)
        }
        val changed = wet xor outletWet
        if (changed == 0) return
        for ((i, x) in OUTLET_XS.withIndex()) {
            if (changed and (1 shl i) != 0) trace.add(now, "outlet x=$x ${if (wet and (1 shl i) != 0) "wet" else "dry"}")
        }
        outletWet = wet
    }

    private fun classicTick(gates: GateRead?, indicator: Int?): Guidance? {
        // The indicator blocks sit on the board: with its chunks unloaded now, the layout read earlier still counts.
        classic.onVariant(indicator ?: tracker.variant.takeIf { ! advancedFailed && tracker.layoutCaptured }, now)
        classic.onGates(gates, now)
        classic.tick(now)
        for (e in classic.drainEvents()) trace.add(now, e)
        return classic.guidance(now)
    }

    private fun classicPart(block: () -> Guidance?): Guidance? {
        if (classicFailed) return null
        return try {
            block()
        }
        catch (e: Exception) {
            classicFailed = true
            trace.add(now, "classic display failed: $e")
            hooks.failed("classic display", e)
            null
        }
    }

    // ================================================================================================================
    // What to show
    // ================================================================================================================

    private fun choose(classicGuidance: Guidance?) {
        val problem = advancedProblem()
        if (problem != advancedProblem) {
            trace.add(now, if (problem == null) "board-truth guidance in use" else "board-truth guidance not used: $problem")
            advancedProblem = problem
        }
        val adv = advancedGuidance
        val (m, g) = when {
            confirmedSolved -> Mode.SOLVED to Guidance.Solved
            tables == null -> Mode.STATUS to Guidance.Status("§cWater Board: §fthe solution data failed to load")
            problem == null && adv != null -> (if (adv == Guidance.Solved) Mode.SOLVED else Mode.ADVANCED) to adv
            classicGuidance != null -> (if (classicGuidance == Guidance.Solved) Mode.SOLVED else Mode.CLASSIC) to classicGuidance
            classicFailed && advancedFailed -> Mode.STATUS to Guidance.Status("§cWater Board: §fsolver error, see the log §8- §7/lbq water reset")
            ! available && classic.gates < 0 -> Mode.STATUS to Guidance.Status("§7Water Board: walk closer to the board")
            else -> Mode.STATUS to Guidance.Status(WaterSupervisor.WAITING_FOR_DOORS)
        }
        if (m != mode) {
            val why = if (m == Mode.CLASSIC) " (board-truth guidance not used: $problem)" else ""
            trace.add(now, "display ${mode.name.lowercase()} -> ${m.name.lowercase()}$why")
            logLines += "display: ${m.name.lowercase()}$why"
        }
        mode = m
        display = g
    }

    /** Why the board-truth guidance must not replace the classic display right now; null when it may. */
    private fun advancedProblem(): String? {
        // Tracked every tick, whatever else is wrong, so a disagreement is timed from when it really started.
        val disagree = available && tracker.ready && classic.gates >= 0 && classic.gates != tracker.doors
        if (! disagree) doorsDisagreeSince = - 1
        else if (doorsDisagreeSince < 0) doorsDisagreeSince = now

        if (advancedFailed) return "it failed (see the log)"
        leadProblem()?.let { return it }
        if (! available) return "the board is not loaded"
        if (! tracker.layoutCaptured) return "the layout is not recognised"
        val sup = supervisor ?: return "no solutions for layout ${tracker.variant}"
        if (! tracker.ready) return "the board is not read yet"
        if (sup.stage == WaterSupervisor.Stage.READING) return "no door closed yet"
        if (classic.variant >= 0 && classic.variant != tracker.variant) return "layout ${tracker.variant} on the board, ${classic.variant} from the indicator"
        if (disagree && now - doorsDisagreeSince > DOOR_DISAGREE_TICKS) {
            return "doors ${WaterTrace.key(tracker.doors)} on the board, ${WaterTrace.key(classic.gates)} at the door blocks"
        }
        val g = advancedGuidance ?: return "no guidance"
        if (g == Guidance.Solved && classic.activated && ! classic.solved) return "solved on the board, but ${WaterTrace.key(classic.gates)} read closed"
        if (g.silent) return "nothing to show in ${sup.stage}"
        return null
    }

    /** Why the classic display keeps the lead (the board-truth side is not asked yet); null once it may hand over. */
    private fun leadProblem(): String? = when {
        ! recovery -> "Water Recovery Guidance is off"
        advancedFirst || leadRequested || classicFailed -> null
        classic.departures > 0 -> null
        classic.activated && ! classic.solved && classic.play == null -> null
        else -> "the timed solution leads until a run fails"
    }

    // ================================================================================================================
    // Run listener
    // ================================================================================================================

    override fun runStarted(play: ShippedPlay, tick: Int) {
        trace.add(tick, "run $play started")
        logLines += "run $play started at $tick"
    }

    override fun runEnded(reason: String, tick: Int) {
        trace.add(tick, "run ended: $reason")
        logLines += "run ended at $tick: $reason"
    }

    // ================================================================================================================
    // Status
    // ================================================================================================================

    /** Lines for `/lbq water status` about what is shown and why. */
    fun displayStatus(): List<String> {
        val lines = ArrayList<String>()
        lines += "§7Display: §f${mode.name.lowercase()}" + (advancedProblem?.let { " §7(board-truth guidance not used: $it)" } ?: "")
        lines += "§7Classic: " + when {
            ! classic.activated -> "§fno door closed yet"
            classic.solved -> "§fevery door open"
            else -> "§fdoors ${Door.names(classic.gates)}§7, play §f${classic.play ?: "none"}" +
                (if (classic.waterTick >= 0) " §7(water pulled at ${classic.waterTick})" else "")
        }
        return lines
    }

    private fun drain(list: ArrayList<String>): List<String> {
        if (list.isEmpty()) return emptyList()
        val out = list.toList()
        list.clear()
        return out
    }

    companion object {
        /** The two door reads may disagree this long (they settle at different speeds) before the classic one wins. */
        const val DOOR_DISAGREE_TICKS = 40

        /** Outlet columns, in door order of the old Skyblocker mapping (x = 9 Purple ... -9 Red), and their rows. */
        private val OUTLET_XS = intArrayOf(9, 5, 0, - 5, - 9)
        private val OUTLET_YS = 58 .. 61

        private val FORMATTING = Regex("§.")

        fun plain(text: String) = text.replace(FORMATTING, "")
    }
}
