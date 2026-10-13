package puzzles.water

import com.laybalt.features.impl.dungeon.solvers.puzzles.water.*
import com.laybalt.features.impl.dungeon.solvers.puzzles.water.WaterLever.*
import com.laybalt.features.impl.dungeon.solvers.puzzles.water.WaterSupervisor.Cause
import com.laybalt.features.impl.dungeon.solvers.puzzles.water.WaterSupervisor.Stage
import kotlin.test.*

/** The supervisor's side of planning: requests, answers, fallbacks, checks and static one-run plays (planner faked). */
class SupervisorPlanningTest {
    private class FakeSim(var continuations: Boolean = true, var checks: Boolean = true, var static: ShippedPlay? = null) : WaterSupervisor.SimSupport {
        override fun continuationsAllowed() = continuations
        override fun checksAllowed() = checks
        override fun staticPlay(closed: Int, parity: Int) = static
    }

    private fun harness(doors: String = "014", sim: FakeSim = FakeSim()): Harness {
        val h = Harness(WaterTestData.tables.getValue(0), doors)
        h.sup.sim = sim
        return h
    }

    private fun Harness.requests() = sup.drainRequests()

    /** Starts the shipped play, lets the water flow a little, then flips Coal (the play never uses it). */
    private fun Harness.strayCoal(): WaterSupervisor.PlanNeeded {
        tick()
        tick { water(true) }
        ticks(10) { flowing() }
        sup.drainRequests()
        tick { flip(COAL) }
        return sup.drainRequests().filterIsInstance<WaterSupervisor.PlanNeeded>().single()
    }

    private fun plan(h: Harness, vararg steps: PlanStep, summary: String = "flip Coal now") =
        ContinuationPlan(PlanFamily.REPAIR, steps.toList(), h.board.now + 100, h.board.doors, 4, summary)

    @Test
    fun `a stray flip asks the planner instead of stopping the water`() {
        val h = harness()
        val request = h.strayCoal()
        assertEquals(Stage.PLANNING, h.sup.stage)
        assertEquals(PlanFault(COAL, PlanFollower.Kind.WRONG_LEVER), request.fault)
        assertTrue(request.remaining!!.isNotEmpty(), "the rest of the shipped play goes along")
        assertEquals(emptyList(), h.messages, "the message waits for the plan")
        assertIs<Guidance.Status>(h.guidance())
    }

    @Test
    fun `a plan from the planner is followed with countdowns and announced`() {
        val h = harness()
        val request = h.strayCoal()
        val now = h.board.now
        h.sup.onPlanResult(request.id, plan(h, PlanStep(COAL, now + 10), PlanStep(GOLD, now + 40)), h.board)
        assertEquals(Stage.CONTINUE, h.sup.stage)
        assertTrue(h.sup.drainMessages().single().contains("Fix: §aflip Coal now"))
        val g = h.guidance() as Guidance.Act
        assertEquals(COAL, g.tracer)
        assertEquals(COAL to GOLD, g.line)
        assertTrue(g.headline!!.startsWith("§eFixing the run"))
        h.ticks(10) { flowing() }
        assertEquals("§a§lCLICK", h.labelTexts(COAL).single())
        h.tick { flip(COAL); flowing() }
        h.ticks(29) { flowing() }
        h.tick { flip(GOLD); flowing() }
        assertEquals(Stage.CONTINUE, h.sup.stage)
        h.tick { toggle(Door.PURPLE); flowing() }
        h.tick { toggle(Door.ORANGE); flowing() }
        h.tick { toggle(Door.RED) }
        assertEquals(Stage.SOLVED, h.sup.stage)
    }

    @Test
    fun `no plan means drain and restart, with the usual message`() {
        val h = harness()
        val request = h.strayCoal()
        h.sup.onPlanResult(request.id, null, h.board)
        assertEquals(Stage.STOP_WATER, h.sup.stage)
        assertTrue(h.sup.drainMessages().single().contains("turn the water §cOFF"))
    }

    @Test
    fun `a planner that does not answer in time falls back to a restart`() {
        val h = harness()
        h.strayCoal()
        h.ticks(WaterSupervisor.PLAN_WAIT_TICKS + 1) { flowing() }
        assertEquals(Stage.STOP_WATER, h.sup.stage)
        assertTrue(h.messages.single().contains("turn the water"))
    }

    @Test
    fun `an answer to an old request is ignored`() {
        val h = harness()
        val first = h.strayCoal()
        // The board changes while the planner works: a new request goes out, the old answer no longer applies.
        h.tick { flip(GOLD) }
        val second = h.requests().filterIsInstance<WaterSupervisor.PlanNeeded>().single()
        assertTrue(second.id > first.id)
        h.sup.onPlanResult(first.id, plan(h, PlanStep(COAL, h.board.now + 10)), h.board)
        assertEquals(Stage.PLANNING, h.sup.stage)
        h.sup.onPlanResult(second.id, plan(h, PlanStep(COAL, h.board.now + 10)), h.board)
        assertEquals(Stage.CONTINUE, h.sup.stage)
    }

    @Test
    fun `a plan that comes too late for its first click is asked for again, three times at most`() {
        val h = harness()
        var request = h.strayCoal()
        repeat(WaterSupervisor.MAX_LATE_RETRIES) { retry ->
            h.ticks(10) { flowing() }
            h.sup.onPlanResult(request.id, plan(h, PlanStep(COAL, h.board.now - 8)), h.board)
            assertEquals(Stage.PLANNING, h.sup.stage, "a late plan is asked for again (retry $retry)")
            request = h.requests().filterIsInstance<WaterSupervisor.PlanNeeded>().last()
        }
        h.ticks(10) { flowing() }
        h.sup.onPlanResult(request.id, plan(h, PlanStep(COAL, h.board.now - 8)), h.board)
        assertEquals(Stage.STOP_WATER, h.sup.stage, "after three late plans the restart it is")
    }

    @Test
    fun `a wrong door during a fix plans again`() {
        val h = harness()
        val request = h.strayCoal()
        h.sup.onPlanResult(request.id, plan(h, PlanStep(COAL, h.board.now + 30)), h.board)
        assertEquals(Stage.CONTINUE, h.sup.stage)
        h.tick { toggle(Door.BLUE) } // Blue had to stay dry
        assertEquals(Stage.PLANNING, h.sup.stage)
        assertEquals(Cause.WRONG_CHANNEL, h.sup.lastIncident?.cause)
    }

    @Test
    fun `fixes that keep going wrong without a door opening end in a restart`() {
        val h = harness()
        var request = h.strayCoal()
        repeat(WaterSupervisor.MAX_REPLANS) { round ->
            h.sup.onPlanResult(request.id, plan(h, PlanStep(COAL, h.board.now + 30)), h.board)
            assertEquals(Stage.CONTINUE, h.sup.stage, "round $round")
            h.tick { flip(EMERALD); flowing() } // not part of the fix
            if (round < WaterSupervisor.MAX_REPLANS - 1) {
                assertEquals(Stage.PLANNING, h.sup.stage, "round $round")
                request = h.requests().filterIsInstance<WaterSupervisor.PlanNeeded>().single()
            }
        }
        assertEquals(Stage.STOP_WATER, h.sup.stage)
        assertTrue(h.messages.last().contains("turn the water"), h.messages.last())
    }

    @Test
    fun `a missed click of the fix is a divergence like any other`() {
        val h = harness()
        val request = h.strayCoal()
        h.sup.onPlanResult(request.id, plan(h, PlanStep(COAL, h.board.now + 10)), h.board)
        h.ticks(10 + 20 + 2) { flowing() }
        assertEquals(Stage.PLANNING, h.sup.stage)
        assertEquals(PlanFollower.Kind.MISSED, h.sup.lastIncident?.divergence?.kind)
    }

    @Test
    fun `a fix with nothing left to click waits for the water and calls it off when it settles`() {
        val h = harness()
        val request = h.strayCoal()
        h.sup.onPlanResult(request.id, plan(h), h.board)
        assertEquals(Stage.CONTINUE, h.sup.stage)
        assertTrue((h.guidance() as Guidance.Act).headline!!.contains("waiting for the water"))
        h.ticks(WaterSupervisor.SETTLE_TICKS + 2) { boardWater = 30 }
        assertEquals(Cause.SETTLED_UNSOLVED, h.sup.lastIncident?.cause)
    }

    @Test
    fun `checks are asked for every half second while a run is on and two misses call it off`() {
        val h = harness()
        h.tick()
        h.tick { water(true) }
        h.ticks(WaterSupervisor.CHECK_INTERVAL) { flowing() }
        val check = h.requests().filterIsInstance<WaterSupervisor.CheckNeeded>().single()
        h.sup.onCheckResult(check.id, false, "the water is heading into the §6Orange§f channel", h.board)
        assertEquals(Stage.RUNNING, h.sup.stage, "one miss is not enough")
        h.ticks(WaterSupervisor.CHECK_INTERVAL) { flowing() }
        val again = h.requests().filterIsInstance<WaterSupervisor.CheckNeeded>().single()
        h.sup.onCheckResult(again.id, false, "the water is heading into the §6Orange§f channel", h.board)
        assertEquals(Stage.PLANNING, h.sup.stage)
        assertEquals(Cause.PREDICTED_FAIL, h.sup.lastIncident?.cause)
    }

    @Test
    fun `a check answer about a board that changed since is ignored`() {
        val h = harness()
        h.tick()
        h.tick { water(true) }
        h.ticks(WaterSupervisor.CHECK_INTERVAL) { flowing() }
        val check = h.requests().filterIsInstance<WaterSupervisor.CheckNeeded>().single()
        h.tick { toggle(Door.PURPLE) }
        repeat(3) { h.sup.onCheckResult(check.id, false, "no", h.board) }
        assertEquals(Stage.RUNNING, h.sup.stage)
    }

    @Test
    fun `no checks and no plans without simulator support`() {
        val h = harness(sim = FakeSim(continuations = false, checks = false))
        h.tick()
        h.tick { water(true) }
        h.ticks(30) { flowing() }
        assertEquals(emptyList(), h.requests())
        h.tick { flip(COAL) }
        assertEquals(Stage.STOP_WATER, h.sup.stage)
        assertEquals(emptyList(), h.requests())
    }

    @Test
    fun `a dry board with the water off restarts instead of planning`() {
        val h = harness()
        h.tick()
        h.tick { water(true) }
        h.ticks(5) { flowing() }
        h.tick { water(false); boardWater = 0 }
        assertEquals(Stage.DRAIN, h.sup.stage)
        assertEquals(emptyList(), h.requests().filterIsInstance<WaterSupervisor.PlanNeeded>())
    }

    @Test
    fun `door states without a shipped play use a static one-run setting when there is one`() {
        val static = ShippedPlay.static(0, Door.bitsOf("24"), GOLD.bit or EMERALD.bit, 220)
        val h = harness("24", FakeSim(static = static))
        h.tick()
        assertEquals(Stage.PREPARE, h.sup.stage)
        assertEquals(listOf(static), h.sup.chain)
        val g = h.guidance() as Guidance.Act
        assertTrue(g.headline!!.contains("one run opens"), g.headline)
        assertEquals(setOf(GOLD, EMERALD), h.labels().filter { it.text == "§lFLIP" }.map { it.lever }.toSet())
        h.tick { flip(GOLD) }
        h.tick { flip(EMERALD) }
        assertEquals("§lPULL", h.labelTexts(WATER).single())
        h.tick { water(true) }
        h.ticks(2) { flowing() }
        assertEquals(Stage.AWAIT, h.sup.stage)
        h.ticks(150) { flowing() }
        assertEquals(Stage.AWAIT, h.sup.stage, "the static run gets its expected time before it counts as stuck")
        h.tick { toggle(Door.BLUE) }
        h.tick { toggle(Door.RED) }
        assertEquals(Stage.SOLVED, h.sup.stage)
    }

    @Test
    fun `three closed doors keep the shipped play even with a static setting at hand`() {
        val static = ShippedPlay.static(0, Door.bitsOf("014"), GOLD.bit, 200)
        val h = harness("014", FakeSim(static = static))
        h.tick()
        assertEquals(listOf("014"), h.sup.chain.map { it.key })
        assertFalse(h.sup.chain.single().synthetic)
    }
}
