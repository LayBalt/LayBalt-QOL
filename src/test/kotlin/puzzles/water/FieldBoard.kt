package puzzles.water

import com.laybalt.features.impl.dungeon.solvers.puzzles.water.*
import com.laybalt.features.impl.dungeon.solvers.puzzles.water.BoardGeometry.cell

/**
 * The Water Board as the session sees it, for replaying field sequences through the real [WaterRoomCore]: raw block
 * reads of both planes, the doors, the levers' powered states and the water, pushed into the core every tick like the
 * session's reads. The layout is made up (three toggle blocks per lever plus the variant's indicator); what matters is
 * that the tracker recognises the variant and sees flips, doors and water exactly as it would on Hypixel.
 *
 * [available] false is "the board's chunks are unloaded": nothing is read then, as in the session. [doorsLoaded] false
 * makes the classic display's own door read unknown as well.
 */
class FieldBoard(val variant: Int, tables: Map<Int, ShippedTable>? = WaterTestData.tables, hooks: WaterRoomCore.Hooks = WaterRoomCore.Hooks.NONE) {
    val core = WaterRoomCore(tables, hooks)
    var now = 392_000
    var available = true
    var doorsLoaded = true

    /** What the indicator blocks read as (null: the real variant while the board is loaded). */
    var indicatorRead: Int? = null

    /** Closed doors at the door blocks, and doors a piston is moving (read as neither). */
    var doors = 0
    var doorsMoving = 0

    val front = ByteArray(BoardGeometry.N) { BoardCodes.AIR }
    val back = ByteArray(BoardGeometry.N) { BoardCodes.WALL }
    val water = IntArray(BoardGeometry.N) { - 1 }
    val powered = BooleanArray(WaterLever.entries.size)

    /** Every guidance shown (one per tick), for assertions on what the player saw. */
    val shown = ArrayList<Guidance>()
    val modes = ArrayList<WaterRoomCore.Mode>()
    val messages = ArrayList<String>()
    val traceLines = ArrayList<String>()
    val log = ArrayList<String>()

    private val cells = Array(WaterLever.blockLevers.size) { ArrayList<Int>() }

    init {
        for (l in WaterLever.blockLevers.indices) {
            val x = - 8 + 3 * l
            for (y in intArrayOf(75, 70, 65)) cells[l] += cell(x, y)
        }
        // The variant's indicator: its lever's block behind the board in an untouched room.
        val (ix, iy, lever) = BoardGeometry.INDICATORS[variant]
        cells[lever.ordinal] += cell(ix, iy)
        for (l in cells.indices) for (c in cells[l]) {
            // Half of the levers start extended (board plane), the others retracted, the indicator always retracted.
            val extended = l % 2 == 0 && c != cell(ix, iy)
            if (extended) front[c] = BoardCodes.toggle(l) else back[c] = BoardCodes.toggle(l)
        }
    }

    /** Moves every block of [lever] to the other plane and flips the lever's powered state (the water lever: the water). */
    fun flip(lever: WaterLever) {
        powered[lever.ordinal] = ! powered[lever.ordinal]
        if (lever == WaterLever.WATER) return
        val code = BoardCodes.toggle(lever.ordinal)
        for (c in cells[lever.ordinal]) {
            if (front[c] == code) {
                front[c] = BoardCodes.AIR
                back[c] = code
            }
            else {
                back[c] = BoardCodes.WALL
                front[c] = code
            }
        }
    }

    /** Our own click on [lever], made last tick: the board shows it this tick (a one-tick lag). */
    fun click(lever: WaterLever) {
        core.onClick(lever, now - 1)
        flip(lever)
    }

    /** Water at the entry hole (the water arriving after a water-lever flip, or stopping). */
    fun entry(wet: Boolean) {
        water[BoardGeometry.ENTRY_CELL] = if (wet) 8 else - 1
        water[BoardGeometry.ENTRANCE_CELL] = if (wet) 8 else - 1
        front[BoardGeometry.ENTRY_CELL] = if (wet) BoardCodes.WATER else BoardCodes.AIR
        front[BoardGeometry.ENTRANCE_CELL] = if (wet) BoardCodes.WATER else BoardCodes.AIR
    }

    /** Water (or none) at (x, y) of the board plane. */
    fun wet(x: Int, y: Int, level: Int = 1) {
        val c = cell(x, y)
        water[c] = level
        front[c] = if (level >= 0) BoardCodes.WATER else BoardCodes.AIR
    }

    fun dry(x: Int, y: Int) = wet(x, y, - 1)

    /** Water on [count] cells in the middle of the board (a run in progress), or none. */
    fun boardWater(count: Int) {
        for (i in 0 until 10) {
            val c = cell(- 4 + i, 68)
            val on = i < count
            water[c] = if (on) 1 else - 1
            front[c] = if (on) BoardCodes.WATER else BoardCodes.AIR
        }
    }

    fun closeDoors(key: String) {
        doors = Door.bitsOf(key)
    }

    /** One server tick: [block] changes the board, then the session reads it (if loaded) and the core runs. */
    fun tick(block: FieldBoard.() -> Unit = {}) {
        now ++
        block()
        val t = core.tracker
        if (available) {
            for (c in 0 until BoardGeometry.N) {
                t.setFront(c, front[c])
                t.setWater(c, water[c])
                t.setBack(c, back[c])
            }
            for (door in Door.entries) if (doorsMoving and door.bit == 0) t.setDoor(door, doors and door.bit != 0)
            for (lever in WaterLever.entries) t.setPowered(lever, powered[lever.ordinal])
        }
        val gates = if (doorsLoaded) GateRead(doors and doorsMoving.inv(), doorsMoving) else null
        core.tick(now, available, gates, indicatorRead ?: if (available) variant else null)
        shown += core.display
        modes += core.mode
        messages += core.drainMessages()
        log += core.drainLog()
        traceLines += core.trace.drain()
        check(core.display == Guidance.Solved || ! core.display.silent) { "silent display at $now in ${core.mode}" }
    }

    fun ticks(n: Int, block: FieldBoard.() -> Unit = {}) = repeat(n) { tick(block) }

    val sup get() = core.supervisor
    val display get() = core.display
    val act get() = core.display as? Guidance.Act

    fun labels(lever: WaterLever) = act?.labels.orEmpty().filter { it.lever == lever }.map { it.text }

    /** Lines of the trace that contain [text]. */
    fun traced(text: String) = traceLines.filter { it.contains(text) }

    /** Door toggles in the trace (as the tracker reported them). */
    fun tracedToggles() = traceLines.filter { DOOR_TOGGLE.containsMatchIn(it) }

    private companion object {
        val DOOR_TOGGLE = Regex("door (Purple|Orange|Blue|Green|Red) (opened|closed) \\(since")
    }
}
