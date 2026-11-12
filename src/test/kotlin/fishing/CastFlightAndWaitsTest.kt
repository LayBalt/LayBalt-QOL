package fishing

import com.laybalt.features.impl.fishing.AutoFishing.CastPath
import com.laybalt.features.impl.fishing.AutoFishing.CastWay
import com.laybalt.features.impl.fishing.BobberFlight
import com.laybalt.features.impl.fishing.BobberFlight.Blocks
import com.laybalt.features.impl.fishing.BobberFlight.Medium
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import kotlin.test.*

/**
 * Round 12: the bobber's flight is weighed with your spread at its distance from the hook's spawn (none there: a player
 * beside or behind you never holds a cast), your creature shields only where every bobber of that spread meets it, and
 * a cast's wait for its flight starts anew on the flight a re-aim turned to, its re-aim's reaction anew on a new block.
 */
class CastFlightAndWaitsTest {
    /** A creature- or player-sized box (0.6 wide, 1.8 high) with its feet's centre at [x], [y], [z]. */
    private fun mob(x: Double, y: Double, z: Double, half: Double = 0.3) = AABB(x - half, y, z - half, x + half, y + 1.8, z + half)

    /** Open water from y 62 down. */
    private val water = Blocks { _, y, _ -> if (y <= 62) Medium.WATER else Medium.AIR }

    /** The real flight Auto Fishing checks: BobberFlight's track from your feet at [x], 64, [z], view yaw 0 (+z), pitch 30. */
    private fun flight(x: Double = 0.5, z: Double = 0.5): List<CastPath.Step> {
        val steps = ArrayList<CastPath.Step>()
        val landing = BobberFlight.fly(x, 64.0, z, 0.0, 30.0, false, water) { tick, x0, y0, z0, x1, y1, z1 ->
            steps += CastPath.Step(tick, Vec3(x0, y0, z0), Vec3(x1, y1, z1))
        }
        return CastPath.flight(steps, landing.contactTick)
    }

    @Test
    fun `your spread grows with the distance along the flight, from none at the hook's spawn to its far-field p95`() {
        assertEquals(0.0, CastPath.spread(0.0))
        assertEquals(0.0, CastPath.spread(- 1.0))
        // side / along p95 0.070 over 459 casts of yours: 0.21 m at 3 m (yours under 7 m: p90 0.20), 0.56 at 8 m
        assertEquals(0.21, CastPath.spread(3.0), 1e-9)
        assertEquals(0.56, CastPath.spread(8.0), 1e-9)
        // never past the p95 of all your landings
        assertEquals(CastPath.SPREAD, CastPath.spread(10.6), 0.01)
        assertEquals(CastPath.SPREAD, CastPath.spread(30.0))
        var last = - 1.0
        for (d in 0 .. 300) {
            val s = CastPath.spread(d / 10.0)
            assertTrue(s >= last, "grows")
            last = s
        }
    }

    @Test
    fun `a player or creature beside or behind you never holds a cast, one on the flight near you does`() {
        val f = flight()
        assertTrue(f.isNotEmpty())
        // the hook spawns 0.16 m to the right of your eye (x 0.34 here) and flies along +z
        val left = mob(1.5, 64.0, 0.5)
        val right = mob(- 0.5, 64.0, 0.5)
        val behind = mob(0.5, 64.0, - 0.5)
        val behindRight = mob(- 0.3, 64.0, - 0.4)
        for (b in listOf(left, right, behind, behindRight)) assertFalse(CastPath.blocked(f, listOf(b to false)), "not on the flight: $b")
        assertFalse(CastPath.blocked(f, listOf(left to false, right to false, behind to false, behindRight to false)), "all of them at a shared spot")
        // with your far-field spread all along the flight (round 11) every one of them held every cast
        val old = { b: AABB -> f.any { s -> b.inflate(CastPath.HOOK_HIT + CastPath.SPREAD).let { it.contains(s.from) || it.clip(s.from, s.to).isPresent } } }
        assertTrue(listOf(left, right, behind).all(old))
        // one 2 m ahead in the flight's way holds it, as one the hook alone meets
        assertTrue(CastPath.blocked(f, listOf(mob(0.34, 64.0, 2.5) to false)))
        // one 0.35 m beside the mean line 2 m out: within the hook and your spread there (0.3 + 0.14 beyond its box)
        assertTrue(CastPath.blocked(f, listOf(mob(0.34 + 0.6 + 0.1, 64.0, 2.5) to false)))
        // one 1.2 m beside it there: no bobber of yours went that far sideways that near
        assertFalse(CastPath.blocked(f, listOf(mob(0.34 + 1.2, 64.0, 2.5) to false)))
    }

    @Test
    fun `your creature shields what lies behind it only where every bobber of your spread meets it`() {
        val steps = (0 until 20).map { CastPath.Step(it, Vec3(0.0, 65.0, it * 1.0), Vec3(0.0, 65.0, it + 1.0)) }
        val another = mob(0.0, 64.0, 8.0) to false
        // yours on the line 5 m out (shrunk by your 0.35 m there it still covers the line): the one behind it is never met
        assertFalse(CastPath.blocked(steps, listOf(mob(0.0, 64.0, 5.0) to true, another)))
        // yours 0.4 m beside the line: the hook alone would meet it (round 11 took that for the bobber's end), a bobber of
        // your spread may pass it and go on to the one behind
        assertTrue(CastPath.blocked(steps, listOf(mob(0.4, 64.0, 5.0) to true, another)))
        assertFalse(CastPath.blocked(listOf(steps.first()), listOf(mob(0.4, 64.0, 5.0) to true, another)), "nothing of the flight reaches it")
        // far out a creature-sized box of yours shrinks to nothing: it hides nothing behind it...
        assertTrue(CastPath.blocked(steps, listOf(mob(0.0, 64.0, 10.0) to true, mob(0.0, 64.0, 12.0) to false)))
        // ...a big one still does where its middle covers the line
        assertFalse(CastPath.blocked(steps, listOf(mob(0.0, 64.0, 10.0, half = 0.7) to true, mob(0.0, 64.0, 12.0) to false)))
        // and yours alone never holds a cast
        assertFalse(CastPath.blocked(steps, listOf(mob(0.4, 64.0, 5.0) to true, mob(0.0, 64.0, 10.0) to true)))
        // a box shrunk to nothing is no box: AABB would swap its sides back
        assertNull(CastPath.widened(mob(0.0, 64.0, 0.0), - 0.31))
        assertNotNull(CastPath.widened(mob(0.0, 64.0, 0.0), - 0.29))
        assertEquals(1.2, CastPath.widened(mob(0.0, 64.0, 0.0), 0.3)!!.xsize, 1e-9)
    }

    @Test
    fun `a re-aimed flight blocked again waits for it anew, bounded, never cast at once`() {
        val w = CastWay()
        // blocked at 0: the way's own wait (500) runs during the re-aim's reaction (600) and runs out first
        assertFalse(w.reaimDue(0) { 600L })
        assertFalse(w.ready(0, blocked = true, wait = { 500L }) { error("blocked") })
        assertTrue(w.ready(500, blocked = true, wait = { error("drawn once") }) { error("blocked") }, "the old flight's wait ran out")
        assertTrue(w.reaimDue(600) { error("drawn once") })
        // the re-aim turned to a clear flight: a new one, nothing of the old wait carried over
        w.reaimTaken(started = true)
        // its move and rest; found blocked again at 1500: it waits a while for it, never in that tick
        assertFalse(w.ready(1500, blocked = true, wait = { 400L }) { error("blocked") })
        assertFalse(w.ready(1850, blocked = true, wait = { error("drawn once") }) { error("blocked") })
        assertTrue(w.ready(1900, blocked = true, wait = { error("drawn once") }) { error("blocked") }, "bounded: then it goes")
        // the same with the re-aimed flight coming clear: a reaction after it, not the re-aim's own turn
        val v = CastWay()
        assertFalse(v.ready(0, blocked = true, wait = { 300L }) { error("blocked") })
        assertFalse(v.reaimDue(0) { 400L })
        assertTrue(v.ready(300, blocked = true, wait = { error("drawn once") }) { error("blocked") })
        assertTrue(v.reaimDue(400) { error("drawn once") })
        v.reaimTaken(started = true)
        assertFalse(v.ready(1200, blocked = true, wait = { 900L }) { error("blocked") })
        assertFalse(v.ready(1300, blocked = false, wait = { error("drawn once") }) { 200L }, "a reaction after it moved")
        assertTrue(v.ready(1500, blocked = false, wait = { error("drawn once") }) { error("drawn once") })
        // a re-aim that found nothing clear: the old flight's wait goes on as it was (never drawn again)
        val u = CastWay()
        assertFalse(u.ready(0, blocked = true, wait = { 300L }) { error("blocked") })
        assertFalse(u.reaimDue(0) { 400L })
        assertTrue(u.reaimDue(400) { error("drawn once") })
        u.reaimTaken(started = false)
        assertTrue(u.ready(400, blocked = true, wait = { error("drawn once") }) { error("blocked") })
    }

    @Test
    fun `a new block within the same cast is reacted to anew, and something walking in and out has the re-aim tried in bounds`() {
        val w = CastWay()
        // blocked at 0, clear again at 100 before the re-aim's reaction (300) ran
        assertFalse(w.ready(0, blocked = true, wait = { 1400L }) { error("blocked") })
        assertFalse(w.reaimDue(0) { 300L })
        assertFalse(w.ready(100, blocked = false, wait = { error("drawn once") }) { 5000L })
        // something else holds the cast meanwhile; blocked again at 2000: a reaction to this block, never in its tick
        assertFalse(w.reaimDue(2000) { 250L }, "round 11 re-aimed in this tick, on the old reaction")
        assertFalse(w.reaimDue(2200) { error("drawn once") })
        assertTrue(w.reaimDue(2250) { error("drawn once") })
        // something walking in and out of the flight faster than your reaction (100 ms in, 100 ms out): no re-aim ever
        // starts on a reaction to a block that was gone; after a few the re-aim is given up and the cast goes within
        // its way's own bounds (castClear: the re-aim's wait only while one may still be tried)
        val x = CastWay()
        var t = 0L
        var went = - 1L
        while (t <= 10_000 && went < 0) {
            val blocked = (t / 100) % 2 == 0L
            if (blocked && ! x.reaimTried) {
                assertFalse(x.reaimDue(t) { 300L }, "re-aimed at $t on a reaction to a block that was gone")
                x.ready(t, true, wait = { 1400L }) { 300L }
            }
            else if (x.ready(t, blocked, wait = { 1400L }) { 300L }) went = t
            t += 50
        }
        assertTrue(x.reaimTried, "given up")
        // the way's wait from the first block ran out at 1375 in a block that came in that tick: the cast waits your
        // reaction to each change after that, and never longer than the way's longest wait again
        assertTrue(went in 1400L .. 1375L + CastWay.WAIT_MAX_MS.toLong() + 50, "went at $went")
        assertEquals(3, CastWay.MAX_GONE)
        // a new cast asks anew
        x.clear()
        assertFalse(x.reaimTried)
        assertFalse(x.reaimDue(20_000) { 300L })
        assertTrue(x.reaimDue(20_300) { error("drawn once") })
    }

    @Test
    fun `a cast that would go anyway goes into the block, never in the tick its flight came clear`() {
        val w = CastWay()
        assertFalse(w.ready(0, blocked = true, wait = { 300L }) { error("blocked") })
        assertTrue(w.ready(300, blocked = true, wait = { error("drawn once") }) { error("blocked") }, "the wait ran out: anyway")
        // something else held the cast that tick; the flight comes clear at 350: your reaction to that, not this tick
        assertFalse(w.ready(350, blocked = false, wait = { error("drawn once") }) { 200L }, "round 12 cast in the clear's own tick")
        assertTrue(w.ready(550, blocked = false, wait = { error("drawn once") }) { error("drawn once") })
        // blocked again before that reaction ran: nor in the tick it walked back in, your reaction to that first
        val v = CastWay()
        assertFalse(v.ready(0, blocked = true, wait = { 300L }) { error("blocked") })
        assertTrue(v.ready(300, blocked = true, wait = { error("drawn once") }) { error("blocked") })
        assertFalse(v.ready(350, blocked = false, wait = { error("drawn once") }) { 400L })
        assertFalse(v.ready(400, blocked = true, wait = { error("drawn once") }) { 200L }, "round 12 cast in the tick it walked back in")
        assertTrue(v.ready(600, blocked = true, wait = { error("drawn once") }) { error("drawn once") })
        // the wait ran out in a clear stretch and something walks in: a reaction to that block, not its own tick
        val c = CastWay()
        assertFalse(c.ready(0, blocked = true, wait = { 300L }) { error("blocked") })
        assertFalse(c.ready(100, blocked = false, wait = { error("drawn once") }) { 900L })
        assertFalse(c.ready(400, blocked = true, wait = { error("drawn once") }) { 250L }, "cast in the block's own tick")
        assertTrue(c.ready(650, blocked = true, wait = { error("drawn once") }) { error("drawn once") })
        // walking in and out faster than your reaction: never past the way's longest wait after its own ran out
        val o = CastWay()
        var t = 0L
        var went = - 1L
        while (t <= 10_000 && went < 0) {
            if (o.ready(t, blocked = (t / 50) % 2 == 0L, wait = { 300L }) { 400L }) went = t
            t += 50
        }
        assertTrue(went in 275L .. 275L + CastWay.WAIT_MAX_MS.toLong() + 50, "went at $went")
    }
}
