package fishing

import com.laybalt.features.impl.fishing.HeldUse
import com.laybalt.features.impl.fishing.HumanAim
import com.laybalt.features.impl.fishing.HumanAim.Owner
import com.laybalt.features.impl.fishing.MotorModel.View
import com.laybalt.features.impl.fishing.MotorParams
import com.laybalt.features.impl.fishing.TimingModel
import com.laybalt.features.impl.fishing.TimingModel.Action
import com.laybalt.features.impl.fishing.ViewPolicy
import com.laybalt.features.impl.fishing.ViewPolicy.Decision
import com.laybalt.features.impl.fishing.ViewPolicy.Need
import com.laybalt.features.impl.fishing.ViewPolicy.TurnBack
import kotlin.random.Random
import kotlin.test.*

/**
 * A rest of the view is one moment, whatever clock reads it: in the game a tick's wall clock is read at its start and the
 * driver's clock at the call, a few ms later and on a coarser grid, so a rest derived again at every tick moves by a
 * millisecond on a third to four fifths of the ticks. Every wait drawn per rest (your gap between moves after it, the turn
 * back's wait after it, the held cast's wait) is keyed on which rest it is ([HumanAim.rests]), and the rest is read once
 * ([HumanAim.restedAt]); a move that moves nothing is no rest.
 */
class RestIdentityTest {
    private class Port(var yaw: Double, var pitch: Double) : HumanAim.Port {
        override val grid = 0.15
        override fun view() = View(yaw, pitch)
        override fun set(yaw: Double, pitch: Double): View {
            this.yaw = yaw
            this.pitch = pitch
            return view()
        }
    }

    /** A driver on a clock of its own; [settle] runs frames until its move rested. */
    private class Rig(seed: Long) {
        var clock = 0.0
        val port = Port(0.0, 0.0)
        val aim = HumanAim(port, { clock }, Random(seed)).also { it.params = MotorParams(durationSigma = 0.05, multiP = List(5) { 0.0 }, slowP = 0.0) }

        fun settle() {
            while (aim.moving) {
                clock += 7.0
                aim.frame()
            }
        }
    }

    @Test
    fun `the view's last moving tick is where your gaps were measured from, before the hand's slow tail to its rest`() {
        val rig = Rig(5)
        assertEquals(Long.MIN_VALUE / 2, rig.aim.turnedAt(0), "none yet")
        // ticks every 50 ms with 7 ms frames between them, as the game drives it; the view's step over each tick logged
        var t = 0L
        val steps = ArrayList<Pair<Long, Double>>()
        var last = rig.port.view()
        fun tick() {
            rig.clock = t.toDouble()
            rig.aim.tick(rest = false, anchor = null)
            val v = rig.port.view()
            steps += t to com.laybalt.features.impl.fishing.MotorModel.angle(last.yaw, last.pitch, v.yaw, v.pitch)
            last = v
        }
        tick()
        assertTrue(rig.aim.start(40.0, 0.0, Owner.KILLER))
        while (rig.aim.moving || t < 2000) {
            repeat(7) {
                rig.clock += 7.0
                rig.aim.frame()
            }
            t += 50
            tick()
        }
        // the learner's moving step: faster than 15 deg/s over the tick
        val lastMoving = steps.last { it.second / 50.0 * 1000.0 > com.laybalt.features.impl.fishing.ViewMotion.THRESHOLD }.first
        assertEquals(lastMoving, rig.aim.turnedAt(t), "the end of the last moving tick")
        // the tick that showed the last step at the latest: the rest came in its frames or later
        assertTrue(rig.aim.turnedAt(t) <= rig.aim.restedAt(t) + 50, "${rig.aim.turnedAt(t)} / ${rig.aim.restedAt(t)}")
        // rest nudges of a few mouse steps are no turn, as the learner finds them: only a step faster than 15 deg/s is
        var expected = rig.aim.turnedAt(t)
        var nudged = 0
        repeat(400) {
            t += 50
            rig.clock = t.toDouble()
            rig.aim.tick(rest = true, anchor = View(40.0, 0.0))
            val v = rig.port.view()
            val step = com.laybalt.features.impl.fishing.MotorModel.angle(last.yaw, last.pitch, v.yaw, v.pitch)
            if (step > 0.0) nudged++
            if (step / 50.0 * 1000.0 > com.laybalt.features.impl.fishing.ViewMotion.THRESHOLD) expected = t
            last = v
            assertEquals(expected, rig.aim.turnedAt(t), "tick $t")
        }
        assertTrue(nudged > 0, "the rest nudged the view")
        // a turn of someone else's (your mouse, Auto Fishing) counts as well
        rig.port.yaw += 3.0
        t += 50
        rig.clock = t.toDouble()
        rig.aim.tick(rest = false, anchor = null)
        assertEquals(t, rig.aim.turnedAt(t))
        // read later in the tick on the driver's clock: still that tick's end, not the call's
        rig.clock = t + 9.0
        assertEquals(t, rig.aim.turnedAt(t))
    }

    @Test
    fun `the rest is read once, ticks read on a later and coarser clock give the same moment, a new rest a new one`() {
        val rig = Rig(1)
        assertEquals(Long.MIN_VALUE / 2, rig.aim.restedAt(0), "none yet")
        assertTrue(rig.aim.start(30.0, 5.0, Owner.KILLER))
        assertEquals(0, rig.aim.rests)
        rig.settle()
        val rested = rig.clock
        assertEquals(1, rig.aim.rests)
        val jitter = Random(3)
        var t = (rig.clock / 50.0).toLong() * 50 + 50
        // the driver's tick at the client tick's start, the read later in the tick: placed against the tick's start, the
        // rest is where it came, not up to a tick early
        val first = run {
            rig.clock = t.toDouble()
            rig.aim.tick(rest = false, anchor = null)
            rig.clock = t + 5.0 + jitter.nextDouble() * 11.0
            rig.aim.restedAt(t)
        }
        assertTrue(kotlin.math.abs(first - rested) <= 1.0, "$first, rested at $rested")
        // the driver's clock read up to 16 ms past each tick's: the derived time would move, the read one does not
        val derived = HashSet<Long>()
        repeat(40) {
            t += 50
            rig.clock = t.toDouble()
            rig.aim.tick(rest = false, anchor = null)
            rig.clock = t + jitter.nextDouble() * 16.0
            derived += t - rig.aim.sinceRestMs().toLong()
            assertEquals(first, rig.aim.restedAt(t), "tick $t")
        }
        assertTrue(derived.size > 3, "the derived time moves: $derived")
        // read without a driver's tick in this client tick (a screen stopped them): against the call, no later than it
        val noTick = Rig(4)
        noTick.aim.start(30.0, 5.0, Owner.KILLER)
        noTick.settle()
        val at = noTick.clock
        noTick.clock = at + 300.0
        assertTrue(noTick.aim.restedAt((at + 300).toLong()) in (at - 1).toLong() .. (at + 1).toLong())
        // the next move's rest is another one
        assertTrue(rig.aim.start(- 20.0, 0.0, Owner.IDLE))
        assertEquals(t, rig.aim.restedAt(t), "in flight: now")
        rig.settle()
        assertEquals(2, rig.aim.rests)
        t = (rig.clock / 50.0).toLong() * 50 + 50
        val second = rig.aim.restedAt(t)
        assertTrue(second > first, "$second after $first")
    }

    @Test
    fun `a goal where the view already is moves nothing and stamps no rest, a move it cuts off does rest`() {
        val rig = Rig(2)
        assertTrue(rig.aim.start(0.0, 0.0, Owner.KILLER))
        assertFalse(rig.aim.moving)
        assertEquals(0, rig.aim.rests)
        assertTrue(rig.aim.sinceRestMs().isInfinite(), "no rest that never happened")
        rig.aim.start(40.0, 0.0, Owner.KILLER)
        rig.settle()
        val rest = rig.clock
        rig.clock += 300.0
        // a turn back whose undershoot is more than the way left: its goal is the view itself
        val v = rig.port.view()
        rig.aim.start(v.yaw, v.pitch, Owner.KILLER)
        assertFalse(rig.aim.moving)
        assertEquals(1, rig.aim.rests)
        assertEquals(300.0, rig.aim.sinceRestMs(), 1e-9, "still timed from the move's real rest at $rest")
        // a move in flight that a new start leaves at rest (nothing carried) came to rest then
        rig.aim.start(v.yaw + 30.0, v.pitch, Owner.KILLER)
        repeat(3) { rig.clock += 7.0; rig.aim.frame() }
        rig.aim.abort()
        assertEquals(2, rig.aim.rests)
    }

    /** Ticks from 1000 on a policy whose first turn after a swap at 1000 waits for a move that rested at [restAt], read with [jitter]. */
    private fun firstTurnAfterRest(seed: Int, restAt: Long, jitter: Random?, keyOnTime: Boolean): Long {
        val p = ViewPolicy(Random(seed))
        var t = 1000L
        while (t < 20_000L) {
            val moving = t < restAt
            val read = if (moving) t else restAt - (jitter?.nextInt(0, 2) ?: 0)
            val s = ViewPolicy.Situation(t, 1, Need.RANGED, View(0.0, 0.0), View(90.0, 0.0), 90.0, 30.0, anchorAt = 1000L, swapped = true,
                accept = { 10.0 }, restedAt = read, viewMoving = moving, restId = if (keyOnTime) read else 7L)
            if (p.update(s) == Decision.START) return t - restAt
            t += 50
        }
        error("no turn")
    }

    @Test
    fun `your gap between moves is drawn once per rest, however the rest's time reads`() {
        // a rest read a millisecond off now and then (as the derived one did): keyed on the rest, the gap is the one drawn
        // with a steady read, seed for seed
        val steady = List(1500) { firstTurnAfterRest(it, 1130, null, keyOnTime = false) }
        val jittered = List(1500) { firstTurnAfterRest(it, 1130, Random(it.toLong()), keyOnTime = false) }
        val moved = steady.indices.count { kotlin.math.abs(steady[it] - jittered[it]) > 50 }
        assertTrue(moved < 15, "$moved of 1500 moved by more than a tick")
        val q = steady.sorted()
        // yours: MOVE_GAP p50 233 / p90 552 (99-399 ms after the rest of a move past the swap)
        assertTrue(q[750] in 180L .. 300L && q[1350] in 400L .. 700L, "p50 ${q[750]} p90 ${q[1350]}")
        // keyed on the time as read, the gap was drawn again whenever it moved: the soonest of many draws
        val old = List(1500) { firstTurnAfterRest(it, 1130, Random(it.toLong()), keyOnTime = true) }.sorted()
        assertTrue(old[1350] < q[1350] - 100, "the old keying compresses p90: ${old[1350]} against ${q[1350]}")
    }

    @Test
    fun `the turn back's wait after a move's rest is drawn once, a millisecond off at later ticks or not`() {
        repeat(200) { seed ->
            val draws = Random(seed)
            var n = 0
            val b = TurnBack(TurnBack.Kind.NOW, 1300, 5.0, { n++; TimingModel.draw(Action.TURN_BACK_AFTER_MOVE, emptyList(), null, draws) }, from = 1000, deathMove = 4)
            val jitter = Random(seed * 31L)
            var t = 1000L
            b.update(t, moving = true, move = 4)
            var started = false
            while (! started && t < 6000) {
                t += 50
                started = b.update(t, moving = false, restedAt = 1120L - jitter.nextInt(0, 2), move = 4) == Decision.START
            }
            assertTrue(started)
            assertEquals(1, n, "seed $seed: drawn $n times")
        }
    }

    @Test
    fun `a held cast's wait is drawn once per rest, its time read a millisecond off at later ticks or not`() {
        val jitter = Random(5)
        repeat(300) { seed ->
            val h = HeldUse()
            var draws = 0
            var t = 1000L
            h.hold(t, true)
            // rest 9 first showed at the tick at 1030; read again later it may come a millisecond off, on ticks 45-55 ms apart
            var went = false
            while (! went && t < 5000) {
                t += 45 + jitter.nextInt(0, 11)
                went = h.go(t, true, 1030L - jitter.nextInt(0, 2), ViewPolicy.MIN_GAP_MS, 9L) { draws++; TimingModel.draw(Action.REST_TO_RECAST, emptyList(), null, Random(seed)) }
            }
            assertTrue(went)
            assertEquals(1, draws, "seed $seed: drawn $draws times")
        }
    }
}
