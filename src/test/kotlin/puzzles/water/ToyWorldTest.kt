package puzzles.water

import com.laybalt.features.impl.dungeon.solvers.puzzles.water.*
import com.laybalt.features.impl.dungeon.solvers.puzzles.water.WaterSupervisor.Stage
import kotlin.test.*

/**
 * Closed-loop tests on the toy comb ([ToyWorld]): the bot only does what the guidance shows, the board is the simulator.
 * Every scenario must end with every door open.
 */
class ToyWorldTest {
    private fun waterClick(w: ToyWorld) = w.clicks.firstOrNull { it.second == WaterLever.WATER }?.first

    /** One extra click of [lever] [afterTicks] after the first water click. */
    private fun strayClick(lever: WaterLever, afterTicks: Int): (ToyWorld) -> Unit {
        var done = false
        return { w ->
            val start = waterClick(w)
            if (! done && start != null && w.now == start + afterTicks) {
                done = true
                w.click(lever)
            }
        }
    }

    private fun playOf(w: ToyWorld, pre: Set<WaterLever>, vararg timed: Pair<WaterLever, Double>): ShippedPlay {
        val want = timed.map { it.first to ShippedPlay.ticks(it.second) }.sortedWith(compareBy({ it.second }, { it.first.ordinal }))
        return w.table.plays.values.first { p ->
            p.startParity == pre.fold(0) { a, l -> a or l.bit } && p.timedClicks.map { it.lever to it.tick } == want
        }
    }

    @Test
    fun `the bot solves every synthetic play by following the timers, without an incident`() {
        val keys = ToyWorld(0).table.plays.keys
        assertTrue(keys.size >= 9)
        for (doors in keys) {
            val w = ToyWorld(doors)
            w.run(1500)
            assertEquals(Stage.SOLVED, w.sup.stage, "doors ${Door.keyOf(doors)}: ${w.messages}")
            assertEquals(emptyList(), w.messages, "doors ${Door.keyOf(doors)}")
            assertTrue(Stage.PLANNING !in w.stages, "no plan needed for ${Door.keyOf(doors)}")
        }
    }

    @Test
    fun `a stray flip mid-run is fixed from where the board is, without draining`() {
        val probe = ToyWorld(0)
        val play = playOf(probe, setOf(ToyBoards.C), ToyBoards.C to 2.0, ToyBoards.B to 2.0, ToyBoards.D to 2.0)
        val w = ToyWorld(play.doors)
        // Clay (lever F) closes the channel between B and C one second in: B could not get water any more.
        w.run(1500, strayClick(ToyBoards.F, 20))
        assertEquals(Stage.SOLVED, w.sup.stage, w.messages.toString())
        assertTrue(Stage.CONTINUE in w.stages)
        assertFalse(Stage.DRAIN in w.stages, "solved without draining")
        assertEquals(1, w.messages.size, w.messages.toString())
        assertTrue(w.messages.single().contains("Fix: §a"), w.messages.single())
    }

    @Test
    fun `without a trusted simulator the same mistake is fixed by draining and restarting`() {
        val probe = ToyWorld(0)
        val play = playOf(probe, setOf(ToyBoards.C), ToyBoards.C to 2.0, ToyBoards.B to 2.0, ToyBoards.D to 2.0)
        val w = ToyWorld(play.doors, trusted = false)
        w.run(4000, strayClick(ToyBoards.F, 20))
        assertEquals(Stage.SOLVED, w.sup.stage, w.messages.toString())
        assertFalse(Stage.PLANNING in w.stages)
        assertTrue(Stage.DRAIN in w.stages)
        assertTrue(w.messages.first().contains("turn the water §cOFF"), w.messages.first())
    }

    @Test
    fun `a flip the player forgets is either done now or worked around`() {
        val probe = ToyWorld(0)
        val play = playOf(probe, setOf(ToyBoards.C), ToyBoards.C to 2.0, ToyBoards.A to 2.0, ToyBoards.E to 2.0)
        val w = ToyWorld(play.doors)
        var skipped = false
        // The bot ignores the first CLICK on Emerald (lever E) for longer than the tolerance: the flip is missed.
        w.run(1500) { world ->
            val g = world.sup.guidance(world) as? Guidance.Act
            if (! skipped && g != null && world.sup.stage == Stage.RUNNING && g.labels.any { it.lever == ToyBoards.E && it.text.contains("CLICK") }) {
                skipped = true
                world.ignore(ToyBoards.E, 25)
            }
        }
        assertTrue(skipped)
        assertEquals(Stage.SOLVED, w.sup.stage, w.messages.toString())
        assertEquals(WaterSupervisor.Cause.DIVERGENCE, w.sup.lastIncident?.cause)
    }

    @Test
    fun `two closed doors are opened in one run by a static setting the simulator found`() {
        // B and D closed: no shipped play has two doors; the static setting "B and D open" reaches both at once.
        val probe = ToyWorld(0)
        val doors = (1 shl probe.perm[ToyBoards.outletOfLever(ToyBoards.B.ordinal)]) or (1 shl probe.perm[ToyBoards.outletOfLever(ToyBoards.D.ordinal)])
        val w = ToyWorld(doors)
        w.step()
        assertEquals(Stage.PREPARE, w.sup.stage)
        assertEquals(1, w.sup.chain.size)
        assertTrue(w.sup.chain.single().synthetic)
        assertTrue((w.sup.guidance(w) as Guidance.Act).headline!!.contains("one run opens"))
        w.run(1500)
        assertEquals(Stage.SOLVED, w.sup.stage, w.messages.toString())
        assertEquals(1, w.clicks.count { it.second == WaterLever.WATER }, "one run")
    }

    @Test
    fun `the same two doors take two shipped plays without the simulator`() {
        val probe = ToyWorld(0)
        val doors = (1 shl probe.perm[ToyBoards.outletOfLever(ToyBoards.B.ordinal)]) or (1 shl probe.perm[ToyBoards.outletOfLever(ToyBoards.D.ordinal)])
        val w = ToyWorld(doors, trusted = false)
        w.step()
        assertEquals(2, w.sup.chain.size)
        w.run(4000)
        assertEquals(Stage.SOLVED, w.sup.stage, w.messages.toString())
    }

    @Test
    fun `when no plan beats a restart the solver drains and restarts`() {
        val probe = ToyWorld(0)
        val play = playOf(probe, setOf(ToyBoards.C), ToyBoards.C to 2.0, ToyBoards.B to 2.0, ToyBoards.D to 2.0)
        val w = ToyWorld(play.doors)
        w.restartTicks = 0 // nothing can beat it
        w.run(4000, strayClick(ToyBoards.F, 20))
        assertEquals(Stage.SOLVED, w.sup.stage, w.messages.toString())
        assertTrue(Stage.PLANNING in w.stages)
        assertFalse(Stage.CONTINUE in w.stages)
        assertTrue(w.messages.first().contains("turn the water"), w.messages.first())
    }
}
