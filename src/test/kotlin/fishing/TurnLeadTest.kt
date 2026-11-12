package fishing

import com.laybalt.features.impl.fishing.AutoFishing
import com.laybalt.features.impl.fishing.AutoMobKiller
import com.laybalt.features.impl.fishing.BehaviorLearner
import com.laybalt.features.impl.fishing.HumanAim
import com.laybalt.features.impl.fishing.HumanAim.Owner
import com.laybalt.features.impl.fishing.MotorModel
import com.laybalt.features.impl.fishing.MotorModel.View
import com.laybalt.features.impl.fishing.MotorParams
import com.laybalt.features.impl.fishing.TimingModel
import com.laybalt.features.impl.fishing.TimingModel.Action
import com.laybalt.features.impl.fishing.ViewMotion
import com.laybalt.features.impl.fishing.ViewPolicy
import com.laybalt.features.impl.fishing.ViewPolicy.Decision
import com.laybalt.features.impl.fishing.ViewPolicy.Need
import com.laybalt.features.impl.fishing.ViewPolicy.TurnBack
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.*

/**
 * Your gaps, latencies and reactions were measured the learner's way: from the last tick of a move that turned the view
 * faster than 15°/s to the tick before the first such tick of the next. The bot times its waits from the same point
 * ([HumanAim.turnedAt], not the hand's slow tail to its rest after it, [HumanAim.turning]) and starts a move its planned
 * sub-threshold onset ([HumanAim.leadMs]) before the moment its first moving tick is due, so the gap the learner measures
 * on the bot is the one drawn.
 */
class TurnLeadTest {
    private class Port(var yaw: Double, var pitch: Double) : HumanAim.Port {
        override val grid = 0.15
        override fun view() = View(yaw, pitch)
        override fun set(yaw: Double, pitch: Double): View {
            this.yaw = yaw
            this.pitch = pitch
            return view()
        }
    }

    /** A driver on a clock of its own, ticked every 50 ms with 7 ms frames between, its view logged at every tick. */
    private class Rig(seed: Long, params: MotorParams = MotorParams()) {
        var clock = 0.0
        var t = 0L
        val port = Port(0.0, 0.0)
        val aim = HumanAim(port, { clock }, Random(seed)).also { it.params = params }
        val lines = ArrayList<ViewMotion.Line>()

        fun tick() {
            clock = t.toDouble()
            aim.tick(rest = false, anchor = null)
            lines += ViewMotion.Line(t, port.yaw, port.pitch)
        }

        /** The frames up to the next tick, and that tick. */
        fun next() {
            while (clock + 7.0 < t + 50) {
                clock += 7.0
                aim.frame()
            }
            t += 50
            tick()
        }
    }

    @Test
    fun `a move turns the view until the learner's last moving tick of it, its slow tail after that turns it less`() {
        val p = MotorParams(multiP = List(5) { 0.0 }, slowP = 0.0)
        repeat(200) { seed ->
            val m = MotorModel()
            val rng = Random(seed)
            m.start(0.0, 0.0, 10.0 + rng.nextDouble() * 80.0, rng.nextDouble() * 40.0 - 20.0, 0.0, p, rng)
            assertTrue(m.speedAhead() > ViewMotion.THRESHOLD, "seed $seed: its onset is still to come")
            var t = 0
            var prev = m.view
            var over = - 1
            while (m.moving) {
                t += 5
                val v = m.sample(t.toDouble())
                if (over >= 0) assertTrue(m.speedAhead() <= ViewMotion.THRESHOLD, "seed $seed: it may turn fast again at $t")
                else if (m.speedAhead() <= ViewMotion.THRESHOLD) over = t
                if (t % 50 != 0) continue
                // no moving tick once it no longer may turn the view that fast
                val speed = MotorModel.angle(prev.yaw, prev.pitch, v.yaw, v.pitch) / 50.0 * 1000.0
                if (over in 0 .. t - 50) assertTrue(speed <= ViewMotion.THRESHOLD, "seed $seed: $speed deg/s at $t after its end at $over")
                prev = v
            }
            assertTrue(over >= 0, "seed $seed: its end was seen while it still came to rest")
            assertEquals(0.0, m.speedAhead(), "at rest")
        }
        // a move of several strokes is still turning while one of them is to start
        val m = MotorModel()
        m.start(0.0, 0.0, 60.0, 0.0, 0.0, MotorParams(multiP = List(5) { 1.0 }, slowP = 0.0, overlapP = 0.0), Random(1))
        assertEquals(Double.POSITIVE_INFINITY, m.speedAhead())
    }

    @Test
    fun `a copy of the motor moves as it does and leaves it alone`() {
        val m = MotorModel()
        m.start(0.0, 0.0, 50.0, 10.0, 0.0, MotorParams(), Random(4))
        m.sample(60.0)
        val c = m.copy()
        for (t in 70 .. 900 step 10) assertEquals(m.sample(t.toDouble()), c.sample(t.toDouble()), "at $t")
        val d = m.copy()
        d.start(d.view.yaw, d.view.pitch, - 30.0, 0.0, 905.0, MotorParams(), Random(5))
        val before = m.view
        d.sample(1200.0)
        assertEquals(before, m.view, "the original is untouched")
    }

    @Test
    fun `a move's lead is when its first moving tick comes, planned with the very draws it starts with`() {
        var leads = 0
        var withLead = 0
        repeat(300) { seed ->
            val rig = Rig(seed.toLong())
            val r = Random(seed * 31 + 7)
            rig.tick()
            // now and then the view is still coming to rest from a move before
            if (r.nextBoolean()) {
                rig.aim.start(r.nextDouble() * 40.0 - 20.0, r.nextDouble() * 20.0 - 10.0, Owner.KILLER)
                while (rig.aim.turning) rig.next()
            }
            val v = rig.port.view()
            val goal = View(v.yaw + (6.0 + r.nextDouble() * 90.0) * (if (r.nextBoolean()) 1 else - 1), (v.pitch + r.nextDouble() * 30.0 - 15.0).coerceIn(- 80.0, 80.0))
            val lead = rig.aim.leadMs(goal.yaw, goal.pitch)
            assertEquals(lead, rig.aim.leadMs(goal.yaw, goal.pitch), "seed $seed: the same plan read again")
            assertTrue(rig.aim.start(goal.yaw, goal.pitch, Owner.KILLER))
            val start = rig.t
            var prev = rig.port.view()
            var first = - 1L
            while (first < 0 && rig.t < start + 2500) {
                rig.next()
                val now = rig.port.view()
                if (MotorModel.angle(prev.yaw, prev.pitch, now.yaw, now.pitch) / 50.0 * 1000.0 > ViewMotion.THRESHOLD) first = rig.t - 50
                prev = now
            }
            assertEquals(lead, first - start, "seed $seed: the first moving tick")
            leads++
            if (lead > 0) withLead++
        }
        // your moves of 6 deg or more: 46 % had slower steps before their first moving one (the bot's hand: 41 %)
        assertTrue(withLead.toDouble() / leads in 0.2 .. 0.7, "$withLead of $leads with a lead")
    }

    /**
     * Moves timed one of your gaps after the last one's last moving tick, through the driver as the killer drives it: what
     * the learner measures between them ([ViewMotion]) is the gap drawn, rounded to the tick, and never your shortest
     * gap's floor or less; timed from the hand's rest and started at the moment instead, it measured a tick and more late.
     */
    @Test
    fun `gaps timed from the last moving tick and started their lead before measure as the gaps drawn`() {
        fun run(fixed: Boolean): Pair<List<Long>, List<Long>> {
            // one stroke a move: every planned move is one the learner finds
            val rig = Rig(11, MotorParams(multiP = List(5) { 0.0 }, slowP = 0.0))
            val r = Random(12)
            val drawn = ArrayList<Long>()
            var dueFor = - 1L
            var due = 0L
            var goal = View(30.0, 0.0)
            rig.tick()
            rig.aim.start(goal.yaw, goal.pitch, Owner.KILLER)
            while (drawn.size < 400) {
                rig.next()
                val busy = if (fixed) rig.aim.turning else rig.aim.moving
                if (busy) continue
                val id = if (fixed) rig.aim.turns else rig.aim.rests.toLong()
                if (id != dueFor) {
                    dueFor = id
                    val rest = if (fixed) rig.aim.turnedAt(rig.t) else rig.aim.restedAt(rig.t)
                    val g = TimingModel.draw(Action.MOVE_GAP, emptyList(), null, r)
                    drawn += g
                    due = TimingModel.dueAt(rest, g)
                    val v = rig.port.view()
                    goal = View(v.yaw + (8.0 + r.nextDouble() * 60.0) * (if (r.nextBoolean()) 1 else - 1), (r.nextDouble() * 40.0 - 20.0))
                }
                val lead = if (fixed) rig.aim.leadMs(goal.yaw, goal.pitch) else 0L
                if (rig.t + lead >= due) rig.aim.start(goal.yaw, goal.pitch, Owner.KILLER)
            }
            val measured = ArrayList<Long>()
            for (run in ViewMotion.runs(rig.lines)) {
                var prev: ViewMotion.Move? = null
                for (m in ViewMotion.segment(run).moves) {
                    prev?.let { a -> if (! m.cut && a.amp >= 6) measured += m.t0 - a.t1 }
                    prev = m
                }
            }
            return drawn to measured
        }
        val (drawn, measured) = run(fixed = true)
        assertTrue(measured.size >= 380, "${measured.size} gaps measured")
        assertTrue(measured.all { it >= 100 }, "${measured.min()}: never under your shortest gap")
        val d = drawn.map { (it + 25) / 50 * 50 }.sorted()
        val m = measured.sorted()
        for (q in listOf(0.1, 0.25, 0.5, 0.75, 0.9)) {
            val i = (q * (m.size - 1)).toInt()
            assertTrue(abs(m[i] - d[(q * (d.size - 1)).toInt()]) <= 50, "q$q: measured ${m[i]} against drawn ${d[(q * (d.size - 1)).toInt()]}")
        }
        // the way it was: from the hand's rest, started at the moment
        val (_, late) = run(fixed = false)
        assertTrue(late.sorted()[late.size / 2] - m[m.size / 2] >= 50, "p50 ${late.sorted()[late.size / 2]} against ${m[m.size / 2]}")
    }

    @Test
    fun `the policy starts a move its lead before the moment its first moving tick is due, on the goal it starts`() {
        val view = View(0.0, 0.0)
        val aim = View(60.0, 0.0)
        val goals = ArrayList<View>()
        fun s(now: Long, lead: Long, restedAt: Long = 1000L, error: Double = 60.0) = ViewPolicy.Situation(
            now, 1, Need.MELEE, view, aim, error, 3.0, false, false, 0, 500L, true, false, 18_000.0, { 0.0 },
            land = { l -> View(aim.yaw + l.side, aim.pitch) }, landing = { ViewPolicy.Landing(ViewPolicy.landingSide(Random(goals.size)), 0.5) },
            restedAt = restedAt, viewMoving = false, restId = 7L, lead = { g -> goals += g; lead },
        )
        fun start(seed: Int, lead: Long): Pair<Long, View> {
            val p = ViewPolicy(Random(seed))
            var t = 1000L
            while (t < 20_000L) {
                if (p.update(s(t, lead)) == Decision.START) return t to p.goal!!
                t += 50
            }
            error("no start")
        }
        repeat(200) { seed ->
            val (at0, _) = start(seed, 0L)
            goals.clear()
            val (at, goal) = start(seed, 100L)
            // the same gap drawn after the swap's move: its first moving tick due at the same moment
            assertEquals(maxOf(at0 - 100, 1000L), at, "seed $seed")
            assertEquals(goal, goals.last(), "seed $seed: its lead planned on the goal it starts")
        }
    }

    @Test
    fun `the turn back starts its lead before its moment, never sooner than that lead`() {
        val b = TurnBack(TurnBack.Kind.NOW, 2000L, 3.0)
        var t = 1000L
        while (b.update(t, false, Long.MIN_VALUE / 2, lead = { 150L }) != Decision.START) t += 50
        assertEquals(1850L, t)
        val c = TurnBack(TurnBack.Kind.NOW, 2000L, 3.0)
        t = 1000L
        while (c.update(t, false, Long.MIN_VALUE / 2) != Decision.START) t += 50
        assertEquals(2000L, t, "no lead: at its moment")
    }

    @Test
    fun `the turn back's lead is planned on the very goal that starts, its undershoot drawn once for the rest`() {
        var draws = 0
        val b = TurnBack(TurnBack.Kind.NOW, 2000L, 9.0)
        assertEquals(4.0, b.short(3) { draws++; 4.0 })
        assertEquals(4.0, b.short(3) { draws++; 5.0 }, "the same rest: the one drawn")
        assertEquals(5.0, b.short(4) { draws++; 5.0 }, "after another move: drawn again")
        assertEquals(2, draws)
        // through the driver, as the killer drives it: a turn back whose undershoot is beyond the fallback, drawn again
        // below it (no world to judge the bobber by); the move that starts comes to its first moving tick when its lead said
        repeat(300) { seed ->
            val rig = Rig(seed.toLong())
            val r = Random(seed * 13 + 5)
            rig.tick()
            val home = View((40.0 + r.nextDouble() * 60.0) * (if (r.nextBoolean()) 1 else - 1), r.nextDouble() * 20.0 - 10.0)
            val back = TurnBack(TurnBack.Kind.NOW, 400L, 9.0)
            val under = Random(seed * 17 + 3)
            val short = { back.short(rig.aim.started) { AutoMobKiller.FightRules.backShort(back.shortBy, { cap -> cap * (0.2 + 0.8 * under.nextDouble()) }) { null } } }
            val v = rig.port.view()
            val lead = { MotorModel.towards(v.yaw, v.pitch, home.yaw, home.pitch, short()).let { g -> rig.aim.leadMs(g.yaw, g.pitch) } }
            while (back.update(rig.t, rig.aim.turning, rig.aim.turnEndAt(rig.t), move = rig.aim.started, lead = lead) != Decision.START) rig.next()
            assertTrue(short() < AutoMobKiller.FightRules.FLUID_FALLBACK_DEG, "seed $seed: the goal that starts is not the one 9 degrees short")
            val planned = lead()
            val g = MotorModel.towards(v.yaw, v.pitch, home.yaw, home.pitch, short())
            assertTrue(rig.aim.start(g.yaw, g.pitch, Owner.KILLER))
            val start = rig.t
            var prev = rig.port.view()
            var first = - 1L
            while (first < 0 && rig.t < start + 2500) {
                rig.next()
                val now = rig.port.view()
                if (MotorModel.angle(prev.yaw, prev.pitch, now.yaw, now.pitch) / 50.0 * 1000.0 > ViewMotion.THRESHOLD) first = rig.t - 50
                prev = now
            }
            assertEquals(planned, first - start, "seed $seed: the first moving tick")
            assertTrue(start + planned >= 400L, "seed $seed: not sooner than its lead")
        }
    }

    @Test
    fun `the last move of 3 degrees or more is found as the learner segments the view, a smaller one after it is not`() {
        val rig = Rig(3, MotorParams(multiP = List(5) { 0.0 }, slowP = 0.0))
        rig.tick()
        assertEquals(Long.MIN_VALUE / 2, rig.aim.bigTurnedAt(0))
        rig.aim.start(30.0, 0.0, Owner.KILLER)
        while (rig.aim.moving) rig.next()
        repeat(4) { rig.next() }
        val big = rig.aim.bigTurnedAt(rig.t)
        assertEquals(rig.aim.turnedAt(rig.t), big, "the move itself")
        // a small move of 2 degrees after two still ticks or more: a move of its own, under 3 degrees
        rig.aim.start(32.0, 0.0, Owner.KILLER)
        while (rig.aim.moving) rig.next()
        repeat(4) { rig.next() }
        val moves = ViewMotion.runs(rig.lines).flatMap { ViewMotion.segment(it).moves }
        assertEquals(2, moves.size, "two moves for the learner")
        assertTrue(moves[1].amp < BehaviorLearner.GAP_MOVE_DEG)
        assertEquals(moves[0].t1, rig.aim.bigTurnedAt(rig.t), "the last of 3 degrees or more")
        assertEquals(moves[1].t1, rig.aim.turnedAt(rig.t), "the last moving tick of any")
    }

    @Test
    fun `with no swap the turn to a creature already there starts inside the click's gap, never before the hand's reaction`() {
        var reactions = 0
        // no turn: the fight starts at the click's moment, nothing drawn
        assertEquals(1400L, AutoMobKiller.FightRules.olderTurnAt(1400L, 1000L, 0L, { error("no entry") }) { reactions++; 200L })
        assertEquals(0, reactions)
        // a turn of 250 ms and a reaction of 100 ms to the view getting there: 1250, after the hand's reaction (1000 + 200 - 25)
        assertEquals(1250L, AutoMobKiller.FightRules.olderTurnAt(1600L, 1000L, 250L, { 100L }) { 200L })
        // a gap too short for the turn: the turn waits for the hand's reaction to the death, the click for the view
        assertEquals(1175L, AutoMobKiller.FightRules.olderTurnAt(1200L, 1000L, 250L, { 100L }) { 200L })
    }

    @Test
    fun `a cast never waits for a slow tail that lands like your fishing view, nor goes from one that does not`() {
        assertFalse(AutoFishing.CastCheck.tailHolds(false) { false }, "no tail: nothing to hold")
        assertFalse(AutoFishing.CastCheck.tailHolds(true) { true })
        assertTrue(AutoFishing.CastCheck.tailHolds(true) { false })
        assertTrue(AutoFishing.CastCheck.tailHolds(true) { null }, "no fishing view to judge by")
    }
}
