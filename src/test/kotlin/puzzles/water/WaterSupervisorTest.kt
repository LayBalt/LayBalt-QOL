package puzzles.water

import com.laybalt.features.impl.dungeon.solvers.puzzles.water.*
import com.laybalt.features.impl.dungeon.solvers.puzzles.water.WaterLever.*
import com.laybalt.features.impl.dungeon.solvers.puzzles.water.WaterSupervisor.Cause
import com.laybalt.features.impl.dungeon.solvers.puzzles.water.WaterSupervisor.Stage
import kotlin.random.Random
import kotlin.test.*

class WaterSupervisorTest {
    private val v0 = WaterTestData.tables.getValue(0)

    private fun harness(variant: Int, doors: String, parity: Int = 0) = Harness(WaterTestData.tables.getValue(variant), doors, parity)

    @Test
    fun `every shipped play played perfectly solves the board without an incident`() {
        val random = Random(7)
        for (table in WaterTestData.tables.values) for (play in table.plays.values) for (jittered in listOf(false, true)) {
            val h = Harness(table, play.key)
            h.tick()
            assertEquals(Stage.PREPARE, h.sup.stage, "$play")
            assertEquals(listOf(play.key), h.sup.chain.map { it.key })
            h.playPerfectly(play, jitter = { if (jittered) random.nextInt(- 10, 11) else 0 })
            assertEquals(Stage.SOLVED, h.sup.stage, "$play jittered=$jittered")
            assertEquals(Guidance.Solved, h.guidance())
            assertEquals(emptyList(), h.messages, "$play")
            assertEquals(0, h.sup.restarts)
        }
    }

    @Test
    fun `untouched board shows the solution display without a headline`() {
        val play = WaterTestData.play(0, "012")
        val h = harness(0, "012")
        h.tick()
        val g = h.guidance() as Guidance.Act
        assertNull(g.headline)
        // Pre-water flips are CLICK labels in the solution style; the water lever waits until they are done.
        for (lever in WaterLever.inMask(play.startParity)) assertEquals("§a§lCLICK", h.labelTexts(lever).first())
        assertTrue(h.labelTexts(WATER).none { it.contains("CLICK") })
        assertTrue(h.labelTexts(EMERALD).single().endsWith("7.9s"))
        for (lever in WaterLever.inMask(play.startParity)) h.tick { flip(lever) }
        assertEquals("§a§lCLICK", h.labelTexts(WATER).first())
        assertEquals(WATER, (h.guidance() as Guidance.Act).tracer)
    }

    @Test
    fun `wrong lever before the water is flipped back, then the run continues normally`() {
        val play = WaterTestData.play(0, "012")
        val h = harness(0, "012")
        h.tick()
        h.tick { flip(COAL) }
        assertEquals(listOf("§lFLIP BACK"), h.labelTexts(COAL))
        assertTrue((h.guidance() as Guidance.Act).headline!!.contains("Coal"))
        h.tick { flip(COAL) }
        assertTrue(h.labelTexts(COAL).isEmpty())
        h.playPerfectly(play)
        assertEquals(Stage.SOLVED, h.sup.stage)
        assertEquals(emptyList(), h.messages)
    }

    @Test
    fun `starting the water with a wrong lever stops the run and restarts it from the real doors`() {
        val h = harness(0, "014")
        h.tick()
        h.tick { flip(COAL) }
        h.tick { water(true) }
        assertEquals(Stage.STOP_WATER, h.sup.stage)
        assertTrue(h.messages.single().contains("wrong position"))
        assertEquals(listOf("§lWATER OFF"), h.labelTexts(WATER))
        h.stopAndDrain()
        assertEquals(Stage.PREPARE, h.sup.stage)
        assertEquals(listOf("014"), h.sup.chain.map { it.key })
        // Coal still has to go back: FLIP in the fix style, the water waits.
        assertEquals(listOf("§lFLIP"), h.labelTexts(COAL))
        h.tick { flip(COAL) }
        assertEquals("§lPULL TO RESTART", h.labelTexts(WATER).first())
        h.playPerfectly(WaterTestData.play(0, "014"))
        assertEquals(Stage.SOLVED, h.sup.stage)
    }

    @Test
    fun `restart after a door already opened replays the right key, not the original one`() {
        val play = WaterTestData.play(0, "024")
        val h = harness(0, "024")
        h.tick()
        // Played correctly until the first door (Purple) opened, then a lever the play never uses.
        h.playPerfectly(play, doorAt = { k -> if (k == 0) 40 else 10_000 }, until = 50)
        assertEquals(Door.bitsOf("24"), h.board.doors)
        h.tick { flip(EMERALD) }
        assertEquals(Stage.STOP_WATER, h.sup.stage)
        h.stopAndDrain()
        assertEquals(Stage.PREPARE, h.sup.stage)
        val chain = h.sup.chain
        assertEquals(2, chain.size)
        assertEquals(Door.bitsOf("24"), chain.fold(0) { acc, p -> acc xor p.doors })
        assertFalse(chain.any { it.key == "024" })
    }

    @Test
    fun `two-play restart chain runs both plays and re-reads the doors in between`() {
        val h = harness(0, "24")
        h.tick()
        assertEquals(Stage.PREPARE, h.sup.stage)
        val chain = h.sup.chain
        assertEquals(2, chain.size)
        assertTrue((h.guidance() as Guidance.Act).headline!!.startsWith("§dRun 1/2"))
        h.playPerfectly(chain[0])
        // First play done: the doors read the second play's key now.
        assertEquals(chain[1].doors, h.board.doors)
        if (h.board.waterOn) {
            assertEquals(Stage.STOP_WATER, h.sup.stage)
            h.stopAndDrain()
        }
        else h.ticks(20) { boardWater = 0 }
        assertEquals(Stage.PREPARE, h.sup.stage)
        assertEquals(1, h.sup.chainIndex)
        assertTrue((h.guidance() as Guidance.Act).headline!!.startsWith("§dRun 2/2"))
        h.playPerfectly(chain[1])
        assertEquals(Stage.SOLVED, h.sup.stage)
        assertEquals(emptyList(), h.messages)
    }

    @Test
    fun `a chain whose first play turns the water off itself never asks to turn it off`() {
        // Find door states whose best chain starts with a play that turns the water off itself.
        val cases = WaterTestData.tables.values.flatMap { table ->
            (1 .. Door.ALL).filter { Door.count(it) != 3 }.mapNotNull { closed ->
                val chain = DoorAlgebra.best(closed, table, 0, boardReady = true)
                if (chain.size > 1 && chain.first().turnsWaterOff) table to closed else null
            }
        }
        assertTrue(cases.isNotEmpty())
        for ((table, closed) in cases) {
            val h = Harness(table, Door.keyOf(closed))
            h.tick()
            val play = h.sup.chain.first()
            // Doors that close again toggle first, so the board does not pass through "all open" on the way.
            val order = Door.of(play.doors).sortedBy { closed and it.bit != 0 }
            h.playPerfectly(play, doorAt = { k -> play.lastClickTick * (order.indexOf(Door.of(play.doors)[k]) + 1) / 3 + 25 })
            assertFalse(h.board.waterOn)
            h.ticks(30) { boardWater = if (now % 100 < 20) 10 else 0 }
            h.ticks(15) { boardWater = 0 }
            assertFalse(Stage.STOP_WATER in h.stages, "$play: water already off, straight to draining")
            assertEquals(Stage.PREPARE, h.sup.stage, "$play")
            assertEquals(1, h.sup.chainIndex)
        }
    }

    @Test
    fun `water reaching a channel that had to stay dry is a wrong-channel incident`() {
        val h = harness(0, "014")
        h.tick()
        h.tick { water(true) }
        h.ticks(30) { flowing() }
        h.tick { toggle(Door.BLUE) }
        assertEquals(Stage.STOP_WATER, h.sup.stage)
        assertEquals(Cause.WRONG_CHANNEL, h.sup.lastIncident?.cause)
        assertTrue(h.messages.single().contains("Blue"))
        h.stopAndDrain()
        // Blue is closed now as well: four closed doors need two plays.
        assertEquals(Door.bitsOf("0124"), h.board.doors)
        assertEquals(2, h.sup.chain.size)
    }

    @Test
    fun `water that settles with doors missing is noticed only once no planned flip is left`() {
        val play = WaterTestData.play(3, "234") // Gold at 3.5 s, then nothing until Clay at 10.6 s (a long gap)
        val h = harness(3, "234")
        h.tick()
        // The water stands still during the long gap: no incident while flips are still planned.
        h.playPerfectly(play, doorAt = { 100_000 }, until = play.lastClickTick + 1, flow = { it < 60 })
        assertEquals(emptyList(), h.messages)
        assertEquals(Stage.AWAIT, h.sup.stage)
        h.ticks(WaterSupervisor.SETTLE_TICKS + 5) { boardWater = 30 }
        assertEquals(Cause.SETTLED_UNSOLVED, h.sup.lastIncident?.cause)
        assertTrue(h.messages.single().contains("the water stopped before reaching"))
    }

    @Test
    fun `all flips done with the water still moving but doors missing runs out of time`() {
        val play = WaterTestData.play(0, "014")
        val h = harness(0, "014")
        h.tick()
        h.playPerfectly(play, doorAt = { 100_000 }, until = play.lastClickTick + 1)
        assertEquals(Stage.AWAIT, h.sup.stage)
        h.ticks(WaterSupervisor.EXHAUST_TICKS + 10) { flowing() }
        assertEquals(Cause.EXHAUSTED, h.sup.lastIncident?.cause)
    }

    @Test
    fun `missed flip is a divergence with the late time in the message`() {
        val play = WaterTestData.play(0, "014")
        val h = harness(0, "014")
        h.tick()
        h.playPerfectly(play, flipOverride = { it.lever != CLAY }, doorAt = { 100_000 }, until = WaterTestData.ticks(3.2) + 30)
        assertEquals(Cause.DIVERGENCE, h.sup.lastIncident?.cause)
        assertTrue(h.messages.single().contains("Clay§f was not flipped in time"), h.messages.single())
    }

    @Test
    fun `a teammate's flip is named as such`() {
        val h = harness(0, "014")
        h.tick()
        h.tick { water(true) }
        h.ticks(10) { flowing() }
        h.tick { flip(COAL, FlipSource.OTHER) }
        assertTrue(h.messages.single().contains("by a teammate"), h.messages.single())
    }

    @Test
    fun `a double flick is explained`() {
        val h = harness(0, "014")
        h.tick()
        h.tick { water(true) }
        h.ticks(WaterTestData.ticks(3.2) - 1) { flowing() }
        h.tick { flip(CLAY) }
        h.tick { flip(CLAY, FlipSource.DOUBLE) }
        assertTrue(h.messages.single().contains("one click flipped it twice"), h.messages.single())
    }

    @Test
    fun `pulling the water while draining asks to turn it off again`() {
        val h = harness(0, "014")
        h.tick()
        h.tick { water(true) }
        h.tick { flip(COAL) }
        h.tick { water(false) }
        assertEquals(Stage.DRAIN, h.sup.stage)
        h.ticks(5) { flowing() }
        h.tick { water(true) }
        assertEquals(Stage.STOP_WATER, h.sup.stage)
    }

    @Test
    fun `water off is never shown while our click on the water lever is still on its way`() {
        val h = harness(0, "014")
        h.tick()
        h.tick { water(true) }
        h.tick { flip(COAL) }
        assertEquals(listOf("§lWATER OFF"), h.labelTexts(WATER))
        h.tick { waterClickPending = true }
        val g = h.guidance()
        assertIs<Guidance.Status>(g)
        assertTrue(g.headline.contains("turning the water off"))
        h.tick { waterClickPending = false; water(false) }
        assertEquals(Stage.DRAIN, h.sup.stage)
    }

    @Test
    fun `a run started before we arrived is watched, then taken over when it stalls`() {
        val h = harness(0, "014")
        h.board.waterOn = true
        h.board.boardWater = 40
        h.board.lastWaterChange = h.board.now
        h.tick()
        assertEquals(Stage.FOREIGN, h.sup.stage)
        assertIs<Guidance.Status>(h.guidance())
        h.ticks(50) { flowing() }
        assertEquals(Stage.FOREIGN, h.sup.stage)
        h.ticks(WaterSupervisor.FOREIGN_SETTLE_TICKS + 5) { boardWater = 40 }
        assertEquals(Stage.STOP_WATER, h.sup.stage)
        assertEquals(Cause.FOREIGN_FAILED, h.sup.lastIncident?.cause)
    }

    @Test
    fun `a teammate's finished run is picked up from the doors it left`() {
        val h = harness(0, "014")
        h.board.waterOn = true
        h.board.boardWater = 40
        h.tick()
        h.tick { toggle(Door.PURPLE) }
        h.tick { water(false, FlipSource.OTHER) }
        h.ticks(30) { flowing() }
        h.ticks(15) { boardWater = 0 }
        assertEquals(Stage.PREPARE, h.sup.stage)
        assertEquals(Door.bitsOf("14"), h.sup.chain.fold(0) { acc, p -> acc xor p.doors })
    }

    @Test
    fun `a teammate starting the water from another lever state is their run`() {
        val h = harness(0, "014")
        h.tick()
        h.tick { flip(COAL, FlipSource.OTHER) }
        h.tick { water(true, FlipSource.OTHER); flowing() }
        assertEquals(Stage.FOREIGN, h.sup.stage)
        assertEquals(emptyList(), h.messages)
    }

    @Test
    fun `runs that fail with every flip on time warn about the start state instead of looping silently`() {
        val play = WaterTestData.play(0, "014")
        val h = harness(0, "014")
        h.board.parityUncertain = COAL.bit or GOLD.bit
        h.tick()
        repeat(2) {
            h.playPerfectly(play, doorAt = { 100_000 }, until = play.lastClickTick + 1)
            h.ticks(WaterSupervisor.SETTLE_TICKS + 5) { boardWater = 30 }
            assertEquals(Cause.SETTLED_UNSOLVED, h.sup.lastIncident?.cause)
            h.stopAndDrain()
            assertEquals(Stage.PREPARE, h.sup.stage)
        }
        assertEquals(2, h.sup.silentFailures)
        assertTrue(h.messages.all { it.contains("may not have started in their default position") })
        val headline = (h.guidance() as Guidance.Act).headline!!
        assertTrue(headline.contains("every flip on time") && headline.contains("Coal"), headline)
    }

    @Test
    fun `legacy display keeps following the run without stopping the water`() {
        val play = WaterTestData.play(0, "014")
        val h = harness(0, "014")
        h.sup.recovery = false
        h.tick()
        h.tick { water(true) }
        h.ticks(5) { flowing() }
        h.tick { flip(COAL) }
        assertEquals(Stage.RUNNING, h.sup.stage)
        assertEquals(emptyList(), h.messages)
        assertEquals(play.timedClicks.size, h.labels().count { it.style == LabelStyle.SOLUTION })
    }

    @Test
    fun `a play that opens every door on its way solves the board even in the middle of a chain`() {
        val table = WaterTestData.tables.getValue(2)
        val h = Harness(table, "1")
        h.tick()
        val play = h.sup.chain.first()
        assertTrue(play.doors and Door.ORANGE.bit != 0)
        // Orange opens first: all five doors are open at that moment, which completes the puzzle.
        h.playPerfectly(play, doorAt = { k -> if (Door.of(play.doors)[k] == Door.ORANGE) 30 else 200 })
        assertEquals(Stage.SOLVED, h.sup.stage)
    }

    @Test
    fun `all doors open is solved and stays solved`() {
        val h = harness(0, "014")
        h.tick()
        h.tick { toggle(Door.PURPLE); toggle(Door.ORANGE); toggle(Door.RED) }
        assertEquals(Stage.SOLVED, h.sup.stage)
        h.tick { toggle(Door.BLUE) }
        assertEquals(Guidance.Solved, h.guidance())
    }

    @Test
    fun `nothing happens while the board cannot be read`() {
        val h = harness(0, "014")
        h.board.ready = false
        h.ticks(5)
        assertEquals(Stage.READING, h.sup.stage)
        assertIs<Guidance.Status>(h.guidance())
    }
}
