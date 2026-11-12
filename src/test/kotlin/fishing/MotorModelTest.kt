package fishing

import com.laybalt.features.impl.fishing.BehaviorLearner
import com.laybalt.features.impl.fishing.HumanAim
import com.laybalt.features.impl.fishing.MotorModel
import com.laybalt.features.impl.fishing.MotorModel.View
import com.laybalt.features.impl.fishing.MotorParams
import com.laybalt.features.impl.fishing.RestMotion
import com.laybalt.features.impl.fishing.TimingModel
import com.laybalt.features.impl.fishing.ViewMotion
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.*

/**
 * The stroke planner and stepper on a fake clock: the shape of a stroke, the caps, stop / abort / retarget, the yaw
 * wrap, and the statistics its defaults produce when measured like the recordings (50 ms ticks, 0.15° grid, the
 * segmentation of `humanize/segment.js`).
 */
class MotorModelTest {
    private val single = MotorParams(slowP = 0.0, multiP = listOf(0.0, 0.0, 0.0, 0.0, 0.0))

    /** Samples a move every [frameMs] until it is done: (time, view) pairs, the start included. */
    private fun run(m: MotorModel, t0: Double, frameMs: Double = 1.0, maxMs: Double = 30000.0): List<Pair<Double, View>> {
        val out = arrayListOf(t0 to m.view)
        var t = t0
        while (m.moving && t - t0 < maxMs) {
            t += frameMs
            out += t to m.sample(t)
        }
        return out
    }

    private fun speeds(path: List<Pair<Double, View>>) =
        (1 until path.size).map { (path[it].second - path[it - 1].second).length / (path[it].first - path[it - 1].first) * 1000 }

    private fun randomGoal(rng: Random, from: View, amp: Double): View {
        val dir = rng.nextDouble() * 2 * PI
        var pitch = from.pitch + amp * sin(dir)
        if (abs(pitch) > 60) pitch = from.pitch - amp * sin(dir)
        return View(from.yaw + amp * cos(dir), pitch.coerceIn(- 60.0, 60.0))
    }

    @Test
    fun `a stroke starts and ends at rest`() {
        val rng = Random(1)
        val starts = ArrayList<Double>()
        val ends = ArrayList<Double>()
        repeat(200) {
            val m = MotorModel()
            val goal = randomGoal(rng, View(0.0, 0.0), 10.0 + rng.nextDouble() * 140)
            m.start(0.0, 0.0, goal.yaw, goal.pitch, 0.0, single, rng)
            val v = speeds(run(m, 0.0)).dropWhile { it == 0.0 }.dropLastWhile { it == 0.0 }
            val peak = v.max()
            starts += v.first() / peak
            ends += v.last() / peak
        }
        assertTrue(starts.max() < 0.12 && ends.max() < 0.12, "first ${starts.max()} last ${ends.max()} of the peak")
        assertTrue(q(starts, 0.5) < 0.03 && q(ends, 0.5) < 0.03, "first ${q(starts, 0.5)} last ${q(ends, 0.5)} of the peak (p50)")
    }

    @Test
    fun `a stroke never goes back`() {
        val rng = Random(2)
        repeat(200) {
            val m = MotorModel()
            val goal = randomGoal(rng, View(30.0, 10.0), 6.0 + rng.nextDouble() * 170)
            m.start(30.0, 10.0, goal.yaw, goal.pitch, 0.0, single, rng)
            val d = goal - View(30.0, 10.0)
            val progress = run(m, 0.0, 2.0).map { (it.second.yaw - 30.0) * d.yaw + (it.second.pitch - 10.0) * d.pitch }
            for (i in 1 until progress.size) assertTrue(progress[i] >= progress[i - 1] - 1e-9, "went back at $i")
            assertEquals(goal.yaw, m.view.yaw, 1e-6)
            assertEquals(goal.pitch, m.view.pitch, 1e-6)
        }
    }

    @Test
    fun `a move ends on its goal, with its planned duration`() {
        val rng = Random(3)
        repeat(500) {
            val m = MotorModel()
            val from = View(rng.nextDouble() * 360 - 180, rng.nextDouble() * 100 - 50)
            val goal = randomGoal(rng, from, 1.0 + rng.nextDouble() * 179)
            m.start(from.yaw, from.pitch, goal.yaw, goal.pitch, 1000.0, MotorParams(), rng)
            val planned = m.plannedMs
            val path = run(m, 1000.0)
            assertFalse(m.moving)
            // a last stroke shorter than minStrokeDeg is not made
            assertTrue((m.view - goal).length < 0.2, "ended ${m.view} for $goal")
            assertEquals(planned, path.last().first - 1000.0, 1.0, "planned $planned")
        }
    }

    @Test
    fun `every step stays within the caps at any frame rate, and a hitch slows the move instead of jumping it`() {
        val rng = Random(4)
        val p = MotorParams()
        var worstTick = 0.0
        var worstSample = 0.0
        for (fps in listOf(10.0, 30.0, 60.0, 144.0)) repeat(250) {
            val m = MotorModel()
            // near the pole half of the time: big yaw changes are small great-circle turns there
            val pole = rng.nextBoolean()
            val from = View(0.0, if (pole) 85.0 * (if (rng.nextBoolean()) 1 else - 1) else rng.nextDouble() * 100 - 50)
            val goal = if (pole) View(rng.nextDouble() * 360 - 180, from.pitch - rng.nextDouble() * 30 * from.pitch / 85)
            else randomGoal(rng, from, 6.0 + rng.nextDouble() * 174)
            m.start(from.yaw, from.pitch, goal.yaw, goal.pitch, 0.0, p, rng)
            // frames and client ticks, with one 250 ms hitch somewhere
            val hitchAt = rng.nextDouble() * 600
            var frame = 1000.0 / fps
            var tick = 50.0
            var last = 0.0 to m.view
            var lastTick = 0.0 to m.view
            while (m.moving && last.first < 30000) {
                val t = min(frame, tick)
                // a frame on a tick is one sample: two 1e-14 ms apart would measure float noise as speed
                val isTick = tick - t < 1e-6
                if (frame - t < 1e-6) frame += 1000.0 / fps
                if (isTick) tick += 50.0
                if (t > hitchAt && t < hitchAt + 250) continue
                val v = m.sample(t)
                val step = (v - last.second).length
                worstSample = max(worstSample, step / min(t - last.first, p.maxStepMs) * 1000)
                if (isTick) {
                    val a = lastTick.second
                    val gc = MotorModel.angle(a.yaw, a.pitch, v.yaw, v.pitch)
                    // a tick spanning the hitch is covered by the per-sample bound
                    if (t - lastTick.first <= 50.0 + 1e-9) worstTick = max(worstTick, max(gc, (v - a).length) / 0.05)
                    lastTick = t to v
                }
                last = t to v
            }
            assertFalse(m.moving)
        }
        assertTrue(worstSample <= p.hardCap * (1 + 1e-9), "a step at $worstSample deg/s")
        assertTrue(worstTick <= p.hardCap * (1 + 1e-9), "a tick at $worstTick deg/s")
    }

    @Test
    fun `one stroke stays within both peak caps`() {
        val rng = Random(5)
        var gcWorst = 0.0
        var mouseWorst = 0.0
        repeat(400) {
            val m = MotorModel()
            val pole = it % 2 == 0
            val from = View(0.0, if (pole) 80.0 else 0.0)
            val goal = if (pole) View(rng.nextDouble() * 360 - 180, 80.0 - rng.nextDouble() * 20) else randomGoal(rng, from, 100.0 + rng.nextDouble() * 80)
            m.start(from.yaw, from.pitch, goal.yaw, goal.pitch, 0.0, single, rng)
            val path = run(m, 0.0, 10.0)
            for (i in 5 until path.size) {
                val a = path[i - 5].second
                val b = path[i].second
                gcWorst = max(gcWorst, MotorModel.angle(a.yaw, a.pitch, b.yaw, b.pitch) / 0.05)
                mouseWorst = max(mouseWorst, (b - a).length / 0.05)
            }
        }
        assertTrue(gcWorst <= 1208 * 1.03, "great circle $gcWorst deg/s")
        assertTrue(mouseWorst <= 1394 * 1.03, "mouse $mouseWorst deg/s")
    }

    @Test
    fun `stop coasts to rest along a tail and abort freezes`() {
        val rng = Random(6)
        repeat(100) {
            val m = MotorModel()
            m.start(0.0, 0.0, 120.0, 0.0, 0.0, single, rng)
            val stopAt = 120.0 * (0.2 + 0.5 * rng.nextDouble())
            var t = 0.0
            var prev = m.view
            var before = 0.0
            while (prev.yaw < stopAt) {
                t += 2.0
                val v = m.sample(t)
                before = (v - prev).length / 2.0 * 1000
                prev = v
            }
            val left = m.plannedMs - t
            m.stop(t)
            assertTrue(m.moving && m.stopping)
            val after = speeds(listOf(t to prev) + run(m, t, 2.0).drop(1))
            assertEquals(before, after.first(), before * 0.15 + 5, "speed at the stop")
            for (i in 1 until after.size) assertTrue(after[i] <= after[i - 1] + 1e-6, "sped up after the stop at $i")
            assertEquals(max(120.0, 0.35 * left), after.size * 2.0, 4.0, "tail length")
        }
        val m = MotorModel()
        m.start(0.0, 0.0, 90.0, 0.0, 0.0, MotorParams(), rng)
        val v = m.sample(100.0)
        m.abort()
        assertFalse(m.moving)
        assertEquals(v, m.sample(150.0))
        assertEquals(v, m.sample(400.0))
    }

    @Test
    fun `a retarget keeps the speed and happens once per stroke`() {
        for (force in listOf(false, true)) repeat(50) { seed ->
            val rng = Random(100 + seed)
            val m = MotorModel()
            m.start(0.0, 0.0, 90.0, 0.0, 0.0, single, rng)
            var t = 0.0
            while (t < m.plannedMs * 0.4) m.sample(++t)
            val before = m.velocity()
            assertTrue(m.retarget(130.0, 0.0, t))
            assertContinuous(before, m.velocity())
            assertEquals(m.view, m.sample(t), "no jump")
            m.sample(++t)
            assertEquals(force, m.retarget(175.0, 0.0, t, force))
            val path = run(m, t)
            assertEquals(if (force) 175.0 else 130.0, path.last().second.yaw, 1e-6)
            assertEquals(175.0, m.goal.yaw, 1e-9, "the goal is kept for the next move")
        }
        // a goal that hardly moved waits for the next stroke
        val m = MotorModel()
        m.start(0.0, 0.0, 90.0, 0.0, 0.0, single, Random(7))
        m.sample(100.0)
        assertFalse(m.retarget(93.0, 0.0, 100.0))
    }

    @Test
    fun `a new move while moving keeps the speed`() {
        val rng = Random(8)
        repeat(50) {
            val m = MotorModel()
            m.start(0.0, 0.0, 100.0, 20.0, 0.0, single, rng)
            var t = 0.0
            while (t < m.plannedMs * 0.5) m.sample(++t)
            val before = m.velocity()
            val v = m.view
            m.start(v.yaw, v.pitch, - 30.0, - 10.0, t, MotorParams(), rng)
            assertContinuous(before, m.velocity())
            run(m, t)
            assertTrue((m.view - View(- 30.0, - 10.0)).length < 0.2, "ended at ${m.view}")
        }
    }

    @Test
    fun `the yaw direction is fixed when the move is planned`() {
        val rng = Random(9)
        repeat(100) {
            val m = MotorModel()
            m.start(170.0, 0.0, - 170.0, 0.0, 0.0, MotorParams(), rng)
            val yaws = run(m, 0.0, 5.0).map { it.second.yaw }
            assertEquals(190.0, yaws.last(), 0.2)
            assertTrue(yaws.min() > 165.0, "went the other way: ${yaws.min()}")
            // the goal crosses the meridian behind the start: the view keeps turning the same way
            val n = MotorModel()
            n.start(0.0, 0.0, 170.0, 0.0, 0.0, MotorParams(), rng)
            var t = 0.0
            val path = arrayListOf(n.view.yaw)
            var goal = 170.0
            while (n.moving) {
                t += 5.0
                goal += 0.4
                n.retarget(MotorModel.wrap(goal), 0.0, t)
                path += n.sample(t).yaw
            }
            // overshoots come back a little, but the view never heads the other way round
            assertTrue(path.min() > - 5.0, "went the other way: ${path.min()}")
            assertTrue(path.last() in 160.0 .. goal + 5.0, "ended at ${path.last()}, the goal at $goal")
        }
    }

    @Test
    fun `a look that keeps a creature in view never goes past its goal on the way`() {
        /** How far past its goal along the way each of [n] moves of 6-60 deg went, as a share of the way. */
        fun pastGoal(params: MotorParams, seed: Int, n: Int = 3000): List<Double> {
            val rng = Random(seed)
            return List(n) {
                val from = View(rng.nextDouble(- 180.0, 180.0), rng.nextDouble(- 40.0, 40.0))
                val goal = randomGoal(rng, from, 6.0 + rng.nextDouble() * 54.0)
                val m = MotorModel()
                m.start(from.yaw, from.pitch, goal.yaw, goal.pitch, 0.0, params, rng)
                val way = goal - from
                val a = way.length
                run(m, 0.0).maxOf { (_, v) -> ((v - from).yaw * way.yaw + (v - from).pitch * way.pitch) / a } / a - 1.0
            }
        }
        val full = pastGoal(HumanAim.paramsOf(null), 1)
        val keep = pastGoal(HumanAim.keepParamsOf(null), 1)
        // your full hand overshoots now and then, by up to nearly half the way
        assertTrue(full.count { it > 0.05 } > full.size * 0.03, "full hand: ${full.count { it > 0.05 }} past by 5 %")
        // a look that keeps a creature in view: no stroke past the goal, at most what one axis lagging the other adds to a
        // sideways correction (its path bends, by a percent or so of the way)
        assertTrue(keep.all { it < 0.03 }, "past its goal by %.1f %%".format(keep.max() * 100))
        assertTrue(keep.count { it > 0.005 } < keep.size / 100, "${keep.count { it > 0.005 }} past by 0.5 %")
        val k = HumanAim.keepParamsOf(null)
        assertEquals(0.0, k.slowP)
        assertEquals(HumanAim.paramsOf(null).crossSd / 2, k.crossSd, 1e-12)
    }

    @Test
    fun `a stroke's share drawn under the goal never piles on it`() {
        /** How many strokes each of [n] moves of [amp] degrees with [params] started, by count. */
        fun strokes(params: MotorParams, seed: Int, amp: ClosedFloatingPointRange<Double>, n: Int = 2000): Map<Int, Int> {
            val rng = Random(seed)
            return List(n) {
                val from = View(rng.nextDouble(- 180.0, 180.0), rng.nextDouble(- 30.0, 30.0))
                val goal = randomGoal(rng, from, amp.start + rng.nextDouble() * (amp.endInclusive - amp.start))
                val m = MotorModel()
                m.start(from.yaw, from.pitch, goal.yaw, goal.pitch, 0.0, params, rng)
                run(m, 0.0)
                m.strokesStarted
            }.groupingBy { it }.eachCount()
        }
        // a keep look's hand, always in strokes, straight: a first or middle stroke on the goal leaves its last one nothing
        val keep = HumanAim.keepParamsOf(null).copy(multiP = List(5) { 1.0 }, leadInP = 0.0, crossSd = 0.0)
        assertEquals(1.0, keep.firstMax)
        assertEquals(1.0, keep.middleMax)
        // a clamp put 11 % of first strokes on the goal and half the middle ones (a first stroke short of half the way
        // always leaves the middle one its share of the rest)
        val first = strokes(keep.copy(middleP = 0.0), 1, 20.0 .. 60.0)
        assertTrue((first[1] ?: 0) < 2000 * 0.02, "first stroke on the goal: $first")
        val middle = strokes(keep.copy(middleP = 1.0, firstMean = 0.45, firstSd = 0.05, firstMin = 0.35, firstMax = 0.55), 2, 100.0 .. 150.0)
        assertTrue((middle[2] ?: 0) < 2000 * 0.04, "middle stroke on the goal: $middle")
    }

    /** 2500 moves of 6-180° planned with [params] and measured like the recordings (50 ms lines on the grid): those of 20° or more. */
    private fun measured20(params: MotorParams, seed: Int): List<ViewMotion.Move> {
        val rng = Random(seed)
        val moves = ArrayList<ViewMotion.Move>()
        repeat(2500) {
            val from = View(rng.nextDouble(- 180.0, 180.0), rng.nextDouble(- 40.0, 40.0))
            val goal = randomGoal(rng, from, 6.0 * 30.0.pow(rng.nextDouble()))
            val m = MotorModel()
            val start = 160.0 + 25 * rng.nextDouble()
            m.start(from.yaw, from.pitch, goal.yaw, goal.pitch, start, params, rng)
            val lines = arrayListOf(ViewMotion.Line(0, grid(from.yaw), grid(from.pitch)))
            var t = 0.0
            var v = from
            var still = 0
            while (still < 3) {
                for (f in 1 .. 5) if (t + f * 10 >= start) v = m.sample(t + f * 10)
                t += 50.0
                lines += ViewMotion.Line(t.roundToLong(), grid(v.yaw), grid(v.pitch))
                if (t > start && ! m.moving) still++
            }
            moves += ViewMotion.runs(lines).flatMap { ViewMotion.segment(it).moves }.filter { ! it.cut }
        }
        return moves.filter { it.amp >= 20 }
    }

    /** The first stroke's share of each measured two-stroke move of [moves]. */
    private fun firstShares(moves: List<ViewMotion.Move>) = moves.filter { it.strokes.size == 2 }.map { it.strokes[0].amp / it.amp }

    @Test
    fun `the default first stroke alone on the motor goes past the reference recordings' first strokes, and past the goal as often`() {
        val m20 = measured20(MotorParams(), 21)
        val first = firstShares(m20)
        // the reference first share's p90 is 1.09: the first strokes past the goal that make most of the overshoots. The
        // plan is set on the replay of the reference fights (the policy ending moves, the looks and their returns, the rest
        // nudges: BehaviorLearner's PLAN_FIRST), where it measures 1.12; the motor alone measures it further past the goal
        assertEquals(1.18, ViewMotion.quantile(first, 0.9), 0.05, "first share p90")
        val over = m20.count { it.over > 1 }.toDouble() / m20.size
        assertTrue(over >= 0.08, "overshoot > 1 deg ${"%.3f".format(over)}")
    }

    @Test
    fun `a learned first stroke measures as far off the reference's first share p90 as your hand's is`() {
        assertEquals(MotorParams().firstMean, BehaviorLearner.firstMuOf(1.09), 1e-12, "the reference's own plan")
        // a hand 0.06 off at its p90, the motor's measured first share moving by about half of what its plan does (the
        // plan moved by the p50's offset itself measured 0.43 of that offset)
        for (d in listOf(- 0.06, 0.06)) {
            val plan = MotorParams(firstMean = BehaviorLearner.firstMuOf(1.09 + d))
            val off = (41 .. 44).map { seed ->
                ViewMotion.quantile(firstShares(measured20(plan, seed)), 0.9) - ViewMotion.quantile(firstShares(measured20(MotorParams(), seed)), 0.9)
            }.average()
            assertEquals(d, off, 0.02, "measured p90 moved by")
        }
    }

    @Test
    fun `a move ended after its stroke still takes back a stroke that went past the goal`() {
        /** Where a move with first strokes of [first] of the way, ended once its first stroke is in flight, went at most and came to rest, along the way. */
        fun ended(first: Double, seed: Int): Pair<Double, Double> {
            val rng = Random(seed)
            val p = MotorParams(slowP = 0.0, multiP = List(5) { 1.0 }, leadInP = 0.0, firstMean = first, firstSd = 0.01, firstMin = 0.3, firstMax = 1.45,
                middleP = 0.0, crossSd = 0.0, lagP50Ms = 1.0, lagMaxMs = 1.0)
            val m = MotorModel()
            m.start(0.0, 0.0, 60.0, 0.0, 0.0, p, rng)
            m.sample(20.0)
            m.endAfterStroke()
            val path = listOf(0.0 to m.view) + run(m, 20.0)
            return path.maxOf { it.second.yaw } / 60.0 to path.last().second.yaw / 60.0
        }
        repeat(20) { seed ->
            // past the goal: it comes back to it in the same move
            val (far, rest) = ended(1.3, seed)
            assertTrue(far > 1.2 && abs(rest - 1.0) < 0.02, "past: went to $far, rested at $rest")
            // short of it: the move ends where its stroke did
            val (farShort, restShort) = ended(0.6, seed)
            assertTrue(abs(farShort - restShort) < 0.01 && restShort in 0.55 .. 0.65, "short: went to $farShort, rested at $restShort")
        }
    }

    /** A new stroke starts at rest, so the speed only changes by what its first half millisecond adds. */
    private fun assertContinuous(before: View, after: View) {
        val jump = (after - before).length
        assertTrue(jump <= 0.1 * before.length + 30.0, "speed $before -> $after")
    }

    @Test
    fun `the plan never passes the pole`() {
        val rng = Random(10)
        // first strokes far past the goal, aimed at the pole
        val p = MotorParams(slowP = 0.0, multiP = listOf(1.0, 1.0, 1.0, 1.0, 1.0), leadInP = 0.0, firstMean = 1.4, firstSd = 0.05)
        var frames = 0
        var over = 0
        repeat(500) {
            val m = MotorModel()
            val from = View(0.0, 40.0 + rng.nextDouble() * 40)
            m.start(from.yaw, from.pitch, rng.nextDouble() * 120 - 60, 88.0 + rng.nextDouble() * 2, 0.0, p, rng)
            var t = 0.0
            while (m.moving) {
                t += 5.0
                val v = m.sample(t)
                assertTrue(abs(v.pitch) <= 90.0)
                frames++
                if (abs(m.planView().pitch) > 90.0 + 1e-9) over++
            }
        }
        assertTrue(over <= frames * 0.002, "$over of $frames frames past the pole")
    }

    @Test
    fun `the same seed gives the same path, and sampling twice gives the same view`() {
        fun path(): List<View> {
            val m = MotorModel()
            m.start(10.0, 5.0, 140.0, - 30.0, 0.0, MotorParams(), Random(42))
            val out = ArrayList<View>()
            var t = 0.0
            while (m.moving) {
                t += 7.0
                val v = m.sample(t)
                assertEquals(v, m.sample(t))
                out += v
            }
            return out
        }
        assertEquals(path(), path())
    }

    @Test
    fun `a small outside change is carried along and a big one aborts`() {
        val m = MotorModel()
        m.start(0.0, 0.0, 60.0, 0.0, 0.0, single, Random(11))
        m.sample(100.0)
        val v = m.view
        assertTrue(m.external(1.0, - 0.5))
        val next = m.sample(110.0)
        assertTrue((next - (v + View(1.0, - 0.5))).length < 1650 * 0.01 + 1e-9, "no snap back")
        run(m, 110.0)
        assertEquals(61.0, m.view.yaw, 1e-6)
        assertEquals(- 0.5, m.view.pitch, 1e-6)

        m.start(0.0, 0.0, 60.0, 0.0, 1000.0, single, Random(12))
        m.sample(1100.0)
        assertFalse(m.external(3.0, 0.0))
        assertFalse(m.moving)
    }

    @Test
    fun `the duration law turns into planned bells`() {
        val p = MotorParams()
        var prev = 0.0
        for (a in listOf(10.0, 30.0, 60.0, 90.0, 150.0)) {
            val measured = MotorModel.lawMs(a, p)
            val planned = MotorModel.plannedMs(measured, a)
            assertTrue(planned > measured && planned <= measured / 0.4 + 1e-9, "A $a: $measured -> $planned")
            val typical = MotorModel.typicalMs(0.0, 0.0, a, 0.0, p)
            assertTrue(typical > prev, "longer turns take longer: $a $typical")
            prev = typical
        }
        assertTrue(MotorModel.typicalMs(0.0, 80.0, 0.0, 25.0, p, lookDown = true) < MotorModel.typicalMs(0.0, 80.0, 0.0, 25.0, p))
        val short = MotorModel.towards(170.0, 0.0, - 170.0, 0.0, 5.0)
        assertEquals(185.0, short.yaw, 1e-6)
    }

    @Test
    fun `the defaults measure like the recordings`() {
        val rng = Random(13)
        val goals = 3000
        val rots = ArrayList<Rot>()
        val m = MotorModel()
        var t = 0.0
        var at = View(0.0, 0.0)
        repeat(goals) {
            val amp = 6.0 * 30.0.pow(rng.nextDouble())
            val goal = randomGoal(rng, at, amp).let { View(it.yaw, it.pitch.coerceIn(- 50.0, 50.0)) }
            repeat(30) {
                t += 50
                rots += Rot(t, at.yaw, at.pitch)
            }
            val start = t + 25 * rng.nextDouble()
            m.start(at.yaw, at.pitch, goal.yaw, goal.pitch, start, MotorParams(), rng)
            var v = at
            var still = 0
            while (still < 2) {
                for (f in 1..5) if (t + f * 10 >= start) v = m.sample(t + f * 10)
                t += 50
                rots += Rot(t, grid(v.yaw), grid(v.pitch))
                if (! m.moving) still++
            }
            at = m.view
        }
        val moves = segment(rots).filter { ! it.cut }
        val m6 = moves.filter { it.amp >= 6 }
        val m20 = moves.filter { it.amp >= 20 }
        val m30 = moves.filter { it.amp >= 30 }
        val m60 = moves.filter { it.amp >= 60 }
        val r = m6.map { ln(it.dur / law(it.amp)) }
        val mean = r.average()
        val sd = sqrt(r.sumOf { (it - mean) * (it - mean) } / (r.size - 1))
        val slow = m6.count { it.dur > 2 * law(it.amp) }.toDouble() / m6.size
        val over = m20.count { it.over > 1 }.toDouble() / m20.size
        val multi = m60.count { it.nsub >= 2 }.toDouble() / m60.size
        val tpk = m20.map { it.tpk }
        val lag = moves.map { abs(it.halfLag) }.filter { it.isFinite() }
        val straight = m20.filter { abs(it.dyaw) > 5 && abs(it.dpitch) > 5 }.map { it.straight }
        val endHigh = m30.count { it.v1 > 0.5 }.toDouble() / m30.size
        val endP50 = q(m30.map { it.v1 }, 0.5)
        println("segments ${m6.size} of $goals; ln(dur/law) mean ${"%.3f".format(mean)} sd ${"%.3f".format(sd)}; >2x ${pct(slow)}; overshoot>1 (A>=20) ${pct(over)}; multi A>=60 ${pct(multi)}")
        println("tpk p10/p90 ${"%.2f".format(q(tpk, 0.1))}/${"%.2f".format(q(tpk, 0.9))}; half lag p50/p90 ${q(lag, 0.5).roundToLong()}/${q(lag, 0.9).roundToLong()}; straightness p10 ${"%.2f".format(q(straight, 0.1))}; last/peak>0.5 ${pct(endHigh)} p50 ${"%.2f".format(endP50)}")

        assertTrue(m6.size <= goals * 1.2, "segments per move ${m6.size.toDouble() / goals}")
        assertTrue(mean in - 0.08 .. 0.13, "ln(dur/law) mean $mean")
        assertTrue(sd in 0.42 .. 0.59, "ln(dur/law) sd $sd")
        assertTrue(slow in 0.05 .. 0.13, "slow moves $slow")
        // these goals give the critic's simulation 6.7 %; his own goals (more big turns) give it 9.4 %, the replay checks 8-20 %
        assertTrue(over in 0.045 .. 0.20, "overshoot $over")
        assertTrue(multi in 0.30 .. 0.60, "multi-stroke $multi")
        assertTrue(q(tpk, 0.1) in 0.11 .. 0.27 && q(tpk, 0.9) in 0.66 .. 0.82, "time to peak")
        assertTrue(q(lag, 0.5) in 15.0 .. 50.0 && q(lag, 0.9) in 120.0 .. 330.0, "axis lag")
        assertTrue(q(straight, 0.1) in 0.70 .. 0.88, "straightness")
        assertTrue(endHigh <= 0.10 && endP50 <= 0.3, "end speed")
        // his peak speeds per amplitude bin (all moves, p25 / p75)
        val band = listOf(Triple(6.0, 15.0, 61.0 to 100.0), Triple(15.0, 30.0, 116.0 to 201.0), Triple(30.0, 60.0, 223.0 to 384.0),
            Triple(60.0, 100.0, 379.0 to 645.0), Triple(100.0, 181.0, 520.0 to 1075.0))
        for ((lo, hi, user) in band) {
            val peak = q(moves.filter { it.amp >= lo && it.amp < hi }.map { it.peak }, 0.5)
            println("peak p50 $lo-$hi: ${peak.roundToLong()} (his p25-p75 ${user.first.roundToLong()}-${user.second.roundToLong()})")
            assertTrue(peak in user.first * 0.95 .. user.second * 1.05, "peak $lo-$hi $peak")
        }
    }

    @Test
    fun `rest nudges are rare, whole grid steps, and drift back`() {
        val rng = Random(14)
        val rest = RestMotion()
        val p = MotorParams()
        val grid = 0.15
        var yaw = 0.0
        var pitch = 30.0
        val anchor = View(0.0, 30.0)
        var changed = 0
        var follow = 0
        var both = 0
        var prev = false
        var far = 0.0
        val n = 200_000
        repeat(n) {
            val d = rest.tick(yaw, pitch, anchor, grid, p, rng)
            if (d == null) {
                prev = false
                return@repeat
            }
            changed++
            if (prev) follow++
            prev = true
            if (d.yaw != 0.0 && d.pitch != 0.0) both++
            for (x in listOf(d.yaw, d.pitch).filter { it != 0.0 }) {
                val steps = abs(x) / grid - 0.5
                assertEquals(steps.roundToLong().toDouble(), steps, 1e-9)
                assertTrue(steps.roundToLong() in 1 .. p.restSteps.size)
            }
            // what the mouse grid keeps of it
            yaw += d.yaw - d.yaw % grid
            pitch += d.pitch - d.pitch % grid
            far = max(far, hypot(yaw - anchor.yaw, pitch - anchor.pitch))
        }
        val share = changed.toDouble() / n
        println("rest: changed ${pct(share)}, after a changed tick ${pct(follow.toDouble() / changed)}, both axes ${pct(both.toDouble() / changed)}, farthest ${"%.2f".format(far)}°")
        assertTrue(share in 0.015 .. 0.035, "changed share $share")
        assertTrue(follow.toDouble() / changed in 0.25 .. 0.55)
        assertTrue(both.toDouble() / changed in 0.40 .. 0.54)
        assertTrue(far < 4.0, "drifted $far°")
    }

    @Test
    fun `a rest nudge's size is drawn with your hand's shares, the yaw's near the pole within its steps, none piled at an end`() {
        val p = MotorParams(restSteps = listOf(0.5, 0.0, 0.3, 0.2))
        val rng = Random(15)
        val n = 200_000
        val drawn = IntArray(p.restSteps.size + 1)
        repeat(n) { drawn[RestMotion.steps(p, rng)]++ }
        assertEquals(0, drawn[0])
        assertEquals(0, drawn[2], "a size with no share is never drawn")
        for ((i, s) in p.restSteps.withIndex()) assertEquals(s, drawn[i + 1].toDouble() / n, 0.005, "share at ${i + 1} steps: ${drawn.toList()}")
        // the pole's: the same lognormal, kept only where it rounds to 1 .. the largest
        val q = MotorParams()
        val pole = IntArray(q.restPoleMax + 1)
        repeat(n) { pole[RestMotion.poleSteps(q, rng)]++ }
        val kept = IntArray(q.restPoleMax + 1)
        var m = 0
        while (m < n) {
            val x = q.restPoleP50 * exp(q.restPoleSigma * TimingModel.normal(rng))
            if (x < 0.5 || x >= q.restPoleMax + 0.5) continue
            kept[x.roundToInt()]++
            m++
        }
        assertEquals(0, pole[0])
        for (k in listOf(1, 2, 3, 5, 10, 30)) assertEquals(kept[k].toDouble() / n, pole[k].toDouble() / n, 0.006, "share at $k steps")
        // a clamp would gather the whole tail past the largest there
        assertTrue(pole[q.restPoleMax] < pole[q.restPoleMax / 2] + 30, "piled at the largest: ${pole[q.restPoleMax]} against ${pole[q.restPoleMax / 2]}")
    }

    @Test
    fun `near the pole a yaw nudge goes further, a pitch nudge and a level yaw nudge do not`() {
        // a hand of one-step nudges on both axes every other tick, and a pole lognormal far above it
        val p = MotorParams(restStartP = 1.0, restContinueP = 0.0, restSteps = listOf(1.0), restPoleP50 = 20.0, restPoleSigma = 0.1, restBothP = 1.0, restYawP = 0.0)
        val rng = Random(16)
        /** Mean steps of the yaw and the pitch nudges at [pitch]. */
        fun steps(pitch: Double): Pair<Double, Double> {
            val rest = RestMotion()
            var yaw = 0.0
            var up = 0.0
            var n = 0
            repeat(2000) {
                val d = rest.tick(0.0, pitch, null, 0.15, p, rng) ?: return@repeat
                yaw += abs(d.yaw) / 0.15 - 0.5
                up += abs(d.pitch) / 0.15 - 0.5
                n++
            }
            return yaw / n to up / n
        }
        val level = steps(30.0)
        assertEquals(1.0, level.first, 1e-9)
        assertEquals(1.0, level.second, 1e-9)
        for (pitch in listOf(p.restPolePitch, 80.0, - 85.0, 90.0)) {
            val (yaw, up) = steps(pitch)
            assertEquals(20.0, yaw, 1.0, "yaw steps at $pitch")
            assertEquals(1.0, up, 1e-9, "pitch steps at $pitch")
        }
        assertEquals(1.0, steps(p.restPolePitch - 0.5).first, 1e-9, "just below the pole")
    }

    @Test
    fun `a yaw nudge near the pole never turns the view as far as a movement, looking straight down it may go furthest`() {
        // a hand that would move the yaw 100 steps near the pole
        val p = MotorParams(restStartP = 1.0, restContinueP = 0.0, restPoleP50 = 100.0, restPoleSigma = 0.3, restBothP = 0.0, restYawP = 1.0)
        val rng = Random(17)
        /** The yaw steps of the nudges in 6000 still ticks at [pitch]. */
        fun steps(pitch: Double): List<Int> {
            val rest = RestMotion()
            return (1 .. 6000).mapNotNull { rest.tick(0.0, pitch, null, 0.15, p, rng)?.let { d -> (abs(d.yaw) / 0.15 - 0.5).roundToInt() } }
        }
        val at70 = steps(70.0)
        // 2 deg of view at 70 deg is 38.98 grid steps of yaw
        assertTrue(at70.all { it <= 38 && it * 0.15 * cos(Math.toRadians(70.0)) < p.restNudgeMaxDeg }, "largest ${at70.maxOrNull()}")
        assertTrue(at70.count { it == 38 } < at70.count { it in 35 .. 37 }, "drawn under the bound, not piled on it")
        val down = steps(90.0)
        assertTrue(down.any { it > 38 } && down.all { it <= p.restPoleMax }, "straight down: up to ${p.restPoleMax}, largest ${down.maxOrNull()}")
    }

    private fun grid(x: Double) = Math.round(x / 0.15) * 0.15

    private fun law(a: Double) = 34 + 111 * log2(1 + a / 6)

    private fun pct(x: Double) = "%.1f%%".format(x * 100)

    private fun q(xs: List<Double>, p: Double): Double {
        if (xs.isEmpty()) return Double.NaN
        val s = xs.sorted()
        val i = (s.size - 1) * p
        val lo = i.toInt()
        val hi = min(lo + 1, s.size - 1)
        return s[lo] + (s[hi] - s[lo]) * (i - lo)
    }

    private class Rot(val t: Double, val yaw: Double, val pitch: Double)

    private class Move(
        val dur: Double, val amp: Double, val peak: Double, val tpk: Double, val over: Double, val nsub: Int,
        val halfLag: Double, val v1: Double, val dyaw: Double, val dpitch: Double, val straight: Double, val cut: Boolean,
    )

    /** `humanize/segment.js`: a movement runs while the view turns faster than 15 deg/s and ends after 2 still ticks. */
    private fun segment(run: List<Rot>): List<Move> {
        val n = run.size
        val s = DoubleArray(n)
        val v = DoubleArray(n)
        val dy = DoubleArray(n)
        val dp = DoubleArray(n)
        for (i in 1 until n) {
            val a = run[i - 1]
            val b = run[i]
            s[i] = MotorModel.angle(a.yaw, a.pitch, b.yaw, b.pitch)
            v[i] = s[i] / (b.t - a.t) * 1000
            dy[i] = MotorModel.wrap(b.yaw - a.yaw)
            dp[i] = b.pitch - a.pitch
        }
        val moves = ArrayList<Move>()
        var i = 1
        while (i < n) {
            if (v[i] <= 15.0) {
                i++
                continue
            }
            var last = i
            var still = 0
            var j = i + 1
            while (j < n) {
                if (v[j] > 15.0) {
                    last = j
                    still = 0
                }
                else {
                    still++
                    if (still >= 2) break
                }
                j++
            }
            moves += describe(run, v, dy, dp, i, last, j >= n && still < 2)
            i = last + 1
        }
        return moves
    }

    private fun dir(yaw: Double, pitch: Double): DoubleArray {
        val y = Math.toRadians(yaw)
        val p = Math.toRadians(pitch)
        return doubleArrayOf(- sin(y) * cos(p), - sin(p), cos(y) * cos(p))
    }

    private fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    private fun describe(run: List<Rot>, v: DoubleArray, dy: DoubleArray, dp: DoubleArray, first: Int, last: Int, cut: Boolean): Move {
        val a = run[first - 1]
        val b = run[last]
        val dur = b.t - a.t
        val amp = MotorModel.angle(a.yaw, a.pitch, b.yaw, b.pitch)
        var peak = 0.0
        var pk = first
        for (k in first..last) if (v[k] > peak) {
            peak = v[k]
            pk = k
        }
        val tpk = ((run[pk - 1].t + run[pk].t) / 2 - a.t) / dur
        var maxProg = 1.0
        if (amp > 0.5) {
            val u = dir(a.yaw, a.pitch)
            val w = dir(b.yaw, b.pitch)
            val c = dot(u, w)
            val arc = acos(c.coerceIn(- 1.0, 1.0))
            val along = DoubleArray(3) { (w[it] - u[it] * c) / sin(arc) }
            for (k in first..last) {
                val d = dir(run[k].yaw, run[k].pitch)
                maxProg = max(maxProg, atan2(dot(d, along), dot(d, u)) / arc)
            }
        }
        val vs = (first..last).map { v[it] }
        val peaks = vs.indices.filter { k -> vs[k] > (if (k > 0) vs[k - 1] else 0.0) && vs[k] >= (if (k < vs.size - 1) vs[k + 1] else 0.0) }
        var nsub = if (peaks.isEmpty()) 0 else 1
        for (k in 1 until peaks.size) {
            val low = (peaks[k - 1]..peaks[k]).minOf { vs[it] }
            if (low < 0.7 * min(vs[peaks[k - 1]], vs[peaks[k]])) nsub++
        }
        var cy = 0.0
        var cp = 0.0
        var path = 0.0
        val cys = arrayListOf(0.0)
        val cps = arrayListOf(0.0)
        val ts = arrayListOf(a.t)
        for (k in first..last) {
            cy += dy[k]
            cp += dp[k]
            path += hypot(dy[k], dp[k])
            cys += cy
            cps += cp
            ts += run[k].t
        }
        fun half(arr: List<Double>, tot: Double): Double {
            for (k in 1 until arr.size) if (abs(arr[k]) >= abs(tot) / 2) {
                val den = abs(arr[k]) - abs(arr[k - 1])
                val f = (abs(tot) / 2 - abs(arr[k - 1])) / (if (den == 0.0) 1.0 else den)
                return ts[k - 1] + f * (ts[k] - ts[k - 1])
            }
            return Double.NaN
        }
        val yawDeg = cy * cos(Math.toRadians((a.pitch + b.pitch) / 2))
        val halfLag = if (abs(yawDeg) > 5 && abs(cp) > 5) half(cps, cp) - half(cys, cy) else Double.NaN
        return Move(dur, amp, peak, tpk, (maxProg - 1) * amp, nsub, halfLag, v[last] / peak, cy, cp, if (path > 0) hypot(cy, cp) / path else 1.0, cut)
    }
}
