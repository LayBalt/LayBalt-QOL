package fishing

import com.laybalt.features.impl.fishing.AutoFishing.CastPath
import com.laybalt.features.impl.fishing.AutoFishing.CastWay
import com.laybalt.features.impl.fishing.AutoFishing.RodWay
import com.laybalt.features.impl.fishing.AutoFishing.TickMark
import com.laybalt.features.impl.fishing.AutoFishing.WayNotice
import com.laybalt.features.impl.fishing.AutoMobKiller.DownHold
import com.laybalt.features.impl.fishing.AutoMobKiller.FightRules
import com.laybalt.features.impl.fishing.AutoMobKiller.PlanBook
import com.laybalt.features.impl.fishing.AutoMobKiller.PlanBook.Change
import com.laybalt.features.impl.fishing.ClickStyle
import com.laybalt.features.impl.fishing.SeaCreatureTracker.ClaimRules
import com.laybalt.features.impl.fishing.WeaponPlanner
import com.laybalt.features.impl.fishing.WeaponPlanner.Plan
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import kotlin.test.*

/**
 * Round 11: Auto Fishing on its own knows your creatures (they neither hold its casts nor its rod clicks, another player's
 * still do), its bobber's flight weighs yours only where the hook meets them and others with your spread, a re-aim around
 * one comes a reaction later and only where the view may turn; the killer finds a group's fight over at its last death,
 * weighs a pending recast against the hit it goes before, keeps an area blade's look down for a clear area and takes the
 * blade back only once that area is clear by a margin; screens opened during a loadout switch are yours, and nothing of a
 * player before a world change is taken for the new one's.
 */
class OwnCreaturesAndEdgesTest {
    /** A creature-sized box (0.6 wide, 1.8 high) with its feet's centre at [x], [y], [z]. */
    private fun mob(x: Double, y: Double, z: Double) = AABB(x - 0.3, y, z - 0.3, x + 0.3, y + 1.8, z + 0.3)

    /** An entity as the rod's checks see it: its box and what it rides with (its root vehicle: itself, or its mount's). */
    private class Thing(val box: AABB, mount: Thing? = null) {
        val root: Thing = mount?.root ?: this
    }

    /** Your creatures' parts as Auto Fishing's ownRoots takes them: the root vehicles of their mobs and nametags. */
    private fun roots(vararg parts: Thing) = parts.mapTo(HashSet()) { it.root }

    /** A cast's flight along +z, from the hook's spawn at 64.0 down to the water at 9 m (62.2). */
    private val flight = (0 until 9).map { CastPath.Step(it, Vec3(0.0, 64.0 - it * 0.2, it * 1.0), Vec3(0.0, 64.0 - (it + 1) * 0.2, it + 1.0)) }

    @Test
    fun `with the killer off your own creatures hold neither a cast nor a rod click, another player's still do`() {
        // the tracker runs for Auto Fishing alone, so your catches are claimed from the catch on (it reset every tick before)
        assertTrue(ClaimRules.runs(killer = false, rod = true, recorder = false))
        assertTrue(ClaimRules.runs(killer = true, rod = false, recorder = false))
        assertTrue(ClaimRules.runs(killer = false, rod = false, recorder = true))
        assertFalse(ClaimRules.runs(killer = false, rod = false, recorder = false))
        // the one just caught comes up right above the bobber: it is claimed yours
        val bobber = Vec3(0.0, 62.2, 9.0)
        assertTrue(ClaimRules.inColumn(Vec3(0.1, 63.9, 8.8), ClaimRules.catchSpots(true, bobber, null, 0, 1500)))
        val fresh = Thing(mob(0.1, 62.1, 8.8))
        val freshTag = Thing(mob(0.1, 64.0, 8.8))
        // an older one of yours walking at you, right in the line
        val older = Thing(mob(- 0.2, 63.0, 4.0))
        // one of yours riding a mount: the mount rides with it
        val mount = Thing(mob(0.0, 63.0, 6.0))
        val rider = Thing(mob(0.0, 64.2, 6.0), mount)
        val mine = roots(fresh, freshTag, older, rider)
        val all = listOf(fresh, freshTag, older, mount, rider)
        // the cast (or the recast after the catch) goes as yours did, nothing held: the real path's own call
        assertFalse(CastPath.hitsOther(flight, all, { it.box }, { it.root }, mine))
        for (t in all) assertFalse(CastPath.hitsOther(flight, listOf(t), { it.box }, { it.root }, mine), "yours: ${t.box}")
        assertTrue(CastPath.ridesWith(mount.root, mine), "the mount of a creature of yours rides with it")
        // without the tracker (round 10 with the killer off) every one of them held the cast
        assertTrue(CastPath.hitsOther(flight, listOf(fresh), { it.box }, { it.root }, emptySet()))
        // another player's creature on the flight still holds it, also behind one of yours the hook only passes beside
        val other = Thing(mob(0.0, 62.5, 7.5))
        assertTrue(CastPath.hitsOther(flight, listOf(other), { it.box }, { it.root }, mine))
        val beside = Thing(mob(0.75, 63.0, 4.0))
        assertTrue(CastPath.hitsOther(flight, listOf(beside, other), { it.box }, { it.root }, roots(beside)))
        // a rod click with one of yours (or its mount) under the crosshair goes; with another player's it waits
        for (t in all) assertFalse(RodWay.blocks(entity = true, own = CastPath.ridesWith(t.root, mine), usableBlock = false))
        assertTrue(RodWay.blocks(entity = true, own = CastPath.ridesWith(other.root, mine), usableBlock = false))
    }

    @Test
    fun `your creature takes the bobber only where the hook really meets it, anything else is met with your spread`() {
        // at the hook's spawn the hook's own margin alone (round 12: the spread grows along the flight)
        assertEquals(CastPath.HOOK_HIT, CastPath.margin(true, 0.0))
        assertEquals(CastPath.HOOK_HIT, CastPath.margin(false, 0.0))
        assertEquals(CastPath.HOOK_HIT + CastPath.SPREAD, CastPath.margin(false, 50.0))
        // your bobbers came to rest up to 0.74 m sideways of the mean line 19 times in 20 (459 casts)
        assertEquals(0.74, CastPath.SPREAD)
        val steps = (0 until 20).map { CastPath.Step(it, Vec3(0.0, 65.0, it * 1.0), Vec3(0.0, 65.0, it + 1.0)) }
        // yours 0.7 m beside the line at 5 m (its box 0.4 m off it: the hook passes it), another's on the line at 8 m
        assertTrue(CastPath.blocked(steps, listOf(mob(0.7, 64.0, 5.0) to true, mob(0.0, 64.0, 8.0) to false)))
        // yours on the line: it takes the bobber, the one behind it is never met
        assertFalse(CastPath.blocked(steps, listOf(mob(0.0, 64.0, 5.0) to true, mob(0.0, 64.0, 8.0) to false)))
        // another's 1.1 m beside the line at 8 m (its box 0.8 m off it, within the spread there) holds the cast, one 1.35 m
        // beside does not
        assertTrue(CastPath.blocked(steps, listOf(mob(1.1, 64.0, 8.0) to false)))
        assertFalse(CastPath.blocked(steps, listOf(mob(1.35, 64.0, 8.0) to false)))
    }

    @Test
    fun `a re-aim around something on the flight comes a reaction later, once, and turns away from the way's own wait`() {
        val w = CastWay()
        // the cast may not turn the view yet (a screen, your mouse, the killer's target): nothing asked, nothing used up
        assertFalse(w.reaimTried)
        assertFalse(w.ready(1000, blocked = true, wait = { 1400L }) { error("blocked") })
        assertFalse(w.reaimTried, "a cast that may not turn keeps its re-aim for when it may")
        // it may now: a reaction after that tick, never in it
        assertFalse(w.reaimDue(1100) { 240L })
        assertFalse(w.reaimDue(1300) { error("drawn once") })
        assertTrue(w.reaimDue(1350) { error("drawn once") })
        // the re-aim turned to a clear flight: the cast follows that move's rest, no reaction to the way on top
        w.reaimTaken(started = true)
        assertTrue(w.reaimTried)
        assertFalse(w.reaimDue(1400) { error("once per cast") })
        assertTrue(w.ready(1800, blocked = false, wait = { error("drawn once") }) { error("no reaction to its own turn") })
        // a re-aim that found no clear spot: the way's wait goes on as before, a reaction after it came clear
        val v = CastWay()
        assertFalse(v.ready(0, blocked = true, wait = { 1400L }) { error("blocked") })
        assertFalse(v.reaimDue(300) { 200L })
        assertTrue(v.reaimDue(500) { error("drawn once") })
        v.reaimTaken(started = false)
        assertFalse(v.ready(550, blocked = false, wait = { error("drawn once") }) { 200L }, "a reaction after the way came clear")
        assertFalse(v.ready(700, blocked = false, wait = { error("drawn once") }) { error("drawn once") })
        assertTrue(v.ready(750, blocked = false, wait = { error("drawn once") }) { error("drawn once") })
    }

    @Test
    fun `SWAP finds the group's fight over at its last death, also with a creature of another group left`() {
        assertTrue(FightRules.swapOver(replan = false, groupLeft = false, anyLeft = true), "another group's creature is reacted to from this death")
        assertTrue(FightRules.swapOver(replan = false, groupLeft = false, anyLeft = false))
        assertFalse(FightRules.swapOver(replan = false, groupLeft = true, anyLeft = true), "the group's next one after the gap")
        // after a loadout the lead target is planned again from all of yours
        assertFalse(FightRules.swapOver(replan = true, groupLeft = false, anyLeft = true))
        assertTrue(FightRules.swapOver(replan = true, groupLeft = false, anyLeft = false))
        // over at the death: your hand is freed then, and one there already is reacted to from that moment, not after the gap
        val death = 10_000L
        val gapEnd = death + 300
        assertTrue(FightRules.olderThanFree(anchor = 2_000, freedAt = death))
        assertEquals(death + 1296 - 25, FightRules.olderReact(swaps = true, keyDown = false, swapAt = death + 1296 - 25, from = death, now = death) { error("swaps") })
        assertTrue(FightRules.olderReact(swaps = false, keyDown = false, swapAt = 0, from = death, now = death) { 200L } < gapEnd, "not stacked on the gap")
    }

    @Test
    fun `a blade left for something in its planned area comes back only once that area is clear by a margin`() {
        val hyperion = Plan("HYPERION", ClickStyle.RIGHT, 3.0, - 3.0, 3.0, 178.0, false, 146.0, 89f)
        val flay = Plan("FLAMING_FLAY", ClickStyle.HOLD, 10.5, - 6.0, 4.0, 32.0, false, 21.0)
        val blade = WeaponPlanner.area("HYPERION", hyperion)!!
        val near = FightRules.plannedArea(blade)
        val wide = FightRules.plannedArea(blade, FightRules.RETAKE_MARGIN)
        assertEquals(near.radius + FightRules.RETAKE_MARGIN, wide.radius, 1e-9)
        // your creatures moved 2.3 m or less in 2 s three times in four (p75 of 2368)
        assertEquals(2.3, FightRules.RETAKE_MARGIN)
        val eye = Vec3(0.0, 65.62, 0.0)
        val down = Vec3(0.0, - 1.0, 0.0)
        val all = setOf("HYPERION", "FLAMING_FLAY")
        fun book(usable: Set<String>) = { _: Set<String>, shrink: Boolean -> listOf(hyperion, flay).firstOrNull { it.option in usable && WeaponPlanner.inEnvelope(it, 2.0, 0.0, shrink) } }
        // another player's creature wandering about the planned zone's edge (12.74 m): where it stands at each tick
        val walk = listOf(0L to 20.0, 50L to 12.5, 2100L to 13.2, 4200L to 12.6, 6300L to 13.3, 8400L to 16.0)
        fun run(hysteresis: Boolean): List<Change> {
            val b = PlanBook { 2000L }
            return walk.map { (t, d) ->
                val box = mob(d, 64.0, 0.0)
                val n = if (FightRules.inArea(near, eye, down, box)) setOf("HYPERION") else emptySet()
                val w = if (FightRules.inArea(wide, eye, down, box)) setOf("HYPERION") else emptySet()
                val usable = if (hysteresis) FightRules.usableFor(all, b.plans[1]?.option, n, w) else all - n
                b.update(1, t, 2.0, 0.0, usable, book(usable))
            }
        }
        // the edge alone swapped the blade in and out at each re-choose
        assertEquals(4, run(hysteresis = false).count { it == Change.SWITCHED })
        // with the margin: left once as it walked in, taken back once it was clear by the margin
        val changes = run(hysteresis = true)
        assertEquals(listOf(Change.PLANNED, Change.SWITCHED, Change.NONE, Change.NONE, Change.NONE, Change.SWITCHED), changes)
        // the current option keeps its plain zone, any other needs the wider one clear
        assertEquals(setOf("HYPERION", "FLAMING_FLAY"), FightRules.usableFor(all, "HYPERION", emptySet(), setOf("HYPERION")))
        assertEquals(setOf("FLAMING_FLAY"), FightRules.usableFor(all, "FLAMING_FLAY", emptySet(), setOf("HYPERION")))
        assertEquals(setOf("FLAMING_FLAY"), FightRules.usableFor(all, "HYPERION", setOf("HYPERION"), setOf("HYPERION")))
        assertEquals(all, FightRules.usableFor(all, null, emptySet(), emptySet()))
    }

    @Test
    fun `a blade's look down waits for a clear area with the view on the creature, and goes a reaction after it came clear`() {
        assertTrue(FightRules.lookDownHeld(held = false, blocked = true, downward = false, clear = false, seen = true), "not under way: held, the view on the creature")
        assertFalse(FightRules.lookDownHeld(held = false, blocked = true, downward = false, clear = false, seen = false), "your reaction to it coming in still to come")
        assertFalse(FightRules.lookDownHeld(held = false, blocked = true, downward = true, clear = false, seen = true), "under way: goes on, checked where it ends")
        assertFalse(FightRules.lookDownHeld(held = false, blocked = false, downward = false, clear = true, seen = false))
        assertTrue(FightRules.lookDownHeld(held = true, blocked = true, downward = false, clear = false, seen = false))
        assertTrue(FightRules.lookDownHeld(held = true, blocked = false, downward = false, clear = false, seen = false), "clear, your reaction still to come")
        assertFalse(FightRules.lookDownHeld(held = true, blocked = false, downward = false, clear = true, seen = false))
        // through the killer's DownHold: held while blocked (0-1000) from the tick its reaction came due, let go a reaction
        // after the first clear tick, never in it
        val d = DownHold()
        val goes = (0L .. 2000L step 50).first { now ->
            val held = d.update(now, now < 1050, downward = false, react = { 120L }) { 220L }
            assertEquals(now in 100L .. 1200L, held, "at $now")
            now > 100 && ! held
        }
        assertEquals(1250L, goes)
    }

    @Test
    fun `a look down still on its way is checked over the area planned around it, not along the level view it turns from`() {
        assertEquals(FightRules.AreaCheck.VIEW, FightRules.useCheck(lookDown = false, reached = false, held = false))
        assertEquals(FightRules.AreaCheck.VIEW, FightRules.useCheck(lookDown = true, reached = true, held = false))
        assertEquals(FightRules.AreaCheck.PLANNED, FightRules.useCheck(lookDown = true, reached = false, held = false))
        assertEquals(FightRules.AreaCheck.NONE, FightRules.useCheck(lookDown = true, reached = false, held = true), "the look down itself waits")
        // another player's creature 18 m ahead: inside the area along the near-level view (10 m on, 11 m round), far
        // outside the look down's
        val blade = WeaponPlanner.Area(WeaponPlanner.TELEPORT_M, WeaponPlanner.IMPLOSION_M + WeaponPlanner.AREA_MARGIN)
        val eye = Vec3(0.0, 65.62, 0.0)
        val far = mob(0.0, 64.0, 18.0)
        assertTrue(FightRules.inArea(blade, eye, Vec3.directionFromRotation(10f, 0f), far))
        assertFalse(FightRules.inArea(FightRules.plannedArea(blade), eye, Vec3(0.0, - 1.0, 0.0), far))
    }

    @Test
    fun `a screen you open during a loadout switch is yours, and so is one still open after it`() {
        assertTrue(FightRules.screenYours(switching = false, loadoutMenu = false))
        assertTrue(FightRules.screenYours(switching = false, loadoutMenu = true), "the switch is done: whatever is open is yours")
        assertTrue(FightRules.screenYours(switching = true, loadoutMenu = false), "chat or your inventory during the switch")
        assertFalse(FightRules.screenYours(switching = true, loadoutMenu = true), "the switch's own menu")
    }

    @Test
    fun `a rod click held for the way that lapsed counts the next one's wait afresh, told at most once`() {
        val n = WayNotice()
        // a bite's reel held 2 s for a player in front of you, then the bite was lost
        for (t in 0L .. 2000L step 50) assertNull(n.update(t, blocked = true))
        n.lapse()
        // the next bite's reel 30 s later finds it there again: counted from then, not told at once
        assertNull(n.update(30_000, blocked = true))
        assertNull(n.update(32_950, blocked = true))
        assertEquals(WayNotice.Tell.PAUSED, n.update(33_000, blocked = true))
        // told already: a lapse keeps it told, its end is still told and the pause not told twice
        n.lapse()
        assertNull(n.update(60_000, blocked = true))
        assertNull(n.update(64_000, blocked = true))
        assertEquals(WayNotice.Tell.RESUMED, n.update(64_050, blocked = false))
    }

    @Test
    fun `no tick of the player before a world change is taken for the new one's`() {
        val mark = TickMark()
        assertFalse(mark.at(0))
        mark.mark(48_213)
        assertTrue(mark.at(48_213))
        assertFalse(mark.at(48_214))
        // the world changed: Auto Fishing's state is reset, the new player counts from 0 and reaches 48 213 again
        mark.clear()
        for (t in listOf(0, 1, 48_213, Int.MAX_VALUE)) assertFalse(mark.at(t))
    }
}
