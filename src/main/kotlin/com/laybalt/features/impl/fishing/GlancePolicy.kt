package com.laybalt.features.impl.fishing

//#if CHEAT

import com.laybalt.features.impl.fishing.BehaviorProfile.Glances
import com.laybalt.features.impl.fishing.MotorModel.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Your looks around and back (looks/SPEC §3.3): now and then the view leaves where it rests in one straight stroke, at
 * nothing in particular or at the spot where your creature just died, dwells there and most of the time comes back,
 * short of where it started; some looks chain into a second one from where the first ended. When they start
 * is learned per phase ([Phase]: waiting for a bite, a fight the bobber or an area weapon kills, a fight that needs your
 * aim, the seconds after a kill), more often right after one of your creatures died; where they go from your own looks'
 * offsets. Nothing learned ([Glances.learned]) means no looks at all.
 *
 * A look never starts unless its whole episode (the look, the dwell, the return) fits in the time the view is free
 * ([Inputs.windowMs]); with the killer's turn back owed it is the return: a look has to be over by the time it is due,
 * and the dwell ends then. A look is given up without a return when another move takes the view ([Inputs.busy], [drop]).
 * It never sets your fishing view. One instance ([HumanAim.glances]) fed once a tick by whoever drives the view then:
 * Auto Fishing between fights, the killer in its fights. Pure: no Minecraft calls, deterministic for a given [Random].
 */
class GlancePolicy(private val rng: Random = Random.Default) {
    /** When the view may look around, in [Glances.PHASES] order. */
    enum class Phase { WAITING, FIGHT_FREE, FIGHT_AIM, AFTER_KILL }

    /** What to do with the view this tick: start a look or the return to [goal], or the episode was given up. */
    enum class Decision { NONE, LOOK, RETURN, DROP }

    enum class State {
        /** No look out (a look that did not come back may still keep the view away, [out]). */
        REST,
        /** A look's move is in flight. */
        OUT,
        /** The view rests where the look ended, until [dwellEnd]. */
        DWELL,
        /** The return's move is in flight. */
        BACK,
    }

    /**
     * This tick as the caller sees it.
     * - [phase]: null when no look may start (the gates of looks/SPEC §3.4 that hold in every phase failed).
     * - [home]: your fishing view (null: none yet); [windowMs]: how long the view is free (infinite when nothing will need it).
     * - [sinceOwnDeathMs]: since the last death of your creatures; [deadSpot]: the view on where one died 1.5 s ago or less.
     * - [returnNow]: a cast or recast is due and waits for the view: it comes back at once.
     * - [turnBackOwed]: the killer's turn back will bring the view home: no return of its own, unless a cast is due.
     * - [moving]: a move of the looks is in flight; [busy]: any other move is.
     * - [returnNotBefore]: something the look could not plan for brought the cast nearer (a bite, a cast let go from where
     *   a look stayed away): your hand reacts to it by then, and no return starts before ([TimingModel.Action.GLANCE_REACTION]).
     */
    class Inputs(
        val now: Long,
        val phase: Phase?,
        val view: View,
        val home: View?,
        val windowMs: Double = Double.POSITIVE_INFINITY,
        val sinceOwnDeathMs: Double = Double.POSITIVE_INFINITY,
        val deadSpot: View? = null,
        val returnNow: Boolean = false,
        val turnBackOwed: Boolean = false,
        val moving: Boolean = false,
        val busy: Boolean = false,
        val returnNotBefore: Long = Long.MIN_VALUE,
    )

    /** Your learned looks ([Table.of] your profile's). */
    var table = Table.OFF
    /** Your hand for a look ([HumanAim.glanceParamsOf]) and for its return ([HumanAim.returnParamsOf]): what an episode's time is planned with. */
    var lookParams = MotorParams()
    var backParams = MotorParams()

    var state = State.REST
        private set
    /** Where [Decision.LOOK] and [Decision.RETURN] aim. */
    var goal: View? = null
        private set
    /** The view the episode started from (null without one). */
    var start: View? = null
        private set
    /** Where the last look came to rest: the rest nudges of its dwell drift back to it. */
    var endView: View? = null
        private set
    /** When the dwell ends (the next look of a chain, or the return). */
    var dwellEnd = Long.MIN_VALUE
        private set
    /** An episode keeps the view away: a look out, its dwell or return, or a look that did not come back. */
    val out get() = state != State.REST || away != null

    private var startHome = false
    private var returns = false
    private var dwell = 0.0
    private var chainNext = false
    private var lookMs = 0.0
    /** A look that did not come back left the view away from [start]. */
    private var away: View? = null
    private var restUntil = Long.MIN_VALUE
    private var lastAt = Long.MIN_VALUE
    private var wasBusy = false
    /**
     * Time fed per phase, and of it the time a look could have started (no episode out, no other move, the view free for
     * [FREE_WINDOW_MS] or more, at home where that is needed): your rate counts all of a phase's time, so the looks make
     * up in the free part for the rest (a fight's aim, a turn back, the looks' own episodes), by at most [COMP_MAX].
     */
    private val phaseMs = DoubleArray(Phase.entries.size)
    private val freeMs = DoubleArray(Phase.entries.size)

    /** This tick's decision; [goal] goes with a [Decision.LOOK] or [Decision.RETURN]. */
    fun update(i: Inputs): Decision {
        goal = null
        val dt = if (lastAt == Long.MIN_VALUE) TimingModel.TICK_MS else (i.now - lastAt).coerceIn(0L, MAX_DT_MS)
        lastAt = i.now
        i.phase?.let { p ->
            phaseMs[p.ordinal] += dt
            if (free(i, p)) freeMs[p.ordinal] += dt
        }
        // another move took the view (the killer's, an idle one): the episode is over, without a return
        if (i.busy) {
            wasBusy = true
            if (! out) return Decision.NONE
            drop()
            return Decision.DROP
        }
        if (wasBusy) {
            wasBusy = false
            restUntil = max(restUntil, i.now + AFTER_MOVE_MS)
        }
        return when (state) {
            State.OUT -> {
                if (i.moving) return Decision.NONE
                // the look came to rest: the next one of a chain after a short gap, else the dwell
                endView = i.view
                state = State.DWELL
                chainNext = rng.nextDouble() < table.chainP
                val gap = if (chainNext) table.chainGap(rng) else 0.0
                if (chainNext && gap + lookMs + episodeMs(i, angle(i.view, start ?: i.view)) > i.windowMs) chainNext = false
                dwellEnd = i.now + (if (chainNext) gap else dwell).toLong()
                Decision.NONE
            }
            State.DWELL -> dwelling(i)
            State.BACK -> {
                if (i.moving) return Decision.NONE
                rest(i.now)
                Decision.NONE
            }
            State.REST -> {
                val from = away
                if (from != null) {
                    if (closes(i) && (! i.turnBackOwed || i.returnNow)) return if (reacting(i)) Decision.NONE else back(i, from)
                    // a look that stayed away holds the next cast in every phase after it (Auto Fishing casts in all of
                    // them), until it comes back or another move takes the view; only where looks may start away from
                    // home does another one go out meanwhile
                    if (i.phase != Phase.AFTER_KILL && i.phase != Phase.FIGHT_AIM) return Decision.NONE
                }
                hazard(i, dt)
            }
        }
    }

    /**
     * Back to rest from any state: the episode is forgotten, no return follows (a killer move, an abort, a screen, your
     * input, a cast from where a look that stayed away left the view).
     */
    fun drop() {
        state = State.REST
        start = null
        away = null
        chainNext = false
        dwellEnd = Long.MIN_VALUE
    }

    /** The move the last [Decision.LOOK] or [Decision.RETURN] asked for could not start (no player). */
    fun failed() = drop()

    /** The state for a trace line: `out`, `dwell` or `back` while an episode is active, `away` after a look that stayed, else null. */
    val traceName: String? get() = when (state) {
        State.OUT -> "out"
        State.DWELL -> "dwell"
        State.BACK -> "back"
        State.REST -> if (away != null) "away" else null
    }

    private fun dwelling(i: Inputs): Decision {
        val from = start ?: return Decision.NONE.also { drop() }
        if (closes(i)) {
            // the dwell lasts until your hand has reacted to what closed the window
            if (reacting(i)) return Decision.NONE
            // a cast does not wait for the turn back: it would wait for that turn back otherwise
            if (! i.turnBackOwed || i.returnNow) return back(i, from)
            // the turn back is due: it is the look's return, the dwell ends with it
            if (i.phase == null || i.windowMs <= 0.0) return Decision.NONE.also { stay(i) }
        }
        if (i.now < dwellEnd) return Decision.NONE
        if (chainNext) {
            chainNext = false
            val g = target(i)
            if (g != null && lookTime(i.view, g) + episodeMs(i, angle(g, from)) <= i.windowMs) return look(i, g)
        }
        if (returns && ! i.turnBackOwed) return back(i, from)
        stay(i)
        return Decision.NONE
    }

    /** The view has to come back now: a cast waiting, the phase gone, or the window closing on the return. */
    private fun closes(i: Inputs) = i.returnNow || i.phase == null || i.windowMs <= backTime(angle(i.view, start ?: away ?: i.view)) + RETURN_LEAD_MS

    /** Your hand has not reacted yet to what brought the cast nearer ([Inputs.returnNotBefore]): the return waits. */
    private fun reacting(i: Inputs) = i.now < i.returnNotBefore

    /** No return: the view stays where the look left it, away from where the episode started. */
    private fun stay(i: Inputs) {
        away = start?.takeIf { state == State.DWELL && angle(i.view, it) > AWAY_DEG }
        state = State.REST
        restUntil = i.now + REST_MS
    }

    private fun rest(now: Long) {
        state = State.REST
        start = null
        away = null
        restUntil = now + REST_MS
    }

    /** A look could start in this tick of [p], as far as the view goes (the hazard's own draw aside). */
    private fun free(i: Inputs, p: Phase): Boolean {
        if (state != State.REST || i.busy || i.moving || i.now < restUntil) return false
        if (away != null && p != Phase.AFTER_KILL && p != Phase.FIGHT_AIM) return false
        if (i.windowMs < if (i.turnBackOwed) REST_MS.toDouble() else FREE_WINDOW_MS) return false
        if (p != Phase.WAITING && p != Phase.FIGHT_FREE) return true
        val home = i.home ?: return false
        return angle(i.view, home) <= Glances.HOME_DEG
    }

    /** No look out: one starts this tick at the phase's rate (its whole episode planned first). */
    private fun hazard(i: Inputs, dt: Long): Decision {
        val p = i.phase ?: return Decision.NONE
        if (i.moving || i.now < restUntil) return Decision.NONE
        // looks from your fishing view only while you wait for a bite or the bobber fights
        if (p == Phase.WAITING || p == Phase.FIGHT_FREE) {
            val home = i.home ?: return Decision.NONE
            if (angle(i.view, home) > Glances.HOME_DEG) return Decision.NONE
        }
        val comp = (phaseMs[p.ordinal] / max(1.0, freeMs[p.ordinal])).coerceIn(1.0, COMP_MAX)
        val h = table.hazard(p, i.sinceOwnDeathMs < DEATH_MS) * comp
        if (h <= 0.0 || rng.nextDouble() >= 1.0 - exp(- h * dt / 60_000.0)) return Decision.NONE
        val g = target(i) ?: return Decision.NONE
        val home = i.home?.let { angle(i.view, it) <= Glances.HOME_DEG } == true
        val back = ! i.turnBackOwed && rng.nextDouble() < if (home) table.returnHomeP else table.returnAwayP
        val d = table.dwell(rng)
        // a look from where an earlier one stayed away comes back to where that one started (home when it did): coming
        // back to the view it left would leave the view away with nothing holding the cast for it
        val from = away ?: i.view
        // with the turn back owed, it is the return: the look only has to be over by the time it is due (the dwell ends then)
        val total = lookTime(i.view, g) + if (i.turnBackOwed) 0.0 else d + (if (back) backTime(angle(from, g)) else 0.0) + REST_MS
        if (total > i.windowMs) return Decision.NONE
        if (away == null) startHome = home
        start = from
        away = null
        returns = back
        dwell = d
        return look(i, g)
    }

    private fun look(i: Inputs, g: View): Decision {
        lookMs = lookTime(i.view, g)
        goal = g
        state = State.OUT
        return Decision.LOOK
    }

    /**
     * The return to [from], short of it by your usual error: at most half the way. A look from your fishing view comes
     * back to it, never off its water (looks starting a little off it would otherwise walk the view away, look by look).
     */
    private fun back(i: Inputs, from: View): Decision {
        val home = i.home
        val to = if (startHome && home != null) (if (abs(home.pitch) >= POLE_HOME) View(i.view.yaw, home.pitch) else home) else from
        val a = angle(i.view, to)
        var cap = ERR_SHARE * a
        if (startHome) cap = min(cap, HOME_ERR_MAX)
        val g = shortOf(i.view, to, table.returnErr(rng, cap))
        if (angle(i.view, g) < MIN_MOVE_DEG) {
            rest(i.now)
            return Decision.NONE
        }
        goal = g
        state = State.BACK
        away = null
        return Decision.RETURN
    }

    /** The rest of an episode after its look: the dwell and the return planned for it (none when the turn back is the return). */
    private fun episodeMs(i: Inputs, amp: Double) = if (i.turnBackOwed) 0.0 else dwell + (if (returns) backTime(amp) else 0.0) + REST_MS

    private fun lookTime(from: View, to: View) = MotorModel.typicalMs(from.yaw, from.pitch, to.yaw, to.pitch, lookParams)

    private fun backTime(amp: Double) = MotorModel.lawMs(amp, backParams)

    /**
     * Where a look goes from the view: where your creature just died (with [Table.deadSpotP], short of it), else one of
     * your own looks' offsets from a view of about this pitch, varied (or the reference recordings' mix without enough of
     * them). Null when it would turn less than [Glances]'s 6°, or when no offset in [TRIES] draws stays within
     * [POLE_PITCH] and turns the yaw at most [MAX_YAW] (a steep view): those are drawn again, never piled at the bounds.
     */
    private fun target(i: Inputs): View? {
        val v = i.view
        val spot = i.deadSpot
        if (spot != null && rng.nextDouble() < table.deadSpotP) {
            val a = angle(v, spot)
            if (a in DEAD_MIN_DEG .. DEAD_MAX_DEG) return shortOf(v, spot, TimingModel.lognormal(DEAD_SHORT_P50, DEAD_SHORT_SIGMA, rng, DEAD_SHORT_SHARE * a))
        }
        repeat(TRIES) {
            val (dYaw, dPitch) = table.offset(v.pitch, rng)
            // past the pole the look goes the other way
            val dp = if (abs(v.pitch + dPitch) > POLE_PITCH) - dPitch else dPitch
            val endPitch = v.pitch + dp
            if (abs(endPitch) > POLE_PITCH) return@repeat
            // the yaw offset is measured on the sphere: the hand turns the view's yaw further the steeper it looks
            val c = cos(Math.toRadians((v.pitch + endPitch) / 2))
            if (abs(dYaw) > MAX_YAW * c) return@repeat
            val g = View(v.yaw + dYaw / c, endPitch)
            return g.takeIf { angle(v, it) >= MIN_LOOK_DEG }
        }
        return null
    }

    /** One look offset: the yaw on the sphere and the pitch (+ down). */
    class Offset(val dYaw: Double, val dPitch: Double, val startPitch: Double)

    /**
     * [Glances] read for the policy: the hazard per phase, the probabilities and your samples (each list only when it has
     * enough of them, else the reference recordings' defaults). Anything broken in an edited profile (a null, a list of
     * the wrong length, a value that is no rate or probability) is read as not learned.
     */
    class Table private constructor(
        private val rate: DoubleArray,
        private val boostShare: DoubleArray,
        val chainP: Double, val returnHomeP: Double, val returnAwayP: Double, val deathBoost: Double, val deadSpotP: Double,
        private val offsets: List<Offset>?, private val dwells: DoubleArray?, private val errs: DoubleArray?, private val gaps: DoubleArray?,
    ) {
        /** Some phase has a rate: looks are on. */
        val learned get() = rate.any { it > 0.0 }

        /** Looks per minute starting an episode in [p] (chained ones come on top), [boosted] in the second after a death of yours. */
        fun hazard(p: Phase, boosted: Boolean): Double {
            val r = rate[p.ordinal]
            if (r <= 0.0) return 0.0
            // the boost moves looks into the second after a death, the phase's mean rate stays
            val mean = 1.0 + (deathBoost - 1.0) * boostShare[p.ordinal]
            return r * (1.0 - chainP) / mean * if (boosted) deathBoost else 1.0
        }

        /** A look's offset from a view at [pitch]: yaw on the sphere and pitch. */
        fun offset(pitch: Double, rng: Random): Pair<Double, Double> {
            val all = offsets
            if (all != null) {
                val near = all.filter { abs(it.startPitch - pitch) <= PITCH_NEAR }.takeIf { it.size >= MIN_NEAR } ?: all
                val o = near[rng.nextInt(near.size)]
                val k = exp(OFFSET_SIGMA * TimingModel.normal(rng))
                return (if (rng.nextBoolean()) o.dYaw else - o.dYaw) * k to o.dPitch * k
            }
            val a = amplitude(rng)
            val up = rng.nextDouble() < when {
                pitch < 30.0 -> 0.32
                pitch < 70.0 -> 0.57
                else -> 0.76
            }
            val pitchSign = if (up) - 1.0 else 1.0
            val yawSign = if (rng.nextBoolean()) 1.0 else - 1.0
            val kind = rng.nextDouble()
            return when {
                kind < PITCH_ONLY_P -> 0.0 to pitchSign * a
                kind < PITCH_ONLY_P + YAW_ONLY_P -> yawSign * a to 0.0
                else -> {
                    val th = Math.toRadians(BOTH_MIN_DEG + (BOTH_MAX_DEG - BOTH_MIN_DEG) * rng.nextDouble())
                    yawSign * a * cos(th) to pitchSign * a * sin(th)
                }
            }
        }

        /** A look's amplitude without samples: lognormal around 22.5°, drawn within 6-140° (never piled at a bound). */
        private fun amplitude(rng: Random) = exp(TimingModel.normal(ln(AMP_P50), AMP_SIGMA, ln(MIN_LOOK_DEG), ln(MAX_AMP), rng))

        fun dwell(rng: Random) = sample(dwells, rng) ?: TimingModel.lognormal(DWELL_P50, DWELL_SIGMA, rng, DWELL_CAP)

        /** The gap before a chained look: one of yours at or below [CHAIN_CAP], varied ([sampleUnder]), else the default lognormal drawn below it. */
        fun chainGap(rng: Random) = sampleUnder(gaps, CHAIN_CAP, rng) ?: TimingModel.lognormal(CHAIN_P50, CHAIN_SIGMA, rng, CHAIN_CAP)

        /** A return's error, drawn below [cap] and never piled at it: one of your return errors ([sampleUnder]), else the default lognormal truncated there. */
        fun returnErr(rng: Random, cap: Double) = sampleUnder(errs, cap, rng) ?: TimingModel.lognormal(ERR_P50, ERR_SIGMA, rng, cap)

        private fun sample(xs: DoubleArray?, rng: Random) = xs?.let { it[rng.nextInt(it.size)] * exp(SAMPLE_SIGMA * TimingModel.normal(rng)) }

        /**
         * One of [xs] at or below [cap], varied like [sample]: a variation that would carry it past the cap goes the other
         * way, so nothing piles there. Null without one.
         */
        private fun sampleUnder(xs: DoubleArray?, cap: Double, rng: Random): Double? {
            val below = xs?.filter { it <= cap }
            if (below.isNullOrEmpty()) return null
            val x = below[rng.nextInt(below.size)]
            val k = exp(SAMPLE_SIGMA * TimingModel.normal(rng))
            return if (x * k <= cap) x * k else x / k
        }

        companion object {
            /** Nothing learned: no looks. */
            val OFF by lazy { of(null) }
            private var source: Glances? = null
            private var cache: Table? = null

            /** The table of [g] (null: nothing learned), cached for the profile's own instance. */
            @Suppress("SENSELESS_COMPARISON")
            fun of(g: Glances?): Table {
                if (g != null && g === source) cache?.let { return it }
                val d = Glances()
                fun prob(v: Double, default: Double, lo: Double = 0.0, hi: Double = 1.0) = if (v.isFinite() && v in lo .. hi) v else default
                // Gson leaves a list an edited profile sets to null null
                val n = Glances.PHASES.size
                val rates: List<Double?>? = g?.rate
                val rate = DoubleArray(n) { p -> rates?.takeIf { it.size == n }?.get(p)?.takeIf { it.isFinite() && it > 0.0 }?.coerceAtMost(Glances.MAX_RATE) ?: 0.0 }
                val minutes: List<Double?>? = g?.minutes
                val deathMinutes: List<Double?>? = g?.deathMinutes
                val share = DoubleArray(n) { p ->
                    val m = minutes?.takeIf { it.size == n }?.get(p)
                    val dm = deathMinutes?.takeIf { it.size == n }?.get(p)
                    if (m != null && dm != null && m.isFinite() && dm.isFinite() && m > 0.0 && dm >= 0.0) (dm / m).coerceIn(0.0, 1.0) else 0.0
                }
                val amps: List<Float?>? = g?.amp
                val yaws: List<Float?>? = g?.dYaw
                val pitches: List<Float?>? = g?.dPitch
                val starts: List<Float?>? = g?.startPitch
                val offsets = if (amps != null && yaws != null && pitches != null && starts != null && yaws.size == pitches.size && yaws.size == starts.size)
                    yaws.indices.mapNotNull { k ->
                        val y = yaws[k]
                        val p = pitches[k]
                        val s = starts[k]
                        if (y != null && p != null && s != null && y.isFinite() && p.isFinite() && s.isFinite()) Offset(y.toDouble(), p.toDouble(), s.toDouble()) else null
                    }.takeIf { it.size >= Glances.MIN_LOOKS }
                else null
                fun ints(l: List<Int?>?) = l?.mapNotNull { it?.takeIf { v -> v > 0 }?.toDouble() }?.takeIf { it.size >= MIN_SAMPLES }?.toDoubleArray()
                val errList: List<Float?>? = g?.returnErr
                val errs = errList?.mapNotNull { it?.takeIf { v -> v.isFinite() && v >= 0f }?.toDouble() }?.takeIf { it.size >= MIN_SAMPLES }?.toDoubleArray()
                val table = Table(
                    rate, share,
                    prob(g?.chainP ?: d.chainP, d.chainP, 0.0, CHAIN_MAX), prob(g?.returnHomeP ?: d.returnHomeP, d.returnHomeP),
                    prob(g?.returnAwayP ?: d.returnAwayP, d.returnAwayP), prob(g?.deathBoost ?: d.deathBoost, d.deathBoost, 1.0, BOOST_MAX),
                    prob(g?.deadSpotP ?: d.deadSpotP, d.deadSpotP), offsets, ints(g?.dwellMs), errs, ints(g?.chainGapMs),
                )
                if (g != null) {
                    source = g
                    cache = table
                }
                return table
            }
        }
    }

    companion object {
        /** A tick's time at risk counts at most this long (a hitch is no longer chance). */
        const val MAX_DT_MS = 100L
        /** No look starts sooner than this after a look's or return's end, nor than [AFTER_MOVE_MS] after any other move. */
        const val REST_MS = 300L
        const val AFTER_MOVE_MS = 100L
        /** The view counts as free for a look with this long left; the rate makes up for the time it is not by at most [COMP_MAX]. */
        const val FREE_WINDOW_MS = 2000.0
        const val COMP_MAX = 2.5
        /** Looks within this long after a death of your creatures come at the death boost's rate. */
        const val DEATH_MS = 1000.0
        /** A look at where your creature died: 6-140° off, short of it by a lognormal around 4° (at most 0.3 of the way). */
        const val DEAD_MIN_DEG = 6.0
        const val DEAD_MAX_DEG = 140.0
        private const val DEAD_SHORT_P50 = 4.0
        private const val DEAD_SHORT_SIGMA = 0.8
        private const val DEAD_SHORT_SHARE = 0.3
        /** Your offsets from a view within this pitch of the current one, when at least [MIN_NEAR] are; varied by exp(N(0, [OFFSET_SIGMA])). */
        private const val PITCH_NEAR = 25.0
        private const val MIN_NEAR = 5
        private const val OFFSET_SIGMA = 0.15
        /** Without samples: the reference recordings' looks, 22.5° at the median, pitch only, yaw only or both at 20-70° from the yaw axis. */
        private const val AMP_P50 = 22.5
        private const val AMP_SIGMA = 0.93
        private const val MAX_AMP = 140.0
        private const val PITCH_ONLY_P = 0.37
        private const val YAW_ONLY_P = 0.18
        private const val BOTH_MIN_DEG = 20.0
        private const val BOTH_MAX_DEG = 70.0
        private const val TRIES = 8
        /** A look turns at least this far; its goal stays this far from straight up or down, its yaw on the view at most [MAX_YAW]. */
        const val MIN_LOOK_DEG = 6.0
        private const val POLE_PITCH = 89.0
        private const val MAX_YAW = 180.0
        /** Your dwell, chain gap and return error samples count from this many, each varied by exp(N(0, [SAMPLE_SIGMA])). */
        private const val MIN_SAMPLES = 8
        private const val SAMPLE_SIGMA = 0.1
        private const val DWELL_P50 = 1250.0
        private const val DWELL_SIGMA = 1.18
        private const val DWELL_CAP = 12_000.0
        private const val CHAIN_P50 = 400.0
        private const val CHAIN_SIGMA = 0.88
        private const val CHAIN_CAP = 3000.0
        private const val CHAIN_MAX = 0.5
        private const val BOOST_MAX = 4.0
        private const val ERR_P50 = 4.4
        private const val ERR_SIGMA = 0.9
        /** A return stops short of the look's start by at most this share of the way, and from your fishing view by at most [HOME_ERR_MAX] (the next cast still lands in its water). */
        private const val ERR_SHARE = 0.5
        const val HOME_ERR_MAX = AutoMobKiller.FightRules.FLUID_FALLBACK_DEG - 1.0
        /** The view comes back when the window has no more than the return's time and this left. */
        const val RETURN_LEAD_MS = 100.0
        /** A return shorter than this is none. */
        private const val MIN_MOVE_DEG = 1.0
        /** A look that did not come back keeps the view away from its start from this far on. */
        private const val AWAY_DEG = 4.0
        /** Your fishing view this close to straight up or down: the return keeps its yaw (it means nothing there). */
        private const val POLE_HOME = 89.5

        private fun angle(a: View, b: View) = MotorModel.angle(a.yaw, a.pitch, b.yaw, b.pitch)

        /**
         * The view on the straight mouse path from [from] to [to] that is [err] degrees (great-circle) short of [to]: the
         * mouse path is no great circle, and near the pole a share of it is far more than that share of the angle.
         */
        private fun shortOf(from: View, to: View, err: Double): View {
            val target = View(from.yaw + MotorModel.wrap(to.yaw - from.yaw), to.pitch)
            if (angle(from, target) <= err) return from
            var lo = 0.0
            var hi = 1.0
            repeat(SHORT_STEPS) {
                val mid = (lo + hi) / 2
                if (angle(from + (target - from) * mid, target) > err) lo = mid else hi = mid
            }
            return from + (target - from) * hi
        }

        private const val SHORT_STEPS = 30
    }
}

//#endif
