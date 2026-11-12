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
    fun `guidance is never silent with the planner in the loop either`() {
        val random = Random(20261014)
        repeat(4_000) { stream ->
            val table = WaterTestData.tables.getValue(random.nextInt(4))
            val board = FakeBoard().apply {
                doors = random.nextInt(1, Door.ALL + 1)
                parity = random.nextInt(64)
                waterOn = random.nextInt(3) == 0
                boardWater = if (waterOn) random.nextInt(1, 60) else 0
                lastWaterChange = now
                flipLag = random.nextInt(0, 12)
            }
            val sup = WaterSupervisor(table).apply {
                toleranceTicks = random.nextInt(4, 60)
                sim = object : WaterSupervisor.SimSupport {
                    override fun continuationsAllowed() = random.nextInt(5) != 0
                    override fun checksAllowed() = random.nextBoolean()
                    override fun staticPlay(closed: Int, parity: Int) =
                        if (random.nextInt(3) == 0) ShippedPlay.static(table.variant, closed, random.nextInt(64), random.nextInt(50, 400)) else null
                }
            }
            val waiting = ArrayList<WaterSupervisor.Request>()
            var everOpen = false
            repeat(200) {
                board.now ++
                when (random.nextInt(12)) {
                    0 -> board.flip(WaterLever.blockLevers.random(random), FlipSource.entries.random(random))
                    1 -> board.water(! board.waterOn, FlipSource.entries.random(random))
                    2 -> board.toggle(Door.entries.random(random))
                    3 -> board.flowing(random.nextInt(1, 80))
                    4 -> board.boardWater = 0
                    5 -> if (board.waterOn) board.flowing()
                    else -> {}
                }
                if (board.doors == 0) everOpen = true
                sup.update(board, board.events.toList())
                board.events.clear()
                waiting += sup.drainRequests()
                // The planner answers some requests, late, wrongly timed or not at all.
                if (waiting.isNotEmpty() && random.nextInt(3) == 0) {
                    when (val r = waiting.removeAt(random.nextInt(waiting.size))) {
                        is WaterSupervisor.PlanNeeded -> {
                            val steps = List(random.nextInt(0, 4)) { PlanStep(WaterLever.entries.random(random), board.now + random.nextInt(- 10, 80)) }
                            val plan = if (random.nextInt(4) == 0) null
                            else ContinuationPlan(PlanFamily.entries.random(random), steps.sortedBy { it.clickTick }, board.now + random.nextInt(200), board.doors, 3, "fix")
                            sup.onPlanResult(r.id, plan, board)
                        }

                        is WaterSupervisor.CheckNeeded -> sup.onCheckResult(r.id, random.nextBoolean(), "the water is going wrong", board)
                    }
                }
                sup.drainMessages()
                val g = sup.guidance(board)
                if (g == Guidance.Solved) assertTrue(everOpen, "stream $stream: solved without the doors ever reading open")
                else assertFalse(g.silent, "stream $stream: silent in ${sup.stage}")
            }
        }
    }

    @Test
    fun `the room as a whole - raw reads, unloaded chunks, flickers and teammates - is never silent and never solved early`() {
        val random = Random(20261015)
        repeat(300) { stream ->
            val b = FieldBoard(random.nextInt(4))
            // Every combination of the settings that choose the display.
            b.core.recovery = random.nextInt(4) != 0
            b.core.advancedFirst = random.nextBoolean()
            val table = WaterTestData.tables.getValue(b.variant)
            val key = table.plays.values.random(random).key
            val activation = random.nextInt(0, 600)
            var everClosed = false
            var everClosedRead = false
            repeat(900) { i ->
                // FieldBoard.tick itself checks that the display is never silent unless solved.
                b.tick {
                    if (i == activation) closeDoors(key)
                    when (random.nextInt(60)) {
                        0 -> available = ! available
                        1 -> doorsLoaded = ! doorsLoaded
                        2 -> if (i > activation) doors = doors xor Door.entries.random(random).bit
                        3 -> flip(WaterLever.blockLevers.random(random))
                        4 -> click(WaterLever.entries.random(random))
                        5 -> {
                            flip(WaterLever.WATER)
                            entry(powered[WaterLever.WATER.ordinal])
                        }

                        6 -> boardWater(random.nextInt(0, 10))
                        7 -> doorsMoving = if (doorsMoving == 0) Door.entries.random(random).bit else 0
                        8 -> if (random.nextInt(40) == 0) core.markSolved("the map")
                        else -> {}
                    }
                    if (doors != 0) {
                        everClosed = true
                        if (doorsLoaded) everClosedRead = true
                    }
                }
                val g = b.display
                if (g == Guidance.Solved) assertTrue(b.core.confirmedSolved || everClosedRead, "stream $stream tick $i: solved before any door was read closed")
                if (! everClosed && ! b.core.confirmedSolved) assertNotEquals(Guidance.Solved, g, "stream $stream tick $i: solved before the doors closed")
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
