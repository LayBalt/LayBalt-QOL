package puzzles.water

import com.laybalt.features.impl.dungeon.solvers.puzzles.water.*
import puzzles.water.ToyBoards.A
import puzzles.water.ToyBoards.B
import puzzles.water.ToyBoards.C
import puzzles.water.ToyBoards.D
import puzzles.water.ToyBoards.E
import puzzles.water.ToyBoards.F
import kotlin.random.Random
import kotlin.test.*

/** The planner on the toy comb, from boards the simulator ran into (the "real" board here is the simulator itself). */
class PlannerTest {
    private val layout = ToyBoards.comb
    private val rules = WaterRules(leverDelay = 1, sourceDelay = 15, externalDelay = 4)
    private val perm = intArrayOf(2, 4, 0, 1, 3)
    private val human = HumanModel(flipLag = 1, waterLag = 15, reaction = 8)
    private fun door(l: WaterLever) = 1 shl perm[ToyBoards.outletOfLever(l.ordinal)]

    /** A board that ran [externals] from dry with lever mask [mask] until [now]. */
    private fun board(mask: Int, now: Int, vararg externals: External): BoardState {
        val s = BoardState(layout, rules)
        s.mask = mask
        s.addExternals(externals.toList())
        WaterSim.run(s, now, stopWhenSettled = false)
        return s
    }

    private fun request(
        state: BoardState, closed: Int, remaining: List<PlanStep>? = null, fault: PlanFault? = null,
        restartEnd: Int = state.tick + 2000, timed: Boolean = false, pendingDoors: Int = 0,
    ) = PlanRequest(
        layout, rules, state, state.tick, closed, perm, human, remaining, fault, restartEnd,
        SearchBudget(wallClockMs = 0, timed = timed), pendingDoors = pendingDoors,
    )

    /** Plays [plan] on a copy of the request's board, every click off by a random amount up to [jitter]. */
    private fun play(req: PlanRequest, plan: ContinuationPlan, jitter: Int, random: Random): Boolean {
        val steps = plan.steps.map { it.copy(clickTick = it.clickTick + random.nextInt(- jitter, jitter + 1)) }
        return Planner.evaluate(req, steps).solved
    }

    /** The comb mid-run: C was open and the water fell through it (C's door opened), then Clay closed the channel by mistake. */
    private fun strayClay(): Pair<BoardState, Int> {
        val s = board(
            C.bit, 60, External.Source(16, true), External.Flip(42, C.ordinal), External.Flip(42, B.ordinal),
            External.Flip(42, D.ordinal), External.Flip(50, F.ordinal),
        )
        return s to (door(B) or door(D))
    }

    @Test
    fun `a stray flip is repaired by flipping it back`() {
        val (s, closed) = strayClay()
        val result = Planner.plan(request(s, closed, emptyList(), PlanFault(F, PlanFollower.Kind.WRONG_LEVER)))
        val plan = assertNotNull(result.best, result.note)
        assertEquals(PlanFamily.REPAIR, plan.family)
        assertEquals(listOf(F), plan.steps.map { it.lever })
        assertTrue(plan.summary.contains("flip Clay now"), plan.summary)
        assertTrue(plan.margin >= 3)
    }

    @Test
    fun `plans are deterministic`() {
        val (s, closed) = strayClay()
        val a = Planner.plan(request(s, closed, emptyList(), PlanFault(F, PlanFollower.Kind.WRONG_LEVER))).best
        val b = Planner.plan(request(s, closed, emptyList(), PlanFault(F, PlanFollower.Kind.WRONG_LEVER))).best
        assertNotNull(a)
        assertEquals(a.steps, b?.steps)
        assertEquals(a.family, b?.family)
        assertEquals(a.predictedEnd, b?.predictedEnd)
    }

    @Test
    fun `the chosen plan works with every click a little off`() {
        val (s, closed) = strayClay()
        val req = request(s, closed, emptyList(), PlanFault(F, PlanFollower.Kind.WRONG_LEVER))
        val plan = assertNotNull(Planner.plan(req).best)
        val random = Random(1)
        repeat(50) { assertTrue(play(req, plan, plan.margin, random), "jittered run $it") }
    }

    @Test
    fun `without the fault known a static re-route finds the same fix`() {
        val (s, closed) = strayClay()
        val plan = assertNotNull(Planner.plan(request(s, closed)).best)
        assertEquals(PlanFamily.STATIC, plan.family)
        assertTrue(Planner.evaluate(request(s, closed), plan.steps).solved)
    }

    @Test
    fun `nothing is proposed when a restart would be faster`() {
        val (s, closed) = strayClay()
        val result = Planner.plan(request(s, closed, emptyList(), PlanFault(F, PlanFollower.Kind.WRONG_LEVER), restartEnd = s.tick + 10))
        assertNull(result.best)
        assertTrue(result.solutions > 0)
    }

    @Test
    fun `water heading into a door that has to stay dry is not a solution`() {
        // Water spread across the channel; only C's door is closed. Opening any other hole wets a door that must stay dry.
        val s = board(0, 120, External.Source(16, true))
        val closed = door(C)
        val plan = assertNotNull(Planner.plan(request(s, closed)).best)
        assertEquals(listOf(C), plan.steps.filter { it.lever != WaterLever.WATER }.map { it.lever })
        assertEquals(- 1, Planner.evaluate(request(s, closed), plan.steps).wrongDoor)
        val wrong = Planner.evaluate(request(s, closed), listOf(PlanStep(B, s.tick + 10)))
        assertEquals(door(B), 1 shl wrong.wrongDoor)
    }

    @Test
    fun `an arrival whose door has not toggled yet is not counted twice`() {
        // C's water reached its outlet just now; its door toggles on its own in a moment.
        val s = board(C.bit, 42, External.Source(16, true))
        assertTrue(s.arrivals[ToyBoards.outletOfLever(C.ordinal)] > 0)
        val withPending = Planner.evaluate(request(s, door(C), pendingDoors = door(C)), emptyList())
        assertTrue(withPending.solved, "the water already reached C")
        val without = Planner.evaluate(request(s, door(C)), emptyList())
        assertFalse(without.solved, "without knowing, C would look missed")
    }

    @Test
    fun `a check says whether the rest of a plan still works`() {
        val (s, closed) = strayClay()
        assertFalse(Planner.check(request(s, closed), emptyList()).solved)
        assertTrue(Planner.check(request(s, closed), listOf(PlanStep(F, s.tick + 10))).solved)
    }

    @Test
    fun `a timed search finds a sequence when no static setting works`() {
        // Dry board, water off, C open: C, B and D closed. Static settings reach C alone or B and D, never all three;
        // pulling the water, then closing C and opening B and D once it went through, does.
        val s = board(C.bit, 10)
        val closed = door(C) or door(B) or door(D)
        val noTimed = Planner.plan(request(s, closed))
        assertNull(noTimed.best, noTimed.note)
        val timed = Planner.plan(request(s, closed, timed = true))
        val plan = assertNotNull(timed.best, timed.note)
        assertEquals(PlanFamily.TIMED, plan.family)
        assertEquals(WaterLever.WATER, plan.steps.first().lever)
        assertTrue(Planner.evaluate(request(s, closed), plan.steps).solved)
    }

    @Test
    fun `cancellation stops the planner`() {
        val (s, closed) = strayClay()
        var calls = 0
        assertFailsWith<java.util.concurrent.CancellationException> {
            Planner.plan(request(s, closed, timed = true), CancelToken { ++ calls > 5 })
        }
    }

    @Test
    fun `the timed search keeps to its node budget`() {
        val s = board(C.bit, 10)
        val closed = door(C) or door(B) or door(D)
        val req = PlanRequest(
            layout, rules, s, s.tick, closed, perm, human, null, null, s.tick + 2000,
            SearchBudget(wallClockMs = 0, timed = true, maxNodes = 50),
        )
        val result = Planner.plan(req)
        assertTrue(result.evaluated < 200, "${result.evaluated} evaluations")
    }

    @Test
    fun `repairs keep the planned clicks that are still ahead`() {
        val steps = listOf(PlanStep(B, 200), PlanStep(D, 260))
        assertEquals(steps, Planner.retime(human, 100, steps))
        val late = Planner.retime(human, 250, steps)
        assertTrue(late.first().clickTick >= 250 + human.reaction)
        assertTrue(late[1].clickTick >= late[0].clickTick + human.gap(B, D))
    }

    @Test
    fun `levers are flipped nearest first`() {
        val h = human.copy(playerX = 5.0, playerZ = - 5.0) // standing at Coal
        assertEquals(listOf(C, B, A, D), Planner.nearestFirst(h, listOf(A, B, C, D)))
        assertEquals(listOf(E), Planner.nearestFirst(h, listOf(E)))
    }

    @Test
    fun `a click that turns the water off shows after the off lag, one that turns it on after the on lag`() {
        val h = HumanModel(flipLag = 3, waterLag = 15, waterOffLag = 9)
        assertEquals(115, h.observed(WaterLever.WATER, 100, on = true))
        assertEquals(109, h.observed(WaterLever.WATER, 100, on = false))
        assertEquals(103, h.observed(WaterLever.GOLD, 100, on = false))
        assertEquals(15, HumanModel(flipLag = 3, waterLag = 15).waterOffLag, "the off lag defaults to the on lag")
    }

    @Test
    fun `the planner plays a water off click with the off lag`() {
        // Water on, every hole closed; the plan turns the water off, then opens C. Whether water is left to fall into C
        // depends on when the water stopped coming in.
        val s = board(0, 60, External.Source(16, true))
        val c = door(C)
        val outletC = ToyBoards.outletOfLever(C.ordinal)
        fun planner(offLag: Int, cClick: Int): Boolean {
            val req = PlanRequest(
                layout, rules, s.copy(), s.tick, c, perm, HumanModel(flipLag = 1, waterLag = 15, waterOffLag = offLag, reaction = 8),
                null, null, s.tick + 2000, SearchBudget(wallClockMs = 0),
            )
            return Planner.evaluate(req, listOf(PlanStep(WaterLever.WATER, 70), PlanStep(C, cClick))).solved
        }
        fun byHand(offLag: Int, cClick: Int): Boolean {
            val sim = s.copy()
            sim.addExternals(listOf(External.Source(70 + offLag, false), External.Flip(cClick + 1, C.ordinal)))
            val outcome = SimRunner.run(sim, s.tick + SearchBudget().horizon)
            return outcome.arrived == 1 shl outletC && outcome.arrivals[outletC] == 1
        }
        var differs = false
        for (k in 72 .. 130) {
            assertEquals(byHand(15, k), planner(15, k), "off lag 15, C at $k")
            assertEquals(byHand(6, k), planner(6, k), "off lag 6, C at $k")
            if (planner(15, k) != planner(6, k)) differs = true
        }
        assertTrue(differs, "the off lag changes what the plan does")
    }
}
