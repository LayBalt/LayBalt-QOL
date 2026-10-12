package puzzles.water

import com.laybalt.features.impl.dungeon.solvers.puzzles.water.*
import com.laybalt.features.impl.dungeon.solvers.puzzles.water.BoardGeometry.cell
import com.laybalt.features.impl.dungeon.solvers.puzzles.water.WaterLever.*
import kotlin.test.*

class BoardTrackerTest {
    // Variant 3's indicator: quartz at (-1, 78), behind the board in an untouched room.
    private val quartz = listOf(cell(- 1, 78), cell(4, 70))
    private val gold = listOf(cell(1, 78), cell(3, 74), cell(- 6, 66))
    private val coal = listOf(cell(5, 77), cell(- 2, 63))

    /** A tracker that has read an untouched variant-3 board: Quartz behind, Gold and Coal in the board plane. */
    private fun tracker(powered: Map<WaterLever, Boolean> = WaterLever.entries.associateWith { false }, quartzFront: Boolean = false): BoardTracker {
        val t = BoardTracker()
        for (c in 0 until BoardGeometry.N) {
            t.setFront(c, BoardCodes.AIR)
            t.setBack(c, BoardCodes.WALL)
        }
        quartz.forEach { if (quartzFront) t.setFront(it, BoardCodes.toggle(QUARTZ.ordinal)) else t.setBack(it, BoardCodes.toggle(QUARTZ.ordinal)) }
        gold.forEach { t.setFront(it, BoardCodes.toggle(GOLD.ordinal)) }
        coal.forEach { t.setFront(it, BoardCodes.toggle(COAL.ordinal)) }
        Door.entries.forEach { t.setDoor(it, it.digit in listOf(0, 2, 4)) }
        powered.forEach { (l, on) -> t.setPowered(l, on) }
        t.available = true
        assertTrue(t.captureLayout())
        t.update(100)
        t.drainEvents()
        return t
    }

    /** Moves [cells] of [lever] to the other plane. */
    private fun BoardTracker.move(lever: WaterLever, cells: List<Int>) {
        val code = BoardCodes.toggle(lever.ordinal)
        for (c in cells) {
            if (front[c] == code) {
                setFront(c, BoardCodes.AIR)
                setBack(c, code)
            }
            else {
                setBack(c, BoardCodes.WALL)
                setFront(c, code)
            }
        }
    }

    private fun BoardTracker.flips() = drainEvents().filterIsInstance<BoardEvent.LeverFlipped>()

    @Test
    fun `the variant is found in either plane and a moved indicator marks its lever flipped`() {
        val untouched = tracker()
        assertEquals(3, untouched.variant)
        assertEquals(0, untouched.parity)
        assertEquals(0, untouched.parityUncertain)

        val flipped = tracker(powered = WaterLever.entries.associateWith { it == QUARTZ }, quartzFront = true)
        assertEquals(3, flipped.variant)
        assertEquals(QUARTZ.bit, flipped.parity)
        assertEquals(0, flipped.parityUncertain)
    }

    @Test
    fun `lever states are only trusted when they agree with the indicator`() {
        val t = tracker(powered = WaterLever.entries.associateWith { it == QUARTZ || it == COAL })
        // The indicator says Quartz is untouched, the lever says flipped: lever states are ignored.
        assertEquals(0, t.parity)
        assertEquals(BoardTracker.ALL_LEVERS and QUARTZ.bit.inv(), t.parityUncertain)

        val agree = tracker(powered = WaterLever.entries.associateWith { it == COAL })
        assertEquals(COAL.bit, agree.parity)
        assertEquals(0, agree.parityUncertain)
    }

    @Test
    fun `a flip is read from the planes and attributed to our click`() {
        val t = tracker()
        t.ownClick(GOLD, 101)
        t.update(102)
        t.move(GOLD, gold)
        t.update(103)
        val flip = t.flips().single()
        assertEquals(GOLD, flip.lever)
        assertEquals(102, flip.tick)
        assertEquals(101, flip.clickTick)
        assertEquals(FlipSource.OWN, flip.source)
        assertEquals(GOLD.bit, t.parity)
        assertEquals(1, t.lag(0))
    }

    @Test
    fun `a partial swap is a transit and the flip is dated from the first block that moved`() {
        val t = tracker()
        t.update(101)
        t.move(GOLD, gold.take(1))
        t.update(102)
        assertEquals(emptyList(), t.flips())
        assertEquals(GOLD.bit, t.transit)
        assertEquals(0, t.parity)
        t.move(GOLD, gold.drop(1))
        t.update(104)
        assertEquals(101, t.flips().single().tick)
        assertEquals(0, t.transit)
        assertEquals(GOLD.bit, t.parity)
    }

    @Test
    fun `blocks that move out and back inside one transit leave the parity alone`() {
        val t = tracker()
        t.update(101)
        t.move(GOLD, gold.take(2))
        t.update(102)
        t.move(GOLD, gold.take(2))
        t.update(103)
        assertEquals(emptyList(), t.flips())
        assertEquals(0, t.parity)
        assertEquals(0, t.transit)
    }

    @Test
    fun `a chunk resend in the middle of a flip gives one flip, dated from the first move`() {
        val t = tracker()
        t.ownClick(COAL, 100)
        t.update(101)
        t.move(COAL, coal.take(1))
        t.update(102)
        // The rest arrives as a whole-chunk resend twelve ticks later: one flip, still ours, still dated 101.
        t.update(110)
        t.move(COAL, coal.drop(1))
        t.update(114)
        val flip = t.flips().single()
        assertEquals(101, flip.tick)
        assertEquals(FlipSource.OWN, flip.source)
    }

    @Test
    fun `a stuck half swap resolves to the majority after the transit limit`() {
        val t = tracker()
        t.update(101)
        t.move(GOLD, gold.take(2))
        for (tick in 102 .. 101 + BoardTracker.TRANSIT_LIMIT) t.update(tick)
        assertEquals(emptyList(), t.flips())
        t.update(102 + BoardTracker.TRANSIT_LIMIT)
        assertEquals(GOLD, t.flips().single().lever)
        assertTrue(t.drainNotes().single().contains("Gold"))
    }

    @Test
    fun `one click that flips a lever twice gives a double flip`() {
        val t = tracker()
        t.ownClick(GOLD, 100)
        t.update(101)
        t.move(GOLD, gold)
        t.update(102)
        t.move(GOLD, gold)
        t.update(104)
        val flips = t.flips()
        assertEquals(listOf(FlipSource.OWN, FlipSource.DOUBLE), flips.map { it.source })
        assertEquals(listOf(100, 100), flips.map { it.clickTick })
        assertEquals(0, t.parity)
    }

    @Test
    fun `a flip without our click is someone else's`() {
        val t = tracker()
        t.rttTicks = 3
        t.update(150)
        t.move(COAL, coal)
        t.update(151)
        val flip = t.flips().single()
        assertEquals(FlipSource.OTHER, flip.source)
        assertEquals(150 - 4, flip.clickTick)
    }

    @Test
    fun `doors are debounced`() {
        val t = tracker()
        t.update(101)
        t.setDoor(Door.BLUE, false)
        t.update(102)
        t.setDoor(Door.BLUE, true)
        t.update(103)
        t.update(104)
        assertEquals(emptyList(), t.drainEvents())
        t.setDoor(Door.RED, false)
        t.update(105)
        t.update(106)
        t.update(107)
        assertEquals(listOf(BoardEvent.DoorToggled(Door.RED, false, 104)), t.drainEvents())
        assertEquals(Door.bitsOf("02"), t.doors)
    }

    private fun BoardTracker.entry(wet: Boolean) {
        setWater(BoardGeometry.ENTRY_CELL, if (wet) 8 else - 1)
        setWater(BoardGeometry.ENTRANCE_CELL, if (wet) 8 else - 1)
    }

    @Test
    fun `the water lever switches the water at once and the board confirms it`() {
        val t = tracker()
        t.ownClick(WATER, 101)
        assertTrue(t.waterClickPending)
        t.update(102)
        t.setPowered(WATER, true)
        t.update(103)
        val on = t.drainEvents().single() as BoardEvent.WaterSwitched
        assertTrue(on.on)
        assertEquals(FlipSource.OWN, on.source)
        assertEquals(101, on.clickTick)
        assertTrue(t.waterOn)
        assertFalse(t.waterClickPending)
        t.entry(true)
        t.update(115)
        assertEquals(emptyList(), t.drainEvents())
        assertEquals(0, t.unconfirmedSwitches)
    }

    @Test
    fun `a lever change the board never follows is corrected`() {
        val t = tracker()
        t.update(101)
        t.setPowered(WATER, true)
        t.update(102)
        assertTrue((t.drainEvents().single() as BoardEvent.WaterSwitched).on)
        for (tick in 103 .. 101 + BoardTracker.BOARD_CONFIRM_TICKS) t.update(tick)
        assertEquals(emptyList(), t.drainEvents())
        t.update(102 + BoardTracker.BOARD_CONFIRM_TICKS)
        val fix = t.drainEvents().single() as BoardEvent.WaterSwitched
        assertFalse(fix.on)
        assertTrue(fix.correction)
        assertFalse(t.waterOn)
        assertEquals(1, t.unconfirmedSwitches)
    }

    @Test
    fun `without lever updates the water is read from the entry hole`() {
        val t = tracker()
        t.ownClick(WATER, 100)
        t.update(101)
        t.entry(true)
        t.update(112)
        val on = t.drainEvents().single() as BoardEvent.WaterSwitched
        assertTrue(on.on)
        assertEquals(FlipSource.OWN, on.source)
        assertEquals(100, on.clickTick)
        t.entry(false)
        t.update(160)
        val off = t.drainEvents().single() as BoardEvent.WaterSwitched
        assertFalse(off.on)
        assertEquals(FlipSource.OTHER, off.source)
    }

    @Test
    fun `board water counts the board only, not the shaft above it`() {
        val t = tracker()
        t.update(101)
        t.setWater(cell(0, 82), 8)
        t.update(102)
        assertEquals(0, t.boardWater)
        t.setWater(cell(3, 70), 2)
        t.setWater(cell(0, 79), 8)
        t.update(103)
        assertEquals(2, t.boardWater)
        assertEquals(102, t.lastWaterChange)
        t.setWater(cell(3, 70), - 1)
        t.update(104)
        assertEquals(1, t.boardWater)
    }

    @Test
    fun `the reference codes put every toggle in its reference plane`() {
        val t = tracker()
        t.update(101)
        t.move(GOLD, gold)
        t.update(102)
        val (front, back) = t.referenceCodes()
        gold.forEach { assertEquals(BoardCodes.toggle(GOLD.ordinal), front[it]) }
        quartz.forEach { assertEquals(BoardCodes.toggle(QUARTZ.ordinal), back[it]) }
        val layout = BoardLayout.fromCodes(front, back, t.variant)
        gold.forEach { assertTrue(layout.refExtended[it]) }
        assertTrue(layout.extended(gold[0], 0))
        assertFalse(layout.extended(gold[0], t.mask))
    }
}
