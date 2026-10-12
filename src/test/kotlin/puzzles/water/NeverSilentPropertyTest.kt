package puzzles.water

import com.laybalt.features.impl.dungeon.solvers.puzzles.water.*
import com.laybalt.features.impl.dungeon.solvers.puzzles.water.WaterSupervisor.Stage
import kotlin.random.Random
import kotlin.test.*

/**
 * Random boards and events (flips by anyone, the water switched on and off, doors toggling, water appearing, moving,
 * standing still and draining, the board becoming unreadable): while the puzzle is unsolved the supervisor always has
 * something to show, and it only claims "solved" after every door read open.
 */
class NeverSilentPropertyTest {
    @Test
    fun `guidance is never silent while the puzzle is unsolved`() {
        val random = Random(20261013)
        repeat(10_000) { stream ->
            val table = WaterTestData.tables.getValue(random.nextInt(4))
            val start = random.nextInt(1, Door.ALL + 1)
            val board = FakeBoard().apply {
                doors = start
                parity = random.nextInt(64)
                parityUncertain = if (random.nextInt(4) == 0) random.nextInt(64) else 0
                waterOn = random.nextInt(5) == 0
                boardWater = if (waterOn || random.nextInt(5) == 0) random.nextInt(1, 60) else 0
                lastWaterChange = now
            }
            val sup = WaterSupervisor(table).apply {
                recovery = random.nextInt(10) != 0
                toleranceTicks = random.nextInt(4, 60)
            }
            var everOpen = false
            repeat(150) {
                board.now ++
                board.ready = random.nextInt(30) != 0
                when (random.nextInt(14)) {
                    0 -> board.flip(WaterLever.blockLevers.random(random), FlipSource.entries.random(random))
                    1 -> board.water(! board.waterOn, FlipSource.entries.random(random))
                    2 -> board.toggle(Door.entries.random(random))
                    3 -> board.flowing(random.nextInt(1, 80))
                    4 -> board.boardWater = 0
                    5 -> board.waterClickPending = ! board.waterClickPending
                    6 -> board.transit = if (random.nextBoolean()) 0 else 1 shl random.nextInt(6)
                    7 -> board.events += BoardEvent.WaterSwitched(random.nextBoolean(), board.now, board.now, FlipSource.OTHER, correction = true)
                    8 -> if (board.waterOn) board.flowing()
                    else -> {}
                }
                if (board.ready && board.doors == 0) everOpen = true
                sup.update(board, board.events.toList())
                board.events.clear()
                sup.drainMessages()
                val g = sup.guidance(board)
                if (g == Guidance.Solved) assertTrue(everOpen, "stream $stream: solved without the doors ever reading open")
                else assertFalse(g.silent, "stream $stream: silent in ${sup.stage}")
                if (sup.stage != Stage.SOLVED && sup.stage != Stage.READING && board.ready) {
                    // A stage that asks for an action always names the lever.
                    if (g is Guidance.Act) assertTrue(g.labels.isNotEmpty() || g.headline != null)
                }
            }
        }
    }

    @Test
    fun `nothing is drawn while the player is away from the room`() {
        val samples = listOf(
            Guidance.Solved,
            Guidance.Status("§7Water Board: reading the board…"),
            Guidance.Act(listOf(LeverLabel(WaterLever.WATER, "§lWATER OFF", LabelStyle.FIX)), WaterLever.WATER, null, "turn the water off"),
        )
        for (g in samples) {
            assertNull(GuidanceGate.forDisplay(g, inRoom = false, distanceToRoom = 60.0))
            assertEquals(g, GuidanceGate.forDisplay(g, inRoom = true, distanceToRoom = 60.0))
            assertEquals(g, GuidanceGate.forDisplay(g, inRoom = false, distanceToRoom = 10.0))
        }
    }
}
