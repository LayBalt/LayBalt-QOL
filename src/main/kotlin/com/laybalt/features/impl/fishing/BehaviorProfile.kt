package com.laybalt.features.impl.fishing

//#if CHEAT

import com.laybalt.LayBaltQOL
import com.laybalt.config.PogObject
import com.laybalt.features.impl.fishing.KillRules.AttackMode
import com.laybalt.features.impl.fishing.KillRules.KillAction
import com.laybalt.features.impl.fishing.KillRules.KillRule
import com.laybalt.utils.ChatUtils
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ln
import kotlin.random.Random

/**
 * What Auto Mob Killer learned from your [FishingRecorder] recordings: reaction and click timings, how you turn
 * towards a creature (one progress curve per turn), where you aim, menu speeds, and per creature which weapon,
 * click and loadout you used. Rebuilt by [BehaviorLearner] from every recording file each time a recording stops.
 */
object BehaviorProfile {
    /** Version of the recording format written by [FishingRecorder]. */
    const val FORMAT = FishingRecorder.FORMAT
    /**
     * Version of [BehaviorLearner]: raised whenever it learns something new or learns it differently, so a profile from an
     * older one is relearned from the same recordings once (2: bobber kills, the recast through catches, no posture artefacts;
     * 3: item roles, weapon styles and envelopes, choices by situation, split-off children; 4: how your hand moves the view
     * ([Motor]), when you turn ([Policy]) and the streaks in your timings ([Timing]); 5: the turn back rate without the
     * fights the next one cut short; 6: the looks at your creatures that no plan needs ([Looks]), and when you look around
     * and back ([Glances]); 7: your melee misses next to your creatures, and your first click after a loadout menu closed
     * in a fight; 8: the first stroke planned to measure like yours, its share past the goal included; 9: your rest
     * nudges' sizes, the yaw's near the pole apart, the first stroke of your turns planned on the replay with the way back
     * from a look apart, and the look rates shrunk with a quarter minute; 10: the first stroke of your ways back; 11: that
     * of your turn backs apart from your ways back from a look; 12: those two estimated together by their spread; 13: the
     * gap from the view's last rest to your way back after a look ([Looks.backGap])).
     */
    const val LEARNER = 13

    /**
     * How your hand moves the view, learned from every movement of 6° or more in your recordings ([ViewMotion]). The
     * measured statistics come first, named after what they measure; the planning parameters the motor model draws its
     * strokes with follow. A planned stroke is no measured one (strokes overlap, 50 ms ticks and the segmentation change
     * what comes out), so each planning parameter is its default moved by the learned statistic against the reference
     * recordings' value. A statistic with too few moves keeps its default; defaults are the four reference recordings.
     */
    data class Motor(
        /** Movements of 6° or more the statistics come from (none cut by a recording's end); 0: all defaults. */
        var moves: Int = 0,
        /** Their sub-strokes of 2° or more (split at speed valleys below 0.7 of the smaller neighbouring peak). */
        var strokes: Int = 0,
        /** Whole-movement duration law, measured (LAD fit): moveLawA + moveLawB * log2(1 + A / 6) ms. */
        var moveLawA: Double = 34.0,
        var moveLawB: Double = 111.0,
        /** sd of ln(movement duration / that law), and the shares slower than twice it and faster than half of it. */
        var moveSigma: Double = 0.50,
        var slowShare: Double = 0.086,
        var fastShare: Double = 0.048,
        /** Sub-stroke duration law, measured (LAD fit): strokeLawA + strokeLawB * log2(1 + A / 6) ms. */
        var strokeLawA: Double = 88.0,
        var strokeLawB: Double = 58.0,
        /** median(sub-stroke duration / (88 + 58 * log2(1 + A / 6))): how much slower than the reference your strokes are. */
        var strokeK: Double = 1.0,
        /** sd and robust sd (IQR / 1.349) of ln(sub-stroke duration / its law). */
        var strokeSigma: Double = 0.40,
        var strokeSigmaRobust: Double = 0.34,
        /** Per [BINS] bin: movements, the share of them with 2 or more sub-strokes, and their peak speed p50 (deg/s). */
        var binMoves: MutableList<Int> = mutableListOf(0, 0, 0, 0, 0),
        var multiShare: MutableList<Double> = mutableListOf(0.20, 0.29, 0.37, 0.46, 0.55),
        var binPeakP50: MutableList<Double> = mutableListOf(79.0, 159.0, 313.0, 485.0, 774.0),
        /** The first sub-stroke's share of the way in 2-stroke movements of 20° or more: samples, p10, p50, p90. */
        var firstShareN: Int = 0,
        var firstShareP10: Double = 0.14,
        var firstShareP50: Double = 0.78,
        var firstShareP90: Double = 1.08,
        /**
         * The same of your turn backs (the moves [GlanceMeasure] finds turning back to where a turn toward a creature
         * started): samples, p50, p90 ...
         */
        var backFirstN: Int = 0,
        var backFirstP50: Double = 0.75,
        var backFirstP90: Double = 0.87,
        /** ...and of your other ways back, from a look (its returns, and turns back to where the fight started). */
        var returnFirstN: Int = 0,
        var returnFirstP50: Double = 0.51,
        var returnFirstP90: Double = 0.88,
        /** Multi-stroke movements of 20° or more: the first sub-stroke's cross-track error / amplitude p50, and the share with no still tick between the strokes. */
        var crossP50: Double = 0.07,
        var overlapShare: Double = 0.58,
        /** Movements of 20° or more past their end by more than 1° and 3°, and the size of those past by 1° as a share of the amplitude (p50). */
        var overshootShare: Double = 0.133,
        var overshoot3Share: Double = 0.090,
        var overshootSizeP50: Double = 0.094,
        /** Time to the peak speed as a share of the movement (movements of 20° or more). */
        var tpkP10: Double = 0.19,
        var tpkP50: Double = 0.49,
        var tpkP90: Double = 0.74,
        /** Movements of 30° or more: last moving tick / peak p50, and the share ending above half the peak. */
        var endP50: Double = 0.15,
        var endOverHalf: Double = 0.05,
        /** Time between yaw and pitch reaching half their way (both over 5°): samples, p10, p50, p90 ms; share with yaw first. */
        var lagN: Int = 0,
        var lagP10Ms: Double = 4.0,
        var lagP50Ms: Double = 33.0,
        var lagP90Ms: Double = 225.0,
        var yawFirst: Double = 0.53,
        /** Mouse-space straightness (amplitude / path) of movements of 20° or more with both axes over 5°: p10, p50. */
        var straightP10: Double = 0.77,
        var straightP50: Double = 0.98,
        /** Peak speed p99 and max over all movements, great-circle and mouse space (hypot of yaw and pitch), deg/s per 50 ms tick. */
        var gcP99: Double = 1208.0,
        var mouseP99: Double = 1394.0,
        var gcMax: Double = 1679.0,
        var mouseMax: Double = 1685.0,
        /** Gap between separate movements (the first 6° or more, the next 3° or more, within 3 s): p10, p50, p90 ms. */
        var gapP10Ms: Double = 100.0,
        var gapP50Ms: Double = 200.0,
        var gapP90Ms: Double = 531.0,
        /** Movements near the pole (start pitch 70° or more, yaw change 60° or more): count, median duration / law against the others'. */
        var poleMoves: Int = 0,
        var poleRatio: Double = 1.12,
        /** Look-downs (pitch down by 30° or more to 85° or more): count, median duration / sub-stroke law. */
        var lookDowns: Int = 0,
        var lookDownRatio: Double = 0.6,
        /** Ticks outside movements, the share of them that changed the view, and the share of those right after a changed one. */
        var restTicks: Int = 0,
        var restChanged: Double = 0.0246,
        var restFollow: Double = 0.41,
        /** Axes of those changes: both, yaw only (the rest pitch only); their size p50 in 0.15° grid steps (larger axis). */
        var restBoth: Double = 0.47,
        var restYaw: Double = 0.31,
        var restStepP50: Double = 2.0,
        /**
         * Axes of those changes and of the one-tick movements under 2° (a nudge too fast for a rest tick): your hand's (the
         * pitch, the yaw below 70°) and the yaw's at 70° or more, where a yaw step turns the view little.
         */
        var restHandAxes: Int = 0,
        var restPoleAxes: Int = 0,
        /** Ticks of a fight (one of your creatures alive, or the 3 s after) and the share of them with no view change at all. */
        var fightTicks: Int = 0,
        var stillShare: Double = 0.925,

        // planning parameters

        /** Planned stroke duration law, a + b * log2(1 + A / 6) ms, and its ln noise σ. */
        var lawA: Double = 70.0,
        var lawB: Double = 50.0,
        var sigma: Double = 0.40,
        /** Probability that a (not slow) move is planned as several strokes, per [BINS] bin. */
        var multiP: MutableList<Double> = mutableListOf(0.50, 0.45, 0.50, 0.50, 0.55),
        /** Mean share of the way of a planned first stroke (not a lead-in) of a turn toward a creature. */
        var firstMu: Double = 0.87,
        /** Mean share of the way of a planned first stroke of a turn back, never past its goal ([HumanAim.turnBackParamsOf]). */
        var backFirstMu: Double = 0.91,
        /** The same of a way back from a look ([HumanAim.returnParamsOf]). */
        var returnFirstMu: Double = 0.68,
        /** Probability of a slow move (3-5 slow strokes). */
        var slowP: Double = 0.12,
        /** p50 of the lagging axis's start delay, ms. */
        var axisLagMs: Double = 20.0,
        /** Mean time to peak of a stroke's velocity bell, as a share of it. */
        var tauMean: Double = 0.50,
        /** sd of a first stroke's cross-track error as a share of the amplitude. */
        var crossSd: Double = 0.07,
        /** Probability that the next stroke starts before the previous one ends. */
        var overlapP: Double = 0.58,
        /** Probability per still tick that a run of rest nudges starts. */
        var restStart: Double = 0.015,
        /** Shares of a rest nudge's 1, 2, ... grid steps on an axis your hand moves (the pitch, the yaw below 70°). */
        var restSteps: MutableList<Double> = MotorParams.REST_STEPS.toMutableList(),
        /** A yaw rest nudge at 70° or more: lognormal median and ln-sd in grid steps. */
        var restPoleP50: Double = 1.03,
        var restPoleSigma: Double = 2.24,
        /** Peak speed caps, great-circle and mouse space, deg/s. */
        var capGc: Double = 1208.0,
        var capMouse: Double = 1394.0,
        /** Duration factors near the pole and for a look-down. */
        var poleFactor: Double = 1.12,
        var lookDownFactor: Double = 0.6
    ) {
        /** The [BINS] bin of an amplitude of [deg] degrees, -1 below 6°. */
        fun bin(deg: Double) = BINS.indexOfLast { deg >= it }

        // in companions, not in BehaviorProfile: touching that object loads (and later saves) the profile file
        companion object {
            /** Lower edges of the amplitude bins of the per-bin lists, in degrees: 6-15, 15-30, 30-60, 60-100, 100 and more. */
            val BINS = listOf(6.0, 15.0, 30.0, 60.0, 100.0)
        }
    }

    /** A distribution in short: samples and their 10th, 50th and 90th percentiles (n 0: a default, not learned). */
    class Dist(var n: Int = 0, var p10: Double = 0.0, var p50: Double = 0.0, var p90: Double = 0.0) {
        /** σ of the log-normal through [p10] and [p90] around [p50]; 0 when they are not all positive. */
        val lnSigma get() = if (p10 > 0 && p50 > 0 && p90 > 0) ln(p90 / p10) / (2 * Z90) else 0.0

        override fun toString() = "n=$n p10=%.1f p50=%.1f p90=%.1f".format(p10, p50, p90)

        private companion object {
            /** z of the 90th percentile of a normal distribution. */
            const val Z90 = 1.2816
        }
    }

    /**
     * How you turn toward your creatures of one weapon:button class (your main attack clicks on a creature, `h` for a held
     * use key, `none` without clicks), or over all of them. Creatures counted are the ones 15° or more off your view 300 ms
     * after they appeared that lived 1 s or more; you turned toward one when a movement of 6° or more closed 40 % of its
     * error. The rest comes from fights with such a turn: its latency after your weapon swap or (without one) after the
     * spawn, the error it left, and your later corrective movements (error at their start and end, the gap after the
     * previous movement, and the start error beyond the class's tolerance).
     */
    class TurnStats(
        var creatures: Int = 0,
        var turned: Int = 0,
        var swapLatency: Dist = Dist(),
        var spawnLatency: Dist = Dist(),
        var stopErr: Dist = Dist(),
        var corrStart: Dist = Dist(),
        var corrEnd: Dist = Dist(),
        var corrGap: Dist = Dist(),
        var slack: Dist = Dist()
    ) {
        /** Share of [creatures] you turned toward (0 without any). */
        val rate get() = if (creatures > 0) turned.toDouble() / creatures else 0.0
    }

    /**
     * When you move the view in fights: [TurnStats] per weapon:button class and over all of them, the share of fight ticks
     * with a view change in fights with and without a turn, and the turn back after a fight (to the view at its start). A
     * distribution of [all] or of the turn back with fewer than [MIN_SAMPLES] samples keeps the reference recordings' value.
     */
    class Policy(
        var all: TurnStats = TurnStats(
            swapLatency = Dist(0, - 50.0, 301.0, 4939.0), spawnLatency = Dist(0, 826.0, 3524.0, 15300.0), stopErr = Dist(0, 3.0, 15.0, 57.0),
            corrStart = Dist(0, 16.0, 56.0, 119.0), corrEnd = Dist(0, 4.0, 16.0, 85.0), corrGap = Dist(0, 102.0, 352.0, 5598.0), slack = Dist(0, 5.4, 15.0, 41.8)
        ),
        var classes: LinkedHashMap<String, TurnStats> = linkedMapOf(),
        /** Fights with a turn toward a creature, and the share of ticks with a view change in fights with and without one. */
        var turnFights: Int = 0,
        var changedTurned: Double = 0.162,
        var changedOther: Double = 0.026,
        /** Fights looked at for a turn back, those ending more than 8° from their start view, and of those: turned back within 3 s, next fight within 3 s, neither. */
        var backFights: Int = 0,
        var backBeyond: Int = 0,
        var backTurned: Int = 0,
        var backInterrupted: Int = 0,
        var backNone: Int = 0,
        /** Of the fights ending more than 8° away, those that started at your fishing view (the view of your last cast), and of those: turned back within 3 s, next fight within 3 s. */
        var backHome: Int = 0,
        var backHomeTurned: Int = 0,
        var backHomeInterrupted: Int = 0,
        /**
         * Share of fights ending more than 8° away that you turned back within 3 s, of those the next fight did not cut short
         * and that started at your fishing view: one that started with a look still out or a turn not undone had no view of
         * its own to turn back to.
         */
        var backRate: Double = 0.78,
        /** Turn back: latency from the last death (ms), error it left (deg), error at your next cast (deg), its end to that cast (ms). */
        var backLatency: Dist = Dist(0, 99.0, 426.0, 1515.0),
        var backRest: Dist = Dist(0, 1.3, 7.5, 22.1),
        var backCastErr: Dist = Dist(0, 1.5, 5.1, 19.1),
        var backCastLag: Dist = Dist(0, 150.0, 348.0, 2153.0),
        /** Your looks at your creatures where no plan needs a turn. */
        var looks: Looks = Looks()
    ) {
        companion object {
            /** A distribution over all classes, or of the turn back, needs this many samples, else it keeps its default. */
            const val MIN_SAMPLES = 8
        }
    }

    /**
     * Your first turns toward your creatures of one weapon:button class (or all of them), as a rate over the creature's
     * age: per [Looks.AGE_BINS] bin the first turns that started in it and the creature-seconds at risk there (from
     * [Looks.FROM_MS] after the spawn to that turn or the death). Counted as [TurnStats] counts them, creatures right
     * overhead left out. [freeAtFirst]: creatures of a weapon plan inside its tolerance 300 ms after the spawn;
     * [freeAtFirstTurned]: of those, turned toward within [Looks.FIRST_MS] of the spawn.
     */
    class LookStats(
        var creatures: Int = 0,
        var turned: Int = 0,
        var events: MutableList<Int> = mutableListOf(0, 0, 0, 0, 0, 0),
        var exposureS: MutableList<Double> = mutableListOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.0),
        var freeAtFirst: Int = 0,
        var freeAtFirstTurned: Int = 0
    )

    /**
     * The looks the killer adds where no plan needs a turn ([ViewPolicy.Looks]): the rate per class and age, where a look
     * stops (end / start error of the creature it ends nearest), and the return to the view it started from after the
     * looks no plan needed. Creatures right overhead (within [OVERHEAD_SIDE] sideways, more than [OVERHEAD_DEG] off) are
     * counted apart: you rarely turn to them. Defaults (and [all] with fewer than [MIN_ALL] creatures) are the four
     * reference recordings.
     */
    class Looks(
        var all: LookStats = LookStats(356, 101, mutableListOf(23, 12, 26, 17, 14, 9), mutableListOf(537.9, 423.0, 486.7, 504.8, 380.7, 708.0), 53, 10),
        var classes: LinkedHashMap<String, LookStats> = linkedMapOf(),
        var stopShare: Dist = Dist(0, 0.06, 0.25, 0.53),
        /** First turns no plan needed, those you turned back from within 5 s, the share, and how long after their end. */
        var backLooks: Int = 0,
        var backTurned: Int = 0,
        var backRate: Double = 0.79,
        var backLatency: Dist = Dist(0, 151.0, 749.0, 3410.0),
        var overhead: Int = 0,
        var overheadTurned: Int = 0,
        /**
         * Of those, the view's last rest before the way back (the look's own, or one of your moves after it) to its start:
         * your way back comes this gap after the view last stopped (the reference recordings' 55: p10 149, p50 252, p90 551
         * ms; 76 over all your first turns toward a creature, free or not, p50 252), however long the look lasted
         * ([backLatency] holds the moves in between: 52 of those 76 had 1 to 7 of yours).
         */
        var backGap: Dist = Dist(0, 149.0, 252.0, 551.0),
    ) {
        companion object {
            /** Lower edges of the age bins, ms: 0-2, 2-4, 4-8, 8-16, 16-32, 32 s and more. */
            val AGE_BINS = listOf(0L, 2000L, 4000L, 8000L, 16000L, 32000L)
            /** A creature is at risk of a look from this age on (the learner counts a creature from its error at 300 ms). */
            const val FROM_MS = 300L
            /** A first-moment look starts within this long after the spawn. */
            const val FIRST_MS = 2000L
            /** A look goes only to a creature this many degrees or more off the view. */
            const val OFF_DEG = 15.0
            /** Right above or below you: closer than this sideways (m) and more than [OVERHEAD_DEG] off the view. */
            const val OVERHEAD_SIDE = 0.5
            const val OVERHEAD_DEG = 150.0
            /** A class's own rates count from this many creatures, the all-class table from [MIN_ALL]. */
            const val MIN_CLASS = 10
            const val MIN_ALL = 30
            /** Each bin's rate is shrunk toward the all-class bin's with this many creature-seconds. */
            const val PRIOR_S = 20.0
            /** The first-moment share is shrunk toward the all-class one with this many creatures. */
            const val PRIOR_FIRST = 5.0
        }
    }

    /**
     * Your looks around and back ([GlancePolicy]), learned by [GlanceMeasure] from the movements of 6° or more that are
     * none of aim, a turn back, walking or teleporting, a menu or world change, a tool, a weapon swept across your creatures
     * or a new fishing view: single straight strokes away from where the view rested, mostly back after a dwell. Lists are
     * indexed by [PHASES]; the per-look samples are kept in order (the newest [MAX_SAMPLES]). The measured statistics come
     * first (the four reference recordings' values in the comments), the planning parameters follow: every [rate] stays 0
     * until [MIN_LOOKS] looks in [MIN_MINUTES] minutes in place were learned, so nothing learned means no looks.
     */
    class Glances(
        /** Outward looks counted (in place, in a phase) over all phases (57). */
        var looks: Int = 0,
        /** Minutes in place per phase (34.0, 5.0, 33.3, 2.9), and the outward looks starting in them (18, 1, 27, 11)... */
        var minutes: MutableList<Double> = mutableListOf(0.0, 0.0, 0.0, 0.0),
        var counts: MutableList<Int> = mutableListOf(0, 0, 0, 0),
        /** ...of them starting within [HOME_DEG] of your fishing view (13, 1, 16, 1). */
        var homeCounts: MutableList<Int> = mutableListOf(0, 0, 0, 0),
        /** Minutes in place within a second after a death of your creatures, and the looks starting then. */
        var deathMinutes: MutableList<Double> = mutableListOf(0.0, 0.0, 0.0, 0.0),
        var deathCounts: MutableList<Int> = mutableListOf(0, 0, 0, 0),
        /** Per look, in order: amplitude, signed yaw change on the sphere, signed pitch change (+ down) and the start pitch (deg). */
        var amp: MutableList<Float> = mutableListOf(),
        var dYaw: MutableList<Float> = mutableListOf(),
        var dPitch: MutableList<Float> = mutableListOf(),
        var startPitch: MutableList<Float> = mutableListOf(),
        /** Looks from your fishing view that came back: the look's end to its return's start (ms), the error that return left (deg). */
        var dwellMs: MutableList<Int> = mutableListOf(),
        var returnErr: MutableList<Float> = mutableListOf(),
        /** A look's end to the next look from its end view, within 3 s (ms). */
        var chainGapMs: MutableList<Int> = mutableListOf(),
        /** Looks from your fishing view watched for 10 s (or back sooner), and back within 10 s (24, 22); the same away from it (12, 2). */
        var homeRisk: Int = 0,
        var homeReturned: Int = 0,
        var awayRisk: Int = 0,
        var awayReturned: Int = 0,
        /** Looks that chained from the one before, from where it ended (8). */
        var chained: Int = 0,
        /** Looks with a creature of yours dead for 3 s or less, those closing 40 % on where it died, and chance's share (43, 13, 6.2). */
        var deadSpotCandidates: Int = 0,
        var deadSpotHits: Int = 0,
        var deadSpotChance: Double = 0.0,
        /** Share of looks with 2 or more sub-strokes, the share all your moves of their amplitudes have, and of looks slower than twice your law. */
        var multiShare: Double = 0.12,
        var multiExpected: Double = 0.29,
        var slowShare: Double = 0.02,
        /** Mean and sd of ln(duration / your whole-move law) of looks; the median of duration / the reference recordings' law. */
        var lnMean: Double = - 0.12,
        var lnSd: Double = 0.39,
        var lawP50: Double = 0.89,
        /** Share of looks of 20° or more that went past their end by more than 1°. */
        var overShare: Double = 0.03,
        /** Minutes of view lines, and every movement of 6° or more of them by what it was ([GlanceMeasure.Kind] names). */
        var rotMinutes: Double = 0.0,
        var kinds: LinkedHashMap<String, Int> = linkedMapOf(),

        // planning parameters

        /** Outward looks per minute in place by phase; 0: none there. */
        var rate: MutableList<Double> = mutableListOf(0.0, 0.0, 0.0, 0.0),
        /** A look chains into a second one from where it ended (8 of the 57; 15 started within 3 s, most of them after a return). */
        var chainP: Double = 0.14,
        /** A look comes back, from your fishing view or from elsewhere. */
        var returnHomeP: Double = 0.90,
        var returnAwayP: Double = 0.17,
        /** The look rate within a second after a death of your creatures, against the rest. */
        var deathBoost: Double = 2.7,
        /** A look goes to where your creature just died. */
        var deadSpotP: Double = 0.12,
        /** A look's motion against an aim move's: multi-stroke and slow moves scaled down, its law sped up. */
        var multiScale: Double = 0.42,
        var slowScale: Double = 0.23,
        var durationFactor: Double = 0.88
    ) {
        /** Some phase has a learned rate: looks are on. */
        @Suppress("SENSELESS_COMPARISON")
        val learned get() = rate != null && rate.any { it != null && it.isFinite() && it > 0.0 }

        companion object {
            /** The phases of the per-phase lists, in [GlancePolicy.Phase] order. */
            val PHASES = listOf("waiting", "fightFree", "fightAim", "afterKill")
            /** A look starts at your fishing view within this many degrees of it. */
            const val HOME_DEG = 8.0
            /** The planning parameters need this many looks in this many minutes in place, the motion ones [MIN_MOTION] looks. */
            const val MIN_LOOKS = 12
            const val MIN_MINUTES = 15.0
            const val MIN_MOTION = 20
            /**
             * A phase's rate is shrunk toward the pooled one with this many minutes ([rateOf]); no rate is above [MAX_RATE]
             * a minute. A quarter minute keeps a phase seen for seconds from a wild rate; the wait after a kill, 3 s a fight
             * (2.9 minutes in place over the four reference recordings, 11 looks), keeps its own: a whole minute pulled it
             * from 3.8 to 3.0 a minute toward the pooled 0.76, and the replayed bot looked after its kills 0.6 times as often
             * as you.
             */
            const val PRIOR_MIN = 0.25
            const val MAX_RATE = 4.0

            /** The rate of a phase with [count] looks in [minutes] in place, your looks of all phases at [pooled] a minute. */
            fun rateOf(count: Int, minutes: Double, pooled: Double) = ((count + PRIOR_MIN * pooled) / (minutes + PRIOR_MIN)).coerceAtMost(MAX_RATE)
            /** A return rate needs this many looks watched long enough, the death boost this many looks after a death, the dead spot this many candidates. */
            const val MIN_RISK = 8
            const val MIN_DEATH_LOOKS = 5
            const val MIN_DEAD_CANDIDATES = 20
            /** Per-look samples kept, the newest. */
            const val MAX_SAMPLES = 400
        }
    }

    /**
     * Whether the slow draws of one timing come in streaks, from its samples in order: slow is above [threshold] (2.5 times
     * the median). [stay] = P(slow | previous slow); [enter] = P(slow | previous not slow) = π(1 - stay) / (1 - π) with π
     * [slowShare]; [lag1] the lag-1 correlation of ln. [n] 0: the reference recordings' values.
     */
    class Streak(
        var n: Int = 0,
        var threshold: Double = 0.0,
        var slowShare: Double = 0.0,
        var stay: Double = 0.0,
        var enter: Double = 0.0,
        /** Slow samples followed by another sample of the same recording (what [stay] is counted over). */
        var slowPairs: Int = 0,
        var lag1: Double = 0.0
    )

    /** Streaks of your timings by the name of their [Profile] samples list, and the rhythm of your rod melee clicks. */
    class Timing(
        var streaks: LinkedHashMap<String, Streak> = linkedMapOf(),
        /** Rod melee click gaps in runs of 2 or more, their lag-1 correlation of ln, and the probability of keeping the fast (< 300 ms) or slow mode fitted to it. */
        var meleeGaps: Int = 0,
        var meleeLag1: Double = 0.56,
        var meleeRho: Double = 0.75
    ) {
        /** The learned streak of [family] (a samples list name like `catchRecastMs`), else its reference value, null for one without. */
        fun streak(family: String): Streak? = streaks[family] ?: DEFAULTS[family]?.let { (pi, stay) -> Streak(slowShare = pi, stay = stay, enter = enterRate(pi, stay)) }

        companion object {
            /** π and stay of the reference recordings for the families whose slow draws come in streaks (bite reactions do not). */
            val DEFAULTS: Map<String, Pair<Double, Double>> = linkedMapOf(
                "catchRecastMs" to (0.065 to 0.42), "afterKillMs" to (0.189 to 0.35), "firstHitMs" to (0.094 to 0.36), "rodToCastMs" to (0.206 to 0.30)
            )

            /** P(slow | previous not slow) that keeps a slow share of [pi] with P(slow | previous slow) [stay]. */
            fun enterRate(pi: Double, stay: Double) = if (pi >= 1.0) 1.0 else (pi * (1 - stay) / (1 - pi)).coerceIn(0.0, 1.0)
        }
    }

    /**
     * How you use one item (a SkyBlock id) against your creatures, learned from your clicks with it while one of them was
     * alive. [WeaponPlanner.plan] turns it into the distance, height and aim it works at.
     */
    class WeaponStyle(
        /** Your usual way: [ClickStyle.HOLD] when at least 40 % of its right clicks are hold repeats. */
        var style: ClickStyle = ClickStyle.LEFT,
        /** Attack clicks with it while one of your creatures was alive (presses, hold repeats and left clicks). */
        var uses: Int = 0,
        /** Those followed within 400 ms by an hp drop or the death of one of your creatures. */
        var effective: Int = 0,
        /** The share of random moments (with a creature of yours alive) followed by such a drop, averaged over [uses]. */
        var chance: Float = 0f,
        /** Its [uses] by kind: left clicks, right presses and right hold repeats. */
        var left: Int = 0,
        var right: Int = 0,
        var held: Int = 0,
        /** Horizontal distance from your feet to the creature that dropped, at effective clicks. */
        var reach: MutableList<Float> = mutableListOf(),
        /** That creature's y minus yours, at effective clicks. */
        var dy: MutableList<Float> = mutableListOf(),
        /** Angle between your view and that creature (at 0.6 of its height) at effective clicks, in degrees. */
        var aimErr: MutableList<Float> = mutableListOf(),
        /** Your view pitch at effective clicks. */
        var pitch: MutableList<Float> = mutableListOf(),
        /** Effective clicks with your crosshair on that creature. */
        var onTarget: Int = 0,
        /** How long you kept the use key held with it (presses with hold repeats). */
        var holdMs: MutableList<Int> = mutableListOf(),
        /** From one press or hold of yours with it to the next. */
        var gapMs: MutableList<Int> = mutableListOf()
    )

    class Turn(
        /** Great-circle angle from your view when you started turning to the view you first hit the creature with, in degrees. */
        var angle: Float = 0f,
        var ms: Int = 0,
        /** Share of the turn done after each tick (above 1 = you overshot the creature). */
        var progress: FloatArray = FloatArray(0)
    )

    class Habit(
        var seen: Int = 0,
        var fought: Int = 0,
        var killed: Int = 0,
        /** Weapon id -> fights you used it in (once per fight, so held repeats do not swamp it). */
        var weapons: LinkedHashMap<String, Int> = linkedMapOf(),
        var left: Int = 0,
        var right: Int = 0,
        var rodClicks: Int = 0,
        var loadouts: LinkedHashMap<Int, Int> = linkedMapOf(),
        var killMs: MutableList<Int> = mutableListOf(),
        /** Caught, never clicked, but dead within a minute: killed somehow (an ability, someone else), not ignored. */
        var diedUnfought: Int = 0,
        /** Caught, never clicked, still around after a minute (or until you left): left alone. */
        var leftAlone: Int = 0,
        /** Killed by the bobber of your recast right after the catch, without a hit of yours before it (also in [killed]). */
        var bobberKills: Int = 0,
        /** Killed by your weapon swing at another of your creatures or at nothing: a sweep (also in [killed]). */
        var sweepKills: Int = 0,
        /**
         * [WeaponPlanner.situation] -> option ([WeaponPlanner.ROD] or a weapon id) -> fights you fought it so: the option
         * that killed it (or the one with the most effective presses), in the situation of its first effective click.
         */
        var choices: LinkedHashMap<String, LinkedHashMap<String, Int>> = linkedMapOf(),
        /** Option whose effective click came last before its death ([WeaponPlanner.BOBBER] for your recast) -> kills. */
        var killsBy: LinkedHashMap<String, Int> = linkedMapOf(),
        /** Its hp lost to one rod hit of yours, as a share of its max hp (0 for a hit that took nothing). */
        var rodHitShare: MutableList<Float> = mutableListOf(),
        /** The highest max hp its nametag showed (0 when no recording read one). */
        var maxHp: Double = 0.0,
        /**
         * The creature it splits off (`Bayou Sludge` for a `Bayou Sludgling`), empty for one you catch, and for one seen
         * next to your dying creatures too rarely to tell (see [BehaviorLearner]).
         */
        var parent: String = "",
        /** Its [SeaCreatures] group id, or its parent's for a child; empty when unknown. */
        var group: String = ""
    ) {
        /** The weapon of most of its fights; a tie goes to the one that killed it more often. */
        val topWeapon get() = weapons.maxWithOrNull(compareBy<Map.Entry<String, Int>>({ it.value }, { killsBy[it.key] ?: 0 }))?.key
        val topLoadout get() = loadouts.maxByOrNull { it.value }?.key ?: 0
        val prefersRight get() = right > left
        /** Your weapon clicks on it are mostly right clicks; rod hits (all of them left clicks) left out. */
        val weaponPrefersRight get() = right > left - rodClicks

        /** The rule your recordings suggest, null without enough data. */
        fun suggestion(): KillRule? {
            // only creatures you clearly left alone become Ignore; ones that also died without a click you could see stay unknown
            if (fought == 0) return if (leftAlone >= 3 && leftAlone > diedUnfought * 2) KillRule(KillAction.IGNORE) else null
            // most of them died to your recast's bobber: recast first, the rod finishes the ones that survive it
            if (bobberKills >= 3 && bobberKills * 2 >= killed) return KillRule(KillAction.ROD, attack = AttackMode.RIGHT)
            val clicks = left + right
            // a rod always hits (a right click would cast it), so a Rod rule's mode is about the recast: Learned follows yours
            if (rodClicks * 10 >= clicks * 6) return KillRule(KillAction.ROD)
            // rod hits mixed with empty-hand or unknown-item clicks: still a rod fight
            val weapon = topWeapon ?: return KillRule(KillAction.ROD)
            // the weapon's click, not the rod's: rod hits mixed in would make an area right click a melee swing
            return KillRule(KillAction.WEAPON, topLoadout, weapon, BehaviorLearner.prettyId(weapon), if (weaponPrefersRight) AttackMode.RIGHT else AttackMode.LEFT)
        }
    }

    class Profile(
        /** The [LEARNER] version this profile was learned with; an older one is relearned once (see [relearnIfOutdated]). */
        var learner: Int = 0,
        var sessions: Int = 0,
        var minutes: Double = 0.0,
        var ownCatches: Int = 0,
        var kills: Int = 0,
        var learnedAt: String = "",
        var biteReactionMs: MutableList<Int> = mutableListOf(),
        /** Your reel click to your recast click after a reel that caught nothing. */
        var castDelayMs: MutableList<Int> = mutableListOf(),
        /** Your reel click to your recast click after a catch, with nothing else done in between. */
        var catchRecastMs: MutableList<Int> = mutableListOf(),
        /** Your recasts after a catch ([catchRecastMs]), and how many of them came from keeping the use key held. */
        var recasts: Int = 0,
        var holdRecasts: Int = 0,
        /** Your recast click to the death of a creature its bobber killed. */
        var bobberDeathMs: MutableList<Int> = mutableListOf(),
        /** Your recast click to your first rod hit on a creature its bobber did not kill. */
        var bobberMissMs: MutableList<Int> = mutableListOf(),
        var spawnReactionMs: MutableList<Int> = mutableListOf(),
        var firstHitMs: MutableList<Int> = mutableListOf(),
        /** Every gap between two left clicks; [clickLeftRodMs] and [clickLeftWeaponMs] split them by what you held. */
        var clickLeftMs: MutableList<Int> = mutableListOf(),
        var clickLeftRodMs: MutableList<Int> = mutableListOf(),
        var clickLeftWeaponMs: MutableList<Int> = mutableListOf(),
        var clickRightMs: MutableList<Int> = mutableListOf(),
        var afterKillMs: MutableList<Int> = mutableListOf(),
        /** A creature's spawn to your swap from the rod to a weapon. */
        var swapReactionMs: MutableList<Int> = mutableListOf(),
        /** That weapon swap to your /loadout for the fight. */
        var weaponToLoadoutMs: MutableList<Int> = mutableListOf(),
        /** The death that ended a fight in another loadout to your /loadout back. */
        var deathToRestoreMs: MutableList<Int> = mutableListOf(),
        /** Closing that loadout menu to taking the rod. */
        var menuCloseToRodMs: MutableList<Int> = mutableListOf(),
        /** Closing a loadout menu you equipped in a fight to your first attack click, with no other item taken in between. */
        var menuCloseToClickMs: MutableList<Int> = mutableListOf(),
        /** Taking the rod to your first rod right click (the cast). */
        var rodToCastMs: MutableList<Int> = mutableListOf(),
        var menuOpenToClickMs: MutableList<Int> = mutableListOf(),
        var menuClickToCloseMs: MutableList<Int> = mutableListOf(),
        var aimHeights: MutableList<Float> = mutableListOf(),
        var rightClickErr: MutableList<Float> = mutableListOf(),
        var meleeDistances: MutableList<Float> = mutableListOf(),
        /**
         * Your left clicks with a rod you fish with while one of your creatures was within [MISS_NEAR] of your eye, and those
         * of them that hit air or a block; the same with your weapons ([missShare]).
         */
        var rodMeleeClicks: Int = 0,
        var rodMeleeMisses: Int = 0,
        var weaponMeleeClicks: Int = 0,
        var weaponMeleeMisses: Int = 0,
        var turns: MutableList<Turn> = mutableListOf(),
        /** Fights where your view was already on the creature when it appeared (no turn to learn). */
        var noTurnFights: Int = 0,
        /** Loadouts you equipped with none of your creatures around and then fished in (loadout -> times). */
        var fishingLoadouts: LinkedHashMap<Int, Int> = linkedMapOf(),
        var habits: LinkedHashMap<String, Habit> = linkedMapOf(),
        /** How you use each item you clicked with while a creature of yours was alive (SkyBlock id -> style). */
        var weaponStyles: LinkedHashMap<String, WeaponStyle> = linkedMapOf(),
        /** The items you fish with (they cast): their right clicks are reels and casts, never attacks. */
        var fishingRods: MutableSet<String> = linkedSetOf(),
        /** [WeaponPlanner.situation] -> option -> fights, over all creatures (see [Habit.choices]). */
        var choices: LinkedHashMap<String, LinkedHashMap<String, Int>> = linkedMapOf(),
        /** [SeaCreatures] group id -> situation -> option -> fights. */
        var groupChoices: LinkedHashMap<String, LinkedHashMap<String, LinkedHashMap<String, Int>>> = linkedMapOf(),
        /** Items seen in your hotbar (recordings with hotbar lines) -> recordings they were in. */
        var hotbarItems: LinkedHashMap<String, Int> = linkedMapOf(),
        /** How your hand moves the view. */
        var motor: Motor = Motor(),
        /** When you move the view in fights and turn back after them. */
        var policy: Policy = Policy(),
        /** Streaks and rhythm in your timings (their samples lists above keep the order you made them in). */
        var timing: Timing = Timing(),
        /** When you look around and back. */
        var glances: Glances = Glances()
    ) {
        val hasData get() = sessions > 0

        /**
         * You recast right through your catches: soon after the reel (median under 400 ms) and mostly by keeping the use
         * key held, instead of waiting for the creature first.
         */
        val recastThroughCatches: Boolean
            get() = catchRecastMs.size >= 8 && catchRecastMs.sorted()[catchRecastMs.size / 2] < 400 && recasts > 0 && holdRecasts * 2 >= recasts

        /** The loadout you fish in most, 0 if none was learned. */
        val topFishingLoadout get() = fishingLoadouts.maxByOrNull { it.value }?.key ?: 0

        /**
         * The share of your left clicks with a fishing rod ([rod]) or a weapon next to one of your creatures that hit air
         * or a block: learned from [MIN_MISS_CLICKS] clicks on, else the reference recordings'.
         */
        fun missShare(rod: Boolean): Double {
            val clicks = if (rod) rodMeleeClicks else weaponMeleeClicks
            val misses = if (rod) rodMeleeMisses else weaponMeleeMisses
            return if (clicks >= MIN_MISS_CLICKS && misses in 0 .. clicks) misses.toDouble() / clicks else if (rod) ROD_MISS else WEAPON_MISS
        }

        // in a companion, not in BehaviorProfile: touching that object loads (and later saves) the profile file
        companion object {
            /** A creature of yours this close to your eye (m) is one your left clicks are meant for, as the recorder's `nearDist`. */
            const val MISS_NEAR = 4.0
            /** A miss share needs this many clicks... */
            const val MIN_MISS_CLICKS = 30
            /** ...else the reference recordings': 130 of 677 rod clicks and 14 of 106 Dark Claymore swings hit air or a block. */
            const val ROD_MISS = 0.20
            const val WEAPON_MISS = 0.13
        }
    }

    private val store = PogObject("fishingProfile", Profile())

    @Volatile
    var current: Profile = store.get().also(::sanitize)
        private set

    /** Explicit nulls in an edited profile file become empty lists, so sampling never throws. */
    @Suppress("SENSELESS_COMPARISON")
    private fun sanitize(p: Profile) {
        if (p.biteReactionMs == null) p.biteReactionMs = mutableListOf()
        if (p.castDelayMs == null) p.castDelayMs = mutableListOf()
        if (p.catchRecastMs == null) p.catchRecastMs = mutableListOf()
        if (p.bobberDeathMs == null) p.bobberDeathMs = mutableListOf()
        if (p.bobberMissMs == null) p.bobberMissMs = mutableListOf()
        if (p.spawnReactionMs == null) p.spawnReactionMs = mutableListOf()
        if (p.firstHitMs == null) p.firstHitMs = mutableListOf()
        if (p.clickLeftMs == null) p.clickLeftMs = mutableListOf()
        if (p.clickLeftRodMs == null) p.clickLeftRodMs = mutableListOf()
        if (p.clickLeftWeaponMs == null) p.clickLeftWeaponMs = mutableListOf()
        if (p.clickRightMs == null) p.clickRightMs = mutableListOf()
        if (p.afterKillMs == null) p.afterKillMs = mutableListOf()
        if (p.swapReactionMs == null) p.swapReactionMs = mutableListOf()
        if (p.weaponToLoadoutMs == null) p.weaponToLoadoutMs = mutableListOf()
        if (p.deathToRestoreMs == null) p.deathToRestoreMs = mutableListOf()
        if (p.menuCloseToRodMs == null) p.menuCloseToRodMs = mutableListOf()
        if (p.menuCloseToClickMs == null) p.menuCloseToClickMs = mutableListOf()
        if (p.rodToCastMs == null) p.rodToCastMs = mutableListOf()
        if (p.menuOpenToClickMs == null) p.menuOpenToClickMs = mutableListOf()
        if (p.menuClickToCloseMs == null) p.menuClickToCloseMs = mutableListOf()
        if (p.aimHeights == null) p.aimHeights = mutableListOf()
        if (p.rightClickErr == null) p.rightClickErr = mutableListOf()
        if (p.meleeDistances == null) p.meleeDistances = mutableListOf()
        if (p.turns == null) p.turns = mutableListOf()
        p.turns.removeIf { it == null || it.progress == null }
        if (p.fishingLoadouts == null) p.fishingLoadouts = linkedMapOf()
        p.fishingLoadouts.entries.removeIf { it.key == null || it.value == null }
        if (p.habits == null) p.habits = linkedMapOf()
        p.habits.entries.removeIf { it.key == null || it.value == null }
        p.habits.values.forEach { h ->
            if (h.weapons == null) h.weapons = linkedMapOf()
            if (h.loadouts == null) h.loadouts = linkedMapOf()
            if (h.killMs == null) h.killMs = mutableListOf()
            if (h.choices == null) h.choices = linkedMapOf()
            sanitizeCounts(h.choices)
            if (h.killsBy == null) h.killsBy = linkedMapOf()
            h.killsBy.entries.removeIf { it.key == null || it.value == null }
            if (h.rodHitShare == null) h.rodHitShare = mutableListOf()
            h.rodHitShare.removeIf { it == null }
            if (h.parent == null) h.parent = ""
            if (h.group == null) h.group = ""
        }
        if (p.weaponStyles == null) p.weaponStyles = linkedMapOf()
        p.weaponStyles.entries.removeIf { it.key == null || it.value == null }
        p.weaponStyles.values.forEach { s ->
            if (s.style == null) s.style = ClickStyle.LEFT
            if (s.reach == null) s.reach = mutableListOf()
            if (s.dy == null) s.dy = mutableListOf()
            if (s.aimErr == null) s.aimErr = mutableListOf()
            if (s.pitch == null) s.pitch = mutableListOf()
            if (s.holdMs == null) s.holdMs = mutableListOf()
            if (s.gapMs == null) s.gapMs = mutableListOf()
            listOf(s.reach, s.dy, s.aimErr, s.pitch).forEach { l -> l.removeIf { it == null } }
            listOf(s.holdMs, s.gapMs).forEach { l -> l.removeIf { it == null } }
        }
        if (p.fishingRods == null) p.fishingRods = linkedSetOf()
        p.fishingRods.removeIf { it == null }
        if (p.choices == null) p.choices = linkedMapOf()
        sanitizeCounts(p.choices)
        if (p.groupChoices == null) p.groupChoices = linkedMapOf()
        p.groupChoices.entries.removeIf { it.key == null || it.value == null }
        p.groupChoices.values.forEach(::sanitizeCounts)
        if (p.hotbarItems == null) p.hotbarItems = linkedMapOf()
        p.hotbarItems.entries.removeIf { it.key == null || it.value == null }
        sanitizeMotor(p)
        if (p.policy == null) p.policy = Policy()
        p.policy.let { pol ->
            if (pol.all == null) pol.all = Policy().all
            if (pol.classes == null) pol.classes = linkedMapOf()
            pol.classes.entries.removeIf { it.key == null || it.value == null }
            (pol.classes.values + pol.all).forEach(::sanitizeTurns)
            if (pol.backLatency == null) pol.backLatency = Policy().backLatency
            if (pol.backRest == null) pol.backRest = Policy().backRest
            if (pol.backCastErr == null) pol.backCastErr = Policy().backCastErr
            if (pol.backCastLag == null) pol.backCastLag = Policy().backCastLag
            if (pol.looks == null) pol.looks = Looks()
            pol.looks.let { l ->
                val d = Looks()
                if (l.all == null) l.all = d.all
                if (l.classes == null) l.classes = linkedMapOf()
                l.classes.entries.removeIf { it.key == null || it.value == null }
                if (l.stopShare == null) l.stopShare = d.stopShare
                if (l.backLatency == null) l.backLatency = d.backLatency
            }
        }
        if (p.timing == null) p.timing = Timing()
        if (p.timing.streaks == null) p.timing.streaks = linkedMapOf()
        p.timing.streaks.entries.removeIf { it.key == null || it.value == null }
        sanitizeGlances(p)
    }

    /** Per-phase lists of the wrong length are as bad as none; rates and probabilities stay within what they can be. */
    @Suppress("SENSELESS_COMPARISON")
    private fun sanitizeGlances(p: Profile) {
        if (p.glances == null) p.glances = Glances()
        val g = p.glances
        val d = Glances()
        val n = Glances.PHASES.size
        fun <T> phased(l: MutableList<T>?, default: MutableList<T>) = l?.takeIf { it.size == n && it.all { v -> v != null } } ?: default
        g.minutes = phased(g.minutes, d.minutes)
        g.counts = phased(g.counts, d.counts)
        g.homeCounts = phased(g.homeCounts, d.homeCounts)
        g.deathMinutes = phased(g.deathMinutes, d.deathMinutes)
        g.deathCounts = phased(g.deathCounts, d.deathCounts)
        g.rate = phased(g.rate, d.rate)
        for (i in g.rate.indices) if (! g.rate[i].isFinite() || g.rate[i] < 0.0) g.rate[i] = 0.0
        if (g.amp == null) g.amp = mutableListOf()
        if (g.dYaw == null) g.dYaw = mutableListOf()
        if (g.dPitch == null) g.dPitch = mutableListOf()
        if (g.startPitch == null) g.startPitch = mutableListOf()
        if (g.dwellMs == null) g.dwellMs = mutableListOf()
        if (g.returnErr == null) g.returnErr = mutableListOf()
        if (g.chainGapMs == null) g.chainGapMs = mutableListOf()
        listOf(g.amp, g.dYaw, g.dPitch, g.startPitch, g.returnErr).forEach { l -> l.removeIf { it == null } }
        listOf(g.dwellMs, g.chainGapMs).forEach { l -> l.removeIf { it == null } }
        if (g.kinds == null) g.kinds = linkedMapOf()
        g.kinds.entries.removeIf { it.key == null || it.value == null }
        fun prob(v: Double, default: Double) = if (v.isFinite()) v.coerceIn(0.0, 1.0) else default
        g.chainP = prob(g.chainP, d.chainP)
        g.returnHomeP = prob(g.returnHomeP, d.returnHomeP)
        g.returnAwayP = prob(g.returnAwayP, d.returnAwayP)
        g.deadSpotP = prob(g.deadSpotP, d.deadSpotP)
        if (! g.deathBoost.isFinite() || g.deathBoost <= 0.0) g.deathBoost = d.deathBoost
        g.durationFactor = if (g.durationFactor.isFinite()) g.durationFactor.coerceIn(0.5, 1.5) else d.durationFactor
        g.multiScale = if (g.multiScale.isFinite()) g.multiScale.coerceIn(0.05, 1.0) else d.multiScale
        g.slowScale = if (g.slowScale.isFinite()) g.slowScale.coerceIn(0.05, 1.0) else d.slowScale
    }

    /** Lists of the wrong length (an edited file) are as bad as none: the motor model indexes them by bin. */
    @Suppress("SENSELESS_COMPARISON")
    private fun sanitizeMotor(p: Profile) {
        if (p.motor == null) p.motor = Motor()
        val m = p.motor
        val d = Motor()
        if (m.binMoves == null || m.binMoves.size != Motor.BINS.size || m.binMoves.any { it == null }) m.binMoves = d.binMoves
        if (m.multiShare == null || m.multiShare.size != Motor.BINS.size || m.multiShare.any { it == null }) m.multiShare = d.multiShare
        if (m.binPeakP50 == null || m.binPeakP50.size != Motor.BINS.size || m.binPeakP50.any { it == null }) m.binPeakP50 = d.binPeakP50
        if (m.multiP == null || m.multiP.size != Motor.BINS.size || m.multiP.any { it == null }) m.multiP = d.multiP
    }

    @Suppress("SENSELESS_COMPARISON")
    private fun sanitizeTurns(t: TurnStats) {
        if (t.swapLatency == null) t.swapLatency = Dist()
        if (t.spawnLatency == null) t.spawnLatency = Dist()
        if (t.stopErr == null) t.stopErr = Dist()
        if (t.corrStart == null) t.corrStart = Dist()
        if (t.corrEnd == null) t.corrEnd = Dist()
        if (t.corrGap == null) t.corrGap = Dist()
        if (t.slack == null) t.slack = Dist()
    }

    @Suppress("SENSELESS_COMPARISON")
    private fun sanitizeCounts(m: LinkedHashMap<String, LinkedHashMap<String, Int>>) {
        m.entries.removeIf { it.key == null || it.value == null }
        m.values.forEach { counts -> counts.entries.removeIf { it.key == null || it.value == null } }
    }

    @Volatile
    var learning = false
        private set

    private var relearnChecked = false

    /**
     * Once per game, when the client is ready: a profile learned by an older [LEARNER] is relearned from the recordings,
     * so what the new learner knows (bobber kills, your recast through catches) is there without pressing Relearn. Not
     * when the largest recording would not fit in the heap still free ([RelearnHeap]): the game is joining a world, and
     * running out of memory would only keep the old profile at every join. Relearn From Recordings is asked for instead.
     */
    fun relearnIfOutdated() {
        if (relearnChecked) return
        relearnChecked = true
        if (! current.hasData || current.learner >= LEARNER) return
        val largest = FishingRecorder.recordingsDir.listFiles { f -> f.extension == "jsonl" }?.maxOfOrNull { it.length() } ?: 0L
        val rt = Runtime.getRuntime()
        if (! RelearnHeap.fits(largest, rt.maxMemory() - (rt.totalMemory() - rt.freeMemory())))
            return ChatUtils.modMessage("§eAuto Mob Killer: your fishing profile is from an older version and your recordings are too long to relearn it while the game loads. Press Relearn From Recordings (Fishing Recorder) to update it.")
        rebuildAsync(report = false, automatic = true)
    }

    /**
     * Whether learning a recording fits in the heap: it holds about 4 times the recording's size at its peak (30 MB for a
     * 7.3 MB, 41-minute one). Pure, so it can be unit tested.
     */
    internal object RelearnHeap {
        /** The heap a recording's learning may take, in times its size: its measured peak with a margin. */
        const val FACTOR = 8L

        /** The largest recording ([largestBytes]) fits [FACTOR] times over in the heap still free ([freeBytes]). */
        fun fits(largestBytes: Long, freeBytes: Long) = largestBytes <= freeBytes / FACTOR
    }

    /** [automatic]: a relearn nobody asked for keeps the old profile when no recording is left to learn from. */
    fun rebuildAsync(report: Boolean, automatic: Boolean = false) {
        if (learning) return
        learning = true
        LayBaltQOL.scope.launch {
            val result = runCatching {
                // someone else's recording dropped into the folder is not learned as yours
                BehaviorLearner.learn(FishingRecorder.recordingsDir.listFiles { f -> f.extension == "jsonl" }?.sortedBy { it.name }.orEmpty(), skipRecorderBuild = true)
            }
            learning = false
            LayBaltQOL.mc.execute {
                result.onSuccess { profile ->
                    if (automatic && ! profile.hasData && current.hasData) return@onSuccess
                    current = profile
                    if (automatic) ChatUtils.modMessage("§7Auto Mob Killer relearned your fishing profile from your recordings for this version: §f${profile.kills}§7 kills (§f${profile.habits.values.sumOf { it.bobberKills }}§7 by your recast's bobber).")
                    store.set(profile)
                    if (report) ChatUtils.modMessage(
                        if (! profile.hasData) "§7Auto Mob Killer: no recordings to learn from yet."
                        else "§7Auto Mob Killer learned from §f${profile.sessions}§7 recording(s), §f%.0f§7 min: §f${profile.kills}§7 kills (§f${profile.habits.values.sumOf { it.bobberKills }}§7 by your recast's bobber), §f${profile.motor.moves}§7 view movements, §f${profile.clickLeftMs.size + profile.clickRightMs.size}§7 click gaps.".format(profile.minutes)
                    )
                }.onFailure {
                    if (report) ChatUtils.modMessage("§cAuto Mob Killer: learning failed (${it.message}).")
                }
            }
        }
    }

    /** `ROD_OF_THE_SEA` -> `Rod Of The Sea`. */
    fun prettyId(id: String) = BehaviorLearner.prettyId(id)
}

/**
 * Human-looking random values: one of your recorded samples (slightly varied) when there are enough, else a default range.
 * Waits Auto Fishing and Auto Mob Killer time draw from [TimingModel] itself; [ms] is kept for the rare ones (the loadout menu).
 */
object Humanize {
    private const val MIN_SAMPLES = 8

    /**
     * With at least [minSamples] samples one of them (from 400 ms on with a little lognormal jitter); with fewer but at
     * least 3 (a rare trip like a loadout switch) anything within their range made 10 % wider; else [min]..[max]. A
     * recorded value outside [min] / 2..[max] * 3 is drawn again, never clamped, so none piles up at a bound
     * ([TimingModel.ranged]).
     */
    fun ms(samples: List<Int>, min: Int, max: Int, minSamples: Int = MIN_SAMPLES): Long = TimingModel.ranged(samples, min, max, Random.Default, minSamples)

    fun value(samples: List<Float>, min: Float, max: Float): Float {
        if (samples.size < MIN_SAMPLES) return Random.nextDouble(min.toDouble(), max.toDouble()).toFloat()
        return samples[Random.nextInt(samples.size)] * Random.nextDouble(0.95, 1.05).toFloat()
    }

    /** A recorded turn of about [angle] degrees (one of the 5 closest, so the same one is not replayed every time). */
    fun turn(turns: List<BehaviorProfile.Turn>, angle: Float): BehaviorProfile.Turn? {
        if (turns.size < MIN_SAMPLES) return null
        return turns.sortedBy { abs(it.angle - angle) }.take(5).randomOrNull()
    }
}

//#endif
