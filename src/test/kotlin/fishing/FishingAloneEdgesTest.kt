package fishing

import com.laybalt.features.impl.fishing.AutoFishing
import com.laybalt.features.impl.fishing.AutoFishing.CastCheck
import com.laybalt.features.impl.fishing.AutoFishing.CastClock
import com.laybalt.features.impl.fishing.AutoFishing.LookPhase
import com.laybalt.features.impl.fishing.AutoMobKiller.FightRules
import com.laybalt.features.impl.fishing.AutoMobKiller.FightRules.HomeTurns
import com.laybalt.features.impl.fishing.AutoMobKiller.HomeWatch
import com.laybalt.features.impl.fishing.GlancePolicy
import com.laybalt.features.impl.fishing.HumanAim
import com.laybalt.features.impl.fishing.HumanAim.Owner
import com.laybalt.features.impl.fishing.MotorModel
import com.laybalt.features.impl.fishing.MotorModel.View
import com.laybalt.features.impl.fishing.MotorParams
import com.laybalt.features.impl.fishing.SeaCreatureTracker.KeptClaims
import com.laybalt.features.impl.fishing.TimingModel
import com.laybalt.features.impl.fishing.ViewPolicy.Decision
import com.laybalt.features.impl.fishing.ViewPolicy.TurnBack
import kotlin.random.Random
import kotlin.test.*

/**
 * Round 12: the killer's turn back holds Auto Fishing only while it turns the view (its slow tail is Auto Fishing's own
 * tail rule's, the settle drawn from the last moving tick, never piled on the release); Auto Fishing's looks with your
 * creatures alive that nobody fights are your waiting ones; with the killer off the view is yours, fight or not, and where
 * your mouse rests is your fishing view; your creatures are yours again after the tracker stopped a while in the same world.
 */
class FishingAloneEdgesTest {
    private class Port(var yaw: Double, var pitch: Double) : HumanAim.Port {
        override val grid = 0.15
        override fun view() = View(yaw, pitch)
        override fun set(yaw: Double, pitch: Double): View {
            this.yaw = yaw
            this.pitch = pitch
            return view()
        }
    }

    @Test
    fun `the turn back's slow tail is no hold of the fight's, its turning and the fight's other moves are`() {
        assertTrue(FightRules.backTail(turnBack = true, backDone = true, moving = true, turning = false))
        assertFalse(FightRules.backTail(turnBack = true, backDone = true, moving = true, turning = true), "still turning the view")
        assertFalse(FightRules.backTail(turnBack = true, backDone = false, moving = true, turning = false), "a move before the turn back's own")
        assertFalse(FightRules.backTail(turnBack = false, backDone = true, moving = true, turning = false), "a fight's move (or a look) in its tail")
        assertFalse(FightRules.backTail(turnBack = true, backDone = true, moving = false, turning = false), "at rest: the fight finishes")
        // in that tail the cast goes only where its bobber lands like one from your fishing view, else waits for the rest
        assertTrue(CastCheck.tailHolds(true) { false })
        assertFalse(CastCheck.tailHolds(true) { true })
    }

    @Test
    fun `after a turn back the cast is released at its last moving tick and goes your settle after it, never piled on the release`() {
        var tails = 0
        var oldPiled = 0
        var runs = 0
        val gaps = ArrayList<Long>()
        repeat(400) { run ->
            var clock = 0.0
            var t = 0L
            val port = Port(60.0, 10.0)
            val aim = HumanAim(port, { clock }, Random(run.toLong())).also { it.params = MotorParams() }
            fun next() {
                while (clock + 7.0 < t + 50) {
                    clock += 7.0
                    aim.frame()
                }
                t += 50
                clock = t.toDouble()
                aim.tick(rest = false, anchor = null)
            }
            // the turn back due at 500 to your fishing view at 0/30, as AutoMobKiller's driveTurnBack runs it
            val back = TurnBack(TurnBack.Kind.NOW, 500L, 2.0)
            var last: View = port.view()
            var movedAt = Long.MIN_VALUE
            var release = - 1L
            var rest = - 1L
            while (t < 6000 && (release < 0 || rest < 0)) {
                next()
                val v = port.view()
                // Auto Fishing's watchView: the last tick the view turned faster than 15 deg/s
                if (MotorModel.angle(last.yaw, last.pitch, v.yaw, v.pitch) > 0.75) movedAt = t
                last = v
                if (back.update(t, aim.turning, aim.turnEndAt(t)) == Decision.START) {
                    assertTrue(aim.start(0.0, 30.0, Owner.KILLER))
                    back.begun(t, aim.plannedMs)
                }
                // AutoMobKiller.holdsAutoFishing in the turn back's phase (the fight's other holds are over by then)
                val holds = ! FightRules.backTail(true, back.done, aim.movingInFight(), aim.turning) && (back.started && ! back.done || aim.movingInFight())
                if (release < 0 && back.started && ! holds) release = t
                if (rest < 0 && back.started && back.done && ! aim.movingInFight()) rest = t
            }
            assertTrue(release in 1 .. rest, "run $run: released by the rest at the latest ($release, $rest)")
            assertTrue(release >= movedAt, "run $run: never while it still turns the view")
            runs++
            if (release < rest) tails++
            // the settle (your turn back to cast: default p50 348) from that last moving tick, drawn after the release
            val rng = Random(run * 7L + 1)
            val settle = { TimingModel.lognormal(348.0, 1.0, rng, 4000.0).toLong() }
            val at = CastClock.afterHold(release, 0L, - 1L, movedAt, { error("no rod back") }, settle) { error("the view moved") }
            assertTrue(at > release, "never on the release tick")
            // the tick it goes on, from the last moving tick
            gaps += (at + 49) / 50 * 50 - movedAt
            // round 11: held to the motor's rest, the settle from the last moving tick piled on that tick
            val old = maxOf(TimingModel.dueAt(movedAt, settle()), rest.toLong())
            if (old <= rest && rest > movedAt + 50) oldPiled++
        }
        assertTrue(tails > runs / 10, "turn backs with a slow tail to rest: $tails of $runs")
        assertTrue(oldPiled > 0, "round 11 piled casts on the rest's tick")
        // no tick after the last moving tick carries the casts (a pile would sit on the release)
        val top = gaps.groupingBy { it }.eachCount().maxBy { it.value }
        assertTrue(top.value < gaps.size * 0.15, "${top.value} of ${gaps.size} at ${top.key}")
    }

    @Test
    fun `the settle is drawn again until it falls after the release, none of it waited for when the view settled long before`() {
        val seq = ArrayDeque(listOf(40L, 70L, 300L))
        // last moving tick at 1000, released at 1050: 40 and 70 fall at or before the release (on its tick), 300 is taken
        assertEquals(1000L + 300 - 25, CastClock.settled(1050L, 1000L) { seq.removeFirst() })
        assertNull(CastClock.settled(9000L, 1000L) { 300L }, "settled long before the release")
        // the rod back during the hold still times it then
        assertEquals(8000L + 300 - 25, CastClock.afterHold(9000L, 0L, 8000L, 1000L, { 300L }, { 300L }) { error("no") })
        // neither: the plain cast delay from the release
        assertEquals(9000L + 200 - 25, CastClock.afterHold(9000L, 5000L, 1000L, 1000L, { 300L }, { 300L }) { 200L })
    }

    @Test
    fun `Auto Fishing's looks with your creatures alive that nobody fights are your waiting ones`() {
        // yours: 0.60 looks a minute with your creatures alive and no attack within 2 s (18.4 min), 0.53 with none alive,
        // 0.20 in fights the bobber or an area weapon decides
        assertEquals(GlancePolicy.Phase.WAITING, LookPhase.of(AutoFishing.Phase.WAITING_BITE, afterKill = false))
        assertEquals(GlancePolicy.Phase.WAITING, LookPhase.of(AutoFishing.Phase.WAITING_BITE, afterKill = true))
        assertEquals(GlancePolicy.Phase.WAITING, LookPhase.of(AutoFishing.Phase.REELING, afterKill = false))
        assertEquals(GlancePolicy.Phase.AFTER_KILL, LookPhase.of(AutoFishing.Phase.CASTING, afterKill = true))
        assertEquals(GlancePolicy.Phase.WAITING, LookPhase.of(AutoFishing.Phase.CASTING, afterKill = false))
        assertNull(LookPhase.of(AutoFishing.Phase.IDLE, afterKill = false))
    }

    @Test
    fun `with the killer off the view is yours, fight or not, and where your mouse settles is where Auto Fishing fishes`() {
        assertEquals(HomeTurns.FISHING, FightRules.homeTurns(killer = false, alive = false))
        assertEquals(HomeTurns.FISHING, FightRules.homeTurns(killer = true, alive = false))
        assertEquals(HomeTurns.FIGHT, FightRules.homeTurns(killer = true, alive = true), "the killer's, or one it leaves alone, as ever")
        // round 12 held or forgot your turns with your creatures alive and the killer off, so Auto Fishing's turns home
        // pulled the view off your creature to the fishing view you left: with the killer off the view is yours again
        assertEquals(HomeTurns.FISHING, FightRules.homeTurns(killer = false, alive = true))
        val home = View(0.0, 30.0)
        val fight = View(70.0, 5.0)
        // the killer off, a creature of yours up: you turn to it and rest there a second, it is where you fish from now on
        val w = HomeWatch(1000L)
        w.turned(10_000, true, 70.0, - 25.0)
        assertNull(w.settled(10_900, true, home, fight), "not settled yet")
        assertEquals(fight, w.settled(11_000, true, home, fight))
        // the killer on: its fight forgets a turn made in it
        val k = HomeWatch(1000L)
        k.turned(10_000, false, 70.0, - 25.0)
        assertNull(k.settled(11_500, false, home, fight))
        assertNull(k.settled(13_000, true, home, fight), "nothing of that fight is taken after it")
        // a held turn is taken once it may be, the view then (HomeWatch's own hold, [pending])
        val v = HomeWatch(1000L)
        val elsewhere = View(- 40.0, 25.0)
        v.turned(20_000, true, - 40.0, - 5.0)
        assertNull(v.settled(21_500, true, home, elsewhere, pending = true))
        assertEquals(elsewhere, v.settled(40_000, true, home, elsewhere, pending = false))
        // a turn made while a move of the bot's still turns the view (or a look keeps it away) is taken once that rested
        val m = HomeWatch(1000L)
        m.turned(70_000, true, 30.0, 0.0)
        assertNull(m.settled(71_000, true, home, View(30.0, 30.0), moving = true))
        assertEquals(View(31.0, 30.0), m.settled(71_050, true, home, View(31.0, 30.0)))
    }

    @Test
    fun `your creatures alive when the tracker stopped are yours again in the same world, bounded, dropped once seen`() {
        val k = KeptClaims(max = 3)
        k.keep(10, KeptClaims.Claim("Sea Emperor", "", false))
        k.keep(11, KeptClaims.Claim("Bayou Sludgling", "Bayou Sludge", false))
        // a nametag with that id naming another creature is not it
        assertNull(k.take(10, "Squid"))
        assertNull(k.take(10, "Sea Emperor"), "dropped once seen")
        val child = assertNotNull(k.take(11, "Bayou Sludgling"))
        assertEquals("Bayou Sludge", child.parent)
        assertNull(k.take(11, "Bayou Sludgling"))
        // never more than its bound: the oldest go first
        for (id in 1 .. 5) k.keep(id, KeptClaims.Claim("Night Squid", "", true))
        assertEquals(3, k.size)
        assertNull(k.take(1, "Night Squid"))
        assertTrue(assertNotNull(k.take(5, "Night Squid")).early)
        // a world change forgets them all
        k.clear()
        assertEquals(0, k.size)
        assertNull(k.take(4, "Night Squid"))
        assertEquals(64, KeptClaims.MAX_KEPT)
    }
}
