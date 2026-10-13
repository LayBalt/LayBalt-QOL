package puzzles.water

import com.laybalt.features.impl.dungeon.solvers.puzzles.water.Door
import com.laybalt.features.impl.dungeon.solvers.puzzles.water.WaterLever
import com.laybalt.features.impl.dungeon.solvers.puzzles.water.WaterLever.*
import kotlin.test.*

class ShippedTableTest {
    @Test
    fun `all 40 entries parse with ten three-door keys per variant`() {
        val tables = WaterTestData.tables
        assertEquals(setOf(0, 1, 2, 3), tables.keys)
        for ((variant, table) in tables) {
            assertEquals(10, table.plays.size, "variant $variant")
            table.plays.values.forEach { play ->
                assertEquals(3, Door.count(play.doors), "$play")
                assertEquals(play.key, Door.keyOf(play.doors))
            }
            // The ten keys are exactly the 3-subsets of the five doors.
            assertEquals(10, table.keys.toSet().size)
        }
    }

    @Test
    fun `start parity follows the number of pre-water flips`() {
        val play = WaterTestData.play(WaterTestData.preStart, key = "012")
        assertEquals(CLAY.bit or QUARTZ.bit or DIAMOND.bit or GOLD.bit, play.startParity)
        assertEquals(1, play.preWater[QUARTZ.ordinal])
        assertEquals(0, play.preWater[EMERALD.ordinal])
        assertEquals(0, WaterTestData.play(WaterTestData.timed).startParity)

        for (table in WaterTestData.tables.values) for (play in table.plays.values) {
            val expected = WaterLever.blockLevers.fold(0) { acc, l ->
                if (play.times[l].orEmpty().count { it == 0.0 } % 2 == 1) acc or l.bit else acc
            }
            assertEquals(expected, play.startParity, "$play")
        }
    }

    @Test
    fun `timed clicks are sorted and the water-off entries are recognised`() {
        var waterOff = 0
        for (table in WaterTestData.tables.values) for (play in table.plays.values) {
            assertEquals(play.timedClicks.sortedBy { it.tick }.map { it.tick }, play.timedClicks.map { it.tick }, "$play")
            assertTrue(play.timedClicks.all { it.tick > 0 }, "$play")
            assertEquals(play.timedClicks.maxOf { it.tick }, play.lastClickTick)
            if (play.turnsWaterOff) waterOff ++
        }
        assertEquals(12, waterOff)

        val play = WaterTestData.play(WaterTestData.waterOff, key = "123", variant = 1)
        assertEquals(listOf(WaterTestData.ticks(6.9)), play.waterOffTicks)
        assertTrue(play.timedClicks.any { it.lever == WATER && it.tick == 138 })
        assertEquals(200, play.lastClickTick)
    }

    @Test
    fun `end parity adds every timed block flip to the start parity`() {
        val play = WaterTestData.play(WaterTestData.timed)
        // Clay, Gold, Diamond, Emerald once; Quartz twice.
        assertEquals(CLAY.bit or GOLD.bit or DIAMOND.bit or EMERALD.bit, play.endParity)
    }
}
