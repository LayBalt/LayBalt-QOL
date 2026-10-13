package puzzles.water

import com.laybalt.features.impl.dungeon.solvers.puzzles.water.*
import kotlin.test.*

/** The shadow check stays quiet while the simulation matches the board and demotes it when it does not. */
class ShadowCheckTest {
    private val layout = ToyBoards.comb
    private val rules = WaterRules(leverDelay = 1, sourceDelay = 15, externalDelay = 4)
    private val c = ToyBoards.C.ordinal

    /** Keeps the water moving: holes open and close, the channel gets blocked and opened, the water goes off. */
    private val busy = listOf(
        External.Source(16, true), External.Flip(73, c), External.Flip(73, ToyBoards.B.ordinal), External.Flip(73, ToyBoards.D.ordinal),
        External.Flip(127, ToyBoards.A.ordinal), External.Flip(127, ToyBoards.E.ordinal), External.Flip(181, ToyBoards.B.ordinal),
        External.Flip(181, ToyBoards.F.ordinal), External.Flip(235, c), External.Flip(289, ToyBoards.F.ordinal), External.Source(343, false),
    )

    /** Runs a real board ([truthLayout], [truthRules]) and shadows it with the simulator's layout and rules. */
    private fun shadowRun(truthRules: WaterRules, truthLayout: BoardLayout = layout, externals: List<External> = busy): ShadowCheck {
        val truth = BoardState(truthLayout, truthRules)
        truth.mask = ToyBoards.C.bit
        truth.addExternals(externals)
        val shadow = ShadowCheck(layout, rules)
        shadow.start(0, truth.mask)
        while (truth.tick < 420) {
            val mask = truth.mask
            WaterSim.step(truth)
            for (l in 0 until 6) if ((truth.mask xor mask) and (1 shl l) != 0) shadow.onFlip(l, truth.tick)
            shadow.tick(truth.tick, truth.level)
        }
        return shadow
    }

    @Test
    fun `a simulation that matches the board is never demoted`() {
        val shadow = shadowRun(rules)
        assertTrue(shadow.ticksCompared >= 400, "${shadow.ticksCompared} ticks")
        assertFalse(shadow.demoted)
        assertTrue(shadow.clean)
        assertEquals(0, shadow.maxDiff)
    }

    @Test
    fun `water moving at another pace demotes the simulation`() {
        val shadow = shadowRun(rules.copy(tickDelay = 3))
        assertTrue(shadow.demoted, "max ${shadow.maxDiff}, mean ${shadow.meanDiff}")
        assertTrue(shadow.firstDivergence >= 0)
    }

    @Test
    fun `a board that differs from the layout the simulator knows demotes it`() {
        // A block in the channel the simulator does not know about: the water never goes where it expects.
        val rows = ToyBoards.combRows().toMutableList()
        rows[1] = rows[1].substring(0, 10) + "#" + rows[1].substring(11)
        val shadow = shadowRun(rules, BoardLayout.fromAscii(rows))
        assertTrue(shadow.demoted, "max ${shadow.maxDiff}, mean ${shadow.meanDiff}")
    }

    @Test
    fun `lever moves reported late are still applied when they happened`() {
        val truth = BoardState(layout, rules)
        truth.mask = ToyBoards.C.bit
        truth.addExternals(busy)
        val shadow = ShadowCheck(layout, rules)
        shadow.start(0, truth.mask)
        val reports = ArrayList<Pair<Int, Int>>() // (reported at, lever) - two ticks after the move, like a piston settling
        val moves = ArrayList<Pair<Int, Int>>()
        while (truth.tick < 420) {
            val mask = truth.mask
            WaterSim.step(truth)
            for (l in 0 until 6) if ((truth.mask xor mask) and (1 shl l) != 0) moves += truth.tick to l
            moves.filter { it.first + 2 == truth.tick }.forEach { shadow.onFlip(it.second, it.first) }
            shadow.tick(truth.tick, truth.level)
        }
        assertEquals(0, shadow.maxDiff)
        assertTrue(reports.isEmpty())
    }

    @Test
    fun `water the simulation cannot hold (above the board, inside blocks) is not counted`() {
        val shadow = ShadowCheck(layout, rules)
        shadow.start(0, 0)
        val levels = ByteArray(layout.size) { - 1 }
        val wall = (0 until layout.size).first { layout.wall[it] }
        levels[wall] = 8
        for (t in 1 .. 40) shadow.tick(t, levels)
        assertTrue(shadow.ticksCompared > 0)
        assertEquals(0, shadow.maxDiff)
    }

    @Test
    fun `the summary line is one JSON object`() {
        val line = shadowRun(rules).summaryJson(2, "024", rules.describe())
        assertTrue(line.startsWith("{") && line.endsWith("}") && "\n" !in line)
        assertTrue(line.contains("\"key\":\"024\""))
    }
}
