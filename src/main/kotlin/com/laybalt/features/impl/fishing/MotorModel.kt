package com.laybalt.features.impl.fishing

//#if CHEAT

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Every tunable of [MotorModel] and [RestMotion]. The defaults reproduce the four manual recordings (DESIGN-HUMAN §1
 * with its amendments, tuned through the motor critic's simulation); the learner moves them by its calibration ratios.
 * Angles are degrees, times milliseconds, speeds degrees per second.
 */
data class MotorParams(
    /** Measured stroke duration T(A) = [lawA] + [lawB]·log2(1 + A/6), A the stroke's great-circle angle. */
    val lawA: Double = 70.0,
    val lawB: Double = 50.0,
    /** ln-sd of a stroke's duration noise, and the range its factor is drawn within. */
    val durationSigma: Double = 0.40,
    val durationMin: Double = 0.5,
    val durationMax: Double = 2.2,
    /** Duration factor of a stroke that starts at |pitch| ≥ [polePitch] and turns the yaw by [poleYaw] or more. */
    val poleFactor: Double = 1.12,
    val polePitch: Double = 70.0,
    val poleYaw: Double = 60.0,
    /** Duration factor of a teleport-blade look-down (his own look-downs are faster than the law). */
    val lookDownFactor: Double = 0.6,
    /** Time to peak speed as a share of the stroke: normal, drawn within [tauMin]..[tauMax]. */
    val tauMean: Double = 0.50,
    val tauSd: Double = 0.16,
    val tauMin: Double = 0.2,
    val tauMax: Double = 0.8,

    /** Share of slow moves: [slowStrokesMin]..[slowStrokesMax] strokes, each [slowFactor] times its planned duration. */
    val slowP: Double = 0.12,
    val slowStrokesMin: Int = 3,
    val slowStrokesMax: Int = 5,
    /** A slow move's intermediate goal k of n: k/n + N(0, [slowSpread]) along the track, drawn within [slowFracMin]..[slowFracMax], plus N(0, [slowCross])·A across. */
    val slowSpread: Double = 0.1,
    val slowFracMin: Double = 0.1,
    val slowFracMax: Double = 1.1,
    val slowCross: Double = 0.15,
    val slowFactor: Double = 2.2,
    /** In a slow move the next stroke starts at the previous one's start + its duration × ([slowNextMin] + [slowNextSpread]·U). */
    val slowNextMin: Double = 0.5,
    val slowNextSpread: Double = 0.2,

    /** Lower edges of the amplitude bins of [multiP]; below the first a move is one stroke and never slow. */
    val multiBins: List<Double> = listOf(6.0, 15.0, 30.0, 60.0, 100.0),
    /** Planned probability of a multi-stroke move per bin (planning, not the measured share). */
    val multiP: List<Double> = listOf(0.50, 0.45, 0.50, 0.50, 0.55),
    /** First stroke: a short lead-in U([leadInMin], [leadInMax]) with probability [leadInP], else N([firstMean], [firstSd]) drawn within [firstMin]..[firstMax]. */
    val leadInP: Double = 0.15,
    val leadInMin: Double = 0.05,
    val leadInMax: Double = 0.30,
    val firstMean: Double = 0.87,
    val firstSd: Double = 0.34,
    val firstMin: Double = 0.35,
    val firstMax: Double = 1.45,
    /** Cross-track error of the first stroke, as a share of the move's angle. */
    val crossSd: Double = 0.07,
    /** Middle stroke with probability [middleP] while more than [middleMinDeg] remain, covering N([middleMean], [middleSd]) of it, drawn within [middleMin]..[middleMax]. */
    val middleP: Double = 0.45,
    val middleMinDeg: Double = 5.0,
    val middleMean: Double = 1.0,
    val middleSd: Double = 0.15,
    val middleMin: Double = 0.6,
    val middleMax: Double = 1.3,
    /** The next stroke overlaps (starts at the previous start + T·(1 − [overlapCut]·U)) with probability [overlapP], else follows within [pauseMaxMs]. */
    val overlapP: Double = 0.58,
    val overlapCut: Double = 0.1,
    val pauseMaxMs: Double = 0.0,
    /** Strokes shorter than this (mouse-space degrees) are skipped. */
    val minStrokeDeg: Double = 0.2,

    /** The lagging axis starts later by a lognormal (median [lagP50Ms], ln-sd [lagSigma]) drawn below [lagMaxMs]. */
    val lagP50Ms: Double = 20.0,
    val lagSigma: Double = 1.3,
    val lagMaxMs: Double = 300.0,
    /** Leaving (start) or going to (goal) |pitch| ≥ [leadPolePitch] with a yaw change ≥ [leadPoleYaw], the yaw leads this often. */
    val leadPolePitch: Double = 70.0,
    val leadPoleYaw: Double = 30.0,
    val yawLeadLeavingPole: Double = 0.65,
    val yawLeadToPole: Double = 0.41,

    /** Peak caps per stroke: great-circle and mouse-space (hypot of yaw and pitch) speed. */
    val gcCap: Double = 1208.0,
    val mouseCap: Double = 1394.0,
    /** No step of the view ever moves faster than this, whatever overlaps. */
    val hardCap: Double = 1650.0,

    /** [MotorModel.stop] coasts to rest over max([stopMinMs], [stopShare] × the stroke time left). */
    val stopMinMs: Double = 120.0,
    val stopShare: Double = 0.35,
    /** A retarget during a stroke needs the goal to move by more than max([retargetMinDeg], [retargetShare] × the angle left). */
    val retargetMinDeg: Double = 5.0,
    val retargetShare: Double = 0.3,
    /** Motor time advances by at most this per sample: a hitch slows the move down instead of jumping it. */
    val maxStepMs: Double = 50.0,
    /** A view changed by someone else by at most this is carried along; more aborts the move. */
    val foreignMaxDeg: Double = 2.5,

    /** Rest nudges: a run starts on [restStartP] of still ticks and goes on with [restContinueP], up to [restMaxTicks] ticks. */
    val restStartP: Double = 0.015,
    val restContinueP: Double = 0.4,
    val restMaxTicks: Int = 3,
    /**
     * Size of a nudge on an axis in mouse grid steps, as your hand makes them (the pitch anywhere, the yaw below
     * [restPolePitch]): the share of 1, 2, ... steps.
     */
    val restSteps: List<Double> = MotorParams.REST_STEPS,
    /**
     * From this |pitch| on a yaw step turns the view little, and your hand moves the yaw further: lognormal, median
     * [restPoleP50] and ln-sd [restPoleSigma] steps, drawn within 1 .. [restPoleMax].
     */
    val restPolePitch: Double = 70.0,
    val restPoleP50: Double = 1.03,
    val restPoleSigma: Double = 2.24,
    val restPoleMax: Int = 150,
    /**
     * No nudge turns the view by this much: yours near the pole stayed under it (the learner counts nudges up to it), so
     * the yaw's there are drawn under it, however far your hand may move the yaw closer to the pole.
     */
    val restNudgeMaxDeg: Double = 2.0,
    /** Axes of a nudge: both [restBothP], yaw only [restYawP], else pitch only. */
    val restBothP: Double = 0.47,
    val restYawP: Double = 0.31,
    /** Farther than this from the rest anchor on an axis, nudges go back towards it. */
    val restAnchorDeg: Double = 1.0,
) {
    companion object {
        /**
         * The reference recordings' nudges of the hand ([restSteps], the pitch anywhere and the yaw below 70°): 2754 axes
         * of the rest ticks and the one-tick movements under 2° (a nudge too fast for a rest tick), 1 to 14 steps.
         */
        val REST_STEPS = listOf(0.444, 0.216, 0.142, 0.104, 0.049, 0.015, 0.009, 0.006, 0.006, 0.004, 0.001, 0.0015, 0.002, 0.0005)
    }
}

/**
 * A hand on a mouse turning the view. A move is planned once, at its start, as one to five strokes with bell-shaped
 * speed that may overlap (DESIGN-HUMAN §1): mostly one stroke, often a first stroke short of or past the goal and a
 * correction, now and then a slow move of several strokes. Each stroke aims at the goal as it is when the stroke starts,
 * so a goal that moves is followed stroke by stroke, never continuously.
 *
 * The caller feeds times ([sample] on every frame and client tick, all times in ms from one monotonic clock) and applies
 * the views; the yaw is continuous (the goal's yaw is wrapped once, at planning) so the caller wraps it against the
 * player's. Pure and deterministic for a given [Random]: no Minecraft calls.
 */
class MotorModel {
    /** A view, or a change of view, in degrees; the yaw is not wrapped. */
    data class View(val yaw: Double, val pitch: Double) {
        operator fun plus(o: View) = View(yaw + o.yaw, pitch + o.pitch)
        operator fun minus(o: View) = View(yaw - o.yaw, pitch - o.pitch)
        operator fun times(k: Double) = View(yaw * k, pitch * k)

        /** Length in mouse space: yaw and pitch degrees as the hand moves them. */
        val length get() = hypot(yaw, pitch)
    }

    private enum class Kind { FRACTION, MIDDLE, FINAL, CORRECTION }

    /** A stroke not started yet: what it aims at and all its random draws, made when the move is planned. */
    private class Recipe(
        val kind: Kind, val f: Double, val c: Double,
        val noise: Double, val tau: Double, val lag: Double, val leadU: Double, val overlap: Boolean, val nextU: Double,
    )

    private class Stroke(
        val kind: Kind, val f: Double, val c: Double, var from: View, var target: View,
        val t0: Double, val ms: Double, val lagYaw: Double, val lagPitch: Double, val d: View, val bell: Bell,
    ) {
        var retargeted = false
        val end get() = t0 + ms + max(lagYaw, lagPitch)

        fun at(t: Double) = View(d.yaw * bell.at((t - t0 - lagYaw) / ms), d.pitch * bell.at((t - t0 - lagPitch) / ms))

        /** The fastest this stroke still moves from [t] on, deg/ms in mouse space: each axis at its peak before it, else now (a bell only slows after its peak). */
        fun speedAhead(t: Double): Double {
            fun axis(lag: Double) = ((t - t0 - lag) / ms).let { u -> if (u >= 1.0) 0.0 else if (u < bell.peakAt) bell.peak else bell.rate(u) } / ms
            return hypot(d.yaw * axis(lagYaw), d.pitch * axis(lagPitch))
        }

        fun copy() = Stroke(kind, f, c, from, target, t0, ms, lagYaw, lagPitch, d, bell).also { it.retargeted = retargeted }
    }

    /** What is left of an interrupted motion: it keeps its speed [v] (deg/ms) and comes to rest along a bell's tail. */
    private class Tail(val t0: Double, val ms: Double, val v: View) {
        val end get() = t0 + ms
        val total get() = v * (ms * COAST)

        fun at(t: Double) = v * (ms * coast(((t - t0) / ms).coerceIn(0.0, 1.0)))

        /** Its speed at [t] in mouse space, deg/ms: the second half of a minimum-jerk bell only slows down. */
        fun speedAt(t: Double): Double {
            val x = ((t - t0) / ms).coerceIn(0.0, 1.0)
            val u = 0.5 + 0.5 * x
            return v.length * 16 * u * u * (1 - u) * (1 - u)
        }
    }

    private var params = MotorParams()
    private var rng: Random = Random.Default
    private var lookDown = false
    private var slowMove = false

    private var lastNow = Double.NaN
    /** Motor time: wall time with every step capped at [MotorParams.maxStepMs]. */
    private var clock = 0.0
    /** Motor time since the last [sample], which bounds how far that sample may move the view. */
    private var budget = 0.0

    /** Where the motion is relative to: the view the move started from, plus the strokes that are over. */
    private var base = ZERO
    /** The point the move's fractions are measured from: its start plus what an interrupted motion still coasts. */
    private var origin = ZERO
    /** Where the strokes started so far end. */
    private var end = ZERO
    private var out = ZERO
    private var tail: Tail? = null
    private val strokes = ArrayList<Stroke>()
    private val queue = ArrayDeque<Recipe>()
    private var nextAt = 0.0

    /** The goal the move ends at (strokes not started yet aim at it); the yaw is continuous with the view's. */
    var goal = ZERO
        private set
    /** A move is in flight: during a stroke, between the strokes of one move, and while a [stop] coasts to rest. */
    var moving = false
        private set
    /** No move in flight: the view is the motor's to start again. */
    val done get() = ! moving
    /** The move was [stop]ped and is coasting to rest. */
    var stopping = false
        private set
    /** Strokes of the plan started in this move (retarget corrections not counted). */
    var strokesStarted = 0
        private set
    /** Strokes of the plan not started yet. */
    val pendingStrokes get() = queue.size
    /** The move's duration in motor ms as planned at its start (its strokes' later goals taken as the start's). */
    var plannedMs = 0.0
        private set
    /** The last view [sample] gave. */
    val view get() = out
    /**
     * Where the move in flight comes to rest: where its strokes end once all of them started (a [stop]'s tail and your
     * mouse carried along included), else its goal.
     */
    val restsAt get() = if (queue.isEmpty()) end else goal

    /**
     * Starts a move from the view [fromYaw]/[fromPitch] to [toYaw]/[toPitch] at [nowMs]. A move still in flight is not
     * cut: its speed carries on and fades over a stop's tail while the new strokes speed up, so the speed stays
     * continuous. [lookDown] marks a teleport-blade look-down, which is faster than the law.
     */
    fun start(
        fromYaw: Double, fromPitch: Double, toYaw: Double, toPitch: Double, nowMs: Double,
        params: MotorParams, rng: Random, lookDown: Boolean = false,
    ) {
        val from = View(fromYaw, fromPitch)
        var carry: Tail? = null
        if (moving) {
            advance(nowMs)
            startDue()
            val pos = position(clock)
            // a view moved by more than a nudge since (a teleport, the server) starts afresh
            if (View(wrap(from.yaw - pos.yaw), from.pitch - pos.pitch).length <= params.foreignMaxDeg) carry = coast(clock, pos, params)
        }
        else {
            lastNow = nowMs
            budget = 0.0
        }
        this.params = params
        this.rng = rng
        this.lookDown = lookDown
        strokes.clear()
        queue.clear()
        tail = carry
        base = from
        out = from
        origin = from + (carry?.total ?: ZERO)
        end = origin
        goal = View(origin.yaw + wrap(toYaw - origin.yaw), toPitch.coerceIn(- 90.0, 90.0))
        stopping = false
        strokesStarted = 0
        plan()
        nextAt = clock
        plannedMs = plannedEnd() - clock
        moving = true
        startDue()
        settle()
    }

    /** The view at [nowMs]; call it on every frame and every client tick. Sampling twice at the same time gives the same view. */
    fun sample(nowMs: Double): View {
        if (! moving) return out
        advance(nowMs)
        startDue()
        val plan = position(clock)
        val step = plan - out
        val limit = params.hardCap * budget / 1000.0
        out = if (step.length > limit) out + step * (limit / step.length) else plan
        budget = 0.0
        fold()
        settle()
        return View(out.yaw, out.pitch.coerceIn(- 90.0, 90.0))
    }

    /**
     * The goal moved to [toYaw]/[toPitch]. During a stroke that was not retargeted yet, a goal that moved by more than
     * max(5°, 0.3 × the angle left) adds a correction stroke on top of it (the speed stays continuous); otherwise the
     * new goal waits for the next stroke of the move, or for the next move. [force] corrects the stroke whatever the
     * distance and however often (a switch between a look-down and a creature). True when a correction was added.
     */
    fun retarget(toYaw: Double, toPitch: Double, nowMs: Double, force: Boolean = false): Boolean {
        if (! moving || stopping) return false
        advance(nowMs)
        startDue()
        val next = View(goal.yaw + wrap(toYaw - goal.yaw), toPitch.coerceIn(- 90.0, 90.0))
        val s = strokes.lastOrNull { it.kind != Kind.CORRECTION && clock < it.end }
        val apply = s != null && (force || ! s.retargeted && angle(goal, next) > max(params.retargetMinDeg, params.retargetShare * angle(position(clock), goal)))
        goal = next
        if (! apply) return false
        val target = targetOf(s, origin, goal)
        val d = target - s.target
        val c = stroke(Kind.CORRECTION, 0.0, 0.0, end, end + d, clock, noise(), tau(), lag(), rng.nextDouble()) ?: return false
        s.retargeted = true
        s.target = target
        strokes += c
        end += d
        return true
    }

    /**
     * Brings the view to rest without freezing it: the current speed fades along a bell's tail over
     * max(120 ms, 0.35 × the stroke time left). For the killer's own transitions (death, drop, plan switch).
     */
    fun stop(nowMs: Double) {
        if (! moving) return
        advance(nowMs)
        startDue()
        val pos = position(clock)
        val carry = coast(clock, pos, params)
        strokes.clear()
        queue.clear()
        base = pos
        tail = carry
        end = pos + (carry?.total ?: ZERO)
        goal = end
        stopping = true
        settle()
    }

    /** Freezes the view at once (takeover, a screen, a world change, a view set by someone else). */
    fun abort() {
        strokes.clear()
        queue.clear()
        tail = null
        moving = false
        stopping = false
        budget = 0.0
    }

    /**
     * Drops the strokes not started yet: the move ends when the strokes in flight do, each at zero speed. A stroke in
     * flight that goes past the goal (a first or middle one drawn past it) still gets its way back, the move's last
     * stroke: you take an overshoot back in the same move (16 % of your turns toward a creature went more than 1° past
     * where they came to rest; ending them on the far side left the killer's at half that).
     */
    fun endAfterStroke() {
        val past = strokes.lastOrNull { it.kind != Kind.CORRECTION }?.let { it.kind != Kind.FINAL && it.f > 1.0 } == true
        if (past) queue.removeAll { it.kind != Kind.FINAL } else queue.clear()
    }

    /**
     * The view was changed by [dYaw]/[dPitch] since the last sample by someone else (your mouse below the takeover
     * threshold, another feature). Up to [MotorParams.foreignMaxDeg] it is carried along as an offset to the rest of the
     * path, with no snap back; more aborts the move and gives false.
     */
    fun external(dYaw: Double, dPitch: Double): Boolean {
        val d = View(dYaw, dPitch)
        if (d.length > params.foreignMaxDeg) {
            abort()
            out += d
            return false
        }
        out += d
        if (! moving) return true
        base += d
        origin += d
        end += d
        for (s in strokes) {
            s.from += d
            s.target += d
        }
        return true
    }

    /** Speed of the planned motion at the motor's current time, in degrees per second on each axis. */
    fun velocity() = velocity(clock) * 1000.0

    /**
     * The fastest the move in flight may still turn the view from the motor's current time on, in degrees per second of
     * mouse space (never below the great-circle speed the learner measures): infinite while a stroke of it is still to
     * start or the view still catches up with the plan (a hitch), else what each stroke in flight and a stop's tail can
     * still reach, summed; 0 with none in flight.
     */
    fun speedAhead(): Double {
        if (! moving) return 0.0
        if (queue.isNotEmpty() || (position(clock) - out).length > CATCH_UP_DEG) return Double.POSITIVE_INFINITY
        var v = tail?.takeIf { clock < it.end }?.speedAt(clock) ?: 0.0
        for (s in strokes) v += s.speedAhead(clock)
        return v * 1000.0
    }

    /**
     * A copy of this motor in its state now, to try a move on ([HumanAim.leadMs]) without touching this one: the strokes
     * in flight and their goals, the queue, the clocks and the last view.
     */
    fun copy(): MotorModel {
        val m = MotorModel()
        m.params = params
        m.rng = rng
        m.lookDown = lookDown
        m.slowMove = slowMove
        m.lastNow = lastNow
        m.clock = clock
        m.budget = budget
        m.base = base
        m.origin = origin
        m.end = end
        m.out = out
        m.tail = tail
        strokes.mapTo(m.strokes) { it.copy() }
        m.queue.addAll(queue)
        m.nextAt = nextAt
        m.goal = goal
        m.moving = moving
        m.stopping = stopping
        m.strokesStarted = strokesStarted
        m.plannedMs = plannedMs
        return m
    }

    /** The unlimited planned view at the motor's current time (the output follows it unless a step hits the hard cap). */
    internal fun planView() = position(clock)

    private fun plan() {
        val p = params
        val a = angle(origin, goal)
        slowMove = a >= p.multiBins.first() && rng.nextDouble() < p.slowP
        when {
            slowMove -> {
                val n = rng.nextInt(p.slowStrokesMin, p.slowStrokesMax + 1)
                for (k in 1 until n) {
                    val f = TimingModel.normal(k.toDouble() / n, p.slowSpread, p.slowFracMin, p.slowFracMax, rng)
                    queue += recipe(Kind.FRACTION, f, p.slowCross * TimingModel.normal(rng))
                }
                queue += recipe(Kind.FINAL)
            }
            rng.nextDouble() < multiP(a, p) -> {
                // stroke shares drawn within their bounds: a share piled on a bound would make a stroke end on the very
                // goal (or the bound's share of the way) far more often than any other
                val f = if (rng.nextDouble() < p.leadInP) p.leadInMin + (p.leadInMax - p.leadInMin) * rng.nextDouble()
                else TimingModel.normal(p.firstMean, p.firstSd, p.firstMin, p.firstMax, rng)
                queue += recipe(Kind.FRACTION, f, p.crossSd * TimingModel.normal(rng))
                if (rng.nextDouble() < p.middleP) queue += recipe(Kind.MIDDLE, TimingModel.normal(p.middleMean, p.middleSd, p.middleMin, p.middleMax, rng))
                queue += recipe(Kind.FINAL)
            }
            else -> queue += recipe(Kind.FINAL)
        }
    }

    private fun recipe(kind: Kind, f: Double = 1.0, c: Double = 0.0) =
        Recipe(kind, f, c, noise(), tau(), lag(), rng.nextDouble(), rng.nextDouble() < params.overlapP, rng.nextDouble())

    // drawn within their bounds like the stroke shares: a clamp put 4 % of strokes at exactly half their duration (the
    // fastest peaks), 3 % at either end of the time to peak and 2 % of axis lags at the cap
    private fun noise() = exp(TimingModel.normal(0.0, params.durationSigma, ln(params.durationMin), ln(params.durationMax), rng))

    private fun tau() = TimingModel.normal(params.tauMean, params.tauSd, params.tauMin, params.tauMax, rng)

    private fun lag() = TimingModel.lognormal(params.lagP50Ms, params.lagSigma, rng, params.lagMaxMs)

    private fun startDue() {
        while (queue.isNotEmpty() && nextAt <= clock) {
            val r = queue.removeFirst()
            val s = build(r, end, nextAt, origin, goal) ?: continue
            strokes += s
            end = s.target
            strokesStarted++
            nextAt = following(s, r)
        }
    }

    /** The end of the move as planned now, its strokes' goals taken as the current one. */
    private fun plannedEnd(): Double {
        var last = max(clock, tail?.end ?: clock)
        for (s in strokes) last = max(last, s.end)
        var from = end
        var at = nextAt
        for (r in queue) {
            // a stroke found not needed when it is due still ends the move no sooner than that
            val s = build(r, from, at, origin, goal)
            if (s == null) {
                last = max(last, at)
                continue
            }
            last = max(last, s.end)
            from = s.target
            at = following(s, r)
        }
        return last
    }

    private fun build(r: Recipe, from: View, at: Double, origin: View, goal: View): Stroke? {
        val target = when (r.kind) {
            Kind.FRACTION -> along(origin, goal, r.f, r.c)
            Kind.MIDDLE -> if (angle(from, goal) <= params.middleMinDeg) return null else from + (goal - from) * r.f
            else -> goal
        }
        return stroke(r.kind, r.f, r.c, from, headroom(target), at, r.noise, r.tau, r.lag, r.leadU)
    }

    private fun targetOf(s: Stroke, origin: View, goal: View) = headroom(
        when (s.kind) {
            Kind.FRACTION -> along(origin, goal, s.f, s.c)
            Kind.MIDDLE -> s.from + (goal - s.from) * s.f
            else -> goal
        }
    )

    private fun stroke(kind: Kind, f: Double, c: Double, from: View, target: View, at: Double, noise: Double, tau: Double, lag: Double, leadU: Double): Stroke? {
        val d = target - from
        if (d.length < params.minStrokeDeg) return null
        val bell = Bell.of(tau)
        val ms = max(strokeMs(from, target, params, lookDown, slowMove) * noise, capMs(from, d, bell, params))
        val yawLeads = leadU < yawLead(from, target, params)
        return Stroke(kind, f, c, from, target, at, ms, if (yawLeads) 0.0 else lag, if (yawLeads) lag else 0.0, d, bell)
    }

    private fun following(s: Stroke, r: Recipe) = when {
        slowMove -> s.t0 + s.ms * (params.slowNextMin + params.slowNextSpread * r.nextU)
        r.overlap -> s.t0 + s.ms * (1 - params.overlapCut * r.nextU)
        else -> s.end + params.pauseMaxMs * r.nextU
    }

    private fun position(t: Double): View {
        var v = base
        tail?.let { v += it.at(t) }
        for (s in strokes) v += s.at(t)
        return v
    }

    /** Degrees per ms, looking ahead: right after a start the motion before it is no longer in [strokes]. */
    private fun velocity(t: Double) = (position(t + EPS) - position(t)) * (1.0 / EPS)

    /** The tail that brings the motion at [t] (at [pos]) to rest, or null when it is still. */
    private fun coast(t: Double, pos: View, p: MotorParams): Tail? {
        val v = velocity(t)
        if (v.length * 1000.0 < STILL_SPEED) return null
        val left = max(strokes.maxOfOrNull { it.end - t } ?: 0.0, tail?.let { it.end - t } ?: 0.0)
        val ms = max(p.stopMinMs, p.stopShare * left)
        val endPitch = pos.pitch + v.pitch * ms * COAST
        // coasting into the pole would stop dead at it: the pitch slows down sooner instead
        if (abs(endPitch) > 90.0) return Tail(t, ms, View(v.yaw, (sign(endPitch) * 90.0 - pos.pitch) / (ms * COAST)))
        return Tail(t, ms, v)
    }

    private fun advance(nowMs: Double) {
        if (lastNow.isNaN()) lastNow = nowMs
        val dt = (nowMs - lastNow).coerceIn(0.0, params.maxStepMs)
        if (nowMs > lastNow) lastNow = nowMs
        clock += dt
        budget += dt
    }

    private fun fold() {
        val it = strokes.iterator()
        while (it.hasNext()) {
            val s = it.next()
            if (clock >= s.end) {
                base += s.d
                it.remove()
            }
        }
        tail?.let {
            if (clock >= it.end) {
                base += it.total
                tail = null
            }
        }
    }

    private fun settle() {
        if (queue.isEmpty() && strokes.isEmpty() && tail == null && (position(clock) - out).length < 1e-9) {
            moving = false
            stopping = false
        }
    }

    /** A stroke's speed bell v(u) ∝ u^(4τ)·(1 − u)^(4(1 − τ)) (τ the time to peak), integrated into a progress table. */
    private class Bell(tau: Double) {
        private val cum = DoubleArray(N + 1)
        /** Highest speed, as progress per unit of stroke time (1.875 for a symmetric bell). */
        val peak: Double
        /** The share of the stroke's time its speed peaks at (its τ): before it the speed rises, after it it only falls. */
        val peakAt = tau

        init {
            val a = 4 * tau
            val b = 4 * (1 - tau)
            var s = 0.0
            var top = 0.0
            for (i in 1..N) {
                val u = (i - 0.5) / N
                val v = u.pow(a) * (1 - u).pow(b)
                s += v
                cum[i] = s
                top = max(top, v)
            }
            for (i in 1..N) cum[i] /= s
            peak = top * N / s
        }

        /** Progress (0..1) at [u] of the stroke's time. */
        fun at(u: Double): Double {
            if (u <= 0.0) return 0.0
            if (u >= 1.0) return 1.0
            val x = u * N
            val i = x.toInt()
            return cum[i] + (cum[i + 1] - cum[i]) * (x - i)
        }

        /** Speed at [u] of the stroke's time, as progress per unit of stroke time (0 outside it). */
        fun rate(u: Double): Double {
            if (u <= 0.0 || u >= 1.0) return 0.0
            val i = (u * N).toInt().coerceAtMost(N - 1)
            return (cum[i + 1] - cum[i]) * N
        }

        companion object {
            const val N = 200
            private val cache = ConcurrentHashMap<Int, Bell>()

            fun of(tau: Double): Bell {
                val key = (tau * 100).roundToInt()
                return cache.getOrPut(key) { Bell(key / 100.0) }
            }
        }
    }

    companion object {
        /** Speed (deg/s) from which the recordings' segmentation counts the view as moving. */
        const val SEGMENT_SPEED = 15.0
        private const val EPS = 0.5
        /** Slower than this (deg/s) an interrupted motion needs no tail. */
        private const val STILL_SPEED = 0.5
        /** The view this far behind the plan (a step the hard cap held back) still catches up: it may still turn fast. */
        private const val CATCH_UP_DEG = 0.01
        /** How far a [Tail] coasts, as a share of its start speed × its duration. */
        private val COAST = coast(1.0)
        private val ZERO = View(0.0, 0.0)

        /** [deg] wrapped into -180 ..< 180. */
        fun wrap(deg: Double): Double {
            var d = deg % 360.0
            if (d >= 180.0) d -= 360.0
            if (d < - 180.0) d += 360.0
            return d
        }

        /** Great-circle angle between two views: close to straight up or down, a big yaw difference is a small turn. */
        fun angle(yaw1: Double, pitch1: Double, yaw2: Double, pitch2: Double): Double {
            val p1 = Math.toRadians(pitch1)
            val p2 = Math.toRadians(pitch2)
            val sp = sin((p2 - p1) / 2)
            val sy = sin(Math.toRadians(wrap(yaw2 - yaw1)) / 2)
            val h = sp * sp + cos(p1) * cos(p2) * sy * sy
            return Math.toDegrees(2 * asin(sqrt(h.coerceIn(0.0, 1.0))))
        }

        private fun angle(a: View, b: View) = angle(a.yaw, a.pitch, b.yaw, b.pitch)

        /** Your measured stroke duration for [amplitude] degrees (moving above [SEGMENT_SPEED] on 50 ms ticks). */
        fun lawMs(amplitude: Double, params: MotorParams = MotorParams()) = params.lawA + params.lawB * log2(1 + amplitude / 6.0)

        /**
         * The duration to plan a bell for so that it measures [measuredMs] the way the recordings are measured: a
         * minimum-jerk stroke of [amplitude] degrees moves slower than [SEGMENT_SPEED] at both ends. At most [measuredMs] / 0.4.
         */
        fun plannedMs(measuredMs: Double, amplitude: Double): Double {
            var t = measuredMs
            repeat(30) {
                val peak = amplitude / t * 1000.0
                var lo = 0.0
                var hi = 0.5
                repeat(40) {
                    val mid = (lo + hi) / 2
                    if (30 * mid * mid * (1 - mid) * (1 - mid) * peak < SEGMENT_SPEED) lo = mid else hi = mid
                }
                t = measuredMs / (1 - 2 * min(lo, 0.3))
            }
            return t
        }

        /** The median duration (ms) of a one-stroke move: what a turn usually takes, for timing an action around it. */
        fun typicalMs(fromYaw: Double, fromPitch: Double, toYaw: Double, toPitch: Double, params: MotorParams = MotorParams(), lookDown: Boolean = false): Double {
            val from = View(fromYaw, fromPitch)
            val to = View(fromYaw + wrap(toYaw - fromYaw), toPitch.coerceIn(- 90.0, 90.0))
            val d = to - from
            if (d.length < params.minStrokeDeg) return 0.0
            return max(strokeMs(from, to, params, lookDown, false), capMs(from, d, Bell.of(params.tauMean), params))
        }

        /** The view [shortByDeg] (great-circle) before [toYaw]/[toPitch] on the straight mouse path from [fromYaw]/[fromPitch]. */
        fun towards(fromYaw: Double, fromPitch: Double, toYaw: Double, toPitch: Double, shortByDeg: Double): View {
            val from = View(fromYaw, fromPitch)
            val to = View(fromYaw + wrap(toYaw - fromYaw), toPitch)
            val a = angle(from, to)
            if (a <= 1e-9) return to
            return from + (to - from) * (1 - shortByDeg / a).coerceIn(0.0, 1.0)
        }

        private fun multiP(a: Double, p: MotorParams): Double {
            val bin = p.multiBins.indexOfLast { a >= it }
            if (bin < 0) return 0.0
            return p.multiP.getOrElse(bin) { p.multiP.lastOrNull() ?: 0.0 }
        }

        private fun strokeMs(from: View, to: View, p: MotorParams, lookDown: Boolean, slow: Boolean): Double {
            val a = max(angle(from, to), 1.0)
            var ms = plannedMs(lawMs(a, p), a)
            if (abs(from.pitch) >= p.polePitch && abs(to.yaw - from.yaw) >= p.poleYaw) ms *= p.poleFactor
            if (lookDown) ms *= p.lookDownFactor
            if (slow) ms *= p.slowFactor
            return ms
        }

        /** The shortest duration that keeps the stroke [d] from [from] within both peak caps. */
        private fun capMs(from: View, d: View, bell: Bell, p: MotorParams): Double {
            val mouse = d.length * bell.peak / min(p.mouseCap, p.hardCap)
            // the yaw counts most where the stroke is closest to level: with the axes apart, the yaw may turn there at full speed
            val to = from.pitch + d.pitch
            val level = if (from.pitch * to <= 0.0) 1.0 else cos(Math.toRadians(min(abs(from.pitch), abs(to))))
            val gc = bell.peak * hypot(d.pitch, level * d.yaw) / min(p.gcCap, p.hardCap)
            return max(mouse, gc) * 1000.0
        }

        private fun yawLead(from: View, to: View, p: MotorParams): Double {
            if (abs(to.yaw - from.yaw) >= p.leadPoleYaw) {
                if (abs(from.pitch) >= p.leadPolePitch) return p.yawLeadLeavingPole
                if (abs(to.pitch) >= p.leadPolePitch) return p.yawLeadToPole
            }
            return 0.5
        }

        /** [f] of the way from [origin] to [goal] plus [c] × the angle across it, in mouse space. */
        private fun along(origin: View, goal: View, f: Double, c: Double): View {
            val d = goal - origin
            val m = d.length
            if (m < 1e-9) return goal
            val across = c * angle(origin, goal) / m
            return View(origin.yaw + d.yaw * f - d.pitch * across, origin.pitch + d.pitch * f + d.yaw * across)
        }

        private fun headroom(v: View) = View(v.yaw, v.pitch.coerceIn(- 90.0, 90.0))

        /** Distance covered by a tail at [x] of its time, as a share of start speed × duration: the second half of a minimum-jerk bell. */
        private fun coast(x: Double): Double {
            fun f(u: Double) = u * u * u / 3 - u.pow(4) / 2 + u.pow(5) / 5
            return 32 * (f(0.5 + 0.5 * x) - f(0.5))
        }
    }
}

/**
 * Rest micro-motion: the few grid steps a still hand on a mouse moves the view now and then (about 2.5 % of still
 * ticks), drawn once per client tick while no move is in flight; looking steeply up or down, where a yaw step turns the
 * view little, the yaw's go further. Nudges drift back towards a rest anchor, so hours of them never walk the view away.
 */
class RestMotion {
    private var run = 0

    /** Ends a run of nudges (a move started). */
    fun reset() {
        run = 0
    }

    /**
     * One still client tick at [yaw]/[pitch]: the change to apply to the view, or null. [anchor] is where the view rests
     * (null: no anchor), [grid] the mouse sensitivity's step in degrees. A change of k steps is k + 0.5 steps, so the
     * grid's truncation keeps all k of them.
     */
    fun tick(yaw: Double, pitch: Double, anchor: MotorModel.View?, grid: Double, params: MotorParams, rng: Random): MotorModel.View? {
        val nudge = if (run > 0) run < params.restMaxTicks && rng.nextDouble() < params.restContinueP else rng.nextDouble() < params.restStartP
        if (! nudge) {
            run = 0
            return null
        }
        run++
        val r = rng.nextDouble()
        val yawToo = r < params.restBothP + params.restYawP
        val pitchToo = r < params.restBothP || ! yawToo
        val pole = abs(pitch) >= params.restPolePitch
        // the steps that turn the view less than a nudge does at this pitch (all of them looking straight down)
        val poleMax = min(params.restPoleMax.toDouble(), params.restNudgeMaxDeg / (grid * cos(Math.toRadians(pitch)))).toInt().coerceAtLeast(1)
        val dYaw = if (yawToo) step(anchor?.let { MotorModel.wrap(it.yaw - yaw) }, if (pole) poleSteps(params, rng, poleMax) else steps(params, rng), grid, params, rng) else 0.0
        var dPitch = if (pitchToo) step(anchor?.let { it.pitch - pitch }, steps(params, rng), grid, params, rng) else 0.0
        if (abs(pitch + dPitch) > 90.0) dPitch = - dPitch
        return MotorModel.View(dYaw, dPitch)
    }

    private fun step(off: Double?, k: Int, grid: Double, params: MotorParams, rng: Random): Double {
        val sign = if (off != null && abs(off) > params.restAnchorDeg) sign(off) else if (rng.nextBoolean()) 1.0 else - 1.0
        return sign * (k + 0.5) * grid
    }

    companion object {
        /** A nudge's size in whole grid steps as your hand makes them: k drawn with [MotorParams.restSteps]' share of it (1 step without one). */
        fun steps(params: MotorParams, rng: Random): Int {
            val shares = params.restSteps
            val total = shares.sum()
            if (! (total > 0.0)) return 1
            var u = rng.nextDouble() * total
            for ((i, s) in shares.withIndex()) {
                u -= s
                if (u < 0.0) return i + 1
            }
            // only the sum's rounding gets here: the largest size with a share
            return shares.indexOfLast { it > 0.0 } + 1
        }

        /**
         * A yaw nudge's size near the pole in whole grid steps: the lognormal ([MotorParams.restPoleP50],
         * [MotorParams.restPoleSigma]) drawn within the sizes that round to 1 .. [max], so neither end gathers the draws
         * beyond it.
         */
        fun poleSteps(params: MotorParams, rng: Random, max: Int = params.restPoleMax): Int {
            val x = exp(TimingModel.normal(ln(params.restPoleP50), params.restPoleSigma, ln(0.5), ln(max + 0.5), rng))
            // the bounds only catch the draw's rounding at the very ends
            return x.roundToInt().coerceIn(1, max)
        }
    }
}

//#endif
