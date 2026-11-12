package fishing

import com.laybalt.features.impl.fishing.BehaviorProfile.Habit
import com.laybalt.features.impl.fishing.BehaviorProfile.Profile
import com.laybalt.features.impl.fishing.BehaviorProfile.WeaponStyle
import com.laybalt.features.impl.fishing.ClickStyle
import com.laybalt.features.impl.fishing.WeaponPlanner
import com.laybalt.features.impl.fishing.WeaponPlanner.BOBBER
import com.laybalt.features.impl.fishing.WeaponPlanner.ROD
import kotlin.test.*

/** [WeaponPlanner] on hand-made profiles: envelopes, the weapon test, the scoring and its tie-break. */
class WeaponPlannerTest {
    /**
     * A Flaming Flay held from up to 10 blocks: reach p95 10 (plan 11), dy p5 -5.6..p95 1.6 (plan -7.1..3.1), view error
     * p50 25 and p75 32.5, never with the crosshair on the creature.
     */
    private fun flay() = WeaponStyle(
        style = ClickStyle.HOLD, uses = 100, effective = 60, chance = 0.15f, right = 20, held = 80,
        reach = MutableList(20) { 0.5f + it * 0.5f },
        dy = MutableList(20) { - 6f + it * 0.4f },
        aimErr = MutableList(20) { 10f + it * 1.5f },
        pitch = MutableList(20) { 10f }
    )

    /** An area attack clicked looking straight down: view errors 100..172, pitch 88..89.8 unless given. */
    private fun areaAttack(pitch: List<Float> = List(10) { 88f + it * 0.2f }) = WeaponStyle(
        style = ClickStyle.RIGHT, uses = 50, effective = 40, chance = 0.15f, right = 50,
        reach = MutableList(10) { 0.5f + it * 0.25f },
        dy = MutableList(10) { 0f },
        aimErr = MutableList(10) { 100f + it * 8f },
        pitch = pitch.toMutableList()
    )

    private fun profile(vararg styles: Pair<String, WeaponStyle>) = Profile(sessions = 1).apply {
        fishingRods = linkedSetOf("ROD_OF_THE_SEA")
        styles.forEach { (id, s) -> weaponStyles[id] = s }
    }

    private val flayProfile get() = profile("FLAMING_FLAY" to flay())
    private val rodAndFlay = setOf(ROD, "FLAMING_FLAY")

    private fun Profile.choose(creature: String, hd: Double, dy: Double, available: Set<String> = rodAndFlay, maxHp: Double = 1000.0, excluded: Set<String> = emptySet(), held: String? = null, shrink: Boolean = false) =
        WeaponPlanner.choose(this, creature, habits[creature]?.group.orEmpty(), maxHp, hd, dy, available, excluded, held, shrink)?.option

    private fun Profile.habit(name: String, build: Habit.() -> Unit) {
        habits.getOrPut(name) { Habit() }.build()
    }

    private fun counts(vararg pairs: Pair<String, Int>) = linkedMapOf(*pairs)

    @Test
    fun `situations are cut at 3, 6 and 10 blocks and 3 blocks up or down`() {
        assertEquals("near/level", WeaponPlanner.situation(3.0, 3.0))
        assertEquals("mid/below", WeaponPlanner.situation(3.01, - 3.01))
        assertEquals("far/above", WeaponPlanner.situation(10.0, 3.5))
        assertEquals("vfar/level", WeaponPlanner.situation(10.5, - 3.0))
    }

    @Test
    fun `the rod plan and the defaults for few samples`() {
        val rod = assertNotNull(WeaponPlanner.plan(Profile(), ROD))
        assertEquals(ClickStyle.LEFT, rod.style)
        assertEquals(3.0, rod.reach)
        assertEquals(- 3.0, rod.dyMin)
        assertEquals(4.5, rod.dyMax)
        assertTrue(rod.needsCrosshair)
        assertTrue(rod.viewPitch.isNaN())

        val p = profile(
            "SWORD" to WeaponStyle(style = ClickStyle.LEFT, uses = 4, effective = 4, reach = mutableListOf(1f, 2f)),
            "WAND" to WeaponStyle(style = ClickStyle.RIGHT, uses = 4, effective = 4),
            "WHIP" to WeaponStyle(style = ClickStyle.HOLD, uses = 4, effective = 4)
        )
        val sword = assertNotNull(WeaponPlanner.plan(p, "SWORD"))
        assertEquals(3.0, sword.reach)
        assertEquals(6.0, sword.aimDeg)
        assertEquals(3.0, sword.aimStopDeg)
        assertTrue(sword.needsCrosshair)
        val wand = assertNotNull(WeaponPlanner.plan(p, "WAND"))
        assertEquals(8.0, wand.reach)
        assertEquals(12.0, wand.aimDeg)
        assertEquals(6.0, wand.aimStopDeg)
        assertFalse(wand.needsCrosshair)
        val whip = assertNotNull(WeaponPlanner.plan(p, "WHIP"))
        assertEquals(10.0, whip.reach)
        assertEquals(25.0, whip.aimDeg)
        assertEquals(12.0, whip.aimStopDeg)
        assertFalse(whip.needsCrosshair)
        assertNull(WeaponPlanner.plan(p, "NOTHING_LEARNED"))
        assertNull(WeaponPlanner.plan(p, BOBBER))
    }

    @Test
    fun `envelopes come from your samples`() {
        val plan = assertNotNull(WeaponPlanner.plan(flayProfile, "FLAMING_FLAY"))
        assertEquals(ClickStyle.HOLD, plan.style)
        assertEquals(11.0, plan.reach, 1e-4, "p95 + 1")
        assertEquals(- 7.1, plan.dyMin, 1e-4, "p5 - 1.5")
        assertEquals(3.1, plan.dyMax, 1e-4, "p95 + 1.5")
        assertEquals(32.5, plan.aimDeg, 1e-4, "p75")
        assertEquals(25.0, plan.aimStopDeg, 1e-4, "p50")
        assertFalse(plan.needsCrosshair)
        assertTrue(plan.viewPitch.isNaN(), "aimed at the creature")

        fun right(reach: Float, onTarget: Int) = WeaponStyle(style = ClickStyle.RIGHT, uses = 10, effective = 10, onTarget = onTarget, reach = MutableList(10) { reach }, aimErr = MutableList(10) { 1f })
        val p = profile("FAR" to right(30f, 6), "CLOSE" to right(0.5f, 5))
        val far = assertNotNull(WeaponPlanner.plan(p, "FAR"))
        assertEquals(16.0, far.reach, "at most 16")
        assertTrue(far.needsCrosshair, "on the creature at 60 % of its effective clicks")
        assertEquals(3.0, far.aimDeg, "at least 3")
        assertEquals(2.0, far.aimStopDeg, "at least 2")
        val close = assertNotNull(WeaponPlanner.plan(p, "CLOSE"))
        assertEquals(3.0, close.reach, "at least 3")
        assertFalse(close.needsCrosshair)
    }

    @Test
    fun `near and level picks the rod, farther or lower the held weapon, nothing out of reach`() {
        val p = flayProfile
        assertEquals(ROD, p.choose("Squid", 2.0, 0.0))
        assertEquals("FLAMING_FLAY", p.choose("Squid", 8.0, 0.0))
        assertEquals("FLAMING_FLAY", p.choose("Squid", 5.0, - 4.0))
        assertEquals("FLAMING_FLAY", p.choose("Squid", 2.0, - 4.0), "below the rod's reach")
        assertNull(p.choose("Squid", 5.0, - 25.0), "a lava creature sunk 25 blocks: parked")
        assertNull(p.choose("Squid", 12.0, 0.0))
        assertNull(p.choose("Squid", Double.NaN, 0.0))
        assertNull(p.choose("Squid", 2.0, 0.0, available = emptySet()))
        assertEquals(ROD, p.choose("Squid", 2.0, 0.0, available = setOf(ROD, "ASPECT_OF_THE_VOID")), "nothing learned for the void: only the rod")
    }

    @Test
    fun `a creature the rod barely hurts gets the weapon even near`() {
        val p = flayProfile
        p.habit("Manta Ray") { rodHitShare = mutableListOf(0.007f, 0f, 0.008f) }
        assertEquals("FLAMING_FLAY", p.choose("Manta Ray", 2.0, 0.0))
        // two hits say nothing yet
        p.habit("Banshee") { rodHitShare = mutableListOf(0.01f, 0.02f) }
        assertEquals(ROD, p.choose("Banshee", 2.0, 0.0))
        // a mean of 22 % per hit is a rod creature
        p.habit("Inkling") { rodHitShare = mutableListOf(0.22f, 0.2f, 0.25f) }
        assertEquals(ROD, p.choose("Inkling", 2.0, 0.0, maxHp = 500_000.0))
    }

    @Test
    fun `the rod is left out where it would take more than 8 hits and another option fought the creature twice`() {
        val p = flayProfile
        // the Snapping Turtle of the Bayou recording: 10 % of its hp a rod hit, the rod scoring best near
        p.habit("Snapping Turtle") {
            rodHitShare = MutableList(8) { 0.1f }
            choices = linkedMapOf("near/level" to counts(ROD to 2, "FLAMING_FLAY" to 1), "vfar/level" to counts("FLAMING_FLAY" to 1))
        }
        assertEquals("FLAMING_FLAY", p.choose("Snapping Turtle", 2.0, 0.0))
        assertTrue(WeaponPlanner.rodTooSlow(p.habits["Snapping Turtle"], rodAndFlay))
        // the rules window says what the killer does
        assertEquals("Flaming Flay (hold) within 11 m", WeaponPlanner.summary(p, "Snapping Turtle", "WATER_HOTSPOT"))
        // without the Flay at hand (or with it excluded) the rod is all there is
        assertEquals(ROD, p.choose("Snapping Turtle", 2.0, 0.0, available = setOf(ROD)))
        assertEquals(ROD, p.choose("Snapping Turtle", 2.0, 0.0, excluded = setOf("FLAMING_FLAY")))
        // one Flay fight says too little
        p.habit("Snapping Turtle") { choices = linkedMapOf("near/level" to counts(ROD to 2, "FLAMING_FLAY" to 1)) }
        assertEquals(ROD, p.choose("Snapping Turtle", 2.0, 0.0))
        // 8 hits or fewer: the rod
        p.habit("Snapping Turtle") {
            rodHitShare = MutableList(8) { 0.13f }
            choices = linkedMapOf("near/level" to counts(ROD to 2, "FLAMING_FLAY" to 1), "vfar/level" to counts("FLAMING_FLAY" to 1))
        }
        assertEquals(ROD, p.choose("Snapping Turtle", 2.0, 0.0))
        assertEquals("Rod within 3 m, Flaming Flay (hold) to 11 m", WeaponPlanner.summary(p, "Snapping Turtle", "WATER_HOTSPOT"))
    }

    @Test
    fun `a tough creature you never rod-hit avoids the rod`() {
        val p = flayProfile
        assertEquals("FLAMING_FLAY", p.choose("Titanoboa", 2.0, 0.0, maxHp = 300_000.0))
        assertEquals(ROD, p.choose("Titanoboa", 2.0, 0.0, maxHp = 150_000.0))
        // the habit's max hp when the caller knows none
        p.habit("Titanoboa") { maxHp = 300_000.0 }
        assertEquals("FLAMING_FLAY", p.choose("Titanoboa", 2.0, 0.0, maxHp = 0.0))
        // only the rod reaches it: still the rod
        assertEquals(ROD, p.choose("Titanoboa", 2.0, 0.0, available = setOf(ROD), maxHp = 300_000.0))
    }

    @Test
    fun `a creature only weapons killed makes the rod a last resort`() {
        val p = flayProfile
        p.habit("Alligator") {
            choices = linkedMapOf("near/level" to counts(ROD to 1))
            killsBy = counts("FLAMING_FLAY" to 3, BOBBER to 5)
        }
        assertEquals("FLAMING_FLAY", p.choose("Alligator", 2.0, 0.0))
        // two weapon kills are not enough, and the rod's one fight there wins
        p.habit("Alligator") { killsBy = counts("FLAMING_FLAY" to 2, BOBBER to 5) }
        assertEquals(ROD, p.choose("Alligator", 2.0, 0.0))
        // one rod kill among them: the rod stays an option
        p.habit("Alligator") { killsBy = counts("FLAMING_FLAY" to 3, ROD to 1) }
        assertEquals(ROD, p.choose("Alligator", 2.0, 0.0))
    }

    @Test
    fun `this creature's fights in this situation outweigh its fights elsewhere, from two on`() {
        val p = flayProfile
        p.habit("Squid") { choices = linkedMapOf("near/level" to counts("FLAMING_FLAY" to 2), "far/level" to counts(ROD to 10)) }
        assertEquals("FLAMING_FLAY", p.choose("Squid", 2.0, 0.0))
        p.habit("Squid") { choices = linkedMapOf("near/level" to counts("FLAMING_FLAY" to 1), "far/level" to counts(ROD to 10)) }
        assertEquals(ROD, p.choose("Squid", 2.0, 0.0), "one fight there: all its fights decide")
    }

    @Test
    fun `the group's and everyone's habits count for a creature you never fought`() {
        val p = flayProfile
        p.groupChoices["WATER"] = linkedMapOf("near/level" to counts("FLAMING_FLAY" to 5))
        assertEquals("FLAMING_FLAY", WeaponPlanner.choose(p, "Sea Archer", "WATER", 1000.0, 2.0, 0.0, rodAndFlay)?.option)
        assertEquals(ROD, WeaponPlanner.choose(p, "Moogma", "LAVA_CRIMSON_ISLE", 1000.0, 2.0, 0.0, rodAndFlay)?.option)
        p.choices["near/level"] = counts("FLAMING_FLAY" to 5)
        assertEquals("FLAMING_FLAY", WeaponPlanner.choose(p, "Moogma", "LAVA_CRIMSON_ISLE", 1000.0, 2.0, 0.0, rodAndFlay)?.option)
    }

    @Test
    fun `a teleport blade is used looking down`() {
        val p = profile("HYPERION" to areaAttack(), "AREA_WAND" to areaAttack(), "LOW_WAND" to areaAttack(List(10) { 10f }))
        val hyperion = assertNotNull(WeaponPlanner.plan(p, "HYPERION"))
        assertTrue(hyperion.aimDeg >= 90.0, "$hyperion")
        assertTrue(hyperion.viewPitch >= 80f, "$hyperion")
        assertFalse(hyperion.needsCrosshair)
        assertEquals(89f, assertNotNull(WeaponPlanner.plan(p, "AREA_WAND")).viewPitch, "learned: pitch p50")
        assertTrue(assertNotNull(WeaponPlanner.plan(p, "LOW_WAND")).viewPitch.isNaN(), "clicked looking ahead: no pitch to keep")

        val noPitch = profile("HYPERION" to areaAttack(emptyList()))
        assertEquals(89f, assertNotNull(WeaponPlanner.plan(noPitch, "HYPERION")).viewPitch)
        val flat = profile("HYPERION" to areaAttack(List(10) { 70f }))
        assertEquals(85f, assertNotNull(WeaponPlanner.plan(flat, "HYPERION")).viewPitch, "never above 85 for a blade")
        val aimed = profile("HYPERION" to WeaponStyle(style = ClickStyle.RIGHT, uses = 10, effective = 10, aimErr = MutableList(10) { 5f }))
        assertEquals(89f, assertNotNull(WeaponPlanner.plan(aimed, "HYPERION")).viewPitch, "even aimed: a blade teleports you to what you look at")
    }

    @Test
    fun `excluded options are left out and the choice is deterministic`() {
        val p = flayProfile
        assertEquals("FLAMING_FLAY", p.choose("Squid", 2.0, 0.0, excluded = setOf(ROD)))
        assertNull(p.choose("Squid", 2.0, 0.0, excluded = setOf(ROD, "FLAMING_FLAY")))
        assertNull(p.choose("Squid", 8.0, 0.0, excluded = setOf("FLAMING_FLAY")))
        repeat(5) {
            assertEquals(ROD, p.choose("Squid", 2.0, 0.0, available = linkedSetOf("FLAMING_FLAY", ROD)))
            assertEquals(ROD, p.choose("Squid", 2.0, 0.0, available = linkedSetOf(ROD, "FLAMING_FLAY")))
        }
    }

    @Test
    fun `the bobber is never a plan`() {
        val p = flayProfile
        p.habit("Frozen Steve") { killsBy = counts(BOBBER to 50) }
        assertEquals(ROD, p.choose("Frozen Steve", 2.0, 0.0, available = setOf(BOBBER, ROD)))
        assertNull(p.choose("Frozen Steve", 2.0, 0.0, available = setOf(BOBBER)))
        assertNull(WeaponPlanner.plan(p, BOBBER))
    }

    @Test
    fun `smoothed scores tie to the item in hand, the rod, the most effective weapon, then the id`() {
        fun weapon(effective: Int) = WeaponStyle(style = ClickStyle.RIGHT, uses = 100, effective = effective, chance = 0.15f)
        val p = profile("W_A" to weapon(50), "W_B" to weapon(70), "W_C" to weapon(70))
        val weapons = setOf("W_A", "W_B", "W_C")
        assertEquals("W_B", p.choose("Squid", 2.0, 0.0, available = weapons), "most effective, then alphabetical")
        assertEquals("W_A", p.choose("Squid", 2.0, 0.0, available = weapons, held = "W_A"))
        assertEquals(ROD, p.choose("Squid", 2.0, 0.0, available = weapons + ROD))
        assertEquals("W_C", p.choose("Squid", 2.0, 0.0, available = weapons + ROD, held = "W_C"))
        // one fight each: still a tie, the rod; one more for a weapon wins it
        p.habit("Squid") { choices = linkedMapOf("near/level" to counts(ROD to 1), "mid/level" to counts("W_A" to 1)) }
        assertEquals(ROD, p.choose("Squid", 2.0, 0.0, available = setOf("W_A", ROD)))
        p.habit("Squid") { choices = linkedMapOf("near/level" to counts(ROD to 1), "mid/level" to counts("W_A" to 2)) }
        assertEquals("W_A", p.choose("Squid", 2.0, 0.0, available = setOf("W_A", ROD)))
    }

    @Test
    fun `shrink keeps a margin inside the envelope`() {
        val rod = WeaponPlanner.rodPlan
        assertTrue(WeaponPlanner.inEnvelope(rod, 2.5, 0.0))
        assertFalse(WeaponPlanner.inEnvelope(rod, 2.5, 0.0, shrink = true))
        assertTrue(WeaponPlanner.inEnvelope(rod, 2.0, 0.0, shrink = true))
        assertTrue(WeaponPlanner.inEnvelope(rod, 1.0, 4.0))
        assertFalse(WeaponPlanner.inEnvelope(rod, 1.0, 4.0, shrink = true))
        assertFalse(WeaponPlanner.inEnvelope(rod, 1.0, - 2.5, shrink = true))
        val p = flayProfile
        assertEquals(ROD, p.choose("Squid", 2.5, 0.0, available = setOf(ROD)))
        assertNull(p.choose("Squid", 2.5, 0.0, available = setOf(ROD), shrink = true))
        assertEquals("FLAMING_FLAY", p.choose("Squid", 2.5, 0.0, shrink = true))
    }

    @Test
    fun `weapons are learned, effective beyond chance, and never fishing rods, mobility or tools`() {
        fun used(uses: Int, effective: Int, chance: Float = 0.2f) = WeaponStyle(style = ClickStyle.RIGHT, uses = uses, effective = effective, chance = chance)
        val p = profile(
            "ASPECT_OF_THE_VOID" to used(100, 90),
            "HOTSPOT_RADAR" to used(100, 90),
            "ROD_OF_THE_SEA" to used(100, 90),
            "TWICE" to used(2, 2),
            "LUCKY" to used(20, 5),
            "GOOD" to used(20, 7),
            "FEW" to used(8, 3, chance = 0.9f)
        )
        assertFalse(WeaponPlanner.isWeapon(p, "ASPECT_OF_THE_VOID"))
        assertFalse(WeaponPlanner.isWeapon(p, "HOTSPOT_RADAR"))
        assertFalse(WeaponPlanner.isWeapon(p, "ROD_OF_THE_SEA"), "you fish with it")
        assertFalse(WeaponPlanner.isWeapon(p, "TWICE"), "3 effective clicks at least")
        assertFalse(WeaponPlanner.isWeapon(p, "LUCKY"), "25 % against a 20 % chance")
        assertTrue(WeaponPlanner.isWeapon(p, "GOOD"), "35 % against a 20 % chance")
        assertTrue(WeaponPlanner.isWeapon(p, "FEW"), "the chance counts from 10 uses")
        assertFalse(WeaponPlanner.isWeapon(p, "NEVER_USED"))
        assertFalse(WeaponPlanner.isWeapon(p, ROD))
        assertNull(p.choose("Squid", 2.0, 0.0, available = setOf("ASPECT_OF_THE_VOID", "LUCKY")))
    }

    @Test
    fun `fishing rods are rod items that are no rod-type weapon, grappling hook or learned weapon`() {
        val p = profile("NEW_WHIP" to WeaponStyle(style = ClickStyle.HOLD, uses = 30, effective = 20, chance = 0.1f), "ROD_OF_THE_SEA" to WeaponStyle(uses = 30, effective = 20))
        assertFalse(WeaponPlanner.isFishingRod(p, "FLAMING_FLAY", isRodItem = true))
        assertFalse(WeaponPlanner.isFishingRod(p, "SOUL_WHIP", isRodItem = true))
        assertFalse(WeaponPlanner.isFishingRod(p, "GRAPPLING_HOOK", isRodItem = true))
        assertTrue(WeaponPlanner.isFishingRod(p, "SOME_NEW_ROD", isRodItem = true), "a rod no recording knows still fishes")
        assertFalse(WeaponPlanner.isFishingRod(p, "NEW_WHIP", isRodItem = true), "learned as a weapon")
        assertTrue(WeaponPlanner.isFishingRod(p, "ROD_OF_THE_SEA", isRodItem = true))
        assertFalse(WeaponPlanner.isFishingRod(p, "ROD_OF_THE_SEA", isRodItem = false))
        assertTrue(WeaponPlanner.isFishingRod(Profile(), "", isRodItem = true), "a plain fishing rod")
    }

    @Test
    fun `the summary walks the level line out to the farthest reach`() {
        assertEquals("Rod within 3 m, Flaming Flay (hold) to 11 m", WeaponPlanner.summary(flayProfile, "Squid", "WATER"))
        assertEquals("Rod within 3 m", WeaponPlanner.summary(Profile(), "Squid", "WATER"))
        val tough = flayProfile
        tough.habit("Titanoboa") { maxHp = 1_000_000.0 }
        assertEquals("Flaming Flay (hold) within 11 m", WeaponPlanner.summary(tough, "Titanoboa", "BACKWATER_BAYOU"))
    }
}
