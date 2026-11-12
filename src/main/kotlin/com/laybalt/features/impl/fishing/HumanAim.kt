package com.laybalt.features.impl.fishing

//#if CHEAT

import com.laybalt.LayBaltQOL.mc
import com.laybalt.event.EventBus
import com.laybalt.event.impl.RenderWorldEvent
import com.laybalt.event.impl.TickEvent
import com.laybalt.event.priority.EventPriority
import com.laybalt.features.impl.fishing.MotorModel.View
import com.laybalt.utils.MathUtils
import com.laybalt.utils.PlayerUtils
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/**
 * Turns your view like a hand on a mouse: the one driver of all automatic view motion, the killer's moves
 * ([Owner.KILLER]), its looks at your creatures ([Owner.LOOK]), Auto Fishing's idle ones ([Owner.IDLE]) and your looks
 * around and back ([Owner.GLANCE], [glance]). A move is
 * planned by [MotorModel] once, at its start, and sampled on every frame and client tick ([frame], [tick]): it never
 * jumps a frame, comes to rest instead of freezing when it is stopped, and keeps its speed when its goal moves. While no
 * move is in flight, [tick] nudges the view by a few mouse steps now and then ([RestMotion]).
 *
 * Every view goes through the [Port] (in game [PlayerUtils.rotate], so it stays on your mouse sensitivity grid). The view
 * actually set is kept: a change of up to 2.5° at the next sample (your mouse below the takeover threshold, another
 * feature) is carried along, a bigger one (the server, a teleport) ends the move.
 */
class HumanAim(
    private val port: Port = GamePort,
    private val clock: () -> Double = { System.nanoTime() / 1e6 },
    private val rng: Random = Random.Default,
) {
    /**
     * Who a move is for: the killer's moves hold Auto Fishing, idle ones never do. A look ([ViewPolicy.Looks]) is the
     * killer's too, but no move of its policy: the policy never sees it, its own start replaces it. A look around
     * ([GlancePolicy]) is nobody's fight: it never holds Auto Fishing, and never sets your fishing view or a rest anchor.
     */
    enum class Owner { KILLER, IDLE, LOOK, GLANCE }

    /** The game's view as [HumanAim] sees and sets it; a fake one in tests. */
    interface Port {
        /** The view now, null without a player. */
        fun view(): View?

        /** Turns the view to [yaw]/[pitch] (on the mouse grid) and gives the view actually set. */
        fun set(yaw: Double, pitch: Double): View

        /** Degrees of one mouse step at your sensitivity. */
        val grid: Double
    }

    private val motor = MotorModel()
    private val rest = RestMotion()
    /** The view the last sample set; a different view at the next one was turned by someone else. */
    private var setView: View? = null

    /** The planning parameters of the next move ([paramsOf] your learned hand). */
    var params = MotorParams()
    /** Who the current (or last) move is for. */
    var owner: Owner? = null
        private set
    /** Moves started on this driver: the move in flight is the one seen earlier as long as this did not change. */
    var started = 0
        private set
    /** A move is in flight: during its strokes, between them and while it coasts to rest. */
    val moving get() = motor.moving
    /**
     * A move in flight still turns the view the way the learner sees a move ([ViewMotion.THRESHOLD]): one of its strokes
     * is still to start or may still turn the view faster than a still one by a tick. What is left once it no longer may
     * is its slow tail to rest: your moves ended at their last moving tick (yours: 1 tail tick at the median, 5 at most,
     * after 559 moves of 6° or more), and your next ones, your casts and your clicks were timed from there.
     */
    val turning get() = motor.moving && motor.speedAhead() > ViewMotion.THRESHOLD
    /** The move in flight was stopped and coasts to rest. */
    val stopping get() = motor.stopping
    /** Strokes the move in flight has started. */
    val strokes get() = motor.strokesStarted
    /** The move's duration as planned at its start, in ms. */
    val plannedMs get() = motor.plannedMs
    /** Where the move in flight comes to rest (a stopped one once its tail has coasted), null with none in flight. */
    val restingAt: View? get() = if (motor.moving) motor.restsAt else null
    /** Where the last move of the killer (or its look) ended, the view rest nudges in its fights drift back to (null: none yet). */
    var restAnchor: View? = null
        private set
    /** When the last move (any owner's) came to rest on [clock]: a stopped one once its tail coasted, a frozen one then. */
    private var restClock = Double.NEGATIVE_INFINITY
    /**
     * The view's moves that came to rest so far: the last rest's identity, the same at every read until the next one.
     * A wait drawn per rest is keyed on it, never on a time derived from the rest (that moves with the clocks it is read on).
     */
    var rests = 0
        private set
    /** The rest [restedAt] last read, and when it came on its reader's clock. */
    private var readRest = - 1
    private var readAt = Long.MIN_VALUE / 2
    /** The driver's clock at its last [tick], the start of a client tick: where the tick's own wall clock was read. */
    private var tickClock = Double.NaN
    /** The view at the end of the last [tick] and that tick's clock: the next tick measures the view's turn since. */
    private var tickView: View? = null
    private var lastTick = Double.NaN
    /**
     * The clock of the last tick that found the view turned faster than a still view since the tick before (the learner's
     * moving step, [ViewMotion.THRESHOLD]), whoever turned it: the end of the last moving tick, where your gaps between moves
     * were measured from, before the hand's slow tail to its rest.
     */
    private var turnedClock = Double.NEGATIVE_INFINITY
    /** The view's moving ticks so far ([turnedClock]'s identity): the end of the last turn is the same one until the next. */
    var turns = 0L
        private set
    /** The turn [turnedAt] last read, and when it ended on its reader's clock. */
    private var readTurn = - 1L
    private var readTurnAt = Long.MIN_VALUE / 2
    /**
     * The view's ticks segmented into moves online the learner's way ([ViewMotion.segment]): the view at the tick before the
     * move in progress (or the last one) first moved and the still ticks since its last moving one ([ViewMotion.STILL_TICKS]
     * end it); the clock of the last moving tick of a move of [BehaviorLearner.GAP_MOVE_DEG] or more, and its identity.
     */
    private var segFrom: View? = null
    private var segStill = ViewMotion.STILL_TICKS
    private var bigClock = Double.NEGATIVE_INFINITY
    private var bigTurns = 0L
    private var readBig = - 1L
    private var readBigAt = Long.MIN_VALUE / 2
    /**
     * The draws of the next move ([start]) as a seed, taken once it is first needed ([leadMs] plans the same move ahead):
     * the move that starts is the one whose first visible tick was timed.
     */
    private var seed: Long? = null

    /**
     * How long ago the last move of the view (any owner's) came to rest, in ms: 0 while one is in flight (coasting to rest
     * included), infinite before the first. The next separate move is timed from that rest, not from when someone noticed it.
     */
    fun sinceRestMs(): Double = if (motor.moving) 0.0 else clock() - restClock

    /**
     * When the last move of the view came to rest on the clock of [now] (a client tick's wall clock, read at its start),
     * [Long.MIN_VALUE] / 2 before the first; [now] while one is in flight. Read once per rest ([rests]) and kept: the
     * driver's clock is read at the call, later in the tick than [now], so the same rest derived again at every tick moves by
     * a millisecond now and then (on a third to four fifths of the ticks), and every wait drawn per rest would be drawn
     * again each time, the soonest of many draws. It is placed against the driver's clock at this tick's start ([tick]),
     * where [now] was read too: against the call's, it lands up to a tick early, before the tick that showed it, and a
     * wait timed from that tick ends a tick early.
     */
    fun restedAt(now: Long): Long {
        if (motor.moving) return now
        if (restClock == Double.NEGATIVE_INFINITY) return Long.MIN_VALUE / 2
        if (readRest != rests) {
            readRest = rests
            val call = clock()
            val start = if (tickClock.isNaN() || call - tickClock > TICK_MS) call else tickClock
            readAt = now - max(0.0, start - restClock).toLong()
        }
        return readAt
    }
    /**
     * When the view last turned faster than a still view by a tick ([turnedClock]) on the clock of [now] (a client tick's
     * wall clock, read at its start), [Long.MIN_VALUE] / 2 before the first: a wait you measured as a gap between two of
     * your moves runs from here, as the learner measured it from the last moving tick of the move before. Read once per
     * turn ([turns]) and kept, like [restedAt].
     */
    fun turnedAt(now: Long): Long {
        if (turnedClock == Double.NEGATIVE_INFINITY) return Long.MIN_VALUE / 2
        if (readTurn != turns) {
            readTurn = turns
            readTurnAt = onTickClock(now, turnedClock)
        }
        return readTurnAt
    }

    /**
     * When the last move of the view of [BehaviorLearner.GAP_MOVE_DEG] or more (as the learner segments the view's ticks)
     * had its last moving tick, on the clock of [now], [Long.MIN_VALUE] / 2 before the first: a smaller one after it (a
     * stroke's short correction split off by two slow ticks, a big nudge) is no rest your way back from a look was timed
     * from (the learner's returnGap). Read once per such move and kept, like [turnedAt].
     */
    fun bigTurnedAt(now: Long): Long {
        if (bigClock == Double.NEGATIVE_INFINITY) return Long.MIN_VALUE / 2
        if (readBig != bigTurns) {
            readBig = bigTurns
            readBigAt = onTickClock(now, bigClock)
        }
        return readBigAt
    }

    /** The driver's [at] on the clock of [now] (a client tick's wall clock, read at its start), placed against this tick's start. */
    private fun onTickClock(now: Long, at: Double): Long {
        val call = clock()
        val start = if (tickClock.isNaN() || call - tickClock > TICK_MS) call else tickClock
        return now - max(0.0, start - at).toLong()
    }

    /**
     * When the view's last turn ended ([turnedAt]), for a wait timed from it: [now] while a move still turns the view
     * ([turning]), [Long.MIN_VALUE] / 2 before the first.
     */
    fun turnEndAt(now: Long): Long = if (turning) now else turnedAt(now)

    /**
     * How long after its start a move to [yaw]/[pitch] with [params] first turns the view faster than a still view by a
     * tick ([ViewMotion.THRESHOLD], where the learner finds a move to start): 0 when its first tick does, else whole
     * ticks; 0 too for one that never does. It is planned with the draws [start] takes next, from the view now and with
     * what still moves of the move in flight, so the move that starts is the one timed. Your gaps and latencies were
     * measured to that first moving tick (46 % of your moves of 6° or more had slower ones before it, as the planned
     * bell's onset has): a move timed by one starts this much before it.
     */
    fun leadMs(yaw: Double, pitch: Double, params: MotorParams = this.params, lookDown: Boolean = false): Long {
        val v = port.view() ?: return 0L
        val m = motor.copy()
        val at = clock()
        m.start(v.yaw, v.pitch, yaw, pitch, at, params, Random(nextSeed()), lookDown)
        var prev = v
        for (k in 1 .. LEAD_TICKS) {
            val s = m.sample(at + k * TICK_MS)
            if (MotorModel.angle(prev.yaw, prev.pitch, s.yaw, s.pitch) / TICK_MS * 1000.0 > ViewMotion.THRESHOLD) return ((k - 1) * TICK_MS).toLong()
            if (! m.moving) return 0L
            prev = s
        }
        return 0L
    }

    private fun nextSeed() = seed ?: rng.nextLong().also { seed = it }

    /** Your fishing view: set at your own casts, when Auto Fishing starts, and after your own mouse turns between fights. */
    var home: View? = null

    /** A move for [owner] is in flight (coasting to rest included). */
    fun moving(owner: Owner) = motor.moving && this.owner == owner

    /** A move of the killer's fight is in flight: its own or a look (coasting to rest included). */
    fun movingInFight() = motor.moving && fight(owner)

    /** [stop] for a move of the killer's fight, its own or a look. */
    fun stopFight() {
        if (! movingInFight()) return
        motor.stop(clock())
        rested()
    }

    /** [abort] for a move of the killer's fight, its own or a look. */
    fun abortFight() {
        if (! fight(owner)) return
        freeze()
        setView = null
    }

    /** Called after the motor was driven while a move was in flight: one that is no longer came to rest now. */
    private fun rested() {
        if (motor.moving) return
        restClock = clock()
        rests++
    }

    /** Freezes the motor (a move in flight comes to rest now). */
    private fun freeze() {
        if (motor.moving) {
            restClock = clock()
            rests++
        }
        motor.abort()
    }

    private fun fight(owner: Owner?) = owner == Owner.KILLER || owner == Owner.LOOK

    /**
     * Starts a move for [owner] from the view to [yaw]/[pitch]; a move still in flight keeps its speed and fades out
     * meanwhile. [lookDown]: a teleport blade's look-down, faster than the law. False without a player.
     */
    fun start(yaw: Double, pitch: Double, owner: Owner, lookDown: Boolean = false): Boolean {
        val v = port.view() ?: return false
        val was = motor.moving
        // the draws its lead was planned with ([leadMs])
        motor.start(v.yaw, v.pitch, yaw, pitch, clock(), params, Random(nextSeed()), lookDown)
        seed = null
        this.owner = owner
        started++
        setView = v
        rest.reset()
        // a goal where the view already is moves nothing: no rest either, the waits timed from the last one stay timed
        // from it; one cut off by it came to rest now
        if (was) rested()
        return true
    }

    /** The goal of the move in flight moved to [yaw]/[pitch] ([MotorModel.retarget]); [force]: it aims at something else now. */
    fun retarget(yaw: Double, pitch: Double, force: Boolean = false) {
        if (motor.moving) motor.retarget(yaw, pitch, clock(), force)
    }

    /** The move ends when its strokes in flight do (a stroke past the goal still comes back, [MotorModel.endAfterStroke]). */
    fun endAfterStroke() = motor.endAfterStroke()

    /** Brings a move of [owner] (any owner's when null) to rest, decelerating: for the killer's own transitions. */
    fun stop(owner: Owner? = null) {
        if (! motor.moving || owner != null && this.owner != owner) return
        motor.stop(clock())
        rested()
    }

    /** Freezes a move of [owner] (any owner's when null) at once: takeover, a screen, a reset. */
    fun abort(owner: Owner? = null) {
        if (owner != null && this.owner != owner) return
        freeze()
        setView = null
    }

    /**
     * One tick of [policy]'s looks around: its inputs from this driver (the view, [home], a look's move in flight, any
     * other move in flight) and the caller's ([phase] null: no look may start; the rest as [GlancePolicy.Inputs]); a
     * look or its return starts with your learned hand ([glanceParamsOf] for a look, [returnParamsOf] for the return). The
     * decision, after it was carried out.
     */
    fun glance(
        policy: GlancePolicy, now: Long, phase: GlancePolicy.Phase?, motor: BehaviorProfile.Motor?, learned: BehaviorProfile.Glances?,
        windowMs: Double = Double.POSITIVE_INFINITY, sinceOwnDeathMs: Double = Double.POSITIVE_INFINITY, deadSpot: View? = null,
        returnNow: Boolean = false, turnBackOwed: Boolean = false, returnNotBefore: Long = Long.MIN_VALUE,
    ): GlancePolicy.Decision {
        val v = port.view() ?: return GlancePolicy.Decision.NONE
        policy.table = GlancePolicy.Table.of(learned)
        policy.lookParams = glanceParamsOf(motor, learned)
        policy.backParams = returnParamsOf(motor)
        val d = policy.update(GlancePolicy.Inputs(
            now, phase, v, home, windowMs, sinceOwnDeathMs, deadSpot, returnNow, turnBackOwed, moving(Owner.GLANCE), this.motor.moving && owner != Owner.GLANCE,
            returnNotBefore,
        ))
        if (d == GlancePolicy.Decision.LOOK || d == GlancePolicy.Decision.RETURN) {
            val g = policy.goal ?: return d
            params = if (d == GlancePolicy.Decision.LOOK) policy.lookParams else policy.backParams
            if (! start(g.yaw, g.pitch, Owner.GLANCE)) policy.failed()
        }
        return d
    }

    /** Every rendered frame, while no screen is open. */
    fun frame() {
        if (motor.moving) sample()
    }

    /**
     * Every client tick, while no screen is open: the move in flight is sampled; without one, [rest] allows a rest nudge
     * drifting back to [anchor].
     */
    fun tick(rest: Boolean, anchor: View?) {
        tickClock = clock()
        try {
            if (motor.moving) {
                sample()
                return
            }
            if (! rest) {
                this.rest.reset()
                return
            }
            val v = port.view() ?: return
            val d = this.rest.tick(v.yaw, v.pitch, anchor, port.grid, params, rng) ?: return
            port.set(v.yaw + d.yaw, v.pitch + d.pitch)
        }
        finally {
            // the view at this tick's end against the last one's, as the learner steps from line to line
            val seen = port.view()
            val before = tickView
            val dt = tickClock - lastTick
            if (seen != null && before != null && dt > 0.0 && MotorModel.angle(before.yaw, before.pitch, seen.yaw, seen.pitch) / dt * 1000.0 > ViewMotion.THRESHOLD) {
                turnedClock = tickClock
                turns++
                // a moving tick after two still ones starts a new move, from the view before it
                if (segStill >= ViewMotion.STILL_TICKS || segFrom == null) segFrom = before
                segStill = 0
                val from = segFrom ?: before
                if (MotorModel.angle(from.yaw, from.pitch, seen.yaw, seen.pitch) >= BehaviorLearner.GAP_MOVE_DEG) {
                    bigClock = tickClock
                    bigTurns++
                }
            }
            else if (segStill < ViewMotion.STILL_TICKS) segStill++
            tickView = seen
            lastTick = tickClock
        }
    }

    private fun sample() {
        val v = port.view() ?: return abort()
        setView?.let { s ->
            val dYaw = MotorModel.wrap(v.yaw - s.yaw)
            val dPitch = v.pitch - s.pitch
            if ((dYaw != 0.0 || dPitch != 0.0) && ! motor.external(dYaw, dPitch)) {
                setView = null
                rested()
                return
            }
        }
        val out = motor.sample(clock())
        // the motor's yaw is continuous: only the difference to the view is wrapped
        val set = port.set(v.yaw + MotorModel.wrap(out.yaw - v.yaw), out.pitch)
        setView = set
        if (! motor.moving) {
            setView = null
            rested()
            if (fight(owner)) restAnchor = set
        }
    }

    /** Forgets the rest anchor of the last fight. */
    fun clearRestAnchor() {
        restAnchor = null
    }

    /** The game's view: the player's rotation, turned through [PlayerUtils.rotate]. */
    object GamePort : Port {
        override fun view() = mc.player?.let { View(it.yRot.toDouble(), it.xRot.toDouble()) }

        override fun set(yaw: Double, pitch: Double): View {
            val p = mc.player ?: return View(yaw, pitch)
            PlayerUtils.rotate(yaw.toFloat(), MathUtils.normalizePitch(pitch.toFloat()))
            return View(p.yRot.toDouble(), p.xRot.toDouble())
        }

        override val grid: Double
            get() {
                val f = mc.options.sensitivity().get() * 0.6 + 0.2
                return f * f * f * 1.2
            }
    }

    companion object {
        /** From this pitch on you look straight up or down: the yaw of the view means nothing. */
        const val POLE_PITCH = 89.5f
        /** A target closer than this sideways is right above or below you. */
        private const val PITCH_ONLY_RADIUS = 0.5
        /** How far the vertical plane you look along may pass the target for a pitch-only turn. */
        private const val PLANE_MISS = 0.25
        /** A target this close to your eye is hit from inside its box, wherever you look. */
        private const val AT_EYE = 0.3
        /** A client tick: a driver's tick longer ago than this was not this tick's (a screen stopped its ticks). */
        private const val TICK_MS = 50.0
        /** A move's lead ([leadMs]) is looked for within this many ticks of its start (a slow move's first strokes may take a while). */
        private const val LEAD_TICKS = 40

        /** Idle rest nudges drift back to your fishing view only within this many degrees of it. */
        const val IDLE_ANCHOR_DEG = 8.0

        /** The one driver of the game's view. */
        val shared by lazy { HumanAim() }

        /** Your looks around and back on [shared]: one episode at a time, whoever feeds it (Auto Fishing or the killer). */
        val glances by lazy { GlancePolicy() }

        /** The client tick [glances] was last fed in: the killer and Auto Fishing never both feed it in one tick. */
        @JvmStatic
        var glanceTick = - 1

        /** Rest nudges of the killer's fights are on (they drift back to [restAnchor]); set by the killer. */
        @JvmStatic
        var killerRest: () -> Boolean = { false }
        /** Idle rest nudges are on (they drift back to [home] when it is close); for Auto Fishing's Shake Head to set. */
        @JvmStatic
        var idleRest: () -> Boolean = { false }
        private var installed = false

        /**
         * Registers the [shared] driver's hooks once (calling it again does nothing): [frame] on every rendered frame and
         * [tick] at the start of every client tick, never while a screen is open (no hand turns the view behind it).
         */
        fun install() {
            if (installed) return
            installed = true
            EventBus.register<RenderWorldEvent> { if (mc.gui.screen() == null) shared.frame() }
            EventBus.register<TickEvent.Start>(EventPriority.HIGH) {
                if (mc.gui.screen() != null || mc.player == null) return@register
                val aim = shared
                val killer = killerRest()
                val idle = ! killer && idleRest()
                // a look's dwell rests where the look ended
                val anchor = if (glances.out) glances.endView else if (killer) aim.restAnchor else aim.home?.takeIf { h ->
                    port().view()?.let { MotorModel.angle(it.yaw, it.pitch, h.yaw, h.pitch) <= IDLE_ANCHOR_DEG } == true
                }
                aim.tick(killer || idle, anchor)
            }
        }

        private fun port(): Port = GamePort

        /** Great-circle angle between two views in degrees: close to straight up or down, a big yaw difference is a small turn. */
        fun angle(yaw1: Float, pitch1: Float, yaw2: Float, pitch2: Float) =
            MotorModel.angle(yaw1.toDouble(), pitch1.toDouble(), yaw2.toDouble(), pitch2.toDouble()).toFloat()

        /** How far the view [yaw]/[pitch] is from a view turned back to: straight up or down only the pitch counts. */
        fun distance(yaw: Float, pitch: Float, toYaw: Float, toPitch: Float) =
            if (abs(toPitch) >= POLE_PITCH) abs(pitch - toPitch) else angle(yaw, pitch, toYaw, toPitch)

        /** The goal of a turn back to [home] from [yaw]: straight up or down only its pitch, keeping the yaw. */
        fun homeGoal(yaw: Double, home: View) = if (abs(home.pitch) >= POLE_PITCH) View(yaw, home.pitch) else home

        /**
         * The view on a target [dx]/[dy]/[dz] from your eye, for a view looking along [viewYaw]/[viewPitch], decided once
         * at a move's start ([pitchOnly]: null decides it now). Closer than [PITCH_ONLY_RADIUS] sideways (right above or
         * below you) only the pitch turns, within the vertical plane you look along, as long as that plane passes the target
         * closely; a target at your eye needs no turn at all (you hit it from inside). The second value says whether this
         * is a pitch-only view.
         */
        fun aimView(viewYaw: Double, viewPitch: Double, dx: Double, dy: Double, dz: Double, pitchOnly: Boolean? = null): Pair<View, Boolean> {
            val flat = hypot(dx, dz)
            if (pitchOnly != false && (pitchOnly == true || flat < PITCH_ONLY_RADIUS)) {
                if (flat * flat + dy * dy < AT_EYE * AT_EYE) return View(viewYaw, viewPitch) to true
                val yaw = Math.toRadians(viewYaw)
                val forward = - dx * sin(yaw) + dz * cos(yaw)
                val side = dx * cos(yaw) + dz * sin(yaw)
                // behind you in that plane the view goes straight up or down, which passes the target at its full distance
                if (pitchOnly == true || (if (forward > 0.0) abs(side) else flat) <= PLANE_MISS)
                    return View(viewYaw, - Math.toDegrees(atan2(dy, max(forward, 0.0)))) to true
            }
            return View(Math.toDegrees(atan2(- dx, dz)), Math.toDegrees(- atan2(dy, flat))) to false
        }

        private var paramsSource: BehaviorProfile.Motor? = null
        private var paramsCache = MotorParams()

        /**
         * Your learned hand ([BehaviorProfile.Motor]'s planning parameters) as [MotorParams]; anything missing, not
         * finite or out of its sane range keeps the default.
         */
        fun paramsOf(m: BehaviorProfile.Motor?): MotorParams {
            if (m == null) return MotorParams()
            if (m === paramsSource) return paramsCache
            val d = MotorParams()
            fun ok(v: Double?, lo: Double, hi: Double, default: Double) = v?.takeIf { it.isFinite() && it in lo .. hi } ?: default
            // Gson leaves a list missing from an edited profile null
            val raw: List<Double?>? = m.multiP
            val multi = raw?.takeIf { l -> l.size == d.multiP.size && l.all { it != null && it.isFinite() } }?.map { (it ?: 0.0).coerceIn(0.0, 0.95) } ?: d.multiP
            val restRaw: List<Double?>? = m.restSteps
            val rest = restRaw?.takeIf { l -> l.size in 1 .. MAX_REST_STEPS && l.all { it != null && it.isFinite() && it >= 0.0 } && l.sumOf { it ?: 0.0 } > 0.0 }?.map { it ?: 0.0 }
                ?: d.restSteps
            val p = d.copy(
                lawA = ok(m.lawA, 10.0, 400.0, d.lawA),
                lawB = ok(m.lawB, 5.0, 400.0, d.lawB),
                durationSigma = ok(m.sigma, 0.05, 1.0, d.durationSigma),
                multiP = multi,
                firstMean = ok(m.firstMu, 0.35, 1.2, d.firstMean),
                slowP = ok(m.slowP, 0.0, 0.5, d.slowP),
                lagP50Ms = ok(m.axisLagMs, 1.0, 200.0, d.lagP50Ms),
                tauMean = ok(m.tauMean, d.tauMin, d.tauMax, d.tauMean),
                crossSd = ok(m.crossSd, 0.0, 0.3, d.crossSd),
                overlapP = ok(m.overlapP, 0.0, 1.0, d.overlapP),
                restStartP = ok(m.restStart, 0.0, 0.2, d.restStartP),
                restSteps = rest,
                restPoleP50 = ok(m.restPoleP50, 0.5, 50.0, d.restPoleP50),
                restPoleSigma = ok(m.restPoleSigma, 0.05, 4.0, d.restPoleSigma),
                gcCap = ok(m.capGc, 200.0, d.hardCap, d.gcCap),
                mouseCap = ok(m.capMouse, 200.0, d.hardCap, d.mouseCap),
                poleFactor = ok(m.poleFactor, 0.5, 2.5, d.poleFactor),
                lookDownFactor = ok(m.lookDownFactor, 0.2, 1.5, d.lookDownFactor),
            )
            paramsSource = m
            paramsCache = p
            return p
        }

        private var returnSource: BehaviorProfile.Motor? = null
        private var returnCache = MotorParams()

        /**
         * Your hand for the way back from a look around: [paramsOf] your [m] with the first stroke of your ways back from a
         * look ([BehaviorProfile.Motor.returnFirstMu], the moves the learner finds returns and turns back to the fight) and
         * no stroke past its goal (a middle stroke short of the goal leaves the last one less of the way). None of your 20
         * returns and turns back to the fight of 20° or more went past their end by more than 1°.
         */
        fun returnParamsOf(m: BehaviorProfile.Motor?): MotorParams {
            if (m != null && m === returnSource) return returnCache
            val p = paramsOf(m).copy(firstMean = muOf(m?.returnFirstMu, BehaviorProfile.Motor().returnFirstMu), firstMax = LOOK_FIRST_MAX, middleMax = LOOK_FIRST_MAX)
            if (m != null) {
                returnSource = m
                returnCache = p
            }
            return p
        }

        private var turnBackSource: BehaviorProfile.Motor? = null
        private var turnBackCache = MotorParams()

        /**
         * Your hand for the killer's turn back to your fishing view after a fight: [paramsOf] your [m] with the first stroke
         * of your turn backs ([backFirstOf]), never past its goal; a middle stroke may go past it like your turns' (your turn
         * backs that went past their end by more than 1°, 5 of 31, all did so after a first stroke short of it). Your ways
         * back to the fishing view after a fight are back from a turn toward a creature, what the learner finds turn backs;
         * the killer's way back from a look takes it when it closes on no creature ([wayBackParamsOf]).
         */
        fun turnBackParamsOf(m: BehaviorProfile.Motor?): MotorParams {
            if (m != null && m === turnBackSource) return turnBackCache
            val p = paramsOf(m).copy(firstMean = backFirstOf(m), firstMax = LOOK_FIRST_MAX)
            if (m != null) {
                turnBackSource = m
                turnBackCache = p
            }
            return p
        }

        /** The planned mean of a turn back's first stroke ([BehaviorProfile.Motor.backFirstMu]); missing, not finite or outside the learner's range: the default. */
        private fun backFirstOf(m: BehaviorProfile.Motor?) = muOf(m?.backFirstMu, BehaviorProfile.Motor().backFirstMu)

        /** A way back's planned first stroke [mu] as learned; missing, not finite or outside the learner's range: [default]. */
        private fun muOf(mu: Double?, default: Double) = mu?.takeIf { it.isFinite() && it in BehaviorLearner.BACK_MU_MIN .. BehaviorLearner.BACK_MU_MAX } ?: default

        /**
         * Your hand for a way back of the killer's that the learner finds to be of [kind] ([GlanceMeasure]), the hand your
         * moves of that kind have: an aim (one that closes on a creature of yours, [lookBackKind]) your turns' ([paramsOf]), a
         * turn back your turn backs' ([turnBackParamsOf]), a return or a way back to the fight your ways back from a look
         * ([returnParamsOf]). One that has to keep the plan's creature in view ([keep]) keeps it the way [keepParamsOf] does,
         * with that kind's first stroke.
         */
        fun wayBackParamsOf(m: BehaviorProfile.Motor?, kind: GlanceMeasure.Kind, keep: Boolean): MotorParams = when (kind) {
            GlanceMeasure.Kind.AIM -> if (keep) keepParamsOf(m) else paramsOf(m)
            GlanceMeasure.Kind.TURN_BACK -> if (keep) keepParamsOf(m).copy(firstMean = backFirstOf(m)) else turnBackParamsOf(m)
            else -> if (keep) keepParamsOf(m).copy(firstMean = muOf(m?.returnFirstMu, BehaviorProfile.Motor().returnFirstMu)) else returnParamsOf(m)
        }

        /**
         * What the learner finds the killer's way back from its look at a creature to be ([GlanceMeasure.kindOf]): one whose
         * [goal] closes on a creature of yours ([creatures]' views: [GlanceMeasure.TURN_CLOSES] of its error from [from] or
         * less) a turn toward it, an aim; any other the way back from that look, itself a turn toward a creature: a turn back.
         */
        fun lookBackKind(from: View, goal: View, creatures: List<View>): GlanceMeasure.Kind =
            if (creatures.any { c -> MotorModel.angle(goal.yaw, goal.pitch, c.yaw, c.pitch) <= GlanceMeasure.TURN_CLOSES * MotorModel.angle(from.yaw, from.pitch, c.yaw, c.pitch) })
                GlanceMeasure.Kind.AIM else GlanceMeasure.Kind.TURN_BACK

        private var glanceSource: Pair<BehaviorProfile.Motor?, BehaviorProfile.Glances?>? = null
        private var glanceCache = MotorParams()

        /**
         * Your hand for a look around ([GlancePolicy]): [paramsOf] your [m] with the looks' own motion from [g] (its
         * defaults without one): fewer corrective strokes and slow moves, a little faster, one stroke that never
         * overshoots on purpose, and straight. Their returns move like the way back from a look ([returnParamsOf]).
         */
        fun glanceParamsOf(m: BehaviorProfile.Motor?, g: BehaviorProfile.Glances?): MotorParams {
            glanceSource?.let { (sm, sg) -> if (sm === m && sg === g && g != null) return glanceCache }
            val base = paramsOf(m)
            val d = BehaviorProfile.Glances()
            fun ok(v: Double?, lo: Double, hi: Double, default: Double) = v?.takeIf { it.isFinite() && it in lo .. hi } ?: default
            val multi = ok(g?.multiScale, 0.05, 1.0, d.multiScale)
            val slow = ok(g?.slowScale, 0.05, 1.0, d.slowScale)
            // one stroke instead of several already makes most of a look's speed: the law keeps only the rest of yours
            val factor = (ok(g?.durationFactor, 0.5, 1.5, d.durationFactor) / LOOK_STROKE_SPEEDUP).coerceIn(LOOK_LAW_MIN, LOOK_LAW_MAX)
            val p = base.copy(
                multiP = base.multiP.map { it * multi },
                slowP = base.slowP * slow,
                lawA = base.lawA * factor,
                lawB = base.lawB * factor,
                firstMax = LOOK_FIRST_MAX,
                leadInP = LOOK_LEAD_IN_P,
                crossSd = base.crossSd * LOOK_CROSS_SHARE,
            )
            glanceSource = m to g
            glanceCache = p
            return p
        }

        /**
         * Your hand for a look that has to keep the plan's creature where its click needs it all the way ([AutoMobKiller]'s
         * looks at your other creatures in a fight): [paramsOf] your [m] with no stroke past the goal (its first and middle
         * strokes are drawn short of it, never piled on it), no slow move (its strokes wander past the goal and across) and
         * half the cross-track error, like a look around's.
         */
        fun keepParamsOf(m: BehaviorProfile.Motor?): MotorParams =
            paramsOf(m).let { it.copy(firstMax = LOOK_FIRST_MAX, middleMax = LOOK_FIRST_MAX, slowP = 0.0, crossSd = it.crossSd * LOOK_CROSS_SHARE) }

        /** A learned rest nudge size list longer than this is broken (the learner keeps 20). */
        private const val MAX_REST_STEPS = 40
        /** A look's first stroke never goes past its goal, rarely starts with a short lead-in, and keeps half the cross-track error. */
        private const val LOOK_FIRST_MAX = 1.0
        private const val LOOK_LEAD_IN_P = 0.05
        private const val LOOK_CROSS_SHARE = 0.5
        /**
         * With the reference hand, looks of the looks' stroke mix alone measure this share of the duration law your other
         * moves do (GlancePolicyTest's motion test without a law factor): what the learned duration factor leaves to the law.
         */
        private const val LOOK_STROKE_SPEEDUP = 0.9
        private const val LOOK_LAW_MIN = 0.6
        private const val LOOK_LAW_MAX = 1.4
    }
}

//#endif
