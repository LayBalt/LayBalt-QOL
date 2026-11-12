package fishing

import com.laybalt.features.impl.fishing.AutoFishing.BiteTimeout
import com.laybalt.features.impl.fishing.AutoFishing.CastAim
import com.laybalt.features.impl.fishing.AutoFishing.CastCheck
import com.laybalt.features.impl.fishing.AutoFishing.CastClock
import com.laybalt.features.impl.fishing.AutoFishing.IdleReaim
import com.laybalt.features.impl.fishing.AutoFishing.LandingCheck
import com.laybalt.features.impl.fishing.AutoFishing.MoveHalt
import com.laybalt.features.impl.fishing.AutoFishing.OffHalt
import com.laybalt.features.impl.fishing.AutoFishing.Phase
import com.laybalt.features.impl.fishing.AutoMobKiller
import com.laybalt.features.impl.fishing.BobberFlight
import com.laybalt.features.impl.fishing.HeldUse
import com.laybalt.features.impl.fishing.ViewPolicy
import com.laybalt.features.impl.fishing.AutoFishing.RodClock
import com.laybalt.features.impl.fishing.AutoFishing.ScreenPause
import com.laybalt.features.impl.fishing.HumanAim
import com.laybalt.features.impl.fishing.HumanAim.Owner
import com.laybalt.features.impl.fishing.MotorModel
import com.laybalt.features.impl.fishing.MotorModel.View
import com.laybalt.features.impl.fishing.MotorParams
import com.laybalt.features.impl.fishing.TimingModel
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.*

/**
 * Auto Fishing's idle re-aims (DESIGN-HUMAN §4.2): about every 40 s, back towards home in the motor's strokes within its
 * speed caps, ending a little short of home; and the cast after a fight, timed from the rod and the view coming back (§3.6).
 */
class AutoFishingIdleTest {
    private val grid = 0.15

    /** What PlayerUtils.rotate sets for [to] from [from]: each axis's change cut to whole mouse steps. */
    private fun onGrid(from: View, to: View): View {
        val d = to - from
        return View(from.yaw + (d.yaw - d.yaw % grid), (from.pitch + (d.pitch - d.pitch % grid)).coerceIn(- 90.0, 90.0))
    }

    private fun dist(a: View, b: View) = MotorModel.angle(a.yaw, a.pitch, b.yaw, b.pitch)

    @Test
    fun `a re-aim goal lies on the way home, a little short of it`() {
        val rng = Random(1)
        val home = View(170.0, 30.0)
        val shorts = ArrayList<Double>()
        repeat(20_000) {
            // across the yaw wrap too
            val view = View(170.0 + rng.nextDouble(- 25.0, 25.0), 30 + rng.nextDouble(- 10.0, 10.0))
            val d = dist(view, home)
            val goal = IdleReaim.goal(view, home, IdleReaim.MIN_DEG, rng)
            if (d < IdleReaim.MIN_DEG) return@repeat assertNull(goal)
            assertNotNull(goal)
            val left = dist(goal, home)
            // the way is straight in mouse space, so great-circle shares of it are a little off
            assertTrue(left <= 3.0 * 1.08 && left <= d / 2 * 1.08, "$left° left of $d°")
            assertEquals(d, dist(view, goal) + left, 0.05 * d, "on the way: no detour")
            shorts += left
        }
        shorts.sort()
        assertEquals(1.0, shorts[shorts.size / 2], 0.15, "p50 short of home")
        assertNull(IdleReaim.goal(View(10.0, 0.0), View(10.5, 0.5), IdleReaim.MIN_DEG, rng), "near home: no re-aim")
        assertNotNull(IdleReaim.goal(View(10.0, 0.0), View(11.5, 0.0), 1.0, rng), "after a missed cast a smaller drift is enough")
    }

    @Test
    fun `a re-aim's short of home is drawn under half the way, never piled on it`() {
        val rng = Random(2)
        val home = View(0.0, 0.0)
        // along the level the way is a great circle, so what is left of it is the short itself
        for (d in listOf(2.0, 3.0, 4.0, 6.0)) {
            val left = List(20_000) { dist(IdleReaim.goal(View(d, 0.0), home, IdleReaim.MIN_DEG, rng)!!, home) }
            assertTrue(left.all { it < d / 2 }, "$d°: past half the way: ${left.max()}")
            // taking the smaller of the draw and half the way stopped half of the 2° re-aims (12 % of the 4° ones) right there
            val atCap = left.count { it >= 0.99 * d / 2 }.toDouble() / left.size
            assertTrue(atCap < 0.03, "$d°: ${"%.1f".format(atCap * 100)} % within 1 % of half the way")
        }
    }

    @Test
    fun `a re-aim moves like your hand, within the caps, and ends short of home`() {
        val p = MotorParams()
        val home = View(0.0, 40.0)
        val left = ArrayList<Double>()
        repeat(400) { i ->
            val rng = Random(i)
            var view = View(rng.nextDouble(- 12.0, 12.0), 40 + rng.nextDouble(- 6.0, 6.0))
            val from = dist(view, home)
            val goal = IdleReaim.goal(view, home, IdleReaim.MIN_DEG, rng) ?: return@repeat
            val motor = MotorModel()
            motor.start(view.yaw, view.pitch, goal.yaw, goal.pitch, 0.0, p, rng)
            var t = 0.0
            while (motor.moving) {
                val dt = 1000.0 / 60
                t += dt
                assertTrue(t < 15_000, "a re-aim ends")
                val set = onGrid(view, motor.sample(t))
                // the grid holds back less than a step per axis, which a later frame may add
                assertTrue((set - view).length <= p.hardCap * dt / 1000 + grid * sqrt(2.0) + 1e-9, "step ${(set - view).length}° in ${dt}ms")
                view = set
            }
            val end = dist(view, home)
            assertTrue(end < from, "closer to home: $from -> $end")
            assertTrue(end <= 3.5, "short of home by at most 3° and the grid: $end")
            left += end
        }
        assertTrue(left.size > 300, "${left.size} re-aims")
        left.sort()
        assertTrue(left[left.size / 2] in 0.5 .. 1.6, "rest error p50 ${left[left.size / 2]}°: short of home, not on it")
    }

    @Test
    fun `re-aims come about every 40 s`() {
        val rng = Random(3)
        val gaps = List(4000) { IdleReaim.interval(rng) }.sorted()
        assertEquals(40_000.0, gaps[gaps.size / 2].toDouble(), 3000.0, "p50")
        assertTrue(gaps.first() > 3000, "never right after one: ${gaps.first()}")
        assertTrue(gaps.groupingBy { it }.eachCount().values.max() < gaps.size * 0.03, "no pile at a bound")
    }

    @Test
    fun `a re-aim never goes out with the cast, nor while your mouse turns the view, nor far from home`() {
        val rng = Random(4)
        val (first, none) = IdleReaim.schedule(1000, - 1, 900, 5.0, false, rng)
        assertFalse(none, "the first one is drawn")
        assertTrue(first > 1000 + 3000)
        // fell due while Auto Fishing was held or casting: drawn afresh in the cast's tick, no correction with the click
        val (again, cast) = IdleReaim.schedule(50_000, 30_000, 50_000, 5.0, false, rng)
        assertFalse(cast)
        assertTrue(again > 50_000 + 3000)
        assertEquals(60_000L to false, IdleReaim.schedule(50_000, 60_000, 40_000, 5.0, false, rng), "not due yet")
        val (next, due) = IdleReaim.schedule(60_000, 60_000, 40_000, 5.0, false, rng)
        assertTrue(due, "due while waiting for a bite")
        assertTrue(next > 60_000 + 3000)
        assertTrue(IdleReaim.schedule(60_000, 60_000, 40_000, IdleReaim.MAX_DEG, false, rng).second)
        // skipped (the next one drawn): your mouse turned the view, the view is one you turned to, no home yet
        assertFalse(IdleReaim.schedule(60_000, 60_000, 40_000, 5.0, true, rng).second)
        val (later, far) = IdleReaim.schedule(60_000, 60_000, 40_000, 90.0, false, rng)
        assertFalse(far)
        assertTrue(later > 60_000 + 3000)
        assertFalse(IdleReaim.schedule(60_000, 60_000, 40_000, null, false, rng).second)
    }

    @Test
    fun `a re-aim stops short of home only as far as its bobber still lands like home's`() {
        val home = View(0.0, 45.0)
        val view = View(7.0, 45.0)
        val rng = Random(5)
        repeat(200) {
            val seed = rng.nextInt()
            val drawn = IdleReaim.goal(view, home, IdleReaim.MIN_DEG, Random(seed))!!
            val short = dist(drawn, home)
            assertEquals(drawn, IdleReaim.goal(view, home, IdleReaim.MIN_DEG, Random(seed)) { true }, "lands: the drawn goal")
            assertEquals(drawn, IdleReaim.goal(view, home, IdleReaim.MIN_DEG, Random(seed)) { null }, "cannot tell: the drawn goal")
            // only 0.75 of the draw still lands: drawn again below that (the bisection finds it to a 64th of the draw), or
            // within the launch noise of home
            val shorter = dist(IdleReaim.goal(view, home, IdleReaim.MIN_DEG, Random(seed)) { dist(it, home) < short * 0.75 }!!, home)
            assertTrue(shorter > 0.0 && shorter < maxOf(short * 0.75, min(short, BobberFlight.NEAR_HOME)) * 1.02, "%.3f of %.3f".format(shorter, short))
            // nothing lands: near home, never on it
            val near = dist(IdleReaim.goal(view, home, IdleReaim.MIN_DEG, Random(seed)) { false }!!, home)
            assertTrue(near > 0.0 && near <= min(short, BobberFlight.NEAR_HOME) * 1.02, "%.3f off home".format(near))
        }
        // on home itself every time before: now spread below the bound, no value twice
        val shorts = List(5000) { dist(IdleReaim.goal(view, home, IdleReaim.MIN_DEG, rng) { false }!!, home) }
        assertTrue(shorts.groupingBy { "%.4f".format(it) }.eachCount().values.max() < 50, "piled at one undershoot")
        assertTrue(shorts.all { it > 1e-6 }, "on home: ${shorts.min()}")
    }

    @Test
    fun `a bobber lying out of water is reeled once it has lain still for your reaction, a move starts that over`() {
        // still at 1000: lying since then
        var since = LandingCheck.lyingSince(- 1, 1000, lying = true)
        assertEquals(1000L, since)
        since = LandingCheck.lyingSince(since, 1050, lying = true)
        assertEquals(1000L, since, "still lying: from when it came to rest")
        assertFalse(LandingCheck.due(since, 1600, 700), "your reaction is not over")
        assertTrue(LandingCheck.due(since, 1700, 700))
        // it moved again (or slid into the water): over, and from the next rest on
        assertEquals(- 1L, LandingCheck.lyingSince(since, 1100, lying = false))
        assertFalse(LandingCheck.due(- 1, 99_999, 700))
        // the reaction drawn per cast: yours to a bobber lying near you (cast to reel 702-2000 ms, a few ticks of flight)
        val rng = Random(21)
        val xs = List(50_000) { TimingModel.draw(TimingModel.Action.LANDING_REEL, emptyList(), null, rng) }.sorted()
        assertEquals(750.0, xs[25_000].toDouble(), 60.0, "p50")
        assertTrue(xs.first() >= 150 && xs.last() <= 3000, "${xs.first()}..${xs.last()}")
        assertTrue(xs.groupingBy { it }.eachCount().values.max() < xs.size * 0.01, "piled at one wait")
    }

    @Test
    fun `a reel whose bobber stays out is tried again after a wait drawn per reel, not at a fixed 2 s`() {
        val rng = Random(22)
        val xs = List(50_000) { TimingModel.draw(TimingModel.Action.REEL_RETRY, emptyList(), null, rng) }.sorted()
        assertEquals(2000.0, xs[25_000].toDouble(), 100.0, "p50")
        assertTrue(xs[5_000] < 1800 && xs[45_000] > 2200, "spread ${xs[5_000]}..${xs[45_000]}")
        assertTrue(xs.last() <= 3000, "under its cap: ${xs.last()}")
        // the first tick past 2 s took a third of them before (2000-2050 ms)
        assertTrue(xs.count { it in 2000L .. 2050L } < xs.size * 0.06, "piled after 2 s")
    }

    @Test
    fun `an idle move cut short by a fight or a hold is brought to rest once, frozen only for a screen, the Recorder or your death`() {
        assertEquals(CastAim.Halt.NONE, CastAim.halt(goesOn = true, moving = true, stopping = false, frozen = true), "it may go on")
        assertEquals(CastAim.Halt.NONE, CastAim.halt(goesOn = false, moving = false, stopping = false, frozen = true), "nothing in flight")
        assertEquals(CastAim.Halt.STOP, CastAim.halt(goesOn = false, moving = true, stopping = false, frozen = false), "a fight or a hold: braked")
        assertEquals(CastAim.Halt.NONE, CastAim.halt(goesOn = false, moving = true, stopping = true, frozen = false), "braking already: left to come to rest")
        assertEquals(CastAim.Halt.ABORT, CastAim.halt(goesOn = false, moving = true, stopping = false, frozen = true), "a screen: frozen")
        assertEquals(CastAim.Halt.ABORT, CastAim.halt(goesOn = false, moving = true, stopping = true, frozen = true), "a screen while braking: frozen")
    }

    /** A view that keeps changes on the mouse grid like the game's fixRot. */
    private class FakePort(var yaw: Double, var pitch: Double) : HumanAim.Port {
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

    /** One client tick of 50 ms at [now] on [aim]: its frames and its tick sample, as HumanAim's hooks drive it. */
    private fun live(aim: HumanAim, clock: DoubleArray, now: Long) {
        for (f in 1 .. 5) {
            clock[0] = now - 50.0 + 10.0 * f
            aim.frame()
        }
        aim.tick(rest = false, anchor = null)
    }

    @Test
    fun `an idle move or a look cut short by a hold or the killer's loadout comes to rest once your hand reacted, never in that tick`() {
        for (owner in listOf(Owner.IDLE, Owner.GLANCE)) {
            val port = FakePort(0.0, 0.0)
            val clock = doubleArrayOf(0.0)
            val aim = HumanAim(port, { clock[0] }, Random(3))
            aim.params = MotorParams(durationSigma = 0.05, multiP = List(5) { 0.0 }, slowP = 0.0)
            assertTrue(aim.start(120.0, 10.0, owner))
            val halt = MoveHalt(owner)
            var draws = 0
            var now = 0L
            var views = 0
            // the hold from the first tick on: the move turns on through your reaction (drawn once, 150 ms), then brakes
            while (now < 150L - 25L) {
                val before = port.view()
                assertFalse(halt.tick(aim, now, CastAim.halt(false, aim.moving(owner), aim.stopping, false)) { draws++; 150L }, "$owner ended at $now")
                now += 50
                live(aim, clock, now)
                if (port.view() != before) views++
                assertTrue(aim.moving(owner) && ! aim.stopping, "$owner still turning at $now")
            }
            assertTrue(views >= 2 && dist(View(0.0, 0.0), port.view()) > 5.0, "$owner: the view went on turning ($views ticks, to ${port.view()})")
            assertTrue(halt.tick(aim, now, CastAim.halt(false, aim.moving(owner), aim.stopping, false)) { draws++; 150L }, "$owner: brought to rest at $now")
            assertTrue(aim.stopping && aim.moving(owner), "$owner: braking, not frozen")
            assertEquals(1, draws, "$owner: one reaction drawn")
            // braking: left to come to rest, nothing drawn again
            assertFalse(halt.tick(aim, now + 50, CastAim.halt(false, aim.moving(owner), aim.stopping, false)) { draws++; 150L })
            assertEquals(1, draws)
        }
        // a hold that let go before the reaction: the next one draws its own
        val aim = HumanAim(FakePort(0.0, 0.0), { 0.0 }, Random(4))
        aim.start(90.0, 0.0, Owner.GLANCE)
        val halt = MoveHalt(Owner.GLANCE)
        var draws = 0
        assertFalse(halt.tick(aim, 0L, CastAim.Halt.STOP) { draws++; 500L })
        assertFalse(halt.tick(aim, 50L, CastAim.Halt.NONE) { draws++; 500L })
        assertFalse(halt.tick(aim, 100L, CastAim.Halt.STOP) { draws++; 500L })
        assertEquals(2, draws)
        assertFalse(halt.tick(aim, 500L, CastAim.Halt.STOP) { draws++; 500L }, "the second reaction runs from its own tick")
        assertTrue(halt.tick(aim, 600L, CastAim.Halt.STOP) { draws++; 500L })
    }

    @Test
    fun `a look or an idle move stops dead for a screen, the Recorder, your death or your own hand`() {
        val aim = HumanAim(FakePort(0.0, 0.0), { 0.0 }, Random(5))
        aim.start(90.0, 0.0, Owner.GLANCE)
        val halt = MoveHalt(Owner.GLANCE)
        var draws = 0
        assertTrue(halt.tick(aim, 0L, CastAim.halt(false, aim.moving(Owner.GLANCE), aim.stopping, frozen = true)) { draws++; 300L })
        assertFalse(aim.moving, "stopped dead in the tick")
        assertEquals(0, draws, "no reaction")
        // another owner's move is never touched
        aim.start(90.0, 0.0, Owner.KILLER)
        MoveHalt(Owner.IDLE).tick(aim, 0L, CastAim.Halt.ABORT) { 0L }
        assertTrue(aim.moving(Owner.KILLER))
    }

    @Test
    fun `turned off by your key or a rare catch, Auto Fishing's move turns on until your hand reacted, then comes to rest`() {
        for (owner in listOf(Owner.IDLE, Owner.GLANCE)) {
            val port = FakePort(0.0, 0.0)
            val clock = doubleArrayOf(0.0)
            val aim = HumanAim(port, { clock[0] }, Random(6))
            aim.params = MotorParams(durationSigma = 0.05, multiP = List(5) { 0.0 }, slowP = 0.0)
            aim.start(150.0, - 20.0, owner)
            val off = OffHalt()
            off.arm(aim, 0L, atOnce = false) { 150L }
            assertTrue(off.pending)
            var now = 0L
            var turned = 0
            while (now < 150L - 25L) {
                val before = port.view()
                off.tick(aim, now, frozen = false)
                assertTrue(aim.moving(owner) && ! aim.stopping, "$owner: still turning at $now")
                now += 50
                live(aim, clock, now)
                if (port.view() != before) turned++
            }
            assertTrue(turned >= 2 && dist(View(0.0, 0.0), port.view()) > 5.0, "$owner: the view turned on ($turned ticks, to ${port.view()})")
            off.tick(aim, now, frozen = false)
            assertTrue(aim.stopping && aim.moving(owner), "$owner: braking at $now, not frozen")
            assertFalse(off.pending)
        }
        // a world change or your death: at once
        val aim = HumanAim(FakePort(0.0, 0.0), { 0.0 }, Random(7))
        aim.start(90.0, 0.0, Owner.IDLE)
        val off = OffHalt()
        off.arm(aim, 0L, atOnce = true) { error("no reaction at once") }
        assertFalse(aim.moving)
        assertFalse(off.pending)
        // a screen opening while the hand still turns: stopped dead then
        aim.start(90.0, 0.0, Owner.GLANCE)
        off.arm(aim, 0L, atOnce = false) { 340L }
        off.tick(aim, 100L, frozen = true)
        assertFalse(aim.moving)
        // another move took the view meanwhile (the killer's, or the next run's after Auto Fishing came back on): untouched
        aim.start(90.0, 0.0, Owner.IDLE)
        off.arm(aim, 0L, atOnce = false) { 340L }
        aim.start(- 40.0, 5.0, Owner.IDLE)
        off.tick(aim, 400L, frozen = true)
        assertTrue(aim.moving(Owner.IDLE) && ! aim.stopping, "a later move of the same owner is not the one it was armed for")
        assertFalse(off.pending)
        // a killer move in flight: not Auto Fishing's
        aim.start(10.0, 0.0, Owner.KILLER)
        off.arm(aim, 0L, atOnce = true) { 340L }
        assertTrue(aim.moving(Owner.KILLER))
    }

    @Test
    fun `a timed cast from a view whose bobber lands elsewhere turns home first`() {
        var asked = 0
        val elsewhere = { asked++; false }
        assertEquals(true, CastCheck.turnsHome(10.0, allowed = true, userTurned = false, lands = elsewhere))
        assertEquals(false, CastCheck.turnsHome(10.0, allowed = true, userTurned = false) { true }, "lands like home")
        assertEquals(false, CastCheck.turnsHome(10.0, allowed = true, userTurned = false) { null }, "cannot tell: casts as before")
        asked = 0
        assertEquals(false, CastCheck.turnsHome(CastCheck.PRE_CAST_DEG, allowed = true, userTurned = false, lands = elsewhere), "close to home")
        assertEquals(false, CastCheck.turnsHome(null, allowed = true, userTurned = false, lands = elsewhere), "no home")
        // the check cannot act: the cast is not held for it, and the check is not used up
        assertNull(CastCheck.turnsHome(10.0, allowed = false, userTurned = false, lands = elsewhere), "a screen, the Recorder")
        assertNull(CastCheck.turnsHome(10.0, allowed = true, userTurned = true, lands = elsewhere), "your mouse turns the view")
        assertEquals(0, asked, "no flight without a reason")
    }

    @Test
    fun `the recast after a catch from a view a look left away turns home first, and when the flight cannot tell only far off`() {
        var asked = 0
        val elsewhere = { asked++; false }
        // a look given up 25 deg up without its return: no look out any more holds this recast
        assertEquals(true, CastCheck.recastTurnsHome(25.0, allowed = true, userTurned = false, lands = elsewhere))
        assertEquals(true, CastCheck.recastTurnsHome(25.0, allowed = true, userTurned = false) { null }, "cannot tell: only near home casts")
        assertEquals(false, CastCheck.recastTurnsHome(5.0, allowed = true, userTurned = false) { null }, "within the fluid fallback")
        assertEquals(false, CastCheck.recastTurnsHome(25.0, allowed = true, userTurned = false) { true }, "lands in home's water: it goes out")
        asked = 0
        assertEquals(false, CastCheck.recastTurnsHome(CastCheck.PRE_CAST_DEG, allowed = true, userTurned = false, lands = elsewhere), "at home after the reel")
        assertEquals(false, CastCheck.recastTurnsHome(null, allowed = true, userTurned = false, lands = elsewhere), "no home")
        // put off, not used up: checked again on the recast's next tick
        assertNull(CastCheck.recastTurnsHome(25.0, allowed = false, userTurned = false, lands = elsewhere), "a screen, the Recorder")
        assertNull(CastCheck.recastTurnsHome(25.0, allowed = true, userTurned = true, lands = elsewhere), "your mouse turns the view")
        assertEquals(0, asked, "no flight without a reason")
    }

    @Test
    fun `a turn home for Auto Fishing's own cast goes on through a fight phase that leaves it to fish, a move or a fought target of the killer's never`() {
        // REACT waiting for this very recast, or the end of a fight only a look kept open: the killer is busy, no move of it
        assertTrue(CastAim.allowed(free = true, killerMoving = false, killerAims = false), "the cast's turn home")
        assertFalse(CastAim.motion(free = true, killerMoving = false, killerBusy = true), "but no idle motion of Auto Fishing's")
        assertTrue(CastAim.motion(free = true, killerMoving = false, killerBusy = false), "between fights")
        // a move of the killer's fight in flight (its turn back, a look) or anything that holds Auto Fishing wins
        assertFalse(CastAim.allowed(free = true, killerMoving = true, killerAims = false))
        assertFalse(CastAim.allowed(free = false, killerMoving = false, killerAims = false), "a screen, the Recorder, a hold")
        assertFalse(CastAim.motion(free = false, killerMoving = false, killerBusy = false))
        // a rod fight's target fought with the view left away and the creature under it: no tug home and back mid-fight
        assertFalse(CastAim.allowed(free = true, killerMoving = false, killerAims = true))
        assertFalse(CastAim.goesOn(motion = false, forCast = true, castAim = CastAim.allowed(free = true, killerMoving = false, killerAims = true)))
        // the idle move in flight: the cast's turn home goes on while it may, a Shake Head re-aim stops with the fight
        assertTrue(CastAim.goesOn(motion = false, forCast = true, castAim = true))
        assertFalse(CastAim.goesOn(motion = false, forCast = false, castAim = true))
        assertFalse(CastAim.goesOn(motion = false, forCast = true, castAim = false))
        assertTrue(CastAim.goesOn(motion = true, forCast = false, castAim = true))
    }

    @Test
    fun `a cast without a bite is reeled after a timeout drawn per cast, never piled at its cap`() {
        val rng = Random(9)
        for (seconds in listOf(15.0, 60.0, 180.0)) {
            val t = seconds * 1000
            val xs = List(50_000) { BiteTimeout.draw(seconds, rng) }.sorted()
            assertTrue(xs.all { it > 0.0 && it <= BiteTimeout.CAP * t }, "$seconds s: ${xs.first()}..${xs.last()}")
            assertEquals(t, xs[25_000], t * 0.02, "around the setting")
            assertTrue(xs[5_000] < 0.85 * t && xs[45_000] > 1.15 * t, "$seconds s: spread ${xs[5_000]}..${xs[45_000]}")
            // clamped, about 2 % would sit right at the cap
            assertTrue(xs.count { it >= 0.999 * BiteTimeout.CAP * t } < xs.size / 500, "$seconds s: piled at the cap")
        }
    }

    @Test
    fun `looks around keep to the reel and the recast, and a loadout menu waits for them`() {
        // reeling: free until a tick after the reel; casting: until the recast after a catch, else the timed cast
        assertEquals(300.0 + TimingModel.TICK_MS, RodClock.glanceWindowMs(Phase.REELING, 1000, 1300, null))
        assertEquals(150.0, RodClock.glanceWindowMs(Phase.CASTING, 1000, 5000, 1150))
        assertEquals(4000.0, RodClock.glanceWindowMs(Phase.CASTING, 1000, 5000, null))
        assertEquals(- 200.0, RodClock.glanceWindowMs(Phase.CASTING, 1000, 800, null), "a cast overdue: the look comes back at once")
        for (p in listOf(Phase.WAITING_BITE, Phase.IDLE)) assertEquals(Double.POSITIVE_INFINITY, RodClock.glanceWindowMs(p, 1000, 1300, 1150), "$p")
        assertTrue(RodClock.busy(Phase.REELING), "a bite being reeled in")
        assertTrue(RodClock.busy(Phase.CASTING), "a cast or recast to come")
        assertFalse(RodClock.busy(Phase.WAITING_BITE), "waiting for a bite: the menu may go")
        assertFalse(RodClock.busy(Phase.IDLE), "held or no rod in hand")
    }

    @Test
    fun `a container screen stops Auto Fishing even with Pause In Menus off`() {
        assertTrue(ScreenPause.pauses(pauseInMenus = true, container = false), "chat with Pause In Menus on")
        assertTrue(ScreenPause.pauses(pauseInMenus = false, container = true), "a chest or the SkyBlock menu: no rod is used there")
        assertTrue(ScreenPause.pauses(pauseInMenus = true, container = true))
        assertFalse(ScreenPause.pauses(pauseInMenus = false, container = false), "chat with Pause In Menus off: fishing goes on")
    }

    @Test
    fun `the cast after a fight waits for the later of the rod and the view coming back`() {
        val rodToCast = { 300L }
        val settle = { 500L }
        val castWait = { 200L }
        // rod back at 1000, the turn back ended at 1400: the view's clock is later
        assertEquals(1400L + 500 - 25, CastClock.afterHold(1500, 0, 1000, 1400, rodToCast, settle, castWait))
        // the rod came back last
        assertEquals(2000L + 300 - 25, CastClock.afterHold(2100, 0, 2000, 500, rodToCast, settle, castWait))
        // the rod in hand and the view still since before the hold: the plain cast delay
        assertEquals(3000L + 200 - 25, CastClock.afterHold(3000, 2000, 1000, 1500, rodToCast, settle, castWait))
        // only the view turned in the fight (a rod fight)
        assertEquals(2500L + 500 - 25, CastClock.afterHold(2600, 2000, 1000, 2500, rodToCast, settle, castWait))
    }

    @Test
    fun `a re-aim or a turn home due behind a screen starts your reaction to its close later, not always in its first tick`() {
        val rng = Random(31)
        // a screen open from 1000 to 5000 (the first tick without it), a re-aim due at 3000 behind it; Auto Fishing's own
        // gate, as the killer's: one move reaction per close
        val starts = ArrayList<Long>()
        repeat(4000) {
            val gate = AutoMobKiller.ScreenGate { TimingModel.draw(it.action, emptyList(), null, rng) }
            gate.closed(5000)
            var t = 5000L
            while (! IdleReaim.schedule(t, 3000, 0, 5.0, false, rng, gate.ready(AutoMobKiller.ScreenGate.Kind.MOVE, t)).second) t += 50
            starts += t - 5000
        }
        starts.sort()
        // yours: 4 of 14 moves in the close's own tick, the rest 48-1995 ms after it (p50 245); before: all 14 in that tick
        val inTick = starts.count { it == 0L } / 4000.0
        assertTrue(inTick in 0.2 .. 0.4, "$inTick in the close's tick")
        val rest = starts.filter { it > 0 }
        assertTrue(rest[rest.size / 2] in 150L .. 400L, "p50 of the rest ${rest[rest.size / 2]}")
        assertTrue(starts.all { it <= 3000 })
        // a re-aim not due yet is not moved by the gate, one due waits for it
        assertEquals(9000L to false, IdleReaim.schedule(6000, 9000, 0, 5.0, false, rng, moveReady = false))
        assertEquals(3000L to false, IdleReaim.schedule(6000, 3000, 0, 5.0, false, rng, moveReady = false))
        // the turn home the cast check asks for waits the same reaction, the check not used up; nothing else waits for it
        assertTrue(CastCheck.waitsForScreen(true, moveReady = false))
        assertFalse(CastCheck.waitsForScreen(true, moveReady = true))
        assertFalse(CastCheck.waitsForScreen(false, moveReady = false), "no turn: the cast goes as before")
        assertFalse(CastCheck.waitsForScreen(null, moveReady = false), "the check cannot act: not used up, as before")
    }

    @Test
    fun `a cast after a missed cast's reel waits for the turn home only once it started, never a rest wait without a rest`() {
        assertEquals(false, CastCheck.moveHold(idleMoving = false, turnOwed = true, due = true, forCast = false), "the turn has not started: owed nothing")
        assertEquals(true, CastCheck.moveHold(idleMoving = true, turnOwed = false, due = false, forCast = true), "the turn home for it")
        assertEquals(true, CastCheck.moveHold(idleMoving = true, turnOwed = false, due = true, forCast = false), "due while an idle move turns")
        assertEquals(false, CastCheck.moveHold(idleMoving = true, turnOwed = false, due = false, forCast = false))
        assertNull(CastCheck.moveHold(idleMoving = false, turnOwed = false, due = true, forCast = true))
        assertFalse(CastCheck.missTurnNeeded(null))
        assertFalse(CastCheck.missTurnNeeded(0.6), "already home: no turn, nothing waits for one")
        assertTrue(CastCheck.missTurnNeeded(1.0))
        assertTrue(CastCheck.missTurnNeeded(5.0))

        // AutoFishing's CASTING ticks after a missed cast's reel at 0, its cast due at 200 by its own clock, the turn home
        // after your reaction to the reel: returns the cast's tick
        fun castTick(distance: Double, allowed: Boolean, reaimAt: Long, rng: Random): Long {
            val held = HeldUse()
            var reaimHome = true
            var forCast = false
            var moveEnd = Long.MAX_VALUE
            var restAt = - 10_000L
            var restId = 0L
            var t = 50L
            while (t < 20_000) {
                if (t >= moveEnd) {
                    moveEnd = Long.MAX_VALUE
                    restAt = t
                    restId ++
                }
                // turnHomeAfterMiss
                if (reaimHome && ! CastCheck.missTurnNeeded(distance)) reaimHome = false
                if (reaimHome && t >= reaimAt && allowed) {
                    reaimHome = false
                    forCast = true
                    moveEnd = t + 300
                }
                val due = t >= 200
                val hold = CastCheck.moveHold(moveEnd != Long.MAX_VALUE, reaimHome && t < reaimAt, due, forCast)
                if (hold != null) held.hold(t, hold)
                else if (held.go(t, due, restAt, ViewPolicy.MIN_GAP_MS, restId) { TimingModel.draw(TimingModel.Action.REST_TO_CAST, emptyList(), null, rng) }) return t
                t += 50
            }
            error("never cast")
        }
        val rng = Random(37)
        repeat(500) {
            val reaimAt = TimingModel.dueAt(0, TimingModel.draw(TimingModel.Action.REEL_TO_TURN, emptyList(), null, rng))
            // the view home already: no turn, the cast on its own clock (it went about 0.9-1 s after the reel before)
            assertEquals(200L, castTick(0.5, true, reaimAt, rng))
            // a turn that cannot start (the killer aims): held only until that reaction ran, never a rest wait on top
            assertEquals(maxOf(200L, (reaimAt + 49) / 50 * 50), castTick(5.0, false, reaimAt, rng))
            // a turn home: the cast goes your wait after its rest, never sooner than your shortest gap
            val turnStart = maxOf(50L, (reaimAt + 49) / 50 * 50)
            assertTrue(castTick(5.0, true, reaimAt, rng) >= turnStart + 300 + ViewPolicy.MIN_GAP_MS)
        }
    }
}
