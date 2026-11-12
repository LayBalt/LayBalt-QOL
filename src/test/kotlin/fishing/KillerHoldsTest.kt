package fishing

import com.laybalt.features.impl.fishing.AutoFishing.CastWay
import com.laybalt.features.impl.fishing.AutoMobKiller.ClearWay
import com.laybalt.features.impl.fishing.AutoMobKiller.DownHold
import com.laybalt.features.impl.fishing.AutoMobKiller.FightRules
import com.laybalt.features.impl.fishing.MotorModel
import com.laybalt.features.impl.fishing.MotorModel.View
import com.laybalt.features.impl.fishing.TimingModel
import com.laybalt.features.impl.fishing.TimingModel.Action
import com.laybalt.features.impl.fishing.ViewPolicy
import com.laybalt.features.impl.fishing.ViewPolicy.Decision
import com.laybalt.features.impl.fishing.ViewPolicy.Need
import kotlin.random.Random
import kotlin.test.*

/**
 * Round 12: an area blade's view turns up to its creature your reaction after something not yours came into its area,
 * never in that tick, also from a look down already there; a first rod hit drawn from the spawn holds your reaction to the
 * spawn, so a recast held past it starts the fight at its time; a weapon's swap waits for a recast Auto Fishing holds past
 * the swap's time, as long as that recast is still the catch's.
 */
class KillerHoldsTest {
    private val tick = TimingModel.TICK_MS

    /** The tick a wait due at [due] goes in, ticks at multiples of 50 ms. */
    private fun tickOf(due: Long) = (due + tick - 1).floorDiv(tick) * tick

    /** The largest share of [xs] on one value. */
    private fun pile(xs: List<Long>) = xs.groupingBy { it }.eachCount().values.max().toDouble() / xs.size

    @Test
    fun `an area blade's hold comes your reaction after the area was found blocked, never in that tick, and not for a block gone before`() {
        // blocked at 1000: held from the tick its reaction came due on
        val d = DownHold()
        for (now in 0L .. 1300L step 50) {
            val held = d.update(now, now >= 1000, downward = false, react = { 180L }) { error("never clear") }
            assertEquals(now >= tickOf(TimingModel.dueAt(1000, 180)), held, "at $now")
        }
        // something walking through the area faster than your reaction: no hold, and a later block is reacted to anew
        val e = DownHold()
        var draws = 0
        for (now in 0L .. 2000L step 50) {
            val blocked = now in 1000L .. 1100L || now >= 1500
            val held = e.update(now, blocked, downward = false, react = { draws++; 300L }) { 200L }
            assertEquals(now >= tickOf(TimingModel.dueAt(1500, 300)), held, "at $now")
        }
        assertEquals(2, draws, "one reaction per block")
        // a look down under way when the reaction came due goes on; held once it ended, still blocked
        val f = DownHold()
        for (now in 0L .. 1000L step 50) {
            val held = f.update(now, now >= 200, downward = now < 600, react = { 100L }) { error("never clear") }
            assertEquals(now >= 600, held, "at $now")
        }
    }

    /**
     * The killer's tick for a blade fight with explicit rules, through the real [ViewPolicy]: the view needs its pitch
     * ([Need.PITCH]) until [DownHold] holds the look down, then the creature ([Need.RANGED]); [blockAt]: something not yours
     * comes into the area then and stays. The tick the policy starts a move towards the creature, and its reaction's due tick.
     */
    private fun bladeFight(seed: Int, blockAt: Long, view: View, old: Boolean): Pair<Long, Long>? {
        val rng = Random(seed)
        val policy = ViewPolicy(Random(seed * 7 + 1))
        val hold = DownHold()
        val oldWay = ClearWay()
        var oldHeld = false
        val creature = View(30.0, 5.0)
        var due = Long.MIN_VALUE
        for (now in 0L .. 12_000L step tick) {
            val blocked = now >= blockAt
            val held = if (old) {
                // round 11: held in the very tick the area was found blocked
                oldHeld = FightRules.lookDownHeld(oldHeld, blocked, false, oldWay.ready(now, blocked) { 200L }, seen = true)
                oldHeld
            }
            else hold.update(now, blocked, downward = false, react = { TimingModel.draw(Action.CORRECTION_REACTION, emptyList(), null, rng).also { due = TimingModel.dueAt(now, it) } }) { 200L }
            val s = if (held) ViewPolicy.Situation(now, 1, Need.RANGED, view, creature, MotorModel.angle(view.yaw, view.pitch, creature.yaw, creature.pitch), Double.NaN,
                anchorAt = 0L, swapped = true, accept = { 4.0 }, restedAt = - 10_000L)
            else ViewPolicy.Situation(now, 1, Need.PITCH, view, View(view.yaw, 85.0), maxOf(0.0, 85.0 - view.pitch), FightRules.PITCH_SLACK.toDouble(),
                anchorAt = 0L, swapped = true, lookDown = { 85.0 }, restedAt = - 10_000L)
            val decision = policy.update(s)
            if (decision != Decision.START) continue
            // the look down itself started first: no case of a turn up to the creature
            if (! held) return null
            return now to tickOf(if (old) blockAt else due)
        }
        return null
    }

    @Test
    fun `the turn up to the creature waits your reaction to the area, before the look down and from one already down there`() {
        // before the look down started: the policy's first moment for the creature is timed from the swap and long past
        val starts = ArrayList<Long>()
        var sameTick = 0
        var cases = 0
        repeat(400) { seed ->
            val blockAt = 50L * (2 + seed % 6)
            val (start, due) = bladeFight(seed, blockAt, View(0.0, 0.0), old = false) ?: return@repeat
            cases++
            assertTrue(start >= due, "seed $seed: started at $start before its reaction's tick $due")
            assertTrue(start > blockAt, "seed $seed: in the block's own tick")
            starts += start - blockAt
            bladeFight(seed, blockAt, View(0.0, 0.0), old = true)?.let { (s, _) -> if (s == blockAt) sameTick++ }
        }
        assertTrue(cases >= 100, "$cases blade fights blocked before their look down")
        // round 11 turned in the block's own tick in many of them
        assertTrue(sameTick >= cases / 4, "$sameTick of $cases in the block's tick before")
        assertTrue(pile(starts) < 0.4, "a pile: ${starts.groupingBy { it }.eachCount()}")
        // a view already down at the blade's pitch when the area became blocked: round 11 stared at the floor, now it turns
        // up a reaction later
        val down = ArrayList<Long>()
        repeat(300) { seed ->
            val (start, due) = bladeFight(seed, 9_000L, View(0.0, 85.0), old = false) ?: error("seed $seed: never turned up")
            assertTrue(start >= due && start > 9_000L, "seed $seed: $start against its reaction's tick $due")
            down += start - 9_000L
        }
        assertTrue(pile(down) < 0.35, "a pile: ${down.groupingBy { it }.eachCount()}")
    }

    @Test
    fun `a fight with no swap picks the creature its click and turn were timed for, and one gone first frees the hand again`() {
        // the group: id to its distance; the start was timed for the nearest (7) when the hand was freed
        val group = listOf(3 to 4.0, 7 to 2.0, 9 to 6.0)
        val timed = FightRules.pick(group, - 1, { it.first }) { it.second }!!.first
        assertEquals(7, timed)
        // a gap later another one walked nearer: the fight still takes the one timed, not one it never planned a turn for
        val later = listOf(3 to 1.5, 7 to 2.2, 9 to 6.0)
        assertEquals(7, FightRules.pick(later, timed, { it.first }) { it.second }!!.first)
        assertEquals(3, FightRules.pick(later, - 1, { it.first }) { it.second }!!.first, "nothing timed: the nearest")
        // the one timed died before the fight started: never picked, and REACT reacts to that death anew
        val gone = listOf(3 to 1.5, 9 to 6.0)
        assertEquals(3, FightRules.pick(gone, timed, { it.first }) { it.second }!!.first)
        assertTrue(FightRules.olderGone(timed) { id -> gone.any { it.first == id } })
        assertFalse(FightRules.olderGone(timed) { id -> later.any { it.first == id } })
        assertFalse(FightRules.olderGone(- 1) { false }, "nothing timed")
        assertNull(FightRules.pick(emptyList<Pair<Int, Double>>(), timed, { it.first }) { it.second })
    }

    @Test
    fun `a recast held past the first rod hit drawn from the spawn starts the fight at that hit, no second reaction`() {
        assertEquals(900L, FightRules.rodReactAt(now = 910, nextAt = 340, led = true, leadAt = 900) { error("no second reaction") })
        assertEquals(700L, FightRules.rodReactAt(now = 610, nextAt = 700, led = true, leadAt = 600) { error("no second reaction") }, "never before the reaction itself")
        assertEquals(1250L, FightRules.rodReactAt(now = 910, nextAt = 340, led = false, leadAt = 900) { 1250L }, "not the lead's hit: drawn after now")
        assertEquals(400L, FightRules.rodReactAt(now = 150, nextAt = 400, led = true, leadAt = 600) { error("before its time") })
        // REACT's ticks for creatures whose recast was due around the spawn and held past the hit drawn from it
        val rng = Random(53)
        val late = ArrayList<Long>()
        val before = ArrayList<Long>()
        repeat(4_000) {
            val due = rng.nextLong(- 50, 300)
            val lead = FightRules.firstRodHit(0L, null, tick, { error("no recast") }, { TimingModel.draw(Action.FIRST_HIT, emptyList(), null, rng) }) { TimingModel.draw(Action.FIRST_HIT, emptyList(), null, rng) }
            if (due > lead) return@repeat
            val reaction = TimingModel.dueAt(0L, TimingModel.draw(Action.SPAWN_REACTION, emptyList(), null, rng))
            val redraw = { now: Long -> FightRules.reactAfter(0L, now, { TimingModel.draw(Action.SPAWN_REACTION, emptyList(), null, rng) }) { TimingModel.draw(Action.SPAWN_REACTION, emptyList(), null, rng) } }
            var now = tick
            while (FightRules.waitsForRecast(now, due, lead, pending = true)) now += tick
            val start = tickOf(maxOf(FightRules.rodReactAt(now, reaction, led = true, leadAt = lead) { redraw(now) }, now))
            assertEquals(tickOf(maxOf(lead, reaction)), start, "the fight starts in the hit's own tick, or at the reaction it holds")
            if (lead >= 900) before += tickOf(if (now >= reaction) redraw(now) else reaction) - tickOf(lead)
            late += start - tickOf(maxOf(lead, reaction))
        }
        assertTrue(late.all { it == 0L })
        // round 11 drew your reaction to the spawn again after the hit's time: a whole reaction late
        val b = before.sorted()
        assertTrue(b.size > 100 && b[b.size / 2] >= 200, "p50 ${b[b.size / 2]} of ${b.size}")
    }

    @Test
    fun `a weapon's swap waits for a recast Auto Fishing holds past the swap's time, as long as it is still the catch's`() {
        assertTrue(FightRules.waitsForRecast(now = 1700, recastDue = 100, leadAt = 1600, pending = true, heldUntil = 3000), "held past the swap's time")
        assertFalse(FightRules.waitsForRecast(now = 3000, recastDue = 100, leadAt = 1600, pending = true, heldUntil = 3000), "no longer this catch's")
        assertFalse(FightRules.waitsForRecast(now = 1700, recastDue = 100, leadAt = 1600, pending = false, heldUntil = 3000), "dropped")
        assertEquals(FightRules.RECAST_PENDING_MS, FightRules.recastHeldUntil(0L, 900L))
        assertEquals(1600L + FightRules.RECAST_PENDING_MS, FightRules.recastHeldUntil(1600L, 2000L), "from its spawn or its return in reach")
        assertEquals(3500L, FightRules.recastHeldUntil(0L, 3500L), "never before the swap's own time")
        assertEquals(1675L, FightRules.swapAfterRecast(1000L, 1600L, heldPast = true, gap = { 700L }) { error("a recast came") })
        assertEquals(1600L, FightRules.swapAfterRecast(null, 1600L, heldPast = false, gap = { error("none came") }) { error("not waited past") })
        assertEquals(3400L, FightRules.swapAfterRecast(null, 1600L, heldPast = true, gap = { error("none came") }) { 3400L })
        // catches as yours: the recast due around the spawn, its flight blocked by another player's creature for a while
        // (Auto Fishing's own CastWay times it), the swap one of your waits after the due time; the recast is Auto Fishing's
        // to send while REACT still waits for it (a Learned weapon's REACT holds it once it no longer does)
        val rng = Random(59)
        var lost = 0
        var lostBefore = 0
        var cases = 0
        val gaps = ArrayList<Long>()
        repeat(3_000) {
            val due = tickOf(rng.nextLong(- 100, 150))
            val clearAt = due + rng.nextLong(0, 2_500)
            val gap = TimingModel.draw(Action.RECAST_TO_SWAP, emptyList(), null, rng)
            val leadAt = TimingModel.dueAt(due, gap)
            fun run(old: Boolean): Pair<Long?, Long> {
                val way = CastWay()
                var cast: Long? = null
                var heldPast = false
                var now = tick
                while (true) {
                    if (cast == null && now >= due && way.ready(now, now < clearAt, { CastWay.waitMs(rng) }) { TimingModel.draw(Action.PATH_CLEAR, emptyList(), null, rng) }) cast = now
                    if (cast == null && FightRules.waitsForRecast(now, due, leadAt, pending = true, heldUntil = if (old) leadAt else FightRules.recastHeldUntil(0L, leadAt))) {
                        if (now >= leadAt) heldPast = true
                        now += tick
                        continue
                    }
                    val swap = if (old) cast?.let { TimingModel.dueAt(it, gap) } ?: leadAt
                        else FightRules.swapAfterRecast(cast, leadAt, heldPast, { gap }) { error("Auto Fishing's way never holds that long") }
                    return cast to tickOf(maxOf(swap, now))
                }
            }
            val (cast, swap) = run(old = false)
            cases++
            if (cast == null || cast >= swap) lost++
            else gaps += swap - cast
            val (oldCast, oldSwap) = run(old = true)
            if (oldCast == null || oldCast >= oldSwap) lostBefore++
        }
        // you recast before your swap 39 times of 39
        assertEquals(0, lost, "swaps that took the rod and its recast")
        assertTrue(lostBefore >= cases / 20, "round 11 lost $lostBefore of $cases")
        assertTrue(gaps.all { it > 0 } && pile(gaps) < 0.1, "the swap your wait after the recast")
        // a hold that never ends (a player standing in front of you): the swap goes your reaction after the catch's window
        val after = ArrayList<Long>()
        repeat(2_000) {
            val leadAt = TimingModel.dueAt(0L, TimingModel.draw(Action.RECAST_TO_SWAP, emptyList(), null, rng))
            var now = tick
            var heldPast = false
            while (FightRules.waitsForRecast(now, 0L, leadAt, pending = true, heldUntil = FightRules.recastHeldUntil(0L, leadAt))) {
                if (now >= leadAt) heldPast = true
                now += tick
            }
            val swap = FightRules.swapAfterRecast(null, leadAt, heldPast, { error("none came") }) {
                FightRules.reactAfter(0L, now, { TimingModel.draw(Action.SWAP_REACTION, emptyList(), null, rng) }) { TimingModel.draw(Action.SPAWN_REACTION, emptyList(), null, rng) }
            }
            if (heldPast) {
                assertTrue(swap > now, "never in the tick the wait ended")
                after += tickOf(swap) - now
            }
        }
        assertTrue(after.size > 1_000 && pile(after) < 0.35, "a pile: ${after.groupingBy { it }.eachCount()}")
    }
}
