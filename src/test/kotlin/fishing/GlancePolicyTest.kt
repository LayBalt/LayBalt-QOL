package fishing

import com.laybalt.features.impl.fishing.AutoFishing.CastCheck
import com.laybalt.features.impl.fishing.BehaviorProfile
import com.laybalt.features.impl.fishing.BehaviorProfile.Glances
import com.laybalt.features.impl.fishing.BehaviorProfile.Profile
import com.laybalt.features.impl.fishing.GlanceMeasure
import com.laybalt.features.impl.fishing.GlancePolicy
import com.laybalt.features.impl.fishing.GlancePolicy.Decision
import com.laybalt.features.impl.fishing.GlancePolicy.Phase
import com.laybalt.features.impl.fishing.GlancePolicy.State
import com.laybalt.features.impl.fishing.HumanAim
import com.laybalt.features.impl.fishing.HumanAim.Owner
import com.laybalt.features.impl.fishing.MotorModel
import com.laybalt.features.impl.fishing.MotorModel.View
import com.laybalt.features.impl.fishing.TimingModel
import com.laybalt.features.impl.fishing.ViewMotion
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.log2
import kotlin.math.roundToLong
import kotlin.random.Random
import kotlin.test.*

/** looks/SPEC §4.1: the looks around and back ([GlancePolicy]) on a fake clock, their motion, and the gates around them. */
class GlancePolicyTest {
    /** Learned looks: [rate] per minute in [phase] (others none), with the reference recordings' probabilities unless given. */
    private fun learned(
        phase: Phase = Phase.WAITING, rate: Double = 0.6, chainP: Double = 0.26, returnHomeP: Double = 0.9, returnAwayP: Double = 0.17,
        deathBoost: Double = 2.7, deathShare: Double = 0.0,
    ) = Glances().apply {
        this.rate = MutableList(4) { if (it == phase.ordinal) rate else 0.0 }
        minutes = MutableList(4) { if (it == phase.ordinal) 100.0 else 0.0 }
        deathMinutes = MutableList(4) { if (it == phase.ordinal) 100.0 * deathShare else 0.0 }
        this.chainP = chainP
        this.returnHomeP = returnHomeP
        this.returnAwayP = returnAwayP
        this.deathBoost = deathBoost
    }

    /**
     * The policy on a fake 50 ms tick: a look or return moves the view to its goal over the time the policy planned for
     * it (no motor), and the counts of what it did.
     */
    private class Harness(seed: Int, val g: Glances?, var view: View = View(0.0, 40.0), val home: View? = View(0.0, 40.0)) {
        val policy = GlancePolicy(Random(seed)).also {
            it.table = GlancePolicy.Table.of(g)
            it.lookParams = HumanAim.glanceParamsOf(null, g)
            it.backParams = HumanAim.paramsOf(null)
        }
        var now = 1000L
        private var movingUntil = - 1L
        private var moveTo: View? = null
        var looks = 0
        var episodes = 0
        var returns = 0
        var lastLookStart = View(0.0, 0.0)
        /** Time a new episode could have started (no episode out), in ms. */
        var restMs = 0.0

        fun tick(
            phase: Phase?, window: Double = Double.POSITIVE_INFINITY, sinceDeath: Double = Double.POSITIVE_INFINITY, deadSpot: View? = null,
            returnNow: Boolean = false, turnBackOwed: Boolean = false, busy: Boolean = false, returnNotBefore: Long = Long.MIN_VALUE,
        ): Decision {
            if (movingUntil >= 0 && now >= movingUntil) {
                view = moveTo!!
                movingUntil = - 1L
            }
            if (! policy.out && movingUntil < 0) restMs += 50.0
            val before = policy.state
            val d = policy.update(GlancePolicy.Inputs(now, phase, view, home, window, sinceDeath, deadSpot, returnNow, turnBackOwed, movingUntil >= 0, busy, returnNotBefore))
            when (d) {
                Decision.LOOK -> {
                    looks++
                    if (before == State.REST) episodes++
                    lastLookStart = view
                    move(policy.goal!!, policy.lookParams)
                }
                Decision.RETURN -> {
                    returns++
                    move(policy.goal!!, policy.backParams)
                }
                Decision.DROP -> movingUntil = - 1L
                Decision.NONE -> Unit
            }
            now += 50
            return d
        }

        private fun move(g: View, params: com.laybalt.features.impl.fishing.MotorParams) {
            moveTo = g
            movingUntil = now + maxOf(50L, MotorModel.typicalMs(view.yaw, view.pitch, g.yaw, g.pitch, params).roundToLong())
        }
    }

    private fun angle(a: View, b: View) = MotorModel.angle(a.yaw, a.pitch, b.yaw, b.pitch)

    @Test
    fun `nothing learned means no looks`() {
        assertFalse(Glances().learned)
        assertFalse(GlancePolicy.Table.of(Glances()).learned)
        val h = Harness(1, Glances())
        repeat(1_000_000) { h.tick(Phase.entries[it % 4]) }
        assertEquals(0, h.looks)
        assertFalse(h.policy.out)
    }

    @Test
    fun `a profile without looks, or with broken ones, reads them as off without throwing`() {
        val gson = com.google.gson.Gson()
        for (json in listOf(
            """{"learner":5}""",
            """{"glances":null}""",
            """{"glances":{"rate":null,"minutes":null,"amp":null,"dYaw":null,"dwellMs":null,"returnErr":null,"chainGapMs":null}}""",
            """{"glances":{"rate":[1.0,2.0],"chainP":7.0}}""",
            """{"glances":{"rate":[-1.0,"NaN",0.0,0.0]}}""",
        )) {
            val p = gson.fromJson(json, Profile::class.java)
            val t = GlancePolicy.Table.of(p.glances)
            assertFalse(t.learned, json)
            Phase.entries.forEach { assertEquals(0.0, t.hazard(it, false), json) }
            val h = Harness(2, p.glances)
            repeat(20_000) { assertEquals(Decision.NONE, h.tick(Phase.WAITING), json) }
        }
        val old = gson.fromJson("""{"learner":5}""", Profile::class.java)
        assertEquals(listOf(0.0, 0.0, 0.0, 0.0), old.glances.rate)
        assertTrue(BehaviorProfile.LEARNER >= 6, "a profile learned before the looks around is learned again")
        // a rate cut to 4 a minute, a chain share to 0.5
        val wild = GlancePolicy.Table.of(learned(rate = 50.0, chainP = 0.4))
        assertEquals(4.0 * 0.6, wild.hazard(Phase.WAITING, false), 1e-9)
    }

    @Test
    fun `looks come at the learned rate, a quarter of them chained`() {
        val h = Harness(3, learned(rate = 0.6, chainP = 0.26))
        val minutes = 600
        var t = 0
        repeat(minutes * 1200) {
            // a cast every 20 s brings a look that stayed away back, as Auto Fishing's cast guard does
            h.tick(Phase.WAITING, returnNow = t % 400 == 0)
            t++
        }
        val perMin = h.looks.toDouble() / minutes
        val episodes = h.episodes.toDouble() / minutes
        assertTrue(abs(perMin - 0.6) <= 0.6 * 0.15, "looks per minute %.3f".format(perMin))
        assertTrue(abs(episodes - 0.6 * 0.74) <= 0.6 * 0.74 * 0.15, "episodes per minute %.3f".format(episodes))
        assertTrue(h.returns >= 0.8 * h.episodes, "most episodes from home come back: ${h.returns} of ${h.episodes}")
    }

    @Test
    fun `right after a death the rate is the boost times the rest, and the mean stays the rate`() {
        val g = learned(Phase.AFTER_KILL, rate = 0.6, chainP = 0.0, deathShare = 0.2)
        val h = Harness(4, g, home = null)
        var boostedMs = 0.0
        var otherMs = 0.0
        var boosted = 0
        var other = 0
        val minutes = 4000
        repeat(minutes * 1200) {
            // a death every 5 s: a fifth of the time is the second after one
            val since = (h.now % 5000).toDouble()
            val free = ! h.policy.out
            val d = h.tick(Phase.AFTER_KILL, sinceDeath = since)
            if (free) if (since < 1000) boostedMs += 50 else otherMs += 50
            if (d == Decision.LOOK) if (since < 1000) boosted++ else other++
        }
        val ratio = (boosted / boostedMs) / (other / otherMs)
        assertTrue(abs(ratio - 2.7) <= 2.7 * 0.15, "boost %.2f (%d / %d)".format(ratio, boosted, other))
        val perMin = h.looks.toDouble() / minutes
        assertTrue(abs(perMin - 0.6) <= 0.6 * 0.1, "looks per minute %.3f".format(perMin))
    }

    @Test
    fun `a look starts only when its whole episode fits in the time the view is free`() {
        val g = learned(chainP = 0.0, returnHomeP = 1.0)
        val short = Harness(5, g)
        repeat(1_000_000) { short.tick(Phase.WAITING, window = 500.0) }
        assertEquals(0, short.looks, "500 ms is too short for a look, a dwell and the return")

        val h = Harness(6, g)
        var started = - 1L
        var longest = 0L
        repeat(2_000_000) {
            val d = h.tick(Phase.WAITING, window = 2500.0)
            if (d == Decision.LOOK) started = h.now - 50
            if (started >= 0 && ! h.policy.out) {
                longest = maxOf(longest, h.now - started)
                started = - 1L
            }
        }
        assertTrue(h.looks > 50, "short looks still fit: ${h.looks}")
        assertTrue(longest <= 2500 + 150, "an episode took $longest ms")
    }

    @Test
    fun `another move taking the view drops the episode, no return follows`() {
        for (busyIn in listOf(State.OUT, State.DWELL)) {
            val h = Harness(7, learned(rate = 4.0, chainP = 0.0, returnHomeP = 1.0))
            var guard = 0
            while (h.policy.state != busyIn && guard++ < 1_000_000) h.tick(Phase.WAITING)
            assertEquals(busyIn, h.policy.state)
            assertEquals(Decision.DROP, h.tick(Phase.WAITING, busy = true))
            assertEquals(State.REST, h.policy.state)
            assertFalse(h.policy.out)
            val returns = h.returns
            // the killer's move goes on for a while, then the view is still: no return of the dropped look
            repeat(20) { h.tick(Phase.WAITING, busy = true) }
            repeat(100) { assertNotEquals(Decision.RETURN, h.tick(Phase.WAITING)) }
            assertEquals(returns, h.returns)
        }
    }

    @Test
    fun `a look from home comes back close to its start, never more than half the way short`() {
        repeat(200) { seed ->
            val h = Harness(seed, learned(rate = 4.0, chainP = 0.0, returnHomeP = 1.0))
            var guard = 0
            while (h.returns == 0 && guard++ < 200_000) h.tick(Phase.WAITING)
            assertEquals(1, h.returns, "seed $seed")
            val start = h.lastLookStart
            val goal = h.policy.goal!!
            val a = angle(start, h.view)
            val err = angle(goal, start)
            // the motor's straight mouse path measures a little differently on the sphere
            assertTrue(err <= GlancePolicy.HOME_ERR_MAX + 0.05 && err <= 0.5 * a + 0.05, "seed $seed: %.2f off its start after %.1f".format(err, a))
        }
    }

    @Test
    fun `a return's error is drawn below its cap, never piled at it`() {
        // your return errors: 10 of 25 above the 5 deg a return home may stop short by
        val yours = listOf(0.4f, 0.8f, 1.1f, 1.3f, 1.6f, 1.9f, 2.2f, 2.5f, 2.9f, 3.3f, 3.6f, 4.0f, 4.2f, 4.4f, 4.6f, 5.1f, 5.7f, 6.1f, 6.9f, 7.7f, 8.9f, 12.7f, 12.8f, 38.3f, 82.9f)
        val tables = listOf(GlancePolicy.Table.of(learned().apply { returnErr = yours.toMutableList() }), GlancePolicy.Table.of(null))
        val rng = Random(5)
        for (t in tables) for (cap in listOf(GlancePolicy.HOME_ERR_MAX, 3.0, 12.0)) {
            val errs = List(40_000) { t.returnErr(rng, cap) }
            assertTrue(errs.all { it in 0.0 .. cap }, "cap $cap: ${errs.max()}")
            val top = errs.count { it > cap * 0.99 } / 4e4
            assertTrue(top < 0.02, "cap $cap: %.1f%% piled at it".format(top * 100))
            assertTrue(errs.count { it < cap / 2 } > errs.size / 4, "cap $cap: spread below it")
        }
        assertEquals(0.0, tables[0].returnErr(rng, 0.0), "no way back to stop short of")
    }

    @Test
    fun `with the turn back owed there is no return, and the dwell still ends`() {
        val h = Harness(8, learned(Phase.AFTER_KILL, rate = 4.0, chainP = 0.0, returnAwayP = 0.6), home = null)
        var guard = 0
        while (h.policy.state != State.DWELL && guard++ < 1_000_000) h.tick(Phase.AFTER_KILL, turnBackOwed = true)
        val end = h.policy.dwellEnd
        while (h.now < end) assertEquals(Decision.NONE, h.tick(Phase.AFTER_KILL, turnBackOwed = true))
        h.tick(Phase.AFTER_KILL, turnBackOwed = true)
        assertEquals(State.REST, h.policy.state)
        assertEquals(0, h.returns)
    }

    @Test
    fun `a cast waiting for the view brings the look back in the same tick`() {
        val h = Harness(9, learned(rate = 4.0, chainP = 0.0, returnHomeP = 0.3))
        repeat(50) {
            var guard = 0
            while (h.policy.state != State.DWELL && guard++ < 1_000_000) h.tick(Phase.WAITING)
            assertEquals(Decision.RETURN, h.tick(Phase.WAITING, returnNow = true), "whatever the drawn return said")
            while (h.policy.out) h.tick(Phase.WAITING)
        }
    }

    @Test
    fun `looks never set home and start only from it while you wait`() {
        val home = View(30.0, 50.0)
        val h = Harness(10, learned(rate = 4.0, returnHomeP = 0.5), view = View(30.0, 50.0), home = home)
        repeat(300_000) {
            val episodes = h.episodes
            val before = h.view
            h.tick(Phase.WAITING, returnNow = it % 400 == 0)
            if (h.episodes > episodes) assertTrue(angle(before, home) <= Glances.HOME_DEG, "an episode from %.1f off home".format(angle(before, home)))
        }
        assertTrue(h.episodes > 100)
        assertEquals(View(30.0, 50.0), h.home)
        // away from home no look starts while you wait
        val far = Harness(11, learned(rate = 4.0), view = View(60.0, 50.0), home = home)
        repeat(200_000) { assertNotEquals(Decision.LOOK, far.tick(Phase.WAITING)) }

        // through the view driver: a look never changes the driver's home
        val port = FakePort(30.0, 50.0)
        var now = 0.0
        val aim = HumanAim(port, { now }, Random(12))
        aim.home = home
        val policy = GlancePolicy(Random(13))
        val g = learned(rate = 4.0)
        for (k in 0 until 200_000) {
            for (f in 1 .. 4) {
                now += 12.5
                aim.frame()
            }
            aim.tick(rest = false, anchor = null)
            aim.glance(policy, now.toLong(), Phase.WAITING, null, g, returnNow = k % 400 == 0)
        }
        assertSame(home, aim.home)
        assertNull(aim.restAnchor, "a look sets no rest anchor either")
    }

    @Test
    fun `goals stay within the poles, and from straight down looks go up`() {
        val table = GlancePolicy.Table.of(learned(rate = 4.0))
        for (pitch in listOf(- 90.0, - 60.0, 0.0, 45.0, 89.0, 90.0)) {
            val h = Harness(14, learned(rate = 4.0, chainP = 0.5), view = View(0.0, pitch), home = View(0.0, pitch))
            var n = 0
            repeat(1_000_000) {
                val before = h.view
                if (h.tick(Phase.WAITING, returnNow = it % 400 == 0) == Decision.LOOK) {
                    val g = h.policy.goal!!
                    assertTrue(g.pitch in - 90.0 .. 90.0, "from $pitch: $g")
                    assertTrue(angle(before, g) >= GlancePolicy.MIN_LOOK_DEG - 1e-6)
                    if (before.pitch >= 89.99) assertTrue(g.pitch < before.pitch, "from straight down a look goes up: $g")
                    n++
                }
            }
            // straight up or down a yaw-only look turns nothing: fewer looks there
            assertTrue(n > 10, "from $pitch: $n looks")
        }
        assertTrue(table.learned)
    }

    @Test
    fun `learned offsets give back their amplitudes`() {
        val rng = Random(15)
        val g = learned(rate = 4.0, chainP = 0.0)
        val amps = List(300) { 6.0 + 50.0 * rng.nextDouble() * rng.nextDouble() }
        val ths = List(amps.size) { rng.nextDouble() * Math.PI * 2 }
        g.amp = amps.mapTo(ArrayList()) { it.toFloat() }
        g.dYaw = amps.indices.mapTo(ArrayList()) { (amps[it] * cos(ths[it])).toFloat() }
        g.dPitch = amps.indices.mapTo(ArrayList()) { (amps[it] * kotlin.math.sin(ths[it])).toFloat() }
        g.startPitch = MutableList(amps.size) { 20f }
        val table = GlancePolicy.Table.of(g)
        val drawn = List(20_000) { table.offset(20.0, rng).let { (y, p) -> hypot(y, p) } }
        for (q in listOf(0.1, 0.5, 0.9)) {
            val want = ViewMotion.quantile(amps, q)
            val got = ViewMotion.quantile(drawn, q)
            assertTrue(abs(got - want) <= 0.15 * want, "p${(q * 100).toInt()}: %.1f against your %.1f".format(got, want))
        }
    }

    @Test
    fun `a look around on its way tells where it rests, the view a fight's turn back is measured from`() {
        val port = FakePort(10.0, 40.0)
        var now = 0.0
        val aim = HumanAim(port, { now }, Random(19))
        aim.params = HumanAim.glanceParamsOf(null, null)
        assertTrue(aim.start(46.0, 20.0, Owner.GLANCE))
        repeat(8) {
            now += 12.5
            aim.frame()
        }
        val mid = port.view()
        val rests = assertNotNull(aim.restingAt, "a look's move in flight")
        assertFalse(aim.movingInFight(), "a look around is no move of the fight")
        assertTrue(angle(mid, rests) > 5.0, "on its way at $mid, rests at $rests")
        repeat(800) {
            now += 12.5
            aim.frame()
        }
        assertFalse(aim.moving)
        assertTrue(angle(port.view(), rests) < 1.0, "it came to rest at ${port.view()}, not at $rests")
    }

    /** A view that keeps changes on the mouse grid like the game's fixRot. */
    private class FakePort(var yaw: Double, var pitch: Double, override val grid: Double = 0.15) : HumanAim.Port {
        override fun view() = View(yaw, pitch)

        override fun set(yaw: Double, pitch: Double): View {
            val dy = yaw - this.yaw
            val dp = pitch - this.pitch
            this.yaw += dy - dy % grid
            this.pitch = (this.pitch + dp - dp % grid).coerceIn(- 90.0, 90.0)
            return view()
        }
    }

    @Test
    fun `a look moves in one straight stroke, a little faster than your law, never past its goal`() {
        val params = HumanAim.glanceParamsOf(null, null)
        val table = GlancePolicy.Table.of(learned(rate = 4.0))
        val rng = Random(16)
        var multi = 0
        var big = 0
        var over = 0
        val ratios = ArrayList<Double>()
        var looks = 0
        while (looks < 10_000) {
            val start = View(rng.nextDouble(- 180.0, 180.0), rng.nextDouble(- 30.0, 80.0))
            val (dYaw, dPitch) = table.offset(start.pitch, rng)
            val end = (start.pitch + dPitch).coerceIn(- 89.0, 89.0)
            val goal = View(start.yaw + dYaw / cos(Math.toRadians((start.pitch + end) / 2)).coerceAtLeast(0.2), end)
            if (angle(start, goal) < 6.0) continue
            looks++
            val port = FakePort(start.yaw, start.pitch)
            var now = 0.0
            val aim = HumanAim(port, { now }, rng)
            aim.params = params
            val lines = ArrayList<ViewMotion.Line>()
            fun line() = port.view().let { lines += ViewMotion.Line(now.roundToLong(), Math.round(it.yaw * 100) / 100.0, Math.round(it.pitch * 100) / 100.0) }
            repeat(3) {
                line()
                now += 50.0
            }
            aim.start(goal.yaw, goal.pitch, Owner.GLANCE)
            var still = 0
            while ((aim.moving || still < 3) && lines.size < 400) {
                for (f in 1 .. 5) {
                    now += 10.0
                    aim.frame()
                }
                aim.tick(rest = false, anchor = null)
                line()
                if (! aim.moving) still++
            }
            val segs = ViewMotion.runs(lines).map { ViewMotion.segment(it) }
            for (s in segs) for (i in 1 until s.run.size) assertTrue(s.speed[i] <= 1650.0 + 1e-6, "a tick at ${s.speed[i]} deg/s")
            val moves = segs.flatMap { it.moves }.filter { it.amp >= 3.0 }
            val main = moves.maxByOrNull { it.amp } ?: continue
            if (main.strokes.size >= 2 || moves.size > 1) multi++
            if (main.amp >= 20) {
                big++
                if (main.over > 1.0) over++
            }
            ratios += main.dur / (34.0 + 111.0 * log2(1 + main.amp / 6.0))
        }
        val p50 = ViewMotion.quantile(ratios, 0.5)
        println("looks: multi-stroke %.3f, overshoot %.3f (n %d), dur/law p50 %.3f".format(multi.toDouble() / looks, over.toDouble() / big, big, p50))
        assertTrue(multi.toDouble() / looks <= 0.20, "multi-stroke share ${multi.toDouble() / looks}")
        assertTrue(over.toDouble() / big <= 0.06, "overshoot share ${over.toDouble() / big}")
        assertTrue(p50 in 0.80 .. 0.98, "dur/law p50 $p50")
    }

    @Test
    fun `a look's move never holds Auto Fishing, nor the killer's fight`() {
        val port = FakePort(0.0, 40.0)
        var now = 0.0
        val aim = HumanAim(port, { now }, Random(17))
        assertTrue(aim.start(40.0, 10.0, Owner.GLANCE))
        now += 10.0
        aim.frame()
        aim.stopFight()
        aim.abortFight()
        assertTrue(aim.moving(Owner.GLANCE), "the killer's stops and aborts leave a look alone")
        while (aim.moving) {
            assertFalse(aim.movingInFight(), "what holdsAutoFishing and busy read")
            assertTrue(aim.moving(Owner.GLANCE))
            now += 10.0
            aim.frame()
        }
        assertNull(aim.restAnchor, "a look sets no rest anchor")
    }

    @Test
    fun `Auto Fishing casts from a look only where it still lands like home`() {
        assertTrue(CastCheck.glanceHolds(out = true, moving = false, distance = 10.0) { null }, "10 deg off with no world to tell")
        assertFalse(CastCheck.glanceHolds(out = true, moving = false, distance = 3.0) { null }, "3 deg off")
        assertFalse(CastCheck.glanceHolds(out = true, moving = false, distance = 10.0) { true }, "the bobber flight says it lands like home's")
        assertTrue(CastCheck.glanceHolds(out = true, moving = false, distance = 3.0) { false })
        assertTrue(CastCheck.glanceHolds(out = false, moving = true, distance = 1.0) { true }, "never while a look's move turns the view")
        assertFalse(CastCheck.glanceHolds(out = false, moving = false, distance = 30.0) { false }, "no look out: the other checks decide")
    }

    @Test
    fun `a look that stayed away holds the cast in every phase after it, and the next look from there comes back home`() {
        repeat(30) { seed ->
            // from home it stays, from elsewhere it comes back: a bobber fight's look, then your wait for a bite
            val h = Harness(seed, learned(Phase.FIGHT_FREE, rate = 4.0, chainP = 0.0, returnHomeP = 0.0))
            var guard = 0
            while (! (h.policy.state == State.REST && h.policy.out) && guard++ < 1_000_000) h.tick(Phase.FIGHT_FREE)
            assertTrue(h.policy.out, "seed $seed")
            for (phase in listOf(Phase.WAITING, Phase.FIGHT_AIM, Phase.AFTER_KILL, Phase.WAITING)) {
                h.tick(phase)
                assertTrue(h.policy.out, "seed $seed: a look that stayed away is forgotten in $phase")
                assertTrue(CastCheck.glanceHolds(h.policy.out, false, angle(h.view, h.home!!)) { null } || angle(h.view, h.home!!) <= 6.0, "seed $seed")
            }
            assertEquals(Decision.RETURN, h.tick(Phase.WAITING, returnNow = true), "seed $seed")
            while (h.policy.out) h.tick(Phase.WAITING)
            assertTrue(angle(h.view, h.home!!) <= GlancePolicy.HOME_ERR_MAX + 0.05, "seed $seed: back %.1f off home".format(angle(h.view, h.home!!)))

            // after a kill: a look from home stays, a look from there comes back to home, not to where it started
            val k = Harness(seed, Glances().apply {
                rate = mutableListOf(0.0, 0.0, 0.0, 4.0)
                minutes = mutableListOf(0.0, 0.0, 0.0, 100.0)
                chainP = 0.0
                returnHomeP = 0.0
                returnAwayP = 1.0
            })
            guard = 0
            while (! (k.policy.state == State.REST && k.policy.out) && guard++ < 1_000_000) k.tick(Phase.AFTER_KILL)
            guard = 0
            while (k.returns == 0 && guard++ < 1_000_000) k.tick(Phase.AFTER_KILL)
            while (k.policy.out) k.tick(Phase.AFTER_KILL)
            assertEquals(1, k.returns, "seed $seed")
            assertTrue(angle(k.view, k.home!!) <= GlancePolicy.HOME_ERR_MAX + 0.05, "seed $seed: back %.1f off home".format(angle(k.view, k.home!!)))
        }
    }

    @Test
    fun `a bite or a cast while a look keeps the view away brings it back once your hand reacted, not in its tick`() {
        repeat(30) { seed ->
            // a look dwelling when the bite comes: the reel's window closes at once, the return waits for the reaction
            val h = Harness(seed, learned(rate = 4.0, chainP = 0.0, returnHomeP = 1.0))
            var guard = 0
            while (h.policy.state != State.DWELL && guard++ < 1_000_000) h.tick(Phase.WAITING)
            val bite = h.now
            val react = 350L + 50 * (seed % 7)
            while (h.now < bite + react) assertEquals(Decision.NONE, h.tick(Phase.WAITING, window = 50.0, returnNotBefore = bite + react), "seed $seed: before the reaction")
            assertEquals(Decision.RETURN, h.tick(Phase.WAITING, window = 50.0, returnNotBefore = bite + react), "seed $seed: once the hand reacted")

            // a look that stayed away when Auto Fishing's cast went out from there: back after the reaction to the click
            val s = Harness(seed, learned(rate = 4.0, chainP = 0.0, returnHomeP = 0.0))
            guard = 0
            while (! (s.policy.state == State.REST && s.policy.out) && guard++ < 1_000_000) s.tick(Phase.WAITING)
            val cast = s.now
            while (s.now < cast + react) assertEquals(Decision.NONE, s.tick(Phase.WAITING, returnNow = true, returnNotBefore = cast + react), "seed $seed: in the click's tick")
            assertEquals(Decision.RETURN, s.tick(Phase.WAITING, returnNow = true, returnNotBefore = cast + react), "seed $seed")
            // an event long past holds nothing back
            while (s.policy.out) s.tick(Phase.WAITING, returnNotBefore = cast + react)
            guard = 0
            while (! (s.policy.state == State.REST && s.policy.out) && guard++ < 1_000_000) s.tick(Phase.WAITING, returnNotBefore = cast + react)
            assertEquals(Decision.RETURN, s.tick(Phase.WAITING, returnNow = true, returnNotBefore = cast + react), "seed $seed: a later cast")
        }
        // the reaction: under 1.5 s (the cast waits for the view), about half a second, hardly ever in the next tick
        val rng = Random(31)
        val xs = List(50_000) { TimingModel.draw(TimingModel.Action.GLANCE_REACTION, emptyList(), null, rng) }.sorted()
        assertTrue(xs.last() <= 1500 && xs.first() >= 30, "${xs.first()}..${xs.last()}")
        assertTrue(xs[25_000] in 430L .. 600L, "p50 ${xs[25_000]}")
        assertTrue(xs.count { it <= 50 } < xs.size * 0.02, "in the event's next tick: ${xs.count { it <= 50 }}")
        assertTrue(xs.groupingBy { it }.eachCount().values.max() < xs.size * 0.01, "piled at one wait")
    }

    @Test
    fun `looks from a steep view are drawn again where their goal would pass the pole or turn the yaw past half round, never piled there`() {
        for (pitch in listOf(85.0, - 85.0, 88.5)) {
            val h = Harness(16, learned(rate = 6.0, chainP = 0.0, returnHomeP = 1.0), view = View(0.0, pitch), home = View(0.0, pitch))
            val yaws = ArrayList<Double>()
            val pitches = ArrayList<Double>()
            repeat(400_000) {
                val before = h.view
                if (h.tick(Phase.WAITING, returnNow = it % 400 == 0) == Decision.LOOK) {
                    val g = h.policy.goal!!
                    yaws += abs(g.yaw - before.yaw)
                    pitches += abs(g.pitch)
                }
            }
            assertTrue(yaws.size > 100, "from $pitch: ${yaws.size} looks")
            assertTrue(yaws.all { it < 180.0 } && pitches.all { it <= 89.0 }, "from $pitch: yaw ${yaws.max()}, pitch ${pitches.max()}")
            // clamped, a third of the looks from 85° turned the yaw by exactly 180° and some ended at exactly 89°
            assertTrue(yaws.count { it >= 179.5 } < yaws.size * 0.01, "from $pitch: %d of %d at a half turn".format(yaws.count { it >= 179.5 }, yaws.size))
            assertTrue(pitches.count { it >= 88.99 } < pitches.size * 0.01, "from $pitch: %d of %d at the pole bound".format(pitches.count { it >= 88.99 }, pitches.size))
        }
    }

    @Test
    fun `chain gaps and look amplitudes are drawn within their bounds, never piled at them`() {
        val rng = Random(17)
        // 3 of your 12 gaps beyond the 3 s cap: clamped, a quarter of the draws sat at exactly 3000 ms
        val g = learned().apply { chainGapMs = mutableListOf(150, 220, 300, 410, 520, 800, 1200, 2600, 2950, 3400, 5200, 9000) }
        val gaps = List(40_000) { GlancePolicy.Table.of(g).chainGap(rng) }
        assertTrue(gaps.all { it > 0.0 && it <= 3000.0 }, "${gaps.min()}..${gaps.max()}")
        assertTrue(gaps.count { it >= 2990.0 } < gaps.size * 0.01, "piled at the cap: ${gaps.count { it >= 2990.0 }}")
        // the reference recordings' amplitudes without offsets of yours: 6-140°, around 22.5°, no value twice
        val table = GlancePolicy.Table.of(learned())
        val amps = List(40_000) { table.offset(20.0, rng).let { (y, p) -> hypot(y, p) } }
        assertTrue(amps.all { it >= GlancePolicy.MIN_LOOK_DEG - 1e-9 && it <= 140.0 + 1e-9 }, "${amps.min()}..${amps.max()}")
        assertEquals(22.5, ViewMotion.quantile(amps, 0.5), 2.5)
        assertTrue(amps.groupingBy { it }.eachCount().values.max() == 1, "an amplitude repeated")
    }

    @Test
    fun `a cast let go from where a look stayed away brings the look back, and looks start again from home`() {
        repeat(20) { seed ->
            val h = Harness(seed, learned(Phase.WAITING, rate = 4.0, chainP = 0.0, returnHomeP = 0.0))
            var guard = 0
            while (! (h.policy.state == State.REST && h.policy.out) && guard++ < 1_000_000) h.tick(Phase.WAITING)
            assertTrue(h.policy.out, "seed $seed: a look stayed away")
            // Auto Fishing's cast went out from there (its bobber lands in home's water): the return is due from that tick on
            assertEquals(Decision.RETURN, h.tick(Phase.WAITING, returnNow = true), "seed $seed")
            while (h.policy.out) h.tick(Phase.WAITING, returnNow = true)
            assertTrue(angle(h.view, h.home!!) <= Glances.HOME_DEG, "seed $seed: back %.1f off home".format(angle(h.view, h.home!!)))
            val looks = h.looks
            guard = 0
            while (h.looks == looks && guard++ < 1_000_000) h.tick(Phase.WAITING)
            assertEquals(looks + 1, h.looks, "seed $seed: looks go on while you wait")
        }
    }

    @Test
    fun `a killer move taking over a look keeps the speed, and the look never returns`() {
        for (during in listOf(State.OUT, State.DWELL)) repeat(10) { seed ->
            val port = FakePort(0.0, 40.0)
            var now = 0.0
            val aim = HumanAim(port, { now }, Random(seed))
            aim.home = View(0.0, 40.0)
            val policy = GlancePolicy(Random(seed + 100))
            val g = learned(rate = 4.0, chainP = 0.0, returnHomeP = 1.0)
            var prev = port.view()
            var guard = 0
            fun step(glance: Boolean): GlancePolicy.Decision {
                var d = Decision.NONE
                for (f in 1 .. 4) {
                    now += 12.5
                    aim.frame()
                    val v = port.view()
                    assertTrue(hypot(v.yaw - prev.yaw, v.pitch - prev.pitch) <= 1650.0 * 0.0125 + 0.3, "seed $seed: a frame step of ${hypot(v.yaw - prev.yaw, v.pitch - prev.pitch)}")
                    prev = v
                }
                aim.tick(rest = false, anchor = null)
                if (glance) d = aim.glance(policy, now.toLong(), Phase.WAITING, null, g)
                return d
            }
            while (policy.state != during && guard++ < 1_000_000) step(true)
            assertEquals(during, policy.state, "seed $seed")
            assertTrue(aim.start(port.yaw + 90.0, 10.0, Owner.KILLER))
            assertEquals(Decision.DROP, step(true))
            // no return of the dropped look: only one of a look started later
            var later = false
            repeat(400) {
                val d = step(true)
                if (d == Decision.LOOK) later = true
                if (! later) assertNotEquals(Decision.RETURN, d, "seed $seed")
            }
        }
    }

    @Test
    fun `a screen stops a look's move dead`() {
        val port = FakePort(0.0, 40.0)
        var now = 0.0
        val aim = HumanAim(port, { now }, Random(18))
        aim.start(60.0, 0.0, Owner.GLANCE)
        repeat(5) {
            now += 10.0
            aim.frame()
        }
        aim.abort(Owner.GLANCE)
        val at = port.view()
        repeat(50) {
            now += 10.0
            aim.frame()
            aim.tick(rest = false, anchor = null)
        }
        assertEquals(at, port.view())
        assertFalse(aim.moving)
    }
}

/** looks/SPEC §1: a synthetic recording measured by [GlanceMeasure]. */
class GlanceMeasureTest {
    private val lines = ArrayList<GlanceMeasure.Line>()
    private var t = 0L
    private var yaw = 0.0
    private var pitch = 30.0
    private var keys = 0

    private fun still(ms: Long) {
        val end = t + ms
        while (t < end) {
            t += 50
            lines += GlanceMeasure.Line(t, yaw, pitch, 0.0, 64.0, 0.0, keys, emptyMap())
        }
    }

    /** A straight move of [dYaw], [dPitch] over [ticks] ticks. */
    private fun move(dYaw: Double, dPitch: Double, ticks: Int) {
        repeat(ticks) {
            yaw += dYaw / ticks
            pitch += dPitch / ticks
            t += 50
            lines += GlanceMeasure.Line(t, yaw, pitch, 0.0, 64.0, 0.0, keys, emptyMap())
        }
    }

    @Test
    fun `an out-and-back look at home while you wait, a look that stays away and a walk are told apart`() {
        still(1000)
        val castAt = t
        still(9000)
        // a look 30 deg up, a dwell of a second, back to 2 deg short of where it started
        move(0.0, - 30.0, 6)
        val lookEnd = t
        still(1000)
        move(0.0, 28.0, 6)
        still(19_000)
        // a look sideways that stays
        move(40.0, 0.0, 6)
        still(15_000)
        // walking while turning
        keys = 1
        move(- 30.0, 0.0, 6)
        keys = 0
        still(15_000)
        val s = GlanceMeasure.Session(lines, listOf(GlanceMeasure.Cast(castAt, View(0.0, 30.0))), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
            emptyList(), emptyList(), setOf("ROD_OF_THE_SEA"), { false })
        val r = GlanceMeasure.measure(s)
        val kinds = r.moves.map { it.kind }
        assertEquals(listOf(GlanceMeasure.Kind.LOOK, GlanceMeasure.Kind.RETURN, GlanceMeasure.Kind.LOOK, GlanceMeasure.Kind.LOCOMOTION), kinds)
        assertEquals(2, r.looks.size)
        val (back, away) = r.looks
        assertTrue(back.home && back.risk && back.returned)
        assertEquals(Phase.WAITING, back.phase)
        assertEquals(30.0, back.amp, 0.5)
        assertEquals(- 30.0, back.dPitch, 0.5)
        assertEquals(1000L, back.dwellMs, "from the look's last moving tick to the line the return starts from")
        assertEquals(2.0, back.returnErr, 0.1)
        assertTrue(lookEnd > 0)
        // the return stopped 2 deg short: the next look still starts at home, and it never comes back
        assertTrue(away.home)
        assertTrue(away.risk && ! away.returned)
        assertEquals(- 1L, away.dwellMs)
        assertEquals(40.0 * cos(Math.toRadians(28.0)), away.dYaw, 0.2)
        assertEquals(- 1L, away.chainGapMs, "20 s after the first")
        assertEquals(1.0, r.minutes[Phase.WAITING.ordinal], 0.05, "the minute since the cast, but the seconds around the walk")
    }
}
