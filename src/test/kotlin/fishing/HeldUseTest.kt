package fishing

import com.laybalt.features.impl.fishing.AutoFishing.Phase
import com.laybalt.features.impl.fishing.AutoFishing.RodClock
import com.laybalt.features.impl.fishing.AutoMobKiller.FightRules
import com.laybalt.features.impl.fishing.AutoMobKiller.HomeWatch
import com.laybalt.features.impl.fishing.CrossGap
import com.laybalt.features.impl.fishing.HeldUse
import com.laybalt.features.impl.fishing.HumanAim
import com.laybalt.features.impl.fishing.HumanAim.Owner
import com.laybalt.features.impl.fishing.MotorModel.View
import com.laybalt.features.impl.fishing.MotorParams
import com.laybalt.features.impl.fishing.RecastTimer
import com.laybalt.features.impl.fishing.TimingModel
import com.laybalt.features.impl.fishing.TimingModel.Action
import com.laybalt.features.impl.fishing.ViewPolicy
import kotlin.random.Random
import kotlin.test.*

/**
 * What Auto Fishing's rod does after something held it back ([HeldUse]): a cast or recast a look around or a turn home
 * held goes out a wait of yours after that move came to rest, a reel or cast a screen held a wait of yours after the
 * close, never in the tick it ended; its rod click and the killer's click a switch of your hand apart ([CrossGap]); and
 * your fishing view never taken from a move of the bot's ([HomeWatch]).
 */
class HeldUseTest {
    private val floor = ViewPolicy.MIN_GAP_MS

    @Test
    fun `a cast held while due goes out a wait after the hold's end, drawn once, never in that tick`() {
        val h = HeldUse()
        var draws = 0
        // due from 1000 on, held until a move came to rest in the frames before the tick at 1200, the first tick that shows
        // it: the wait runs from that tick, as your rests are timed from the tick that shows them
        for (t in 1000L .. 1150L step 50) h.hold(t, due = true)
        assertTrue(h.owed)
        assertNull(h.goesAt, "nothing drawn while held")
        assertFalse(h.go(1200, true, 1200, floor) { draws++; 250L }, "not in the tick the move rested")
        assertEquals(1200L + 250 - 25, h.goesAt)
        assertFalse(h.go(1250, true, 1200, floor) { draws++; 250L })
        assertFalse(h.go(1350, true, 1200, floor) { draws++; 250L })
        assertTrue(h.go(1450, true, 1200, floor) { draws++; 250L }, "at its time")
        assertEquals(1, draws, "drawn once")
        assertFalse(h.owed)
        // held again before its time: the time drawn is dropped, the next end draws anew
        val g = HeldUse()
        g.hold(0, true)
        assertFalse(g.go(100, true, 100, floor) { 400L })
        g.hold(150, true)
        assertNull(g.goesAt)
        assertFalse(g.go(600, true, 600, floor) { 300L })
        assertEquals(600L + 300 - 25, g.goesAt)
        // the shortest wait still leaves two ticks after the tick that saw the rest
        val s = HeldUse()
        s.hold(0, true)
        assertFalse(s.go(50, true, 50, floor) { floor })
        assertFalse(s.go(100, true, 50, floor) { floor })
        assertTrue(s.go(150, true, 50, floor) { floor })
    }

    @Test
    fun `another move coming to rest while it waits times it anew from that rest`() {
        val h = HeldUse()
        h.hold(0, true)
        assertFalse(h.go(100, true, 100, floor) { 300L })
        assertEquals(100L + 300 - 25, h.goesAt)
        // a move that held nothing rested at 330 (seen at 350), just before its time: never right after that rest either
        assertFalse(h.go(350, true, 350, floor) { 200L })
        assertEquals(350L + 200 - 25, h.goesAt)
        assertFalse(h.go(400, true, 350, floor) { error("drawn once per end") })
        assertTrue(h.go(550, true, 350, floor) { error("drawn once per end") })
    }

    @Test
    fun `held before it was due, or due long after the last rest, it goes when due`() {
        val h = HeldUse()
        h.hold(0, due = false)
        assertFalse(h.owed)
        assertFalse(h.go(100, false, 50, floor) { error("no wait for one not due") })
        assertTrue(h.go(400, true, 50, floor) { error("no wait for one due long after the rest") })
    }

    @Test
    fun `one due a moment after a rest waits like one held, from that rest`() {
        val h = HeldUse()
        assertFalse(h.go(1050, true, 1050, floor) { 200L })
        assertTrue(h.owed)
        assertEquals(1050L + 200 - 25, h.goesAt)
        // due two ticks after the tick that saw the rest: not right after it
        assertTrue(HeldUse().go(1100, true, 1000, floor) { error("no wait") })
        // a reel (no floor): it goes when due whatever rested just before
        assertTrue(HeldUse().go(1050, true, 1050, 0L) { error("no wait") })
    }

    @Test
    fun `a reel or cast due behind a screen goes out your reaction to its close later, one not yet due at its own time`() {
        val rng = Random(21)
        val gaps = ArrayList<Long>()
        repeat(2000) {
            val h = HeldUse()
            // the screen paused Auto Fishing from 0 on, the reel came due at 300, the screen closed before the tick at 2000
            for (t in 0L .. 1950L step 50) h.hold(t, due = t >= 300)
            var t = 2000L
            while (! h.go(t, true, 2000L, 0L) { TimingModel.draw(Action.SCREEN_CLOSE_TO_REEL, emptyList(), null, rng) }) t += 50
            gaps += t - 2000L
        }
        // yours: 0, 51, 99, 102, 144, 153, 792, 1147 ms after the close: some in its tick or the next, most later
        val first = gaps.count { it <= 50 }.toDouble() / gaps.size
        assertTrue(first in 0.05 .. 0.4, "$first in the close's tick or the next")
        assertTrue(gaps.sorted()[gaps.size / 2] in 100L .. 200L, "p50 ${gaps.sorted()[gaps.size / 2]}")
        // not due until after the screen closed: it goes when due, no reaction to the close on top
        val h = HeldUse()
        for (t in 0L .. 450L step 50) h.hold(t, due = false)
        assertFalse(h.go(500, false, 500, 0L) { error("not due") })
        assertTrue(h.go(700, true, 500, 0L) { error("no wait for the close") })
    }

    @Test
    fun `a look given up where it stood leaves no rest to time from, the wait runs from that tick`() {
        val h = HeldUse()
        h.hold(5000, true)
        // the view's last rest came before the hold (the look dwelt), and long ago
        assertFalse(h.go(5100, true, 2000, floor) { 300L })
        assertEquals(5100L + 300 - 25, h.goesAt)
    }

    @Test
    fun `on a real hand a cast held by a look's way back goes out a drawn wait after its rest, spread like yours`() {
        val gaps = ArrayList<Long>()
        val rng = Random(11)
        repeat(400) { run ->
            val port = Port(0.0, 0.0)
            val clock = doubleArrayOf(0.0)
            val aim = HumanAim(port, { clock[0] }, Random(run.toLong()))
            aim.params = MotorParams(durationSigma = 0.05, multiP = List(5) { 0.0 }, slowP = 0.0)
            assertTrue(aim.start(40.0, 10.0, Owner.GLANCE))
            val held = HeldUse()
            var now = 0L
            var restSeen = Long.MIN_VALUE
            var castAt = Long.MIN_VALUE
            // the recast is due from the first tick on: Auto Fishing's tick after the driver's, as in the game
            while (castAt == Long.MIN_VALUE && now < 10_000L) {
                now += 50
                live(aim, clock, now)
                if (aim.moving(Owner.GLANCE)) {
                    held.hold(now, true)
                    continue
                }
                // the first tick that shows the rest, as Auto Fishing watches it
                if (restSeen == Long.MIN_VALUE) restSeen = now
                if (held.go(now, true, restSeen, floor) { TimingModel.draw(Action.REST_TO_RECAST, emptyList(), null, rng) }) castAt = now
            }
            assertTrue(castAt > restSeen, "run $run: the cast went out")
            gaps += castAt - restSeen
        }
        gaps.sort()
        assertTrue(gaps.first() >= floor, "never within your shortest gap after the rest: ${gaps.first()}")
        // yours: p10 150, p50 202, p90 444 (52 recasts held by a move), 22 of them (42 %) on the fourth tick after the
        // rest: no tick carries more than yours did, within its sampling noise (two sd: 56 %), and that one is your fourth
        val top = gaps.groupingBy { (it + 25) / 50 }.eachCount().maxBy { it.value }
        assertTrue(top.value < gaps.size * 0.56, "${top.value} of ${gaps.size} in tick ${top.key} after the rest")
        assertEquals(4L, top.key, "the tick most of them go on")
        assertTrue(gaps[gaps.size / 2] in 150L .. 400L, "p50 ${gaps[gaps.size / 2]}")
        assertTrue(gaps[gaps.size * 9 / 10] > 350L, "p90 ${gaps[gaps.size * 9 / 10]}")
    }

    @Test
    fun `the waits after a rest and after a screen are spread like yours`() {
        val rng = Random(5)
        fun quantiles(a: Action) = List(40_000) { TimingModel.draw(a, emptyList(), null, rng) }.sorted().let { xs -> listOf(0.1, 0.5, 0.9).map { xs[(it * xs.size).toInt()] } }
        // yours: recasts p10 150 / p50 202 / p90 444; other casts 140 / 350 / 2359; a reel after a close p50 about 120, a cast 206 / 599 / 1289
        val recast = quantiles(Action.REST_TO_RECAST)
        assertTrue(recast[0] in 100L .. 180L && recast[1] in 190L .. 300L && recast[2] in 400L .. 650L, "$recast")
        val cast = quantiles(Action.REST_TO_CAST)
        assertTrue(cast[0] in 95L .. 200L && cast[1] in 250L .. 450L && cast[2] in 1200L .. 2600L, "$cast")
        // as your casts are measured: the tick each goes on after the last moving tick (the nearest one, never within
        // 95 ms: HeldUse), quantile by quantile against yours on the tick grid (recasts 150 / 200 / 200 / 313 / 445 of 52,
        // other casts 140 / 200 / 350 / 900 / 2360 of 39), each within a tick or two at the slow end
        fun ticks(a: Action) = List(40_000) { maxOf(TimingModel.draw(a, emptyList(), null, rng) - TimingModel.TICK_MS / 2, ViewPolicy.MIN_GAP_MS).let { (it + 49) / 50 * 50 } }.sorted()
            .let { xs -> listOf(0.1, 0.25, 0.5, 0.75, 0.9).map { xs[(it * xs.size).toInt()] } }
        val rt = ticks(Action.REST_TO_RECAST)
        assertEquals(listOf(150L, 200L, 200L), rt.take(3), "$rt")
        assertTrue(rt[3] in 250L .. 350L && rt[4] in 400L .. 550L, "$rt")
        val ct = ticks(Action.REST_TO_CAST)
        assertTrue(ct[0] in 100L .. 150L && ct[1] in 150L .. 250L && ct[2] in 300L .. 400L && ct[3] in 750L .. 1100L && ct[4] in 2000L .. 2600L, "$ct")
        // drawn, not piled: the quick hand's draws spread over the fourth tick's 50 ms, the slow ones never on the cap
        val quick = List(40_000) { TimingModel.draw(Action.REST_TO_RECAST, emptyList(), null, rng) }.filter { it in 176L .. 225L }
        assertTrue(quick.toSet().size >= 30 && quick.groupingBy { it }.eachCount().values.max() < quick.size * 0.05, "a pile")
        val slow = List(40_000) { TimingModel.draw(Action.REST_TO_CAST, emptyList(), null, rng) }
        assertTrue(slow.max() <= 3000L && slow.count { it >= 2990L } < slow.size * 0.005, "piled at the cap")
        val reel = quantiles(Action.SCREEN_CLOSE_TO_REEL)
        assertTrue(reel[1] in 100L .. 200L, "$reel")
        val afterClose = quantiles(Action.SCREEN_CLOSE_TO_CAST)
        assertTrue(afterClose[0] in 150L .. 300L && afterClose[1] in 450L .. 700L && afterClose[2] in 1000L .. 1700L, "$afterClose")
    }

    @Test
    fun `a click timed from after the other button's click holds the switch, one due before it waits a switch drawn after its due time`() {
        val g = CrossGap()
        assertEquals(Long.MIN_VALUE, g.notBefore(0L, 0L, 0L) { error("no click of the other button yet") })
        // the other button's click came at or before this click's clock started (the recast a first hit is timed from, a
        // left click before the bite a reel is timed from): that clock holds your switch, none on top
        assertEquals(Long.MIN_VALUE, g.notBefore(10_000L, 10_000L, 10_600L) { error("inside that clock") })
        assertEquals(Long.MIN_VALUE, g.notBefore(9_000L, 10_000L, 10_200L) { error("inside that clock") })
        // after it: a switch drawn once per such click, again until it falls after the click's own due time (never the later
        // of the two waits, which would put every click whose switch came first right at its due time)
        var draws = 0
        val seq = ArrayDeque(listOf(100L, 150L, 400L))
        val at = g.notBefore(10_100L, 10_000L, 10_300L) { draws++; seq.removeFirst() }
        assertEquals(10_100L + 400 - 25, at)
        assertEquals(3, draws)
        assertEquals(at, g.notBefore(10_100L, 10_000L, 10_300L) { error("drawn once per click") })
        assertEquals(11_000L + 200 - 25, g.notBefore(11_000L, 10_500L, 11_000L) { draws++; 200L }, "a new click: a switch of its own")
        // none of the switches falls after the due time: yours had come by then, no wait
        assertEquals(Long.MIN_VALUE, CrossGap().notBefore(10_100L, 10_000L, 20_000L) { 900L })
        val rng = Random(9)
        for ((a, p50s) in listOf(Action.CROSS_L_TO_R to 200L .. 330L, Action.CROSS_R_TO_L to 240L .. 380L)) {
            val xs = List(40_000) { TimingModel.draw(a, emptyList(), null, rng) }
            // yours to a click already due: 12 left-to-right (p50 202), 42 right-to-left (p50 276), none within two ticks
            assertTrue(xs.all { it >= 95 }, "$a: none within two ticks")
            assertTrue(xs.all { it <= 1000 }, "$a: within the second it was measured in")
            val top = xs.groupingBy { it }.eachCount().maxBy { it.value }
            assertTrue(top.value < xs.size * 0.02, "$a: ${top.value} at ${top.key}")
            val p50 = xs.sorted()[xs.size / 2]
            assertTrue(p50 in p50s, "$a: p50 $p50")
        }
    }

    @Test
    fun `your first rod hit after the recast is your hit after a missed bobber, the switch from that recast not counted twice`() {
        val rng = Random(13)
        val after = ArrayList<Long>()
        val doubled = ArrayList<Long>()
        repeat(20_000) {
            val spawn = 50_000L
            val recast = spawn + 150
            val gate = FightRules.firstRodHit(spawn, recast, Long.MIN_VALUE, { TimingModel.draw(Action.BOBBER_MISS, emptyList(), null, rng) }, { error("a recast came") }) { error("") }
            // Auto Fishing's last rod click is that very recast; the hit's clock started there
            val from = FightRules.firstClickFrom(spawn, recast, Long.MIN_VALUE, Long.MIN_VALUE)
            val cross = CrossGap().notBefore(recast, from, gate) { error("no switch on top of your hit after the recast") }
            after += maxOf(gate, cross) - recast
            doubled += maxOf(gate, TimingModel.dueAt(recast, TimingModel.draw(Action.CROSS_R_TO_L, emptyList(), null, rng))) - recast
        }
        // yours (BOBBER_MISS): p50 600, measured from the recast with the switch inside
        val p50 = after.sorted()[after.size / 2]
        assertTrue(p50 in 540L .. 660L, "p50 $p50")
        // a reel of a new bite after that recast (a rod click after the hit's clock started) waits a switch
        val reel = 50_400L
        val from = FightRules.firstClickFrom(50_000L, 50_150L, Long.MIN_VALUE, Long.MIN_VALUE)
        assertTrue(CrossGap().notBefore(reel, from, 50_450L) { 300L } > 50_450L)
        assertTrue(doubled.sorted()[after.size / 2] >= p50, "the old max only ever made it later")
    }

    @Test
    fun `a held reel, cast or recast leaves the view free for a look only until it goes`() {
        assertEquals(250.0, RodClock.glanceWindowMs(Phase.CASTING, 1000, 800, null, 1250))
        assertEquals(0.0, RodClock.glanceWindowMs(Phase.REELING, 1000, 800, null, 1000), "still held: no look")
        assertEquals(Double.POSITIVE_INFINITY, RodClock.glanceWindowMs(Phase.WAITING_BITE, 1000, 800, null, 1250))
    }

    @Test
    fun `your fishing view is never taken from a move of the bot's in flight or coming to rest`() {
        assertEquals(HomeWatch.Start.TAKE, HomeWatch.atStart(hasHome = true, moving = false))
        assertEquals(HomeWatch.Start.TAKE, HomeWatch.atStart(hasHome = false, moving = false))
        // Auto Fishing on again while the move its stop let run out still turns: the view you had stays
        assertEquals(HomeWatch.Start.KEEP, HomeWatch.atStart(hasHome = true, moving = true))
        assertEquals(HomeWatch.Start.WAIT, HomeWatch.atStart(hasHome = false, moving = true))
        // your settled turn waits for a move of the bot's to rest, and is taken then
        val w = HomeWatch(settleMs = 1000)
        w.turned(0, between = true, yaw = 20.0, pitch = 0.0)
        assertNull(w.settled(1500, true, View(0.0, 0.0), View(20.0, 0.0), moving = true))
        assertEquals(View(25.0, 0.0), w.settled(1600, true, View(0.0, 0.0), View(25.0, 0.0), moving = false))
    }

    @Test
    fun `a recast held before it was due goes by its time once the hold ended, never its ticks left over`() {
        val timer = RecastTimer()
        timer.start(200)
        assertFalse(timer.tick(1050, 1200))
        timer.held()
        assertFalse(timer.tick(1100, 1200), "its ticks no longer count")
        assertFalse(timer.tick(1150, 1200))
        assertTrue(timer.tick(1200, 1200), "at its time")
        assertFalse(timer.pending)
        // nothing held it: its ticks, as a held use button
        val plain = RecastTimer()
        plain.start(200)
        assertEquals(listOf(false, false, false, true), List(4) { plain.tick(0L, Long.MAX_VALUE) })
    }

    @Test
    fun `on a real hand an idle re-aim braked by the reel holds the recast, which goes your wait after its rest, no fixed tick after it`() {
        val rng = Random(17)
        val gaps = ArrayList<Long>()
        repeat(400) { run ->
            val port = Port(0.0, 0.0)
            val clock = doubleArrayOf(0.0)
            val aim = HumanAim(port, { clock[0] }, Random(run.toLong()))
            aim.params = MotorParams(durationSigma = 0.05, multiP = List(5) { 0.0 }, slowP = 0.0)
            // an idle re-aim of 60° started before the bite's reel at 10 000; the reel brakes it ([Owner.IDLE] stop)
            var now = 10_000L - 50L * (1 + run % 4)
            clock[0] = now.toDouble()
            assertTrue(aim.start(60.0, 0.0, Owner.IDLE))
            while (now < 10_000L) {
                now += 50
                live(aim, clock, now)
            }
            aim.stop(Owner.IDLE)
            val timer = RecastTimer()
            timer.start(200)
            val recastDue = 10_000L + 200
            val held = HeldUse()
            var owed = false
            var restSeen = Long.MIN_VALUE
            var castAt = Long.MIN_VALUE
            // AutoFishing's CASTING tick: an idle move in flight holds the recast (owed once due by its time), else its
            // ticks or its time, then the wait after the rest
            while (castAt == Long.MIN_VALUE && now < 15_000L) {
                now += 50
                live(aim, clock, now)
                val due = owed || now >= recastDue
                if (aim.moving(Owner.IDLE)) {
                    if (due) owed = true else timer.held()
                    held.hold(now, due)
                    continue
                }
                if (restSeen == Long.MIN_VALUE) restSeen = now
                if (! owed && timer.tick(now, recastDue)) owed = true
                if (owed && held.go(now, true, restSeen, floor, aim.rests.toLong()) { TimingModel.draw(Action.REST_TO_RECAST, emptyList(), null, rng) }) castAt = now
            }
            assertTrue(castAt > restSeen, "run $run: the recast went out")
            gaps += castAt - restSeen
        }
        gaps.sort()
        assertTrue(gaps.first() >= floor, "never within your shortest gap after the rest: ${gaps.first()}")
        // no tick after the rest carries more than yours did within its noise (22 of 52, 42 %, on the fourth: 56 % at two sd)
        val top = gaps.groupingBy { it }.eachCount().maxBy { it.value }
        assertTrue(top.value < gaps.size * 0.56, "${top.value} of ${gaps.size} at ${top.key} ms after the tick that saw the rest")
        // yours held by a move: p10 150, p50 202, p90 444 after its rest
        assertTrue(gaps[gaps.size / 2] in 150L .. 350L, "p50 ${gaps[gaps.size / 2]}")
    }

    @Test
    fun `a reel with no bite is due by its own clock, and one due behind a screen goes your wait after its close`() {
        fun due(phase: Phase, now: Long, hook: Int?, landing: Boolean = false, recast: Boolean = false) =
            RodClock.due(phase, now, hook, 7, 1000, recast, false, 1200, 1000, 1000, 2000, 60_000.0, landing)
        assertTrue(due(Phase.WAITING_BITE, 3100, 7), "the reeled bobber's retry")
        assertFalse(due(Phase.WAITING_BITE, 2900, 7))
        assertTrue(due(Phase.WAITING_BITE, 61_100, 8), "the timeout")
        assertFalse(due(Phase.WAITING_BITE, 30_000, 8))
        assertTrue(due(Phase.WAITING_BITE, 4000, 8, landing = true), "a bobber lying out of the water")
        assertTrue(due(Phase.CASTING, 3100, 7), "the retry while casting")
        assertTrue(due(Phase.CASTING, 3100, null, recast = true), "the recast at its time")
        assertFalse(due(Phase.CASTING, 1100, null, recast = true))
        assertFalse(due(Phase.WAITING_BITE, 99_999, null))
        // the timeout ran out at 500 behind your screen, closed before the tick at 2000: your wait after a close with no bite
        val rng = Random(23)
        val gaps = ArrayList<Long>()
        repeat(2000) {
            val h = HeldUse()
            for (t in 0L .. 1950L step 50) h.hold(t, due = t >= 500)
            var t = 2000L
            while (! h.go(t, true, 2000L, floor) { TimingModel.draw(Action.SCREEN_CLOSE_TO_CAST, emptyList(), null, rng) }) t += 50
            gaps += t - 2000L
        }
        gaps.sort()
        assertTrue(gaps.first() >= 100, "never in the close's tick or the next: ${gaps.first()}")
        assertTrue(gaps[gaps.size / 2] in 450L .. 700L, "p50 ${gaps[gaps.size / 2]} (yours 599)")
    }

    @Test
    fun `a bite right after a screen closed is reeled on its own reaction, never on the wait drawn for the timeout it replaced`() {
        // the bite timeout ran out at 500 behind your screen, closed before the tick at 2000; its wait after the close drawn
        // at the first tick (100 ms here), a bite seen at 2050 with your reaction to it 300 ms
        fun reelTick(clearOnBite: Boolean): Long {
            val h = HeldUse()
            for (t in 0L .. 1950L step 50) h.hold(t, due = t >= 500)
            assertFalse(h.go(2000, true, 2000L, floor) { 100L }, "the timeout waits its reaction to the close")
            // the bite: Auto Fishing reels it now ([AutoFishing]'s WAITING_BITE drops what the screen held for the timeout)
            if (clearOnBite) h.clear()
            val biteDue = TimingModel.dueAt(2050, 300)
            var t = 2050L
            while (! h.go(t, t >= biteDue, 2000L, 0L) { 100L }) t += 50
            return t
        }
        assertEquals(2350L, reelTick(clearOnBite = true), "the bite's own reaction")
        // kept, the screen's wait for the timeout let the reel go the tick after the bite showed, its reaction never waited
        assertEquals(2100L, reelTick(clearOnBite = false))
    }

    @Test
    fun `the turn home after a cast reeled in with no bite comes your reaction to the reel later`() {
        val rng = Random(29)
        val xs = List(40_000) { TimingModel.draw(Action.REEL_TO_TURN, emptyList(), null, rng) }
        // yours: 5 of 62 in the reel's tick, 5 in the next, p50 376, p90 1434, at most 2900 ms
        val ticks = xs.map { TimingModel.dueAt(0L, it) }
        val inTick = ticks.count { it <= 0L }.toDouble() / xs.size
        val next = ticks.count { it in 1L .. 50L }.toDouble() / xs.size
        assertTrue(inTick in 0.04 .. 0.14 && next in 0.04 .. 0.14, "$inTick in the reel's tick, $next in the next")
        val s = xs.sorted()
        assertTrue(s[s.size / 2] in 300L .. 500L, "p50 ${s[s.size / 2]}")
        assertTrue(s[s.size * 9 / 10] in 900L .. 1800L, "p90 ${s[s.size * 9 / 10]}")
        assertTrue(xs.all { it <= 3000 })
        val top = xs.groupingBy { it }.eachCount().maxBy { it.value }
        assertTrue(top.value < xs.size * 0.02, "${top.value} at ${top.key}")
    }

    private class Port(var yaw: Double, var pitch: Double) : HumanAim.Port {
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

    /** One tick of the game: five frames, then the driver's tick (its HIGH-priority listener runs before Auto Fishing's). */
    private fun live(aim: HumanAim, clock: DoubleArray, now: Long) {
        for (f in 1 .. 5) {
            clock[0] = now - 50.0 + 10.0 * f
            aim.frame()
        }
        aim.tick(rest = false, anchor = null)
    }
}
