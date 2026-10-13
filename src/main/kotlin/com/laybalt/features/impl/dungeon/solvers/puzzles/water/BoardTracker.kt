package com.laybalt.features.impl.dungeon.solvers.puzzles.water

import com.laybalt.features.impl.dungeon.solvers.puzzles.water.BoardGeometry.N

/**
 * The live picture of the Water Board. The reader feeds it raw block reads (block-change packets, chunk loads and a
 * periodic full read, all on the client thread); [update] turns them, once per server tick, into board facts and
 * [BoardEvent]s:
 *
 * - **Lever parity from the planes.** The first full read is the reference snapshot. A block lever's bit is 1 while all
 *   of its blocks are in the other plane than in the reference and 0 while all are back; anything in between is a
 *   transit. Nothing is counted, so a swap out and back, a double flick or a missed packet can never invert it.
 * - **Doors** from the door blocks, debounced for [DOOR_DEBOUNCE] ticks: a door that flickers (a piston moving it, two
 *   toggles in a row) must have settled before it counts.
 * - **Water coming in** from the water lever's powered state (fast; the parity of its changes, so two flips between two
 *   ticks are no flip) checked against water at the entry hole (truth).
 * - **Water on the board** (count and last change) for drains and settled boards.
 *
 * Nothing runs while the board is not readable ([available] false: its chunks are unloaded and would read as air), not
 * even the timeouts. The first readable update after such a stretch is a [BoardEvent.Resynced]: the board is taken as it
 * is, without inventing timed flips, door toggles or water switches for what changed unseen.
 *
 * Clicks are hints only: they attribute flips to us and give the click-frame tick of a flip.
 */
class BoardTracker : BoardView {
    override var now = 0
        private set

    // ---- raw reads -------------------------------------------------------------------------------------------------
    val front = ByteArray(N) { BoardCodes.UNKNOWN }
    val back = ByteArray(N) { BoardCodes.UNKNOWN }
    val water = ByteArray(N) { - 1 }

    private var doorRaw = 0
    private val doorRawSince = IntArray(Door.entries.size)
    private var doorsRead = false
    private var doorStable = 0
    private var initializedDoors = false

    private val powered = BooleanArray(WaterLever.entries.size)
    private val poweredKnown = BooleanArray(WaterLever.entries.size)

    /** The water lever's powered state when its last change was taken into account. */
    private var waterPoweredRef = false

    /** First tick the water lever moved since then (-1: it did not), and how often. */
    private var waterPoweredChangeTick = - 1
    private var waterPoweredChanges = 0

    /** Set by the reader every tick: room frame known and its chunks loaded. */
    var available = false

    /** The board was readable at least once (that first read is the silent initialisation). */
    private var everAvailable = false

    /** The next readable update takes the reads as a fresh picture, not as live changes. */
    private var resyncPending = false

    /** First tick of the current stretch the board was not watched (NEVER while it is). */
    private var unwatchedSince = NEVER

    /** Times the board was read again after not being watched (status and trace). */
    var resyncs = 0
        private set

    /** Ping in server ticks, set by the solver; the default latency of flips we did not click ourselves. */
    var rttTicks = 0

    // ---- layout and parity -----------------------------------------------------------------------------------------
    var variant = - 1
        private set
    var layoutCaptured = false
        private set
    val toggleLever = ByteArray(N) { - 1 }
    private val refFront = BooleanArray(N)
    private val cellsOf = Array(WaterLever.blockLevers.size) { IntList(32) }

    /** Parity of the reference snapshot relative to the untouched board, and which of its bits are confirmed. */
    var startOffset = 0
        private set
    var startKnown = 0
        private set

    /** How the untouched lever state was established (for /lbq water status). */
    var startSource = "not read"
        private set

    private val stable = IntArray(WaterLever.blockLevers.size)
    private val firstMove = IntArray(WaterLever.blockLevers.size) { - 1 }
    private var transitMask = 0
    private val transitWarned = BooleanArray(WaterLever.blockLevers.size)

    // ---- water -----------------------------------------------------------------------------------------------------
    private var waterOnState = false
    private var entryWetLast = false
    private var entryChange = 0

    /** Tick the entry cell itself last went dry (NEVER while it is wet): when an off click shows at the entry. */
    private var entryDryTick = NEVER

    /** Tick the entry last turned wet (entry cell, or falling water just below it). */
    private var entryWetEdge = NEVER
    private var awaitBoardUntil = - 1
    private var awaitBoardExpect = false
    private var waterInitialized = false
    var unconfirmedSwitches = 0
        private set

    private var boardWaterCount = 0
    private var lastBoardChange = 0

    /** Tick of the last change of each board-plane water cell (appeared, vanished or changed level). */
    val cellChangeTick = IntArray(N) { NEVER }

    /** Tick each board-plane cell last went from dry to wet. */
    val wetSince = IntArray(N) { NEVER }

    /** A running counter stamped on every water cell change: the order of same-tick changes (for the simulator). */
    val cellChangeSeq = IntArray(N)
    private var changeSeq = 0

    // ---- clicks and attribution ------------------------------------------------------------------------------------
    private val clicks = Array(WaterLever.entries.size) { ArrayDeque<IntArray>() } // [tick, matched]
    private val lastFlipTick = IntArray(WaterLever.entries.size) { Int.MIN_VALUE / 2 }
    private val lastFlipClick = IntArray(WaterLever.entries.size) { - 1 }
    // 0 block levers, 1 water lever (powered state), 2 water on at the entry, 3 water off at the entry. On and off are kept
    // apart: the entry dries on its own schedule, and the cell below it keeps falling for one more water tick.
    private val lagSamples = Array(4) { ArrayDeque<Int>() }

    private val events = ArrayList<BoardEvent>()
    private val notes = ArrayList<String>()

    /** Current recording of cell and lever changes for the self-check / recorder, if any. */
    var recording: RunRecord? = null

    // ================================================================================================================
    // Raw input (client thread)
    // ================================================================================================================

    fun setFront(cell: Int, code: Byte) {
        if (front[cell] == code) return
        front[cell] = code
        toggleChanged(cell, code)
    }

    fun setBack(cell: Int, code: Byte) {
        if (back[cell] == code) return
        back[cell] = code
        toggleChanged(cell, code)
    }

    /** Water level at [cell] of the board plane (-1 for none). */
    fun setWater(cell: Int, level: Int) {
        val lvl = level.coerceIn(- 1, 15).toByte()
        if (water[cell] == lvl) return
        val wasWet = water[cell] >= 0
        water[cell] = lvl
        cellChangeTick[cell] = now
        cellChangeSeq[cell] = ++ changeSeq
        if (! wasWet && lvl >= 0) wetSince[cell] = now
        if (BoardGeometry.y(cell) <= BoardGeometry.DRY_MAX_Y) {
            if (wasWet != (lvl >= 0)) boardWaterCount += if (lvl >= 0) 1 else - 1
            lastBoardChange = now
        }
        if (cell == BoardGeometry.ENTRY_CELL || cell == BoardGeometry.ENTRANCE_CELL) entryChange = now
        if (cell == BoardGeometry.ENTRY_CELL) entryDryTick = if (lvl >= 0) NEVER else now
        recording?.cell(now, cell, lvl.toInt())
    }

    fun setDoor(door: Door, closed: Boolean) {
        doorsRead = true
        val bit = door.bit
        if ((doorRaw and bit != 0) == closed) return
        doorRaw = if (closed) doorRaw or bit else doorRaw and bit.inv()
        doorRawSince[door.digit] = now
    }

    fun setPowered(lever: WaterLever, on: Boolean) {
        val i = lever.ordinal
        if (poweredKnown[i] && powered[i] == on) return
        val known = poweredKnown[i]
        powered[i] = on
        poweredKnown[i] = true
        if (lever != WaterLever.WATER) return
        if (! known) {
            waterPoweredRef = on
            return
        }
        if (waterPoweredChangeTick < 0) waterPoweredChangeTick = now
        waterPoweredChanges ++
    }

    /** One of our clicks on [lever] at click-frame [tick]. */
    fun ownClick(lever: WaterLever, tick: Int) {
        clicks[lever.ordinal].addLast(intArrayOf(tick, 0))
        recording?.click(tick, lever)
    }

    private fun toggleChanged(cell: Int, code: Byte) {
        if (! layoutCaptured) return
        val lever = toggleLever[cell].toInt()
        if (lever >= 0) {
            if (firstMove[lever] < 0) firstMove[lever] = now
            return
        }
        // A toggle block in a cell we did not know (it was moving when the reference was taken): adopt it.
        if (! BoardCodes.isToggle(code)) return
        val x = BoardGeometry.x(cell)
        val y = BoardGeometry.y(cell)
        if (! BoardGeometry.inToggleArea(x, y)) return
        val l = BoardCodes.lever(code)
        val inFront = front[cell] == code
        val swappedNow = stable[l] == 1
        toggleLever[cell] = l.toByte()
        refFront[cell] = inFront != swappedNow
        cellsOf[l].add(cell)
    }

    // ================================================================================================================
    // Layout capture
    // ================================================================================================================

    /** Which variant the board is, from Odin's indicator cells in either plane (-1 if none matches). */
    fun detectVariant(): Int {
        for ((i, indicator) in BoardGeometry.INDICATORS.withIndex()) {
            val (x, y, lever) = indicator
            val c = BoardGeometry.cell(x, y)
            val code = BoardCodes.toggle(lever.ordinal)
            if (front[c] == code || back[c] == code) return i
        }
        return - 1
    }

    /**
     * Takes the reference snapshot from the current reads (call after a full read). Returns false when the board is
     * not readable yet or its variant is unknown.
     */
    fun captureLayout(): Boolean {
        if (layoutCaptured) return true
        variant = detectVariant()
        if (variant < 0) return false
        for (cell in 0 until N) {
            val x = BoardGeometry.x(cell)
            val y = BoardGeometry.y(cell)
            if (! BoardGeometry.inToggleArea(x, y)) continue
            val f = front[cell]
            val b = back[cell]
            val code = when {
                BoardCodes.isToggle(f) -> f
                BoardCodes.isToggle(b) -> b
                else -> continue
            }
            val l = BoardCodes.lever(code)
            toggleLever[cell] = l.toByte()
            refFront[cell] = f == code
            cellsOf[l].add(cell)
        }
        stable.fill(0)
        firstMove.fill(- 1)
        transitMask = 0
        layoutCaptured = true
        estimateStart()
        return true
    }

    /**
     * Parity of the reference relative to the untouched board. The variant's indicator block sits behind the board in
     * an untouched room, which gives its lever for sure. The levers' powered states give all six, but they are only
     * trusted when they agree with the indicator.
     */
    private fun estimateStart() {
        val (x, y, lever) = BoardGeometry.INDICATORS[variant]
        val indicatorFlipped = front[BoardGeometry.cell(x, y)] == BoardCodes.toggle(lever.ordinal)
        val indicatorBit = if (indicatorFlipped) lever.bit else 0
        val allPowered = WaterLever.blockLevers.all { poweredKnown[it.ordinal] }
        val poweredBits = WaterLever.blockLevers.fold(0) { acc, l -> if (powered[l.ordinal]) acc or l.bit else acc }
        if (allPowered && (poweredBits and lever.bit) == indicatorBit) {
            startOffset = poweredBits
            startKnown = ALL_LEVERS
            startSource = if (poweredBits == 0) "untouched (indicator + lever states)" else "lever states (${WaterLever.names(poweredBits)} flipped before the read)"
        }
        else {
            startOffset = indicatorBit
            startKnown = lever.bit
            startSource = if (allPowered) "indicator only (lever states disagree)" else "indicator only (lever states unknown)"
        }
    }

    /** Overrides the untouched-state estimate (calibration found a better one). */
    fun overrideStart(offset: Int, source: String) {
        startOffset = offset and ALL_LEVERS
        startKnown = ALL_LEVERS
        startSource = source
    }

    /** Lever bits relative to the reference snapshot. */
    val mask: Int get() = stable.foldIndexed(0) { l, acc, s -> if (s == 1) acc or (1 shl l) else acc }

    fun cellsOf(lever: Int): IntArray = IntArray(cellsOf[lever].size) { cellsOf[lever][it] }
    fun refExtended(cell: Int) = refFront[cell]

    /** Board-plane codes of the reference snapshot (toggles in their reference plane), for the simulator layout. */
    fun referenceCodes(): Pair<ByteArray, ByteArray> {
        val f = front.copyOf()
        val b = back.copyOf()
        for (cell in 0 until N) {
            val l = toggleLever[cell].toInt()
            if (l < 0) continue
            val code = BoardCodes.toggle(l)
            if (refFront[cell]) {
                f[cell] = code
                if (b[cell] == code) b[cell] = BoardCodes.WALL
            }
            else {
                b[cell] = code
                if (f[cell] == code || BoardCodes.blocks(f[cell])) f[cell] = BoardCodes.AIR
            }
        }
        return f to b
    }

    // ================================================================================================================
    // Per-tick update
    // ================================================================================================================

    /** Processes everything read since the last call; [tick] is the new server tick. */
    fun update(tick: Int) {
        now = tick
        if (! available) {
            // The chunks are gone (they would read as air): no door, lever or water timing runs on a picture that is no
            // longer kept up to date, and the next readable update starts from a fresh read.
            markUnwatched()
            pruneClicks()
            return
        }
        if (resyncPending) resync()
        else {
            updateDoors()
            if (layoutCaptured) updateLevers()
            updateWater()
        }
        everAvailable = true
        resyncPending = false
        unwatchedSince = NEVER
        pruneClicks()
    }

    /**
     * The board stopped being watched (its chunks were unloaded, or are about to be re-sent after a teleport): what is
     * read next is a fresh picture, not a live change.
     */
    fun markUnwatched() {
        if (! everAvailable) return
        if (unwatchedSince == NEVER) unwatchedSince = now
        resyncPending = true
    }

    /** Whether the next readable update will be a [BoardEvent.Resynced]. */
    val resyncing get() = resyncPending

    /**
     * Takes the reads as they are: doors, lever parity and the water are set from them without timed events, and one
     * [BoardEvent.Resynced] says what changed while the board was not watched.
     */
    private fun resync() {
        resyncs ++
        val gap = if (unwatchedSince == NEVER) 0 else now - unwatchedSince
        val doorsBefore = doorStable
        if (doorsRead) {
            doorStable = doorRaw
            doorRawSince.fill(now)
            initializedDoors = true
        }
        var levers = 0
        if (layoutCaptured) for (l in WaterLever.blockLevers.indices) {
            val bit = 1 shl l
            when (val state = leverState(l)) {
                - 1 -> {
                    // Caught mid-swap: the transit resolves through the usual update, dated from now.
                    firstMove[l] = now
                    transitMask = transitMask or bit
                }

                else -> {
                    firstMove[l] = - 1
                    transitMask = transitMask and bit.inv()
                    if (state != stable[l]) {
                        stable[l] = state
                        levers = levers or bit
                    }
                }
            }
        }
        val waterBefore = waterOnState
        val entryWet = entryWet()
        waterOnState = entryWet
        entryWetLast = entryWet
        if (entryWet) entryWetEdge = entryChange
        awaitBoardUntil = - 1
        waterPoweredRef = powered[WaterLever.WATER.ordinal]
        waterPoweredChangeTick = - 1
        waterPoweredChanges = 0
        lastBoardChange = now
        waterInitialized = true
        events += BoardEvent.Resynced(now, gap, doorsBefore, doorStable, levers, waterBefore, waterOnState)
    }

    /** Events since the last call, oldest first. */
    fun drainEvents(): List<BoardEvent> {
        if (events.isEmpty()) return emptyList()
        val out = events.toList()
        events.clear()
        return out
    }

    /** Diagnostic notes (inconsistencies) since the last call. */
    fun drainNotes(): List<String> {
        if (notes.isEmpty()) return emptyList()
        val out = notes.toList()
        notes.clear()
        return out
    }

    private fun updateDoors() {
        if (! doorsRead) return
        for (door in Door.entries) {
            val raw = doorRaw and door.bit != 0
            val st = doorStable and door.bit != 0
            if (raw == st) continue
            val since = doorRawSince[door.digit]
            if (now - since < DOOR_DEBOUNCE && initializedDoors) continue
            doorStable = if (raw) doorStable or door.bit else doorStable and door.bit.inv()
            if (initializedDoors) {
                events += BoardEvent.DoorToggled(door, raw, since)
                recording?.door(since, door.digit, raw)
            }
        }
        initializedDoors = true
    }

    /** Lever [l]'s blocks: (at the reference, swapped, unreadable or moving). */
    private fun leverCounts(l: Int): IntArray {
        val cells = cellsOf[l]
        val code = BoardCodes.toggle(l)
        val counts = IntArray(3)
        for (i in 0 until cells.size) {
            val c = cells[i]
            val inFront = front[c] == code
            val inBack = back[c] == code
            when {
                inFront == inBack -> counts[2] ++
                inFront == refFront[c] -> counts[0] ++
                else -> counts[1] ++
            }
        }
        return counts
    }

    /** 0: every block of lever [l] at the reference, 1: every block swapped, -1: anything in between (or no blocks). */
    private fun leverState(l: Int): Int {
        if (cellsOf[l].size == 0) return stable[l]
        val (atRef, swapped, moving) = leverCounts(l)
        return when {
            moving == 0 && swapped == 0 -> 0
            moving == 0 && atRef == 0 -> 1
            else -> - 1
        }
    }

    private fun updateLevers() {
        for (l in WaterLever.blockLevers.indices) {
            if (cellsOf[l].size == 0) continue
            val bit = 1 shl l
            var state = leverState(l)
            if (state < 0) {
                val (atRef, swapped, moving) = leverCounts(l)
                if (firstMove[l] < 0) firstMove[l] = now
                transitMask = transitMask or bit
                if (now - firstMove[l] <= TRANSIT_LIMIT) continue
                // Stuck half-way (a block that never moved, a read we missed): go with the majority.
                if (! transitWarned[l]) {
                    transitWarned[l] = true
                    notes += "${WaterLever.entries[l].displayName}: $swapped moved, $atRef did not, $moving unreadable for ${now - firstMove[l]} ticks"
                }
                state = if (swapped > atRef) 1 else 0
            }
            transitMask = transitMask and bit.inv()
            val start = if (firstMove[l] >= 0) firstMove[l] else now
            firstMove[l] = - 1
            if (state != stable[l]) {
                stable[l] = state
                emitFlip(WaterLever.entries[l], start)
            }
        }
    }

    private fun emitFlip(lever: WaterLever, tick: Int) {
        val i = lever.ordinal
        val click = matchClick(i, tick, CLICK_WINDOW)
        val (source, clickTick) = when {
            click >= 0 -> FlipSource.OWN to click
            lastFlipClick[i] >= 0 && tick - lastFlipTick[i] <= DOUBLE_WINDOW -> FlipSource.DOUBLE to lastFlipClick[i]
            else -> FlipSource.OTHER to tick - lag(0)
        }
        if (source == FlipSource.OWN) sample(0, tick - click)
        lastFlipTick[i] = tick
        lastFlipClick[i] = if (source == FlipSource.OWN) click else - 1
        events += BoardEvent.LeverFlipped(lever, tick, clickTick, source)
        recording?.flip(tick, lever, source)
    }

    /** Water at the entry hole (or falling right below it): water is coming into the board. */
    fun entryWet() = water[BoardGeometry.ENTRY_CELL] >= 0 || water[BoardGeometry.ENTRANCE_CELL] >= 8

    private fun updateWater() {
        val entryWet = entryWet()
        if (! waterInitialized) {
            waterInitialized = true
            waterOnState = entryWet
            lastBoardChange = now
            entryWetLast = entryWet
            waterPoweredRef = powered[WaterLever.WATER.ordinal]
            waterPoweredChangeTick = - 1
            waterPoweredChanges = 0
            return
        }

        val w = WaterLever.WATER.ordinal
        if (waterPoweredChanges > 0) {
            val t = waterPoweredChangeTick
            val changes = waterPoweredChanges
            waterPoweredChangeTick = - 1
            waterPoweredChanges = 0
            if (powered[w] != waterPoweredRef) {
                waterPoweredRef = powered[w]
                switchWater(! waterOnState, t, 1, correction = false)
                awaitBoardUntil = t + BOARD_CONFIRM_TICKS
                awaitBoardExpect = waterOnState
            }
            // Flipped and flipped back between two reads (a double flick): the water did not change.
            else notes += "the water lever moved $changes times between two ticks and is back where it was"
        }

        if (entryWet != entryWetLast) {
            entryWetLast = entryWet
            if (entryWet) entryWetEdge = entryChange
            val edge = entryEdge(entryWet)
            val kind = if (entryWet) 2 else 3
            when {
                awaitBoardUntil >= 0 && entryWet == awaitBoardExpect -> {
                    awaitBoardUntil = - 1
                    if (lastFlipClick[w] >= 0) sample(kind, edge - lastFlipClick[w])
                }

                awaitBoardUntil < 0 && entryWet != waterOnState -> switchWater(entryWet, edge, kind, correction = false)
            }
        }

        if (awaitBoardUntil in 0 until now && entryWet != waterOnState) {
            // The lever said so, the board did not follow: trust the board.
            awaitBoardUntil = - 1
            unconfirmedSwitches ++
            notes += "water lever changed but no water ${if (waterOnState) "came in" else "stopped"} within $BOARD_CONFIRM_TICKS ticks"
            waterOnState = entryWet
            events += BoardEvent.WaterSwitched(entryWet, now, now, FlipSource.OTHER, correction = true)
        }
        else if (awaitBoardUntil in 0 until now) awaitBoardUntil = - 1
    }

    /**
     * Tick a water change shows at the entry: getting wet is the last change there; drying is when the entry cell itself
     * dried (the falling water below it vanishes one water tick later), if it did since it was last wet.
     */
    private fun entryEdge(wet: Boolean): Int =
        if (wet || entryDryTick == NEVER || entryDryTick < entryWetEdge) entryChange else entryDryTick

    /** [kind]: 1 = seen on the water lever's powered state, 2 / 3 = seen at the entry hole turning wet / dry (later than the click). */
    private fun switchWater(on: Boolean, tick: Int, kind: Int, correction: Boolean) {
        val w = WaterLever.WATER.ordinal
        val window = if (kind == 1) CLICK_WINDOW else CLICK_WINDOW + lag(kind)
        val click = matchClick(w, tick, window)
        val (source, clickTick) = when {
            click >= 0 -> FlipSource.OWN to click
            else -> FlipSource.OTHER to tick - lag(kind)
        }
        if (source == FlipSource.OWN && kind == 1) sample(1, tick - click)
        lastFlipTick[w] = tick
        lastFlipClick[w] = if (source == FlipSource.OWN) click else - 1
        waterOnState = on
        events += BoardEvent.WaterSwitched(on, tick, clickTick, source, correction)
        recording?.water(tick, on, source)
    }

    /** Earliest unmatched own click on lever [i] within [window] ticks before [tick]; marks it matched. */
    private fun matchClick(i: Int, tick: Int, window: Int): Int {
        for (c in clicks[i]) {
            if (c[1] != 0) continue
            if (c[0] in tick - window .. tick) {
                c[1] = 1
                return c[0]
            }
        }
        return - 1
    }

    private fun pruneClicks() {
        for (q in clicks) while (q.isNotEmpty() && now - q.first()[0] > CLICK_KEEP) q.removeFirst()
    }

    private fun sample(kind: Int, value: Int) {
        if (value !in 0 .. 60) return
        val q = lagSamples[kind]
        q.addLast(value)
        if (q.size > 9) q.removeFirst()
    }

    /** Our clicks of the last [maxAge] ticks that the board has not shown yet (lever, click tick), oldest first. */
    fun unmatchedClicks(maxAge: Int): List<Pair<WaterLever, Int>> = WaterLever.entries
        .flatMap { lever -> clicks[lever.ordinal].filter { it[1] == 0 && now - it[0] <= maxAge }.map { lever to it[0] } }
        .sortedBy { it.second }

    /** Whether [lag] of [kind] comes from our own clicks (otherwise it is a default). */
    fun measured(kind: Int) = lagSamples[kind].isNotEmpty()

    /**
     * Typical ticks from a click to the board showing it: [kind] 0 block levers, 1 water lever state, 2 / 3 the entry hole
     * getting wet / drying. An unmeasured off lag is taken to equal the on lag.
     */
    fun lag(kind: Int): Int {
        val q = lagSamples[kind]
        if (q.isEmpty() && kind == 3) return lag(2)
        if (q.isEmpty()) return rttTicks + if (kind == 2) DEFAULT_ENTRY_LAG else 1
        return q.sorted()[q.size / 2]
    }

    // ================================================================================================================
    // BoardView
    // ================================================================================================================

    override val ready get() = available && layoutCaptured && doorsRead && waterInitialized
    override val doors get() = doorStable
    override val parity get() = (startOffset xor mask) and ALL_LEVERS
    override val transit get() = transitMask
    override val parityUncertain get() = ALL_LEVERS and startKnown.inv()
    override val waterOn get() = waterOnState
    override val boardWater get() = boardWaterCount
    override val lastWaterChange get() = lastBoardChange
    override val flipLag get() = lag(0)
    override val waterClickPending: Boolean
        get() {
            val q = clicks[WaterLever.WATER.ordinal]
            return q.any { it[1] == 0 && now - it[0] <= PENDING_CLICK_TICKS }
        }

    /** The water lever's powered state, if read (for status output). */
    fun powered(lever: WaterLever): Boolean? = if (poweredKnown[lever.ordinal]) powered[lever.ordinal] else null

    companion object {
        const val ALL_LEVERS = 0b111111
        const val NEVER = Int.MIN_VALUE / 2

        /**
         * Ticks a door must read the same before it counts. A sticky piston takes 2-3 ticks to move a door, and the field
         * logs of 3.2.4 show doors that read open for 3 ticks and then closed again (23:14:11): those are not toggles.
         */
        const val DOOR_DEBOUNCE = 8
        const val TRANSIT_LIMIT = 20
        const val CLICK_WINDOW = 30
        const val DOUBLE_WINDOW = 10
        const val CLICK_KEEP = 120
        const val PENDING_CLICK_TICKS = 60
        const val BOARD_CONFIRM_TICKS = 60
        const val DEFAULT_ENTRY_LAG = 12
    }
}
