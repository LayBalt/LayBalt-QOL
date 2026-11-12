package fishing

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.laybalt.features.impl.fishing.TimingModel
import com.laybalt.features.impl.fishing.TimingModel.Action
import com.laybalt.features.impl.fishing.TimingModel.StreakState
import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.*

/**
 * The wait sampler: no piles at bounds, learned quantiles kept, slow streaks and melee rhythm with the samples'
 * distribution kept, the old Humanize wrapper, tick rounding; and, with `LBQ_RECORDINGS_DIR`, the user's own catch
 * recasts, after-kill waits, bite reactions and rod melee gaps measured against the sampler's output.
 */
class TimingModelTest {
    @Test
    fun `defaults have no piles and stay within their floor and cap`() {
        val rng = Random(1)
        for (a in Action.entries) {
            val state = StreakState()
            val xs = LongArray(100_000) { TimingModel.draw(a, emptyList(), state, rng) }
            val top = xs.toList().groupingBy { it }.eachCount().maxBy { it.value }
            val allowed = when (a) {
                // the held key's exact 4 ticks, and clicks in the swap tick
                Action.CATCH_RECAST -> 0.85
                Action.SWAP_CLICK_HYPERION -> 0.33
                Action.SWAP_CLICK_FLAY -> 0.18
                // a click already on its way as the creature died: 38 % of yours (melee) and 1 in 9 (presses) in that tick
                Action.DEATH_CLICK -> 0.42
                Action.DEATH_PRESS -> 0.14
                // children that came up in their parent's death tick: no wait for them (18 of your 25)
                Action.CHILD_WAIT -> 0.76
                // turn backs that started in the key-up's very tick (4 of your 11)
                Action.TURN_BACK_AFTER_KEY_UP -> 0.40
                // moves in a screen's close tick (4 of your 14)
                Action.SCREEN_CLOSE_TO_MOVE -> 0.33
                else -> 0.03
            }
            assertTrue(top.value / 1e5 <= allowed, "$a: ${top.value / 1e3}% at ${top.key} ms")
            assertTrue(xs.all { it >= a.floorMs && it <= a.capMs }, "$a: ${xs.min()}..${xs.max()}")
        }
        assertEquals(459.0, TimingModel.typical(Action.AFTER_KILL, emptyList()).toDouble(), 459 * 0.05)
        assertEquals(200L, TimingModel.typical(Action.CATCH_RECAST, emptyList()))
    }

    @Test
    fun `a normal drawn within bounds stays within them, piles at neither and keeps the normal's shape between them`() {
        val rng = Random(7)
        // a keep look's first stroke: N(0.65, 0.28) under 1.0, and its middle stroke N(1.0, 0.15) under 1.0
        for ((mean, sd, lo, hi) in listOf(listOf(0.65, 0.28, 0.35, 1.0), listOf(1.0, 0.15, 0.6, 1.0), listOf(0.2, 0.1, 0.1, 1.1))) {
            val xs = DoubleArray(200_000) { TimingModel.normal(mean, sd, lo, hi, rng) }
            assertTrue(xs.all { it in lo .. hi }, "outside $lo..$hi")
            // no point mass: the share within 0.1 % of the range from a bound is what the density there gives, not a pile
            val edge = 0.001 * (hi - lo)
            fun density(x: Double) = exp(- (x - mean) * (x - mean) / (2 * sd * sd))
            val norm = (0 until 10_000).sumOf { density(lo + (hi - lo) * (it + 0.5) / 10_000) } * (hi - lo) / 10_000
            for (b in listOf(lo, hi)) {
                val expected = density(b) * edge / norm
                val got = xs.count { abs(it - b) <= edge }.toDouble() / xs.size
                assertTrue(got <= expected * 1.5 + 2e-4, "$got within $edge of $b, about $expected expected")
            }
            // the mean of the normal between the bounds
            val want = (0 until 10_000).sumOf { val x = lo + (hi - lo) * (it + 0.5) / 10_000; x * density(x) } * (hi - lo) / 10_000 / norm
            assertEquals(want, xs.average(), 0.003, "mean of N($mean, $sd) within $lo..$hi")
        }
        // a range far out in a tail is drawn evenly instead, and a range of one point is that point
        val far = DoubleArray(10_000) { TimingModel.normal(0.0, 0.01, 5.0, 6.0, rng) }
        assertTrue(far.all { it in 5.0 .. 6.0 } && far.average() in 5.45 .. 5.55)
        assertEquals(3.0, TimingModel.normal(0.0, 1.0, 3.0, 3.0, rng))
    }

    @Test
    fun `learned samples keep their quantiles`() {
        val rng = Random(2)
        val samples = List(600) { (450 * exp(0.8 * TimingModel.normal(rng))).roundToInt() }
        val xs = List(100_000) { TimingModel.draw(Action.FIRST_HIT, samples, null, rng).toDouble() }
        for (p in listOf(0.1, 0.5, 0.9)) {
            val want = q(samples.map { it.toDouble() }, p)
            assertEquals(want, q(xs, p), want * 0.1, "p$p")
        }
    }

    @Test
    fun `outliers and samples below the floor are never drawn`() {
        val rng = Random(3)
        val samples = List(500) { 150 + rng.nextInt(151) } + listOf(5, 10, 100_000)
        val xs = LongArray(50_000) { TimingModel.draw(Action.BITE, samples, null, rng) }
        assertTrue(xs.all { it in 30L .. 900L }, "${xs.min()}..${xs.max()}")
        val capped = LongArray(50_000) { TimingModel.draw(Action.FIRST_TURN_SPAWN, emptyList(), null, rng, maxMs = 1500.0) }
        assertTrue(capped.all { it <= 1500 })
        assertTrue(capped.count { it >= 1490 } < capped.size * 0.02, "piled at the cap")
        repeat(1000) { assertTrue(TimingModel.lognormal(3500.0, 1.1, rng, 2000.0) <= 2000.0) }
    }

    @Test
    fun `waits under 400 ms are not jittered`() {
        val rng = Random(4)
        val held = List(20) { 200 }
        repeat(1000) { assertEquals(200L, TimingModel.draw(Action.CATCH_RECAST, held, StreakState(), rng)) }
        val long = List(20) { 1000 }
        val xs = LongArray(10_000) { TimingModel.draw(Action.AFTER_KILL, long, null, rng) }
        assertTrue(xs.toSet().size > 100, "jittered")
        assertTrue(xs.all { it in 250L .. 3000L })
    }

    /** 2000 ordered waits: about 10 % slow, a slow one followed by a slow one half of the time. */
    private fun streaky(rng: Random): List<Int> {
        var slow = false
        return List(2000) {
            slow = if (slow) rng.nextDouble() < 0.5 else rng.nextDouble() < 0.1 * 0.5 / 0.9
            if (slow) 800 + rng.nextInt(700) else 150 + rng.nextInt(100)
        }
    }

    @Test
    fun `slow waits come in streaks and keep their share`() {
        val rng = Random(5)
        val samples = streaky(rng)
        val thr = TimingModel.slowThreshold(samples)!!
        val share = TimingModel.slowShare(samples)
        assertEquals(0.5, TimingModel.stayRate(samples)!!, 0.08)

        fun run(action: Action, state: StreakState?): Pair<Double, Double> {
            val xs = List(100_000) { TimingModel.draw(action, samples, state, rng) > thr }
            val pairs = (1 until xs.size).filter { xs[it - 1] }
            return xs.count { it }.toDouble() / xs.size to pairs.count { xs[it] }.toDouble() / pairs.size
        }
        val (learnedShare, learnedStay) = run(Action.AFTER_KILL, StreakState())
        assertEquals(share, learnedShare, 0.015)
        assertEquals(0.5, learnedStay, 0.06, "the samples' own stay rate")
        val (setShare, setStay) = run(Action.AFTER_KILL, StreakState(stay = 0.2))
        assertEquals(share, setShare, 0.015)
        assertEquals(0.2, setStay, 0.04)
        // independent draws: bites have no streaks, and no state means none either
        assertEquals(share, run(Action.BITE, StreakState()).second, 0.03)
        assertEquals(share, run(Action.AFTER_KILL, null).second, 0.03)
    }

    @Test
    fun `a stay rate of 1 still enters the slow state and leaves it`() {
        val rng = Random(10)
        val samples = streaky(rng)
        val thr = TimingModel.slowThreshold(samples)!!
        // each of your slow waits followed by another (a slow run that ended a recording): the learned stay is 1
        val state = StreakState(stay = 1.0)
        val xs = List(100_000) { TimingModel.draw(Action.AFTER_KILL, samples, state, rng) > thr }
        assertEquals(TimingModel.slowShare(samples), xs.count { it }.toDouble() / xs.size, 0.03, "your slow share")
        assertTrue((1 until xs.size).count { xs[it - 1] && ! xs[it] } > 100, "slow runs end")
        val stuck = StreakState(stay = 1.0).apply { slow = true }
        assertTrue(List(2000) { TimingModel.draw(Action.AFTER_KILL, samples, stuck, rng) > thr }.any { ! it }, "once slow, not slow forever")
    }

    @Test
    fun `the pace between slow waits is fitted for the stay rate the draws use`() {
        val rng = Random(11)
        // ordered waits whose slow ones stay more often (0.7) than the rate learned over all recordings (0.4) that the
        // caller passes, and whose normal waits keep their half half of the time
        var slow = false
        var high = false
        val samples = List(3000) {
            slow = if (slow) rng.nextDouble() < 0.7 else rng.nextDouble() < 0.06 * 0.3 / 0.94
            if (! slow && rng.nextDouble() >= 0.5) high = rng.nextBoolean()
            when {
                slow -> 1000 + rng.nextInt(600)
                high -> 280 + rng.nextInt(100)
                else -> 90 + rng.nextInt(30)
            }
        }
        assertEquals(0.7, TimingModel.stayRate(samples)!!, 0.1)
        fun lag1(stay: Double?): Double {
            val state = StreakState(stay)
            return TimingModel.lag1(List(50_000) { TimingModel.draw(Action.AFTER_KILL, samples, state, rng).toInt() })!!
        }
        val target = TimingModel.lag1(samples)!!
        assertEquals(target, lag1(null), 0.05, "the samples' own stay rate")
        // weaker slow streaks are made up for by a pace kept more often: the draws keep the samples' lag-1 (a keep fitted
        // for the samples' own stay rate falls about 0.14 short)
        assertEquals(target, lag1(0.4), 0.05, "a learned stay rate")
    }

    @Test
    fun `the melee rhythm is kept`() {
        val rng = Random(6)
        fun lag1(state: StreakState?, samples: List<Int>) = TimingModel.lag1(List(50_000) { TimingModel.draw(Action.MELEE_GAP_ROD, samples, state, rng).toInt() })!!
        val shuffled = List(600) { if (rng.nextBoolean()) 150 + rng.nextInt(60) else 400 + rng.nextInt(100) }
        assertTrue(lag1(StreakState(stay = 0.75), shuffled) > 0.45)
        assertTrue(lag1(StreakState(), emptyList()) > 0.45, "the default rhythm")
        assertEquals(0.0, lag1(null, shuffled), 0.03)
        // shuffled gaps have no rhythm to learn
        assertTrue(TimingModel.fitRho(shuffled)!! <= 0.1)

        var mode = false
        val ordered = List(800) {
            if (rng.nextDouble() >= 0.6) mode = rng.nextBoolean()
            if (mode) 400 + rng.nextInt(100) else 150 + rng.nextInt(60)
        }
        val rho = TimingModel.fitRho(ordered)!!
        assertTrue(rho in 0.45 .. 0.75, "rho $rho")
        assertEquals(TimingModel.lag1(ordered)!!, lag1(StreakState(), ordered), 0.1, "the learned rhythm's lag-1")
    }

    @Test
    fun `the Humanize wrapper redraws instead of clamping, and always ends`() {
        val rng = Random(7)
        val out = List(8) { 10_000 }
        repeat(100) { assertEquals(10_000L, TimingModel.ranged(out, 100, 200, rng), "a sample itself") }
        val mixed = List(7) { 100 } + 5000
        val xs = LongArray(100_000) { TimingModel.ranged(mixed, 100, 200, rng) }
        assertEquals(0, xs.count { it == 5000L })
        assertTrue(xs.all { it == 100L })
        val few = listOf(100, 200, 300)
        assertTrue(LongArray(10_000) { TimingModel.ranged(few, 100, 200, rng) }.all { it in 90L .. 330L })
        assertTrue(LongArray(10_000) { TimingModel.ranged(emptyList(), 100, 200, rng) }.all { it in 100L .. 200L })
    }

    @Test
    fun `waits are due on the nearest tick, and a swap click can go out in the swap tick`() {
        /** Ticks after now until the wait is due, when it is checked once per tick. */
        fun ticks(ms: Long): Long {
            val due = TimingModel.dueAt(1000, ms)
            var tick = 1000L
            while (tick < due) tick += TimingModel.TICK_MS
            return (tick - 1000) / TimingModel.TICK_MS
        }
        assertEquals(listOf(0L, 0L, 1L, 1L, 2L, 4L), listOf(0L, 20L, 30L, 70L, 80L, 200L).map { ticks(it) })
        val rng = Random(8)
        val sameTick = List(50_000) { TimingModel.draw(Action.SWAP_CLICK_HYPERION, emptyList(), null, rng) }.count { ticks(it) == 0L }
        assertEquals(0.32, sameTick / 50_000.0, 0.03)
    }

    @Test
    fun `the same seed gives the same waits`() {
        fun waits(): List<Long> {
            val rng = Random(9)
            val recasts = StreakState()
            val clicks = StreakState()
            return List(1000) { TimingModel.draw(Action.CATCH_RECAST, emptyList(), recasts, rng) + TimingModel.draw(Action.MELEE_GAP_ROD, emptyList(), clicks, rng) }
        }
        assertEquals(waits(), waits())
    }

    // ---- the user's recordings ----

    private class Ev(val e: String, val t: Long, val o: JsonObject)

    private fun events(file: File): List<Ev>? {
        val out = ArrayList<Ev>()
        file.forEachLine { line ->
            if (line.isBlank() || line.startsWith("{\"e\":\"rot\"")) return@forEachLine
            val o = try {
                JsonParser.parseString(line).asJsonObject
            }
            catch (e: Exception) {
                return@forEachLine
            }
            val e = o.get("e")?.asString ?: return@forEachLine
            if (e == "rot") return@forEachLine
            out += Ev(e, o.get("t")?.asLong ?: return@forEachLine, o)
        }
        // the bot's own traces are no user data
        if (out.any { it.e == "session" && it.o.get("bot")?.asBoolean == true }) return null
        return out
    }

    private fun JsonObject.flag(name: String) = get(name)?.asBoolean == true
    private fun JsonObject.text(name: String) = get(name)?.asString

    /** Per file, in order: catch to recast, last death to the rod, bite to reel, rod melee gap runs (`crit-motor/t1_timing.js`). */
    private class Samples(val recast: List<List<Int>>, val afterKill: List<List<Int>>, val bite: List<List<Int>>, val gaps: List<List<Int>>)

    private fun extract(files: List<File>): Samples {
        val recast = ArrayList<List<Int>>()
        val afterKill = ArrayList<List<Int>>()
        val bite = ArrayList<List<Int>>()
        val gaps = ArrayList<List<Int>>()
        for (f in files) {
            val ev = events(f) ?: continue
            val s1 = ArrayList<Int>()
            val s2 = ArrayList<Int>()
            val s3 = ArrayList<Int>()
            for (i in ev.indices) {
                if (ev[i].e == "catch") {
                    val c = (i + 1 until ev.size).asSequence().map { ev[it] }.firstOrNull { it.e == "cast" || it.e == "catch" }
                    if (c != null && c.e == "cast" && c.t - ev[i].t <= 3000) s1 += (c.t - ev[i].t).toInt()
                }
                if (ev[i].e == "bite") {
                    val c = (i + 1 until min(ev.size, i + 40)).asSequence().map { ev[it] }.firstOrNull { it.e == "reel" || it.e == "bite" }
                    if (c != null && c.e == "reel" && c.t - ev[i].t <= 3000) s3 += (c.t - ev[i].t).toInt()
                }
            }
            val death = ev.filter { it.e == "death" }.associate { it.o.get("id").asLong to it.t }
            val iv = ev.filter { it.e == "spawn" && it.o.flag("own") }.mapNotNull { s -> death[s.o.get("id").asLong]?.let { s.t to it } }.sortedBy { it.first }
            val fights = ArrayList<LongArray>()
            for ((a, b) in iv) {
                val l = fights.lastOrNull()
                if (l != null && a <= l[1]) l[1] = max(l[1], b) else fights += longArrayOf(a, b)
            }
            for (fight in fights) {
                val n = ev.firstOrNull { it.t >= fight[1] && ((it.e == "slot" && it.o.flag("rod")) || (it.e == "click" && it.o.text("b") == "R" && it.o.flag("rod"))) }
                if (n != null && n.t - fight[1] <= 8000) s2 += (n.t - fight[1]).toInt()
            }
            val clicks = ev.filter { it.e == "click" }
            var seq = ArrayList<Int>()
            for (i in 1 until clicks.size) {
                val a = clicks[i - 1]
                val b = clicks[i]
                val rodMelee = a.o.text("b") == "L" && b.o.text("b") == "L" && a.o.text("item") == "ROD_OF_THE_SEA" && b.o.text("item") == "ROD_OF_THE_SEA"
                if (rodMelee && b.t - a.t <= 1500 && fights.any { b.t >= it[0] && b.t <= it[1] }) seq += (b.t - a.t).toInt()
                else if (seq.isNotEmpty()) {
                    gaps += seq
                    seq = ArrayList()
                }
            }
            if (seq.isNotEmpty()) gaps += seq
            recast += s1
            afterKill += s2
            bite += s3
        }
        return Samples(recast, afterKill, bite, gaps)
    }

    private class Serial(val slowShare: Double, val slowAfterSlow: Double, val slowPairs: Int, val lag1: Double)

    /** Slow share, slow after slow (and how many slow waits it is measured on) and lag-1 of ln over runs that keep their order. */
    private fun serial(runs: List<List<Int>>, thr: Double): Serial {
        var n = 0
        var slow = 0
        var prevSlow = 0
        var both = 0
        val xs = ArrayList<Double>()
        val ys = ArrayList<Double>()
        for (r in runs) for (i in r.indices) {
            n++
            if (r[i] > thr) slow++
            if (i == 0) continue
            xs += ln(max(1, r[i - 1]).toDouble())
            ys += ln(max(1, r[i]).toDouble())
            if (r[i - 1] > thr) {
                prevSlow++
                if (r[i] > thr) both++
            }
        }
        val mx = xs.average()
        val my = ys.average()
        var sxy = 0.0
        var sxx = 0.0
        var syy = 0.0
        for (i in xs.indices) {
            sxy += (xs[i] - mx) * (ys[i] - my)
            sxx += (xs[i] - mx) * (xs[i] - mx)
            syy += (ys[i] - my) * (ys[i] - my)
        }
        return Serial(slow.toDouble() / n, if (prevSlow == 0) Double.NaN else both.toDouble() / prevSlow, prevSlow, sxy / sqrt(sxx * syy))
    }

    /** Kolmogorov-Smirnov distance between the tick counts (nearest tick) of two sets of waits. */
    private fun tickKs(a: List<Int>, b: List<Long>): Double {
        val x = a.map { Math.round(it / 50.0) }.sorted()
        val y = b.map { Math.round(it / 50.0) }.sorted()
        var i = 0
        var j = 0
        var d = 0.0
        while (i < x.size && j < y.size) {
            val v = min(x[i], y[j])
            while (i < x.size && x[i] <= v) i++
            while (j < y.size && y[j] <= v) j++
            d = max(d, abs(i.toDouble() / x.size - j.toDouble() / y.size))
        }
        return d
    }

    @Test
    fun `the sampler keeps the recorded waits, their streaks and the melee rhythm`() {
        val dir = System.getenv("LBQ_RECORDINGS_DIR")
        assumeTrue("LBQ_RECORDINGS_DIR is not set", ! dir.isNullOrBlank())
        val files = File(dir).listFiles { f -> f.name.endsWith(".jsonl") }?.sortedBy { it.name }.orEmpty()
        assumeTrue("no recordings in $dir", files.isNotEmpty())
        val s = extract(files)
        val rng = Random(10)
        for ((action, runs) in listOf(Action.CATCH_RECAST to s.recast, Action.AFTER_KILL to s.afterKill, Action.BITE to s.bite, Action.MELEE_GAP_ROD to s.gaps)) {
            val samples = runs.flatten()
            if (samples.size < 50) continue
            val thr = TimingModel.slowThreshold(samples)!!
            val state = StreakState()
            val draws = List(20_000) { TimingModel.draw(action, samples, state, rng) }
            val user = serial(runs, thr)
            val bot = serial(listOf(draws.map { it.toInt() }), thr)
            val ks = tickKs(samples, draws)
            println("$action n=${samples.size}: tick KS ${"%.3f".format(ks)}; slow ${"%.1f".format(user.slowShare * 100)}% vs ${"%.1f".format(bot.slowShare * 100)}%; " +
                "slow after slow ${"%.0f".format(user.slowAfterSlow * 100)}% (of ${user.slowPairs}) vs ${"%.0f".format(bot.slowAfterSlow * 100)}%; lag-1 ${"%.2f".format(user.lag1)} vs ${"%.2f".format(bot.lag1)}")
            assertTrue(ks <= 0.06, "$action tick KS $ks")
            // a rate measured on a handful of slow waits says nothing
            if (user.slowPairs >= 20) assertEquals(user.slowAfterSlow, bot.slowAfterSlow, 0.12, "$action slow after slow")
            if (action.kind == TimingModel.Kind.RHYTHM) assertEquals(user.lag1, bot.lag1, 0.1, "$action lag-1")
        }
    }

    private fun q(xs: List<Double>, p: Double): Double {
        val s = xs.sorted()
        val i = (s.size - 1) * p
        val lo = i.toInt()
        val hi = min(lo + 1, s.size - 1)
        return s[lo] + (s[hi] - s[lo]) * (i - lo)
    }
}
