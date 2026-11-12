package fishing

import com.google.gson.JsonArray
import com.google.gson.JsonParser
import com.laybalt.features.impl.fishing.BehaviorProfile
import com.laybalt.features.impl.fishing.GlanceMeasure
import com.laybalt.features.impl.fishing.HumanAim
import com.laybalt.features.impl.fishing.HumanAim.Owner
import com.laybalt.features.impl.fishing.MotorModel
import com.laybalt.features.impl.fishing.MotorModel.View
import com.laybalt.features.impl.fishing.MotorParams
import com.laybalt.features.impl.fishing.TimingModel
import com.laybalt.features.impl.fishing.ViewMotion
import com.laybalt.features.impl.fishing.ViewPolicy
import com.laybalt.features.impl.fishing.ViewPolicy.Decision
import com.laybalt.features.impl.fishing.ViewPolicy.Need
import com.laybalt.features.impl.fishing.ViewPolicy.TurnBack
import com.laybalt.features.impl.fishing.WeaponPlanner
import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.*

/** When the killer turns the view (DESIGN-HUMAN §2): the first turn's latency, tolerances, corrections without pursuit, the turn back. */
class ViewPolicyTest {
    private val aim = View(90.0, 10.0)
    private val view = View(0.0, 10.0)

    /** A creature 90° off a still view, for [need] with [tolerance]. */
    private fun s(
        now: Long, need: Need = Need.RANGED, error: Double = 90.0, tolerance: Double = 30.0, moving: Boolean = false, onTarget: Boolean = false,
        target: Int = 1, anchorAt: Long = 0L, swapped: Boolean = false, falls: Boolean = false, strokes: Int = 0, accept: Double = 10.0,
        notBefore: Long = Long.MIN_VALUE, braking: Boolean = false, restedAt: Long = Long.MIN_VALUE, others: Boolean = false,
    ) = ViewPolicy.Situation(now, target, need, view, aim, error, tolerance, onTarget, moving, strokes, anchorAt, swapped, falls, 18_000.0, { accept },
        notBefore = notBefore, braking = braking, restedAt = restedAt, viewMoving = moving || braking || others)

    @Test
    fun `the view is free for a look around until the first turn's moment, never after it`() {
        val policy = ViewPolicy(Random(1))
        assertEquals(Double.POSITIVE_INFINITY, policy.freeForMs(0L), "no target")
        policy.update(s(0L, need = Need.NONE))
        assertEquals(Double.POSITIVE_INFINITY, policy.freeForMs(0L), "a plan that never turns")
        policy.reset()
        policy.update(s(0L, target = 2))
        val first = policy.freeForMs(0L)
        assertTrue(first > 0.0 && first.isFinite(), "the first turn waits for your reaction: $first")
        assertEquals(maxOf(0.0, first - 100.0), policy.freeForMs(100L), 1e-9)
        val turn = firstStart(policy, 50L, 60_000L) { s(it, target = 2) }
        assertNotNull(turn)
        assertEquals(0.0, policy.freeForMs(turn), "the turn is the plan's")
    }

    /** Feeds [policy] still ticks from [from] until it starts a move; the time it did, or null by [until]. */
    private fun firstStart(policy: ViewPolicy, from: Long, until: Long, make: (Long) -> ViewPolicy.Situation): Long? {
        var t = from
        while (t <= until) {
            if (policy.update(make(t)) == Decision.START) return t
            t += 50
        }
        return null
    }

    @Test
    fun `the first turn waits for your reaction, about 3,5 s after a spawn, about 330 ms after a swap`() {
        val spawn = List(400) { seed -> firstStart(ViewPolicy(Random(seed)), 0, 30_000) { s(it) } ?: 30_000L }.sorted()
        assertTrue(spawn[200] in 2500L .. 4800L, "spawn p50 ${spawn[200]}")
        assertTrue(spawn.all { it <= 18_050L }, "never later than Give Up After allows")
        assertTrue(spawn[40] >= 500L, "spawn p10 ${spawn[40]}: never at once")

        val swap = List(400) { seed -> firstStart(ViewPolicy(Random(seed)), 1000, 30_000) { s(it, anchorAt = 1000, swapped = true) }!! - 1000 }.sorted()
        assertTrue(swap[200] in 150L .. 600L, "swap p50 ${swap[200]}")
        // 15 % in the swap's own tick or right after it, and the fast part of the lognormal (yours: p10 -50 ms)
        assertTrue(swap.count { it <= 50 } in 50 .. 150, "${swap.count { it <= 50 }} right away")
    }

    @Test
    fun `after the loadout menu the first turn comes at the moment the fight timed it, never on the swap's latency`() {
        // the swap before /loadout at 0, the menu closed at 2500, the turn timed for 2700: then, whatever the swap's latency
        // (yours all came within 284 ms of the close; on the latency from the swap 11 % came after the close and later)
        val held = List(400) { seed -> firstStart(ViewPolicy(Random(seed)), 2500, 30_000) { s(it, anchorAt = 0L, swapped = true, notBefore = 2700L) }!! }
        assertTrue(held.all { it == 2700L }, "${held.groupingBy { it }.eachCount()}")
        // a moment already past (a big turn timed before the close): the first tick after the close
        val past = List(400) { seed -> firstStart(ViewPolicy(Random(seed)), 2500, 30_000) { s(it, anchorAt = 0L, swapped = true, notBefore = 2400L) }!! }
        assertTrue(past.all { it == 2500L }, "${past.groupingBy { it }.eachCount()}")
        // none set: the swap's latency, as before
        val free = List(400) { seed -> firstStart(ViewPolicy(Random(seed)), 2500, 30_000) { s(it, anchorAt = 0L, swapped = true) }!! }
        assertTrue(free.count { it > 2500L } > 10, "${free.count { it > 2500L }} on the latency")
        // only the target's first moment (here found within the tolerance): a later re-anchor (a swap for another plan at
        // 4000) waits its own latency
        val again = List(400) { seed ->
            val q = ViewPolicy(Random(seed))
            q.update(s(2500, error = 20.0, anchorAt = 0L, swapped = true, notBefore = 2400L))
            firstStart(q, 4000, 30_000) { s(it, anchorAt = 4000L, swapped = true, notBefore = 2400L) }!!
        }
        assertTrue(again.count { it > 4100L } > 100, "${again.count { it > 4100L }} of 400 wait their latency from the new swap")
        // a look around keeps to it
        val l = ViewPolicy(Random(1))
        l.update(s(2500, anchorAt = 0L, swapped = true, notBefore = 2700L))
        assertEquals(200.0, l.freeForMs(2500))
    }

    @Test
    fun `no turn when the creature is within the tolerance at the turn's moment, falls into the crosshair, or the plan needs none`() {
        val p = ViewPolicy(Random(1))
        assertNull(firstStart(p, 0, 60_000) { s(it, error = 20.0, tolerance = 30.0) }, "within the plan's aim")
        val falling = ViewPolicy(Random(1))
        assertNull(firstStart(falling, 0, 20_000) { s(it, need = Need.MELEE, tolerance = 3.0, falls = true) })
        val area = ViewPolicy(Random(1))
        assertNull(firstStart(area, 0, 60_000) { s(it, need = Need.NONE) })
        assertEquals(Decision.STOP, area.update(s(60_050, need = Need.NONE, moving = true)), "a move left from another plan comes to rest")
    }

    @Test
    fun `a ranged move stops short of the creature by its acceptance, a melee move aims at it`() {
        val p = ViewPolicy(Random(2))
        val t = firstStart(p, 0, 60_000) { s(it, accept = 15.0) }!!
        val g = p.goal!!
        assertEquals(15.0, MotorModel.angle(g.yaw, g.pitch, aim.yaw, aim.pitch), 0.5, "15 degrees short")
        assertEquals(15.0, p.acceptance)
        // in flight it follows the creature stroke by stroke, and ends once within its acceptance
        assertEquals(Decision.RETARGET, p.update(s(t + 50, moving = true, error = 60.0)))
        assertEquals(Decision.END, p.update(s(t + 100, moving = true, error = 14.0)))
        assertEquals(Decision.NONE, p.update(s(t + 150, moving = true, error = 13.0)), "ended once")

        val m = ViewPolicy(Random(2))
        firstStart(m, 0, 60_000) { s(it, need = Need.MELEE, tolerance = 3.0) }!!
        assertEquals(aim, m.goal)
    }

    @Test
    fun `a melee move ends once the crosshair is on the creature, or after 3 strokes`() {
        val p = ViewPolicy(Random(3))
        val t = firstStart(p, 0, 60_000) { s(it, need = Need.MELEE, tolerance = 3.0) }!!
        assertEquals(Decision.RETARGET, p.update(s(t + 50, need = Need.MELEE, tolerance = 3.0, moving = true, error = 40.0, strokes = 1)))
        assertEquals(Decision.END, p.update(s(t + 100, need = Need.MELEE, tolerance = 3.0, moving = true, error = 2.0, onTarget = true, strokes = 1)))

        val q = ViewPolicy(Random(3))
        val u = firstStart(q, 0, 60_000) { s(it, need = Need.MELEE, tolerance = 3.0) }!!
        assertEquals(Decision.END, q.update(s(u + 50, need = Need.MELEE, tolerance = 3.0, moving = true, error = 20.0, strokes = 3)))
    }

    @Test
    fun `no new move within the reaction gap after a move ends with the crosshair off`() {
        repeat(50) { seed ->
            val p = ViewPolicy(Random(seed))
            val t = firstStart(p, 0, 60_000) { s(it, need = Need.MELEE, tolerance = 3.0) }!!
            p.update(s(t + 50, need = Need.MELEE, tolerance = 3.0, moving = true, error = 30.0))
            // the move ended 20 degrees off the creature (the hand's spread): a correction, but only after a reaction
            val end = t + 400
            val again = firstStart(p, end, end + 20_000) { s(it, need = Need.MELEE, tolerance = 3.0, error = 20.0) }!!
            assertTrue(again - end >= 100, "seed $seed: ${again - end} ms after the end")
        }
        val gaps = List(300) { seed ->
            val p = ViewPolicy(Random(seed))
            val t = firstStart(p, 0, 60_000) { s(it, need = Need.MELEE, tolerance = 3.0) }!!
            p.update(s(t + 50, need = Need.MELEE, tolerance = 3.0, moving = true, error = 30.0))
            firstStart(p, t + 400, t + 20_000) { s(it, need = Need.MELEE, tolerance = 3.0, error = 20.0) }!! - (t + 400)
        }.sorted()
        assertTrue(gaps[150] in 150L .. 400L, "reaction p50 ${gaps[150]}")
    }

    @Test
    fun `no pursuit, after a move that got there the view stays still until the creature is off by the slack as well`() {
        val starts = ArrayList<Double>()
        repeat(200) { seed ->
            val p = ViewPolicy(Random(seed))
            val t = firstStart(p, 0, 60_000) { s(it, tolerance = 30.0) }!!
            p.update(s(t + 50, moving = true, error = 40.0))
            // the move ended within the plan's aim; the creature then drifts off one degree a tick
            var err = 25.0
            var now = t + 400
            var started: Double? = null
            while (now < t + 60_000 && started == null) {
                if (p.update(s(now, error = err, tolerance = 30.0)) == Decision.START) started = err
                err += 1.0
                now += 50
            }
            starts += started ?: 999.0
        }
        starts.sort()
        assertTrue(starts.all { it > 30.0 }, "never within the tolerance")
        // the slack (p50 15 degrees) plus the reaction's drift
        assertTrue(starts[100] in 40.0 .. 60.0, "corrections start at p50 ${starts[100]}")
        assertTrue(starts[20] > 33.0, "p10 ${starts[20]}")
    }

    @Test
    fun `a move towards another creature comes to rest when the target changes`() {
        val p = ViewPolicy(Random(4))
        val t = firstStart(p, 0, 60_000) { s(it) }!!
        assertEquals(Decision.STOP, p.update(s(t + 50, moving = true, target = 2, anchorAt = t)))
        // the new creature gets its own first turn
        assertEquals(Decision.NONE, p.update(s(t + 100, target = 2, anchorAt = t)))
    }

    @Test
    fun `a turn towards the next creature starts one of your gaps after the move before came to rest, never while it brakes`() {
        val waits = List(300) { seed ->
            val p = ViewPolicy(Random(seed))
            val t = firstStart(p, 0, 60_000) { s(it) }!!
            // the target changes while the move turns: it is stopped and brakes for two ticks; the next creature's first
            // turn was timed long before (a group target, its moment past)
            assertEquals(Decision.STOP, p.update(s(t + 50, moving = true, target = 2, notBefore = 0L)))
            assertEquals(Decision.NONE, p.update(s(t + 100, target = 2, notBefore = 0L, braking = true)), "seed $seed: braking")
            assertEquals(Decision.NONE, p.update(s(t + 150, target = 2, notBefore = 0L, braking = true)))
            firstStart(p, t + 200, t + 10_000) { s(it, target = 2, notBefore = 0L) }!! - (t + 200)
        }.sorted()
        // it started in the tick after the stop before: now a gap of yours after the rest (none under 95 ms, p50 200)
        assertTrue(waits.first() >= 100, "${waits.first()}")
        assertTrue(waits[150] in 150L .. 300L, "p50 ${waits[150]}")
        assertTrue(waits.groupingBy { it }.eachCount().values.max() < 300 * 0.4, "${waits.groupingBy { it }.eachCount()}")
        // a creature leaving the plan's aim while the view still brakes: corrected only after the rest and a gap as well
        val p = ViewPolicy(Random(3))
        val t = firstStart(p, 0, 60_000) { s(it, need = Need.MELEE, tolerance = 3.0) }!!
        p.update(s(t + 50, need = Need.MELEE, tolerance = 3.0, moving = true, error = 30.0))
        for (k in 1 .. 20) assertEquals(Decision.NONE, p.update(s(t + 50 + k * 50L, need = Need.MELEE, tolerance = 3.0, error = 20.0, braking = true)), "braking, tick $k")
        val rest = t + 1100
        val again = firstStart(p, rest, rest + 20_000) { s(it, need = Need.MELEE, tolerance = 3.0, error = 20.0) }!!
        assertTrue(again - rest >= 100, "${again - rest} ms after the rest")
    }

    @Test
    fun `the first turn keeps its latency, or after a move past the swap or its moment comes your gap after that rest, never both`() {
        fun start(seed: Int, restedAt: Long, busyUntil: Long = Long.MIN_VALUE) =
            firstStart(ViewPolicy(Random(seed)), 1000, 30_000) { s(it, anchorAt = 1000, swapped = true, restedAt = restedAt, others = it < busyUntil) }!!
        // the view at rest since before the swap: on the latency from the swap alone, as with no move before it at all (a
        // gap of yours after that rest was stacked on a latency measured from your moves: p50 353 -> 453 ms, yours 301)
        val free = List(400) { start(it, Long.MIN_VALUE / 2) }
        assertEquals(free, List(400) { start(it, 500) })
        // the move before still braked at the pick and came to rest 20 ms later (the driver's own time, between ticks):
        // one of your gaps between moves after that rest, whatever the latency (yours after a move that went on past the
        // swap: 99-399 ms after its rest, 8 of your 20), timed from the rest itself, not from the update that saw it
        val rested = List(400) { start(it, 1020, busyUntil = 1050) }
        assertTrue(rested.all { it >= 1020 + ViewPolicy.MIN_GAP_MS }, "${rested.min()}")
        val sorted = rested.map { it - 1020 }.sorted()
        assertTrue(sorted[200] in 180L .. 300L, "p50 ${sorted[200]} after the rest")
        assertTrue(rested.groupingBy { it }.eachCount().values.max() < 400 * 0.3, "${rested.groupingBy { it }.eachCount()}")
        // a rest right at the swap: a latency that would start sooner than 95 ms after it is drawn again, none piled there
        val atSwap = List(2000) { start(it, 1000) }
        assertTrue(atSwap.all { it >= 1095 }, "${atSwap.min()}")
        assertTrue(atSwap.count { it == 1100L } < 2000 * 0.1, "${atSwap.count { it == 1100L }} in the first tick allowed")
        // any move of the view, whoever's (a look around, Auto Fishing's), holds the turn; one still in flight when the
        // moment came is followed one of your gaps between moves after its rest (yours: 99-398 ms)
        val after = List(400) { seed ->
            val t = start(seed, Long.MIN_VALUE, busyUntil = 9000)
            assertTrue(t >= 9000, "seed $seed: $t while another move turned the view")
            t - 9000
        }.sorted()
        assertTrue(after.first() >= 100, "${after.first()}")
        assertTrue(after[200] in 150L .. 300L, "p50 ${after[200]}")
        // a spawn's first turn (your latency p50 3.5 s) whose moment came a tick after a look around came back: one of your
        // gaps after that rest, never a latency drawn again from its far tail (seconds later)
        val spawnTurns = List(400) { seed ->
            val free = firstStart(ViewPolicy(Random(seed)), 0, 30_000) { s(it) }!!
            val rest = free - 50
            firstStart(ViewPolicy(Random(seed)), 0, 30_000) { s(it, restedAt = if (it >= rest) rest else Long.MIN_VALUE / 2) }!! - rest
        }.sorted()
        assertTrue(spawnTurns.first() >= 100, "${spawnTurns.first()}")
        assertTrue(spawnTurns[200] in 150L .. 300L, "p50 ${spawnTurns[200]}")
        assertTrue(spawnTurns[380] < 800, "p95 ${spawnTurns[380]}")
    }

    @Test
    fun `a switch between a look-down and a creature aim retargets the move in flight`() {
        val p = ViewPolicy(Random(5))
        val t = firstStart(p, 0, 60_000) { s(it, need = Need.PITCH, error = 60.0, tolerance = 5.0) }!!
        assertEquals(Decision.RETARGET, p.update(s(t + 50, need = Need.PITCH, error = 40.0, tolerance = 5.0, moving = true)))
        assertFalse(p.force)
        assertEquals(Decision.RETARGET, p.update(s(t + 100, need = Need.RANGED, moving = true)))
        assertTrue(p.force)
    }

    @Test
    fun `the policy waits only while the view is off and a turn is still to come`() {
        val p = ViewPolicy(Random(6))
        p.update(s(0, need = Need.MELEE, tolerance = 3.0))
        assertTrue(p.waiting, "the first turn's reaction")
        p.update(s(50, need = Need.MELEE, tolerance = 3.0, onTarget = true, error = 1.0))
        assertFalse(p.waiting, "on the creature nothing is pending")
    }

    @Test
    fun `a melee move comes to rest on a point of the box drawn per move, kept while it follows the creature`() {
        // the box seen from the eye: a point's view moves 4 degrees per half-width sideways, 8 per height
        val land = { l: ViewPolicy.Landing -> View(aim.yaw + 4.0 * l.side, aim.pitch - 8.0 * (l.up - 0.5)) }
        val drawn = ArrayDeque(listOf(ViewPolicy.Landing(0.6, 0.3), ViewPolicy.Landing(- 0.2, 0.8)))
        var draws = 0
        fun m(now: Long, moving: Boolean = false, error: Double = 90.0, aimYaw: Double = aim.yaw) = ViewPolicy.Situation(
            now, 1, Need.MELEE, view, View(aimYaw, aim.pitch), error, 3.0, false, moving, 1, 0L, false, false, 18_000.0, { 0.0 },
            land = { l -> land(l).let { View(it.yaw + aimYaw - aim.yaw, it.pitch) } }, landing = { draws++; drawn.removeFirst() },
        )
        val p = ViewPolicy(Random(8))
        val t = firstStart(p, 0, 60_000) { m(it) }!!
        assertView(View(92.4, 11.6), p.goal, "0.6 of the half-width beside the line, at 0.3 of the height")
        // the creature moved 10 degrees: the move follows it to the same point of its box, nothing drawn again
        assertEquals(Decision.RETARGET, p.update(m(t + 50, moving = true, aimYaw = 100.0)))
        assertView(View(102.4, 11.6), p.goal)
        assertEquals(1, draws)
        // the move ended off the creature: the correction comes to rest on a point of its own
        p.update(m(t + 400, error = 20.0))
        val again = firstStart(p, t + 450, t + 20_000) { m(it, error = 20.0) }!!
        assertTrue(again > t)
        assertView(View(89.2, 7.6), p.goal)
        assertEquals(2, draws)
        // without a point of the box: the aim point, as before
        val q = ViewPolicy(Random(8))
        firstStart(q, 0, 60_000) { s(it, need = Need.MELEE, tolerance = 3.0) }!!
        assertEquals(aim, q.goal)
    }

    @Test
    fun `your melee turns come to rest beside the box's centre line, never piled on it`() {
        val rng = Random(9)
        val sides = List(40_000) { ViewPolicy.landingSide(rng) }
        val a = sides.map { abs(it) }.sorted()
        // yours: p10 0.12, p50 0.46, p90 0.74 of the half-width, none within 0.05 of the line, either side alike
        assertEquals(0.12, a[4_000], 0.02)
        assertEquals(0.46, a[20_000], 0.02)
        assertEquals(0.74, a[36_000], 0.02)
        assertTrue(a.last() <= ViewPolicy.SIDE_MAX, "inside the box")
        // none within 0.05 of the line, as none of your 25 were: the lowest decile spreads from your closest (0.079) to
        // p10, never piled at its lower end
        assertEquals(0, a.count { it < 0.05 }, "on the line")
        assertTrue(a.first() >= ViewPolicy.SIDE_MIN)
        assertTrue(a.count { it < ViewPolicy.SIDE_MIN + 0.004 } in 200 .. 1_000, "${a.count { it < ViewPolicy.SIDE_MIN + 0.004 }} at the lowest knot")
        assertEquals(0.5, sides.count { it > 0 } / 40_000.0, 0.02)
        assertTrue(sides.groupingBy { it }.eachCount().values.max() < 5, "a pile")
    }

    @Test
    fun `a look-down comes to rest at a pitch drawn per move, kept while it goes on`() {
        val pitches = ArrayDeque(listOf(87.45, 90.0))
        fun d(now: Long, target: Int = 1, moving: Boolean = false, error: Double = 60.0, anchorAt: Long = 0L) = ViewPolicy.Situation(
            now, target, Need.PITCH, view, View(view.yaw, 89.7), error, 5.0, false, moving, 1, anchorAt, false, false, 18_000.0, { 0.0 },
            lookDown = { pitches.removeFirst() },
        )
        val p = ViewPolicy(Random(10))
        val t = firstStart(p, 0, 60_000) { d(it) }!!
        assertView(View(view.yaw, 87.45), p.goal)
        assertEquals(Decision.RETARGET, p.update(d(t + 50, moving = true, error = 40.0)))
        assertView(View(view.yaw, 87.45), p.goal, "the same pitch all the way")
        // the next target's look-down draws its own
        val q = firstStart(p, t + 100, t + 60_000) { d(it, target = 2, anchorAt = t + 100) }!!
        assertTrue(q > t)
        assertView(View(view.yaw, 90.0), p.goal)
        // without a draw: the plan's pitch
        val r = ViewPolicy(Random(10))
        firstStart(r, 0, 60_000) { s(it, need = Need.PITCH, error = 60.0, tolerance = 5.0) }!!
        assertEquals(aim, r.goal)
    }

    private fun assertView(expected: View, actual: View?, message: String? = null) {
        assertNotNull(actual, message)
        assertEquals(expected.yaw, actual.yaw, 1e-9, message)
        assertEquals(expected.pitch, actual.pitch, 1e-9, message)
    }

    @Test
    fun `a learned weapon's move stops at one of your aim errors, else around 15 degrees below its aim`() {
        val errs = listOf(2f, 3f, 4f, 5f, 6f, 8f, 10f, 12f, 20f, 40f)
        val rng = Random(7)
        val learned = List(2000) { ViewPolicy.rangedAcceptance(errs, 32.0, rng) }
        // at or below the p75 (10 degrees), jittered by 10 %
        assertTrue(learned.all { it < 10.0 * 1.5 })
        assertTrue(learned.any { it < 2.5 })
        // never beyond the plan's aim, where the click would be held back and the move left to a no-op restart: a
        // jitter past it is drawn again, not clamped onto it
        for (aimDeg in listOf(12.0, 10.0, 9.0)) {
            val tight = List(4000) { ViewPolicy.rangedAcceptance(errs, aimDeg, rng) }
            assertTrue(tight.all { it <= aimDeg }, "aim $aimDeg: ${tight.max()}")
            if (aimDeg >= 10.0) assertTrue(tight.count { it == aimDeg } < 40, "aim $aimDeg: ${tight.count { it == aimDeg }} piled at it")
        }
        val fallback = List(2000) { ViewPolicy.rangedAcceptance(emptyList(), 32.0, rng) }
        assertTrue(fallback.all { it <= 0.9 * 32.0 + 1e-9 })
        assertTrue(fallback.sorted()[1000] in 9.0 .. 17.0, "p50 ${fallback.sorted()[1000]}")
        // a tight aim: drawn below 0.9 of it, never piled at half of it (a fallback after 8 draws past it put 26 % there)
        val tight = List(4000) { ViewPolicy.rangedAcceptance(emptyList(), 6.0, rng) }
        assertTrue(tight.all { it > 0.0 && it < 0.9 * 6.0 })
        assertTrue(tight.groupingBy { it }.eachCount().values.max() < 5, "a pile")
        assertTrue(tight.count { it > 4.0 } > 800, "${tight.count { it > 4.0 }} near the bound: the lognormal's own shape below it")
        // aim errors of yours all beyond a plan cached from an earlier list: the default, never the plan's aim itself
        val past = List(4000) { ViewPolicy.rangedAcceptance(List(10) { 20f + it }, 12.0, rng) }
        assertTrue(past.all { it < 0.9 * 12.0 })
        assertTrue(past.groupingBy { it }.eachCount().values.max() < 5, "a pile")
    }

    @Test
    fun `turn back is none within 8 degrees, else most of the time after your latency, otherwise late or not at all`() {
        val learned = ViewPolicy.Learned()
        assertEquals(TurnBack.Kind.NONE, ViewPolicy.turnBack(0, 7.9, false, learned, Random(1)).kind)
        val plans = List(2000) { seed -> ViewPolicy.turnBack(1000, 45.0, inFluid = false, learned = learned, rng = Random(seed)) }
        val now = plans.filter { it.kind == TurnBack.Kind.NOW }
        assertTrue(now.size in 1470 .. 1650, "${now.size} turn back right away (78 %)")
        assertTrue(plans.filter { it.kind == TurnBack.Kind.LATE }.size == 2000 - now.size, "the rest late: the view does not cast into water")
        // your latency with nothing of your hand going on past the death: p50 275 (12 of your 38)
        val latency = now.map { it.at - 1000 }.sorted()
        assertTrue(latency[latency.size / 2] in 230L .. 380L, "latency p50 ${latency[latency.size / 2]}")
        val short = now.map { it.shortBy }.sorted()
        assertTrue(short[short.size / 2] in 4.0 .. 6.0, "undershoot p50 ${short[short.size / 2]}")
        val late = plans.filter { it.kind == TurnBack.Kind.LATE }.map { it.at - 1000 }.sorted()
        assertTrue(late[late.size / 2] in 6000L .. 12_000L, "late p50 ${late[late.size / 2]}")
        // Auto Fishing waits for it: never a minute with the view parked on the fight
        assertTrue(late.last() <= 20_000L, "late at most 20 s, was ${late.last()}")
        // looking at the water still: no late one
        val wet = List(2000) { seed -> ViewPolicy.turnBack(1000, 45.0, inFluid = true, learned = learned, rng = Random(seed)) }
        assertTrue(wet.none { it.kind == TurnBack.Kind.LATE })
    }

    @Test
    fun `a learned turn back rate needs 8 fights the next one did not cut short`() {
        val policy = BehaviorProfile.Policy(backBeyond = 12, backTurned = 6, backInterrupted = 5, backRate = 6.0 / 7)
        assertEquals(ViewPolicy.BACK_RATE, ViewPolicy.Learned.of(policy, null).backRate, "7 to learn from: the default")
        policy.backBeyond = 13
        policy.backRate = 6.0 / 8
        assertEquals(0.75, ViewPolicy.Learned.of(policy, null).backRate, 1e-9)
    }

    @Test
    fun `a turn back with 22 degrees of rest error happens once, with no loop, and is over when its move ends`() {
        val b = TurnBack(TurnBack.Kind.NOW, 500, 22.0)
        assertEquals(Decision.NONE, b.update(400, moving = false))
        assertEquals(Decision.START, b.update(500, moving = false))
        b.begun(500, 400.0)
        assertEquals(Decision.NONE, b.update(550, moving = true))
        assertFalse(b.done)
        // the move ended 22 degrees short: over, never started again for that error
        assertEquals(Decision.NONE, b.update(950, moving = false))
        assertTrue(b.done)
        repeat(20) { assertEquals(Decision.NONE, b.update(1000L + it * 50, moving = false)) }

        // a screen stopped it: it starts again once
        val c = TurnBack(TurnBack.Kind.NOW, 0, 5.0)
        assertEquals(Decision.START, c.update(0, false))
        c.aborted()
        assertEquals(Decision.START, c.update(50, false))
        // and it is given up a second after its planned end
        c.begun(50, 300.0)
        c.update(100, moving = true)
        c.update(1400, moving = true)
        assertTrue(c.done)
        assertTrue(TurnBack(TurnBack.Kind.NONE, 0, 0.0).done)
    }

    @Test
    fun `a turn back due while the fight's move still coasts starts only after it came to rest and your wait after a rest`() {
        var waits = 0
        val b = TurnBack(TurnBack.Kind.NOW, 500, 5.0, afterMove = { waits++; 180L }, from = 400, latency = { error("timed from the rest") })
        assertEquals(Decision.NONE, b.update(450, moving = true))
        assertEquals(Decision.NONE, b.update(500, moving = true), "due, but the move stopped at the death still turns the view")
        assertEquals(Decision.NONE, b.update(600, moving = true))
        assertEquals(Decision.NONE, b.update(650, moving = false), "at rest since 650")
        assertEquals(Decision.NONE, b.update(800, moving = false))
        assertEquals(Decision.START, b.update(650 + 180, moving = false))
        assertTrue(b.started)
        assertEquals(1, waits, "one wait per rest")

        // a view at rest all along: due on time, nothing drawn
        val c = TurnBack(TurnBack.Kind.NOW, 500, 5.0, afterMove = { error("no move before it") })
        assertEquals(Decision.NONE, c.update(450, moving = false))
        assertEquals(Decision.START, c.update(500, moving = false))
    }

    @Test
    fun `a turn back after a move that went on past the death comes your wait after its rest, the latency not on top`() {
        // yours after a move still turning at the death: 147-1501 ms after its rest (12, p50 about 500), none under 95
        val rng = Random(31)
        val waits = List(40_000) { TimingModel.draw(TimingModel.Action.TURN_BACK_AFTER_MOVE, emptyList(), null, rng) }.sorted()
        assertTrue(waits.first() >= 95, "${waits.first()}")
        assertEquals(470.0, waits[20_000].toDouble(), 40.0)
        assertTrue(waits[4_000] in 150L .. 260L, "p10 ${waits[4_000]}")
        assertTrue(waits.last() <= 3000L)
        // the rest is the driver's own (a move that ended in a frame between ticks), not the tick that found it: the wait
        // runs from it, whatever the latency from the death would have been
        val b = TurnBack(TurnBack.Kind.NOW, 1300, 5.0, afterMove = { 400L }, from = 1000, latency = { error("timed from the rest") })
        assertEquals(Decision.NONE, b.update(1050, moving = true, restedAt = 900))
        assertEquals(Decision.NONE, b.update(1150, moving = false, restedAt = 1120))
        assertEquals(1520L, b.at, "the rest at 1120 plus the wait, not the latency's 1300 nor a max of both")
        assertEquals(Decision.START, b.update(1550, moving = false, restedAt = 1120))
        // the plans the killer makes: the start after the move stopped at the death came to rest spreads like your waits
        // (it was the later of your whole latency from the death and a gap after the rest: your latency counted twice)
        val starts = List(2000) { seed ->
            val p = ViewPolicy.turnBack(0, 45.0, false, ViewPolicy.Learned(backRate = 1.0), Random(seed))
            var t = 0L
            while (t < 150) {
                p.update(t, moving = true)
                t += 50
            }
            val rest = t
            while (p.update(t, moving = false) != Decision.START) t += 50
            t - rest
        }.sorted()
        assertTrue(starts.all { it >= 100 }, "${starts.min()}")
        assertTrue(starts.groupingBy { it }.eachCount().values.max() < 2000 * 0.2, "${starts.groupingBy { it }.eachCount()}")
        assertEquals(475.0, starts[1000].toDouble(), 60.0, "p50 from the rest")
    }

    @Test
    fun `a turn back after a held key comes your wait after the key-up, a third of them in its very tick, never while it is held`() {
        // yours: of 11 turn backs that did not start before the key-up, 4 in its very tick, the rest 48-1199 ms after it
        val rng = Random(32)
        val waits = List(40_000) { TimingModel.draw(TimingModel.Action.TURN_BACK_AFTER_KEY_UP, emptyList(), null, rng) }
        assertEquals(0.36, waits.count { it == 0L } / 40_000.0, 0.03)
        val later = waits.filter { it > 0 }.sorted()
        assertEquals(212.0, later[later.size / 2].toDouble(), 40.0)
        assertTrue(waits.all { it <= 3000 })
        // held through the death at 1000: nothing while it is down, then the key-up's wait from the key-up at 1210
        val b = TurnBack(TurnBack.Kind.NOW, 1200, 5.0, afterMove = { error("no move") }, from = 1000, latency = { error("timed from the key-up") }, afterKeyUp = { 0L })
        for (t in 1050L .. 1200L step 50L) assertEquals(Decision.NONE, b.update(t, moving = false, held = true), "held at $t")
        assertEquals(Decision.START, b.update(1250, moving = false, held = false, keyUpAt = 1210), "in the key-up's tick, as 4 of your 11")
        val c = TurnBack(TurnBack.Kind.NOW, 1200, 5.0, from = 1000, latency = { error("timed from the key-up") }, afterKeyUp = { 150L })
        assertEquals(Decision.NONE, c.update(1250, moving = false, keyUpAt = 1210))
        assertEquals(1360L, c.at)
        // a key that went up before the death is no end of anything after it: the latency from the death stands
        val d = TurnBack(TurnBack.Kind.NOW, 1300, 5.0, afterKeyUp = { error("before the death") }, from = 1000, latency = { 300L })
        assertEquals(Decision.START, d.update(1300, moving = false, keyUpAt = 900))
    }

    @Test
    fun `a turn back with nothing going on at the death keeps its latency, drawn again only to fall your shortest gap after a rest just before`() {
        // the view came to rest 20 ms before the death at 1000: a latency of 30 would start 50 ms after that rest (it
        // started with no gap at all); it is drawn again, never a gap stacked on it
        val latencies = ArrayDeque(listOf(60L, 200L))
        val b = TurnBack(TurnBack.Kind.NOW, 1030, 5.0, afterMove = { error("no move after the death") }, from = 1000, latency = { latencies.removeFirst() })
        assertEquals(Decision.NONE, b.update(1050, moving = false, restedAt = 980))
        assertEquals(1200L, b.at, "60 still falls within 95 ms of the rest, 200 does not")
        assertEquals(Decision.START, b.update(1200, moving = false, restedAt = 980))
        // a rest long before: as drawn
        val c = TurnBack(TurnBack.Kind.NOW, 1030, 5.0, from = 1000, latency = { error("as drawn") })
        assertEquals(Decision.START, c.update(1050, moving = false, restedAt = 500))
        // with your latencies the start after such a rest is never closer than 95 ms, and no pile there
        val starts = List(4000) { seed ->
            val p = ViewPolicy.turnBack(1000, 45.0, false, ViewPolicy.Learned(backRate = 1.0), Random(seed))
            var t = 1000L
            while (p.update(t, moving = false, restedAt = 990) != Decision.START) t += 50
            p.at - 990
        }
        assertTrue(starts.all { it >= 95 }, "${starts.min()}")
        assertTrue(starts.groupingBy { it / 50 }.eachCount().values.max() < 4000 * 0.15, "a pile after the rest")
    }

    @Test
    fun `a turn back due before the loadout switch was over is drawn again after it, never piled where it ends`() {
        val latencies = ArrayDeque(listOf(300L, 700L, 1200L))
        val b = TurnBack(TurnBack.Kind.NOW, 400, 5.0, from = 100, latency = { latencies.removeFirst() })
        // the switch was over at 900: 100 + 300 and 100 + 700 fall before it, 100 + 1200 after
        b.notBefore(900)
        assertEquals(1300L, b.at)
        // due after it already: left as it is
        b.notBefore(1000)
        assertEquals(1300L, b.at)
        assertEquals(Decision.NONE, b.update(1250, moving = false))
        assertEquals(Decision.START, b.update(1300, moving = false))
        b.notBefore(5000)
        assertEquals(1300L, b.at, "started: nothing to draw again")
        // with your latencies none piles in the tick after the wait
        val rng = Random(33)
        val at = List(20_000) { ViewPolicy.turnBack(0, 45.0, false, ViewPolicy.Learned(backRate = 1.0), rng).also { it.notBefore(800) }.at }
        assertTrue(at.all { it > 800 })
        // the latency's own density right after the wait, not a pile there (it was 73 % in that tick)
        val first = at.count { it <= 850 }
        val second = at.count { it in 851L .. 900L }
        assertTrue(first / 20_000.0 < 0.12 && first < 1.5 * second, "$first in the tick after the wait, $second in the next")
    }

    @Test
    fun `a first turn found not needed frees the target for looks until the policy turns for it`() {
        val p = ViewPolicy(Random(9))
        var t = 0L
        while (! p.foundFree) {
            assertFalse(p.free, "before the first turn's moment nothing is known")
            assertEquals(Decision.NONE, p.update(s(t, error = 20.0, tolerance = 30.0)))
            t += 50
            assertTrue(t < 30_000, "the first turn's moment comes")
        }
        assertTrue(p.free, "within the aim at the first turn's moment")
        p.update(s(t, error = 20.0, tolerance = 30.0))
        assertFalse(p.foundFree, "only at that moment")
        assertTrue(p.free)
        // the creature leaves the aim and the slack: the policy turns, and the target is no longer free
        val corrected = firstStart(p, t + 50, t + 10_000) { s(it, error = 170.0, tolerance = 30.0) }
        assertNotNull(corrected)
        assertFalse(p.free)
        val bobber = ViewPolicy(Random(9))
        bobber.update(s(0, need = Need.NONE))
        assertTrue(bobber.free, "a plan that never turns")
    }

    @Test
    fun `a look that left the creature just beyond the tolerance is corrected after a reaction, not after the slack`() {
        fun situation(now: Long, error: Double, looking: Boolean) =
            ViewPolicy.Situation(now, 1, Need.RANGED, view, aim, error, 30.0, anchorAt = 0L, accept = { 10.0 }, maxWaitMs = 18_000.0, looking = looking)
        var lookCorrected = 0
        var stillCorrected = 0
        repeat(200) { seed ->
            for (afterLook in listOf(true, false)) {
                val p = ViewPolicy(Random(seed))
                var t = 0L
                while (! p.foundFree) {
                    p.update(situation(t, 20.0, false))
                    t += 50
                }
                // a look moves the view: whatever the error meanwhile, no correction
                if (afterLook) repeat(10) {
                    assertEquals(Decision.NONE, p.update(situation(t, 60.0, true)), "seed $seed: no correction while a look moves")
                    t += 50
                }
                val start = firstStart(p, t, t + 2000) { situation(it, 31.0, false) }
                if (start != null) if (afterLook) lookCorrected++ else stillCorrected++
            }
        }
        assertEquals(200, lookCorrected, "1 degree beyond the aim after a look: corrected within 2 s")
        assertTrue(stillCorrected < 40, "the creature drifting there by itself waits for the slack: $stillCorrected of 200")
    }

    // ---- looks ----

    /** Your creature [id] [error] degrees off a view at (0, 0), straight to the side. */
    private fun mob(id: Int = 7, error: Double = 80.0, age: Long = 5000, cls: String? = null, overhead: Boolean = false, readyAt: Long = 0L) =
        ViewPolicy.Looks.Creature(id, cls, age, error, View(error, 0.0), overhead, readyAt)

    private fun scene(
        now: Long, vararg mobs: ViewPolicy.Looks.Creature, target: Int = - 1, need: Need = Need.NONE, free: Boolean = true, firstFree: Boolean = false,
        keeps: (View) -> Boolean = { true }, quiet: Boolean = false, moving: Boolean = false, view: View = View(0.0, 0.0),
    ) = ViewPolicy.Looks.Scene(now, view, mobs.toList(), target, need, free, firstFree, keeps, quiet, moving)

    /** Looks [perSecond] times a second at any creature of any age, returns as yours (79 %, p50 252 ms after the rest). */
    private fun eager(perSecond: Double = 1000.0) = ViewPolicy.Looks.Table.of(BehaviorProfile.Looks(
        all = BehaviorProfile.LookStats(100, 50, MutableList(6) { (perSecond * 10).toInt() }, MutableList(6) { 10.0 }, 0, 0)
    ))

    @Test
    fun `the looks' table is your reference rate per second alive without a profile, and a class of yours shrunk toward all`() {
        val d = ViewPolicy.Looks.Table.DEFAULT
        listOf(23.0 / 537.9, 12.0 / 423.0, 26.0 / 486.7, 17.0 / 504.8, 14.0 / 380.7, 9.0 / 708.0).zip(BehaviorProfile.Looks.AGE_BINS).forEach { (rate, age) ->
            assertEquals(rate, d.rate(null, age), 1e-9)
            assertEquals(rate, d.rate("ANY:L", age + 1999), 1e-9, "a class without counts reads all of them")
        }
        assertEquals(0.0, d.rate(null, - 1))
        assertEquals(10.0 / 53, d.first(null), 1e-9, "the weapon plans' first-moment share")
        assertEquals(listOf(0.06, 0.25, 0.53), d.stop.toList())
        assertEquals(0.79, d.backRate)
        assertEquals(252.0, d.backGapP50)

        val looks = BehaviorProfile.Looks()
        looks.classes["FLAMING_FLAY:R"] = BehaviorProfile.LookStats(21, 10, mutableListOf(8, 0, 2, 0, 0, 0), mutableListOf(18.6, 8.5, 9.9, 0.0, 0.0, 0.0), 12, 7)
        looks.classes["FEW:L"] = BehaviorProfile.LookStats(9, 9, mutableListOf(9, 0, 0, 0, 0, 0), mutableListOf(1.0, 0.0, 0.0, 0.0, 0.0, 0.0), 9, 9)
        val t = ViewPolicy.Looks.Table.of(looks)
        assertEquals((8 + 20 * 23.0 / 537.9) / (18.6 + 20), t.rate("FLAMING_FLAY:R", 500), 1e-9)
        assertEquals(d.rate(null, 40_000), t.rate("FLAMING_FLAY:R", 40_000), 1e-9, "a bin without your exposure reads all of them")
        assertEquals((7 + 5 * 10.0 / 53) / (12 + 5), t.first("FLAMING_FLAY:R"), 1e-9, "your Flay clicks: 0.47")
        assertEquals(d.rate(null, 500), t.rate("FEW:L", 500), 1e-9, "9 creatures are too few for a class of its own")
        assertSame(t, ViewPolicy.Looks.Table.of(looks), "read once per profile")

        // fewer than 30 creatures over all classes: the reference table
        val few = BehaviorProfile.Looks(all = BehaviorProfile.LookStats(20, 20, mutableListOf(20, 0, 0, 0, 0, 0), mutableListOf(5.0, 0.0, 0.0, 0.0, 0.0, 0.0), 0, 0))
        assertEquals(d.rate(null, 500), ViewPolicy.Looks.Table.of(few).rate(null, 500), 1e-9)
    }

    @Test
    fun `a look goes to a creature no plan needs at your rate per second alive and stops about a quarter of its error short`() {
        var looked = 0
        repeat(2000) { seed ->
            val looks = ViewPolicy.Looks(Random(seed))
            // 4 to 8 s old, 80 degrees off: your 0.053 looks a second, 19 % within 4 s
            for (t in 0L until 4000L step 50L) {
                if (looks.update(scene(t, mob(age = 4000 + t))) != Decision.START) continue
                looked++
                assertEquals(ViewPolicy.Looks.Kind.LOOK, looks.kind)
                assertEquals(7, looks.lookedAt)
                break
            }
        }
        assertTrue(looked in 320 .. 460, "$looked of 2000 looked within 4 s (1 - exp(-0.053 x 4) = 19 %)")

        // your stop shares: p10 0.06, p50 0.25, p90 0.53, nothing beyond 0.02 .. 0.6
        val shares = List(4000) { seed ->
            val looks = ViewPolicy.Looks(Random(seed))
            looks.table = eager()
            assertEquals(Decision.START, looks.update(scene(0, mob())))
            val g = looks.goal!!
            MotorModel.angle(g.yaw, g.pitch, 80.0, 0.0) / 80.0
        }.sorted()
        assertTrue(shares.first() >= ViewPolicy.Looks.STOP_MIN - 1e-6 && shares.last() <= ViewPolicy.Looks.STOP_MAX + 1e-6, "${shares.first()} .. ${shares.last()}")
        for ((q, ref) in listOf(0.1 to 0.06, 0.5 to 0.25, 0.9 to 0.53)) {
            val v = shares[(shares.size * q).toInt()]
            assertTrue(abs(v - ref) <= 0.1 * ref, "stop share p${(q * 100).toInt()} $v, yours $ref")
        }
    }

    @Test
    fun `a look's way back comes your gap after the view last turned, never within your shortest gap, learned or not`() {
        // the look's last moving tick was the one at 100 ms (the driver's clock), the tick at 150 ms sees it over: timed from
        // that tick, due on the tick nearest to your gap after it
        fun gaps(table: ViewPolicy.Looks.Table, turnedAt: Long, from: Long = if (turnedAt == Long.MIN_VALUE) 150L else turnedAt): List<Long> = (0 until 3000).mapNotNull { seed ->
            val looks = ViewPolicy.Looks(Random(seed))
            looks.table = table
            assertEquals(Decision.START, looks.update(scene(0, mob())))
            val g = looks.goal!!
            looks.update(scene(50, mob(), moving = true, view = g))
            var t = 150L
            while (t < 8000) {
                val d = ViewPolicy.Looks.Scene(t, g, listOf(mob()), - 1, Need.NONE, true, false, { true }, false, false, turnedAt)
                if (looks.update(d) == Decision.START) return@mapNotNull t - from
                t += 50
            }
            null
        }
        val eager = eager()
        val told = gaps(eager, 100L).sorted()
        // whole ticks after that tick: your 252 ms p50 lands on the tick nearest to it
        assertTrue(told.first() >= ViewPolicy.MIN_GAP_MS, "never within your shortest gap: ${told.first()}")
        val p50 = told[told.size / 2]
        assertTrue(p50 in 230L .. 330L, "p50 $p50 ms after the last moving tick (yours 252)")
        val p90 = told[told.size * 9 / 10]
        assertTrue(p90 in 450L .. 650L, "p90 $p90 ms (yours 551)")
        assertTrue(told.last() <= ViewPolicy.Looks.RETURN_CAP_MS + 50, "never later than 5 s: ${told.last()}")
        // not told: from the first update that sees the look over
        val untold = gaps(eager, Long.MIN_VALUE).sorted()
        assertTrue(untold.first() >= ViewPolicy.MIN_GAP_MS - 50 && untold[untold.size / 2] in 230L .. 330L, "${untold.first()} / ${untold[untold.size / 2]}")
        // a turn from before the look began is another move's: from the first update that finds the look over as well
        val stale = gaps(eager, - 500L, from = 150L).sorted()
        assertTrue(stale.first() >= ViewPolicy.MIN_GAP_MS - 50 && stale[stale.size / 2] in 230L .. 330L, "${stale.first()} / ${stale[stale.size / 2]}")
        // a learned gap is yours
        val learned = BehaviorProfile.Looks(all = BehaviorProfile.LookStats(100, 50, MutableList(6) { 10_000 }, MutableList(6) { 10.0 }, 0, 0),
            backGap = BehaviorProfile.Dist(40, 500.0, 1000.0, 2000.0))
        val slow = gaps(ViewPolicy.Looks.Table.of(learned), 100L).sorted()
        assertTrue(slow[slow.size / 2] in 900L .. 1100L, "learned p50 1000: ${slow[slow.size / 2]}")
        assertEquals(252.0, ViewPolicy.Looks.Table.DEFAULT.backGapP50)
    }

    @Test
    fun `a look's way back is timed from its last move of 3 degrees or more, a smaller one after it inside the gap, and starts its lead early`() {
        // the look's own move ended at 100 ms; a short stroke split off it (under 3 degrees) ended at [last]
        fun back(seed: Int, last: Long, lead: Long = 0L, goals: MutableList<View>? = null): Long? {
            val looks = ViewPolicy.Looks(Random(seed))
            looks.table = eager()
            assertEquals(Decision.START, looks.update(scene(0, mob())))
            val g = looks.goal!!
            looks.update(scene(50, mob(), moving = true, view = g))
            var t = 150L
            while (t < 8000) {
                val d = ViewPolicy.Looks.Scene(t, g, listOf(mob()), - 1, Need.NONE, true, false, { true }, false, false, 100L, { v -> goals?.add(v); lead }, last)
                if (looks.update(d) == Decision.START) return t.also { goals?.let { assertEquals(looks.goal, it.last(), "seed $seed: its lead planned on the goal it starts") } }
                t += 50
            }
            return null
        }
        val plain = (0 until 2000).mapNotNull { back(it, 100L)?.minus(100L) }.sorted()
        assertTrue(plain.size > 1000 && plain[plain.size / 2] in 230L .. 330L, "p50 ${plain[plain.size / 2]} (yours 252)")
        // a split-off stroke ending at 300 ms: still your gap from 100 ms, drawn again only where it would come within your
        // shortest gap after that stroke, never piled right after it
        val split = (0 until 2000).mapNotNull { back(it, 300L) }
        assertTrue(split.all { it >= 300 + ViewPolicy.MIN_GAP_MS }, "${split.min()}")
        assertTrue(split.count { it == 400L } < split.size * 0.25, "${split.count { it == 400L }} at the first tick allowed")
        val splitGaps = split.map { it - 100L }.sorted()
        assertTrue(splitGaps[splitGaps.size / 2] in 350L .. 500L, "p50 ${splitGaps[splitGaps.size / 2]} from the look's end")
        // a lead of a tick: the same way back, its move started a tick before its moment
        repeat(300) { seed ->
            val goals = ArrayList<View>()
            assertEquals(back(seed, 100L)?.minus(50L), back(seed, 100L, lead = 50L, goals = goals), "seed $seed")
        }
    }

    @Test
    fun `a look is one move per creature, and most of the time a return to the view it started from follows after your latency`() {
        var returns = 0
        val latencies = ArrayList<Long>()
        repeat(2000) { seed ->
            val looks = ViewPolicy.Looks(Random(seed))
            looks.table = eager()
            assertEquals(Decision.START, looks.update(scene(0, mob())))
            val g = looks.goal!!
            assertEquals(Decision.NONE, looks.update(scene(50, mob(), moving = true, view = g)), "no correction while it moves")
            // it ended at 100 ms: one return at most, toward where it started, then nothing more for that creature
            var t = 100L
            while (t < 8000) {
                val d = looks.update(scene(t, mob(), view = g))
                if (d == Decision.START) {
                    assertEquals(ViewPolicy.Looks.Kind.RETURN, looks.kind, "seed $seed: no second look at the same creature")
                    val back = looks.goal!!
                    assertTrue(MotorModel.angle(back.yaw, back.pitch, 0.0, 0.0) <= ViewPolicy.Looks.RETURN_SHORT_CAP + 1e-6, "seed $seed: back to $back")
                    assertTrue(MotorModel.angle(back.yaw, back.pitch, 0.0, 0.0) < MotorModel.angle(g.yaw, g.pitch, 0.0, 0.0))
                    returns++
                    latencies += t - 100
                    looks.update(scene(t + 50, mob(), moving = true, view = back))
                    for (k in 1 .. 100) assertEquals(Decision.NONE, looks.update(scene(t + 100 + k * 50, mob(), view = back)), "seed $seed: the return was the last move")
                    break
                }
                assertEquals(Decision.NONE, d)
                t += 50
            }
        }
        assertTrue(returns in 1500 .. 1660, "$returns of 2000 returned (79 %)")
        val p50 = latencies.sorted()[latencies.size / 2]
        assertTrue(p50 in 200L .. 330L, "return p50 $p50 ms after the look ended (your gap after the last rest, 252)")
        assertTrue(latencies.max() <= 5050L, "never later than 5 s")
    }

    @Test
    fun `the policy's turns win, no look while it moves, waits or starts, and a look in flight stops with its return dropped`() {
        val looks = ViewPolicy.Looks(Random(1))
        looks.table = eager()
        repeat(100) { assertEquals(Decision.NONE, looks.update(scene(it * 50L, mob(), quiet = true))) }
        assertEquals(Decision.START, looks.update(scene(5000, mob(), mob(8))))
        assertEquals(Decision.STOP, looks.update(scene(5050, mob(), mob(8), quiet = true, moving = true)), "the policy's move takes over")
        // the other creature may still be looked at later, the return to the first look never comes
        var t = 5100L
        var next: ViewPolicy.Looks.Kind? = null
        while (t < 20_000 && next == null) {
            if (looks.update(scene(t, mob(), mob(8))) == Decision.START) next = looks.kind
            t += 50
        }
        assertEquals(ViewPolicy.Looks.Kind.LOOK, next)
        assertEquals(8, looks.lookedAt)
        // a new fight: every creature may be looked at again
        looks.reset()
        assertEquals(Decision.START, looks.update(scene(30_000, mob())))
    }

    @Test
    fun `a look keeps the plan's target within its tolerance, or it does not go out`() {
        val looks = ViewPolicy.Looks(Random(2))
        looks.table = eager()
        val target = mob(1, error = 2.0)
        // a melee target in front of you, its box 5 degrees: a look at the creature 80 degrees off would leave it
        val keeps = { g: View -> MotorModel.angle(g.yaw, g.pitch, 2.0, 0.0) <= 5.0 }
        repeat(200) { assertEquals(Decision.NONE, looks.update(scene(it * 50L, target, mob(), target = 1, need = Need.MELEE, keeps = keeps))) }
        // a ranged target within its 30 degree aim, a creature 50 degrees off on the same side: a look that stops 18 degrees
        // or more short of it keeps the target in the aim, a longer one does not go out
        val ranged = { g: View -> MotorModel.angle(g.yaw, g.pitch, 2.0, 0.0) <= 30.0 }
        var started = 0
        repeat(1000) { seed ->
            val l = ViewPolicy.Looks(Random(seed))
            l.table = eager()
            if (l.update(scene(0, target, mob(error = 50.0), target = 1, need = Need.RANGED, keeps = ranged)) == Decision.START) {
                started++
                assertTrue(ranged(l.goal!!), "seed $seed: ${l.goal}")
            }
        }
        assertTrue(started in 100 .. 600, "$started of 1000 looks kept the target in its aim")
        val open = ViewPolicy.Looks(Random(3))
        open.table = eager()
        assertEquals(Decision.START, open.update(scene(0, target, mob(), target = 1, need = Need.MELEE)))
        assertEquals(7, open.lookedAt, "never the target itself within 15 degrees")
    }

    @Test
    fun `a weapon plan's target gets a first-moment look with your chance, other plans and its first 2 s get none`() {
        val looks = BehaviorProfile.Looks()
        looks.classes["FLAMING_FLAY:R"] = BehaviorProfile.LookStats(21, 10, mutableListOf(8, 0, 2, 0, 0, 0), mutableListOf(18.6, 8.5, 9.9, 0.0, 0.0, 0.0), 12, 7)
        val table = ViewPolicy.Looks.Table.of(looks)
        val flay = mob(1, error = 25.0, age = 600, cls = "FLAMING_FLAY:R")
        var first = 0
        repeat(2000) { seed ->
            val l = ViewPolicy.Looks(Random(seed))
            l.table = table
            if (l.update(scene(600, flay, target = 1, need = Need.RANGED, firstFree = true)) == Decision.START) first++
        }
        assertTrue(first in 860 .. 1010, "$first of 2000 first-moment looks (0.47)")
        val area = ViewPolicy.Looks(Random(1))
        area.table = table
        assertEquals(Decision.NONE, area.update(scene(200, mob(1, error = 25.0, age = 200, cls = "FLAMING_FLAY:R"), target = 1, need = Need.NONE, firstFree = true)),
            "a plan that never turns has no first moment, and 200 ms is too young for a look")
        // a weapon plan's target missed its first moment: no rate look before it is 2 s old
        val young = ViewPolicy.Looks(Random(4))
        young.table = eager()
        for (t in 600L until 2000L step 50L) assertEquals(Decision.NONE, young.update(scene(t, mob(1, error = 25.0, age = t), target = 1, need = Need.RANGED)))
        assertEquals(Decision.START, young.update(scene(2000, mob(1, error = 25.0, age = 2000), target = 1, need = Need.RANGED)))
        // not free (the policy turned for it): never
        val busy = ViewPolicy.Looks(Random(4))
        busy.table = eager()
        repeat(100) { assertEquals(Decision.NONE, busy.update(scene(3000L + it * 50, mob(1, age = 3000L + it * 50), target = 1, need = Need.RANGED, free = false))) }
    }

    @Test
    fun `no look at a creature right above or below you, within 15 degrees, younger than 300 ms or before its bobber's chance`() {
        val looks = ViewPolicy.Looks(Random(5))
        looks.table = eager()
        val overhead = mob(1, error = 160.0, overhead = true)
        val close = mob(2, error = 10.0)
        repeat(20) {
            val t = it * 50L
            assertEquals(Decision.NONE, looks.update(scene(t, overhead, close, mob(3, age = 250), mob(4, readyAt = 1000))), "at $t")
        }
        assertEquals(Decision.START, looks.update(scene(1000, overhead, close, mob(3, age = 250), mob(4, readyAt = 1000))))
        assertEquals(4, looks.lookedAt)
    }

    @Test
    fun `no look at a melee creature the plan turns to once it is the target, the target itself while free still gets one`() {
        val looks = ViewPolicy.Looks(Random(6))
        looks.table = eager()
        val waiting = ViewPolicy.Looks.Creature(1, "ROD_OF_THE_SEA:L", 5000, 80.0, View(80.0, 0.0), false, planTurns = true)
        repeat(20) { assertEquals(Decision.NONE, looks.update(scene(it * 50L, waiting, target = 2, need = Need.MELEE))) }
        // the same creature with a plan that never turns to it is looked at
        val bobber = ViewPolicy.Looks.Creature(3, "ROD_OF_THE_SEA:R", 5000, 80.0, View(80.0, 0.0), false)
        assertEquals(Decision.START, looks.update(scene(1000, waiting, bobber, target = 2, need = Need.MELEE)))
        assertEquals(3, looks.lookedAt)
    }

    @Test
    fun `a turn back's latency is drawn within the 3 s your turn backs were measured in`() {
        val rng = Random(8)
        // yours with nothing going on at the death: 0-2048 ms, p50 275; drawn without a bound 1.7 % would come after 3 s,
        // skipped turn backs. A learned latency over all your turn backs holds the rests and key-ups the others are timed
        // from: a profile's (here a slow one) is not what this one is drawn from
        val policy = BehaviorProfile.Policy(backBeyond = 40, backTurned = 40, backInterrupted = 0, backRate = 1.0, backLatency = BehaviorProfile.Dist(38, 1000.0, 2000.0, 2900.0))
        val at = List(20_000) { ViewPolicy.turnBack(0L, 30.0, false, ViewPolicy.Learned.of(policy, null), rng).at }
        assertTrue(at.all { it in 0L .. ViewPolicy.BACK_WINDOW_MS.toLong() }, "latest ${at.max()} ms")
        assertTrue(at.count { it >= ViewPolicy.BACK_WINDOW_MS - 50 } < 20_000 * 0.002, "no pile at the window's end")
        assertEquals(300.0, at.sorted()[10_000].toDouble(), 300.0 * 0.1)
        // one in the death's tick now and then (yours: 1 of 12), not a pile there
        assertTrue(at.count { it < 25 } < 20_000 * 0.03, "${at.count { it < 25 }}")
    }
}

/** The view driver on a fake view and clock: frames and ticks, caps, stops, aborts, owners, foreign input and rest nudges. */
class HumanAimTest {
    /** A view that keeps changes on the mouse grid like the game's fixRot. */
    private class FakePort(var yaw: Double = 0.0, var pitch: Double = 0.0, override val grid: Double = 0.15) : HumanAim.Port {
        override fun view() = View(yaw, pitch)

        override fun set(yaw: Double, pitch: Double): View {
            val dy = yaw - this.yaw
            val dp = pitch - this.pitch
            this.yaw += dy - dy % grid
            this.pitch = (this.pitch + dp - dp % grid).coerceIn(- 90.0, 90.0)
            return view()
        }
    }

    private class Rig(seed: Int = 1, yaw: Double = 0.0, pitch: Double = 0.0) {
        val port = FakePort(yaw, pitch)
        var now = 1000.0
        val aim = HumanAim(port, { now }, Random(seed))
    }

    /**
     * Runs [rig] at [fps] for [ms], with a hitch of [hitchMs] every second, the way the game does: frames, and the client
     * ticks that fall due (several in a row after a hitch, at one time). Returns the view at every tick.
     */
    private fun run(rig: Rig, fps: Double, ms: Double, hitchMs: Double = 0.0): List<View> {
        val ticks = ArrayList<View>()
        var nextTick = rig.now
        val end = rig.now + ms
        var nextHitch = rig.now + 1000.0
        while (rig.now < end) {
            var due = 0
            while (nextTick <= rig.now && due < 10) {
                rig.aim.tick(rest = false, anchor = null)
                ticks += rig.port.view()
                nextTick += 50.0
                due++
            }
            if (due == 10) nextTick = rig.now + 50.0
            rig.aim.frame()
            rig.now += if (hitchMs > 0 && rig.now >= nextHitch) { nextHitch += 1000.0; hitchMs } else 1000.0 / fps
        }
        return ticks
    }

    @Test
    fun `at 10, 30, 60 and 144 fps and with 250 ms hitches every tick stays within both caps, with no catch-up`() {
        for (fps in listOf(10.0, 30.0, 60.0, 144.0)) for (hitch in listOf(0.0, 250.0)) for (seed in 0 until 12) {
            val rig = Rig(seed, pitch = 60.0)
            assertTrue(rig.aim.start(170.0, - 20.0, Owner.KILLER))
            // a slow move of 5 long strokes takes several seconds, more with the time a hitch loses
            val ticks = run(rig, fps, 12_000.0, hitch)
            assertFalse(rig.aim.moving, "fps $fps hitch $hitch seed $seed: the move ended")
            for (i in 1 until ticks.size) {
                val a = ticks[i - 1]
                val b = ticks[i]
                val gc = MotorModel.angle(a.yaw, a.pitch, b.yaw, b.pitch) * 20.0
                val mouse = hypot(b.yaw - a.yaw, b.pitch - a.pitch) * 20.0
                assertTrue(gc <= 1679.0 && mouse <= 1685.0, "fps $fps hitch $hitch seed $seed tick $i: $gc / $mouse deg/s")
            }
            val last = ticks.last()
            assertTrue(MotorModel.angle(last.yaw, last.pitch, 170.0, - 20.0) < 5.0, "fps $fps seed $seed: ends near the goal, $last")
        }
    }

    @Test
    fun `a move never jumps a frame and is never one-frame fast near the pole`() {
        val rig = Rig(3, yaw = 0.0, pitch = 85.0)
        rig.aim.start(180.0, 85.0, Owner.KILLER)
        var prev = rig.port.view()
        while (rig.aim.moving) {
            rig.now += 1000.0 / 144
            rig.aim.frame()
            val v = rig.port.view()
            assertTrue(hypot(v.yaw - prev.yaw, v.pitch - prev.pitch) <= 1650.0 / 144 + 0.3, "a frame step of ${hypot(v.yaw - prev.yaw, v.pitch - prev.pitch)}")
            prev = v
        }
    }

    @Test
    fun `stop decelerates to rest instead of freezing, and the tail still holds the killer's move`() {
        // stopped in full swing (above about 200 deg/s), not in a pause between two strokes nor in a slow move
        val rig = (0 until 50).asSequence().map { seed ->
            Rig(seed).also { r ->
                r.aim.start(120.0, 0.0, Owner.KILLER)
                var swing: Double
                do {
                    swing = r.port.yaw
                    r.now += 7.0
                    r.aim.frame()
                } while (r.port.yaw - swing < 1.4 && r.aim.moving)
            }
        }.first { it.aim.moving }
        val before = rig.port.view()
        rig.aim.stop(Owner.KILLER)
        assertTrue(rig.aim.moving(Owner.KILLER), "the tail is part of the killer's move")
        rig.now += 7.0
        rig.aim.frame()
        val first = abs(rig.port.yaw - before.yaw)
        assertTrue(first > 0.0, "no freeze: it keeps moving right after the stop")
        val steps = ArrayList<Double>()
        var prev = rig.port.yaw
        var t = 0
        while (rig.aim.moving) {
            rig.now += 7.0
            rig.aim.frame()
            steps += abs(rig.port.yaw - prev)
            prev = rig.port.yaw
            t += 7
        }
        assertTrue(t >= 100, "the tail lasts at least about 120 ms, was $t")
        assertTrue(steps.takeLast(5).average() < steps.take(5).average(), "it slows down")
        assertFalse(rig.aim.moving(Owner.KILLER))
    }

    @Test
    fun `a move stopped in full swing tells where it comes to rest, so the turn back is measured from there`() {
        repeat(20) { seed ->
            val rig = Rig(seed)
            rig.aim.start(120.0, 20.0, Owner.KILLER)
            var swing: Double
            do {
                swing = rig.port.yaw
                rig.now += 7.0
                rig.aim.frame()
            } while (rig.port.yaw - swing < 1.4 && rig.aim.moving)
            if (! rig.aim.moving) return@repeat
            rig.aim.stop(Owner.KILLER)
            val rest = assertNotNull(rig.aim.restingAt)
            val at = rig.port.view()
            assertTrue(MotorModel.angle(at.yaw, at.pitch, rest.yaw, rest.pitch) > 2.0, "seed $seed: it still coasts")
            while (rig.aim.moving) {
                rig.now += 7.0
                rig.aim.frame()
            }
            val end = rig.port.view()
            assertTrue(MotorModel.angle(end.yaw, end.pitch, rest.yaw, rest.pitch) < 0.3, "seed $seed: rests at $end, said $rest")
            assertNull(rig.aim.restingAt)
        }
    }

    @Test
    fun `the driver knows when the last move of anyone came to rest, in the frame it did, not the tick someone noticed`() {
        val rig = Rig(8)
        assertTrue(rig.aim.sinceRestMs().isInfinite(), "none yet")
        rig.aim.start(40.0, 5.0, Owner.GLANCE)
        assertEquals(0.0, rig.aim.sinceRestMs(), "in flight")
        var restedAt = 0.0
        while (rig.aim.moving) {
            rig.now += 7.0
            rig.aim.frame()
            if (! rig.aim.moving) restedAt = rig.now
        }
        // a tick 30 ms after that frame finds it 30 ms ago
        rig.now += 30.0
        rig.aim.tick(rest = false, anchor = null)
        assertEquals(30.0, rig.aim.sinceRestMs(), 1e-9)
        assertEquals(restedAt, rig.now - rig.aim.sinceRestMs(), 1e-9)
        // a stopped move once its tail coasted; a frozen one at once; an idle move as much as the killer's
        rig.aim.start(- 40.0, 0.0, Owner.KILLER)
        repeat(10) { rig.now += 7.0; rig.aim.frame() }
        rig.aim.stopFight()
        assertEquals(0.0, rig.aim.sinceRestMs(), "still coasting")
        while (rig.aim.moving) { rig.now += 7.0; rig.aim.frame() }
        val coasted = rig.now
        rig.now += 50.0
        assertEquals(50.0, rig.aim.sinceRestMs(), 1e-9)
        assertEquals(coasted, rig.now - rig.aim.sinceRestMs(), 1e-9)
        rig.aim.start(10.0, 0.0, Owner.IDLE)
        repeat(5) { rig.now += 7.0; rig.aim.frame() }
        rig.aim.abort(Owner.IDLE)
        assertEquals(0.0, rig.aim.sinceRestMs(), 1e-9, "frozen now")
        // a stop or an abort with nothing in flight is no rest
        rig.now += 100.0
        rig.aim.stopFight()
        rig.aim.abortFight()
        rig.aim.stop()
        assertEquals(100.0, rig.aim.sinceRestMs(), 1e-9)
    }

    @Test
    fun `abort freezes at once, no motion after a takeover or a screen`() {
        val rig = Rig(5)
        rig.aim.start(90.0, 30.0, Owner.KILLER)
        repeat(20) { rig.now += 7.0; rig.aim.frame() }
        rig.aim.abort(Owner.KILLER)
        val v = rig.port.view()
        repeat(100) {
            rig.now += 7.0
            rig.aim.frame()
            rig.aim.tick(rest = false, anchor = null)
        }
        assertEquals(v, rig.port.view())
        assertFalse(rig.aim.moving)
    }

    @Test
    fun `an idle move is no move of the killer, and the killer's stop or abort leaves it alone`() {
        val rig = Rig(6)
        rig.aim.start(5.0, 3.0, Owner.IDLE)
        assertTrue(rig.aim.moving)
        assertFalse(rig.aim.moving(Owner.KILLER), "an idle move never holds Auto Fishing")
        rig.aim.stop(Owner.KILLER)
        rig.aim.abort(Owner.KILLER)
        assertTrue(rig.aim.moving(Owner.IDLE))
        assertFalse(rig.aim.stopping)
        // the killer starting a move takes it over, with its speed
        rig.now += 50.0
        rig.aim.frame()
        rig.aim.start(60.0, 0.0, Owner.KILLER)
        assertTrue(rig.aim.moving(Owner.KILLER))
    }

    @Test
    fun `a look is the killer's motion but no move of its policy, and its end is where rest nudges drift back to`() {
        val rig = Rig(7)
        rig.aim.start(40.0, 5.0, Owner.LOOK)
        assertTrue(rig.aim.movingInFight(), "it holds Auto Fishing like the killer's moves")
        assertFalse(rig.aim.moving(Owner.KILLER), "the policy never sees it")
        rig.aim.stop(Owner.KILLER)
        assertFalse(rig.aim.stopping, "the policy's own stop leaves it alone")
        while (rig.aim.moving) {
            rig.now += 7.0
            rig.aim.frame()
        }
        val end = rig.port.view()
        assertEquals(end, rig.aim.restAnchor)
        // the killer's own start replaces a look in flight; stopFight and abortFight cover both, never an idle move
        rig.aim.start(0.0, 0.0, Owner.LOOK)
        repeat(10) { rig.now += 7.0; rig.aim.frame() }
        rig.aim.start(- 30.0, 0.0, Owner.KILLER)
        assertTrue(rig.aim.moving(Owner.KILLER))
        repeat(10) { rig.now += 7.0; rig.aim.frame() }
        rig.aim.stopFight()
        assertTrue(rig.aim.stopping, "a killer's move in full swing coasts to rest")
        rig.aim.abortFight()
        assertFalse(rig.aim.moving)
        rig.aim.start(10.0, 0.0, Owner.IDLE)
        rig.aim.stopFight()
        rig.aim.abortFight()
        assertTrue(rig.aim.moving(Owner.IDLE))
        assertFalse(rig.aim.movingInFight())
    }

    @Test
    fun `your mouse below the takeover threshold is carried along, a bigger jump ends the move`() {
        val rig = Rig(7)
        rig.aim.start(100.0, 0.0, Owner.KILLER)
        repeat(10) { rig.now += 7.0; rig.aim.frame() }
        rig.port.yaw += 1.5
        rig.now += 7.0
        rig.aim.frame()
        assertTrue(rig.aim.moving, "1.5 degrees of yours: carried along")
        rig.port.yaw += 20.0
        rig.now += 7.0
        rig.aim.frame()
        assertFalse(rig.aim.moving, "a teleport or the server: the move ends")
    }

    @Test
    fun `rest nudges change about 2,5 % of still ticks by whole mouse steps and drift back to the anchor`() {
        val rig = Rig(8, yaw = 10.0, pitch = 40.0)
        val anchor = View(10.0, 40.0)
        var changed = 0
        var prev = rig.port.view()
        val n = 40_000
        repeat(n) {
            rig.now += 50.0
            rig.aim.tick(rest = true, anchor = anchor)
            val v = rig.port.view()
            if (v != prev) {
                changed++
                val steps = abs(v.yaw - prev.yaw) / 0.15
                assertTrue(steps < 0.01 || steps > 0.99, "whole steps: $steps")
            }
            prev = v
        }
        val share = changed.toDouble() / n
        assertTrue(share in 0.015 .. 0.035, "changed ticks $share")
        assertTrue(MotorModel.angle(prev.yaw, prev.pitch, anchor.yaw, anchor.pitch) < 4.0, "an hour of nudges stays near the anchor: $prev")
        assertFalse(rig.aim.moving)
    }

    @Test
    fun `your learned hand maps onto the motor, anything broken keeps the default`() {
        assertEquals(MotorParams(), HumanAim.paramsOf(BehaviorProfile.Motor()), "the defaults are the reference recordings' hand")
        val m = BehaviorProfile.Motor(lawA = 80.0, lawB = 55.0, sigma = Double.NaN, capGc = 99_999.0, multiP = mutableListOf(0.4, 0.4))
        val p = HumanAim.paramsOf(m)
        assertEquals(80.0, p.lawA)
        assertEquals(55.0, p.lawB)
        assertEquals(MotorParams().durationSigma, p.durationSigma)
        assertEquals(MotorParams().gcCap, p.gcCap)
        assertEquals(MotorParams().multiP, p.multiP)
        assertEquals(MotorParams(), HumanAim.paramsOf(null))
    }

    @Test
    fun `the killer's ways back are the kind the learner finds them to be`() {
        val from = View(40.0, 0.0)
        val target = View(0.0, 0.0)
        // back onto the plan's creature: it closes that creature's error (to 0.6 of it or less), a turn toward it
        assertEquals(GlanceMeasure.Kind.AIM, HumanAim.lookBackKind(from, View(2.0, 0.0), listOf(target)))
        // back to where the look started with no creature closed on: the way back of a turn toward a creature
        assertEquals(GlanceMeasure.Kind.TURN_BACK, HumanAim.lookBackKind(from, View(80.0, 0.0), listOf(target, View(40.0, 30.0))))
        assertEquals(GlanceMeasure.Kind.TURN_BACK, HumanAim.lookBackKind(from, View(80.0, 0.0), emptyList()))
        // a creature it moves away from or merely passes is not closed on (0.6 of its error is the line)
        assertEquals(GlanceMeasure.Kind.TURN_BACK, HumanAim.lookBackKind(from, View(25.0, 0.0), listOf(target)))
        assertEquals(GlanceMeasure.Kind.AIM, HumanAim.lookBackKind(from, View(23.0, 0.0), listOf(target)))
    }

    @Test
    fun `the way back from a look around is planned with your ways back's first stroke never past its goal`() {
        val m = BehaviorProfile.Motor(firstMu = 0.9, backFirstMu = 0.6, returnFirstMu = 0.55, lawA = 80.0)
        val turn = HumanAim.paramsOf(m)
        val back = HumanAim.returnParamsOf(m)
        // the way back from a look around: your ways back from a look's first stroke
        assertEquals(turn.copy(firstMean = 0.55, firstMax = 1.0, middleMax = 1.0), back, "only its first stroke and its strokes past the goal differ")
        // the turn back after a fight: your turn backs' first stroke, a middle stroke like your turns'
        assertEquals(turn.copy(firstMean = 0.6, firstMax = 1.0), HumanAim.turnBackParamsOf(m))
        // the killer's ways back: the hand of what the learner finds them to be, keeping the plan's creature in view the way
        // its looks do when the plan needs aim
        val kind = GlanceMeasure.Kind.TURN_BACK
        assertEquals(HumanAim.turnBackParamsOf(m), HumanAim.wayBackParamsOf(m, kind, keep = false))
        assertEquals(HumanAim.keepParamsOf(m).copy(firstMean = 0.6), HumanAim.wayBackParamsOf(m, kind, keep = true))
        assertEquals(turn, HumanAim.wayBackParamsOf(m, GlanceMeasure.Kind.AIM, keep = false))
        assertEquals(back, HumanAim.wayBackParamsOf(m, GlanceMeasure.Kind.BACK_TO_FIGHT, keep = false))
        assertEquals(HumanAim.keepParamsOf(m).copy(firstMean = 0.55), HumanAim.wayBackParamsOf(m, GlanceMeasure.Kind.RETURN, keep = true))
        // the look itself goes like a turn, never past its goal
        assertEquals(turn.firstMean, HumanAim.glanceParamsOf(m, null).firstMean)
        assertEquals(1.0, HumanAim.glanceParamsOf(m, null).firstMax)
        // nothing learned, broken, or outside what the learner gives: the reference's
        val d = BehaviorProfile.Motor()
        assertEquals(d.returnFirstMu, HumanAim.returnParamsOf(null).firstMean)
        assertEquals(d.backFirstMu, HumanAim.turnBackParamsOf(null).firstMean)
        for (bad in listOf(Double.NaN, 0.2, 1.3)) {
            assertEquals(d.returnFirstMu, HumanAim.returnParamsOf(BehaviorProfile.Motor(returnFirstMu = bad)).firstMean, "$bad")
            assertEquals(d.backFirstMu, HumanAim.turnBackParamsOf(BehaviorProfile.Motor(backFirstMu = bad)).firstMean, "$bad")
        }
    }

    @Test
    fun `a target right above or below you turns only the pitch, decided once per move`() {
        val (below, pitchOnly) = HumanAim.aimView(30.0, 0.0, 0.1, - 2.0, 0.2)
        assertTrue(pitchOnly)
        assertEquals(30.0, below.yaw)
        assertTrue(below.pitch > 80.0)
        val (side, flat) = HumanAim.aimView(0.0, 0.0, - 3.0, 0.0, 0.0)
        assertFalse(flat)
        assertEquals(90.0, side.yaw, 1e-9)
        // a move that started pitch-only stays so
        assertTrue(HumanAim.aimView(30.0, 0.0, 0.6, - 2.0, 0.2, pitchOnly = true).second)
        assertFalse(HumanAim.aimView(30.0, 0.0, 0.1, - 2.0, 0.2, pitchOnly = false).second)
    }
}

/**
 * DESIGN-HUMAN §7, the replay (`LBQ_RECORDINGS_DIR`, all four recordings): ViewPolicy and the motor (through HumanAim, at
 * the recorded ticks with 5 frames each) fight your own creatures from your own fights, with your main weapon class per
 * creature, starting from your view. Your side is measured in the same run with the same segmentation.
 */
class ViewPolicyReplayTest {
    private class Mob(val id: Int, val x: Double, val y: Double, val z: Double, val h: Double, val w: Double)
    private class Rot(val t: Long, val yaw: Double, val pitch: Double, val x: Double, val y: Double, val z: Double, val c: List<Mob>)
    private class Seen(val t: Long, val view: View)

    /** A view that keeps changes on the mouse grid like the game's fixRot. */
    private class GridPort(var yaw: Double, var pitch: Double) : HumanAim.Port {
        override val grid = 0.15
        override fun view() = View(yaw, pitch)

        override fun set(yaw: Double, pitch: Double): View {
            val dy = yaw - this.yaw
            val dp = pitch - this.pitch
            this.yaw += dy - dy % grid
            this.pitch = (this.pitch + dp - dp % grid).coerceIn(- 90.0, 90.0)
            return view()
        }
    }

    /** One side's numbers. */
    private class Side {
        var fightTicks = 0
        var changed = 0
        var maxGc = 0.0
        var maxMouse = 0.0
        val moves = ArrayList<ViewMotion.Move>()
        var creatures = 0
        var turned = 0
        val classCreatures = HashMap<String, Int>()
        val classTurned = HashMap<String, Int>()
    }

    private fun mobOf(a: JsonArray) =
        Mob(a[0].asInt, a[1].asDouble, a[2].asDouble, a[3].asDouble, if (a.size() > 4) a[4].asDouble else - 1.0, if (a.size() > 5) a[5].asDouble else - 1.0)

    /** The aim point's view from the player at [r] for [m], whether it is pitch-only, and its great-circle error from [v]. */
    private fun aimOf(r: Rot, m: Mob, v: View, share: Double, pitchOnly: Boolean?): Triple<View, Boolean, Double> {
        val h = if (m.h > 0) m.h else 1.8
        val (a, po) = HumanAim.aimView(v.yaw, v.pitch, m.x - r.x, m.y + h * share - (r.y + EYE), m.z - r.z, pitchOnly)
        return Triple(a, po, MotorModel.angle(v.yaw, v.pitch, a.yaw, a.pitch))
    }

    private fun boxHalf(r: Rot, m: Mob): Double {
        val size = if (m.h > 0) maxOf(m.h, m.w) else 1.8
        val d = maxOf(0.1, sqrt((m.x - r.x) * (m.x - r.x) + (m.y + size / 2 - r.y - EYE) * (m.y + size / 2 - r.y - EYE) + (m.z - r.z) * (m.z - r.z)))
        return maxOf(3.0, Math.toDegrees(atan2(size / 2, d)))
    }

    @Test
    fun `replayed on your own fights the bot turns about as rarely as you, never pursues, and stays within your speeds`() {
        val path = System.getenv("LBQ_RECORDINGS_DIR")
        assumeTrue("LBQ_RECORDINGS_DIR is not set", ! path.isNullOrBlank())
        val files = File(path).listFiles { f -> f.extension == "jsonl" }?.sortedBy { it.name }.orEmpty()
        assumeTrue("no recordings in $path", files.isNotEmpty())
        val user = Side()
        val bot = Side()
        var fightMs = 0.0
        val rng = Random(42)
        for (file in files) {
            val rots = ArrayList<Rot>()
            val spawns = HashMap<Int, Long>()
            val counts = HashMap<Int, HashMap<String, Int>>()
            file.forEachLine { line ->
                val o = JsonParser.parseString(line).asJsonObject
                when (o["e"].asString) {
                    "rot" -> rots += Rot(o["t"].asLong, o["yaw"].asDouble, o["pitch"].asDouble, o["x"].asDouble, o["y"].asDouble, o["z"].asDouble,
                        o["c"]?.asJsonArray?.map { mobOf(it.asJsonArray) }.orEmpty())
                    "spawn" -> if (o["own"]?.asBoolean == true) spawns[o["id"].asInt] = o["t"].asLong
                    "click" -> {
                        val b = o["b"].asString
                        // rod right clicks are casts and reels, not attacks
                        val attack = o["auto"]?.asBoolean != true && o["cast"]?.asBoolean != true && ! (b == "R" && o["rod"]?.asBoolean == true)
                        val id = (o["target"] ?: o["near"])?.asInt
                        if (attack && id != null) {
                            val cls = o["item"].asString + ":" + b + if (o["hold"]?.asBoolean == true) "h" else ""
                            counts.getOrPut(id) { HashMap() }.merge(cls, 1, Int::plus)
                        }
                    }
                }
            }
            val classes = counts.mapValues { (_, m) -> m.entries.maxBy { it.value }.key }

            // runs of consecutive rot lines (a recording writes every tick of a fight)
            val runs = ArrayList<List<Rot>>()
            var cur = ArrayList<Rot>()
            for (r in rots) {
                if (cur.isNotEmpty() && (r.t - cur.last().t > 120 || r.t <= cur.last().t)) {
                    runs += cur
                    cur = ArrayList()
                }
                cur += r
            }
            if (cur.isNotEmpty()) runs += cur

            for (run in runs.filter { r -> r.size > 1 && r.any { it.c.isNotEmpty() } }) {
                fightMs += run.last().t - run.first().t
                val botSeen = simulate(run, spawns, classes, rng)
                measure(user, run, run.map { Seen(it.t, View(it.yaw, it.pitch)) }, spawns, classes)
                measure(bot, run, botSeen, spawns, classes)
            }
        }

        fun share(s: Side) = s.changed.toDouble() / s.fightTicks
        fun big(s: Side) = s.moves.count { it.amp >= 6.0 } / (fightMs / 60_000.0)
        fun ends(s: Side) = s.moves.filter { it.amp >= 30.0 }.map { it.endShare }.sorted()
        fun lnSd(s: Side): Double {
            val xs = s.moves.filter { it.amp >= 6.0 && it.dur > 0 }.map { ln(it.dur / (34.0 + 111.0 * log2(1 + it.amp / 6.0))) }
            val m = xs.average()
            return sqrt(xs.sumOf { (it - m) * (it - m) } / xs.size)
        }
        fun rate(s: Side) = s.turned.toDouble() / maxOf(1, s.creatures)
        val report = buildString {
            appendLine("replay over %.1f fight minutes".format(fightMs / 60_000.0))
            for ((name, s) in listOf("user" to user, "bot" to bot)) {
                val e = ends(s)
                appendLine("%s: changed ticks %.1f %% of %d, moves >= 6 deg %.2f/min (%d), max tick %.0f gc / %.0f mouse deg/s, end/peak p50 %.2f (> 0.5: %.1f %%, n %d), ln-sd %.2f, turned toward %d of %d (%.0f %%)".format(
                    name, 100 * share(s), s.fightTicks, big(s), s.moves.count { it.amp >= 6.0 }, s.maxGc, s.maxMouse,
                    e.getOrElse(e.size / 2) { 0.0 }, 100.0 * e.count { it > 0.5 } / maxOf(1, e.size), e.size, lnSd(s), s.turned, s.creatures, 100 * rate(s)))
                appendLine("  by class: " + s.classCreatures.keys.sorted().joinToString { k -> "$k ${s.classTurned[k] ?: 0}/${s.classCreatures[k]}" })
            }
        }
        println(report)
        assertTrue(share(bot) <= 0.15, "fight ticks with a view change: bot %.3f\n%s".format(share(bot), report))
        assertTrue(bot.maxGc <= 1679.0 && bot.maxMouse <= 1685.0, report)
        assertTrue(big(bot) <= 2.0 * big(user), "moves per minute\n$report")
        assertTrue(rate(bot) <= rate(user) + 0.10, "turn rate\n$report")
        // the bot's moves of 30 degrees or more are few here (about 60): the 10 % share of fast endings (fast strokes and
        // look-downs over 3 ticks, the motor's own 50 ms sampling) gets 5 points of room for that
        val e = ends(bot)
        if (e.size >= 10) assertTrue(e[e.size / 2] <= 0.3 && e.count { it > 0.5 } <= 0.15 * e.size, "end speed\n$report")
    }

    /** The bot's view at every line of [run]: the policy and the motor at the recorded ticks, 5 frames between two. */
    private fun simulate(run: List<Rot>, spawns: Map<Int, Long>, classes: Map<Int, String>, rng: Random): List<Seen> {
        val port = GridPort(run[0].yaw, run[0].pitch)
        var now = run[0].t.toDouble()
        val aim = HumanAim(port, { now }, rng)
        val policy = ViewPolicy(rng)
        val readyAt = HashMap<Int, Long>()
        val out = ArrayList<Seen>()
        var target = - 1
        var pitchOnly = false
        for ((i, r) in run.withIndex()) {
            now = r.t.toDouble()
            aim.tick(rest = r.c.isNotEmpty(), anchor = aim.restAnchor)
            out += Seen(r.t, port.view())
            val m = r.c.firstOrNull()
            if ((m?.id ?: - 1) != target) {
                // the target died (or another one is next): the move towards it comes to rest
                aim.stop(Owner.KILLER)
                target = m?.id ?: - 1
            }
            if (m != null) {
                val cls = classes[m.id] ?: "none"
                val item = cls.substringBefore(':')
                val rodMelee = cls.endsWith(":L") && item.contains("ROD")
                val weapon = cls != "none" && ! rodMelee
                val spawn = spawns[m.id] ?: r.t
                // the reaction before the fight: the swap to a weapon, or the start of a rod fight
                val ready = readyAt.getOrPut(m.id) {
                    spawn + TimingModel.draw(if (weapon) TimingModel.Action.SWAP_REACTION else TimingModel.Action.SPAWN_REACTION, emptyList(), null, rng)
                }
                if (r.t >= ready) {
                    val v = port.view()
                    val moving = aim.moving(Owner.KILLER)
                    val need: Need
                    val tolerance: Double
                    val aimView: View
                    val po: Boolean
                    val error: Double
                    var onTarget = false
                    var lookDown = false
                    when {
                        cls == "none" -> {
                            need = Need.NONE
                            tolerance = 0.0
                            val a = aimOf(r, m, v, 0.6, null)
                            aimView = a.first
                            po = a.second
                            error = a.third
                        }
                        item in WeaponPlanner.TELEPORT_BLADES -> {
                            need = Need.PITCH
                            tolerance = 5.0
                            aimView = View(v.yaw, 89.0)
                            po = true
                            error = maxOf(0.0, 89.0 - v.pitch)
                            lookDown = true
                        }
                        cls.endsWith(":L") -> {
                            need = Need.MELEE
                            tolerance = boxHalf(r, m)
                            val a = aimOf(r, m, v, 0.65, if (moving) pitchOnly else null)
                            aimView = a.first
                            po = a.second
                            error = a.third
                            onTarget = error <= tolerance
                        }
                        else -> {
                            need = Need.RANGED
                            tolerance = if (cls.endsWith("h")) FLAY_AIM else RIGHT_AIM
                            val a = aimOf(r, m, v, 0.6, if (moving) pitchOnly else null)
                            aimView = a.first
                            po = a.second
                            error = a.third
                        }
                    }
                    val falls = need == Need.MELEE && r.t - spawn <= 1000 && hypot(m.x - r.x, m.z - r.z) < 1.0
                    val s = ViewPolicy.Situation(
                        r.t, m.id, need, v, aimView, error, tolerance, onTarget, moving && ! aim.stopping, aim.strokes,
                        if (weapon) ready else spawn, weapon, falls, 18_000.0, { ViewPolicy.rangedAcceptance(emptyList(), tolerance, rng) },
                    )
                    when (policy.update(s)) {
                        Decision.START -> policy.goal?.let { g -> if (aim.start(g.yaw, g.pitch, Owner.KILLER, lookDown)) pitchOnly = po }
                        Decision.RETARGET -> policy.goal?.let { g -> aim.retarget(g.yaw, g.pitch, policy.force) }
                        Decision.END -> aim.endAfterStroke()
                        Decision.STOP -> aim.stop(Owner.KILLER)
                        Decision.NONE -> Unit
                    }
                }
            }
            val next = run.getOrNull(i + 1)?.t ?: (r.t + 50)
            for (k in 1 .. 5) {
                now = r.t + (next - r.t) * k / 5.0
                aim.frame()
            }
        }
        return out
    }

    /** One side's fight ticks, speeds, movements and turns towards creatures over [run] with its views [seen]. */
    private fun measure(side: Side, run: List<Rot>, seen: List<Seen>, spawns: Map<Int, Long>, classes: Map<Int, String>) {
        for (i in 1 until run.size) {
            if (run[i].c.isEmpty()) continue
            side.fightTicks++
            val a = seen[i - 1].view
            val b = seen[i].view
            if (abs(MotorModel.wrap(b.yaw - a.yaw)) > 0.005 || abs(b.pitch - a.pitch) > 0.005) side.changed++
            val dt = (seen[i].t - seen[i - 1].t).toDouble()
            if (dt >= 30.0) {
                side.maxGc = maxOf(side.maxGc, MotorModel.angle(a.yaw, a.pitch, b.yaw, b.pitch) / dt * 1000)
                side.maxMouse = maxOf(side.maxMouse, hypot(MotorModel.wrap(b.yaw - a.yaw), b.pitch - a.pitch) / dt * 1000)
            }
        }
        val moves = ViewMotion.runs(seen.map { ViewMotion.Line(it.t, it.view.yaw, it.view.pitch) }).flatMap { ViewMotion.segment(it).moves }.filter { ! it.cut }
        side.moves += moves
        // turned toward a creature 15 degrees or more off at +300 ms that lived 1 s or more: a movement of 6 degrees or
        // more closed 40 % of its error
        val life = HashMap<Int, LongRange>()
        for (r in run) for (m in r.c) life[m.id] = (life[m.id]?.first ?: r.t) .. r.t
        for ((id, span) in life) {
            if (span.last - span.first < 1000 || id !in spawns) continue
            fun errAt(t: Long): Double? {
                val i = run.indexOfFirst { it.t >= t }.takeIf { it >= 0 } ?: return null
                val m = run[i].c.firstOrNull { it.id == id } ?: return null
                return aimOf(run[i], m, seen[i].view, 0.6, false).third
            }
            val e300 = errAt(span.first + 300) ?: continue
            if (e300 < 15.0) continue
            val cls = classes[id] ?: "none"
            side.creatures++
            side.classCreatures.merge(cls, 1, Int::plus)
            val turned = moves.any { mv ->
                mv.amp >= 6.0 && mv.t0 >= span.first && mv.t0 <= span.last && (errAt(mv.t1) ?: Double.MAX_VALUE) <= 0.6 * (errAt(mv.t0) ?: 0.0)
            }
            if (turned) {
                side.turned++
                side.classTurned.merge(cls, 1, Int::plus)
            }
        }
    }

    private companion object {
        const val EYE = 1.62
        /** A Flaming Flay hold's aim (your learned plan's) and the default right-click aim. */
        const val FLAY_AIM = 32.0
        const val RIGHT_AIM = 12.0
    }
}
