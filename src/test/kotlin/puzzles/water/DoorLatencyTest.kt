package puzzles.water

import com.laybalt.features.impl.dungeon.solvers.puzzles.water.*
import kotlin.test.*

/** The outlet-to-door latency is measured on the board instead of assumed. */
class DoorLatencyTest {
    @Test
    fun `the default is used until a door has toggled with water at its outlet`() {
        val d = DoorLatency()
        assertFalse(d.measured)
        assertEquals(2, d.value(WaterRules().doorDelay))
        assertEquals(DoorLatency.PENDING_MIN_TICKS, DoorLatency.pendingWindow(d.value(2)))
    }

    @Test
    fun `a door that follows the water into the basin widens the pending window`() {
        // Outlet at y60, basin at y58: two falling cells (10 ticks) plus the door's own delay.
        val d = DoorLatency()
        for (ticks in listOf(13, 12, 14, 13, 40 + 1)) d.learn(ticks)
        assertTrue(d.measured)
        assertEquals(13, d.value(2), "median; a latency past the limit is not one arrival's toggle")
        assertEquals(19, DoorLatency.pendingWindow(d.value(2)))
    }

    @Test
    fun `only recent samples count`() {
        val d = DoorLatency()
        repeat(9) { d.learn(2) }
        repeat(9) { d.learn(14) }
        assertEquals(14, d.value(2))
    }
}
