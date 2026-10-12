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
 * - **Doors** from the door blocks, debounced for two ticks.
 * - **Water coming in** from the water lever's powered state (fast) checked against water at the entry hole (truth).
 * - **Water on the board** (count and last change) for drains and settled boards.
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
    private var waterPoweredEdge = - 1

    /** Set by the reader every tick: room frame known and its chunks loaded. */
    var available = false

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
    private var awaitBoardUntil = - 1
    private var awaitBoardExpect = false
    private var waterInitialized = false
    var unconfirmedSwitches = 0
        private set

    private var boardWaterCount = 0
    private var lastBoardChange = 0

    // ---- clicks and attribution ------------------------------------------------------------------------------------
    private val clicks = Array(WaterLever.entries.size) { ArrayDeque<IntArray>() } // [tick, matched]
    private val lastFlipTick = IntArray(WaterLever.entries.size) { Int.MIN_VALUE / 2 }
    private val lastFlipClick = IntArray(WaterLever.entries.size) { - 1 }
    private val lagSamples = Array(3) { ArrayDeque<Int>() } // 0 block levers, 1 water (powered), 2 water (board)

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
        if (BoardGeometry.y(cell) <= BoardGeometry.DRY_MAX_Y) {
            if (wasWet != (lvl >= 0)) boardWaterCount += if (lvl >= 0) 1 else - 1
            lastBoardChange = now
        }
        if (cell == BoardGeometry.ENTRY_CELL || cell == BoardGeometry.ENTRANCE_CELL) entryChange = now
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
        val edge = poweredKnown[i]
        powered[i] = on
        poweredKnown[i] = true
        if (edge && lever == WaterLever.WATER) waterPoweredEdge = now
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
        updateDoors()
        if (layoutCaptured) updateLevers()
        updateWater()
        pruneClicks()
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

    private fun updateLevers() {
        for (l in WaterLever.blockLevers.indices) {
            val cells = cellsOf[l]
            if (cells.size == 0) continue
            val code = BoardCodes.toggle(l)
            var atRef = 0
            var swapped = 0
            var moving = 0
            for (i in 0 until cells.size) {
                val c = cells[i]
                val inFront = front[c] == code
                val inBack = back[c] == code
                when {
                    inFront == inBack -> moving ++
                    inFront == refFront[c] -> atRef ++
                    else -> swapped ++
                }
            }
            val bit = 1 shl l
            var state = when {
                moving == 0 && swapped == 0 -> 0
                moving == 0 && atRef == 0 -> 1
                else -> - 1
            }
            if (state < 0) {
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

    private fun updateWater() {
        val entryWet = water[BoardGeometry.ENTRY_CELL] >= 0 || water[BoardGeometry.ENTRANCE_CELL] >= 8
        if (! waterInitialized) {
            if (! available) return
            waterInitialized = true
            waterOnState = entryWet
            lastBoardChange = now
            entryWetLast = entryWet
            waterPoweredEdge = - 1
            return
        }

        val w = WaterLever.WATER.ordinal
        if (waterPoweredEdge >= 0) {
            val t = waterPoweredEdge
            waterPoweredEdge = - 1
            switchWater(! waterOnState, t, 1, correction = false)
            awaitBoardUntil = t + BOARD_CONFIRM_TICKS
            awaitBoardExpect = waterOnState
        }

        if (entryWet != entryWetLast) {
            entryWetLast = entryWet
            when {
                awaitBoardUntil >= 0 && entryWet == awaitBoardExpect -> {
                    awaitBoardUntil = - 1
                    if (lastFlipClick[w] >= 0) sample(2, entryChange - lastFlipClick[w])
                }

                awaitBoardUntil < 0 && entryWet != waterOnState -> switchWater(entryWet, entryChange, 2, correction = false)
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

    /** [kind]: 1 = seen on the water lever's powered state, 2 = seen at the entry hole (later than the click). */
    private fun switchWater(on: Boolean, tick: Int, kind: Int, correction: Boolean) {
        val w = WaterLever.WATER.ordinal
        val window = if (kind == 1) CLICK_WINDOW else CLICK_WINDOW + lag(2)
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

    /** Typical ticks from a click to the board showing it: [kind] 0 block levers, 1 water lever state, 2 entry hole. */
    fun lag(kind: Int): Int {
        val q = lagSamples[kind]
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
    override val waterClickPending: Boolean
        get() {
            val q = clicks[WaterLever.WATER.ordinal]
            return q.any { it[1] == 0 && now - it[0] <= PENDING_CLICK_TICKS }
        }

    /** The water lever's powered state, if read (for status output). */
    fun powered(lever: WaterLever): Boolean? = if (poweredKnown[lever.ordinal]) powered[lever.ordinal] else null

    companion object {
        const val ALL_LEVERS = 0b111111
        const val DOOR_DEBOUNCE = 2
        const val TRANSIT_LIMIT = 20
        const val CLICK_WINDOW = 30
        const val DOUBLE_WINDOW = 10
        const val CLICK_KEEP = 120
        const val PENDING_CLICK_TICKS = 60
        const val BOARD_CONFIRM_TICKS = 60
        const val DEFAULT_ENTRY_LAG = 12
    }
}
