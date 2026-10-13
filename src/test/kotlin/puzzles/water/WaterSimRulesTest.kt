package puzzles.water

import com.laybalt.features.impl.dungeon.solvers.puzzles.water.*
import kotlin.test.*

/** The 1.8 liquid rules on self-authored toy boards (row 0 = top). */
class WaterSimRulesTest {
    private fun layout(vararg rows: String) = BoardLayout.fromAscii(rows.toList())
    private fun BoardLayout.at(row: Int, col: Int) = row * width + col
    private fun state(layout: BoardLayout, rules: WaterRules = WaterRules()) = BoardState(layout, rules)

    private class Recorder : SimListener {
        val placed = mutableListOf<Triple<Int, Int, Int>>() // cell, level, tick
        val removed = mutableListOf<Pair<Int, Int>>()
        val arrivals = mutableListOf<Pair<Int, Int>>()
        override fun placed(cell: Int, level: Int, tick: Int) {
            placed += Triple(cell, level, tick)
        }

        override fun removed(cell: Int, tick: Int) {
            removed += cell to tick
        }

        override fun arrival(outlet: Int, tick: Int) {
            arrivals += outlet to tick
        }
    }

    private val shaft = layout(
        "#S#", "#.#", "#.#", "#.#", "#.#", "#.#", "#.#", "#.#", "#.#", "#.#",
        "#.#", "#.#", "#.#", "#.#", "#.#", "#.#", "#.#", "#.#", "#.#", "###",
    )

    @Test
    fun `a falling column advances one cell every five ticks`() {
        val s = state(shaft)
        s.addExternal(External.Source(1, true))
        val rec = Recorder()
        WaterSim.run(s, 60, rec)
        for (row in 0 .. 11) {
            val placed = rec.placed.first { it.first == shaft.at(row, 1) }
            assertEquals(1 + 5 * row, placed.third, "row $row")
            assertEquals(8, placed.second)
        }
    }

    @Test
    fun `cutting the source leaves a slug of water that keeps falling`() {
        val s = state(shaft)
        s.addExternals(listOf(External.Source(1, true), External.Source(21, false)))
        WaterSim.run(s, 60, stopWhenSettled = false)
        val wetRows = (0 until shaft.height).filter { s.wet(shaft.at(it, 1)) }
        // Front: row r wet from 1 + 5r. Tail: the entry dries at 21 (the off, like the on, is the entry's own change),
        // then one row every 5 ticks.
        assertEquals((8 .. 11).toList(), wetRows)
        WaterSim.run(s, 200)
        assertEquals(0, s.waterCount(), "the slug lands and vanishes")
    }

    private val floor = layout(
        "#########S##########",
        "#..................#",
        "####################",
    )

    @Test
    fun `a landing cell spreads seven blocks each way with levels one to seven`() {
        val s = state(floor)
        s.addExternal(External.Source(1, true))
        WaterSim.run(s, 200)
        for (d in 1 .. 7) {
            assertEquals(d, s.level[floor.at(1, 9 + d)].toInt(), "right $d")
            assertEquals(d, s.level[floor.at(1, 9 - d)].toInt(), "left $d")
        }
        assertFalse(s.wet(floor.at(1, 17)))
        assertFalse(s.wet(floor.at(1, 1)))
        assertTrue(s.settled)
    }

    /** Landing cell at column 9; a hole in the floor [offset] cells to the right (none to the left). */
    private fun holeBoard(offset: Int, leftOffset: Int? = null, wallAt: Int? = null): BoardLayout {
        val floorRow = CharArray(20) { '#' }.also { row ->
            row[9 + offset] = '.'
            leftOffset?.let { row[9 - it] = '.' }
        }
        val flowRow = "#..................#".toCharArray().also { row -> wallAt?.let { row[9 + it] = '#' } }
        return layout("#########S##########", String(flowRow), String(floorRow), "####################")
    }

    private fun firstSideCells(board: BoardLayout): Pair<Boolean, Boolean> {
        val s = state(board)
        s.addExternal(External.Source(1, true))
        // The entry ticks at 6 (falls), the landing cell is placed at 6 and ticks at 11: its sideways placements happen then.
        WaterSim.run(s, 11, stopWhenSettled = false)
        return s.wet(board.at(1, 8)) to s.wet(board.at(1, 10))
    }

    @Test
    fun `the hole search attracts the flow to holes up to five blocks away`() {
        for (offset in 1 .. 5) assertEquals(false to true, firstSideCells(holeBoard(offset)), "hole at offset $offset")
        assertEquals(true to true, firstSideCells(holeBoard(6)), "a hole six blocks away is not seen")
        assertEquals(true to true, firstSideCells(holeBoard(3, leftOffset = 3)), "equally near holes split the flow")
        assertEquals(false to true, firstSideCells(holeBoard(3, leftOffset = 4)), "the nearer hole wins")
        assertEquals(true to true, firstSideCells(holeBoard(4, wallAt = 2)), "a wall cuts the search")
    }

    @Test
    fun `water standing on water does not spread sideways`() {
        val board = layout(
            "###S###",
            "#.....#",
            "#.....#",
            "#.....#",
            "#######",
        )
        val s = state(board)
        s.addExternal(External.Source(1, true))
        WaterSim.run(s, 300)
        for (row in 1 .. 2) for (col in listOf(1, 2, 4, 5)) assertFalse(s.wet(board.at(row, col)), "row $row col $col")
        for (col in 1 .. 5) assertTrue(s.wet(board.at(3, col)), "floor col $col")
    }

    @Test
    fun `sideways water decays once the feed stops`() {
        val s = state(floor)
        s.addExternals(listOf(External.Source(1, true), External.Source(60, false)))
        WaterSim.run(s, 200)
        assertEquals(0, s.waterCount())
        assertTrue(s.settled)
    }

    @Test
    fun `a cell vanishing over air leaves a level-7 drip that falls and vanishes on landing`() {
        val board = layout(
            "#######",
            "#.a...#",
            "###B###",
            "###.###",
            "###.###",
            "###.###",
            "#######",
        )
        for (drip in listOf(true, false)) {
            val s = state(board, WaterRules(dripQuirk = drip))
            s.setWater(board.at(1, 2), 2)
            s.setWater(board.at(1, 3), 3)
            // Lever 0 pushes its block into the water next to (1,3); lever 1 pulls the floor out from under it.
            s.addExternals(listOf(External.Flip(1, 0), External.Flip(1, 1)))
            val rec = Recorder()
            WaterSim.run(s, 100, rec)
            if (drip) {
                assertEquals(listOf(Triple(board.at(2, 3), 7, 5), Triple(board.at(3, 3), 7, 10), Triple(board.at(4, 3), 7, 15), Triple(board.at(5, 3), 7, 20)), rec.placed)
            }
            else assertEquals(emptyList(), rec.placed)
            assertEquals(0, s.waterCount())
        }
    }

    @Test
    fun `a block moving into water destroys it and the water behind it decays`() {
        val board = layout(
            "#S#########",
            "#.A.......#",
            "###########",
        )
        val s = state(board)
        s.mask = 1 // lever 0 starts retracted: the flow runs through column 2
        s.addExternal(External.Source(1, true))
        WaterSim.run(s, 100)
        assertTrue(s.wet(board.at(1, 2)) && s.wet(board.at(1, 6)))
        s.addExternal(External.Flip(101, 0))
        WaterSim.run(s, 300)
        assertFalse(s.wet(board.at(1, 2)), "the block replaced the water")
        for (col in 3 .. 9) assertFalse(s.wet(board.at(1, col)), "col $col decayed")
        assertTrue(s.wet(board.at(1, 1)))
    }

    @Test
    fun `a block moving out from under flowing water turns it into a fall`() {
        val board = layout(
            "#S#######",
            "#.......#",
            "####A####",
            "####.####",
            "#########",
        )
        val s = state(board)
        s.addExternal(External.Source(1, true))
        WaterSim.run(s, 100)
        val above = board.at(1, 4)
        assertEquals(3, s.level[above].toInt())
        s.addExternal(External.Flip(101, 0))
        val rec = Recorder()
        WaterSim.run(s, 300, rec)
        // Woken 4 ticks after the flip, the cell keeps its level (its neighbours still feed it) and falls into the hole.
        assertEquals(Triple(board.at(2, 4), 3 + 8, 105), rec.placed.first())
        assertTrue(s.wet(board.at(3, 4)))
        // 1.8 keeps the old stream past the hole: levels come from the neighbours, not from where the water goes.
        assertTrue(s.wet(board.at(1, 5)))
    }

    @Test
    fun `still water never ticks again until a neighbour changes`() {
        val board = layout(
            "#####",
            "#.a.#",
            "#####",
        )
        val s = state(board)
        s.setWater(board.at(1, 1), 1)
        WaterSim.run(s, 50, stopWhenSettled = false)
        assertEquals(0, s.lastChange)
        assertTrue(s.settled)

        // Lever 0's block moves into (1,2) and back out: (1,1) is woken 4 ticks after each change.
        s.addExternals(listOf(External.Flip(60, 0), External.Flip(70, 0)))
        val rec = Recorder()
        WaterSim.run(s, 120, rec)
        // (1,1) has no support (no neighbour water, nothing above): it vanishes when woken at 64.
        assertEquals(listOf(board.at(1, 1) to 64), rec.removed)
    }

    @Test
    fun `same-tick spreading follows the room rotation`() {
        for ((rot, plusFirst) in listOf(0 to false, 90 to true, 180 to true, 270 to false)) {
            assertEquals(plusFirst, BoardRotation.plusXFirst(rot), "rotation $rot")
            val s = state(floor, WaterRules(rotation = rot))
            s.addExternal(External.Source(1, true))
            val rec = Recorder()
            WaterSim.run(s, 11, rec, stopWhenSettled = false)
            val sides = rec.placed.filter { it.third == 11 && it.second == 1 }.map { it.first }
            val expected = if (plusFirst) listOf(floor.at(1, 10), floor.at(1, 8)) else listOf(floor.at(1, 8), floor.at(1, 10))
            assertEquals(expected, sides, "rotation $rot")
        }
    }

    @Test
    fun `neighbour notifications follow west east down up north south`() {
        assertContentEquals(intArrayOf(Dir.LEFT, Dir.RIGHT, Dir.DOWN, Dir.UP), BoardRotation.notifyOrder(0))
        assertContentEquals(intArrayOf(Dir.DOWN, Dir.UP, Dir.RIGHT, Dir.LEFT), BoardRotation.notifyOrder(90))
        assertContentEquals(intArrayOf(Dir.RIGHT, Dir.LEFT, Dir.DOWN, Dir.UP), BoardRotation.notifyOrder(180))
        assertContentEquals(intArrayOf(Dir.DOWN, Dir.UP, Dir.LEFT, Dir.RIGHT), BoardRotation.notifyOrder(270))
        assertContentEquals(intArrayOf(Dir.LEFT, Dir.RIGHT, Dir.DOWN, Dir.UP), BoardRotation.notifyOrder(360))
    }

    @Test
    fun `an outlet counts one arrival per dry to wet transition, draining counts none`() {
        val board = layout(
            "#S#####",
            "#.....#",
            "##O####",
            "##.####",
            "#######",
        )
        val s = state(board)
        s.addExternals(listOf(External.Source(1, true), External.Source(80, false), External.Source(200, true)))
        val rec = Recorder()
        WaterSim.run(s, 400, rec)
        assertEquals(2, s.arrivals[0])
        assertEquals(2, rec.arrivals.size)
        assertTrue(rec.arrivals[1].second > 200)
    }

    @Test
    fun `a pending tick survives its water being destroyed and fires early when water flows back`() {
        val board = layout(
            "#S###",
            "#.a.#",
            "#####",
        )
        val c = board.at(1, 2)
        val s = state(board)
        s.sourceOn = true
        s.setWater(board.at(0, 1), 8)
        s.setWater(board.at(1, 1), 8)
        s.setWater(c, 1, flowing = true)
        s.schedule(c, 7)
        // Lever 0's block moves into c at 1 (destroying the water) and out again at 2.
        s.addExternals(listOf(External.Flip(1, 0), External.Flip(2, 0)))
        val rec = Recorder()
        WaterSim.run(s, 40, rec)
        // (1,1) is woken at 5 and refills c; c then ticks at its old time (7), not at 10, and spreads on.
        assertEquals(Triple(c, 1, 5), rec.placed[0])
        assertEquals(Triple(board.at(1, 3), 2, 7), rec.placed[1])
    }

    @Test
    fun `copies evolve exactly like the original`() {
        val s = state(floor)
        s.addExternals(listOf(External.Source(1, true), External.Source(40, false)))
        WaterSim.run(s, 20, stopWhenSettled = false)
        val copy = s.copy()
        WaterSim.run(s, 150)
        WaterSim.run(copy, 150)
        assertContentEquals(s.level, copy.level)
        assertEquals(s.tick, copy.tick)
        assertEquals(s.lastChange, copy.lastChange)
    }

    @Test
    fun `the slug at the entry and every row below it is exactly off minus on ticks long`() {
        val s = state(shaft)
        s.addExternals(listOf(External.Source(1, true), External.Source(21, false)))
        val rec = Recorder()
        WaterSim.run(s, 120, rec, stopWhenSettled = false)
        for (row in 0 .. 8) {
            val cell = shaft.at(row, 1)
            val wet = rec.placed.first { it.first == cell }.third
            val dry = rec.removed.first { it.first == cell }.second
            assertEquals(20, dry - wet, "row $row: wet at $wet, dry at $dry")
        }
    }

    @Test
    fun `a water off click is played its own lead earlier than an on click, never before it`() {
        val play = ShippedPlay(1, "123", WaterTestData.waterOff)
        val offClick = WaterTestData.ticks(6.9)
        val sources = { rules: WaterRules -> SimRunner.playExternals(play, rules, 100).filterIsInstance<External.Source>() }

        val same = sources(WaterRules(sourceDelay = 7))
        assertEquals(listOf(External.Source(107, true), External.Source(107 + offClick, false)), same)

        val lead = sources(WaterRules(sourceDelay = 7, sourceOffLead = 5))
        assertEquals(listOf(External.Source(107, true), External.Source(102 + offClick, false)), lead)
        assertEquals(offClick - 5, lead[1].tick - lead[0].tick, "the slug is 5 ticks shorter")

        val tiny = ShippedPlay(1, "123", WaterTestData.waterOff + (WaterLever.WATER to listOf(0.0, 0.1)))
        val clamped = SimRunner.playExternals(tiny, WaterRules(sourceDelay = 3, sourceOffLead = 5), 100).filterIsInstance<External.Source>()
        assertTrue(clamped[1].tick > clamped[0].tick, "$clamped")
    }
}
