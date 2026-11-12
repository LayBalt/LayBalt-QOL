package fishing

import com.laybalt.features.impl.fishing.AutoFishing.CastPath
import com.laybalt.features.impl.fishing.AutoFishing.CastWay
import com.laybalt.features.impl.fishing.AutoFishing.RodClick
import com.laybalt.features.impl.fishing.AutoFishing.RodWay
import com.laybalt.features.impl.fishing.AutoFishing.WayNotice
import com.laybalt.features.impl.fishing.AutoMobKiller.FightRules
import com.laybalt.features.impl.fishing.AutoMobKiller.FightRules.PathKind
import com.laybalt.features.impl.fishing.AutoMobKiller.HandLag
import com.laybalt.features.impl.fishing.AutoMobKiller.PlanBook
import com.laybalt.features.impl.fishing.AutoMobKiller.PlanBook.Change
import com.laybalt.features.impl.fishing.BobberFlight
import com.laybalt.features.impl.fishing.BobberFlight.Blocks
import com.laybalt.features.impl.fishing.BobberFlight.Medium
import com.laybalt.features.impl.fishing.ClickStyle
import com.laybalt.features.impl.fishing.MotorModel
import com.laybalt.features.impl.fishing.MotorModel.View
import com.laybalt.features.impl.fishing.TimingModel
import com.laybalt.features.impl.fishing.WeaponPlanner
import com.laybalt.features.impl.fishing.WeaponPlanner.Plan
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.*

/**
 * Round 10's safety rules: an area ability checked over its whole area and left for the next weapon you use while
 * something not yours is in it, your creatures (alive or dying) never counted as another's under the crosshair, a creature
 * of yours shielding only where the ability really meets it, Auto Fishing's click checked for the very rod it was meant
 * for and moving its state on only once it went out, its wait for the way told in chat, and its cast's bobber flight
 * kept off other players' creatures and players within bounds.
 */
class AreaAndRodGuardTest {
    private val flay = Plan("FLAMING_FLAY", ClickStyle.HOLD, 10.5, - 6.0, 4.0, 32.0, false, 21.0)
    private val hyperion = Plan("HYPERION", ClickStyle.RIGHT, 3.0, - 3.0, 3.0, 178.0, false, 146.0, 89f)
    private val eye = Vec3(0.0, 65.62, 0.0)
    private val down = Vec3(0.0, - 1.0, 0.0)

    /** A creature-sized box (0.6 wide, 1.8 high) with its feet's centre at [x], [y], [z]. */
    private fun mob(x: Double, y: Double, z: Double) = AABB(x - 0.3, y, z - 0.3, x + 0.3, y + 1.8, z + 0.3)

    @Test
    fun `a teleport blade's area is its teleport with the implosion around it, an area weapon's at least the implosion's around you`() {
        val blade = assertNotNull(WeaponPlanner.area("HYPERION", hyperion))
        assertEquals(WeaponPlanner.TELEPORT_M, blade.length)
        assertEquals(WeaponPlanner.IMPLOSION_M + WeaponPlanner.AREA_MARGIN, blade.radius)
        // every Wither Impact blade, with or without a learned plan
        for (id in WeaponPlanner.TELEPORT_BLADES) assertEquals(11.0, WeaponPlanner.area(id, null)?.radius)
        // an area weapon (aimed 90 degrees off or more): around you, as far as it reached your creatures, the implosion's at least
        val wide = Plan("FIRE_VEIL", ClickStyle.RIGHT, 14.0, - 3.0, 3.0, 120.0, false)
        assertEquals(0.0, WeaponPlanner.area("FIRE_VEIL", wide)?.length)
        assertEquals(15.0, WeaponPlanner.area("FIRE_VEIL", wide)?.radius)
        assertEquals(11.0, WeaponPlanner.area("FIRE_VEIL", Plan("FIRE_VEIL", ClickStyle.RIGHT, 4.0, - 3.0, 3.0, 95.0, false))?.radius)
        // what hits only what is on its way has no area
        assertNull(WeaponPlanner.area("FLAMING_FLAY", flay))
        assertNull(WeaponPlanner.area(WeaponPlanner.ROD, WeaponPlanner.rodPlan))
        assertNull(WeaponPlanner.area("SOME_SWORD", null))
    }

    @Test
    fun `a box counts as in the area within its radius of the teleport's line, not beyond`() {
        val blade = WeaponPlanner.Area(10.0, 11.0)
        // another player's creature level with you, 10.5 m to the side of a look straight down: the implosion reaches it
        assertTrue(FightRules.inArea(blade, eye, down, mob(10.8, 64.0, 0.0)))
        // its box's near side 11.5 m off: out of reach
        assertFalse(FightRules.inArea(blade, eye, down, mob(11.8, 64.0, 0.0)))
        // below where the teleport may end (10 m down): 10.9 m below that is in, 11.6 m is not
        assertTrue(FightRules.inArea(blade, eye, down, mob(0.0, 65.62 - 10.0 - 10.9 - 1.8, 0.0)))
        assertFalse(FightRules.inArea(blade, eye, down, mob(0.0, 65.62 - 10.0 - 11.6 - 1.8, 0.0)))
        // an area around you alone
        val around = WeaponPlanner.Area(0.0, 11.0)
        assertTrue(FightRules.inArea(around, eye, down, mob(0.0, 64.0, 10.9)))
        assertFalse(FightRules.inArea(around, eye, down, mob(0.0, 64.0, 11.6)))
    }

    @Test
    fun `a blade's planned area holds every area of a look down it is used with`() {
        val blade = WeaponPlanner.area("HYPERION", hyperion)!!
        val planned = FightRules.plannedArea(blade)
        assertEquals(blade.radius + blade.length * cos(Math.toRadians(FightRules.BLADE_PITCH.toDouble())), planned.radius, 1e-9)
        val rng = Random(7)
        var inUse = 0
        repeat(20_000) {
            val box = mob(rng.nextDouble(- 16.0, 16.0), rng.nextDouble(45.0, 70.0), rng.nextDouble(- 16.0, 16.0))
            // any look down from the blade's pitch on, any yaw
            val dir = Vec3.directionFromRotation(rng.nextDouble(FightRules.BLADE_PITCH.toDouble(), 90.0).toFloat(), rng.nextDouble(- 180.0, 180.0).toFloat())
            if (FightRules.inArea(blade, eye, dir, box)) {
                inUse++
                assertTrue(FightRules.inArea(planned, eye, down, box), "in the area of a use, so in the planned one: $box")
            }
        }
        assertTrue(inUse > 1000)
    }

    @Test
    fun `only something alive that is not yours and no nametag keeps an area ability from being used`() {
        assertTrue(FightRules.areaBlocks(mine = false, stand = false, living = true), "another player's creature, a player, a mob")
        assertFalse(FightRules.areaBlocks(mine = true, stand = false, living = true), "a creature of yours, or riding with one")
        assertFalse(FightRules.areaBlocks(mine = false, stand = true, living = true), "an armor stand")
        assertFalse(FightRules.areaBlocks(mine = false, stand = false, living = false), "an item, an arrow, a bobber")
    }

    @Test
    fun `an area ability blocked leaves the plan to the next weapon you use, parks when none reaches, and comes back`() {
        // the planner's order for this creature: the Hyperion first (your habit), the Flay next
        fun book(order: List<Plan>, usable: Set<String>) = { _: Set<String>, shrink: Boolean ->
            order.firstOrNull { it.option in usable && WeaponPlanner.inEnvelope(it, 2.0, 0.0, shrink) }
        }
        val all = setOf("HYPERION", "FLAMING_FLAY", "ROD")
        val b = PlanBook { 2000L }
        assertEquals(Change.PLANNED, b.update(1, 0, 2.0, 0.0, all, book(listOf(hyperion, flay), all)))
        assertEquals("HYPERION", b.plans[1]?.option)
        // another player's creature walks into its area: the Hyperion is no option now, the Flay takes over at once
        val noArea = all - "HYPERION"
        assertEquals(Change.SWITCHED, b.update(1, 50, 2.0, 0.0, noArea, book(listOf(hyperion, flay), noArea)))
        assertEquals("FLAMING_FLAY", b.plans[1]?.option)
        // the Flay sticks until the plan is chosen again, then the Hyperion may come back once its area is clear
        assertEquals(Change.NONE, b.update(1, 1000, 2.0, 0.0, all, book(listOf(hyperion, flay), all)))
        assertEquals(Change.SWITCHED, b.update(1, 2100, 2.0, 0.0, all, book(listOf(hyperion, flay), all)))
        assertEquals("HYPERION", b.plans[1]?.option)
        // with only the Hyperion at hand: parked like a creature nothing reaches, never a use waiting on its area
        val only = setOf("HYPERION")
        val c = PlanBook { 2000L }
        assertEquals(Change.PLANNED, c.update(2, 0, 2.0, 0.0, only, book(listOf(hyperion), only)))
        assertEquals(Change.PARKED, c.update(2, 50, 2.0, 0.0, emptySet(), book(listOf(hyperion), emptySet())))
        assertNull(c.plans[2])
        assertEquals(Change.UNPARKED, c.update(2, 1600, 2.0, 0.0, only, book(listOf(hyperion), only)))
        assertEquals("HYPERION", c.plans[2]?.option)
    }

    @Test
    fun `the planner picks the next weapon you use when an area ability is left out`() {
        val profile = com.laybalt.features.impl.fishing.BehaviorProfile.Profile(sessions = 1).apply {
            fishingRods = linkedSetOf("ROD_OF_THE_SEA")
            weaponStyles["FLAMING_FLAY"] = com.laybalt.features.impl.fishing.BehaviorProfile.WeaponStyle(
                style = ClickStyle.HOLD, uses = 100, effective = 60, chance = 0.15f, right = 20, held = 80,
                reach = MutableList(20) { 0.5f + it * 0.5f }, dy = MutableList(20) { - 6f + it * 0.4f },
                aimErr = MutableList(20) { 10f + it * 1.5f }, pitch = MutableList(20) { 10f })
            weaponStyles["HYPERION"] = com.laybalt.features.impl.fishing.BehaviorProfile.WeaponStyle(
                style = ClickStyle.RIGHT, uses = 50, effective = 40, chance = 0.15f, right = 50,
                reach = MutableList(10) { 0.5f + it * 0.25f }, dy = MutableList(10) { 0f },
                aimErr = MutableList(10) { 100f + it * 8f }, pitch = MutableList(10) { 88f + it * 0.2f })
            habits.getOrPut("Banshee") { com.laybalt.features.impl.fishing.BehaviorProfile.Habit() }.choices["near/level"] = linkedMapOf("HYPERION" to 6, "FLAMING_FLAY" to 2)
        }
        val all = setOf("HYPERION", "FLAMING_FLAY")
        assertEquals("HYPERION", WeaponPlanner.choose(profile, "Banshee", "", 1000.0, 2.0, 0.0, all)?.option)
        assertEquals("FLAMING_FLAY", WeaponPlanner.choose(profile, "Banshee", "", 1000.0, 2.0, 0.0, all - "HYPERION")?.option)
        assertNull(WeaponPlanner.choose(profile, "Banshee", "", 1000.0, 2.0, 0.0, setOf("HYPERION") - "HYPERION"), "nothing left: parked")
    }

    @Test
    fun `a creature of yours shields only where the ability meets it, so one behind it beside the line is still found`() {
        assertEquals(0.0, FightRules.hitMargin(PathKind.SHIELD, 0.5))
        assertEquals(0.5, FightRules.hitMargin(PathKind.BLOCKER, 0.5))
        // the view line along +z, 0.6 m to the side of your creature at 5 m, another player's creature on the line at 8 m
        val from = Vec3(0.6, 65.0, 0.0)
        val to = Vec3(0.6, 65.0, 12.0)
        fun hit(box: AABB, kind: PathKind) = box.inflate(FightRules.hitMargin(kind, 0.5)).clip(from, to).map { it.distanceTo(from) to kind }.orElse(null)
        val yours = hit(mob(0.0, 64.0, 5.0), PathKind.SHIELD)
        val other = hit(mob(0.6, 64.0, 8.0), PathKind.BLOCKER)
        assertNull(yours, "the line passes beside your creature: it takes no hit")
        assertTrue(FightRules.pathBlocked(listOfNotNull(yours, other)))
        // widened like a blocker (round 9) your creature would have hidden the other one
        val widened = mob(0.0, 64.0, 5.0).inflate(0.5).clip(from, to).map { it.distanceTo(from) to PathKind.SHIELD }.orElse(null)
        assertFalse(FightRules.pathBlocked(listOfNotNull(widened, other)))
    }

    @Test
    fun `your creature dying under the crosshair is no other entity, so the held key goes up at your reaction, not at once`() {
        // after the death there is no target: only your creature's own flag keeps it from counting as another's
        assertFalse(FightRules.usesOnOther(entity = true, fought = false, own = true), "your creature, dying with the target gone, or another of yours (a Sludgling)")
        assertFalse(FightRules.usesOnOther(entity = true, fought = true, own = false), "the creature fought")
        assertTrue(FightRules.usesOnOther(entity = true, fought = false, own = false), "another player's creature, a player")
        assertFalse(FightRules.usesOnOther(entity = false, fought = false, own = false))
        // the hold through the death: the key goes up at the drawn reaction (your p50 200 ms after such deaths), never in
        // the first tick after it the way lagTick would let it go for something in the way
        val lag = HandLag()
        lag.died(10_000, held = true, style = ClickStyle.HOLD, clicking = false, others = false, rng = Random(3), reaction = { 230L }) { 0L }
        if (FightRules.usesOnOther(true, fought = false, own = true)) lag.keyUpBy(10_050)
        assertEquals(TimingModel.dueAt(10_000, 230L), lag.keyUpAt)
    }

    @Test
    fun `a rod click goes only with the very rod it was meant for`() {
        assertTrue(RodWay.sameRod(2, "ROD_OF_THE_SEA", rod = true, clickSlot = 2, clickId = "ROD_OF_THE_SEA"))
        assertFalse(RodWay.sameRod(5, "ROD_OF_THE_SEA", rod = true, clickSlot = 2, clickId = "ROD_OF_THE_SEA"), "a hotbar key to another rod's slot")
        assertFalse(RodWay.sameRod(2, "HYPERION", rod = false, clickSlot = 2, clickId = "ROD_OF_THE_SEA"), "the slot's item swapped (the offhand key)")
        assertFalse(RodWay.sameRod(2, "ROD_OF_THE_SEA", rod = false, clickSlot = 2, clickId = "ROD_OF_THE_SEA"), "not the item the click was meant for")
    }

    @Test
    fun `a rod click moves Auto Fishing on only once it went out, a cancelled one leaves its reel due`() {
        val click = RodClick()
        var phase = "REELING"
        var commits = 0
        // a bite's reel sent in tick 100, cancelled as the game handled it (another player stepped in front of you)
        click.sent(100) { phase = "CASTING"; commits++ }
        assertTrue(click.pending)
        click.settled(100, out = false)
        assertEquals("REELING", phase, "still a reel of that bite")
        assertEquals(0, commits)
        // the reel waits for the way past your latest reel of a bite: the bite is lost, never reeled as a late recast
        assertTrue(RodWay.biteLost(1000, 0, held = true))
        // and the way held for it goes with it: the next rod click (the next bite's reel, seconds later) is no reaction to it
        val way = com.laybalt.features.impl.fishing.AutoMobKiller.ClearWay()
        way.block()
        way.clear()
        assertTrue(way.ready(9000, blocked = false) { error("a stale reaction") })
        // one that went out is done once, whatever else the game handles in its tick
        click.sent(120) { phase = "CASTING"; commits++ }
        click.settled(119, out = true)
        assertEquals(0, commits, "another tick's use is not this click")
        click.settled(120, out = true)
        click.settled(120, out = true)
        assertEquals("CASTING", phase)
        assertEquals(1, commits)
        // one the game never handled in its tick never went out
        click.sent(130) { commits++ }
        click.expire(130)
        assertTrue(click.pending, "still its own tick")
        click.expire(131)
        assertFalse(click.pending)
        click.settled(130, out = true)
        assertEquals(1, commits)
    }

    @Test
    fun `a wait for the way of more than a few seconds is told once, and its end once`() {
        val n = WayNotice()
        assertNull(n.update(0, blocked = false))
        // a player passing in front of you for 2 s: nothing to tell
        for (t in 50L .. 2000L step 50) assertNull(n.update(t, blocked = true))
        assertNull(n.update(2050, blocked = false))
        // one standing there: told once, after 3 s
        val tells = (3000L .. 9000L step 50).mapNotNull { n.update(it, blocked = true)?.let { tell -> it to tell } }
        assertEquals(listOf(6000L to WayNotice.Tell.PAUSED), tells)
        // the bite lost meanwhile: the next rod click due still finds it there, still told only once
        assertNull(n.update(30_000, blocked = true))
        assertEquals(WayNotice.Tell.RESUMED, n.update(30_050, blocked = false))
        assertNull(n.update(30_100, blocked = false))
        // seen blocked again at the next bite, long after a clear look: counted from then
        assertNull(n.update(60_000, blocked = true))
        assertNull(n.update(62_000, blocked = true))
        assertEquals(WayNotice.Tell.PAUSED, n.update(63_000, blocked = true))
        n.clear()
        assertNull(n.update(63_050, blocked = false), "Auto Fishing stopped meanwhile: nothing to take back")
    }

    @Test
    fun `a cast with something not yours on its flight waits a while for it, never longer, and goes a reaction after it moved`() {
        val w = CastWay()
        assertTrue(w.ready(0, blocked = false, wait = { error("nothing to wait for") }) { error("nothing to react to") }, "a clear flight: at once")
        w.clear()
        // blocked: one re-aim may be tried, a reaction after the flight was found blocked, once per cast
        assertFalse(w.reaimDue(0) { 150L }, "never in the tick it was found blocked")
        assertTrue(w.reaimDue(150) { error("drawn once") })
        w.reaimTaken(started = false)
        assertFalse(w.reaimDue(200) { error("tried already") })
        // it moves out after 300 ms: the cast goes a reaction after the first clear tick, not in it
        assertFalse(w.ready(1000, blocked = true, wait = { 900L }) { error("blocked") })
        assertFalse(w.ready(1300, blocked = false, wait = { error("drawn once") }) { 200L })
        assertFalse(w.ready(1450, blocked = false, wait = { error("drawn once") }) { error("drawn once") })
        assertTrue(w.ready(1500, blocked = false, wait = { error("drawn once") }) { error("drawn once") })
        // a new cast: it stays there past the wait, and the cast goes into it as yours did now and then
        w.clear()
        assertFalse(w.reaimTried, "a new cast may re-aim again")
        assertFalse(w.ready(5000, blocked = true, wait = { 700L }) { error("blocked") })
        assertFalse(w.ready(5600, blocked = true, wait = { error("drawn once") }) { error("blocked") })
        assertTrue(w.ready(5700, blocked = true, wait = { error("drawn once") }) { error("blocked") })
        // and nothing holds it any more (the crosshair's own check may still)
        assertTrue(w.ready(5750, blocked = true, wait = { error("drawn once") }) { error("blocked") })
        // the default wait: a short look, never past 1.5 s, never piled
        val rng = Random(11)
        val waits = List(20_000) { CastWay.waitMs(rng) }.sorted()
        assertTrue(waits.first() > 0 && waits.last() <= CastWay.WAIT_MAX_MS.toLong(), "${waits.first()} .. ${waits.last()}")
        assertEquals(CastWay.WAIT_P50_MS, waits[10_000].toDouble(), 30.0)
        assertTrue(waits.groupingBy { it }.eachCount().values.max() < 20_000 * 0.01, "a pile")
    }

    @Test
    fun `a cast's flight is hooked in whatever box it meets first, yours taking the bobber`() {
        // a straight flight along +z at hook height
        val steps = (0 until 20).map { CastPath.Step(it, Vec3(0.0, 65.0, it * 1.0), Vec3(0.0, 65.0, it + 1.0)) }
        fun at(z: Double, x: Double = 0.0) = mob(x, 64.0, z)
        assertTrue(CastPath.blocked(steps, listOf(at(8.0) to false)), "another player's creature on the line")
        assertFalse(CastPath.blocked(steps, listOf(at(5.0) to true, at(8.0) to false)), "yours first: it takes the bobber")
        assertTrue(CastPath.blocked(steps, listOf(at(5.0) to false, at(8.0) to true)), "another's first")
        assertFalse(CastPath.blocked(steps, emptyList()))
        // beside the line: within the hook's widening and the spread 8 m out (0.3 + about 0.6 beyond the box) it counts,
        // beyond not
        assertTrue(CastPath.blocked(steps, listOf(at(8.0, x = 1.1) to false)))
        assertFalse(CastPath.blocked(steps, listOf(at(8.0, x = 1.35) to false)))
        // past the bobber's first tick in the water it floats there: what swims by later is the bite's business
        val flown = CastPath.flight(steps, contactTick = 6)
        assertEquals(7, flown.size)
        assertFalse(CastPath.blocked(flown, listOf(at(12.0) to false)))
        assertEquals(steps, CastPath.flight(steps, contactTick = - 1))
    }

    @Test
    fun `a re-aim around something on the flight is one of your small re-aims, drawn, never the same twice`() {
        val rng = Random(5)
        val from = View(30.0, 40.0)
        val sizes = ArrayList<Double>()
        repeat(4000) {
            val v = assertNotNull(CastPath.reaim(from, rng) { true })
            sizes += MotorModel.angle(from.yaw, from.pitch, v.yaw, v.pitch)
        }
        sizes.sort()
        assertTrue(sizes.first() >= CastPath.REAIM_MIN * 0.95 && sizes.last() <= CastPath.REAIM_MAX * 1.05, "${sizes.first()} .. ${sizes.last()}")
        // your small re-aims between casts: p50 2.57 (ln fit 2.41)
        assertEquals(2.41, sizes[2000], 0.25)
        assertEquals(sizes.size, sizes.toSet().size, "no two alike")
        // only a view the check takes, and none when no view does
        val left = CastPath.reaim(from, Random(9)) { it.yaw < from.yaw }
        assertTrue(left != null && left.yaw < from.yaw)
        assertNull(CastPath.reaim(from, Random(9)) { false })
        // never past straight up or down
        repeat(500) { assertTrue(abs(CastPath.reaim(View(0.0, 89.0), rng) { true }?.pitch ?: 0.0) <= 90.0) }
    }

    @Test
    fun `the bobber's flight is told move by move, chained up to where it lands`() {
        val openWater = Blocks { _, y, _ -> if (y <= 62) Medium.WATER else Medium.AIR }
        val steps = ArrayList<CastPath.Step>()
        val landing = BobberFlight.fly(0.5, 64.0, 0.5, 0.0, 30.0, false, openWater) { tick, x0, y0, z0, x1, y1, z1 ->
            steps += CastPath.Step(tick, Vec3(x0, y0, z0), Vec3(x1, y1, z1))
        }
        assertTrue(steps.size > 20)
        assertEquals(0, steps.first().tick)
        for (i in 1 until steps.size) {
            assertEquals(steps[i - 1].tick + 1, steps[i].tick)
            assertTrue(steps[i - 1].to.distanceTo(steps[i].from) < 1e-9, "chained at ${steps[i].tick}")
        }
        assertTrue(steps.last().to.distanceTo(Vec3(landing.x, landing.y, landing.z)) < 1e-9)
        assertTrue(landing.contactTick in 0 until steps.size)
        // the same flight without a track lands the same
        val plain = BobberFlight.fly(0.5, 64.0, 0.5, 0.0, 30.0, false, openWater)
        assertEquals(plain, landing)
        // the first move leaves the hook's spawn: 0.16 to the right of the view, 0.1 below the eye
        assertEquals(64.0 + BobberFlight.EYE - 0.1, steps.first().from.y, 1e-9)
        assertEquals(0.5 - cos(0.0) * 0.16, steps.first().from.x, 1e-9)
        assertEquals(0.5 - sin(0.0) * 0.16, steps.first().from.z, 1e-9)
    }

    private fun abs(x: Double) = kotlin.math.abs(x)
}
