package puzzles.water

import com.laybalt.features.impl.dungeon.solvers.puzzles.water.*

/** A hand-driven [BoardView]: tests set what the board shows and queue the events it would produce. */
class FakeBoard : BoardView {
    override var now = 1000
    override var ready = true
    override var doors = 0
    override var parity = 0
    override var transit = 0
    override var parityUncertain = 0
    override var waterOn = false
    override var waterClickPending = false
    override var boardWater = 0
    override var lastWaterChange = 0
    override var flipLag = 0

    val events = ArrayList<BoardEvent>()

    fun flip(lever: WaterLever, source: FlipSource = FlipSource.OWN, clickTick: Int = now) {
        if (lever == WaterLever.WATER) return water(! waterOn, source, clickTick)
        parity = parity xor lever.bit
        events += BoardEvent.LeverFlipped(lever, now, clickTick, source)
    }

    fun water(on: Boolean, source: FlipSource = FlipSource.OWN, clickTick: Int = now) {
        waterOn = on
        events += BoardEvent.WaterSwitched(on, now, clickTick, source)
    }

    fun toggle(door: Door) {
        doors = doors xor door.bit
        events += BoardEvent.DoorToggled(door, doors and door.bit != 0, now)
    }

    /** Water moving on the board this tick (or [count] cells standing still). */
    fun flowing(count: Int = 30) {
        boardWater = count
        lastWaterChange = now
    }
}

/** Runs a [WaterSupervisor] against a [FakeBoard] tick by tick. */
class Harness(val table: ShippedTable, doors: String, parity: Int = 0) {
    val board = FakeBoard().also {
        it.doors = Door.bitsOf(doors)
        it.parity = parity
    }
    val sup = WaterSupervisor(table)
    val messages = ArrayList<String>()
    val stages = ArrayList<WaterSupervisor.Stage>()

    /** Advances one tick: the supervisor sees the queued events, then the board's state. */
    fun tick(block: FakeBoard.() -> Unit = {}) {
        board.now ++
        board.block()
        sup.update(board, board.events.toList())
        board.events.clear()
        messages += sup.drainMessages()
        stages += sup.stage
        check(sup.stage == WaterSupervisor.Stage.SOLVED || ! sup.guidance(board).silent) { "silent guidance in ${sup.stage}" }
    }

    fun ticks(n: Int, block: FakeBoard.() -> Unit = {}) = repeat(n) { tick(block) }

    fun guidance() = sup.guidance(board)

    fun labels() = (guidance() as? Guidance.Act)?.labels.orEmpty()

    fun labelTexts(lever: WaterLever) = labels().filter { it.lever == lever }.map { it.text }

    /**
     * Plays [play] perfectly from now: pre-water flips, the water, every timed flip on time (+[jitter]), and each of the
     * play's doors toggling [doorAt] ticks after the start. Water moves on the board while [flow] says so (ticks after
     * the start). Stops [until] ticks after the start (default: a little after the last click and door).
     */
    fun playPerfectly(
        play: ShippedPlay,
        jitter: (Int) -> Int = { 0 },
        doorAt: (Int) -> Int = { k -> play.lastClickTick * (k + 1) / 3 + 25 },
        until: Int? = null,
        flow: (Int) -> Boolean = { true },
        flipOverride: ((TimedClick) -> Boolean)? = null,
        /** Ticks between a click and the board showing it (ping plus the server's lever delay). */
        lag: Int = 0,
    ) {
        for (lever in WaterLever.blockLevers) if ((board.parity xor play.startParity) and lever.bit != 0) tick { flip(lever) }
        val start = board.now + 1
        if (lag == 0) tick { water(true) }
        else {
            ticks(lag)
            tick { water(true, clickTick = start) }
        }
        val clicks = play.timedClicks.withIndex()
            .map { (i, c) -> c to start + c.tick + jitter(i) }
            .groupBy { (_, clickTick) -> clickTick + lag }
        val doors = Door.of(play.doors)
        val doorTicks = doors.indices.associateBy { start + lag + doorAt(it) }
        val end = start + lag + (until ?: (maxOf(play.lastClickTick, doors.indices.maxOf(doorAt)) + 60))
        while (board.now < end && sup.stage != WaterSupervisor.Stage.SOLVED) tick {
            clicks[now]?.forEach { (c, clickTick) -> if (flipOverride?.invoke(c) != false) flip(c.lever, clickTick = clickTick) }
            doorTicks[now]?.let { toggle(doors[it]) }
            if (flow(now - start) && (waterOn || now < start + play.lastClickTick + 40)) flowing() else boardWater = maxOf(boardWater, 1)
        }
    }

    /** Turns the water off if it is on and lets the board drain. */
    fun stopAndDrain() {
        if (board.waterOn) tick { water(false) }
        ticks(40) { flowing() }
        ticks(15) { boardWater = 0 }
    }
}
