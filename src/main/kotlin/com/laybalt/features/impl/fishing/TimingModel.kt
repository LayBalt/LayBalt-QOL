package com.laybalt.features.impl.fishing

//#if CHEAT

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Waits drawn the way you wait (DESIGN-HUMAN §3): one of your recorded samples, a short one as it is (it already sits
 * on the tick grid) and a longer one with a little lognormal jitter; with fewer than [MIN_SAMPLES] samples, a default
 * fitted to the recordings. Nothing is clamped to fixed bounds, so no value piles up at a bound: a draw outside the
 * sanity bounds (0.25 × p1 .. 3 × p99, the action's floor and cap) is drawn again.
 *
 * Slow waits come in streaks ([Kind.STREAK]) and melee clicks keep their rhythm ([Kind.RHYTHM]) through a [StreakState]
 * the caller keeps per action family. Pure and deterministic for a given [Random].
 */
object TimingModel {
    /** With fewer samples than this an action's default is used. */
    const val MIN_SAMPLES = 8
    /** One client tick. */
    const val TICK_MS = 50L
    /** Samples from this on are jittered; shorter ones are used as they are. */
    private const val JITTER_FROM_MS = 400
    private const val JITTER_SIGMA = 0.1
    /** A wait above this many times the median is slow, the threshold the streaks are measured with. */
    private const val SLOW_FACTOR = 2.5
    /** A streak side or rhythm mode with fewer samples than this gives plain resampling. */
    private const val MIN_SIDE = 3
    /** Melee gaps shorter than this are the fast rhythm. */
    const val RHYTHM_SPLIT_MS = 300
    private const val TRIES = 8
    private const val DEFAULT_QUANTILES = 400
    /** A stay rate is learned from at least this many slow waits followed by another wait. */
    private const val MIN_STAY_PAIRS = 8
    /** A rhythm is learned from at least this many ordered gaps. */
    private const val MIN_RHYTHM_SAMPLES = 30
    private const val RHO_MAX = 0.95
    private const val FIT_DRAWS = 8000
    private const val FIT_SEED = 1L

    enum class Kind {
        /** Independent draws. */
        PLAIN,
        /** A two-state slow/normal chain: slow waits come in streaks. */
        STREAK,
        /** Melee click gaps: the fast or slow rhythm is kept for a while. */
        RHYTHM,
    }

    /** One part of a default distribution, [weight] its share. */
    sealed class Part(val weight: Double) {
        /** The value at quantile [u] (0..1), below [capMs]. */
        abstract fun quantile(u: Double, capMs: Double): Double

        class Fixed(val ms: Double, weight: Double = 1.0) : Part(weight) {
            override fun quantile(u: Double, capMs: Double) = min(ms, capMs)
        }

        class LogNormal(val p50: Double, val sigma: Double, weight: Double = 1.0) : Part(weight) {
            override fun quantile(u: Double, capMs: Double): Double {
                val top = if (capMs.isInfinite()) 1.0 else phi(ln(capMs / p50) / sigma)
                return p50 * exp(sigma * phiInv(u * top))
            }
        }

        class Uniform(val lo: Double, val hi: Double, weight: Double = 1.0) : Part(weight) {
            override fun quantile(u: Double, capMs: Double) = min(lo + (hi - lo) * u, capMs)
        }
    }

    /**
     * What is waited for, with the default used until [MIN_SAMPLES] samples are learned (critic §1C and the amendments).
     * [stay] is the default chance a slow wait follows a slow one ([Kind.STREAK], measured where [slowShare] of the
     * waits were slow) or that the rhythm is kept ([Kind.RHYTHM]); no draw is below [floorMs] or above [capMs].
     */
    enum class Action(
        val kind: Kind, private val parts: List<Part>, val stay: Double = 0.0, val slowShare: Double = 0.0,
        val floorMs: Double = 30.0, val capMs: Double = Double.POSITIVE_INFINITY,
    ) {
        /** Bite to reel: independent, anticipations under 100 ms included. */
        BITE(Kind.PLAIN, listOf(Part.LogNormal(201.0, 0.55))),
        /** Cast without a catch, to the recast. */
        CAST(Kind.PLAIN, listOf(Part.LogNormal(200.0, 0.6))),
        /** Catch to recast: mostly the held key's 4 ticks, presses, and streaks of slow recasts. */
        CATCH_RECAST(Kind.STREAK, listOf(Part.Fixed(200.0, 0.80), Part.LogNormal(151.0, 0.45, 0.14), Part.LogNormal(900.0, 0.45, 0.06)), stay = 0.42, slowShare = 0.065),
        /** Rod back in hand to the cast. */
        ROD_TO_CAST(Kind.STREAK, listOf(Part.LogNormal(201.0, 1.48)), stay = 0.30, slowShare = 0.206, capMs = 4000.0),
        /** View settled after a turn back to the cast. */
        SETTLE_TO_CAST(Kind.PLAIN, listOf(Part.LogNormal(350.0, 1.0)), capMs = 4000.0),
        /** Spawn to the start of a rod or learned fight. */
        SPAWN_REACTION(Kind.PLAIN, listOf(Part.LogNormal(340.0, 0.35))),
        /** Spawn to the swap from the rod to a weapon. */
        SWAP_REACTION(Kind.PLAIN, listOf(Part.LogNormal(852.0, 0.45))),
        /** Swap to the first click with a Hyperion (or another teleport blade): 29 % in the swap tick. */
        SWAP_CLICK_HYPERION(Kind.PLAIN, listOf(Part.Fixed(0.0, 0.29), Part.LogNormal(69.0, 0.6, 0.71)), floorMs = 0.0),
        /** Swap to the first click with a Flaming Flay: 15 % in the swap tick. */
        SWAP_CLICK_FLAY(Kind.PLAIN, listOf(Part.Fixed(0.0, 0.15), Part.LogNormal(113.0, 0.6, 0.85)), floorMs = 0.0),
        /** Swap to the first click with any other weapon. */
        SWAP_CLICK(Kind.PLAIN, listOf(Part.LogNormal(100.0, 0.6)), floorMs = 0.0),
        /** Switching to the fight's next plan. */
        PLAN_SWITCH(Kind.PLAIN, listOf(Part.LogNormal(220.0, 0.35))),
        /** Spawn to the first rod hit. */
        FIRST_HIT(Kind.STREAK, listOf(Part.LogNormal(601.0, 0.567)), stay = 0.36, slowShare = 0.094),
        /** The recast's bobber missed: to the first rod hit. */
        BOBBER_MISS(Kind.PLAIN, listOf(Part.LogNormal(600.0, 0.35))),
        /** Cast to the recast's bobber killing a weak creature. */
        BOBBER_DEATH(Kind.PLAIN, listOf(Part.LogNormal(260.0, 0.25))),
        /** Last death to the rod back in hand. */
        AFTER_KILL(Kind.STREAK, listOf(Part.LogNormal(459.0, 1.15)), stay = 0.35, slowShare = 0.189, capMs = 8000.0),
        /** Last death to the `/loadout` that restores the fishing loadout. */
        DEATH_TO_RESTORE(Kind.PLAIN, listOf(Part.LogNormal(905.0, 0.4))),
        /** Weapon swap to the `/loadout` for the fight. */
        WEAPON_TO_LOADOUT(Kind.PLAIN, listOf(Part.LogNormal(1000.0, 0.45))),
        /** Loadout menu closed to the rod in hand. */
        MENU_CLOSE_TO_ROD(Kind.PLAIN, listOf(Part.LogNormal(151.0, 0.6))),
        /** Loadout menu closed in a fight to the first click with the weapon (yours: 102-533 ms, p50 392). */
        MENU_CLOSE_TO_CLICK(Kind.PLAIN, listOf(Part.LogNormal(390.0, 0.6))),
        /**
         * Loadout menu closed in a fight to the weapon taken again where the loadout moved it: yours came 151-451 ms after
         * the close (4 weapon swaps), any slot change after such a close 151-704 ms (8): few, a conservative fit.
         */
        MENU_CLOSE_TO_SWAP(Kind.PLAIN, listOf(Part.LogNormal(250.0, 0.5))),
        /** Your recast after a catch to your swap to a weapon (108 weapon fights: p10 485, p50 747, p90 1315 ms; one in 108 a tick after it). */
        RECAST_TO_SWAP(Kind.PLAIN, listOf(Part.LogNormal(734.0, 0.54))),
        /**
         * The creature you fought died to your hand reacting to it: the use key you held went up 50-950 ms later (p50 250,
         * 28 deaths with nothing else of yours alive); a move still turning comes to rest then too, if it has not ended
         * (your view's moves at a death ran on like at any other moment: under half their speed after p50 99 ms, 102 ms
         * elsewhere). Drawn within your range: a hold going on past your slowest key-up would be no hand of yours.
         */
        DEATH_REACTION(Kind.PLAIN, listOf(Part.LogNormal(212.0, 0.91)), capMs = 950.0),
        /**
         * The creature you fought with melee died to a click already on its way still going out, at nothing: 38 % of yours
         * in the death's tick, the rest p50 146 ms after it (73 clicks, up to 902 ms).
         */
        DEATH_CLICK(Kind.PLAIN, listOf(Part.Fixed(0.0, 0.38), Part.LogNormal(146.0, 0.89, 0.62)), floorMs = 0.0, capMs = 1000.0),
        /** The same after your right presses: 1 of 9 in the death's tick, the rest 99-551 ms (few: a conservative fit). */
        DEATH_PRESS(Kind.PLAIN, listOf(Part.Fixed(0.0, 0.11), Part.LogNormal(232.0, 0.58, 0.89)), floorMs = 0.0, capMs = 1000.0),
        /** The use key you held through a kill went up to the item swap after it: 103-2649 ms, p50 202 (19 kills, one swap came first). */
        KEY_UP_TO_SWAP(Kind.PLAIN, listOf(Part.LogNormal(205.0, 0.7))),
        /**
         * A creature you fought died with another of yours alive, fought with another item: to your swap to that item (45
         * within 3 s, the swap followed by your fight click: 50-2951 ms, a third of them quick, 50-603 ms, the rest p50 about
         * 1650), drawn within the 3 s they were measured in.
         */
        DEATH_TO_SWAP(Kind.PLAIN, listOf(Part.LogNormal(270.0, 0.65, 0.33), Part.LogNormal(1550.0, 0.45, 0.67)), capMs = 3000.0),
        /**
         * A creature others split off as it dies (a Bayou Sludge) died to the wait for its children: theirs came up 0-596 ms
         * after the death (25 within 1.5 s of one in your recordings, 18 of them in its tick, the rest p50 about 150 ms, 1499
         * at the most); few, a conservative fit.
         */
        CHILD_WAIT(Kind.PLAIN, listOf(Part.Fixed(0.0, 0.72), Part.LogNormal(150.0, 1.2, 0.28)), floorMs = 0.0, capMs = 1500.0),
        /**
         * The last kill was such a creature and none came up off it: to the rod back in hand. Your first slot change after
         * such a death with no child within 1.5 s came 0-4149 ms later (27, p10 248, p50 797, p90 3990), not your usual wait
         * after a kill: the hand waits for children that may come, it does not wait out when they would.
         */
        PARENT_AFTER_KILL(Kind.PLAIN, listOf(Part.LogNormal(900.0, 1.13)), capMs = 6000.0),
        /**
         * The rod came back with creatures parked out of reach to the moment the weapon loadout may go: your /loadout back
         * to the fishing one came 3.7-22.7 s after the rod while a creature of yours stayed alive (6, p50 about 9 s), less
         * your reaction to that moment drawn on top ([DEATH_TO_RESTORE], p50 905); few, a conservative fit.
         */
        PARKED_KEEP(Kind.PLAIN, listOf(Part.LogNormal(8000.0, 0.75)), capMs = 25_000.0),
        /**
         * Uses of a plan without an hp drop of its creature for this long (and a drawn number of them) give it up: your
         * recordings have no weapon that did nothing for long (your only switches after uses that did not hurt yet came
         * 400-652 ms after one or two rod hits, your habit with those creatures), a conservative lognormal around the 1.5 s
         * it used to be.
         */
        ESCALATE(Kind.PLAIN, listOf(Part.LogNormal(1500.0, 0.35)), capMs = 5000.0),
        /**
         * A screen of yours (your inventory, a menu) closed with a creature of yours alive to your next click: 0-1898 ms
         * (20, p50 575, one in the close's tick and one a tick after).
         */
        SCREEN_CLOSE_TO_CLICK(Kind.PLAIN, listOf(Part.LogNormal(412.0, 0.99)), floorMs = 0.0, capMs = 3000.0),
        /**
         * A screen of yours closed to your next slot change: 51-2605 ms (34 within 3 s, p10 148, p25 200, p50 474, p90
         * 1148; one within a tick, none in the close's own).
         */
        SCREEN_CLOSE_TO_SLOT(Kind.PLAIN, listOf(Part.LogNormal(400.0, 0.9)), capMs = 3000.0),
        /** The same to your next command (a /loadout): 107, 156, 156, 207, 1456, 1706, 2251 ms (7; few, a conservative fit, never within two ticks). */
        SCREEN_CLOSE_TO_COMMAND(Kind.PLAIN, listOf(Part.LogNormal(436.0, 1.0)), floorMs = 95.0, capMs = 3000.0),
        /**
         * The same to your next move of the view of 3 degrees or more: 4 of 14 in the close's own tick (the hand already on
         * the mouse), the rest 48-1995 ms (p50 245).
         */
        SCREEN_CLOSE_TO_MOVE(Kind.PLAIN, listOf(Part.Fixed(0.0, 0.29), Part.LogNormal(245.0, 1.15, 0.71)), floorMs = 0.0, capMs = 3000.0),
        /**
         * A move came to rest to the next separate one: your gaps between moves (384 of a move of 6 degrees or more and one
         * of 3 or more within 3 s) p10 100, p25 147, p50 200, p75 303, p90 535 ms, none under 95; drawn above that, this
         * lognormal gives 112 / 141 / 207 / 331 / 524.
         */
        MOVE_GAP(Kind.PLAIN, listOf(Part.LogNormal(150.0, 0.85)), floorMs = 95.0),
        /**
         * A creature right above or below you (it lands on you) to the first turn towards it while it did not fall into the
         * crosshair by itself: no recording measures it, a conservative lognormal around the second it used to be.
         */
        FALL_WAIT(Kind.PLAIN, listOf(Part.LogNormal(1000.0, 0.3)), capMs = 3000.0),
        /**
         * Between two re-chooses of a Learned creature's plan (another weapon may reach it better by now): nothing of yours
         * to measure, a conservative lognormal around the 2 s it used to be, so no plan switch falls on a fixed grid.
         */
        RECHOOSE(Kind.PLAIN, listOf(Part.LogNormal(2000.0, 0.3)), capMs = 6000.0),
        /**
         * The crosshair came onto the creature to the melee click: your first clicks after a turn brought it there came
         * 0-700 ms later, p50 100 ms, one in 14 in that very tick (a draw under 25 ms is due at once).
         */
        BOX_ENTRY_CLICK(Kind.PLAIN, listOf(Part.LogNormal(100.0, 1.0)), floorMs = 0.0, capMs = 2000.0),
        /**
         * The view came within a ranged plan's aim to the first right press or hold: your first Flay presses after a turn
         * came 1-445 ms after the error fell within its aim (19: p10 51, p50 150, p90 359, one in that very tick), 18 of
         * 26 while the view still turned.
         */
        AIM_ENTRY_PRESS(Kind.PLAIN, listOf(Part.LogNormal(150.0, 0.75)), floorMs = 0.0, capMs = 2000.0),
        /**
         * A teleport blade's look-down came within 5° of where it came to rest to the click: yours 1-299 ms later (7: p50
         * 53, one in that very tick); few, a conservative fit.
         */
        LOOK_DOWN_CLICK(Kind.PLAIN, listOf(Part.LogNormal(60.0, 0.8)), floorMs = 0.0, capMs = 1500.0),
        /**
         * Something a click must never touch (another player's creature, a player or any other entity under the crosshair or
         * on the ability's way, a door or a lever) moved out of the way to the click held for it: your recordings log no
         * entity but yours, so a conservative lognormal around your reactions to a view where a press may go out
         * ([AIM_ENTRY_PRESS], p50 150) and to a bite (p50 201), never in the tick the way came clear.
         */
        PATH_CLEAR(Kind.PLAIN, listOf(Part.LogNormal(200.0, 0.6)), floorMs = 50.0, capMs = 1500.0),
        /** Gaps between rod melee clicks: two rhythms, 150-200 and 400-500 ms. */
        MELEE_GAP_ROD(Kind.RHYTHM, listOf(Part.LogNormal(175.0, 0.15, 0.45), Part.LogNormal(450.0, 0.2, 0.55)), stay = 0.75),
        /** Gaps between weapon melee clicks. */
        MELEE_GAP_WEAPON(Kind.RHYTHM, listOf(Part.LogNormal(170.0, 0.2, 0.75), Part.LogNormal(380.0, 0.3, 0.25)), stay = 0.75),
        /** Gaps between single right presses. */
        PRESS_GAP(Kind.PLAIN, listOf(Part.LogNormal(345.0, 0.55))),
        /** How long the use key is held (Flaming Flay). */
        HOLD(Kind.PLAIN, listOf(Part.LogNormal(1098.0, 1.02)), capMs = 10505.0),
        /** Release to the next hold. */
        HOLD_GAP(Kind.PLAIN, listOf(Part.LogNormal(598.0, 0.5))),
        /** Swap to the first turn towards the creature: 15 % right away. */
        FIRST_TURN_SWAP(Kind.PLAIN, listOf(Part.Uniform(0.0, 50.0, 0.15), Part.LogNormal(330.0, 1.5, 0.85)), floorMs = 0.0, capMs = 8000.0),
        /** Spawn (or unparking) to the first turn when nothing was swapped; cap it at Give Up After − 2 s with maxMs. */
        FIRST_TURN_SPAWN(Kind.PLAIN, listOf(Part.LogNormal(3500.0, 1.1))),
        /** The creature left the tolerance to the corrective move. */
        CORRECTION_REACTION(Kind.PLAIN, listOf(Part.LogNormal(150.0, 0.6))),
        /**
         * Last death to the turn back, within the 3 s your turn backs were measured in, when nothing of your hand went on
         * past the death: yours 0-2048 ms (12 of your 38, p50 275, one in the death's tick). The others had a move still
         * turning or the use key held at the death and came after its end ([TURN_BACK_AFTER_MOVE], [TURN_BACK_AFTER_KEY_UP]):
         * the latency of all 38 (p50 426) holds those ends already.
         */
        TURN_BACK(Kind.PLAIN, listOf(Part.LogNormal(300.0, 1.08)), floorMs = 0.0, capMs = 3000.0),
        /**
         * A move of the view still turning at the last death (or one after it) came to rest to the turn back: yours 147-1501
         * ms after it (12, p50 about 500), none sooner than your gaps between moves allow.
         */
        TURN_BACK_AFTER_MOVE(Kind.PLAIN, listOf(Part.LogNormal(470.0, 0.68)), floorMs = 95.0, capMs = 3000.0),
        /**
         * The use key held through the last kill went up to the turn back: of your 11 that did not start before the key-up, 4
         * started in its very tick, the rest 48-1199 ms after it (6 of your 17 started with the key still down, which the
         * killer never does: no use goes off on the way back).
         */
        TURN_BACK_AFTER_KEY_UP(Kind.PLAIN, listOf(Part.Fixed(0.0, 0.36), Part.LogNormal(212.0, 1.33, 0.64)), floorMs = 0.0, capMs = 3000.0),
        /** A turn back that was skipped, when the view does not look at water after all (Auto Fishing waits for it). */
        LATE_TURN_BACK(Kind.PLAIN, listOf(Part.LogNormal(9000.0, 0.8)), capMs = 20_000.0),
        /** Between idle re-aims towards the fishing view. */
        IDLE_REAIM(Kind.PLAIN, listOf(Part.LogNormal(40000.0, 0.6))),
        /**
         * Something a look around could not plan for brought the cast nearer (a bite, a cast let go from where the look
         * stayed away) to the view starting back. Your first moves back towards where you cast from before, after a cast
         * from a view 4° or more off it, came 48-1199 ms after the click in the first 1.5 s (14 of 81 casts, p50 578, one
         * in the click's next tick); your first moves after a bite while you looked away 199-904 ms (4 of 27). The rest
         * came later or never: you recast from where you looked, which the cast guard does not let the bot do, so it is
         * drawn under 1.5 s.
         */
        GLANCE_REACTION(Kind.PLAIN, listOf(Part.LogNormal(550.0, 0.8)), capMs = 1500.0),
        /**
         * The reeled bobber is still out (server lag) to reeling again: none of your 1774 reels needed another, so a
         * conservative lognormal around the old fixed 2 s, drawn per reel and per retry.
         */
        REEL_RETRY(Kind.PLAIN, listOf(Part.LogNormal(2000.0, 0.25)), capMs = 3000.0),
        /**
         * Auto Fishing's bobber came to rest out of water and lava to the reel: your reels of a bobber lying 3 blocks or
         * more above the water, with no bite, came 702-2000 ms after the cast (14, p50 997), a few ticks of flight before
         * it lay still; what is left is your reaction to it (few: a conservative fit), drawn per cast.
         */
        LANDING_REEL(Kind.PLAIN, listOf(Part.LogNormal(750.0, 0.45)), capMs = 3000.0),
        /**
         * A cast reeled in with no bite to the start of the turn home: your next move of 3° or more after such a reel came
         * in the reel's own tick 5 times and in the next 5 times of 62, the rest 100-2900 ms after it (p50 447, p90 1495),
         * within the 3 s they were measured in.
         */
        REEL_TO_TURN(Kind.PLAIN, listOf(Part.Uniform(0.0, 75.0, 0.16), Part.LogNormal(479.0, 0.82, 0.84)), floorMs = 0.0, capMs = 3000.0),
        /**
         * A move of the view came to rest to your recast after a catch, the recast due before that rest (its reel came
         * before it): 52 recasts after a move of 3° or more, 101-2153 ms after its last moving tick (p10 150, p25 199, p50
         * 202, p75 312, p90 444), none sooner; drawn above your shortest gap after a rest, within the 3 s they were
         * measured in. 22 of them came 4 ticks after it (0.45-2.7 s after their reel, so no held key's ticks): a quick
         * hand of yours and a slower one, two lognormals judged by the tick each draw goes on (the nearest to the wait):
         * the quick one as the bins' maximum likelihood has it (r12/restmix.js), the slower one chosen by your quantiles
         * on the tick grid (r12/recastq.js), whose ticks give 150 / 200 / 200 / 300 / 450 and 42 % on the fourth tick, as
         * yours (150 / 200 / 200 / 313 / 445, 42 %); log-likelihood over your bins -116.8 (the bins' best -114.6, whose
         * ticks reach 600 at p90; one lognormal -127.3).
         */
        REST_TO_RECAST(Kind.PLAIN, listOf(Part.LogNormal(202.0, 0.096, 0.4), Part.LogNormal(260.0, 0.6, 0.6)), floorMs = 95.0, capMs = 3000.0),
        /**
         * The same for your other casts (no catch before their reel: after a fight, a reel without a bite): 39 of them,
         * 98-2998 ms after the last moving tick (p10 140, p25 200, p50 350, p75 904, p90 2359), a quick hand and a fifth
         * that waited seconds: two lognormals fitted the same way (0.757 / 0.243 of the law cut to 95..3000; here each part
         * is drawn below the cap with its whole weight and the quick one's draws under 95 ms are dropped, so 0.817 /
         * 0.183 keep those shares), whose ticks give 150 / 200 / 350 / 1000 / 2350 (r12/poolcheck.js; one lognormal fitted
         * to the p50 and p90 together put its median at 450).
         */
        REST_TO_CAST(Kind.PLAIN, listOf(Part.LogNormal(220.0, 0.867, 0.817), Part.LogNormal(2579.0, 0.291, 0.183)), floorMs = 95.0, capMs = 3000.0),
        /**
         * A screen of yours (a container: the recorder logs those) closed while a bite of yours waited for its reel, to the
         * reel: 0, 51, 99, 102, 144, 153, 792, 1147 ms (8; one in the close's tick, one a tick after); few, a conservative fit.
         */
        SCREEN_CLOSE_TO_REEL(Kind.PLAIN, listOf(Part.LogNormal(150.0, 1.0)), floorMs = 0.0, capMs = 1500.0),
        /** The same with no bobber out (a cast due) to your cast: 193-3754 ms (22, p10 206, p25 314, p50 599, p75 864, p90 1289). */
        SCREEN_CLOSE_TO_CAST(Kind.PLAIN, listOf(Part.LogNormal(560.0, 0.75)), floorMs = 95.0, capMs = 4000.0),
        /**
         * A left click to your next right click of the rod that was due already when the left one went out (its own clock
         * started before it, so that clock cannot hold the switch): a bite reel whose bite showed before the left click, a
         * recast whose reel came before it. 12 of them, 101 / 150 / 155 / 196 / 201 / 202 / 250 / 296 / 298 / 548 / 549 /
         * 800 ms, none within two ticks; few, a conservative fit. Your other left-to-right pairs (154 within a second, p50
         * 649) are reels and casts timed from a bite or a reel after the left click: their own clocks hold the switch.
         */
        CROSS_L_TO_R(Kind.PLAIN, listOf(Part.LogNormal(261.0, 0.61)), floorMs = 95.0, capMs = 1000.0),
        /**
         * A right click of the rod to your next left click that came in your hitting rhythm (the left click before it at most
         * 1.5 s earlier, so its clock started before the right one): 42 of them, p10 147 / p25 201 / p50 276 / p75 534 / p90
         * 700, 99-849 ms, none within two ticks. Your other right-to-left pairs (383 within a second, a hump around 650 ms)
         * are your first hits after the recast, which your first hit after a missed bobber ([BOBBER_MISS]) is measured on.
         */
        CROSS_R_TO_L(Kind.PLAIN, listOf(Part.LogNormal(311.0, 0.64)), floorMs = 95.0, capMs = 1000.0);

        /** The default as [DEFAULT_QUANTILES] evenly spaced quantiles, so it is drawn like learned samples. */
        val defaults: List<Int> by lazy { quantiles(parts, capMs) }
    }

    /**
     * What a family of waits remembers between draws: whether the last one was slow (or in the slow rhythm). [stay]
     * overrides the chance a slow wait follows a slow one, or that the rhythm is kept (the learned ρ).
     */
    class StreakState(var stay: Double? = null) {
        /** The last draw was slow; null before the first. */
        var slow: Boolean? = null
        /** The last normal (not slow) draw was in the upper half of the normal waits; null before the first. */
        var high: Boolean? = null

        /** Forgets the last draw (a new session or fight). */
        fun reset() {
            slow = null
            high = null
        }
    }

    /**
     * The draws' candidates for one list of samples: sorted, within the sanity bounds, split by streak and rhythm. A
     * streak's normal waits are split at their median as well ([normalLow], [normalHigh]): a normal draw stays in the
     * half of the one before with chance [keep], fitted so the draws have your samples' lag-1 with the stay rate they
     * are drawn with.
     */
    private class Pool(
        val learned: Boolean, val all: IntArray, val lo: Double, val hi: Double,
        val slow: IntArray, val normal: IntArray, val fast: IntArray, val slowMode: IntArray, val stay: Double?,
        val normalLow: IntArray = IntArray(0), val normalHigh: IntArray = IntArray(0), val split: Int = 0, val keep: Double = 0.0,
    )

    /**
     * Keyed by content, so a list rebuilt for every draw still finds its pool (a rhythm's ρ fit takes milliseconds); and
     * by a streak's [stay] override, which its [Pool.keep] is fitted for.
     */
    private data class PoolKey(val action: Action, val samples: List<Int>, val maxMs: Double?, val stay: Double? = null)

    private const val MAX_POOLS = 256
    private val pools = ConcurrentHashMap<PoolKey, Pool>()

    /**
     * A wait for [action] in ms: one of [samples] (yours, in the order they happened) or, with fewer than
     * [MIN_SAMPLES], of the action's default. With a [state] a [Kind.STREAK] action draws slow waits in streaks and a
     * [Kind.RHYTHM] one keeps its rhythm; pass null for a draw that must not advance them (a missed click). [maxMs] caps
     * this draw (the wait is drawn below it, never piled at it).
     */
    fun draw(action: Action, samples: List<Int>, state: StreakState?, rng: Random, maxMs: Double? = null): Long {
        val pool = pool(action, samples, maxMs, state?.stay?.takeIf { action.kind == Kind.STREAK })
        if (pool.all.isEmpty()) return max(0.0, min(pool.lo, pool.hi)).roundToLong()
        val x = when {
            state == null || action.kind == Kind.PLAIN -> pick(pool.all, rng)
            action.kind == Kind.STREAK -> streak(action, pool, state, rng)
            else -> rhythm(action, pool, state, rng)
        }
        return finish(x, pool, rng)
    }

    /** The median wait for [action]: what it usually takes, for planning around it. */
    fun typical(action: Action, samples: List<Int>, maxMs: Double? = null): Long {
        val all = pool(action, samples, maxMs).all
        return if (all.isEmpty()) 0L else all[all.size / 2].toLong()
    }

    /**
     * The old `Humanize.ms` without its clamp pile-up: with at least [minSamples] samples one of them (jittered from
     * 400 ms on), with 3 or more anything within their range made 10 % wider, else [min]..[max]. A value outside
     * [min] / 2 .. [max] × 3 is drawn again, at most 8 times, then a sample itself is used.
     */
    fun ranged(samples: List<Int>, min: Int, max: Int, rng: Random, minSamples: Int = MIN_SAMPLES): Long {
        val lo = min / 2.0
        val hi = max * 3.0
        if (samples.size >= minSamples) {
            var base = 0
            repeat(TRIES) {
                base = samples[rng.nextInt(samples.size)]
                val v = jitter(base, rng)
                if (v in lo..hi) return v.roundToLong()
            }
            return base.toLong()
        }
        if (samples.size < MIN_SIDE) return rng.nextLong(min.toLong(), maxOf(min, max).toLong() + 1)
        val low = (samples.min() * 0.9).toLong()
        // maxOf: an edited profile with negative values must not make the range empty
        val high = maxOf(low, (samples.max() * 1.1).toLong())
        repeat(TRIES) {
            val v = rng.nextLong(low, high + 1)
            if (v >= lo && v <= hi) return v
        }
        return samples[rng.nextInt(samples.size)].toLong()
    }

    /** When a wait of [waitMs] from [nowMs], checked once per tick, is due: rounded to the nearest tick, not the next. */
    fun dueAt(nowMs: Long, waitMs: Long) = nowMs + waitMs - TICK_MS / 2

    /** A standard normal draw. */
    fun normal(rng: Random): Double {
        val u = 1.0 - rng.nextDouble()
        return sqrt(- 2.0 * ln(u)) * cos(2.0 * PI * rng.nextDouble())
    }

    /**
     * A normal draw with mean [mean] and sd [sd], drawn within [lo] .. [hi] (never piled at a bound): the inverse CDF of
     * the part of the normal between them. A range so far out in a tail that no draw falls into it is drawn evenly.
     */
    fun normal(mean: Double, sd: Double, lo: Double, hi: Double, rng: Random): Double {
        if (hi <= lo) return lo
        if (sd <= 0.0) return mean.coerceIn(lo, hi)
        val a = phi((lo - mean) / sd)
        val b = phi((hi - mean) / sd)
        if (b - a < TAIL_MASS) return lo + (hi - lo) * rng.nextDouble()
        // the bounds only catch the approximations' rounding at the very ends, no draw piles there
        return (mean + sd * phiInv(a + (b - a) * rng.nextDouble())).coerceIn(lo, hi)
    }

    /** A lognormal draw with median [p50] and ln-sd [sigma], drawn below [cap] (never piled at it). */
    fun lognormal(p50: Double, sigma: Double, rng: Random, cap: Double = Double.POSITIVE_INFINITY): Double {
        if (cap.isInfinite() || sigma <= 0.0) return min(p50 * exp(sigma * normal(rng)), cap)
        if (cap <= 0.0) return 0.0
        return p50 * exp(sigma * phiInv(rng.nextDouble().coerceAtLeast(1e-12) * phi(ln(cap / p50) / sigma)))
    }

    /** The slow threshold of [samples]: 2.5 × their median, or null without samples. */
    fun slowThreshold(samples: List<Int>): Double? = if (samples.isEmpty()) null else SLOW_FACTOR * median(samples)

    /** Share of [samples] above the slow threshold. */
    fun slowShare(samples: List<Int>): Double {
        val thr = slowThreshold(samples) ?: return 0.0
        return samples.count { it > thr }.toDouble() / samples.size
    }

    /** How often a slow wait follows a slow one in the [ordered] samples, or null with fewer than 8 slow ones followed by another. */
    fun stayRate(ordered: List<Int>): Double? {
        val thr = slowThreshold(ordered) ?: return null
        var prev = 0
        var both = 0
        for (i in 1 until ordered.size) {
            if (ordered[i - 1] <= thr) continue
            prev++
            if (ordered[i] > thr) both++
        }
        return if (prev >= MIN_STAY_PAIRS) both.toDouble() / prev else null
    }

    /** Lag-1 autocorrelation of ln of the [ordered] samples, or null with fewer than 3. */
    fun lag1(ordered: List<Int>): Double? {
        if (ordered.size < 3) return null
        return lag1(DoubleArray(ordered.size) { ln(max(1, ordered[it]).toDouble()) })
    }

    /**
     * The rhythm ρ (chance the fast or slow rhythm is kept) whose draws have the lag-1 of ln of your [ordered] gaps, or
     * null with too few gaps or a rhythm with too few samples on one side.
     */
    fun fitRho(ordered: List<Int>): Double? {
        if (ordered.size < MIN_RHYTHM_SAMPLES) return null
        val target = lag1(ordered) ?: return null
        val all = ordered.filter { it > 0 }.toIntArray()
        val fast = all.filter { it < RHYTHM_SPLIT_MS }.toIntArray()
        val slow = all.filter { it >= RHYTHM_SPLIT_MS }.toIntArray()
        if (fast.size < MIN_SIDE || slow.size < MIN_SIDE) return null
        var best = 0.0
        var bestErr = Double.MAX_VALUE
        var i = 0
        while (i * 0.05 <= RHO_MAX + 1e-9) {
            val rho = i * 0.05
            val err = abs(simulatedLag1(rho, all, fast, slow) - target)
            if (err < bestErr) {
                best = rho
                bestErr = err
            }
            i++
        }
        return best
    }

    private fun simulatedLag1(rho: Double, all: IntArray, fast: IntArray, slow: IntArray): Double {
        val rng = Random(FIT_SEED)
        var mode: Boolean? = null
        val xs = DoubleArray(FIT_DRAWS)
        for (i in xs.indices) {
            val keep = mode
            val x = if (keep != null && rng.nextDouble() < rho) pick(if (keep) slow else fast, rng)
            else pick(all, rng).also { mode = it >= RHYTHM_SPLIT_MS }
            xs[i] = ln(max(1.0, jitter(x, rng)))
        }
        return lag1(xs)
    }

    private fun streak(action: Action, pool: Pool, state: StreakState, rng: Random): Int {
        if (pool.slow.size < MIN_SIDE || pool.normal.size < MIN_SIDE) return pick(pool.all, rng)
        val pi = pool.slow.size.toDouble() / pool.all.size
        // a stay rate of 1 (each of your slow waits followed by another) would never enter the slow state, or never leave it
        val stay = (state.stay ?: pool.stay ?: defaultStay(action, pool.learned, pi)).coerceIn(0.0, RHO_MAX)
        // entering this often keeps the slow share at pi: the draws keep the samples' distribution
        val enter = (pi * (1 - stay) / (1 - pi)).coerceIn(0.0, 1.0)
        val slow = if (state.slow == true) rng.nextDouble() < stay else rng.nextDouble() < enter
        state.slow = slow
        if (slow) return pick(pool.slow, rng)
        // your pace between the slow ones carries over too; staying in a half as often as leaving it keeps every share
        val high = state.high
        if (high != null && pool.keep > 0.0 && rng.nextDouble() < pool.keep) return pick(if (high) pool.normalHigh else pool.normalLow, rng)
        return pick(pool.normal, rng).also { state.high = it >= pool.split }
    }

    /**
     * The action's default stay rate. Learned samples with too few slow waits to learn their own keep the default's
     * lift over its slow share instead: a default of 35 % slow after slow means nothing where 0.4 % of the waits are slow.
     */
    private fun defaultStay(action: Action, learned: Boolean, pi: Double): Double {
        if (! learned || action.slowShare <= 0.0) return action.stay
        return (pi * action.stay / action.slowShare).coerceIn(pi, RHO_MAX)
    }

    /**
     * The chance a normal wait stays in the half of the one before whose draws (slow streaks at [stay] included) have the
     * lag-1 of ln of your [ordered] waits; 0 with too few of them, halves too small, or a lag-1 the streaks already reach.
     */
    private fun fitKeep(ordered: List<Int>, all: IntArray, slow: IntArray, normal: IntArray, low: IntArray, high: IntArray, split: Int, stay: Double): Double {
        if (ordered.size < MIN_RHYTHM_SAMPLES || low.size < MIN_SIDE || high.size < MIN_SIDE || slow.size < MIN_SIDE) return 0.0
        val target = lag1(ordered) ?: return 0.0
        val pi = slow.size.toDouble() / all.size
        val enter = (pi * (1 - stay) / (1 - pi)).coerceIn(0.0, 1.0)
        var best = 0.0
        var bestErr = Double.MAX_VALUE
        var i = 0
        while (i * 0.05 <= RHO_MAX + 1e-9) {
            val keep = i * 0.05
            val rng = Random(FIT_SEED)
            var wasSlow = false
            var wasHigh: Boolean? = null
            val xs = DoubleArray(FIT_DRAWS)
            for (k in xs.indices) {
                wasSlow = rng.nextDouble() < if (wasSlow) stay else enter
                val h = wasHigh
                val x = when {
                    wasSlow -> pick(slow, rng)
                    h != null && rng.nextDouble() < keep -> pick(if (h) high else low, rng)
                    else -> pick(normal, rng).also { wasHigh = it >= split }
                }
                xs[k] = ln(max(1.0, jitter(x, rng)))
            }
            val err = abs(lag1(xs) - target)
            if (err < bestErr) {
                best = keep
                bestErr = err
            }
            i++
        }
        return best
    }

    private fun rhythm(action: Action, pool: Pool, state: StreakState, rng: Random): Int {
        if (pool.fast.size < MIN_SIDE || pool.slowMode.size < MIN_SIDE) return pick(pool.all, rng)
        val rho = (state.stay ?: pool.stay ?: action.stay).coerceIn(0.0, 1.0)
        val prev = state.slow
        if (prev != null && rng.nextDouble() < rho) return pick(if (prev) pool.slowMode else pool.fast, rng)
        val x = pick(pool.all, rng)
        state.slow = x >= RHYTHM_SPLIT_MS
        return x
    }

    private fun finish(x: Int, pool: Pool, rng: Random): Long {
        if (x < JITTER_FROM_MS) return x.toLong()
        repeat(TRIES) {
            val v = jitter(x, rng)
            if (v >= pool.lo && v <= pool.hi) return v.roundToLong()
        }
        return x.toLong()
    }

    private fun jitter(x: Int, rng: Random) = if (x < JITTER_FROM_MS) x.toDouble() else x * exp(JITTER_SIGMA * normal(rng))

    private fun pick(values: IntArray, rng: Random) = values[rng.nextInt(values.size)]

    private fun pool(action: Action, samples: List<Int>, maxMs: Double?, stayOverride: Double? = null): Pool {
        pools[PoolKey(action, samples, maxMs, stayOverride)]?.let { return it }
        val learned = samples.size >= MIN_SAMPLES
        val sorted = (if (learned) samples else action.defaults).sorted()
        val lo = max(action.floorMs, 0.25 * quantile(sorted, 0.01))
        val hi = minOf(action.capMs, maxMs ?: Double.POSITIVE_INFINITY, 3 * quantile(sorted, 0.99))
        val all = sorted.filter { it >= lo && it <= hi }.toIntArray()
        val thr = if (all.isEmpty()) 0.0 else SLOW_FACTOR * all[all.size / 2]
        val stay = when {
            ! learned -> null
            action.kind == Kind.STREAK -> stayRate(samples)
            action.kind == Kind.RHYTHM -> fitRho(samples)
            else -> null
        }
        val slow = all.filter { it > thr }.toIntArray()
        val normal = all.filter { it <= thr }.toIntArray()
        val split = if (normal.isEmpty()) 0 else normal[normal.size / 2]
        val low = normal.filter { it < split }.toIntArray()
        val high = normal.filter { it >= split }.toIntArray()
        // fitted for the stay rate the draws use (one learned over every recording overrides these samples' own): a keep
        // fitted for the other one leaves the draws' lag-1 off yours (catch recasts: 0.33 for your 0.41, with 0.53 learned
        // and 0.67 in these samples)
        val keepStay = stayOverride ?: stay ?: defaultStay(action, true, slow.size.toDouble() / max(1, all.size))
        val keep = if (learned && action.kind == Kind.STREAK) fitKeep(samples, all, slow, normal, low, high, split, keepStay.coerceIn(0.0, RHO_MAX)) else 0.0
        val pool = Pool(
            learned, all, lo, hi, slow, normal,
            all.filter { it < RHYTHM_SPLIT_MS }.toIntArray(), all.filter { it >= RHYTHM_SPLIT_MS }.toIntArray(), stay,
            low, high, split, keep,
        )
        if (pools.size >= MAX_POOLS) pools.clear()
        // a copy: the caller's list may grow later, which would change its key under the map
        pools[PoolKey(action, samples.toList(), maxMs, stayOverride)] = pool
        return pool
    }

    private fun quantiles(parts: List<Part>, capMs: Double): List<Int> {
        val total = parts.sumOf { it.weight }
        val out = ArrayList<Int>()
        for (part in parts) {
            val n = max(1, (DEFAULT_QUANTILES * part.weight / total).roundToInt())
            for (j in 0 until n) out += part.quantile((j + 0.5) / n, capMs).roundToInt()
        }
        return out.sorted()
    }

    private fun median(samples: List<Int>) = quantile(samples.sorted(), 0.5)

    private fun quantile(sorted: List<Int>, q: Double): Double {
        if (sorted.isEmpty()) return 0.0
        val i = (sorted.size - 1) * q
        val lo = i.toInt()
        val hi = min(lo + 1, sorted.size - 1)
        return sorted[lo] + (sorted[hi] - sorted[lo]) * (i - lo)
    }

    private fun lag1(xs: DoubleArray): Double {
        if (xs.size < 3) return 0.0
        var mx = 0.0
        var my = 0.0
        for (i in 1 until xs.size) {
            mx += xs[i - 1]
            my += xs[i]
        }
        mx /= xs.size - 1
        my /= xs.size - 1
        var sxy = 0.0
        var sxx = 0.0
        var syy = 0.0
        for (i in 1 until xs.size) {
            val a = xs[i - 1] - mx
            val b = xs[i] - my
            sxy += a * b
            sxx += a * a
            syy += b * b
        }
        return if (sxx <= 0.0 || syy <= 0.0) 0.0 else sxy / sqrt(sxx * syy)
    }

    /** Standard normal CDF (Numerical Recipes' erfc, error below 1.2e-7). */
    internal fun phi(z: Double): Double {
        val x = - z / sqrt(2.0)
        val t = 1.0 / (1.0 + 0.5 * abs(x))
        val r = t * exp(- x * x - 1.26551223 + t * (1.00002368 + t * (0.37409196 + t * (0.09678418 + t * (- 0.18628806 +
            t * (0.27886807 + t * (- 1.13520398 + t * (1.48851587 + t * (- 0.82215223 + t * 0.17087277)))))))))
        return 0.5 * (if (x >= 0) r else 2.0 - r)
    }

    /** Inverse of [phi] (Acklam's rational approximation, relative error below 1.2e-9). */
    private fun phiInv(p: Double): Double {
        val q = p.coerceIn(1e-15, 1 - 1e-15)
        if (q < P_LOW) {
            val r = sqrt(- 2 * ln(q))
            return (((((C[0] * r + C[1]) * r + C[2]) * r + C[3]) * r + C[4]) * r + C[5]) / ((((D[0] * r + D[1]) * r + D[2]) * r + D[3]) * r + 1)
        }
        if (q > 1 - P_LOW) {
            val r = sqrt(- 2 * ln(1 - q))
            return - (((((C[0] * r + C[1]) * r + C[2]) * r + C[3]) * r + C[4]) * r + C[5]) / ((((D[0] * r + D[1]) * r + D[2]) * r + D[3]) * r + 1)
        }
        val r = q - 0.5
        val s = r * r
        return (((((A[0] * s + A[1]) * s + A[2]) * s + A[3]) * s + A[4]) * s + A[5]) * r /
            (((((B[0] * s + B[1]) * s + B[2]) * s + B[3]) * s + B[4]) * s + 1)
    }

    /** Below this much of the normal between its bounds a truncated draw ([normal]) is drawn evenly: the inverse CDF is no use so far out. */
    private const val TAIL_MASS = 1e-9
    private const val P_LOW = 0.02425
    private val A = doubleArrayOf(- 3.969683028665376e+01, 2.209460984245205e+02, - 2.759285104469687e+02, 1.383577518672690e+02, - 3.066479806614716e+01, 2.506628277459239e+00)
    private val B = doubleArrayOf(- 5.447609879822406e+01, 1.615858368580409e+02, - 1.556989798598866e+02, 6.680131188771972e+01, - 1.328068155288572e+01)
    private val C = doubleArrayOf(- 7.784894002430293e-03, - 3.223964580411365e-01, - 2.400758277161838e+00, - 2.549732539343734e+00, 4.374664141464968e+00, 2.938163982698783e+00)
    private val D = doubleArrayOf(7.784695709041462e-03, 3.224671290700398e-01, 2.445134137142996e+00, 3.754408661907416e+00)
}

//#endif
