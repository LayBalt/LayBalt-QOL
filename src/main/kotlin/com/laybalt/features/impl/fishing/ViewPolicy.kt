package com.laybalt.features.impl.fishing

//#if CHEAT

import com.laybalt.features.impl.fishing.BehaviorProfile.Dist
import com.laybalt.features.impl.fishing.BehaviorProfile.LookStats
import com.laybalt.features.impl.fishing.BehaviorProfile.Looks as LearnedLooks
import com.laybalt.features.impl.fishing.MotorModel.View
import com.laybalt.features.impl.fishing.TimingModel.Action
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * When the killer moves your view in a fight, the way you do (DESIGN-HUMAN §2): only when the plan needs aim, never
 * before your reaction to the creature, closing the error to the plan's tolerance instead of to zero, and never pursuing
 * a creature that moves. After a move the view stays still until the creature is off by more than the tolerance plus a
 * slack drawn for that still period; a discrete correction follows after a reaction.
 *
 * One instance per fight, fed once a tick through [update]. It owns the first turn's anchor and latency, the acceptance
 * error of each move, the slack of each still period and the reaction to a creature leaving it. Pure: no Minecraft
 * calls, deterministic for a given [Random].
 */
class ViewPolicy(private val rng: Random = Random.Default) {
    /** What the plan needs the view for. */
    enum class Need {
        /** Nothing (the bobber, an area weapon): the view never turns. */
        NONE,
        /** The crosshair on the creature (rod or weapon melee). */
        MELEE,
        /** The view within an aim error of the creature (right presses, the use key held). */
        RANGED,
        /** Only the pitch, looking down (a teleport blade). */
        PITCH,
    }

    /** What to do with the view this tick. */
    enum class Decision {
        NONE,
        /** Start a move to [goal]. */
        START,
        /** The move in flight aims at [goal] from now on; [force]: what it aims at changed (a look-down became a creature aim). */
        RETARGET,
        /** The move ends once its strokes in flight do, each at zero speed. */
        END,
        /** The move comes to rest, decelerating. */
        STOP,
    }

    /**
     * This tick's fight as the policy sees it.
     * - [error]: the view's error to what the plan aims at, in degrees (great-circle; for [Need.PITCH] how far the pitch is
     *   above the plan's pitch, 0 at or below it).
     * - [tolerance]: the error the plan accepts (its `aimDeg`, the box's angular half-size, the pitch slack); NaN for an
     *   explicit ranged rule, which accepts the [acceptance] it drew for the last move.
     * - [aim]: the view on the aim point (for a look-down: the view's yaw and the plan's pitch); [onTarget]: the crosshair
     *   is on the creature (melee).
     * - [moving]: a move of the killer is in flight (not coasting to rest); [strokes]: strokes it started.
     * - [anchorAt]: the swap for this creature ([swapped]), else its spawn or its return from parking.
     * - [maxWaitMs]: the latest first turn after a spawn (Give Up After minus 2 s).
     * - [accept]: draws a move's acceptance error, once per move; [shortShare]: the share of it the move stops short of
     *   the aim point (drawn once per move).
     * - [looking]: a look of the killer ([Looks]) moves the view: no correction meanwhile, and one it left the creature
     *   beyond the tolerance comes after a reaction, without the slack.
     * - [notBefore]: the target's first turn comes at this moment instead of after its latency (after the killer's
     *   loadout menu, a turn timed to get there a reaction before the first click: yours all came within 284 ms of the
     *   close, never on the swap's latency); [Long.MIN_VALUE]: none. Only the target's first moment takes it: a later
     *   re-anchor (another plan, a swap) times its turn from its own latency.
     * - [land]: the view on a point of the creature's box ([Landing]), where a melee move comes to rest instead of [aim]
     *   (null: [aim]); [landing] draws that point once per move. Your turns onto a creature never came to rest on its
     *   centre line: each one somewhere else on the box ([landingSide]).
     * - [lookDown]: draws the pitch a look-down comes to rest at, once per move (NaN: [aim]'s).
     * - [braking]: a move of the killer still comes to rest ([moving] is false then).
     * - [viewMoving]: any move of the view is in flight, braking included, whoever's (a look around's, Auto Fishing's):
     *   no move starts meanwhile; [restedAt]: when the last one came to rest (the motion driver knows: the end of its last
     *   moving tick, [HumanAim.turnedAt], where your gaps were measured from; [Long.MIN_VALUE]: the first update that finds
     *   the view still stands in for it). A move comes your shortest gap between moves after that rest at the soonest
     *   (yours never came closer than 95 ms, p50 200). [restId]: which rest that is ([HumanAim.turns]; by default its
     *   time): a wait drawn per rest is drawn once per rest, however its time reads.
     * - [lead]: how long after its start a move to a goal first turns the view the way the learner sees a move start
     *   ([HumanAim.leadMs]): your latencies, reactions and gaps were measured to that first moving tick, so a move timed by
     *   one starts that much before its moment (0: at it).
     */
    class Situation(
        val now: Long,
        val target: Int,
        val need: Need,
        val view: View,
        val aim: View,
        val error: Double,
        val tolerance: Double,
        val onTarget: Boolean = false,
        val moving: Boolean = false,
        val strokes: Int = 0,
        val anchorAt: Long = now,
        val swapped: Boolean = false,
        val fallsIntoView: Boolean = false,
        val maxWaitMs: Double = Double.POSITIVE_INFINITY,
        val accept: () -> Double = { 0.0 },
        val shortShare: ClosedFloatingPointRange<Double> = 1.0 .. 1.0,
        val looking: Boolean = false,
        val notBefore: Long = Long.MIN_VALUE,
        val land: ((Landing) -> View)? = null,
        val landing: () -> Landing = { Landing.CENTRE },
        val lookDown: () -> Double = { Double.NaN },
        val braking: Boolean = false,
        val restedAt: Long = Long.MIN_VALUE,
        val viewMoving: Boolean = moving || braking || looking,
        val restId: Long = restedAt,
        val lead: (View) -> Long = { 0L },
    )

    /** A point of a creature's box: [side] shares of its half-width beside its centre line (either way), at [up] of its height. */
    class Landing(val side: Double, val up: Double) {
        companion object {
            val CENTRE = Landing(0.0, 0.5)
        }
    }

    /**
     * Learned distributions that replace the defaults (DESIGN-HUMAN-AMENDMENTS §2.4-2.7); null keeps the default. A
     * distribution counts from [BehaviorProfile.Policy.MIN_SAMPLES] samples with positive percentiles on. The learned
     * turn back latency is none of them: it is measured over all your turn backs from the death, the ones that came after
     * a move or a held key went on included, so the turn back draws yours split by that ([turnBack]).
     */
    class Learned(
        val swapLatency: Dist? = null,
        val spawnLatency: Dist? = null,
        val slack: Dist? = null,
        val backRate: Double = BACK_RATE,
        val backCastErr: Dist? = null,
    ) {
        companion object {
            /** The learned [policy] for the weapon:button class [cls] (`HYPERION:R`, `FLAMING_FLAY:Rh`), falling back to all classes. */
            fun of(policy: BehaviorProfile.Policy?, cls: String?): Learned {
                if (policy == null) return Learned()
                val c = cls?.let { policy.classes[it] }
                fun pick(f: (BehaviorProfile.TurnStats) -> Dist?) = c?.let(f)?.takeIf(::usable) ?: policy.all.let(f)?.takeIf(::usable)
                return Learned(
                    pick { it.swapLatency }, pick { it.spawnLatency }, pick { it.slack },
                    if (policy.backBeyond - policy.backInterrupted >= BehaviorProfile.Policy.MIN_SAMPLES) policy.backRate.coerceIn(0.0, 1.0) else BACK_RATE,
                    policy.backCastErr.takeIf(::usable),
                )
            }

            private fun usable(d: Dist?) = d != null && d.n >= BehaviorProfile.Policy.MIN_SAMPLES && d.p10 > 0.0 && d.p50 > 0.0 && d.lnSigma > 0.0
        }
    }

    var learned = Learned()

    private var target = - 1
    private var need = Need.NONE
    private var anchor = 0L
    /** The first turn towards this creature happened or was found not needed at its moment. */
    private var firstDone = false
    private var turnAt = - 1L
    /** This target's first moment was timed already: a [Situation.notBefore] is used once per target. */
    private var timed = false
    private var wasMoving = false
    /** [Decision.END] went out for the move in flight. */
    private var ended = false
    /** When the last move ended (or the first turn was found not needed). */
    private var stillSince = Long.MIN_VALUE / 2
    /** A move of the view was in flight at the last [update]. */
    private var wasBusy = false
    /**
     * The first update that found the view still after a move, kept across targets: when that move came to rest for a
     * caller that does not pass [Situation.restedAt].
     */
    private var stillAt = Long.MIN_VALUE / 2
    /** The first turn's moment came while a move of the view was in flight: it follows one of your gaps after its rest. */
    private var dueBusy = false
    /** The rest that gap was drawn for, and when it is over. */
    private var gapFor = Long.MIN_VALUE
    private var gapOver = 0L
    /** The first turn's moment is the target's latency from its anchor (not a moment the fight timed, [Situation.notBefore]). */
    private var byLatency = true
    private var slack = 0.0
    /** The view was within the tolerance in this still period: from then on it waits for the slack as well. */
    private var slackOn = false
    private var offSince = - 1L
    private var reaction = 0L
    private var share = 1.0
    /** Where on the box the current or last melee move comes to rest, and the pitch of a look-down (NaN: the plan's). */
    private var landing = Landing.CENTRE
    private var landPitch = Double.NaN
    /**
     * The next move's own draws (its acceptance, the share of it it stops short by, where on the box or at which pitch it
     * comes to rest), made once before it starts and kept until it does: its [Situation.lead] is planned on the goal they
     * give. [nextFor]: the need they were drawn for (null: none drawn).
     */
    private var nextAccept = Double.NaN
    private var nextShare = 1.0
    private var nextLanding = Landing.CENTRE
    private var nextPitch = Double.NaN
    private var nextFor: Need? = null

    /** The acceptance error of the current or last move (NaN before the first). */
    var acceptance = Double.NaN
        private set
    /** Where [Decision.START] and [Decision.RETARGET] aim. */
    var goal: View? = null
        private set
    /** [Decision.RETARGET] switches what the move aims at: correct the stroke in flight whatever the distance. */
    var force = false
        private set
    /** Moves started towards the current target (the first turn and corrections). */
    var moves = 0
        private set

    /** The view was within the tolerance at the last [update]. */
    private var lastWithin = false

    /**
     * The policy is waiting to turn: the view is off the creature and the first turn's moment, or the reaction to a
     * creature that left the tolerance, has not come yet. A melee use that does not land meanwhile is no failure of the
     * plan ([AutoMobKiller.FightRules.countsUse]).
     */
    val waiting get() = target >= 0 && need != Need.NONE && ! lastWithin && (! firstDone || offSince >= 0)

    /** The last [update] was the first turn's moment and found no turn needed: the view was within the tolerance. */
    var foundFree = false
        private set

    /**
     * The plan needs no turn now: it never turns ([Need.NONE]), or its first turn was found not needed and the view is
     * still within the tolerance ([Looks] may look at this target then).
     */
    val free get() = target >= 0 && (need == Need.NONE || firstDone && moves == 0 && lastWithin)

    /**
     * How long from [now] the plan leaves the view alone, for a look around ([GlancePolicy]): infinite when it never
     * turns ([Need.NONE]) or has no target, until the first turn's moment while that waits, else not at all (its first
     * turn is due or done: the view is the plan's).
     */
    fun freeForMs(now: Long): Double = when {
        target < 0 || need == Need.NONE -> Double.POSITIVE_INFINITY
        ! firstDone && turnAt >= 0 -> max(0.0, (turnAt - now).toDouble())
        else -> 0.0
    }

    /** Forgets the fight (a new one starts from scratch). */
    fun reset() {
        target = - 1
        need = Need.NONE
        wasMoving = false
        wasBusy = false
        stillAt = Long.MIN_VALUE / 2
        newTarget(- 1, 0L)
    }

    /** This tick's decision for [s]; [goal] and [force] go with it. */
    fun update(s: Situation): Decision {
        goal = null
        force = false
        foundFree = false
        if (wasBusy && ! s.viewMoving) stillAt = s.now
        wasBusy = s.viewMoving
        var stopOld = false
        if (s.target != target) {
            // a move towards the creature before is no move of this one
            stopOld = s.moving && target >= 0
            target = s.target
            need = s.need
            newTarget(s.target, s.anchorAt)
        }
        val needChanged = s.need != need
        need = s.need
        if (wasMoving && ! s.moving) beginStill(s)
        wasMoving = s.moving
        if (s.need == Need.NONE) return if (s.moving) Decision.STOP else Decision.NONE
        if (stopOld) return Decision.STOP

        if (s.moving) {
            if (needChanged) {
                acceptance = s.accept()
                drawLanding(s)
                goal = goalOf(s)
                force = true
                return Decision.RETARGET
            }
            if (ended) return Decision.NONE
            // melee is there once the crosshair is on the creature (or after a few strokes, wherever it is); a ranged
            // move once it is within its acceptance: the strokes in flight finish, no further one starts
            val there = when (s.need) {
                Need.MELEE -> s.onTarget || s.strokes >= MAX_MELEE_STROKES
                Need.RANGED -> s.error <= acceptance * share
                else -> false
            }
            if (there) {
                ended = true
                return Decision.END
            }
            goal = goalOf(s)
            return Decision.RETARGET
        }

        // another plan (or a swap) for the same creature: its first turn is timed from the new anchor
        if (needChanged || s.anchorAt > anchor) {
            anchor = maxOf(anchor, s.anchorAt)
            firstDone = false
            turnAt = - 1L
            offSince = - 1L
            dueBusy = false
        }
        // an explicit rule's tolerance is its acceptance, needed before its first move
        if (s.tolerance.isNaN() && acceptance.isNaN()) acceptance = s.accept()
        val tolerance = if (s.tolerance.isNaN()) acceptance else s.tolerance
        lastWithin = within(s, tolerance)
        if (! firstDone) {
            if (turnAt < 0) {
                byLatency = timed || s.notBefore == Long.MIN_VALUE
                turnAt = if (byLatency) anchor + firstLatency(s) else s.notBefore
                timed = true
            }
            // falling into the crosshair by itself: the turn waits for the fall
            if (s.fallsIntoView) return Decision.NONE
            // after a swap your first turn came one of your gaps between moves after a move of the view that went on past
            // the swap (in flight at it, or a look after it: 8 of your 20, 99-399 ms after its rest), not on your latency
            if (s.swapped && byLatency && (s.viewMoving || restOf(s) > anchor)) {
                if (! gapAfterRest(s)) return Decision.NONE
            }
            else {
                // your latency ran to the turn's first moving tick: a turn starts its lead before it (whether none is
                // needed is found at the moment itself)
                if (s.now < turnAt && (within(s, tolerance) || ! reached(s, turnAt))) return Decision.NONE
                // a move of the view before (the one stopped as the target changed, a look, Auto Fishing's) came to rest first
                if (! within(s, tolerance) && ! firstRestOver(s)) return Decision.NONE
            }
            firstDone = true
            if (within(s, tolerance)) {
                stillSince = s.now
                slack = drawSlack()
                slackOn = true
                foundFree = true
                return Decision.NONE
            }
            return start(s)
        }

        // the plan's clicks wait for a view a look left off the creature only for your reaction, not for the slack
        if (s.looking) {
            slackOn = false
            offSince = - 1L
            return Decision.NONE
        }
        if (within(s, tolerance)) slackOn = true
        // a move that ended outside the tolerance (the hand's spread) is corrected after a reaction; one that got there
        // waits until the creature is off by the slack as well
        val trigger = tolerance + if (slackOn) slack else 0.0
        if (s.onTarget || s.error <= trigger) {
            offSince = - 1L
            return Decision.NONE
        }
        if (offSince < 0) {
            offSince = s.now
            reaction = TimingModel.draw(Action.CORRECTION_REACTION, emptyList(), null, rng)
        }
        // never while a move of the view is in flight (braking too); a reaction that would start the correction sooner than
        // your shortest gap between moves after the last one came to rest is drawn again until it does not (it piled two
        // ticks after the rest), so it is a separate move of its own
        if (s.viewMoving) return Decision.NONE
        reaction = reactionAfter(reaction, restOf(s) + MIN_GAP_MS - offSince)
        if (! reached(s, offSince + reaction)) return Decision.NONE
        return start(s)
    }

    /**
     * The moment [at] a move is timed to show its first moving tick has come for one starting now: it is here, or the
     * next move's lead ([Situation.lead], planned on its own draws, [drawNext]) reaches it from now.
     */
    private fun reached(s: Situation, at: Long): Boolean {
        if (s.now >= at) return true
        if (at - s.now > MAX_LEAD_MS) return false
        drawNext(s)
        return s.now + s.lead(goalOf(s, nextAccept * nextShare, nextLanding, nextPitch)) >= at
    }

    /** The next move's own draws for [s]'s need, once ([nextFor]); [start] takes them. */
    private fun drawNext(s: Situation) {
        if (nextFor == s.need) return
        nextAccept = s.accept()
        nextShare = if (s.shortShare.start >= s.shortShare.endInclusive) s.shortShare.start else s.shortShare.start + (s.shortShare.endInclusive - s.shortShare.start) * rng.nextDouble()
        nextLanding = if (s.need == Need.MELEE) s.landing() else Landing.CENTRE
        nextPitch = if (s.need == Need.PITCH) s.lookDown() else Double.NaN
        nextFor = s.need
    }

    /** When the view's last move came to rest: the driver's own time, else the first still update after it. */
    private fun restOf(s: Situation) = if (s.restedAt != Long.MIN_VALUE) s.restedAt else stillAt

    /** Which rest [restOf] is: the driver's identity of it ([Situation.restId]), else that first still update. */
    private fun restIdOf(s: Situation) = if (s.restedAt != Long.MIN_VALUE) s.restId else stillAt

    /**
     * The first turn, its moment come, may start now. Not while a move of the view is in flight; one that still was when
     * the moment came, or one after the anchor that came to rest less than your shortest gap before it (a look around just
     * back), is followed one of your gaps between moves after its rest: the next move of yours after it. A rest before the
     * anchor that close (a latency shorter than that gap) draws the latency again until it falls after that gap: your
     * latency holds your gaps after earlier moves already, so a rest your shortest gap or more before the moment adds
     * nothing to it.
     */
    private fun firstRestOver(s: Situation): Boolean {
        if (s.viewMoving) {
            dueBusy = true
            return false
        }
        if (dueBusy) return gapAfterRest(s)
        val rest = restOf(s)
        if (turnAt >= rest + MIN_GAP_MS) return true
        if (rest > anchor || ! byLatency) {
            dueBusy = true
            return gapAfterRest(s)
        }
        turnAt = latencyAfter(s, rest)
        return reached(s, turnAt)
    }

    /**
     * No move of the view in flight, and one of your gaps between moves (drawn once per rest, keyed on which rest it is,
     * never on its time as read) passed since the last one rested: due on the tick nearest to it (your gaps ran from a
     * last moving tick to the tick before a first one, both on the tick grid), your shortest gap after it at the soonest
     * (the tick it rounds to included), the move starting its lead before.
     */
    private fun gapAfterRest(s: Situation): Boolean {
        if (s.viewMoving) return false
        val id = restIdOf(s)
        if (id != gapFor) {
            gapFor = id
            gapOver = gapAfter(restOf(s))
        }
        return reached(s, gapOver)
    }

    /** One of your gaps between moves after [rest], as [gapAfterRest] times it. */
    private fun gapAfter(rest: Long) = maxOf(TimingModel.dueAt(rest, TimingModel.draw(Action.MOVE_GAP, emptyList(), null, rng)), rest + MIN_GAP_MS)

    /**
     * The first turn's moment again, your shortest gap after the [rest] (before the anchor) at the soonest: the latency
     * from the anchor drawn again until it is ([REDRAW_TRIES] tries), else one of your gaps after the rest.
     */
    private fun latencyAfter(s: Situation, rest: Long): Long {
        repeat(REDRAW_TRIES) {
            val at = anchor + firstLatency(s)
            if (at >= rest + MIN_GAP_MS) return at
        }
        return gapAfter(rest)
    }

    /** [drawn], or a correction reaction drawn again until it is [floor] or more ([REDRAW_TRIES] tries, then one after it). */
    private fun reactionAfter(drawn: Long, floor: Long): Long {
        if (drawn >= floor) return drawn
        repeat(REDRAW_TRIES) {
            val r = TimingModel.draw(Action.CORRECTION_REACTION, emptyList(), null, rng)
            if (r >= floor) return r
        }
        return floor + TimingModel.draw(Action.CORRECTION_REACTION, emptyList(), null, rng)
    }

    private fun newTarget(id: Int, anchorAt: Long) {
        target = id
        anchor = anchorAt
        lastWithin = false
        firstDone = false
        turnAt = - 1L
        timed = false
        byLatency = true
        dueBusy = false
        gapFor = Long.MIN_VALUE
        ended = false
        stillSince = Long.MIN_VALUE / 2
        slack = 0.0
        slackOn = false
        offSince = - 1L
        acceptance = Double.NaN
        moves = 0
        landing = Landing.CENTRE
        landPitch = Double.NaN
        nextFor = null
    }

    private fun beginStill(s: Situation) {
        stillSince = s.now
        slack = drawSlack()
        slackOn = false
        offSince = - 1L
        ended = false
    }

    /** Starts the next move with its own draws ([drawNext]: the ones its lead was planned on, when it was). */
    private fun start(s: Situation): Decision {
        drawNext(s)
        nextFor = null
        acceptance = nextAccept
        share = nextShare
        moves++
        ended = false
        offSince = - 1L
        when (s.need) {
            Need.MELEE -> landing = nextLanding
            Need.PITCH -> landPitch = nextPitch
            else -> Unit
        }
        goal = goalOf(s)
        return Decision.START
    }

    /** Where this move comes to rest: a point of the box for melee, a pitch for a look-down; kept while the move retargets. */
    private fun drawLanding(s: Situation) {
        when (s.need) {
            Need.MELEE -> landing = s.landing()
            Need.PITCH -> landPitch = s.lookDown()
            else -> Unit
        }
    }

    private fun within(s: Situation, tolerance: Double) = s.onTarget || s.error <= tolerance

    /**
     * Melee aims at the move's point of the box (the aim point without one), a look-down at the move's pitch, a ranged
     * move short of the creature by its acceptance.
     */
    private fun goalOf(s: Situation) = goalOf(s, acceptance * share, landing, landPitch)

    /** [goalOf] for a move stopping [short] degrees short of a ranged aim, coming to rest at [landing] or at [pitch]. */
    private fun goalOf(s: Situation, short: Double, landing: Landing, pitch: Double) = when (s.need) {
        Need.RANGED -> MotorModel.towards(s.view.yaw, s.view.pitch, s.aim.yaw, s.aim.pitch, short)
        Need.MELEE -> s.land?.invoke(landing) ?: s.aim
        Need.PITCH -> if (pitch.isNaN()) s.aim else View(s.aim.yaw, pitch)
        else -> s.aim
    }

    private fun firstLatency(s: Situation): Long {
        if (s.swapped) return learned.swapLatency?.let { lognormal(it, FIRST_SWAP_CAP_MS) } ?: TimingModel.draw(Action.FIRST_TURN_SWAP, emptyList(), null, rng)
        val cap = s.maxWaitMs.coerceAtLeast(0.0)
        return learned.spawnLatency?.let { lognormal(it, cap) } ?: TimingModel.draw(Action.FIRST_TURN_SPAWN, emptyList(), null, rng, cap.takeIf { it.isFinite() })
    }

    private fun drawSlack() = learned.slack?.let { TimingModel.lognormal(it.p50, it.lnSigma, rng, SLACK_CAP) } ?: TimingModel.lognormal(SLACK_P50, SLACK_SIGMA, rng, SLACK_CAP)

    private fun lognormal(d: Dist, cap: Double) = TimingModel.lognormal(d.p50, d.lnSigma, rng, cap).toLong()

    /**
     * The looks the killer adds at your creatures where no plan needs a turn, learned per weapon:button class and age
     * ([LearnedLooks]): a first-moment look at a weapon plan's target that is inside its tolerance when the first turn's
     * moment finds no turn needed, and from [LearnedLooks.FROM_MS] on a rate per second alive at every creature not looked
     * at yet in this fight. A look is one move ([HumanAim.Owner.LOOK]) that stops short of the creature by a share of its
     * error and is never corrected; most of the time a return to the view it started from follows. The policy's turns
     * always win: its move, its wait, its start, a look-down or the bobber's chance still to come keep looks quiet
     * ([Scene.quiet]), and a look's goal keeps the target within the plan's tolerance ([Scene.keeps]), so a look never
     * holds back a click the plan would send.
     *
     * One instance for the killer, [reset] per fight and fed once a tick of it through [update]. Pure: no Minecraft calls,
     * deterministic for a given [Random].
     */
    class Looks(private val rng: Random = Random.Default) {
        /**
         * One of your creatures alive: its weapon:button class ([cls], null: unknown), its age, the [error] and the [aim]
         * view to the point at half its height (where your turns were measured), [overhead]: right above or below you. No
         * look goes to it before [readyAt]. [planTurns]: not the target, but a melee one its plan turns to once it is: no
         * look goes to it, since your turns toward its class are the ones that plan makes (looks at them as well turned
         * toward your rod melee creatures 1.4 times as often as you).
         */
        class Creature(val id: Int, val cls: String?, val ageMs: Long, val error: Double, val aim: View, val overhead: Boolean, val readyAt: Long = 0L, val planTurns: Boolean = false)

        /**
         * This tick of the fight: the [view], your [creatures] alive, the plan's [target] (-1: none) and what it [need]s.
         * - [free]: the target's plan needs no turn now ([ViewPolicy.free]); [firstFree]: its first turn's moment found none
         *   needed in this tick ([ViewPolicy.foundFree]).
         * - [keeps]: a view keeps the target within the plan's tolerance (always true when the plan needs no aim).
         * - [quiet]: no look now: the policy's move or wait, its start in this tick, a look-down, the bobber's chance.
         * - [moving]: a look's move still turns the view (its slow tail to rest is over for the learner: [HumanAim.turning]).
         * - [turnedAt]: the end of the view's last move of [BehaviorLearner.GAP_MOVE_DEG] or more, as the motion driver
         *   segments it ([HumanAim.bigTurnedAt], where your ways back were measured from; [Long.MIN_VALUE]: not told, the
         *   first update that finds the look over stands in for it); [lastTurnAt]: the end of its last moving tick of any
         *   ([HumanAim.turnedAt]): no way back starts sooner than your shortest gap after it.
         * - [lead]: how long after its start a move to a goal first shows ([HumanAim.leadMs]): your way back's gap ran to
         *   that first moving tick, so the return starts that much before its moment.
         */
        class Scene(
            val now: Long, val view: View, val creatures: List<Creature>, val target: Int, val need: Need,
            val free: Boolean, val firstFree: Boolean, val keeps: (View) -> Boolean, val quiet: Boolean, val moving: Boolean,
            val turnedAt: Long = Long.MIN_VALUE,
            val lead: (View) -> Long = { 0L },
            val lastTurnAt: Long = turnedAt,
        )

        /** What [Decision.START] starts: a look at a creature, or the return to the view a look started from. */
        enum class Kind { LOOK, RETURN }

        /** Your learned looks ([Table.of] your profile's). */
        var table = Table.DEFAULT
        /** Where [Decision.START] aims, and what for. */
        var goal: View? = null
            private set
        var kind: Kind? = null
            private set
        /** The creature the current or last look went to (-1: none). */
        var lookedAt = - 1
            private set

        /** Creatures looked at in this fight: each one gets one look at most. */
        private val looked = HashSet<Int>()
        private var lastAt = Long.MIN_VALUE
        /** A move of this layer was asked for and has not ended yet. */
        private var inFlight = false
        /**
         * The view the last look started from, while a return to it is to come; [returnAt] once the look ended (-1 before),
         * and how far short of that view the return ends (drawn with it: its lead is planned on that goal).
         */
        private var returnFrom: View? = null
        private var returnAt = - 1L
        private var returnShort = 0.0
        /** The target whose first turn's moment found no turn needed: its first-moment chance comes at the next free tick. */
        private var firstFor = - 1
        /** When the move of this layer last asked for began. */
        private var begunAt = Long.MIN_VALUE

        /** A new fight: every creature may be looked at again. */
        fun reset() {
            looked.clear()
            lookedAt = - 1
            lastAt = Long.MIN_VALUE
            hold()
        }

        /** No looks for now (outside the fight's phases, a screen, a takeover): a return or a first-moment look still to come is dropped. */
        fun hold() {
            inFlight = false
            returnFrom = null
            returnAt = - 1L
            firstFor = - 1
        }

        /** The move the last [Decision.START] asked for could not start (no player). */
        fun failed() {
            inFlight = false
            if (kind == Kind.LOOK) returnFrom = null
        }

        /** This tick's decision for [s]: [Decision.START] a move to [goal] ([kind]), [Decision.STOP] the look's move, or none. */
        fun update(s: Scene): Decision {
            goal = null
            kind = null
            val dt = if (lastAt == Long.MIN_VALUE) TimingModel.TICK_MS else (s.now - lastAt).coerceIn(0L, MAX_DT_MS)
            lastAt = s.now
            if (s.firstFree) firstFor = s.target
            if (inFlight && ! s.moving) {
                // the look ended where its strokes did (or another move took the view over): your way back came one of your
                // gaps after the view last stopped, never a still dwell of your whole latency (that one holds the moves you
                // made in between, 52 of your 76)
                inFlight = false
                // (a turn before the look began is another move's: then the first update that finds the look over). Your gaps
                // ran from your last moving tick to the tick before your first moving step, both on the tick grid: due on the
                // tick nearest to the drawn gap, not always the next one
                if (returnFrom != null) {
                    // from the look's own end (or a move of 3 degrees or more after it): a smaller one split off it is inside
                    // your gap, as the learner measured it, though no way back starts within your shortest gap after it
                    returnAt = if (s.turnedAt != Long.MIN_VALUE && s.turnedAt >= begunAt) backAfter(s.turnedAt, s.lastTurnAt) else s.now + backGap()
                    returnShort = TimingModel.lognormal(UNDERSHOOT_P50, UNDERSHOOT_SIGMA, rng, RETURN_SHORT_CAP)
                }
            }
            if (s.quiet) {
                hold()
                return if (s.moving) Decision.STOP else Decision.NONE
            }
            if (s.moving) return Decision.NONE

            val from = returnFrom
            if (from != null && returnAt >= 0) {
                val g = MotorModel.towards(s.view.yaw, s.view.pitch, from.yaw, from.pitch, returnShort)
                // your gap ran to the way back's first moving tick: it starts its lead before
                if (s.now >= returnAt || returnAt - s.now <= MAX_LEAD_MS && s.now + s.lead(g) >= returnAt) {
                    returnFrom = null
                    returnAt = - 1L
                    if (MotorModel.angle(s.view.yaw, s.view.pitch, g.yaw, g.pitch) >= MIN_MOVE_DEG && s.keeps(g)) return begin(g, Kind.RETURN, s.now)
                }
            }

            val aimed = s.need == Need.MELEE || s.need == Need.RANGED
            if (firstFor >= 0) {
                val id = firstFor
                firstFor = - 1
                val c = s.creatures.firstOrNull { it.id == id }
                if (id == s.target && aimed && c != null && lookable(s, c) && rng.nextDouble() < table.first(c.cls) && look(s, c)) return Decision.START
            }
            for (c in s.creatures) {
                if (! lookable(s, c) || c.ageMs < LearnedLooks.FROM_MS) continue
                if (c.id == s.target) {
                    if (! s.free) continue
                    // a weapon plan's target had its first-moment chance for its first seconds
                    if (aimed && c.ageMs < LearnedLooks.FIRST_MS) continue
                }
                val h = table.rate(c.cls, c.ageMs)
                if (h <= 0.0 || rng.nextDouble() >= 1.0 - exp(- h * dt / 1000.0)) continue
                if (look(s, c)) return Decision.START
            }
            return Decision.NONE
        }

        /**
         * Your gap from the view's last rest to your way back ([Table.backGapP50]), drawn below [RETURN_CAP_MS] and again
         * while it is shorter than your shortest gap between separate moves ([MIN_GAP_MS]: sooner, the two would measure as
         * one move); past [REDRAW_TRIES] tries one of your gaps between moves.
         */
        private fun backGap(): Long {
            repeat(REDRAW_TRIES) {
                val g = TimingModel.lognormal(table.backGapP50, table.backGapSigma, rng, RETURN_CAP_MS)
                if (g >= MIN_GAP_MS) return g.toLong()
            }
            return TimingModel.draw(Action.MOVE_GAP, emptyList(), null, rng)
        }

        /**
         * When the way back is due: your gap ([backGap]) after [rest] (the end of the last move of 3° or more) on the tick
         * nearest to it, drawn again while that falls within your shortest gap after [last] (a smaller move after it);
         * past [REDRAW_TRIES] tries one of your gaps between moves after [last].
         */
        private fun backAfter(rest: Long, last: Long): Long {
            repeat(REDRAW_TRIES) {
                val g = backGap()
                if (last == Long.MIN_VALUE || rest + g >= last + MIN_GAP_MS) return TimingModel.dueAt(rest, g)
            }
            return TimingModel.dueAt(last, TimingModel.draw(Action.MOVE_GAP, emptyList(), null, rng))
        }

        private fun lookable(s: Scene, c: Creature) = c.id !in looked && ! c.overhead && c.error >= LearnedLooks.OFF_DEG && s.now >= c.readyAt && ! c.planTurns

        /** A look at [c] short of it by a share of its error, when that keeps the target within the plan's tolerance. */
        private fun look(s: Scene, c: Creature): Boolean {
            val g = MotorModel.towards(s.view.yaw, s.view.pitch, c.aim.yaw, c.aim.pitch, stopShare() * c.error)
            if (! s.keeps(g)) return false
            looked += c.id
            lookedAt = c.id
            returnFrom = if (rng.nextDouble() < table.backRate) s.view else null
            returnAt = - 1L
            begin(g, Kind.LOOK, s.now)
            return true
        }

        private fun begin(g: View, k: Kind, now: Long): Decision {
            goal = g
            kind = k
            inFlight = true
            begunAt = now
            return Decision.START
        }

        /**
         * The share of the error a look leaves: through your stop shares' p10, p50 and p90, the quantiles between them (and
         * out to [STOP_MIN] and [STOP_MAX]) interpolated in ln, so no draw piles up at a bound.
         */
        private fun stopShare(): Double {
            val q = table.stop
            val knots = doubleArrayOf(min(STOP_MIN, q[0]), q[0], q[1], q[2], max(STOP_MAX, q[2]))
            val u = rng.nextDouble()
            val i = STOP_QUANTILES.indexOfLast { u >= it }.coerceIn(0, knots.size - 2)
            val f = (u - STOP_QUANTILES[i]) / (STOP_QUANTILES[i + 1] - STOP_QUANTILES[i])
            return exp(ln(knots[i]) + (ln(knots[i + 1]) - ln(knots[i])) * f)
        }

        /**
         * [LearnedLooks] read for the killer: the rate per second by class and age bin (a class's own counts from
         * [LearnedLooks.MIN_CLASS] creatures on, shrunk toward the all-class bin; the all-class table from
         * [LearnedLooks.MIN_ALL] creatures on, else the default's), the first-moment chance likewise, where a look stops
         * and the return. Anything broken in an edited profile (a null, a list of the wrong length, a negative count) is
         * read as not learned.
         */
        class Table private constructor(
            private val all: DoubleArray, private val classes: Map<String, DoubleArray>,
            private val firstAll: Double, private val firstClasses: Map<String, Double>,
            /** Where a look stops: p10, p50 and p90 of the share of the error it leaves. */
            val stop: DoubleArray,
            val backRate: Double,
            /**
             * Your way back's gap after the view's last rest ([LearnedLooks.backGap]): lognormal median and ln-sd. Your
             * latency from the look ([LearnedLooks.backLatency]) is no wait of its own: it holds the moves you made in between.
             */
            val backGapP50: Double, val backGapSigma: Double,
        ) {
            /** Looks per second at a creature of class [cls] (null: unknown) aged [ageMs]. */
            fun rate(cls: String?, ageMs: Long): Double {
                val b = LearnedLooks.AGE_BINS.indexOfLast { ageMs >= it }
                if (b < 0) return 0.0
                return (cls?.let { classes[it] } ?: all)[b]
            }

            /** The chance of a first-moment look at a target of class [cls] (null: unknown). */
            fun first(cls: String?) = cls?.let { firstClasses[it] } ?: firstAll

            companion object {
                /** The reference recordings' looks. */
                val DEFAULT by lazy { of(null) }
                private var source: LearnedLooks? = null
                private var cache: Table? = null

                /** The table of [looks] (null: the defaults), cached for the profile's own instance. */
                fun of(looks: LearnedLooks?): Table {
                    if (looks != null && looks === source) cache?.let { return it }
                    val d = LearnedLooks()
                    // Gson leaves a field an edited profile sets to null null
                    val learnedAll: LookStats? = looks?.all
                    val base = learnedAll?.takeIf { usableStats(it) && it.creatures >= LearnedLooks.MIN_ALL } ?: d.all
                    val all = DoubleArray(LearnedLooks.AGE_BINS.size) { b -> if (base.exposureS[b] > 0.0) base.events[b] / base.exposureS[b] else 0.0 }
                    val firstAll = firstShare(base) ?: firstShare(d.all) ?: 0.0
                    val classes = HashMap<String, DoubleArray>()
                    val firsts = HashMap<String, Double>()
                    val learned: Map<String, LookStats?>? = looks?.classes
                    learned?.forEach { (cls, st) ->
                        if (st == null || ! usableStats(st) || st.creatures < LearnedLooks.MIN_CLASS) return@forEach
                        classes[cls] = DoubleArray(all.size) { b -> (st.events[b] + LearnedLooks.PRIOR_S * all[b]) / (st.exposureS[b] + LearnedLooks.PRIOR_S) }
                        firsts[cls] = (st.freeAtFirstTurned + LearnedLooks.PRIOR_FIRST * firstAll) / (st.freeAtFirst + LearnedLooks.PRIOR_FIRST)
                    }
                    val learnedStop: Dist? = looks?.stopShare
                    val stop = learnedStop?.takeIf { usableDist(it) } ?: d.stopShare
                    val backRate = looks?.takeIf { it.backLooks >= BehaviorProfile.Policy.MIN_SAMPLES && it.backRate.isFinite() && it.backRate in 0.0 .. 1.0 }?.backRate ?: d.backRate
                    val learnedGap: Dist? = looks?.backGap
                    val gap = learnedGap?.takeIf { usableDist(it) } ?: d.backGap
                    val table = Table(all, classes, firstAll, firsts, doubleArrayOf(stop.p10, stop.p50, stop.p90), backRate, gap.p50, gap.lnSigma)
                    if (looks != null) {
                        source = looks
                        cache = table
                    }
                    return table
                }

                private fun firstShare(st: LookStats) = if (st.freeAtFirst > 0) st.freeAtFirstTurned.toDouble() / st.freeAtFirst else null

                private fun usableStats(st: LookStats): Boolean {
                    val n = LearnedLooks.AGE_BINS.size
                    val events: List<Int?>? = st.events
                    val exposure: List<Double?>? = st.exposureS
                    return events != null && events.size == n && events.all { it != null && it >= 0 } &&
                        exposure != null && exposure.size == n && exposure.all { it != null && it.isFinite() && it >= 0.0 } &&
                        st.creatures >= 0 && st.turned in 0 .. st.creatures && st.freeAtFirst >= 0 && st.freeAtFirstTurned in 0 .. st.freeAtFirst
                }

                private fun usableDist(d: Dist) = d.n >= BehaviorProfile.Policy.MIN_SAMPLES && d.p10 > 0.0 && d.p10 <= d.p50 && d.p50 <= d.p90 && d.lnSigma > 0.0
            }
        }

        companion object {
            /**
             * A look's goal keeps the plan's target within this share of its tolerance ([Scene.keeps]): room for the
             * creature to move while the look runs.
             */
            const val KEEP_SHARE = 0.8
            /** A tick's time at risk counts at most this long (a hitch is no longer chance). */
            const val MAX_DT_MS = 100L
            /** A look leaves this share of the creature's error at least and at most (unless yours go beyond). */
            const val STOP_MIN = 0.02
            const val STOP_MAX = 0.6
            /** The quantiles of the stop share's knots: its bounds, p10, p50 and p90. */
            private val STOP_QUANTILES = doubleArrayOf(0.0, 0.1, 0.5, 0.9, 1.0)
            /** The return comes at most this long after the look ended. */
            const val RETURN_CAP_MS = 5000.0
            /** The return stops short of the view the look started from by the turn back's undershoot, at most this. */
            const val RETURN_SHORT_CAP = 20.0
            /** A return shorter than this is none. */
            const val MIN_MOVE_DEG = 1.0
        }
    }

    /**
     * The turn back to your fishing view after a fight (DESIGN-HUMAN-AMENDMENTS §2.7): one move, started at [at] and ending
     * wherever its strokes end, [shortBy] degrees short of the view. It never starts again for the error it left, only after
     * a screen stopped it ([aborted]). It comes the way yours came after what your hand still did at the death:
     * - nothing went on past the moment it is timed from ([from]: the death, or the loadout menu's close): your [latency]
     *   from it, drawn again until it falls your shortest gap between moves after the view's last rest (a move that came
     *   to rest just before the death); your latency holds your gap already, so none is added to it;
     * - a move of the view still turned past it ([deathMove], the one in flight then), or the use key stayed held: from that
     *   end on (the later of the two), one of your waits after a move's rest ([afterMove]) or after a key-up
     *   ([afterKeyUp]), drawn once per end; your latency from the death holds those ends, so it is not waited out on top.
     *   A [Kind.LATE] one keeps its own latency instead.
     * A move started after it (a look around after the kill, timed to fit the turn back) never times it anew: only a rest
     * of one closer than your shortest gap before the moment draws the wait again from what it is timed from, until it falls
     * after that gap. Never while a move turns the view or the use key is held.
     */
    class TurnBack(
        val kind: Kind, at: Long, val shortBy: Double,
        private val afterMove: () -> Long = { TimingModel.typical(Action.TURN_BACK_AFTER_MOVE, emptyList()) },
        private val from: Long = at,
        private val latency: () -> Long = { at - from },
        private val afterKeyUp: () -> Long = afterMove,
        private val deathMove: Int = ANY_MOVE,
    ) {
        /** The move is due then. */
        var at = at
            private set
        enum class Kind {
            /** No turn back: the view is close enough, or still looks at the water. */
            NONE,
            /** Turn back after your latency. */
            NOW,
            /** A turn back you skipped, made up for a good while later because the view does not look at the water. */
            LATE,
        }

        /** The move was started. */
        var started = false
            private set
        /** The turn back is over: its move ended, it was never needed, or it timed out. */
        var done = kind == Kind.NONE
            private set
        private var deadline = Long.MAX_VALUE
        /** The view's last rest as seen before the start, and a move turned the view at the last update. */
        private var lastRest = Long.MIN_VALUE / 2
        private var moved = false
        /** The end of what went on past [from] the start is timed from ([Long.MIN_VALUE]: none), that it was a key-up, and which end it is. */
        private var endAt = Long.MIN_VALUE
        private var endKeyUp = false
        private var endId = Long.MIN_VALUE
        /** The rest the wait was last drawn again for. */
        private var flooredFor = Long.MIN_VALUE
        /** How far short of your fishing view the move stops ([short]), and the move whose rest it was drawn at. */
        private var shortDrawn = Double.NaN
        private var shortFor = Int.MIN_VALUE

        /**
         * How far short of your fishing view the move stops: [draw]n once for the view's rest after the driver's [move]
         * ([HumanAim.started]), again only after another move. The lead [update] plans with and the move that starts take
         * this very one, so the move's first moving tick comes when its lead said (a goal drawn again at the start, say
         * below the fallback where [shortBy] is beyond it, would start a move of another length than the one timed).
         */
        fun short(move: Int, draw: () -> Double): Double {
            if (shortDrawn.isNaN() || move != shortFor) {
                shortDrawn = draw()
                shortFor = move
            }
            return shortDrawn
        }

        /** A screen stopped the move: it starts again from where the view is once the screen is closed. */
        fun aborted() {
            if (! done) started = false
        }

        /**
         * The turn back comes after [t] (the loadout switch was over only then): a moment due at or before it is drawn again
         * from what it is timed from until it falls after it, never piled where that wait ends; past [REDRAW_TRIES] tries
         * one from [t].
         */
        fun notBefore(t: Long) {
            if (started || done || at > t) return
            at = if (endAt != Long.MIN_VALUE) after(endAt, t, ::endWait) else after(from, t, latency)
        }

        private fun endWait() = if (endKeyUp) afterKeyUp() else afterMove()

        /** [base] plus a [draw] that falls after [t], drawn again up to [REDRAW_TRIES] times; then one from [t]. */
        private fun after(base: Long, t: Long, draw: () -> Long): Long {
            repeat(REDRAW_TRIES) {
                val a = base + draw()
                if (a > t) return a
            }
            return t + draw()
        }

        /**
         * [Decision.START] when the move is due, otherwise [Decision.NONE]. [moving]: a move is in flight (before the start
         * one still turning the view, whoever's, its slow tail to rest over for the learner: [HumanAim.turning]; after it its
         * own) and [restedAt] when the view's last one came to rest, the end of its last moving tick ([HumanAim.turnEndAt];
         * [Long.MIN_VALUE]: the first update that finds the view still stands in for it), [move] which one that is (the
         * driver's count of moves, [HumanAim.started]; [ANY_MOVE]: not told, every rest is that of the move at the death);
         * [held]: the use key held through the kill is still down, [keyUpAt]: when it went up ([Long.MIN_VALUE]: never held).
         * [lead]: how long after its start the turn back first turns the view the way the learner sees a move start
         * ([HumanAim.leadMs]): your latencies and waits ran to that first moving tick, so it starts that much before.
         */
        fun update(now: Long, moving: Boolean, restedAt: Long = Long.MIN_VALUE, held: Boolean = false, keyUpAt: Long = Long.MIN_VALUE, move: Int = ANY_MOVE,
            lead: () -> Long = { 0L }): Decision {
            if (done) return Decision.NONE
            if (! started) {
                // a move still coasting from the death (or a look on its way back) comes to rest first, and the key goes up:
                // one sweep with the move would be no turn back of yours, and no use goes off on the way back
                if (moving || held) {
                    if (moving) moved = true
                    return Decision.NONE
                }
                if (restedAt != Long.MIN_VALUE) lastRest = restedAt else if (moved) lastRest = now
                moved = false
                // only the move in flight at the death went on past it; the same rest read a millisecond off at a later tick
                // is the same end, never drawn again
                val restEnd = if (deathMove == ANY_MOVE || move == deathMove) lastRest else Long.MIN_VALUE
                val keyUp = keyUpAt > from && keyUpAt >= restEnd
                val end = if (keyUp) keyUpAt else restEnd
                val id = if (keyUp) keyUpAt else if (move == ANY_MOVE) restEnd else move.toLong()
                if (kind == Kind.NOW && end > from) {
                    if (endAt == Long.MIN_VALUE || keyUp != endKeyUp || id != endId) {
                        endAt = end
                        endKeyUp = keyUp
                        endId = id
                        at = end + endWait()
                    }
                }
                // a rest closer than your shortest gap before the moment (a move that came to rest just before the death, a
                // look around after it just back) draws the wait again from what it is timed from until it falls after it
                val restId = if (move == ANY_MOVE) lastRest else move.toLong()
                if (restId != flooredFor && lastRest > endAt) {
                    flooredFor = restId
                    if (at < lastRest + MIN_GAP_MS) at = if (endAt != Long.MIN_VALUE) after(endAt, lastRest + MIN_GAP_MS - 1, ::endWait) else after(from, lastRest + MIN_GAP_MS - 1, latency)
                }
                if (now < at && (at - now > MAX_LEAD_MS || now + lead() < at)) return Decision.NONE
                started = true
                return Decision.START
            }
            if (! moving || now > deadline) done = true
            return Decision.NONE
        }

        /** The move started at [now] and is planned to take [plannedMs]: it is given up a second after that. */
        fun begun(now: Long, plannedMs: Double) {
            deadline = now + plannedMs.toLong() + BACK_SLACK_MS
        }

        companion object {
            /** No move was in flight at the death: no rest after it is an end of anything that went on past it. */
            const val NO_MOVE = - 1
            /** Which move was in flight at the death is not told: any rest after it counts as its end. */
            const val ANY_MOVE = Int.MIN_VALUE
        }
    }

    companion object {
        /** A melee move ends after this many strokes, wherever it is. */
        const val MAX_MELEE_STROKES = 3
        /** A turn back's latency or a correction's reaction is drawn again this often at most to fall after a wait. */
        const val REDRAW_TRIES = 32
        /**
         * Your shortest gap between separate moves (384 of them: 95 ms, the two still ticks that end a move): no first turn,
         * correction or turn back starts sooner after the move before came to rest.
         */
        const val MIN_GAP_MS = 95L
        /** A move's lead ([Situation.lead]) is planned only this close to its moment (a slow move's may be longer: it starts this early). */
        const val MAX_LEAD_MS = 1000L
        /** Slack beyond the tolerance before a correction: lognormal median, ln-sd and cap, in degrees. */
        const val SLACK_P50 = 15.0
        const val SLACK_SIGMA = 0.8
        const val SLACK_CAP = 90.0
        /** The first turn after a swap comes at most this late. */
        const val FIRST_SWAP_CAP_MS = 8000.0
        /** A fight ending this close to your fishing view needs no turn back. */
        const val BACK_MIN_DEG = 8.0
        /** Share of fights ending farther away that you turned back from within 3 s, of those the next fight did not cut short. */
        const val BACK_RATE = 0.78
        /** Your turn backs were the ones within this long of the death: their latency is drawn within it, never later. */
        const val BACK_WINDOW_MS = 3000.0
        /** Where a turn back ends short of your fishing view: lognormal median and ln-sd, in degrees. */
        const val UNDERSHOOT_P50 = 5.0
        const val UNDERSHOOT_SIGMA = 1.0
        /** A turn back is given up this long after its planned end. */
        const val BACK_SLACK_MS = 1000L
        /** A ranged move's acceptance without learned aim errors: lognormal median and ln-sd in degrees, below 0.9 of the plan's aim. */
        private const val ACCEPT_P50 = 15.0
        private const val ACCEPT_SIGMA = 1.0
        private const val ACCEPT_MAX_SHARE = 0.9
        private const val ACCEPT_JITTER = 0.1

        /**
         * Where a Learned right click or hold's move stops (DESIGN-HUMAN-AMENDMENTS §2.5): one of your [aimErr] samples at
         * or below their p75 and within [aimDeg] (your effective clicks' errors with that weapon), jittered by a lognormal
         * drawn below [aimDeg] (never piled at it, so the move ends where a click may go out); with fewer than
         * [TimingModel.MIN_SAMPLES] samples, or none within [aimDeg] (a plan cached from an earlier list), a lognormal
         * around 15° drawn below 0.9 of [aimDeg].
         */
        fun rangedAcceptance(aimErr: List<Float>, aimDeg: Double, rng: Random): Double {
            if (aimErr.size >= TimingModel.MIN_SAMPLES) {
                val sorted = aimErr.sorted()
                val p75 = sorted[((sorted.size - 1) * 0.75).toInt()]
                val low = sorted.filter { it <= p75 && it > 0f && it < aimDeg }
                if (low.isNotEmpty()) return TimingModel.lognormal(low[rng.nextInt(low.size)].toDouble(), ACCEPT_JITTER, rng, aimDeg)
            }
            return TimingModel.lognormal(ACCEPT_P50, ACCEPT_SIGMA, rng, ACCEPT_MAX_SHARE * aimDeg)
        }

        /**
         * Where beside the centre line of a creature's box your turns onto it came to rest, in shares of the box's
         * half-width as seen from your eye, on the quantiles of your 25 turns that came to rest on the box (the 10 onto
         * melee creatures alike): p10 0.12, p50 0.46, p90 0.74, either side alike; the quantiles between interpolated,
         * out to [SIDE_MIN] and [SIDE_MAX] (a few turns: no learned field, a fit to the reference recordings). None of
         * yours came within 0.05 of the line: the lowest decile is drawn between your closest (0.079) and p10, never on it.
         */
        fun landingSide(rng: Random): Double {
            val u = rng.nextDouble()
            val i = SIDE_QUANTILES.indexOfLast { u >= it }.coerceIn(0, SIDE_QUANTILES.size - 2)
            val f = (u - SIDE_QUANTILES[i]) / (SIDE_QUANTILES[i + 1] - SIDE_QUANTILES[i])
            val side = SIDE_KNOTS[i] + (SIDE_KNOTS[i + 1] - SIDE_KNOTS[i]) * f
            return if (rng.nextBoolean()) side else - side
        }

        /** The farthest from the centre line a melee move comes to rest, in shares of the half-width: inside the box. */
        const val SIDE_MAX = 0.9
        /** The closest to the centre line a melee move is planned to come to rest: your closest of 25 (0.079). */
        const val SIDE_MIN = 0.08
        private val SIDE_QUANTILES = doubleArrayOf(0.0, 0.1, 0.5, 0.9, 1.0)
        private val SIDE_KNOTS = doubleArrayOf(SIDE_MIN, 0.12, 0.46, 0.74, SIDE_MAX)

        /**
         * The turn back after a fight whose last death (or menu close) was at [now], with the view [distance] degrees from
         * your fishing view. Within [BACK_MIN_DEG] none; otherwise your share of turn backs after your latency (all three
         * of your waits drawn within the 3 s they were measured in: past it they would be skipped turn backs, on top of
         * the share you skipped), else none while the view still casts into water ([inFluid]) or a late one. [deathMove]:
         * the move in flight at that moment ([TurnBack.NO_MOVE] none), the only one whose rest it is timed from.
         */
        fun turnBack(now: Long, distance: Double, inFluid: Boolean, learned: Learned, rng: Random, deathMove: Int = TurnBack.ANY_MOVE): TurnBack {
            if (distance <= BACK_MIN_DEG) return TurnBack(TurnBack.Kind.NONE, now, 0.0)
            val short = backUndershoot(learned, rng)
            val afterMove = { TimingModel.draw(Action.TURN_BACK_AFTER_MOVE, emptyList(), null, rng) }
            val afterKeyUp = { TimingModel.draw(Action.TURN_BACK_AFTER_KEY_UP, emptyList(), null, rng) }
            if (rng.nextDouble() < learned.backRate) {
                val latency = { TimingModel.draw(Action.TURN_BACK, emptyList(), null, rng) }
                return TurnBack(TurnBack.Kind.NOW, now + latency(), short, afterMove, now, latency, afterKeyUp, deathMove)
            }
            if (inFluid) return TurnBack(TurnBack.Kind.NONE, now, 0.0)
            val late = { TimingModel.draw(Action.LATE_TURN_BACK, emptyList(), null, rng) }
            return TurnBack(TurnBack.Kind.LATE, now + late(), short, afterMove, now, late, afterKeyUp, deathMove)
        }

        /**
         * How far short of your fishing view a turn back ends: your errors at the cast after one ([Learned.backCastErr]),
         * else a lognormal around 5°, drawn below [cap] (never piled at it: where only a shorter one still casts into the
         * water, [AutoMobKiller.FightRules.backShort]).
         */
        fun backUndershoot(learned: Learned, rng: Random, cap: Double = Double.POSITIVE_INFINITY) =
            learned.backCastErr?.let { TimingModel.lognormal(it.p50, it.lnSigma, rng, cap) } ?: TimingModel.lognormal(UNDERSHOOT_P50, UNDERSHOOT_SIGMA, rng, cap)
    }
}

//#endif
