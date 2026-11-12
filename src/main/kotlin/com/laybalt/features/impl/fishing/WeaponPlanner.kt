package com.laybalt.features.impl.fishing

//#if CHEAT

import com.laybalt.features.impl.fishing.BehaviorProfile.Habit
import com.laybalt.features.impl.fishing.BehaviorProfile.Profile
import com.laybalt.features.impl.fishing.BehaviorProfile.WeaponStyle

/** How you click with an item in a fight: left clicks, single right presses, or the use key held down. */
enum class ClickStyle { LEFT, RIGHT, HOLD }

/**
 * Picks the weapon for one fight the way you do in your recordings: by the creature's distance and height, how tough it
 * is and what you used on it before. Pure (no game state), so it is unit tested with hand-made profiles.
 *
 * An option is [ROD] (rod melee, whichever fishing rod you hold), [BOBBER] (your recast's bobber, only ever learned in
 * [Habit.killsBy]) or a weapon's SkyBlock id.
 */
object WeaponPlanner {
    /** Rod melee, whichever fishing rod (or rod-type weapon) you swing. */
    const val ROD = "ROD"
    /** Your recast's bobber: learned in [Habit.killsBy] for the killer's recast-first wait, never a plan. */
    const val BOBBER = "BOBBER"

    /** Fishing-rod items that are weapons: their right clicks are attacks, they never cast. */
    val ROD_WEAPONS = setOf("FLAMING_FLAY", "SOUL_WHIP")
    /** Items that move you: never weapons, never what kills a creature. */
    val MOBILITY = setOf("ASPECT_OF_THE_VOID", "ASPECT_OF_THE_END", "GRAPPLING_HOOK", "SKYBLOCK_MENU")
    /** Fishing tools that are clicked during fights without hurting anything. */
    val NOT_WEAPONS = setOf("HOTSPOT_RADAR", "WHALE_BAIT", "POISON_SAMPLE")
    /** Fishing-rod items you never fish with. */
    val NOT_FISHING_RODS = ROD_WEAPONS + "GRAPPLING_HOOK"
    /** Swords whose right click teleports you where you look: used looking down, never at a creature. */
    val TELEPORT_BLADES = setOf("HYPERION", "ASTRAEA", "SCYLLA", "VALKYRIE")
    /** Their Wither Impact teleports you this far along the view (less where a block stops it)... */
    const val TELEPORT_M = 10.0
    /** ...and its Implosion hits every mob within this of where you land (SkyBlock's 10 blocks). */
    const val IMPLOSION_M = 10.0
    /** An area's reach is checked this much wider: a creature walking in during the reaction and the ping before the use lands. */
    const val AREA_MARGIN = 1.0

    /**
     * Where an ability hits everything around it: within [radius] of anywhere on the line from your eye along the view
     * for [length] (0: around you).
     */
    class Area(val length: Double, val radius: Double)

    /**
     * The [Area] [id]'s ability hits everything in, null when it hits only what is on its way: a teleport blade's implosion
     * around anywhere its teleport may end ([TELEPORT_M], [IMPLOSION_M]); an area weapon ([plan] aimed 90 degrees off or
     * more in your fights: it hurt your creatures wherever you looked) around you as far as it reached them, never less
     * than the implosion's radius (nothing tells its real one). Both [AREA_MARGIN] wider.
     */
    fun area(id: String, plan: Plan?): Area? = when {
        id in TELEPORT_BLADES -> Area(TELEPORT_M, IMPLOSION_M + AREA_MARGIN)
        plan != null && plan.aimDeg >= 90.0 -> Area(0.0, maxOf(plan.reach, IMPLOSION_M) + AREA_MARGIN)
        else -> null
    }

    /** Fewer samples than this give the style's default envelope. */
    private const val MIN_SAMPLES = 5
    /** An item clicked this often has to beat random timing to be a weapon. */
    private const val CHANCE_USES = 10
    private const val CHANCE_MARGIN = 0.10f
    private const val MIN_EFFECTIVE = 3
    private const val ROD_REACH = 3.0
    private const val ROD_DY_MIN = - 3.0
    private const val ROD_DY_MAX = 4.5
    /** Creature habits with this many fights in a situation say more than its fights elsewhere. */
    private const val SITUATION_FIGHTS = 2
    /** The rod barely scratches it: a mean share of max hp per hit, or a max hp, beyond which the rod is a last resort. */
    private const val TINY_ROD_SHARE = 0.05
    private const val TOUGH_HP = 200_000.0
    private const val ROD_SAMPLES = 3
    private const val ROD_PENALTY = 0.1
    /** The rod takes more than 8 hits: a mean share of max hp per hit under this... */
    private const val SLOW_ROD_SHARE = 0.125
    /** ... and you fought the creature with another option this often (over all situations): that option instead. */
    private const val OTHER_FIGHTS = 2
    private const val SHRINK_REACH = 0.75
    private const val SHRINK_DY = 1.0
    private const val TIE = 1e-9

    /** One option's envelope and click style for a fight, from [plan]. */
    class Plan(
        val option: String,
        val style: ClickStyle,
        /** Horizontal distance from your feet it works up to. */
        val reach: Double,
        /** The creature's y minus yours it works within. */
        val dyMin: Double,
        val dyMax: Double,
        /** Max view error before clicking, in degrees; 90 or more means no turn is needed. */
        val aimDeg: Double,
        /** The crosshair must be on the creature (melee always does). */
        val needsCrosshair: Boolean,
        /** A turn stops once the view error is below this. */
        val aimStopDeg: Double = aimDeg / 2,
        /** Look down to this pitch instead of at the creature (teleport blades); NaN = aim at the creature. */
        val viewPitch: Float = Float.NaN
    ) {
        /** For messages: `Rod`, `Flaming Flay (hold)`, `Hyperion (right)`. */
        val label get() = if (option == ROD) "Rod" else "${BehaviorLearner.prettyId(option)} (${style.name.lowercase()})"

        override fun toString() = "$label reach %.1f dy %.1f..%.1f aim %.0f/%.0f%s%s".format(
            reach, dyMin, dyMax, aimDeg, aimStopDeg, if (needsCrosshair) " crosshair" else "", if (viewPitch.isNaN()) "" else " pitch %.0f".format(viewPitch)
        )
    }

    private class Defaults(val reach: Double, val dyMin: Double, val dyMax: Double, val aimDeg: Double, val aimStopDeg: Double, val needsCrosshair: Boolean)

    // the height range is not in the samples-based table: melee gets the rod's, ranged weapons a little more below you
    private val defaults = mapOf(
        ClickStyle.LEFT to Defaults(3.0, ROD_DY_MIN, ROD_DY_MAX, 6.0, 3.0, true),
        ClickStyle.RIGHT to Defaults(8.0, - 4.5, 4.5, 12.0, 6.0, false),
        ClickStyle.HOLD to Defaults(10.0, - 4.5, 4.5, 25.0, 12.0, false)
    )

    /** The rod's plan: melee with the crosshair on the creature, within 3 blocks and a little above you. */
    val rodPlan = Plan(ROD, ClickStyle.LEFT, ROD_REACH, ROD_DY_MIN, ROD_DY_MAX, 6.0, true, 3.0)

    /** `near`/`mid`/`far`/`vfar` (horizontal distance up to 3, 6, 10, beyond) + `/below`, `/level` or `/above` (3 blocks). */
    fun situation(hd: Double, dy: Double): String {
        val h = when {
            hd <= 3.0 -> "near"
            hd <= 6.0 -> "mid"
            hd <= 10.0 -> "far"
            else -> "vfar"
        }
        val v = when {
            dy < - 3.0 -> "below"
            dy > 3.0 -> "above"
            else -> "level"
        }
        return "$h/$v"
    }

    /**
     * A weapon you learned to use: it has a [WeaponStyle], is no fishing rod of yours, no mobility item or tool, hurt your
     * creatures at least 3 times, and (once clicked 10 times) was followed by an hp drop at least 10 points more often
     * than random moments of the same recordings were ([WeaponStyle.chance]).
     */
    fun isWeapon(profile: Profile, id: String): Boolean {
        if (id.isEmpty() || id == ROD || id == BOBBER || id in MOBILITY || id in NOT_WEAPONS) return false
        val s = profile.weaponStyles[id] ?: return false
        if (id !in ROD_WEAPONS && id in profile.fishingRods) return false
        if (s.effective < MIN_EFFECTIVE) return false
        return s.uses < CHANCE_USES || s.effective.toFloat() / s.uses >= s.chance + CHANCE_MARGIN
    }

    /**
     * A fishing-rod item ([isRodItem]: a vanilla fishing rod) that you fish with: not a rod-type weapon or grappling hook,
     * and either one your recordings saw you fish with or one they never saw you fight with. A rod no recording knows (a
     * new one, another island's) still fishes.
     */
    fun isFishingRod(profile: Profile, id: String, isRodItem: Boolean): Boolean {
        if (! isRodItem || id in NOT_FISHING_RODS) return false
        return id in profile.fishingRods || ! isWeapon(profile, id)
    }

    /** [hd], [dy] (your feet to the creature) are inside [plan]'s envelope; [shrink] keeps a margin inside it (unparking). */
    fun inEnvelope(plan: Plan, hd: Double, dy: Double, shrink: Boolean = false): Boolean {
        if (hd.isNaN() || dy.isNaN()) return false
        val reach = if (shrink) plan.reach - SHRINK_REACH else plan.reach
        val min = if (shrink) plan.dyMin + SHRINK_DY else plan.dyMin
        val max = if (shrink) plan.dyMax - SHRINK_DY else plan.dyMax
        return hd <= reach && dy >= min && dy <= max
    }

    /**
     * The envelope and click style of [id]: [rodPlan] for [ROD], else from the item's [WeaponStyle] (null without one, and
     * for [BOBBER]). Reach is the 95th percentile of your effective distances + 1 (3..16), the height range your 5th to 95th
     * percentile widened by 1.5, the aim your 75th percentile view error (the 50th stops a turn); fewer than 5 samples give
     * the style's defaults. A teleport blade is used looking down ([Plan.viewPitch]). Never throws.
     */
    fun plan(profile: Profile, id: String): Plan? = try {
        when (id) {
            ROD -> rodPlan
            BOBBER, "" -> null
            else -> profile.weaponStyles[id]?.let { cachedPlan(profile, id, it) }
        }
    }
    catch (e: Exception) {
        null
    }

    /**
     * The option to fight [creature] with now, null when nothing you have reaches it ([hd], [dy] from your feet): leave it
     * parked and fish on. [available]: the weapon ids in your hotbar plus [ROD] when a fishing rod is there; [excluded]: the
     * options that did nothing to it; [held]: the item in your hand (wins ties); [shrink]: envelopes with a margin.
     *
     * Each option that reaches it scores your habits, every frequency smoothed as (n + 1) / (N + options):
     * 4 x this creature in this situation (2 fights or more there), else 2 x this creature anywhere; plus 1 x its [group]
     * in this situation, 0.5 x all creatures in this situation and 0.5 x what killed this creature. The rod scores a tenth
     * when it barely hurts the creature, when the creature is tough and rarely rod-hit, or when only weapons killed it.
     * Ties: [held], the rod, the most effective weapon, then the id. When the rod wins but would take more than 8 hits
     * ([rodTooSlow]), the best of the other options that reach it. Deterministic, never throws.
     */
    fun choose(
        profile: Profile, creature: String, group: String, maxHp: Double, hd: Double, dy: Double,
        available: Set<String>, excluded: Set<String> = emptySet(), held: String? = null, shrink: Boolean = false
    ): Plan? {
        var plans = emptyList<Plan>()
        return try {
            plans = available.filter { it !in excluded && it != BOBBER }
                .mapNotNull { id -> if (id == ROD) rodPlan else if (isWeapon(profile, id)) plan(profile, id) else null }
                .filter { inEnvelope(it, hd, dy, shrink) }
            val best = if (plans.size <= 1) plans.firstOrNull() else pick(profile, creature, group, maxHp, situation(hd, dy), plans, held)
            // here, not in the killer alone: the rules window's summary has to say what the killer does
            val others = plans.filter { it.option != ROD }
            if (best?.option != ROD || others.isEmpty() || ! rodTooSlow(profile.habits[creature], available - excluded)) best
            else if (others.size == 1) others[0]
            else pick(profile, creature, group, maxHp, situation(hd, dy), others, held)
        }
        catch (e: Exception) {
            // an edited profile with holes in it: the rod when it reaches, else the first one that does
            plans.firstOrNull { it.option == ROD } ?: plans.firstOrNull()
        }
    }

    /**
     * The rod would take more than 8 hits on [habit]'s creature (a mean share of max hp per hit under 12.5 % over 3 hits
     * or more) and your fights show another option of [available] for it at least twice.
     */
    fun rodTooSlow(habit: Habit?, available: Set<String>): Boolean {
        val shares = habit?.rodHitShare.orEmpty()
        if (shares.size < ROD_SAMPLES || shares.average() >= SLOW_ROD_SHARE) return false
        val fights = HashMap<String, Int>()
        habit?.choices.orEmpty().values.forEach { m -> m.forEach { (o, n) -> fights.merge(o, n, Int::plus) } }
        return fights.any { (o, n) -> o != ROD && o != BOBBER && o in available && n >= OTHER_FIGHTS }
    }

    private fun pick(profile: Profile, creature: String, group: String, maxHp: Double, situation: String, plans: List<Plan>, held: String?): Plan {
        val options = plans.map { it.option }
        val habit = profile.habits[creature]
        val here = habit?.choices?.get(situation)
        val overall = habit?.choices?.values?.let(::sum)
        val groupHere = profile.groupChoices[group]?.get(situation)
        val everyone = profile.choices[situation]
        val kills = habit?.killsBy
        val rodWeak = rodPenalty(habit, maxHp)
        val scores = options.associateWith { o ->
            var v = if (here != null && here.values.sum() >= SITUATION_FIGHTS) 4 * freq(here, o, options) else 2 * freq(overall, o, options)
            v += freq(groupHere, o, options)
            v += 0.5 * freq(everyone, o, options)
            v += 0.5 * freq(kills, o, options)
            if (o == ROD && rodWeak) v * ROD_PENALTY else v
        }
        val best = scores.values.max()
        val tied = plans.filter { scores.getValue(it.option) >= best - TIE }
        return tied.minWith(compareBy<Plan>({ it.option != held }, { it.option != ROD }, { - effectiveness(profile, it.option) }, { it.option }))
    }

    /**
     * The rod is a last resort: 3 rod hits or more took a mean under 5 % of its max hp each, it is tough (200k hp) without
     * those 3 hits, or 3 kills or more were all by weapons.
     */
    private fun rodPenalty(habit: Habit?, maxHp: Double): Boolean {
        val shares = habit?.rodHitShare.orEmpty()
        if (shares.size >= ROD_SAMPLES && shares.average() < TINY_ROD_SHARE) return true
        val hp = if (maxHp > 0) maxHp else habit?.maxHp ?: 0.0
        if (shares.size < ROD_SAMPLES && hp >= TOUGH_HP) return true
        val kills = habit?.killsBy.orEmpty()
        val attackKills = kills.entries.sumOf { (o, n) -> if (o == BOBBER) 0 else n }
        return attackKills >= 3 && (kills[ROD] ?: 0) == 0
    }

    /** (n + 1) / (N + options), N summed over [options] only; no map at all says nothing (1 / options). */
    private fun freq(counts: Map<String, Int>?, option: String, options: List<String>): Double {
        if (counts == null) return 1.0 / options.size
        val total = options.sumOf { counts[it] ?: 0 }
        return ((counts[option] ?: 0) + 1.0) / (total + options.size)
    }

    private fun sum(maps: Collection<Map<String, Int>>): Map<String, Int> {
        val out = HashMap<String, Int>()
        maps.forEach { m -> m.forEach { (k, v) -> out.merge(k, v, Int::plus) } }
        return out
    }

    private fun effectiveness(profile: Profile, option: String): Double {
        val s = profile.weaponStyles[option] ?: return 0.0
        return if (s.uses > 0) s.effective.toDouble() / s.uses else 0.0
    }

    /**
     * For the rules window: the option you would get along the level line with every weapon you learned at hand, e.g.
     * `Rod within 3 m, Flaming Flay (hold) to 11 m` ([choose], so the rod that would take more than 8 hits is left out
     * as the killer leaves it out).
     */
    fun summary(profile: Profile, creature: String, group: String): String = try {
        val available = profile.weaponStyles.keys.filterTo(LinkedHashSet()) { isWeapon(profile, it) } + ROD
        val maxHp = profile.habits[creature]?.maxHp ?: 0.0
        val segments = mutableListOf<Pair<String, Double>>()
        var hd = 0.0
        while (hd <= 16.0) {
            val label = choose(profile, creature, group, maxHp, hd, 0.0, available)?.label
            if (label != null) {
                if (segments.lastOrNull()?.first == label) segments[segments.size - 1] = label to hd else segments += label to hd
            }
            hd += 0.25
        }
        if (segments.isEmpty()) "Nothing you have reaches it: parked"
        else segments.mapIndexed { i, (label, to) -> "$label ${if (i == 0) "within" else "to"} %.0f m".format(to) }.joinToString(", ")
    }
    catch (e: Exception) {
        "Rod within 3 m"
    }

    private data class Key(val style: ClickStyle, val uses: Int, val effective: Int, val onTarget: Int, val reach: Int, val dy: Int, val aimErr: Int, val pitch: Int)
    private class Cached(val key: Key, val plan: Plan)

    // plan() sorts lists of hundreds of samples and the killer asks several times a tick: cached per profile instance,
    // while the style's counts stay the same (a test or the learner may still be filling it)
    private var cacheFor: Profile? = null
    private val cache = HashMap<String, Cached>()

    @Synchronized
    private fun cachedPlan(profile: Profile, id: String, s: WeaponStyle): Plan {
        if (cacheFor !== profile) {
            cacheFor = profile
            cache.clear()
        }
        val key = Key(s.style, s.uses, s.effective, s.onTarget, s.reach.size, s.dy.size, s.aimErr.size, s.pitch.size)
        cache[id]?.takeIf { it.key == key }?.let { return it.plan }
        return buildPlan(id, s).also { cache[id] = Cached(key, it) }
    }

    private fun buildPlan(id: String, s: WeaponStyle): Plan {
        val d = defaults.getValue(s.style)
        val r = if (s.reach.size >= MIN_SAMPLES) (q(s.reach, 0.95) + 1.0).coerceIn(3.0, 16.0) else d.reach
        val dyMin = if (s.dy.size >= MIN_SAMPLES) q(s.dy, 0.05) - 1.5 else d.dyMin
        val dyMax = if (s.dy.size >= MIN_SAMPLES) q(s.dy, 0.95) + 1.5 else d.dyMax
        val aim = if (s.aimErr.size >= MIN_SAMPLES) q(s.aimErr, 0.75).coerceIn(3.0, 180.0) else d.aimDeg
        val stop = if (s.aimErr.size >= MIN_SAMPLES) q(s.aimErr, 0.5).coerceIn(2.0, aim) else minOf(d.aimStopDeg, aim)
        val crosshair = when {
            s.style == ClickStyle.LEFT -> true
            s.effective >= MIN_SAMPLES -> s.onTarget >= 0.6 * s.effective
            else -> d.needsCrosshair
        }
        var view = Float.NaN
        if (aim >= 90.0 && s.pitch.size >= MIN_SAMPLES && q(s.pitch, 0.25) >= 60.0) view = q(s.pitch, 0.5).toFloat().coerceIn(60f, 90f)
        // a teleport blade clicked towards a creature moves you to it: always looking down
        if (id in TELEPORT_BLADES) view = if (view.isNaN()) 89f else maxOf(view, 85f)
        return Plan(id, s.style, r, dyMin, dyMax, aim, crosshair, stop, view)
    }

    /** The [p] quantile the way the recordings were analysed: sorted[min(n - 1, floor(p * n))]. */
    private fun q(a: List<Float>, p: Double): Double {
        val s = a.sorted()
        return s[minOf(s.size - 1, (p * s.size).toInt())].toDouble()
    }
}

//#endif
