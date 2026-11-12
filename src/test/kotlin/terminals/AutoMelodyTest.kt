package terminals

import com.laybalt.features.impl.floor7.terminals.AutoMelody
import com.laybalt.features.impl.floor7.terminals.AutoMelody.SKIP_ALL
import com.laybalt.features.impl.floor7.terminals.AutoMelody.SKIP_EDGES
import kotlin.test.*

/** Auto Melody (NoammAddons melodyTick) on LayBalt's 3-row Melody: clay buttons 16, 25, 34. */
class AutoMelodyTest {
    @Test
    fun `the clay buttons are column 7 of the three lane rows`() {
        assertEquals(listOf(16, 25, 34), (0 until AutoMelody.ROWS).map(AutoMelody::claySlot))
    }

    @Test
    fun `clicks the row's button when the green pane is in the marked column`() {
        assertEquals(16, AutoMelody.clickFor(current = 2, correct = 2, buttonRow = 0, sinceLastClickMs = 10_000, lastClickedSlot = null))
        assertEquals(25, AutoMelody.clickFor(current = 0, correct = 0, buttonRow = 1, sinceLastClickMs = 10_000, lastClickedSlot = 16))
        assertEquals(34, AutoMelody.clickFor(current = 4, correct = 4, buttonRow = 2, sinceLastClickMs = 10_000, lastClickedSlot = null))
    }

    @Test
    fun `waits while the green pane is elsewhere`() {
        for (current in 0 .. 4) if (current != 3) assertNull(AutoMelody.clickFor(current, 3, 1, 10_000, null))
    }

    @Test
    fun `keeps 250ms between clicks`() {
        assertNull(AutoMelody.clickFor(1, 1, 0, 249, null))
        assertEquals(16, AutoMelody.clickFor(1, 1, 0, 250, null))
    }

    @Test
    fun `does not click the same button again before a slot update`() {
        assertNull(AutoMelody.clickFor(1, 1, 1, 10_000, lastClickedSlot = 25))
    }

    @Test
    fun `needs the whole state and a lane row`() {
        assertNull(AutoMelody.clickFor(null, 1, 0, 10_000, null))
        assertNull(AutoMelody.clickFor(1, null, 0, 10_000, null))
        assertNull(AutoMelody.clickFor(1, 1, null, 10_000, null))
        assertNull(AutoMelody.clickFor(1, 1, 3, 10_000, null))
        assertNull(AutoMelody.clickFor(1, 1, - 1, 10_000, null))
    }

    @Test
    fun `skips hit the following rows' buttons one tick apart`() {
        assertEquals(listOf(1 to 25, 2 to 34), AutoMelody.skipClicks(buttonRow = 0, current = 4, mode = SKIP_EDGES, skipFirstRow = false))
        assertEquals(listOf(1 to 34), AutoMelody.skipClicks(buttonRow = 1, current = 0, mode = SKIP_EDGES, skipFirstRow = false))
    }

    @Test
    fun `nothing is skipped from the last row, and never past it`() {
        assertEquals(emptyList(), AutoMelody.skipClicks(2, 0, SKIP_ALL, true))
        // upstream also clicked slot 43 from the second row (left from the 4-row Melody)
        val slots = (0 until AutoMelody.ROWS).flatMap { row -> AutoMelody.skipClicks(row, 4, SKIP_ALL, true).map { it.second } }
        assertTrue(slots.all { it in listOf(25, 34) }, "skip slots $slots")
    }

    @Test
    fun `edges mode only skips from the first and last column`() {
        assertEquals(listOf(1 to 34), AutoMelody.skipClicks(1, 0, SKIP_EDGES, false))
        assertEquals(listOf(1 to 34), AutoMelody.skipClicks(1, 4, SKIP_EDGES, false))
        for (column in 1 .. 3) assertEquals(emptyList(), AutoMelody.skipClicks(1, column, SKIP_EDGES, false))
        for (column in 0 .. 4) assertEquals(listOf(1 to 34), AutoMelody.skipClicks(1, column, SKIP_ALL, false))
    }

    @Test
    fun `without Skip First Row the first row only skips from its last column`() {
        for (column in 0 .. 3) assertEquals(emptyList(), AutoMelody.skipClicks(0, column, SKIP_ALL, skipFirstRow = false))
        assertEquals(listOf(1 to 25, 2 to 34), AutoMelody.skipClicks(0, 4, SKIP_ALL, skipFirstRow = false))
        assertEquals(listOf(1 to 25, 2 to 34), AutoMelody.skipClicks(0, 2, SKIP_ALL, skipFirstRow = true))
        // Edges still applies on top of it
        assertEquals(emptyList(), AutoMelody.skipClicks(0, 2, SKIP_EDGES, skipFirstRow = true))
        assertEquals(listOf(1 to 25, 2 to 34), AutoMelody.skipClicks(0, 0, SKIP_EDGES, skipFirstRow = true))
    }
}
