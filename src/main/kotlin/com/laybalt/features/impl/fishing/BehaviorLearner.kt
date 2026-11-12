package com.laybalt.features.impl.fishing

//#if CHEAT

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.laybalt.features.impl.fishing.BehaviorProfile.Dist
import com.laybalt.features.impl.fishing.BehaviorProfile.Glances
import com.laybalt.features.impl.fishing.BehaviorProfile.Habit
import com.laybalt.features.impl.fishing.BehaviorProfile.LookStats
import com.laybalt.features.impl.fishing.BehaviorProfile.Looks
import com.laybalt.features.impl.fishing.BehaviorProfile.Motor
import com.laybalt.features.impl.fishing.BehaviorProfile.Profile
import com.laybalt.features.impl.fishing.BehaviorProfile.Streak
import com.laybalt.features.impl.fishing.BehaviorProfile.Timing
import com.laybalt.features.impl.fishing.BehaviorProfile.Turn
import com.laybalt.features.impl.fishing.BehaviorProfile.TurnStats
import com.laybalt.features.impl.fishing.BehaviorProfile.WeaponStyle
import com.laybalt.features.impl.fishing.SeaCreatureTracker.ClaimRules
import com.laybalt.features.impl.fishing.WeaponPlanner.BOBBER
import com.laybalt.features.impl.fishing.WeaponPlanner.ROD
import java.io.File
import java.time.LocalDateTime
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Turns [FishingRecorder] recordings (JSON lines) into a [Profile]. Pure: no files besides the given ones, no game state,
 * so it is unit tested with synthetic recordings. Fields newer recorders add are used when present, never required.
 */
object BehaviorLearner {
    private const val MAX_SAMPLES = 600
    private const val MAX_TURNS = 300
    /** A catch line within this time of a reel belongs to that reel. */
    private const val CATCH_REEL_MS = 1500L
    /** A creature dying this soon after its catch without your clicks was killed by you somehow (not ignored). */
    private const val QUICK_DEATH_MS = 60_000L
    /** Longer gaps between your reel and your recast are breaks, not your rhythm. */
    private const val MAX_RECAST_MS = 2000L
    /** Clicks this soon before a creature died without one of its own are the ones that killed it (area attacks). */
    private const val KILLING_CLICK_MS = 1500L
    /** A cast seen this soon after your rod right click was made by that click. */
    private const val CAST_CLICK_MS = 400L
    /** An item is a fishing rod with this many casts of its own, at least [MIN_CAST_SHARE] of its right clicks. */
    private const val MIN_CASTS = 3
    private const val MIN_CAST_SHARE = 0.10
    /** A left click of yours this long before a death can have killed it (a later one cannot have reached the server in time). */
    private val KILL_CLICK_MS = 30L .. 500L
    /** A creature dying this long after your cast click, with no left click of yours that could have killed it, died to that bobber. */
    private val BOBBER_DEATH_MS = 60L .. 600L
    /** The recast that kills a creature can come this long before its spawn is seen. */
    private const val BOBBER_EARLY_MS = 150L
    /**
     * Longer waits are breaks, not reactions: the start of a turn or the weapon swap after a spawn, /loadout after that
     * swap, the cast after taking the rod, the first hit after the recast.
     */
    private const val REACTION_MS = 3000L
    /** A swap this soon after a creature appeared was started before you could see it. */
    private const val MIN_REACTION_MS = 200L
    /** Your /loadout back comes this long after the fight's last death at most. */
    private const val RESTORE_MS = 5000L
    /** The rod taken this long after a loadout menu closed is the way back to fishing. */
    private const val MENU_ROD_MS = 2000L
    /** Lines that end the wait for your first click after a loadout menu closed: another item, menu, command or world. */
    private val CLOSE_CLICK_BREAKS = setOf("slot", "menu", "cmd", "world")
    /** A loadout equipped with none of your creatures around is your fishing loadout when you cast this soon after. */
    private const val FISHING_LOADOUT_CAST_MS = 10_000L
    /** A view this close to the one you first hit a creature with needed no turn. */
    private const val NO_TURN_DEG = 6.0
    /** A view step per tick that starts a turn, and one so small that the hand is still. */
    private const val MOVE_DEG = 0.6
    private const val STILL_DEG = 0.3
    /** Share of the way after which the first still tick ends a turn. */
    private const val TURN_DONE = 0.8f
    /** A correction starting this soon after a turn ended is part of it. */
    private const val CORRECTION_MS = 300L
    /** A creature closer than this horizontally is right above or below you: there is no direction to turn to. */
    private const val MIN_TURN_DISTANCE = 0.5
    /**
     * A click is effective when one of your creatures loses hp or dies this soon after it: random moments with a creature
     * of yours alive are followed by one within 400 ms 15-43 % of the time, within 800 ms far more often.
     */
    private const val EFFECT_MS = 400L
    /** The view a click was made with: the last rotation at most this old. */
    private const val ROT_AGE_MS = 150L
    /** A rod hit's damage is what the creature lost this long after it, or until your next click (+50 ms) if that came sooner. */
    private const val ROD_SHARE_MS = 600L
    private const val ROD_SHARE_NEXT_MS = 50L
    /** A rod hit this close to a weapon click (before, after) may have been helped by that weapon: no sample. */
    private const val ROD_SHARE_WEAPON_BEFORE_MS = 800L
    private const val ROD_SHARE_WEAPON_AFTER_MS = 600L
    /** A held use key repeats every 4 ticks; a repeat later than this is a new hold. */
    private const val HOLD_REPEAT_MS = 400L
    /** The key was let go somewhere before the next repeat would have come: half a repeat is added to a hold. */
    private const val HALF_REPEAT_MS = 100L
    /** Gaps between your presses (or holds) with a weapon outside this range are breaks or double counts. */
    private val PRESS_GAP_MS = 30L .. 1500L
    /** Every this many-th rotation sample of a fight is a random moment for an item's base rate. */
    private const val BASE_RATE_STEP = 10
    /** Eye height above your feet, as the recorder's `err`. */
    private const val EYE = 1.62
    /** A creature without a linked mob is aimed at as if it were this tall. */
    private const val DEFAULT_HEIGHT = 1.8
    /** Where on a creature you aim: this share of its height, as the recorder's `err` and the killer. */
    private const val AIM_SHARE = 0.6
    /**
     * A creature is a child's parent only when that child came up after at least this many of its deaths and this share
     * of all of them ([Kinship]): Bayou Sludges had Bayou Sludglings after 12 of 24 deaths and Magma Slugs Baby Magma
     * Slugs after 18 of 30, while a Frog Man had one chance "child" after 1 of 169.
     */
    private const val PARENT_DEATHS = 2
    private const val PARENT_SHARE = 0.25
    /**
     * A child found offline has no position after its spawn line, so where it came up stands for where it was only this
     * long: Baby Magma Slugs claimed as yours stayed within 3.5 blocks of their spawn for 3 s, but ones you killed 11-58 s
     * later had sunk 25 blocks and you had dropped down after them.
     */
    private const val DERIVED_SPOT_MS = 3000L
    /** Words of letters, as a nametag shows a creature's name (`Bayou Sludgling`, `Rider of the Deep`). */
    private val creatureName = Regex("""^\p{Lu}[\p{L}'-]*(?: \p{L}[\p{L}'-]*)*$""")
    /** A lower-case letter glued to a capital: the obfuscated glyph an older recording kept on a name (`aCorrupted Grincha`). */
    private val gluedGlyph = Regex("""\p{Ll}\p{Lu}""")
    /** Loadout slots of the `/loadout` menu (same as [LoadoutSwitcher.slots]). */
    private val loadoutSlots = listOf(14, 15, 16, 23, 24, 25, 32, 33, 34, 41, 42, 43)
    private val loadoutTitle = Regex("""^\((\d+)/\d+\) Loadouts?$""")
    private val loadoutCommands = setOf("loadout", "loadouts")

    /** A motor statistic needs this many movements (sub-strokes, samples), a per-bin one this many in its bin, or it keeps its default. */
    private const val MIN_MOVES = 30
    private const val MIN_BIN = 20
    /** The rest share needs this many ticks outside movements, the still share this many fight ticks. */
    private const val MIN_REST_TICKS = 1000
    /**
     * Rest nudges' sizes need this many axes of your hand's and this many of the yaw's near the pole: a nudge is a rest
     * tick's change or a one-tick movement under [NUDGE_MOVE_DEG] (one too fast for a rest tick); from [REST_POLE_PITCH] on
     * the yaw's are the pole's. Your hand's are counted up to [REST_STEPS_MAX] grid steps, the pole's fitted within
     * [REST_POLE_MAX] (the motor's [MotorParams.restPoleMax]).
     */
    private const val MIN_REST_AXES = 300
    private const val MIN_POLE_AXES = 100
    private const val NUDGE_MOVE_DEG = 2.0
    private const val REST_POLE_PITCH = 70.0
    private const val REST_STEPS_MAX = 20
    private const val REST_POLE_MAX = 150
    private const val MIN_FIGHT_TICKS = 20
    /** A timing's streak needs this many samples, its stay rate this many slow samples followed by another. */
    private const val MIN_STREAK = 30
    private const val MIN_SLOW_PAIRS = 5
    /** A timing above this many times its median is a slow one. */
    private const val SLOW_FACTOR = 2.5
    /** Lines this long after one of your creatures was last seen still belong to its fight (the recorder's tail). */
    private const val FIGHT_TAIL_MS = 3000L
    /** Fights you can turn in: shorter ones are over before a turn, longer ones were left alone for minutes. */
    private val TURN_FIGHT_MS = 1000L .. 120_000L
    /** A creature this far off your view 300-600 ms after it appeared, living this long, is one you could turn toward. */
    private const val TURN_OFF_DEG = 15.0
    private const val TURN_LIFE_MS = 1000L
    /** A movement turned toward a creature when it left at most this share of the error to it. */
    private const val TURN_CLOSES = 0.6
    /** Where the turn analysis aims at a creature: half its height (the motor critic's scripts). */
    private const val TURN_AIM_SHARE = 0.5
    /** A creature without a linked mob is this wide; the crosshair is on a box within its angular half-size, at least this. */
    private const val DEFAULT_WIDTH = 0.6
    private const val MIN_BOX_DEG = 3.0
    /** A fight ending this close to its start view needs no turn back; one is looked for this long after its last death. */
    private const val BACK_NONE_DEG = 8.0
    private const val BACK_WINDOW_MS = 3000L
    /** The view at a fight's start and end: the line at most this far from it. */
    private const val BACK_VIEW_MS = 200L
    /** A return after a turn toward a creature starts at most this long after it, ending at most this share as far from its start view. */
    private const val LOOK_BACK_MS = 5000L
    private const val LOOK_BACK_SHARE = 0.5
    /** A movement between a turn and its return this large or larger is a rest of the view the return is timed from (the replay's gap rows count them). */
    const val GAP_MOVE_DEG = 3.0
    /** Gaps between separate movements longer than this are no reaction to the one before. */
    private const val MOVE_GAP_MS = 3000L
    /** A melee click gap at or above this is the slow mode of your rhythm. */
    private const val MELEE_SLOW_MS = 300

    // the reference recordings' statistics and the planning parameters they were tuned to (DESIGN-HUMAN amendments §6.2)
    private const val REF_STROKE_A = 88.0
    private const val REF_STROKE_B = 58.0
    private const val PLAN_LAW_A = 70.0
    private const val PLAN_LAW_B = 50.0
    private val REF_MULTI = listOf(0.20, 0.29, 0.37, 0.46, 0.55)
    // the reference first share's p90 of 1.09 (the first strokes past the goal that make most overshoots) plans the first
    // stroke of the turns toward a creature at 0.87 (sd 0.34): replayed on the reference fights, with the ways back planned
    // on their own ([HumanAim.returnParamsOf], [HumanAim.turnBackParamsOf]: 10.3 % of them more than 1° past their end
    // against your 9.8 %), the bot's moves go past their end 11.7 % of the time against the reference's 13.3 % (its aims
    // and turn backs 14.0 % against 16.4 %), and its first share measures p50 0.74 / p90 1.09 against 0.78 / 1.09. The
    // motor alone (no policy ending moves, no looks or rest nudges) measured the reference p90 at a plan of 0.75. A plan of
    // 0.91 matched the turns' 16.4 % with the turn backs on this hand, but its first strokes past the goal turned 60-100°
    // moves faster than you ever did (peak p99 1058 against your fastest 1008). A plan moved by some share moves the
    // measured p90 by 0.54 of it (the motor alone; the replay's p90 moved less than its noise between plans 0.87 and
    // 0.92): see [firstMuOf]
    private const val REF_FIRST_P90 = 1.09
    private const val PLAN_FIRST = 0.87
    private const val FIRST_P90_SLOPE = 0.54
    /** A learned first stroke's plan saturates within these, inside the range [HumanAim.paramsOf] takes. */
    private const val FIRST_MU_MIN = 0.40
    private const val FIRST_MU_MAX = 1.15
    // your ways back of 20° or more in two strokes (13 in the reference recordings: turn backs, returns, back to the
    // fight) end their first stroke short of the goal, p50 0.73 (p90 0.89, none past it). Their hand plans the first
    // stroke like your turns' (the reference plan) and never past the goal: on the motor alone that measures p50 0.71,
    // and a plan moved by some share moves the measured p50 by 0.25 of it (plans 0.40-0.87, never past the goal)
    private const val REF_BACK_P50 = 0.727
    private const val PLAN_BACK = PLAN_FIRST
    private const val BACK_P50_SLOPE = 0.25
    /**
     * A way back's plan saturates within these: well inside [HumanAim.returnParamsOf]'s range and away from both bounds
     * the stroke is drawn within (0.35 and the goal), so no careful or careless hand's first strokes crowd at either.
     */
    const val BACK_MU_MIN = 0.50
    const val BACK_MU_MAX = 0.95
    private const val REF_SLOW = 0.086
    private const val PLAN_SLOW = 0.12
    private const val REF_LAG_MS = 33.0
    private const val PLAN_LAG_MS = 20.0
    private const val REF_TPK = 0.49
    private const val PLAN_TAU = 0.50
    private const val REF_CROSS = 0.07
    private const val PLAN_CROSS = 0.07
    private const val REF_REST = 0.0246
    private const val PLAN_REST = 0.015
    /** No planned stroke may be faster than this, whatever was learned. */
    private const val HARD_CAP = 1650.0

    /**
     * One line of a recording: its kind, time and fields. A `rot` line, most of every recording, is kept read ([rot]) with
     * no fields ([NO_FIELDS]): a JSON tree per line held while a recording is learned takes about 16 times the file's size.
     */
    private class Ev(val type: String, val t: Long, val o: JsonObject, val rot: GlanceMeasure.Line? = null)

    /** The fields of every `rot` line kept read; never changed. */
    private val NO_FIELDS = JsonObject()

    /**
     * Learns from [files]. [skipRecorderBuild]: leave out recordings made with the recorder-only jar (other players'
     * recordings, session `build` = [FishingRecorder.RECORDER_BUILD]), so your own profile is learned from you only.
     */
    fun learn(files: List<File>, skipRecorderBuild: Boolean = false): Profile {
        val profile = Profile(learnedAt = LocalDateTime.now().toString(), learner = BehaviorProfile.LEARNER)
        // what you fish with has to be known before any click is read: a rod-type weapon's right click is an attack
        profile.fishingRods = fishingRodsOf(files, skipRecorderBuild)
        val kin = Kinship()
        val views = ViewLog()
        views.glanceArea = areaClassesOf(files, skipRecorderBuild)
        for (file in files) {
            val events = readEvents(file, skipRecorderBuild) ?: continue
            // the part of each timing list this recording adds: a streak never runs from one recording into the next
            val before = timingLists(profile).mapValues { it.value.size }
            learnSession(events, profile, kin, views)
            for ((name, list) in timingLists(profile)) views.ranges.getOrPut(name, ::ArrayList) += before.getValue(name) until list.size
        }
        kin.settle(profile)
        learnMotor(views, profile.motor)
        learnPolicy(views, profile)
        learnTiming(views, profile)
        profile.glances = learnGlances(views, profile.motor)
        profile.cap()
        return profile
    }

    /** The timing lists whose order is learned ([BehaviorProfile.Timing]), by name. */
    private fun timingLists(p: Profile) = linkedMapOf(
        "biteReactionMs" to p.biteReactionMs, "catchRecastMs" to p.catchRecastMs, "afterKillMs" to p.afterKillMs, "firstHitMs" to p.firstHitMs, "rodToCastMs" to p.rodToCastMs
    )

    /** What the view analysis gathers over all recordings before it becomes [Motor], [BehaviorProfile.Policy] and [BehaviorProfile.Timing]. */
    private class ViewLog {
        /** Movements not cut by a recording's end, every amplitude, in order. */
        val moves = ArrayList<ViewMotion.Move>()
        /** Gaps between separate movements (the first 6° or more, the next 3° or more). */
        val gaps = ArrayList<Double>()
        var restTicks = 0
        var restChanged = 0
        var restFollow = 0
        var restBoth = 0
        var restYaw = 0
        val restSteps = ArrayList<Double>()
        /** Grid steps of each axis of a rest nudge ([MIN_REST_AXES]): your hand's, and the yaw's near the pole. */
        val restHand = ArrayList<Int>()
        val restPole = ArrayList<Int>()
        var fightTicks = 0
        var fightStill = 0

        val all = TurnLog()
        val classes = LinkedHashMap<String, TurnLog>()
        fun turns(cls: String) = classes.getOrPut(cls, ::TurnLog)
        var turnFights = 0
        var ticksTurned = 0
        var changedTurned = 0
        var ticksOther = 0
        var changedOther = 0
        /** Corrective movements: the class of the creature, the error at their start, the creature's angular half-size then. */
        val corrections = ArrayList<Triple<String, Double, Double>>()
        /** Every creature counted for a turn, for the looks ([BehaviorProfile.Looks]). */
        val looks = ArrayList<LookSample>()
        /** Your movements that turned toward a counted creature: end / start error of the creature each ended nearest. */
        val lookStop = ArrayList<Double>()

        var backFights = 0
        var backBeyond = 0
        var backTurned = 0
        var backInterrupted = 0
        var backNone = 0
        /** Of the fights ending more than 8° away, those that started at your fishing view, and of those: turned back, cut short. */
        var backHome = 0
        var backHomeTurned = 0
        var backHomeInterrupted = 0
        val backLatency = ArrayList<Double>()
        val backRest = ArrayList<Double>()
        val backCastErr = ArrayList<Double>()
        val backCastLag = ArrayList<Double>()

        /** Your outward looks of every recording ([GlanceMeasure]), minutes in place per phase (and after a death of yours), all your moves of 6° or more by kind. */
        val glances = ArrayList<GlanceMeasure.Look>()
        val glanceMinutes = DoubleArray(Glances.PHASES.size)
        val glanceDeathMinutes = DoubleArray(Glances.PHASES.size)
        var glanceRotMinutes = 0.0
        val glanceKinds = LinkedHashMap<String, Int>()
        /** Your area weapon:button classes over all recordings ([GlanceMeasure.areaClasses]). */
        var glanceArea: Set<String> = emptySet()
        /** The first sub-stroke's share of your turn backs ([Motor.backFirstN]) and your other ways back ([Motor.returnFirstN]) of 20° or more in two strokes. */
        val backFirst = ArrayList<Double>()
        val returnFirst = ArrayList<Double>()

        /** Timing list name -> the index range each recording added. */
        val ranges = HashMap<String, MutableList<IntRange>>()
        /** Your rod melee click gaps, one list per run of them in a fight. */
        val meleeRuns = ArrayList<List<Int>>()
    }

    /**
     * A creature counted for a turn: its class, its error and angular half-size 300 ms after the spawn, whether it was
     * right overhead, its life, the age your first turn toward it started at, and how long after that turn's end you
     * turned back toward the view it started from (both -1: none).
     */
    private class LookSample(val cls: String, val e300: Double, val box300: Double, val overhead: Boolean, val life: Long, val firstAge: Long, val back: Long, val backGap: Long = - 1L)

    private class TurnLog {
        var creatures = 0
        var turned = 0
        val swapLatency = ArrayList<Double>()
        val spawnLatency = ArrayList<Double>()
        val stopErr = ArrayList<Double>()
        val corrStart = ArrayList<Double>()
        val corrEnd = ArrayList<Double>()
        val corrGap = ArrayList<Double>()
        val slack = ArrayList<Double>()
    }

    /**
     * Who splits off whom, over every recording at once. A child's parent is the creature it came up after at least
     * [PARENT_DEATHS] deaths of, and [PARENT_SHARE] of all the deaths of yours of that kind; with more than one such
     * creature, the one with the most such deaths. One creature that came up next to yours dying by chance (someone
     * else's, a Corrupted catch an older recording could not name) is no parent: the killer holds Auto Fishing after every
     * death of a habit's parent, waiting for children that never come.
     */
    private class Kinship {
        /** Deaths of yours (not lost from view) by creature name. */
        val deaths = HashMap<String, Int>()
        /** Child name -> parent name -> the parent's deaths it came up after (session << 32 | entity id). */
        val after = HashMap<String, HashMap<String, MutableSet<Long>>>()

        fun child(name: String, parent: String, death: Long) {
            after.getOrPut(name, ::HashMap).getOrPut(parent, ::HashSet) += death
        }

        /** Sets [Habit.parent] and [Habit.group] of every child whose parent the recordings show often enough. */
        fun settle(profile: Profile) {
            for ((name, parents) in after) {
                val habit = profile.habits[name] ?: continue
                // a creature never splits into its own kind: a baby that came up as another baby died next to it is the
                // big one's
                val parent = parents.entries
                    .filter { (p, seen) -> p != name && seen.size >= PARENT_DEATHS && seen.size >= PARENT_SHARE * (deaths[p] ?: 0) }
                    .maxWithOrNull(compareBy<Map.Entry<String, MutableSet<Long>>>({ it.value.size }, { it.key }))?.key ?: continue
                habit.parent = parent
                habit.group = parentGroup(profile, parent)
            }
        }
    }

    /**
     * The events of a recording in file order, null when it is none (its first line is no session line) or one to skip.
     * [keep] looks at a raw line before it is parsed, so a pass that needs few kinds of lines parses only those.
     */
    private fun readEvents(file: File, skipRecorderBuild: Boolean, keep: (String) -> Boolean = { true }): List<Ev>? {
        val events = ArrayList<Ev>()
        file.useLines { lines ->
            for (line in lines) {
                if (events.isNotEmpty() && ! keep(line)) continue
                val o = runCatching { JsonParser.parseString(line).asJsonObject }.getOrNull() ?: continue
                val type = o.str("e")
                val ev = if (type == "rot") Ev("rot", o.long("t"), NO_FIELDS, GlanceMeasure.line(o)) else Ev(type, o.long("t"), o)
                // a bot trace ([FishingRecorder] Trace) shows the killer's moves, never yours
                if (events.isEmpty() && (ev.type != "session" || skipRecorderBuild && o.str("build") == FishingRecorder.RECORDER_BUILD || o.bool("bot"))) return null
                events += ev
            }
        }
        return events.ifEmpty { null }
    }

    private class CastCount {
        var casts = 0
        /** Presses and held repeats. */
        var rights = 0
    }

    /**
     * The items you fish with: at least [MIN_CASTS] casts made by their own clicks, and casts on at least 10 % of their
     * right clicks. Never the item you held at a reel or cast line (the Flay is held at many). With no such item at all
     * (a tiny recording), every fishing-rod item that is no rod-type weapon.
     */
    private fun fishingRodsOf(files: List<File>, skipRecorderBuild: Boolean): MutableSet<String> {
        val counts = HashMap<String, CastCount>()
        val rodItems = LinkedHashSet<String>()
        for (file in files) {
            val events = readEvents(file, skipRecorderBuild) { "click" in it || "cast" in it || "slot" in it } ?: continue
            // newer recordings mark the click that cast; in older ones it is your last right click shortly before the cast
            val marked = events.any { it.type == "click" && it.o.has("cast") }
            var lastRight: Ev? = null
            for (ev in events) when (ev.type) {
                "slot" -> if (ev.o.bool("rod")) rodItems += ev.o.str("item")
                "click" -> {
                    val item = ev.o.str("item")
                    if (ev.o.bool("rod")) rodItems += item
                    if (ev.o.str("b") != "R") continue
                    val count = counts.getOrPut(item, ::CastCount)
                    count.rights ++
                    if (marked && ev.o.bool("cast")) count.casts ++
                    lastRight = ev
                }
                "cast" -> if (! marked) {
                    lastRight?.takeIf { ev.t - it.t in 0 .. CAST_CLICK_MS }?.let { counts.getValue(it.o.str("item")).casts ++ }
                    lastRight = null
                }
            }
        }
        val rods = counts.filter { (id, c) -> id !in WeaponPlanner.NOT_FISHING_RODS && c.casts >= MIN_CASTS && c.casts >= c.rights * MIN_CAST_SHARE }.keys
        return LinkedHashSet(rods.ifEmpty { rodItems.filter { it !in WeaponPlanner.NOT_FISHING_RODS } })
    }

    /** Your area weapon:button classes from the clicks of all [files] ([GlanceMeasure.areaClasses]): one recording has too few clicks of a class to tell. */
    private fun areaClassesOf(files: List<File>, skipRecorderBuild: Boolean): Set<String> {
        val clicks = ArrayList<GlanceMeasure.Click>()
        for (file in files) {
            val events = readEvents(file, skipRecorderBuild) { "click" in it } ?: continue
            for (ev in events) {
                if (ev.type != "click" || ev.o.bool("cast") || ev.o.bool("auto") || ! ev.o.has("err")) continue
                clicks += GlanceMeasure.Click(ev.t, ev.o.str("item"), ev.o.str("b"), ev.o.bool("hold"), ev.o.double("err"))
            }
        }
        return GlanceMeasure.areaClasses(clicks)
    }

    private class Fight(val id: Int, val name: String, val t: Long, val spawn: JsonObject?) {
        var deathAt = 0L
        /** Left behind by a world change (warp, lobby swap) or lost by the tracker: over, but not a kill. */
        var goneAt = 0L
        val clicks = mutableListOf<Ev>()
        /** Your recast click whose bobber killed it. */
        var bobber: Ev? = null
        /** Killed by your swing at another of your creatures or at nothing (that swing is its only click). */
        var swept = false
        /** When it stopped being around (a death or a world change). */
        val end get() = if (deathAt > 0) deathAt else if (goneAt > 0) goneAt else Long.MAX_VALUE
    }

    /**
     * One tick of your view: [dir] is the unit view vector, [x], [y], [z] where you stood (your feet), [creatures] id -> x,
     * y, z, height, width (-1 when no mob was linked); [tick] the client tick counter (-1 in older recordings), [tp] a
     * teleport line.
     */
    private class RotSample(
        val t: Long, val dir: DoubleArray, val x: Double, val y: Double, val z: Double, val pitch: Double, val creatures: Map<Int, DoubleArray>,
        val yaw: Double = 0.0, val tick: Long = - 1, val tp: Boolean = false
    )

    /** What a session's lines say about every creature, yours or not, for the weapon analysis after the line loop. */
    private class SessionLog {
        val spawns = mutableListOf<Ev>()
        /** Yours seen again after the tracker stopped ("kept"): no new creature, nothing of them is learned in this file. */
        val kept = HashSet<Int>()
        val hp = HashMap<Int, MutableList<Ev>>()
        val deaths = HashMap<Int, MutableList<Ev>>()
        val worlds = mutableListOf<Long>()
        /** Children the recorder already named (a `parent` on their spawn or own line): id -> its spawn line, parent. */
        val namedChildren = LinkedHashMap<Int, Pair<Ev, String>>()
        val clicks = mutableListOf<Ev>()
        val hotbar = LinkedHashSet<String>()
    }

    private fun learnSession(events: List<Ev>, profile: Profile, kin: Kinship, views: ViewLog) {
        profile.sessions ++
        profile.minutes += events.last().t / 60000.0
        val rods = profile.fishingRods

        val fights = linkedMapOf<Int, Fight>()
        val spawns = HashMap<Int, Ev>()
        val rots = mutableListOf<RotSample>()
        val log = SessionLog()
        // a catch line comes from chat, usually just before the tick that writes its reel
        val catchTimes = events.filter { it.type == "catch" }.map { it.t }
        fun hadCatch(t: Long) = catchTimes.any { abs(it - t) <= CATCH_REEL_MS }
        fun addFight(id: Int, spawn: Ev) {
            if (id in fights) return
            val name = spawn.o.str("name")
            fights[id] = Fight(id, name, spawn.t, spawn.o)
            profile.habits.getOrPut(name) { Habit() }.seen ++
        }
        var lastBite = - 1L
        /** Your click that reeled in after a bite, waiting for your recast click (both are your own clicks, so no ping is learned). */
        var reelClickAt = - 1L
        /** Your last slot change, menu, command or left click: a recast after a catch made after one of them is not your rhythm. */
        var lastBreakAt = - 1L
        /** When you took the rod, waiting for your cast. */
        var rodSlotAt = - 1L
        /** Your rod right click waiting for the cast it may have made. */
        var castCandidate: Ev? = null
        /** Your rod right clicks that cast (presses and held repeats). */
        val castClicks = mutableListOf<Ev>()
        val leftClicks = mutableListOf<Ev>()
        /** Your clicks that name none of your creatures (abilities aimed elsewhere, out-of-reach swings). */
        val freeClicks = mutableListOf<Ev>()

        for (ev in events) {
            when (ev.type) {
                "bite" -> {
                    lastBite = ev.t
                    reelClickAt = - 1L
                }
                "catch" -> profile.ownCatches ++
                "cast" -> {
                    castCandidate?.let { if (ev.t - it.t <= CAST_CLICK_MS) castClicks += it }
                    castCandidate = null
                }
                "spawn" -> {
                    val id = ev.o.int("id")
                    // one of yours seen again after the tracker stopped ("kept") came up earlier: no new creature at this
                    // line, no fight starts, and its clicks are no sample of a weapon
                    if (ev.o.bool("kept")) {
                        log.kept += id
                        continue
                    }
                    spawns[id] = ev
                    log.spawns += ev
                    // a creature split off one of yours is yours to fight, but no catch
                    val parent = ev.o.str("parent")
                    if (parent.isNotEmpty() && ev.o.bool("own")) log.namedChildren[id] = ev to parent
                    else if (ev.o.bool("own")) addFight(id, ev)
                }
                // ownership decided after the spawn: a late catch message, or a reel-spot claim given back
                "own" -> {
                    val id = ev.o.int("id")
                    val parent = ev.o.str("parent")
                    if (id in log.kept) Unit
                    else if (ev.o.bool("own") && parent.isNotEmpty()) spawns[id]?.let { log.namedChildren[id] = it to parent }
                    else if (ev.o.bool("own")) spawns[id]?.let { addFight(id, it) }
                    else {
                        log.namedChildren.remove(id)
                        fights.remove(id)?.let { profile.habits[it.name]?.let { h -> h.seen -- } }
                    }
                }
                "hp" -> log.hp.getOrPut(ev.o.int("id")) { mutableListOf() } += ev
                // a creature the tracker lost without seeing it die (gone out of range) is over, not killed
                "death" -> {
                    log.deaths.getOrPut(ev.o.int("id")) { mutableListOf() } += ev
                    fights[ev.o.int("id")]?.let {
                        val gone = ev.o.str("cause") == "gone"
                        if (gone && it.goneAt == 0L) it.goneAt = ev.t
                        if (! gone && it.deathAt == 0L) it.deathAt = ev.t
                    }
                }
                // the tracker forgets every creature on a world change without a death
                "world" -> {
                    log.worlds += ev.t
                    fights.values.forEach { if (it.deathAt == 0L && it.goneAt == 0L) it.goneAt = ev.t }
                    lastBite = - 1L
                    reelClickAt = - 1L
                }
                "hotbar" -> (ev.o.get("items") as? JsonArray)?.forEach { e ->
                    e.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString?.takeIf { it.isNotEmpty() }?.let(log.hotbar::add)
                }
                "slot", "menu", "menuClose", "cmd" -> {
                    lastBreakAt = ev.t
                    if (ev.type == "slot") rodSlotAt = if (ev.o.str("item") in rods) ev.t else - 1L
                }
                // your own clicks with a fishing rod are reels and casts, not attacks: the bite reaction, then the recast
                // after the reel (a rod-type weapon like the Flay is no fishing rod: its right clicks are attacks)
                "click" -> if (ev.o.str("b") == "R" && ev.o.str("item") in rods) {
                    if (! ev.o.bool("auto")) log.clicks += ev
                    val auto = ev.o.bool("auto")
                    val hold = ev.o.bool("hold")
                    // your first rod click after taking the rod (back from a weapon or a menu) casts
                    if (! auto && rodSlotAt >= 0 && ev.t - rodSlotAt in 1 .. REACTION_MS) profile.rodToCastMs += (ev.t - rodSlotAt).toInt()
                    rodSlotAt = - 1L
                    // newer recordings mark the click that casts; in older ones it is the click right before a cast
                    if (! auto && ev.o.bool("cast")) castClicks += ev
                    castCandidate = ev.takeUnless { auto || ev.o.bool("cast") }
                    if (auto) {
                        // Auto Fishing used up this bite and its reel
                        lastBite = - 1L
                        reelClickAt = - 1L
                    }
                    // a held use key repeats every 4 ticks: never your reaction to a bite, but your recast once you reeled in
                    else if (! hold && lastBite >= 0 && ev.t - lastBite in 1 .. 2000) {
                        profile.biteReactionMs += (ev.t - lastBite).toInt()
                        lastBite = - 1L
                        reelClickAt = ev.t
                    }
                    else if (reelClickAt >= 0) {
                        val gap = ev.t - reelClickAt
                        if (gap in 1 .. MAX_RECAST_MS) {
                            if (! hadCatch(reelClickAt)) profile.castDelayMs += gap.toInt()
                            // after a catch only a recast with nothing else in between is your rhythm, not one after a fight
                            else if (lastBreakAt < reelClickAt) {
                                profile.catchRecastMs += gap.toInt()
                                profile.recasts ++
                                if (hold) profile.holdRecasts ++
                            }
                        }
                        reelClickAt = - 1L
                    }
                }
                else if (! ev.o.bool("auto")) {
                    log.clicks += ev
                    val target = ev.o.intOrNull("target") ?: ev.o.intOrNull("near")?.takeIf { ev.o.str("b") == "R" && ev.o.double("err") < 25.0 }
                    val fight = target?.let { fights[it] }
                    // a click on another entity (someone else's creature, an NPC) is never one of yours
                    // nor one meant for yours seen again after the tracker stopped (kept): no free click to credit elsewhere
                    if (fight != null) fight.clicks += ev else if (! ev.o.has("target") && target !in log.kept) freeClicks += ev
                    if (ev.o.str("b") == "L") {
                        leftClicks += ev
                        lastBreakAt = ev.t
                        countMiss(ev, profile)
                        // where you aim, from hits on the side of the box only: a hit from inside it (dist 0) or on its top
                        // or bottom face (ay 1 or 0) says nothing about it
                        val ay = ev.o.double("ay")
                        if (ev.o.has("target") && ev.o.double("dist") > 0.01 && ay > 0.02 && ay < 0.98) {
                            profile.aimHeights += ay.toFloat()
                            profile.meleeDistances += ev.o.double("dist").toFloat()
                        }
                    }
                    // a held repeat is no aim of yours
                    if (ev.o.str("b") == "R" && ev.o.has("near") && ! ev.o.bool("hold")) profile.rightClickErr += ev.o.double("err").toFloat()
                }
                "rot" -> ev.rot?.let { r -> rots += RotSample(r.t, viewDir(r.yaw, r.pitch), r.x, r.y, r.z, r.pitch, r.creatures, r.yaw, r.tick, r.tp) }
            }
        }

        learnMenus(events, profile)
        learnSwaps(events, fights, profile)
        learnCloseToClick(events, fights, profile)
        learnFishingLoadouts(events, fights, profile)

        // what killed a creature without a left click of yours that could have: the bobber of your recast; one that died
        // without any click of its own right after your swing at another of your creatures (or at nothing) was swept along
        for (fight in fights.values) {
            val death = fight.deathAt.takeIf { it > 0 } ?: continue
            val cast = castClicks.lastOrNull { it.t >= fight.t - BOBBER_EARLY_MS && death - it.t in BOBBER_DEATH_MS }
            val swings = leftClicks.filter { death - it.t in KILL_CLICK_MS }
            if (swings.isEmpty()) fight.bobber = cast
            else if (fight.clicks.isEmpty() && cast == null) {
                val sweep = swings.lastOrNull { it.o.str("item") !in rods && it.o.intOrNull("target").let { id -> id == null || id in fights } } ?: continue
                fight.clicks += sweep
                fight.swept = true
            }
        }

        // a fight that died without a single click naming it, while no other of your creatures was alive: the clicks you
        // made meanwhile were for it (an ability aimed at your feet, swings from just out of reach)
        for (fight in fights.values) {
            if (fight.clicks.isNotEmpty() || fight.deathAt == 0L || fight.bobber != null) continue
            // an area attack killing a pile of creatures at once (barn fishing): the clicks right before the death
            val killing = freeClicks.filter { it.t in fight.deathAt - KILLING_CLICK_MS .. fight.deathAt }
            if (killing.isNotEmpty()) {
                fight.clicks += killing
                continue
            }
            if (fight.deathAt - fight.t > QUICK_DEATH_MS) continue
            val alone = fights.values.none { o -> o !== fight && o.t < fight.deathAt && o.end > fight.t }
            if (alone) fight.clicks += freeClicks.filter { it.t > fight.t && it.t <= fight.deathAt }
        }

        val sessionEnd = events.last().t
        for (fight in fights.values) {
            val habit = profile.habits.getOrPut(fight.name) { Habit() }
            if (habit.group.isEmpty()) habit.group = groupOf(fight.name)
            val bobber = fight.bobber
            // your first hit: your first click on it, or the recast whose bobber killed it
            val firstHit = fight.clicks.firstOrNull() ?: bobber
            if (firstHit == null) {
                // without your clicks: dead soon = killed somehow; around (or gone later) for a minute = left alone;
                // seen for less than a minute = too short to tell
                val lived = (if (fight.end == Long.MAX_VALUE) sessionEnd else fight.end) - fight.t
                if (fight.deathAt > 0 && lived <= QUICK_DEATH_MS) habit.diedUnfought ++
                else if (lived >= QUICK_DEATH_MS) habit.leftAlone ++
                continue
            }
            habit.fought ++
            if (bobber != null) {
                habit.bobberKills ++
                profile.bobberDeathMs += (fight.deathAt - bobber.t).toInt()
            }
            if (fight.swept) habit.sweepKills ++

            val first = fight.clicks.firstOrNull()
            if (first != null) {
                // spawn to your first rod hit (only rod fights read it; weapon fights wait for their swap and loadout)
                if (first.o.str("item") in rods && first.o.str("b") == "L") profile.firstHitMs += (first.t - fight.t).toInt()
                fight.clicks.forEachIndexed { i, click ->
                    // holding the use key is one right click of yours, however many times it repeats
                    val prev = fight.clicks.getOrNull(i - 1)
                    if (click.o.bool("hold") && prev != null && prev.o.str("b") == "R" && prev.o.str("item") == click.o.str("item") && click.t - prev.t <= HOLD_REPEAT_MS) return@forEachIndexed
                    if (click.o.str("b") == "R") habit.right ++ else habit.left ++
                    if (click.o.str("item") in rods) habit.rodClicks ++
                }
                // once per fight: a weapon held down for seconds is no more yours than one swung twice
                fight.clicks.mapTo(LinkedHashSet()) { it.o.str("item") }.filter { isWeaponItem(it, rods) }.forEach { habit.weapons.merge(it, 1, Int::plus) }
                // your rod melee rhythm: runs of rod hit gaps, broken by anything else
                var rodRun = mutableListOf<Int>()
                fun endRodRun() {
                    if (rodRun.size >= 2) views.meleeRuns += rodRun
                    rodRun = mutableListOf()
                }
                fight.clicks.zipWithNext().forEach { (a, b) ->
                    val gap = b.t - a.t
                    if (gap !in 30 .. 1500 || a.o.str("b") != b.o.str("b")) {
                        endRodRun()
                        return@forEach
                    }
                    if (a.o.str("b") == "R") {
                        endRodRun()
                        // a hold's repeats come at the game's pace, not yours
                        if (! a.o.bool("hold") && ! b.o.bool("hold")) profile.clickRightMs += gap.toInt()
                    }
                    else {
                        profile.clickLeftMs += gap.toInt()
                        // rod hits and weapon swings have a pace of their own; a gap with a swap in it has neither
                        val aRod = a.o.str("item") in rods
                        val bRod = b.o.str("item") in rods
                        if (aRod == bRod) (if (bRod) profile.clickLeftRodMs else profile.clickLeftWeaponMs).add(gap.toInt())
                        if (aRod && bRod) rodRun += gap.toInt() else endRodRun()
                    }
                }
                endRodRun()

                // the loadout you equipped between the catch and your first hit
                events.filter { it.type == "menuClick" && it.t in fight.t - 2000 .. first.t }.forEach { click ->
                    loadoutOf(click)?.let { habit.loadouts.merge(it, 1, Int::plus) }
                }

                // a creature that survived your recast: how long after it you hit it first
                val firstLeft = fight.clicks.firstOrNull { it.o.str("b") == "L" }
                val recast = firstLeft?.let { l -> castClicks.firstOrNull { it.t >= fight.t - BOBBER_EARLY_MS && it.t < l.t } }
                // rod hits only: a weapon's first swing comes after its swap (timed by swapReactionMs)
                if (bobber == null && firstLeft != null && firstLeft.o.str("item") in rods && recast != null && firstLeft.t - recast.t <= REACTION_MS) profile.bobberMissMs += (firstLeft.t - recast.t).toInt()
            }

            if (fight.deathAt > 0) {
                habit.killed ++
                profile.kills ++
                habit.killMs += (fight.deathAt - firstHit.t).toInt()
                // back to the rod (or your own recast click) after the last creature you fought died: the first thing
                // that happens next must be yours, not Auto Fishing's cast or the next catch
                val othersAlive = fights.values.any { o ->
                    o !== fight && o.clicks.isNotEmpty() && o.t < fight.deathAt && (o.end > fight.deathAt || o.end == fight.deathAt && o.id > fight.id)
                }
                // with the bobber still out (or recast before the death) you never left fishing: there is no way back to time
                val hookOut = bobber != null || events.lastOrNull { it.t <= fight.deathAt && (it.type == "cast" || it.type == "reel") }?.type == "cast"
                val next = if (othersAlive || hookOut) null else events.firstOrNull { it.t > fight.deathAt && afterKillMarker(it, rods) != null }
                if (next != null && afterKillMarker(next, rods) == true && next.t - fight.deathAt <= 8000) profile.afterKillMs += (next.t - fight.deathAt).toInt()
                if (! othersAlive) learnRestore(fight, events, profile)
            }

            learnTurn(fight, rots, profile)
        }

        learnWeapons(fights, rots, log, profile, kin)
        log.hotbar.forEach { profile.hotbarItems.merge(it, 1, Int::plus) }
        learnViews(events, rots, views)
        measureGlances(events, profile, views)
    }

    /** One recording's looks around ([GlanceMeasure]): a weapon is an item you clicked your creatures with that is none of your rods, mobility items or tools. */
    private fun measureGlances(events: List<Ev>, profile: Profile, views: ViewLog) {
        val rods = profile.fishingRods
        val weapons = profile.weaponStyles.keys.filterTo(HashSet()) { isWeaponItem(it, rods) }
        val lines = events.mapNotNull { e -> e.o.takeIf { e.rot == null } }
        val r = GlanceMeasure.measure(GlanceMeasure.read(lines, rods, { it in weapons }, views.glanceArea, events.mapNotNull { it.rot }))
        views.glances += r.looks
        for (p in Glances.PHASES.indices) {
            views.glanceMinutes[p] += r.minutes[p]
            views.glanceDeathMinutes[p] += r.deathMinutes[p]
        }
        views.glanceRotMinutes += r.rotMinutes
        r.moves.forEach { views.glanceKinds.merge(it.kind.name.lowercase(), 1, Int::plus) }
        views.backFirst += WaysBack.firstShares(r.moves, WaysBack.TURN_BACKS)
        views.returnFirst += WaysBack.firstShares(r.moves, WaysBack.FROM_LOOKS)
    }

    /**
     * Your ways back as the motor plans them, pure so they can be unit tested: your turn backs ([Motor.backFirstMu], back
     * from a turn toward a creature) apart from your ways back from a look ([Motor.returnFirstMu], to where it or the
     * fight started); the killer's ways back take the hand of the kind the learner finds them to be
     * ([HumanAim.wayBackParamsOf]). Few of a kind say little on their own (yours: 8 turn backs, 5 from a look), so each
     * kind's median is a hierarchical estimate ([estimates]): drawn towards the median of all your ways back by how far the
     * kinds' medians lie apart against how far a median of so few spreads (the spread of the kind's own first shares), and
     * that one towards the reference recordings' by the ways back each was measured from.
     */
    internal object WaysBack {
        /** What [GlanceMeasure] finds turning back to where a turn toward a creature, a look or the fight started. */
        val KINDS = setOf(GlanceMeasure.Kind.TURN_BACK, GlanceMeasure.Kind.RETURN, GlanceMeasure.Kind.BACK_TO_FIGHT)
        /** Back from a turn toward a creature. */
        val TURN_BACKS = setOf(GlanceMeasure.Kind.TURN_BACK)
        /** Back from a look: to where it started, or to where the fight started. */
        val FROM_LOOKS = setOf(GlanceMeasure.Kind.RETURN, GlanceMeasure.Kind.BACK_TO_FIGHT)
        /** The ways back of 20° or more in two strokes the reference median ([REF_BACK_P50]) was measured from. */
        const val REF_BACK_N = 13
        /** A median of n values spreads about this many times their variance over n (a normal's: π / 2). */
        private const val MEDIAN_VARIANCE = PI / 2.0

        /** The first sub-stroke's share of each way back of [kinds] among [moves] of 20° or more in two strokes, as [Motor.firstShareP50] counts all of them. */
        fun firstShares(moves: List<GlanceMeasure.Measured>, kinds: Set<GlanceMeasure.Kind> = KINDS) =
            moves.filter { it.kind in kinds && it.move.amp >= 20 && it.move.strokes.size == 2 }.map { it.move.strokes[0].amp / it.move.amp }

        /**
         * The median first share of each kind of your ways back in [kinds] as estimated together: the median of all of them,
         * drawn towards the reference's by the ways back each was measured from (a median of the same spread counts by its
         * number), and each kind's own drawn towards that by w = τ² / (τ² + v): v how far its median spreads with its count
         * (π / 2 times its shares' variance over their number), τ² how far the kinds' medians lie apart beyond what their own
         * spread explains (the method of moments, never below 0). Kinds that lie apart no more than chance says are one hand;
         * few of a kind that lie far apart keep most of their own. A kind of fewer than two (no spread to tell its median's
         * by) is the pooled one; with fewer than two such kinds, every kind is.
         */
        fun estimates(kinds: List<List<Double>>): List<Double> {
            val all = kinds.flatten()
            if (all.isEmpty()) return kinds.map { REF_BACK_P50 }
            val pooled = median(all)
            val prior = (all.size * pooled + REF_BACK_N * REF_BACK_P50) / (all.size + REF_BACK_N)
            val learned = kinds.filter { it.size >= 2 }
            if (learned.size < 2) return kinds.map { prior }
            fun v(xs: List<Double>) = MEDIAN_VARIANCE * variance(xs) / xs.size
            val apart = learned.sumOf { (median(it) - pooled).let { d -> d * d } } / (learned.size - 1)
            val tau2 = max(0.0, apart - learned.sumOf { v(it) } / learned.size)
            return kinds.map { xs ->
                if (xs.size < 2) prior
                else {
                    val vk = v(xs)
                    val w = if (tau2 + vk <= 0.0) 1.0 else tau2 / (tau2 + vk)
                    prior + w * (median(xs) - prior)
                }
            }
        }

        private fun median(xs: List<Double>) = ViewMotion.quantile(xs, 0.5)

        private fun variance(xs: List<Double>): Double {
            if (xs.size < 2) return 0.0
            val m = xs.average()
            return xs.sumOf { (it - m) * (it - m) } / (xs.size - 1)
        }

        /** The planned first strokes ([backFirstMuOf]) of your turn backs ([back]) and your ways back from a look ([fromLooks]), estimated together ([estimates]). */
        fun plans(back: List<Double>, fromLooks: List<Double>): Pair<Double, Double> {
            val (b, l) = estimates(listOf(back, fromLooks))
            return backFirstMuOf(b) to backFirstMuOf(l)
        }
    }

    /**
     * [Glances] from your outward looks of all recordings: the measured statistics, then the planning parameters
     * (looks/SPEC §3.2.5) once there are [Glances.MIN_LOOKS] looks in [Glances.MIN_MINUTES] minutes in place, else every
     * rate stays 0. A phase's rate is shrunk toward the pooled one; in a fight that needs your aim only looks from your
     * fishing view count (the killer looks there only before its first turn). The motion parameters compare the looks'
     * strokes and durations with all your moves' ([motor]), from [Glances.MIN_MOTION] looks on.
     */
    private fun learnGlances(views: ViewLog, motor: Motor): Glances {
        val g = Glances()
        val looks = views.glances
        val n = Glances.PHASES.size
        g.looks = looks.size
        g.minutes = views.glanceMinutes.toMutableList()
        g.deathMinutes = views.glanceDeathMinutes.toMutableList()
        g.rotMinutes = views.glanceRotMinutes
        g.kinds = LinkedHashMap(views.glanceKinds)
        for (l in looks) {
            val p = l.phase.ordinal
            g.counts[p] ++
            if (l.home) g.homeCounts[p] ++
            if (l.deathBoosted) g.deathCounts[p] ++
            if (l.home) {
                if (l.risk) g.homeRisk ++
                if (l.returned) g.homeReturned ++
            }
            else {
                if (l.risk) g.awayRisk ++
                if (l.returned) g.awayReturned ++
            }
            if (l.chainGapMs >= 0) g.chained ++
            if (l.deadCandidate) {
                g.deadSpotCandidates ++
                if (l.deadHit) g.deadSpotHits ++
                g.deadSpotChance += l.deadChance
            }
        }
        val kept = looks.takeLast(Glances.MAX_SAMPLES)
        g.amp = kept.mapTo(ArrayList()) { it.amp.toFloat() }
        g.dYaw = kept.mapTo(ArrayList()) { it.dYaw.toFloat() }
        g.dPitch = kept.mapTo(ArrayList()) { it.dPitch.toFloat() }
        g.startPitch = kept.mapTo(ArrayList()) { it.startPitch.toFloat() }
        g.dwellMs = kept.filter { it.dwellMs >= 0 }.mapTo(ArrayList()) { it.dwellMs.toInt() }
        g.returnErr = kept.filter { it.returnErr.isFinite() }.mapTo(ArrayList()) { it.returnErr.toFloat() }
        g.chainGapMs = kept.filter { it.chainGapMs >= 0 }.mapTo(ArrayList()) { it.chainGapMs.toInt() }

        fun law(amp: Double) = max(1.0, motor.moveLawA + motor.moveLawB * x(amp))
        val timed = looks.filter { it.dur > 0 }
        if (timed.isNotEmpty()) {
            val ratios = timed.map { it.dur / law(it.amp) }
            g.multiShare = looks.count { it.strokes >= 2 }.toDouble() / looks.size
            g.multiExpected = looks.map { motor.multiShare.getOrElse(motor.bin(it.amp).coerceAtLeast(0)) { 0.0 } }.average()
            g.slowShare = ratios.count { it > 2 }.toDouble() / ratios.size
            g.lnMean = ratios.map(::ln).average()
            g.lnSd = sd(ratios.map(::ln))
            // against one law for everyone, so two profiles' looks compare
            val ref = Motor()
            g.lawP50 = ViewMotion.quantile(timed.map { it.dur / (ref.moveLawA + ref.moveLawB * x(it.amp)) }, 0.5)
        }
        looks.filter { it.amp >= 20 }.takeIf { it.isNotEmpty() }?.let { big -> g.overShare = big.count { it.over > 1 }.toDouble() / big.size }

        val total = g.minutes.sum()
        if (looks.size < Glances.MIN_LOOKS || total < Glances.MIN_MINUTES) return g
        val pooled = looks.size / total
        for (p in 0 until n) {
            val count = if (p == GlancePolicy.Phase.FIGHT_AIM.ordinal) g.homeCounts[p] else g.counts[p]
            g.rate[p] = Glances.rateOf(count, g.minutes[p], pooled)
        }
        if (g.homeRisk >= Glances.MIN_RISK) g.returnHomeP = (g.homeReturned.toDouble() / g.homeRisk).coerceIn(0.3, 1.0)
        if (g.awayRisk >= Glances.MIN_RISK) g.returnAwayP = (g.awayReturned.toDouble() / g.awayRisk).coerceIn(0.0, 0.6)
        g.chainP = (g.chained.toDouble() / looks.size).coerceIn(0.0, 0.5)
        val deathLooks = g.deathCounts.sum()
        val deathMin = g.deathMinutes.sum()
        if (deathLooks >= Glances.MIN_DEATH_LOOKS && deathMin > 0.0 && total > deathMin && looks.size > deathLooks)
            g.deathBoost = ((deathLooks / deathMin) / ((looks.size - deathLooks) / (total - deathMin))).coerceIn(1.0, 4.0)
        if (g.deadSpotCandidates >= Glances.MIN_DEAD_CANDIDATES) g.deadSpotP = ((g.deadSpotHits - g.deadSpotChance) / looks.size).coerceIn(0.0, 0.3)
        if (looks.size >= Glances.MIN_MOTION) {
            g.multiScale = (g.multiShare / g.multiExpected).coerceIn(0.05, 1.0)
            g.slowScale = (g.slowShare / motor.slowShare).coerceIn(0.05, 1.0)
            // against all your moves' ln-mean under the same law
            val all = views.moves.filter { it.amp >= 6 && it.dur > 0 }.map { ln(it.dur / law(it.amp)) }
            if (all.isNotEmpty()) g.durationFactor = exp(g.lnMean - all.average()).coerceIn(0.5, 1.5)
        }
        return g
    }

    /** An item that can be the weapon of a fight: not your fishing rod, not a mobility item or fishing tool, not your fist. */
    private fun isWeaponItem(item: String, rods: Set<String>) =
        item.isNotEmpty() && item !in rods && item !in WeaponPlanner.MOBILITY && item !in WeaponPlanner.NOT_WEAPONS

    /**
     * Your left click [ev] counted for the miss share ([Profile.missShare]) when one of your creatures was within
     * [Profile.MISS_NEAR]: with a rod you fish with or a weapon (as the killer tells them apart), a miss when it hit air
     * or a block. Swings with a tool or nothing in hand are none of the killer's.
     */
    private fun countMiss(ev: Ev, profile: Profile) {
        if (! ev.o.has("near") || ev.o.double("nearDist") > Profile.MISS_NEAR) return
        val item = ev.o.str("item")
        val rod = item in profile.fishingRods
        if (! rod && ! isWeaponItem(item, profile.fishingRods)) return
        val miss = ev.o.str("hit") == "air" || ev.o.str("hit") == "block"
        if (rod) {
            profile.rodMeleeClicks ++
            if (miss) profile.rodMeleeMisses ++
        }
        else {
            profile.weaponMeleeClicks ++
            if (miss) profile.weaponMeleeMisses ++
        }
    }

    /**
     * What a click of yours attacks with: [ROD] for a left click with any fishing-rod item (a rod-type weapon's swing is
     * the same melee as the rod's), the weapon's id, or null (a reel or cast, a tool).
     */
    private fun optionOf(click: Ev, rods: Set<String>): String? {
        val item = click.o.str("item")
        val left = click.o.str("b") == "L"
        return when {
            item in rods -> if (left) ROD else null
            left && isRodMelee(click, rods) -> ROD
            isWeaponItem(item, rods) -> item
            else -> null
        }
    }

    /** A swing with a rod-type weapon (a fishing-rod item you do not fish with, like the Flay). */
    private fun isRodMelee(click: Ev, rods: Set<String>) =
        click.o.str("b") == "L" && click.o.bool("rod") && click.o.str("item").let { it !in rods && it !in WeaponPlanner.MOBILITY }

    private fun groupOf(name: String) = SeaCreatures.byName(name)?.groupId ?: ""

    /** Where a child of [parent] fights: its parent's group. */
    private fun parentGroup(profile: Profile, parent: String) = profile.habits[parent]?.group?.takeIf { it.isNotEmpty() } ?: groupOf(parent)

    /**
     * One of your creatures for the weapon analysis: a fight, or a child split off one of yours as it died. [derived]: a
     * child found from spawn and death lines alone (older recordings), so without hp lines or positions. [parentId]: the
     * creature whose death it was found at (null for one the recorder named a parent for).
     */
    private class Life(
        val id: Int, val name: String, val t: Long, val end: Long, val deathAt: Long, val maxHp: Double, private val startHp: Double,
        /** Its hp lines: times and values. */
        private val hpT: LongArray, private val hpV: DoubleArray,
        val spawn: DoubleArray?, val fight: Fight?, val parent: String, val derived: Boolean, val parentId: Int?
    ) {
        fun alive(at: Long) = at >= t && at < end

        private fun hpAt(at: Long): Double {
            var lo = 0
            var hi = hpT.size - 1
            var found = - 1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                if (hpT[mid] <= at) {
                    found = mid
                    lo = mid + 1
                }
                else hi = mid - 1
            }
            return if (found >= 0) hpV[found] else startHp
        }

        /** Lost hp or died in ([from], [until]]. */
        fun dropped(from: Long, until: Long): Boolean {
            if (deathAt in from + 1 .. until) return true
            var prev = hpAt(from)
            for (i in hpT.indices) {
                if (hpT[i] <= from) continue
                if (hpT[i] > until) break
                if (hpV[i] < prev) return true
                prev = hpV[i]
            }
            return false
        }

        /** Hp lost in ([from], [until]], all that was left when it died then. */
        fun lost(from: Long, until: Long): Double {
            var prev = hpAt(from)
            var sum = 0.0
            for (i in hpT.indices) {
                if (hpT[i] <= from) continue
                if (hpT[i] > until) break
                if (hpV[i] < prev) sum += prev - hpV[i]
                prev = hpV[i]
            }
            if (deathAt in from + 1 .. until) sum += prev.coerceAtLeast(0.0)
            return sum
        }
    }

    /** One attack click of yours on a creature: what with, whether it hurt it, and where it was then. */
    private class Attack(val ev: Ev, val option: String, val effective: Boolean, val geo: Geo?) {
        val hold get() = ev.o.bool("hold")
    }

    /** A creature seen from your feet: horizontal distance, its y minus yours, your view's error to it and your pitch. */
    private class Geo(val hd: Double, val dy: Double, val err: Double, val pitch: Double)

    /**
     * A press of the use key with a weapon and the repeats of holding it. Not [pressed]: it began with a repeat, the key
     * held through a swap from another item, so how long it was held is unknown.
     */
    private class Run(val start: Long, val pressed: Boolean) {
        var last = start
        var repeats = if (pressed) 0 else 1
        val end get() = if (repeats > 0) last + HALF_REPEAT_MS else last
    }

    /**
     * What you do with your weapons: children split off your creatures, per item how and how far it hurts them
     * ([WeaponStyle]), per creature what killed it and the option and situation of each fight ([Habit.choices]), and how
     * much one rod hit takes ([Habit.rodHitShare]).
     */
    private fun learnWeapons(fights: Map<Int, Fight>, rots: List<RotSample>, log: SessionLog, profile: Profile, kin: Kinship) {
        val rods = profile.fishingRods
        /** A child that came up at [spawn]: alive until its death or a world change, whichever came first. */
        fun child(id: Int, spawn: Ev, parent: String, parentId: Int?, derived: Boolean): Life {
            val death = log.deaths[id].orEmpty().firstOrNull { it.t >= spawn.t }
            val world = log.worlds.firstOrNull { it >= spawn.t } ?: Long.MAX_VALUE
            val end = minOf(death?.t ?: Long.MAX_VALUE, world)
            val died = death?.takeIf { it.o.str("cause") != "gone" && it.t <= world }?.t ?: 0L
            return life(id, spawn.o.str("name"), spawn.t, end, died, spawn.o, log.hp[id].orEmpty(), null, parent, derived, parentId)
        }

        val lives = ArrayList<Life>()
        for (f in fights.values) lives += life(f.id, f.name, f.t, f.end, f.deathAt, f.spawn, log.hp[f.id].orEmpty(), f, "", false)
        for ((id, entry) in log.namedChildren) if (id !in fights) lives += child(id, entry.first, entry.second, null, derived = false)
        lives += derivedChildren(fights, log, rots, lives.mapTo(HashSet()) { it.id }) { id, spawn, parent -> child(id, spawn, parent.name, parent.id, derived = true) }

        // who splits off whom is settled over all recordings at the end ([Kinship]): one chance child makes no parent
        fun deathKey(id: Int) = (profile.sessions.toLong() shl 32) or (id.toLong() and 0xffffffffL)
        for (life in lives) {
            val habit = profile.habits.getOrPut(life.name) { Habit() }
            if (life.maxHp > habit.maxHp) habit.maxHp = life.maxHp
            if (life.deathAt > 0) kin.deaths.merge(life.name, 1, Int::plus)
            if (life.parent.isEmpty()) continue
            // the death it came up at: the one it was found for, else (the recorder names the parent only) the death of
            // that kind it came up next to
            val parentId = life.parentId ?: lives.filter { it.name == life.parent && it.deathAt > 0 && ClaimRules.isChild(life.t - it.deathAt, 0.0) }
                .minByOrNull { abs(life.t - it.deathAt) }?.id ?: continue
            kin.child(life.name, life.parent, deathKey(parentId))
        }

        val clicks = log.clicks
        val sessionUses = HashMap<String, Int>()
        val runs = HashMap<String, Run>()
        val lastLeft = HashMap<String, Long>()
        val attacks = HashMap<Int, MutableList<Attack>>()
        fun closeRun(item: String) {
            val run = runs.remove(item) ?: return
            if (run.pressed && run.repeats > 0) profile.weaponStyles.getValue(item).holdMs += (run.end - run.start).toInt()
        }

        for ((i, ev) in clicks.withIndex()) {
            if (ev.o.bool("cast")) continue
            val item = ev.o.str("item")
            val right = ev.o.str("b") == "R"
            if (right && item in rods) continue
            val alive = lives.filter { it.alive(ev.t) }
            if (alive.isEmpty()) continue
            val rot = rotAt(rots, ev.t)?.takeIf { ev.t - it.t <= ROT_AGE_MS }
            val target = ev.o.intOrNull("target")
            val near = ev.o.intOrNull("near")
            // at one of yours seen again after the tracker stopped: its life is not known here, nor what the click did
            if (target in log.kept || target == null && near in log.kept) continue
            val dropped = alive.filter { it.dropped(ev.t, ev.t + EFFECT_MS) }
            // the creature a click hurt: the one under your crosshair, else your nearest one (the recorder's near), else
            // the one closest to you
            val hit = dropped.firstOrNull { it.id == target } ?: dropped.firstOrNull { it.id == near } ?: dropped.minByOrNull { distance(rot, it) }

            // a rod-type weapon's swing is rod melee, no sample of how you use its ability
            if (item.isNotEmpty() && item !in rods && ! isRodMelee(ev, rods)) {
                val s = profile.weaponStyles.getOrPut(item) { WeaponStyle() }
                s.uses ++
                sessionUses.merge(item, 1, Int::plus)
                val hold = ev.o.bool("hold")
                if (! right) s.left ++ else if (hold) s.held ++ else s.right ++
                if (hit != null) {
                    s.effective ++
                    if (target == hit.id) s.onTarget ++
                    geoOf(rot, hit)?.let { g ->
                        s.reach += g.hd.toFloat()
                        s.dy += g.dy.toFloat()
                        s.aimErr += g.err.toFloat()
                        s.pitch += g.pitch.toFloat()
                    }
                }
                if (right) {
                    val run = runs[item]
                    if (hold && run != null && ev.t - run.last <= HOLD_REPEAT_MS) {
                        run.last = ev.t
                        run.repeats ++
                    }
                    else {
                        val prevEnd = run?.end
                        closeRun(item)
                        if (! hold && prevEnd != null && ev.t - prevEnd in PRESS_GAP_MS) s.gapMs += (ev.t - prevEnd).toInt()
                        runs[item] = Run(ev.t, pressed = ! hold)
                    }
                }
                else {
                    lastLeft[item]?.let { if (ev.t - it in PRESS_GAP_MS) s.gapMs += (ev.t - it).toInt() }
                    lastLeft[item] = ev.t
                }
            }

            val option = optionOf(ev, rods) ?: continue
            // a rod hit is on what it hit; a weapon's click on the creature it hurt, else on the one you aimed at
            val on = if (option == ROD) alive.firstOrNull { it.id == target } else hit ?: alive.firstOrNull { it.id == target } ?: alive.firstOrNull { it.id == near }
            if (on == null) continue
            val effective = if (option == ROD) on.dropped(ev.t, ev.t + EFFECT_MS) else hit != null
            attacks.getOrPut(on.id) { mutableListOf() } += Attack(ev, option, effective, geoOf(rot, on))
            // what your fishing rod's hit takes, not a rod-type weapon's
            if (option == ROD && item in rods && on.maxHp > 0) rodHitShare(clicks, i, on, rods)?.let { profile.habits.getValue(on.name).rodHitShare += it }
        }
        runs.keys.toList().forEach(::closeRun)

        // how often your creatures lose hp anyway: a tool clicked while they fight is not effective by chance alone (fight
        // lines only: they come every tick, the lines outside fights only when the view changed)
        var moments = 0
        var drops = 0
        val fightRots = rots.filter { it.creatures.isNotEmpty() }
        for (i in fightRots.indices step BASE_RATE_STEP) {
            val t = fightRots[i].t
            val alive = lives.filter { it.alive(t) }
            if (alive.isEmpty()) continue
            moments ++
            if (alive.any { it.dropped(t, t + EFFECT_MS) }) drops ++
        }
        val base = if (moments > 0) drops.toFloat() / moments else 0f
        for ((item, uses) in sessionUses) {
            val s = profile.weaponStyles.getValue(item)
            s.chance = (s.chance * (s.uses - uses) + base * uses) / s.uses
            val rights = s.right + s.held
            s.style = when {
                s.left > rights -> ClickStyle.LEFT
                s.held >= rights * 0.4 -> ClickStyle.HOLD
                else -> ClickStyle.RIGHT
            }
        }

        for (life in lives) {
            val habit = profile.habits.getValue(life.name)
            // a child is fought where its parent was caught (its own habit's parent is only settled at the end)
            val group = if (life.parent.isEmpty()) habit.group else parentGroup(profile, life.parent)
            if (life.derived) {
                learnDerivedChild(life, clicks, rots, habit, group, profile)
                continue
            }
            val list = attacks[life.id].orEmpty()
            // your recast's bobber killed it only when you did not attack it at all
            val killer = if (life.deathAt == 0L) null
                else list.lastOrNull { it.effective && it.ev.t <= life.deathAt }?.option ?: BOBBER.takeIf { list.isEmpty() && life.fight?.bobber != null }
            killer?.let { habit.killsBy.merge(it, 1, Int::plus) }
            // the bobber's kills are the killer's recast-first business, no choice of a weapon
            if (list.isEmpty()) continue
            val option = killer ?: list.filter { it.effective && ! it.hold }.groupingBy { it.option }.eachCount().maxByOrNull { it.value }?.key ?: list.first().option
            // the situation you chose it in: your first click with it, not its first effective one, which often comes only
            // once the creature walked up to you (Flay fights started from afar would count as close-up ones and push out
            // the rod you use there)
            val geo = list.firstOrNull { it.option == option && it.geo != null }?.geo ?: continue
            countChoice(profile, habit, group, WeaponPlanner.situation(geo.hd, geo.dy), option)
        }
    }

    private fun life(
        id: Int, name: String, t: Long, end: Long, deathAt: Long, spawn: JsonObject?, hp: List<Ev>, fight: Fight?, parent: String, derived: Boolean, parentId: Int? = null
    ) = Life(
        id, name, t, end, deathAt, spawn?.double("max") ?: 0.0, spawn?.double("hp") ?: 0.0,
        LongArray(hp.size) { hp[it].t }, DoubleArray(hp.size) { hp[it].o.double("hp") },
        spawn?.takeIf { it.has("x") }?.let { doubleArrayOf(it.double("x"), it.double("y"), it.double("z")) }, fight, parent, derived, parentId
    )

    /**
     * [ClaimRules.childName] for a name a recording logged, read the way the tracker reads a nametag
     * ([SeaCreatureTracker.readName]): an older recording kept a Corrupted creature's obfuscated glyphs on its name, and
     * `aCorrupted Grincha` is a Grinch you caught, never a child. A name the table does not know must look like a
     * creature's (words of letters, no glyph glued on): the tracker reads no other names now, so this is its rule too.
     */
    private fun childName(name: String, maxHp: Double): Boolean {
        val read = SeaCreatureTracker.readName(name)
        val creature = read.creature
        if (creature == null && (! creatureName.matches(name) || gluedGlyph.containsMatchIn(name))) return false
        return ClaimRules.childName(creature?.name ?: read.name, creature, maxHp)
    }

    /**
     * Children of your creatures that an older recording shows only as someone else's ([ClaimRules.isChild] and
     * [childName]): a creature you cannot catch, coming up -300..800 ms around one of your creatures' death, within 7
     * blocks of where it died, at most 3 per death. [make] gets the creature whose death it came up at.
     */
    private fun derivedChildren(fights: Map<Int, Fight>, log: SessionLog, rots: List<RotSample>, known: Set<Int>, make: (Int, Ev, Fight) -> Life): List<Life> {
        class Death(val fight: Fight, val pos: DoubleArray) {
            var children = 0
        }
        val deaths = fights.values.filter { it.deathAt > 0 }.mapNotNull { f ->
            val pos = lastPosition(rots, f.id, f.deathAt) ?: f.spawn?.takeIf { it.has("x") }?.let { doubleArrayOf(it.double("x"), it.double("y"), it.double("z")) }
            pos?.let { Death(f, it) }
        }
        if (deaths.isEmpty()) return emptyList()
        val out = mutableListOf<Life>()
        val claimed = HashSet<Int>()
        for (spawn in log.spawns) {
            val id = spawn.o.int("id")
            if (id in known || id in claimed || spawn.o.bool("own") || ! spawn.o.has("x")) continue
            if (! childName(spawn.o.str("name"), spawn.o.double("max"))) continue
            val x = spawn.o.double("x")
            val y = spawn.o.double("y")
            val z = spawn.o.double("z")
            fun dist(d: Death) = sqrt((d.pos[0] - x) * (d.pos[0] - x) + (d.pos[1] - y) * (d.pos[1] - y) + (d.pos[2] - z) * (d.pos[2] - z))
            val parent = ClaimRules.parentOf(deaths.filter { it.children < ClaimRules.CHILD_MAX }, { spawn.t - it.fight.deathAt }, ::dist) ?: continue
            parent.children ++
            claimed += id
            out += make(id, spawn, parent.fight)
        }
        return out
    }

    /**
     * A child found offline has no hp lines and no positions: what killed it is your last weapon click within 400 ms before
     * its death (an aimed weapon only with it under the crosshair; a held or area weapon needs none). Its situation is
     * where it came up, seen from where you stood at that click, and only when the click came within [DERIVED_SPOT_MS] of
     * its spawn: later it may be anywhere (lava children sink), and a kill made close to it would count as one made from
     * far above. [group]: its parent's.
     */
    private fun learnDerivedChild(life: Life, clicks: List<Ev>, rots: List<RotSample>, habit: Habit, group: String, profile: Profile) {
        if (life.deathAt == 0L) return
        val rods = profile.fishingRods
        val weaponsKnown = profile.weaponStyles.keys.any { WeaponPlanner.isWeapon(profile, it) }
        val from = life.deathAt - EFFECT_MS
        val killing = clicks.lastOrNull { k ->
            if (k.t < from || k.t > life.deathAt || k.t < life.t - EFFECT_MS || k.o.bool("cast")) return@lastOrNull false
            val option = optionOf(k, rods) ?: return@lastOrNull false
            val onIt = k.o.intOrNull("target") == life.id
            if (option == ROD) return@lastOrNull onIt
            if (weaponsKnown && ! WeaponPlanner.isWeapon(profile, option)) return@lastOrNull false
            val plan = WeaponPlanner.plan(profile, option)
            onIt || plan != null && (plan.style == ClickStyle.HOLD || plan.aimDeg >= 90.0)
        } ?: return
        val option = optionOf(killing, rods) ?: return
        habit.killsBy.merge(option, 1, Int::plus)
        if (killing.t - life.t > DERIVED_SPOT_MS) return
        val spawn = life.spawn ?: return
        val rot = rotAt(rots, killing.t) ?: return
        countChoice(profile, habit, group, WeaponPlanner.situation(hypot(spawn[0] - rot.x, spawn[2] - rot.z), spawn[1] - rot.y), option)
    }

    /** One fight of [habit]'s creature with [option] in [situation], also for every creature and for its [group]. */
    private fun countChoice(profile: Profile, habit: Habit, group: String, situation: String, option: String) {
        habit.choices.getOrPut(situation) { linkedMapOf() }.merge(option, 1, Int::plus)
        profile.choices.getOrPut(situation) { linkedMapOf() }.merge(option, 1, Int::plus)
        if (group.isNotEmpty()) profile.groupChoices.getOrPut(group) { linkedMapOf() }.getOrPut(situation) { linkedMapOf() }.merge(option, 1, Int::plus)
    }

    /**
     * What your rod hit [clicks][[i]] took from [life], as a share of its max hp: until your next click (+50 ms) or for
     * 600 ms. Null when a weapon was clicked around it (800 ms before to 600 ms after), which may have done the damage.
     */
    private fun rodHitShare(clicks: List<Ev>, i: Int, life: Life, rods: Set<String>): Float? {
        val hit = clicks[i]
        fun weapon(k: Ev) = k.o.str("item").let { it.isNotEmpty() && it !in rods && it !in WeaponPlanner.MOBILITY }
        var j = i - 1
        while (j >= 0 && clicks[j].t >= hit.t - ROD_SHARE_WEAPON_BEFORE_MS) if (weapon(clicks[j --])) return null
        j = i + 1
        while (j < clicks.size && clicks[j].t <= hit.t + ROD_SHARE_WEAPON_AFTER_MS) if (weapon(clicks[j ++])) return null
        val next = (i + 1 until clicks.size).firstOrNull { clicks[it].t > hit.t }?.let(clicks::get)
        val end = minOf(hit.t + ROD_SHARE_MS, next?.let { it.t + ROD_SHARE_NEXT_MS } ?: Long.MAX_VALUE)
        return (life.lost(hit.t, end) / life.maxHp).toFloat()
    }

    /** Index of the last rotation sample at or before [t], -1 for none. */
    private fun rotIndex(rots: List<RotSample>, t: Long): Int {
        var lo = 0
        var hi = rots.size - 1
        var found = - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (rots[mid].t <= t) {
                found = mid
                lo = mid + 1
            }
            else hi = mid - 1
        }
        return found
    }

    private fun rotAt(rots: List<RotSample>, t: Long) = rots.getOrNull(rotIndex(rots, t))

    /** Where creature [id] was last seen in a rotation sample at or before [t] (searching back 10 s at most). */
    private fun lastPosition(rots: List<RotSample>, id: Int, t: Long): DoubleArray? {
        var at = rotIndex(rots, t)
        while (at >= 0 && t - rots[at].t <= 10_000) {
            rots[at].creatures[id]?.let { return it }
            at --
        }
        return null
    }

    /** [life] seen from the view of [rot] (null without that view or without [life] in it). */
    private fun geoOf(rot: RotSample?, life: Life): Geo? {
        val c = rot?.creatures?.get(life.id) ?: return null
        val height = if (c[3] > 0) c[3] else DEFAULT_HEIGHT
        val ex = c[0] - rot.x
        val ey = c[1] + height * AIM_SHARE - (rot.y + EYE)
        val ez = c[2] - rot.z
        val len = sqrt(ex * ex + ey * ey + ez * ez)
        val err = if (len < 1e-6) 0.0 else Math.toDegrees(acos(((rot.dir[0] * ex + rot.dir[1] * ey + rot.dir[2] * ez) / len).coerceIn(- 1.0, 1.0)))
        return Geo(hypot(ex, ez), c[1] - rot.y, err, rot.pitch)
    }

    /** From your feet to [life] (its position in [rot], else where it came up), for the nearest creature a click hurt. */
    private fun distance(rot: RotSample?, life: Life): Double {
        if (rot == null) return 0.0
        val c = rot.creatures[life.id] ?: life.spawn ?: return Double.MAX_VALUE
        return sqrt((c[0] - rot.x) * (c[0] - rot.x) + (c[1] - rot.y) * (c[1] - rot.y) + (c[2] - rot.z) * (c[2] - rot.z))
    }

    /**
     * What an event after a kill means for the after-kill time: true for your own move back to fishing (the rod slot, your
     * rod click), false for something that ends the wait without you (Auto Fishing's click or cast, a new catch), null else.
     */
    private fun afterKillMarker(e: Ev, rods: Set<String>): Boolean? = when {
        e.type == "slot" && e.o.str("item") in rods -> true
        e.type == "click" && e.o.str("b") == "R" && e.o.str("item") in rods && ! e.o.bool("hold") -> ! e.o.bool("auto")
        e.type == "cast" && e.o.bool("auto") -> false
        e.type == "spawn" && e.o.bool("own") && ! e.o.bool("kept") || e.type == "own" && e.o.bool("own") -> false
        // a bite starts the next catch; a loadout or wardrobe trip is timed on its own (the menu speeds)
        e.type == "bite" || e.type == "cmd" || e.type == "menu" && e.o.str("title") != "other" -> false
        else -> null
    }

    /**
     * Your reaction time and your turn towards a creature: from your view when it appeared to the view you first hit it
     * with (great-circle angles, so a yaw change while looking straight down counts for almost nothing). The turn starts
     * with a view step of [MOVE_DEG] or more towards that view and ends at the first still tick after [TURN_DONE] of the
     * way, keeping a correction right after it; a movement that stops before that was not the turn (looking around), the
     * next one may be. A creature that came into your crosshair needed no turn.
     */
    private fun learnTurn(fight: Fight, rots: List<RotSample>, profile: Profile) {
        val hit = fight.clicks.firstOrNull { it.o.intOrNull("target") == fight.id } ?: return
        val samples = rots.filter { it.t >= fight.t && it.t <= hit.t && fight.id in it.creatures }
        if (samples.size < 3) return
        val goal = samples.last().dir
        if (angle(samples.first().dir, goal) < NO_TURN_DEG) {
            profile.noTurnFights ++
            return
        }
        fun step(i: Int) = angle(samples[i - 1].dir, samples[i].dir)
        fun towardsGoal(i: Int) = step(i) >= MOVE_DEG && angle(samples[i].dir, goal) < angle(samples[i - 1].dir, goal)
        // the share of the way from samples[base] along the great circle to the goal (above 1 = past it, below 0 = away from it)
        fun progressFrom(base: Int): List<Float> {
            val start = samples[base].dir
            val cosArc = dot(start, goal)
            val arc = acos(cosArc.coerceIn(- 1.0, 1.0))
            val along = DoubleArray(3) { (goal[it] - start[it] * cosArc) / sin(arc) }
            return samples.map { (atan2(dot(it.dir, along), dot(it.dir, start)) / arc).toFloat() }
        }

        var onset = (1 until samples.size).firstOrNull(::towardsGoal) ?: return
        var progress: List<Float>
        var reached: Int
        var pause: Int?
        do {
            progress = progressFrom(onset - 1)
            reached = (onset until samples.size).firstOrNull { progress[it] >= TURN_DONE } ?: return
            pause = (onset + 1 .. reached).lastOrNull { step(it) < STILL_DEG }
            if (pause != null) onset = (pause + 1 .. reached).firstOrNull(::towardsGoal) ?: return
        } while (pause != null)
        val base = samples[onset - 1]
        val way = angle(base.dir, goal)
        if (way < NO_TURN_DEG) return
        val c = base.creatures.getValue(fight.id)
        if (hypot(c[0] - base.x, c[2] - base.z) < MIN_TURN_DISTANCE) return

        var end = (reached until samples.size - 1).firstOrNull { step(it + 1) < STILL_DEG } ?: (samples.size - 1)
        // a correction right after (an overshoot taken back, the rest of an undershoot) belongs to the turn
        val correction = (end + 2 until samples.size).firstOrNull { step(it) >= MOVE_DEG }
        if (correction != null && samples[correction - 1].t - samples[end].t <= CORRECTION_MS) {
            val stop = (correction until samples.size - 1).firstOrNull { step(it + 1) < STILL_DEG } ?: (samples.size - 1)
            if (angle(samples[stop].dir, goal) < angle(samples[end].dir, goal)) end = stop
        }
        if (end - onset < 1) return
        // a turn long after the spawn is still a turn, but no reaction to the spawn
        if (base.t - fight.t <= REACTION_MS) profile.spawnReactionMs += (base.t - fight.t).toInt()
        profile.turns += Turn(way.toFloat(), (samples[end].t - base.t).toInt(), FloatArray(end - onset + 1) { progress[onset + it] })
    }

    /**
     * Your view in one recording ([ViewMotion]): every movement for [Motor], the ticks outside movements (rest nudges) and
     * in fights (still or not), and per creature and fight whether and when you turned toward it, your corrections after
     * that, and your turn back after the fight (the motor analysis scripts p1_kill, p3_aimfights and back2, ported).
     */
    private fun learnViews(events: List<Ev>, rots: List<RotSample>, views: ViewLog) {
        if (rots.size < 2) return
        val ownTimes = rots.filter { it.creatures.isNotEmpty() }.map { it.t }.toLongArray()
        fun inFight(t: Long): Boolean {
            val i = lastAtOrBefore(ownTimes, t)
            return i >= 0 && t - ownTimes[i] <= FIGHT_TAIL_MS
        }
        // every movement, cut ones too: a turn back may still be going when the recording's fight lines stop
        val moves = ArrayList<ViewMotion.Move>()
        for (run in ViewMotion.runs(rots.map { ViewMotion.Line(it.t, it.yaw, it.pitch, it.tick, it.tp) })) {
            val seg = ViewMotion.segment(run)
            moves += seg.moves
            var prev: ViewMotion.Move? = null
            for (m in seg.moves) {
                if (m.cut) continue
                views.moves += m
                prev?.let { a -> (m.t0 - a.t1).takeIf { a.amp >= 6 && m.amp >= 3 && it <= MOVE_GAP_MS }?.let { views.gaps += it.toDouble() } }
                prev = m
            }
            // a nudge's axes in grid steps: near the pole the yaw's go further for the view they turn
            fun nudge(i: Int) {
                val dPitch = abs(seg.dPitch[i])
                val dYaw = abs(seg.dYaw[i])
                if (dPitch > ViewMotion.STILL_DEG) views.restHand += (dPitch / ViewMotion.GRID_DEG).roundToInt()
                if (dYaw > ViewMotion.STILL_DEG) (if (abs(run[i - 1].pitch) >= REST_POLE_PITCH) views.restPole else views.restHand) += (dYaw / ViewMotion.GRID_DEG).roundToInt()
            }
            for (m in seg.moves) if (! m.cut && m.first == m.last && m.amp < NUDGE_MOVE_DEG) nudge(m.first)
            var prevChanged = false
            for (i in 1 until run.size) {
                val changed = abs(seg.dYaw[i]) > ViewMotion.STILL_DEG || abs(seg.dPitch[i]) > ViewMotion.STILL_DEG
                if (inFight(run[i].t)) {
                    views.fightTicks ++
                    if (! changed) views.fightStill ++
                }
                if (seg.inMove[i]) {
                    prevChanged = false
                    continue
                }
                views.restTicks ++
                if (changed) {
                    views.restChanged ++
                    if (prevChanged) views.restFollow ++
                    val yawMoved = abs(seg.dYaw[i]) > ViewMotion.STILL_DEG
                    val pitchMoved = abs(seg.dPitch[i]) > ViewMotion.STILL_DEG
                    if (yawMoved && pitchMoved) views.restBoth ++ else if (yawMoved) views.restYaw ++
                    views.restSteps += (max(abs(seg.dYaw[i]), abs(seg.dPitch[i])) / ViewMotion.GRID_DEG).roundToInt().toDouble()
                    nudge(i)
                }
                prevChanged = changed
            }
        }
        val kept = moves.filter { ! it.cut }

        fun at(t: Long) = rots[max(0, rotIndex(rots, t))]
        val clicks = events.filter { it.type == "click" && ! it.o.bool("cast") && ! it.o.bool("auto") }.sortedBy { it.t }
        val clickTimes = clicks.map { it.t }.toLongArray()
        val classes = HashMap<Int, String>()
        /** Your main attack clicks on creature [id] while it was alive, as `item:button` (`h`: held), `none` without. */
        fun classOf(id: Int, from: Long, to: Long) = classes.getOrPut(id) {
            val counts = LinkedHashMap<String, Int>()
            var i = lastAtOrBefore(clickTimes, from - 1) + 1
            while (i < clicks.size && clicks[i].t <= to) {
                val c = clicks[i ++]
                if (id !in at(c.t).creatures) continue
                counts.merge(c.o.str("item").ifEmpty { "?" } + ":" + c.o.str("b") + if (c.o.bool("hold")) "h" else "", 1, Int::plus)
            }
            counts.maxByOrNull { it.value }?.key ?: "none"
        }
        fun closes(m: ViewMotion.Move, id: Int): Boolean {
            val ra = at(m.t0)
            val rb = at(m.t1)
            val ca = ra.creatures[id] ?: return false
            val cb = rb.creatures[id] ?: return false
            return turnErr(rb, cb) <= turnErr(ra, ca) * TURN_CLOSES
        }

        val own = events.filter { it.type == "spawn" && it.o.bool("own") && ! it.o.bool("kept") }.sortedBy { it.t }
        val deaths = HashMap<Int, Ev>()
        events.filter { it.type == "death" }.forEach { deaths[it.o.int("id")] = it }

        // per creature: was it far enough off to need a turn, and did you turn toward it (when, and back after it)
        val counted = HashSet<Int>()
        val toward = LinkedHashMap<Long, ViewMotion.Move>()
        for (s in own) {
            val id = s.o.int("id")
            val death = deaths[id] ?: continue
            if (death.t - s.t < TURN_LIFE_MS) continue
            val r1 = firstWith(rots, id, s.t + 300, s.t + 600) ?: continue
            val c1 = r1.creatures.getValue(id)
            val e300 = turnErr(r1, c1)
            if (e300 < TURN_OFF_DEG) continue
            val closing = kept.filter { it.amp >= 6 && it.t0 in s.t .. death.t && closes(it, id) }
            val first = closing.firstOrNull()
            val cls = classOf(id, s.t, death.t)
            for (log in listOf(views.all, views.turns(cls))) {
                log.creatures ++
                if (first != null) log.turned ++
            }
            counted += id
            closing.forEach { toward.putIfAbsent(it.t0, it) }
            val overhead = hypot(c1[0] - r1.x, c1[2] - r1.z) < Looks.OVERHEAD_SIDE && e300 > Looks.OVERHEAD_DEG
            views.looks += LookSample(cls, e300, boxDeg(r1, c1), overhead, death.t - s.t, first?.let { it.t0 - s.t } ?: - 1L, first?.let { returnAfter(kept, it) } ?: - 1L,
                first?.let { returnGap(kept, it) } ?: - 1L)
        }
        // where those turns stopped: against the counted creature each ended nearest, from 15 degrees or more off
        for (m in toward.values) {
            val ra = at(m.t0)
            val rb = at(m.t1)
            var near = - 1
            var eb = Double.MAX_VALUE
            for ((id, c) in rb.creatures) {
                val e = turnErr(rb, c)
                if (e < eb) {
                    near = id
                    eb = e
                }
            }
            val ca = ra.creatures[near] ?: continue
            val ea = turnErr(ra, ca)
            if (ea >= TURN_OFF_DEG && near in counted) views.lookStop += eb / ea
        }

        // fights: your creatures' lifetimes merged where they overlap
        class Span(val t0: Long, var t1: Long, val spawns: MutableList<Ev>)
        val spans = ArrayList<Span>()
        for (s in own) {
            val end = deaths[s.o.int("id")]?.t ?: continue
            val last = spans.lastOrNull()
            if (last != null && s.t <= last.t1) {
                last.t1 = max(last.t1, end)
                last.spawns += s
            }
            else spans += Span(s.t, end, mutableListOf(s))
        }
        val slots = events.filter { it.type == "slot" }
        for (span in spans) {
            if (span.t1 - span.t0 !in TURN_FIGHT_MS) continue
            // the first movement that turned toward one of its creatures 15° or more off
            var found: Pair<ViewMotion.Move, Ev>? = null
            search@ for (m in kept) {
                if (m.t0 < span.t0 || m.t0 > span.t1 || m.amp < 6) continue
                val ra = at(m.t0)
                val rb = at(m.t1)
                for (s in span.spawns) {
                    val ca = ra.creatures[s.o.int("id")] ?: continue
                    val cb = rb.creatures[s.o.int("id")] ?: continue
                    val ea = turnErr(ra, ca)
                    if (ea >= TURN_OFF_DEG && turnErr(rb, cb) <= ea * TURN_CLOSES) {
                        found = m to s
                        break@search
                    }
                }
            }
            var ticks = 0
            var changed = 0
            for (i in max(0, rotIndex(rots, span.t0)) + 1 .. max(0, rotIndex(rots, span.t1))) {
                if (rots[i].t - rots[i - 1].t > ViewMotion.MAX_GAP_MS) continue
                ticks ++
                if (abs(ViewMotion.wrap(rots[i].yaw - rots[i - 1].yaw)) > ViewMotion.STILL_DEG || abs(rots[i].pitch - rots[i - 1].pitch) > ViewMotion.STILL_DEG) changed ++
            }
            if (found == null) {
                views.ticksOther += ticks
                views.changedOther += changed
                continue
            }
            val (turn, target) = found
            views.turnFights ++
            views.ticksTurned += ticks
            views.changedTurned += changed
            val targetId = target.o.int("id")
            val logs = listOf(views.all, views.turns(classOf(targetId, target.t, deaths.getValue(targetId).t)))
            val swap = slots.firstOrNull { it.t >= target.t - 200 && it.t <= turn.t0 + 100 }
            if (swap != null) logs.forEach { it.swapLatency += (turn.t0 - swap.t).toDouble() }
            else logs.forEach { it.spawnLatency += (turn.t0 - target.t).toDouble() }
            at(turn.t1).let { rb -> rb.creatures[targetId]?.let { c -> logs.forEach { it.stopErr += turnErr(rb, c) } } }
            // later movements that brought the view closer to one of its creatures: your corrections
            var prev = turn
            for (m in kept) {
                if (m.t0 < span.t0 || m.t0 > span.t1 || m.amp < 6 || m.t0 <= turn.t0) continue
                val ra = at(m.t0)
                val rb = at(m.t1)
                var best: Ev? = null
                var bestStart = 0.0
                var bestEnd = 0.0
                for (s in span.spawns) {
                    val ca = ra.creatures[s.o.int("id")] ?: continue
                    val cb = rb.creatures[s.o.int("id")] ?: continue
                    val ea = turnErr(ra, ca)
                    val eb = turnErr(rb, cb)
                    if (eb < ea && (best == null || ea - eb > bestStart - bestEnd)) {
                        best = s
                        bestStart = ea
                        bestEnd = eb
                    }
                }
                if (best != null) {
                    val id = best.o.int("id")
                    val cls = classOf(id, best.t, deaths.getValue(id).t)
                    for (log in listOf(views.all, views.turns(cls))) {
                        log.corrStart += bestStart
                        log.corrEnd += bestEnd
                        log.corrGap += (m.t0 - prev.t1).toDouble()
                    }
                    views.corrections += Triple(cls, bestStart, boxDeg(ra, ra.creatures.getValue(id)))
                }
                prev = m
            }
        }

        // the turn back to the view a fight started with
        val casts = events.filter { it.type == "cast" }
        for ((k, span) in spans.withIndex()) {
            if (span.t1 - span.t0 > TURN_FIGHT_MS.last) continue
            val start = at(span.t0)
            val end = at(span.t1)
            if (abs(start.t - span.t0) > BACK_VIEW_MS || abs(end.t - span.t1) > BACK_VIEW_MS) continue
            views.backFights ++
            val err = homeDistance(end.yaw, end.pitch, start.yaw, start.pitch)
            if (err <= BACK_NONE_DEG) continue
            views.backBeyond ++
            // a fight that started away from the view you last cast from (a look still out, a turn not undone yet) had no
            // fishing view of its own to turn back to; without a cast with a view, every fight counts
            val fished = BackRules.fished(start.yaw, start.pitch, casts.lastOrNull { it.t < span.t0 && it.o.has("yaw") }?.let { it.o.double("yaw") to it.o.double("pitch") })
            if (fished) views.backHome ++
            val back = moves.firstOrNull { it.t0 >= span.t1 - 50 && it.t0 <= span.t1 + BACK_WINDOW_MS && homeDistance(it.y1, it.p1, start.yaw, start.pitch) <= err * 0.5 }
            if (back != null) {
                views.backTurned ++
                if (fished) views.backHomeTurned ++
                views.backLatency += (back.t0 - span.t1).toDouble()
                views.backRest += homeDistance(back.y1, back.p1, start.yaw, start.pitch)
                casts.firstOrNull { it.t > back.t1 }?.takeIf { it.o.has("yaw") }?.let { cast ->
                    views.backCastErr += homeDistance(cast.o.double("yaw"), cast.o.double("pitch"), start.yaw, start.pitch)
                    views.backCastLag += (cast.t - back.t1).toDouble()
                }
            }
            else if ((spans.getOrNull(k + 1)?.t0 ?: Long.MAX_VALUE) - span.t1 < BACK_WINDOW_MS) {
                views.backInterrupted ++
                if (fished) views.backHomeInterrupted ++
            }
            else views.backNone ++
        }
    }

    /**
     * How long after the end of [m] (a turn toward a creature) you turned back toward the view it started from: the first
     * movement of 6 degrees or more within [LOOK_BACK_MS] that ends at most [LOOK_BACK_SHARE] as far from that view as [m]
     * did; -1 for none.
     */
    private fun returnAfter(kept: List<ViewMotion.Move>, m: ViewMotion.Move): Long = wayBack(kept, m)?.let { it.t0 - m.t1 } ?: - 1L

    /** The way back after [m] that [returnAfter] times (null: none). */
    private fun wayBack(kept: List<ViewMotion.Move>, m: ViewMotion.Move): ViewMotion.Move? {
        val away = ViewMotion.angle(m.y1, m.p1, m.y0, m.p0)
        return kept.firstOrNull { it.t0 > m.t1 && it.t0 <= m.t1 + LOOK_BACK_MS && it.amp >= 6 && ViewMotion.angle(it.y1, it.p1, m.y0, m.p0) <= LOOK_BACK_SHARE * away }
    }

    /**
     * How long before the way back after [m] ([returnAfter]) the view last came to rest: the end of the last movement of
     * [GAP_MOVE_DEG] or more between them (one of your moves in between), else of [m]; -1 for no way back. Your ways back
     * came this gap after the last rest whatever came before (p50 252 ms), the look's own or a move after it.
     */
    private fun returnGap(kept: List<ViewMotion.Move>, m: ViewMotion.Move): Long {
        val back = wayBack(kept, m) ?: return - 1L
        val rest = kept.lastOrNull { it.t0 > m.t1 && it.t0 < back.t0 && it.amp >= GAP_MOVE_DEG }?.t1 ?: m.t1
        return back.t0 - rest
    }

    /**
     * The planned first stroke's mean ([BehaviorProfile.Motor.firstMu]) for a hand whose two-stroke moves' first share
     * measures [p90] at its p90: the reference's plan moved so that the motor measures it as far off the reference's p90
     * as yours is. The p90 is what the plan was tuned on (the first strokes past the goal, the overshoots), and the
     * motor's measured quantiles move by less than the plan does, each by its own share.
     */
    fun firstMuOf(p90: Double): Double {
        val x = (p90 - REF_FIRST_P90) / FIRST_P90_SLOPE
        // a per-profile constant, so it saturates: a hand far off the reference plans close to a bound, never past it (the
        // motor would fall back to the default there, a careful hand's plan jumping up past a careless one's)
        val room = if (x >= 0.0) FIRST_MU_MAX - PLAN_FIRST else PLAN_FIRST - FIRST_MU_MIN
        return PLAN_FIRST + room * tanh(x / room)
    }

    /**
     * The planned first stroke's mean of a way back ([BehaviorProfile.Motor.backFirstMu]) for a hand whose ways back's
     * two-stroke first share measures [p50] at its median: the reference's plan moved so that the motor measures it as far
     * off the reference's median as yours is, saturating within [BACK_MU_MIN]..[BACK_MU_MAX] (monotone, its slope
     * continuous at the reference, like [firstMuOf]). The median, not the p90: no first stroke of a way back goes past
     * its goal, so the p90 barely moves with the plan.
     */
    fun backFirstMuOf(p50: Double): Double {
        val x = (p50 - REF_BACK_P50) / BACK_P50_SLOPE
        val room = if (x >= 0.0) BACK_MU_MAX - PLAN_BACK else PLAN_BACK - BACK_MU_MIN
        return PLAN_BACK + room * tanh(x / room)
    }

    /** How the sizes of your rest nudges become the motor's ([MotorParams.restSteps], [MotorParams.restPoleP50]), pure so they can be unit tested. */
    internal object RestRules {
        /** The share of each size 1 .. [max] grid steps of [steps] (larger ones left out, not piled at [max]), up to the largest seen; null with none. */
        fun shares(steps: List<Int>, max: Int): MutableList<Double>? {
            val counts = IntArray(max + 1)
            for (k in steps) if (k in 1 .. max) counts[k]++
            val total = counts.sum()
            val top = counts.indexOfLast { it > 0 }
            if (total == 0 || top < 1) return null
            return MutableList(top) { counts[it + 1].toDouble() / total }
        }

        /**
         * The lognormal (median, ln-sd) whose draws within 0.5 .. [max] + 0.5 rounded to whole steps ([RestMotion.poleSteps])
         * make [steps] the likeliest, larger ones left out; null with none. A grid search, coarse and then around its best.
         */
        fun lognormal(steps: List<Int>, max: Int): Pair<Double, Double>? {
            val counts = steps.filter { it in 1 .. max }.groupingBy { it }.eachCount()
            if (counts.isEmpty()) return null
            val lo = ln(0.5)
            val hi = ln(max + 0.5)
            fun ll(mu: Double, sigma: Double): Double {
                val z = TimingModel.phi((hi - mu) / sigma) - TimingModel.phi((lo - mu) / sigma)
                if (z <= 0.0) return Double.NEGATIVE_INFINITY
                var sum = 0.0
                for ((k, n) in counts) {
                    val p = TimingModel.phi((ln(k + 0.5) - mu) / sigma) - TimingModel.phi((ln(k - 0.5) - mu) / sigma)
                    sum += n * ln(max(p / z, 1e-300))
                }
                return sum
            }
            var best = Triple(Double.NEGATIVE_INFINITY, 0.0, 1.0)
            fun search(mus: List<Double>, sigmas: List<Double>) {
                for (mu in mus) for (sigma in sigmas) {
                    val l = ll(mu, sigma)
                    if (l > best.first) best = Triple(l, mu, sigma)
                }
            }
            search((0 .. 60).map { lo + (hi - lo) * it / 60 }, (1 .. 40).map { 0.1 * it })
            val (_, mu0, s0) = best
            search((- 20 .. 20).map { mu0 + it * 0.005 }, (- 20 .. 20).map { s0 + it * 0.005 }.filter { it > 0.0 })
            return exp(best.second) to best.third
        }
    }

    /** The learned turn back rate's rules ([learnPolicy]), pure so they can be unit tested. */
    internal object BackRules {
        /** A fight started at your fishing view: within 8° of the view of your last [cast] before it (yaw, pitch; null: no cast with a view, it counts). */
        fun fished(yaw: Double, pitch: Double, cast: Pair<Double, Double>?) = cast == null || homeDistance(yaw, pitch, cast.first, cast.second) <= BACK_NONE_DEG

        /**
         * Your turn back rate: of the fights ending more than 8° from where they started, the share you turned back from
         * within 3 s, leaving out those the next fight cut short (the killer's next fight cuts its own short as well).
         * Counted over the fights that started at your fishing view ([fished]) once there are [min] of them, else over all;
         * null with fewer.
         */
        fun rate(turned: Int, beyond: Int, interrupted: Int, homeTurned: Int, home: Int, homeInterrupted: Int, min: Int): Double? = when {
            home - homeInterrupted >= min -> homeTurned.toDouble() / (home - homeInterrupted)
            beyond - interrupted >= min -> turned.toDouble() / (beyond - interrupted)
            else -> null
        }
    }

    /** Angle between your view in [rot] and creature [c] (at half its height) in degrees. */
    private fun turnErr(rot: RotSample, c: DoubleArray): Double {
        val height = if (c[3] > 0) c[3] else DEFAULT_HEIGHT
        val ex = c[0] - rot.x
        val ey = c[1] + height * TURN_AIM_SHARE - (rot.y + EYE)
        val ez = c[2] - rot.z
        val len = sqrt(ex * ex + ey * ey + ez * ez)
        if (len < 1e-6) return 0.0
        return Math.toDegrees(acos(((rot.dir[0] * ex + rot.dir[1] * ey + rot.dir[2] * ez) / len).coerceIn(- 1.0, 1.0)))
    }

    /** Angular half-size of creature [c]'s box seen from your eye in [rot], at least [MIN_BOX_DEG]. */
    private fun boxDeg(rot: RotSample, c: DoubleArray): Double {
        val height = if (c[3] > 0) c[3] else DEFAULT_HEIGHT
        val width = if (c.size > 4 && c[4] > 0) c[4] else DEFAULT_WIDTH
        val dist = sqrt((c[0] - rot.x).let { it * it } + (c[1] + height * TURN_AIM_SHARE - rot.y - EYE).let { it * it } + (c[2] - rot.z).let { it * it })
        return max(MIN_BOX_DEG, Math.toDegrees(atan2(max(height, width) / 2, dist)))
    }

    /** The distance the killer turns back by (HumanAim's): pitch only when the goal looks straight up or down. */
    private fun homeDistance(yaw: Double, pitch: Double, goalYaw: Double, goalPitch: Double) =
        if (abs(goalPitch) >= 89.5) abs(pitch - goalPitch) else ViewMotion.angle(yaw, pitch, goalYaw, goalPitch)

    /** The first line in [from]..[to] that shows creature [id]. */
    private fun firstWith(rots: List<RotSample>, id: Int, from: Long, to: Long): RotSample? {
        var i = rotIndex(rots, from - 1) + 1
        while (i < rots.size && rots[i].t <= to) {
            if (id in rots[i].creatures) return rots[i]
            i ++
        }
        return null
    }

    /** Index of the last of the sorted [times] at or before [t], -1 for none. */
    private fun lastAtOrBefore(times: LongArray, t: Long): Int {
        var lo = 0
        var hi = times.size - 1
        var found = - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (times[mid] <= t) {
                found = mid
                lo = mid + 1
            }
            else hi = mid - 1
        }
        return found
    }

    /**
     * [Motor] from every movement of all recordings: the measured statistics (each kept at its default below
     * [MIN_MOVES] samples, a bin's below [MIN_BIN]), then each planning parameter as its default moved by the learned
     * statistic against the reference recordings' (DESIGN-HUMAN amendments §6.2), so the reference recordings give the
     * defaults back.
     */
    private fun learnMotor(views: ViewLog, m: Motor) {
        val all = views.moves
        val m6 = all.filter { it.amp >= 6 }
        m.moves = m6.size
        if (m6.size >= MIN_MOVES) {
            // the analysis fitted the law from 3° on: the small movements pin its intercept
            val fit = all.filter { it.amp >= 3 && it.dur > 0 }
            val (a, b) = lad(fit.map { x(it.amp) }, fit.map { it.dur })
            m.moveLawA = a
            m.moveLawB = b
            val ratios = m6.map { it.dur / max(1.0, a + b * x(it.amp)) }
            m.moveSigma = sd(ratios.map(::ln))
            m.slowShare = ratios.count { it > 2 }.toDouble() / ratios.size
            m.fastShare = ratios.count { it < 0.5 }.toDouble() / ratios.size
        }
        fun moveLaw(amp: Double) = max(1.0, m.moveLawA + m.moveLawB * x(amp))

        val strokes = m6.flatMap { it.strokes }.filter { it.amp >= 2 && it.dur > 0 }
        m.strokes = strokes.size
        if (strokes.size >= MIN_MOVES) {
            val (a, b) = lad(strokes.map { x(it.amp) }, strokes.map { it.dur })
            m.strokeLawA = a
            m.strokeLawB = b
            val ratios = strokes.map { it.dur / refStroke(it.amp) }
            m.strokeK = ViewMotion.quantile(ratios, 0.5)
            val lr = ratios.map(::ln)
            m.strokeSigma = sd(lr)
            m.strokeSigmaRobust = (ViewMotion.quantile(lr, 0.75) - ViewMotion.quantile(lr, 0.25)) / 1.349
        }
        fun strokeLaw(amp: Double) = m.strokeK * refStroke(amp)

        for (bin in Motor.BINS.indices) {
            val inBin = m6.filter { m.bin(it.amp) == bin }
            m.binMoves[bin] = inBin.size
            if (inBin.size < MIN_BIN) continue
            m.multiShare[bin] = inBin.count { it.strokes.size >= 2 }.toDouble() / inBin.size
            m.binPeakP50[bin] = ViewMotion.quantile(inBin.map { it.peak }, 0.5)
        }

        val m20 = all.filter { it.amp >= 20 }
        val firstShares = m20.filter { it.strokes.size == 2 }.map { it.strokes[0].amp / it.amp }
        m.firstShareN = firstShares.size
        if (firstShares.size >= MIN_MOVES) {
            m.firstShareP10 = ViewMotion.quantile(firstShares, 0.1)
            m.firstShareP50 = ViewMotion.quantile(firstShares, 0.5)
            m.firstShareP90 = ViewMotion.quantile(firstShares, 0.9)
        }
        val back = views.backFirst
        m.backFirstN = back.size
        if (back.isNotEmpty()) {
            m.backFirstP50 = ViewMotion.quantile(back, 0.5)
            m.backFirstP90 = ViewMotion.quantile(back, 0.9)
        }
        val fromLooks = views.returnFirst
        m.returnFirstN = fromLooks.size
        if (fromLooks.isNotEmpty()) {
            m.returnFirstP50 = ViewMotion.quantile(fromLooks, 0.5)
            m.returnFirstP90 = ViewMotion.quantile(fromLooks, 0.9)
        }
        val valleys = m20.mapNotNull { it.valley }
        if (valleys.size >= MIN_MOVES) {
            m.crossP50 = ViewMotion.quantile(valleys.map { it.cross }, 0.5)
            m.overlapShare = valleys.count { it.pauseMs == 0.0 }.toDouble() / valleys.size
        }
        if (m20.size >= MIN_MOVES) {
            m.overshootShare = m20.count { it.over > 1 }.toDouble() / m20.size
            m.overshoot3Share = m20.count { it.over > 3 }.toDouble() / m20.size
            m20.filter { it.over > 1 }.map { it.over / it.amp }.takeIf { it.isNotEmpty() }?.let { m.overshootSizeP50 = ViewMotion.quantile(it, 0.5) }
            m.tpkP10 = ViewMotion.quantile(m20.map { it.tpk }, 0.1)
            m.tpkP50 = ViewMotion.quantile(m20.map { it.tpk }, 0.5)
            m.tpkP90 = ViewMotion.quantile(m20.map { it.tpk }, 0.9)
            val straight = m20.filter { abs(it.dYaw) > 5 && abs(it.dPitch) > 5 }.map { it.mStraight }
            if (straight.isNotEmpty()) {
                m.straightP10 = ViewMotion.quantile(straight, 0.1)
                m.straightP50 = ViewMotion.quantile(straight, 0.5)
            }
        }
        val ends = all.filter { it.amp >= 30 }.map { it.endShare }.filter { it.isFinite() }
        if (ends.size >= MIN_MOVES) {
            m.endP50 = ViewMotion.quantile(ends, 0.5)
            m.endOverHalf = ends.count { it > 0.5 }.toDouble() / ends.size
        }
        val lags = all.map { it.halfLag }.filter { it.isFinite() }
        m.lagN = lags.size
        if (lags.size >= MIN_MOVES) {
            m.lagP10Ms = ViewMotion.quantile(lags.map(::abs), 0.1)
            m.lagP50Ms = ViewMotion.quantile(lags.map(::abs), 0.5)
            m.lagP90Ms = ViewMotion.quantile(lags.map(::abs), 0.9)
            m.yawFirst = lags.count { it > 0 }.toDouble() / lags.size
        }
        if (all.size >= MIN_MOVES) {
            m.gcP99 = ViewMotion.quantile(all.map { it.peak }, 0.99)
            m.mouseP99 = ViewMotion.quantile(all.map { it.mPeak }, 0.99)
            m.gcMax = all.maxOf { it.peak }
            m.mouseMax = all.maxOf { it.mPeak }
        }
        if (views.gaps.size >= MIN_MOVES) {
            m.gapP10Ms = ViewMotion.quantile(views.gaps, 0.1)
            m.gapP50Ms = ViewMotion.quantile(views.gaps, 0.5)
            m.gapP90Ms = ViewMotion.quantile(views.gaps, 0.9)
        }
        // near the pole a yaw change moves the hand far for little view: duration against the law, relative to the others'
        val pole = all.filter { abs(it.p0) >= 70 && abs(it.dYaw) >= 60 }
        m.poleMoves = pole.size
        val level = all.filter { abs(it.p0) < 50 && abs(it.p1) < 50 && it.amp >= 20 }
        if (pole.size >= MIN_MOVES && level.size >= MIN_MOVES) {
            m.poleRatio = ViewMotion.quantile(pole.map { it.dur / moveLaw(it.amp) }, 0.5) / ViewMotion.quantile(level.map { it.dur / moveLaw(it.amp) }, 0.5)
        }
        // a deliberate look straight down (a Hyperion's): mostly pitch, down to 85° or more
        val lookDowns = all.filter { it.dPitch >= 30 && it.dPitch >= 0.7 * it.mPath && it.p1 >= 85 }
        m.lookDowns = lookDowns.size
        if (lookDowns.size >= MIN_MOVES) m.lookDownRatio = ViewMotion.quantile(lookDowns.map { it.dur / strokeLaw(it.amp) }, 0.5)

        m.restTicks = views.restTicks
        m.fightTicks = views.fightTicks
        if (views.restTicks >= MIN_REST_TICKS) {
            m.restChanged = views.restChanged.toDouble() / views.restTicks
            if (views.restChanged > 0) {
                m.restFollow = views.restFollow.toDouble() / views.restChanged
                m.restBoth = views.restBoth.toDouble() / views.restChanged
                m.restYaw = views.restYaw.toDouble() / views.restChanged
                m.restStepP50 = ViewMotion.quantile(views.restSteps, 0.5)
            }
        }
        m.restHandAxes = views.restHand.size
        m.restPoleAxes = views.restPole.size
        if (views.fightTicks >= MIN_FIGHT_TICKS) m.stillShare = views.fightStill.toDouble() / views.fightTicks

        // planning parameters
        val d = Motor()
        if (strokes.size >= MIN_MOVES) {
            m.lawA = PLAN_LAW_A * m.strokeK
            m.lawB = PLAN_LAW_B * m.strokeK
            m.sigma = m.strokeSigma
        }
        for (bin in Motor.BINS.indices) {
            if (m.binMoves[bin] >= MIN_BIN) m.multiP[bin] = (d.multiP[bin] * m.multiShare[bin] / REF_MULTI[bin]).coerceIn(0.05, 0.9)
        }
        if (m.firstShareN >= MIN_MOVES) m.firstMu = firstMuOf(m.firstShareP90)
        WaysBack.plans(back, fromLooks).let { (turnBacks, looks) ->
            m.backFirstMu = turnBacks
            m.returnFirstMu = looks
        }
        if (m6.size >= MIN_MOVES) m.slowP = (PLAN_SLOW * m.slowShare / REF_SLOW).coerceIn(0.01, 0.5)
        if (m.lagN >= MIN_MOVES) m.axisLagMs = PLAN_LAG_MS * m.lagP50Ms / REF_LAG_MS
        if (m20.size >= MIN_MOVES) m.tauMean = (PLAN_TAU + (m.tpkP50 - REF_TPK)).coerceIn(0.2, 0.8)
        if (valleys.size >= MIN_MOVES) {
            m.crossSd = PLAN_CROSS * m.crossP50 / REF_CROSS
            m.overlapP = m.overlapShare
        }
        if (views.restTicks >= MIN_REST_TICKS) m.restStart = (PLAN_REST * m.restChanged / REF_REST).coerceIn(0.0, 0.2)
        if (views.restHand.size >= MIN_REST_AXES) RestRules.shares(views.restHand, REST_STEPS_MAX)?.let { m.restSteps = it }
        if (views.restPole.size >= MIN_POLE_AXES) RestRules.lognormal(views.restPole, REST_POLE_MAX)?.let { (p50, sigma) ->
            m.restPoleP50 = p50
            m.restPoleSigma = sigma
        }
        if (all.size >= MIN_MOVES) {
            m.capGc = m.gcP99.coerceAtMost(HARD_CAP)
            m.capMouse = m.mouseP99.coerceAtMost(HARD_CAP)
        }
        if (pole.size >= MIN_MOVES && level.size >= MIN_MOVES) m.poleFactor = m.poleRatio.coerceIn(0.8, 1.6)
        if (lookDowns.size >= MIN_MOVES) m.lookDownFactor = m.lookDownRatio.coerceIn(0.3, 1.2)
    }

    /** log2(1 + A / 6): the duration laws' amplitude term. */
    private fun x(amp: Double) = log2(1 + amp / 6)

    /** The reference recordings' sub-stroke duration law. */
    private fun refStroke(amp: Double) = REF_STROKE_A + REF_STROKE_B * x(amp)

    /** Least absolute deviations fit of y = a + b x: for every b on a 0.25 grid the best a is the median of y - b x. */
    private fun lad(x: List<Double>, y: List<Double>): Pair<Double, Double> {
        var bestErr = Double.MAX_VALUE
        var best = 0.0 to 0.0
        val r = ArrayList<Double>(x.size)
        var b = 0.0
        while (b <= 250.0) {
            r.clear()
            for (i in x.indices) r += y[i] - b * x[i]
            val a = ViewMotion.quantile(r, 0.5)
            val err = r.sumOf { abs(it - a) }
            if (err < bestErr) {
                bestErr = err
                best = a to b
            }
            b += 0.25
        }
        return best
    }

    private fun sd(a: List<Double>): Double {
        if (a.size < 2) return 0.0
        val mean = a.average()
        return sqrt(a.sumOf { (it - mean) * (it - mean) } / (a.size - 1))
    }

    private fun dist(a: List<Double>, min: Int = 1): Dist? =
        if (a.size < min) null else Dist(a.size, ViewMotion.quantile(a, 0.1), ViewMotion.quantile(a, 0.5), ViewMotion.quantile(a, 0.9))

    /**
     * [BehaviorProfile.Policy] from the turns of all recordings: every class's measurements as they are, the distributions
     * over all of them only from [BehaviorProfile.Policy.MIN_SAMPLES] samples on (else the reference recordings' values stay). The
     * slack of a correction is its start error beyond the class's tolerance: the creature's box for a melee class, the
     * weapon's learned aim for an aimed one (none for an area weapon or a fishing rod's bobber, which never turn).
     */
    private fun learnPolicy(views: ViewLog, profile: Profile) {
        val policy = profile.policy
        val min = BehaviorProfile.Policy.MIN_SAMPLES
        for ((cls, start, box) in views.corrections) {
            val item = cls.substringBefore(':')
            val tolerance = when {
                cls == "none" || item == "?" -> null
                cls.substringAfter(':') == "L" -> box
                item in profile.fishingRods -> null
                else -> WeaponPlanner.plan(profile, item)?.aimDeg?.takeIf { it < 90.0 }
            } ?: continue
            views.all.slack += start - tolerance
            views.turns(cls).slack += start - tolerance
        }
        fun TurnStats.fill(log: TurnLog, min: Int) {
            creatures = log.creatures
            turned = log.turned
            dist(log.swapLatency, min)?.let { swapLatency = it }
            dist(log.spawnLatency, min)?.let { spawnLatency = it }
            dist(log.stopErr, min)?.let { stopErr = it }
            dist(log.corrStart, min)?.let { corrStart = it }
            dist(log.corrEnd, min)?.let { corrEnd = it }
            dist(log.corrGap, min)?.let { corrGap = it }
            dist(log.slack, min)?.let { slack = it }
        }
        policy.all.fill(views.all, min)
        for ((cls, log) in views.classes) policy.classes[cls] = TurnStats().apply { fill(log, 1) }
        policy.turnFights = views.turnFights
        if (views.ticksTurned >= MIN_FIGHT_TICKS) policy.changedTurned = views.changedTurned.toDouble() / views.ticksTurned
        if (views.ticksOther >= MIN_FIGHT_TICKS) policy.changedOther = views.changedOther.toDouble() / views.ticksOther
        policy.backFights = views.backFights
        policy.backBeyond = views.backBeyond
        policy.backTurned = views.backTurned
        policy.backInterrupted = views.backInterrupted
        policy.backNone = views.backNone
        policy.backHome = views.backHome
        policy.backHomeTurned = views.backHomeTurned
        policy.backHomeInterrupted = views.backHomeInterrupted
        BackRules.rate(views.backTurned, views.backBeyond, views.backInterrupted, views.backHomeTurned, views.backHome, views.backHomeInterrupted, min)?.let { policy.backRate = it }
        dist(views.backLatency, min)?.let { policy.backLatency = it }
        dist(views.backRest, min)?.let { policy.backRest = it }
        dist(views.backCastErr, min)?.let { policy.backCastErr = it }
        dist(views.backCastLag, min)?.let { policy.backCastLag = it }
        policy.looks = learnLooks(views, profile)
    }

    /**
     * [BehaviorProfile.Looks] from every counted creature: per class and over all of them (creatures right overhead
     * apart) your first turns and the creature-seconds at risk by age, the first-moment share of the weapon plans' free
     * creatures, where your turns stopped, and your return after the first turns no plan needed. A creature is free when
     * its plan would not turn for it at 300 ms ([lookTolerance]). Too few samples keep a distribution's default.
     */
    private fun learnLooks(views: ViewLog, profile: Profile): Looks {
        val min = BehaviorProfile.Policy.MIN_SAMPLES
        val looks = Looks(all = LookStats())
        val bins = Looks.AGE_BINS
        val latencies = ArrayList<Double>()
        val gaps = ArrayList<Double>()
        for (s in views.looks) {
            val tolerance = lookTolerance(s.cls, s.box300, profile)
            val free = s.e300 <= tolerance
            val turned = s.firstAge >= 0
            if (turned && free) {
                looks.backLooks ++
                if (s.back >= 0) {
                    looks.backTurned ++
                    latencies += s.back.toDouble()
                    gaps += s.backGap.toDouble()
                }
            }
            if (s.overhead) {
                looks.overhead ++
                if (turned) looks.overheadTurned ++
                continue
            }
            for (st in listOf(looks.all, looks.classes.getOrPut(s.cls, ::LookStats))) {
                st.creatures ++
                if (turned) {
                    st.turned ++
                    st.events[bins.indexOfLast { s.firstAge >= it }] ++
                }
                val end = if (turned) s.firstAge else s.life
                for (b in bins.indices) {
                    val lo = max(bins[b], Looks.FROM_MS)
                    val hi = min(bins.getOrNull(b + 1) ?: Long.MAX_VALUE, end)
                    if (hi > lo) st.exposureS[b] += (hi - lo) / 1000.0
                }
                if (tolerance.isFinite() && free) {
                    st.freeAtFirst ++
                    if (s.firstAge in 0 until Looks.FIRST_MS) st.freeAtFirstTurned ++
                }
            }
        }
        dist(views.lookStop, min)?.let { looks.stopShare = it }
        if (looks.backLooks >= min) looks.backRate = looks.backTurned.toDouble() / looks.backLooks
        dist(latencies, min)?.let { looks.backLatency = it }
        dist(gaps, min)?.let { looks.backGap = it }
        return looks
    }

    /**
     * The error a plan of class [cls] accepts without a turn ([box]: the creature's angular half-size), the slack's
     * tolerance: the box for a melee class, the weapon's learned aim for an aimed one. Infinite where no turn is ever
     * needed (no clicks, a fishing rod's right clicks and holds, an area weapon), NaN where none is known (a teleport
     * blade's look-down, an item without a plan): never free.
     */
    private fun lookTolerance(cls: String, box: Double, profile: Profile): Double {
        val item = cls.substringBefore(':')
        return when {
            cls == "none" || item == "?" -> Double.POSITIVE_INFINITY
            cls.substringAfter(':') == "L" -> box
            item in profile.fishingRods -> Double.POSITIVE_INFINITY
            item in WeaponPlanner.TELEPORT_BLADES -> Double.NaN
            else -> WeaponPlanner.plan(profile, item)?.aimDeg?.let { if (it >= 90.0) Double.POSITIVE_INFINITY else it } ?: Double.NaN
        }
    }

    /**
     * [BehaviorProfile.Timing]: for every timing list, its slow share and whether slow ones follow slow ones, counted within
     * each recording (a family keeps its reference values below [MIN_STREAK] samples, its stay rate below [MIN_SLOW_PAIRS]
     * slow samples with a successor); and the melee rhythm's lag-1, matched by the mode-keeping probability that gives it.
     */
    private fun learnTiming(views: ViewLog, profile: Profile) {
        val timing = profile.timing
        for ((name, list) in timingLists(profile)) {
            val runs = views.ranges[name].orEmpty().map { r -> list.subList(r.first, r.last + 1) }.filter { it.isNotEmpty() }
            val n = runs.sumOf { it.size }
            if (n < MIN_STREAK) continue
            val threshold = SLOW_FACTOR * ViewMotion.quantile(runs.flatten().map { it.toDouble() }, 0.5)
            var slow = 0
            var pairs = 0
            var stays = 0
            for (run in runs) for ((i, v) in run.withIndex()) {
                if (v > threshold) slow ++
                if (i > 0 && run[i - 1] > threshold) {
                    pairs ++
                    if (v > threshold) stays ++
                }
            }
            val pi = slow.toDouble() / n
            val stay = if (pairs >= MIN_SLOW_PAIRS) stays.toDouble() / pairs else Timing.DEFAULTS[name]?.second ?: pi
            timing.streaks[name] = Streak(n, threshold, pi, stay, Timing.enterRate(pi, stay), pairs, lag1(runs))
        }
        val gaps = views.meleeRuns.sumOf { it.size }
        timing.meleeGaps = gaps
        if (gaps >= MIN_STREAK) {
            timing.meleeLag1 = lag1(views.meleeRuns)
            timing.meleeRho = fitRho(views.meleeRuns.flatten(), timing.meleeLag1)
        }
    }

    /** Correlation of ln(sample) with ln(the sample before it), within each run (0 with fewer than 3 pairs). */
    private fun lag1(runs: List<List<Int>>): Double {
        val xs = ArrayList<Double>()
        val ys = ArrayList<Double>()
        for (run in runs) for (i in 1 until run.size) {
            xs += ln(max(1, run[i - 1]).toDouble())
            ys += ln(max(1, run[i]).toDouble())
        }
        if (xs.size < 3) return 0.0
        val mx = xs.average()
        val my = ys.average()
        var sxy = 0.0
        var sxx = 0.0
        var syy = 0.0
        for (i in xs.indices) {
            sxy += (xs[i] - mx) * (ys[i] - my)
            sxx += (xs[i] - mx) * (xs[i] - mx)
            syy += (ys[i] - my) * (ys[i] - my)
        }
        return if (sxx > 0 && syy > 0) sxy / sqrt(sxx * syy) else 0.0
    }

    /**
     * The probability of keeping the previous mode (fast below [MELEE_SLOW_MS], or slow) whose draws give a lag-1 closest
     * to [target]: each draw keeps the mode with that probability and resamples within it, else resamples from all [gaps]
     * (samples of 400 ms or more varied by exp(N(0, 0.1)), as the timing model draws them). Seeded, so a relearn of the
     * same recordings gives the same value.
     */
    private fun fitRho(gaps: List<Int>, target: Double): Double {
        val fast = gaps.filter { it < MELEE_SLOW_MS }
        val slow = gaps.filter { it >= MELEE_SLOW_MS }
        if (fast.isEmpty() || slow.isEmpty()) return Timing().meleeRho
        var best = Timing().meleeRho
        var bestErr = Double.MAX_VALUE
        for (k in 0 .. 19) {
            val rho = k * 0.05
            val rng = java.util.Random(4L + k)
            var mode: Boolean? = null
            val draws = ArrayList<Int>(20_000)
            repeat(20_000) {
                val keep = mode
                val x = if (keep != null && rng.nextDouble() < rho) (if (keep) slow else fast).let { it[rng.nextInt(it.size)] }
                else gaps[rng.nextInt(gaps.size)].also { mode = it >= MELEE_SLOW_MS }
                draws += if (x >= 400) (x * exp(0.1 * rng.nextGaussian())).toInt() else x
            }
            val err = abs(lag1(listOf(draws)) - target)
            if (err < bestErr) {
                bestErr = err
                best = rho
            }
        }
        return best
    }

    private fun learnMenus(events: List<Ev>, profile: Profile) {
        var openAt = - 1L
        var clickAt = - 1L
        for (ev in events) when (ev.type) {
            "menu" -> {
                openAt = if (ev.o.str("title").endsWith("Loadouts") || ev.o.str("title").endsWith("Loadout")) ev.t else - 1L
                clickAt = - 1L
            }
            "menuClick" -> if (openAt >= 0 && clickAt < 0) {
                val gap = ev.t - openAt
                if (gap in 50 .. 4000) profile.menuOpenToClickMs += gap.toInt()
                clickAt = ev.t
            }
            "menuClose" -> {
                if (clickAt >= 0 && ev.t - clickAt in 30 .. 3000) profile.menuClickToCloseMs += (ev.t - clickAt).toInt()
                openAt = - 1L
                clickAt = - 1L
            }
        }
    }

    /** How long after a creature appeared you swapped from the rod to a weapon, and how long after that you typed /loadout. */
    private fun learnSwaps(events: List<Ev>, fights: Map<Int, Fight>, profile: Profile) {
        val rods = profile.fishingRods
        var rodInHand = false
        val swappedFor = HashSet<Int>()
        for ((i, ev) in events.withIndex()) {
            if (ev.type != "slot") continue
            val fromRod = rodInHand
            rodInHand = ev.o.str("item") in rods
            if (! fromRod || rodInHand || ev.o.str("item").isEmpty()) continue
            // the newest of your creatures still around is the one you swapped for (once per creature)
            val fight = fights.values.lastOrNull { it.t <= ev.t && it.end > ev.t } ?: continue
            if (ev.t - fight.t !in MIN_REACTION_MS .. REACTION_MS || ! swappedFor.add(fight.id)) continue
            profile.swapReactionMs += (ev.t - fight.t).toInt()
            // /loadout with the weapon in hand, before the fight is over and the rod comes back
            val next = (i + 1 until events.size).firstOrNull { events[it].isLoadoutCmd || events[it].type == "slot" && events[it].o.str("item") in rods }?.let(events::get)
            if (next != null && next.isLoadoutCmd && next.t <= fight.end && next.t - ev.t <= REACTION_MS) profile.weaponToLoadoutMs += (next.t - ev.t).toInt()
        }
    }

    /**
     * A loadout menu you equipped in a fight (one of your creatures alive) and closed: your first attack click after it,
     * within [REACTION_MS] and with no item taken, menu or command in between (a swap after the menu has its own timing).
     */
    private fun learnCloseToClick(events: List<Ev>, fights: Map<Int, Fight>, profile: Profile) {
        val rods = profile.fishingRods
        var equipped = false
        for ((i, ev) in events.withIndex()) when (ev.type) {
            "menu" -> equipped = false
            "menuClick" -> if (loadoutOf(ev) != null) equipped = true
            "menuClose" -> {
                val fight = equipped && fights.values.any { it.t <= ev.t && it.end > ev.t }
                equipped = false
                if (! fight) continue
                for (j in i + 1 until events.size) {
                    val next = events[j]
                    if (next.t - ev.t > REACTION_MS || next.type in CLOSE_CLICK_BREAKS) break
                    // a reel or cast is no attack
                    if (next.type != "click" || next.o.bool("auto") || next.o.str("b") == "R" && next.o.str("item") in rods) continue
                    profile.menuCloseToClickMs += (next.t - ev.t).toInt()
                    break
                }
            }
        }
    }

    /** After the last death of a fight you equipped a loadout for: your /loadout back, then the rod once its menu closed. */
    private fun learnRestore(fight: Fight, events: List<Ev>, profile: Profile) {
        if (events.none { it.type == "menuClick" && it.t in fight.t - 2000 .. fight.deathAt && loadoutOf(it) != null }) return
        // a new catch first: you went on fishing in the fight loadout
        val i = events.indexOfFirst { it.t > fight.deathAt && (it.isLoadoutCmd || it.type == "spawn" && it.o.bool("own") && ! it.o.bool("kept")) }
        if (i < 0 || ! events[i].isLoadoutCmd || events[i].t - fight.deathAt > RESTORE_MS) return
        // other items first (a hotspot relocation): the /loadout was no reaction to the death, the menu is still timed
        if (events.subList(0, i).none { it.t > fight.deathAt && it.type == "slot" }) profile.deathToRestoreMs += (events[i].t - fight.deathAt).toInt()
        val close = (i + 1 until events.size).firstOrNull { events[it].type == "menuClose" } ?: return
        val rod = (close + 1 until events.size).firstOrNull { events[it].type == "slot" }?.let(events::get) ?: return
        val gap = rod.t - events[close].t
        if (rod.o.str("item") in profile.fishingRods && gap <= MENU_ROD_MS) profile.menuCloseToRodMs += gap.toInt()
    }

    /** Loadouts you equipped with none of your creatures around and then fished in (the last one before the cast counts). */
    private fun learnFishingLoadouts(events: List<Ev>, fights: Map<Int, Fight>, profile: Profile) {
        var pending = 0
        var pendingAt = 0L
        for (ev in events) when (ev.type) {
            "menuClick" -> {
                val loadout = loadoutOf(ev) ?: continue
                pending = if (fights.values.any { it.t <= ev.t && it.end > ev.t }) 0 else loadout
                pendingAt = ev.t
            }
            "cast" -> {
                if (pending != 0 && ev.t - pendingAt <= FISHING_LOADOUT_CAST_MS) profile.fishingLoadouts.merge(pending, 1, Int::plus)
                pending = 0
            }
        }
    }

    /** The loadout (in /loadout order, 12 per page) a loadout menu click equips, null for any other click. */
    private fun loadoutOf(click: Ev): Int? {
        val page = loadoutTitle.find(click.o.str("title"))?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val i = loadoutSlots.indexOf(click.o.int("slot")).takeIf { it >= 0 } ?: return null
        return (page - 1) * loadoutSlots.size + i + 1
    }

    private val Ev.isLoadoutCmd get() = type == "cmd" && o.str("cmd") in loadoutCommands

    /** Unit view vector of a Minecraft yaw and pitch (yaw 0 looks to +z, pitch 90 straight down). */
    private fun viewDir(yaw: Double, pitch: Double): DoubleArray {
        val y = Math.toRadians(yaw)
        val p = Math.toRadians(pitch)
        return doubleArrayOf(- sin(y) * cos(p), - sin(p), cos(y) * cos(p))
    }

    private fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    /** Great-circle angle between two views in degrees: a yaw difference counts for less the steeper you look. */
    private fun angle(a: DoubleArray, b: DoubleArray) = Math.toDegrees(acos(dot(a, b).coerceIn(- 1.0, 1.0)))

    fun wrap(deg: Float): Float {
        var d = deg % 360f
        if (d >= 180f) d -= 360f
        if (d < - 180f) d += 360f
        return d
    }

    /** Keeps the newest samples of every list. */
    private fun Profile.cap() {
        fun MutableList<*>.keepLast(n: Int) {
            if (size > n) subList(0, size - n).clear()
        }
        listOf(
            biteReactionMs, castDelayMs, catchRecastMs, bobberDeathMs, bobberMissMs, spawnReactionMs, firstHitMs, clickLeftMs, clickLeftRodMs,
            clickLeftWeaponMs, clickRightMs, afterKillMs, swapReactionMs, weaponToLoadoutMs, deathToRestoreMs, menuCloseToRodMs, menuCloseToClickMs,
            rodToCastMs, menuOpenToClickMs, menuClickToCloseMs
        ).forEach { it.keepLast(MAX_SAMPLES) }
        listOf(aimHeights, rightClickErr, meleeDistances).forEach { it.keepLast(MAX_SAMPLES) }
        turns.keepLast(MAX_TURNS)
        habits.values.forEach {
            it.killMs.keepLast(100)
            it.rodHitShare.keepLast(100)
        }
        // the four sample lists of a style are filled together, so cutting each to its newest keeps them aligned
        weaponStyles.values.forEach { s -> listOf(s.reach, s.dy, s.aimErr, s.pitch, s.holdMs, s.gapMs).forEach { it.keepLast(MAX_SAMPLES) } }
    }

    /** `ROD_OF_THE_SEA` -> `Rod Of The Sea`. */
    fun prettyId(id: String) = id.lowercase().split('_').joinToString(" ") { w -> w.replaceFirstChar(Char::uppercaseChar) }

    private fun JsonObject.str(key: String) = get(key)?.takeIf { it.isJsonPrimitive }?.asString ?: ""
    private fun JsonObject.long(key: String) = get(key)?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L
    private fun JsonObject.int(key: String) = get(key)?.takeIf { it.isJsonPrimitive }?.asInt ?: 0
    private fun JsonObject.intOrNull(key: String) = get(key)?.takeIf { it.isJsonPrimitive }?.asInt
    private fun JsonObject.double(key: String) = get(key)?.takeIf { it.isJsonPrimitive }?.asDouble ?: 0.0
    private fun JsonObject.bool(key: String) = get(key)?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
}

/**
 * Movements of your view, segmented the way the motor analysis measured them (`humanize/segment.js`): a movement starts
 * on a tick that turns the view faster than [THRESHOLD] deg/s and ends after [STILL_TICKS] slower ticks in a row, and it
 * splits into sub-strokes at speed valleys below [VALLEY] of the smaller neighbouring peak. Speeds are great-circle
 * degrees per tick, so a yaw swing while looking straight down counts for little. Pure: the replay test measures a bot
 * trace with the same code as your recordings.
 */
object ViewMotion {
    /** A tick turning the view faster than this (deg/s) is moving. */
    const val THRESHOLD = 15.0
    /** This many ticks in a row at or below [THRESHOLD] end a movement. */
    const val STILL_TICKS = 2
    /** A speed valley below this share of the smaller neighbouring peak splits two sub-strokes. */
    const val VALLEY = 0.7
    /** One client tick, ms. */
    const val TICK_MS = 50.0
    /** Lines without a tick counter further apart than this (wall clock) belong to separate runs. */
    const val MAX_GAP_MS = 120L
    /** Lines with tick counters further apart than this were not written in one go (the idle heartbeat is 20 ticks). */
    const val MAX_SPARSE_TICKS = 40L
    /** A faster step is no hand (his all-time maximum is 1679 deg/s): a warp, or an 11 ms tick in a recording without `tk`. */
    const val ARTEFACT_DEG_S = 2600.0
    /** A smaller yaw or pitch change is none: lines are rounded to 0.01°. */
    const val STILL_DEG = 0.005
    /** The view moves on this grid at the usual mouse sensitivity (0.15° a count). */
    const val GRID_DEG = 0.15

    /** A `rot` line: wall clock ms, the view, the client tick counter `tk` (-1 in older recordings) and the `tp` mark. */
    class Line(val t: Long, val yaw: Double, val pitch: Double, val tick: Long = - 1, val tp: Boolean = false)

    /** One tick of a run: wall clock ms, the view, and the step from the tick before in ms (0 for the first of a run). */
    class Tick(val t: Long, val yaw: Double, val pitch: Double, val dt: Double)

    /** A sub-stroke: great-circle amplitude (deg), duration (ms, half a tick off at each inner split) and peak speed (deg/s). */
    class Stroke(val amp: Double, val dur: Double, val peak: Double)

    /**
     * Where a multi-stroke movement's first sub-stroke ended, in mouse space as shares of the movement's way: along it and
     * across it (unsigned); and the still time (ticks of [THRESHOLD] deg/s or less) from that valley on.
     */
    class Valley(val along: Double, val cross: Double, val pauseMs: Double)

    /**
     * One movement: steps [first]..[last] of its run (step i goes from tick i - 1 to tick i), [cut] when its run ended
     * before it did. Times: [t0] (wall clock of tick first - 1) and [t1] (of tick last); [dur] the sum of its steps.
     * Angles in degrees, speeds in deg/s; [mAmp], [mPath] and [mPeak] are mouse space (hypot of yaw and pitch changes).
     */
    class Move(
        val first: Int, val last: Int, val cut: Boolean,
        val t0: Long, val t1: Long, val dur: Double,
        val amp: Double, val path: Double, val peak: Double,
        /** Time of the peak step's middle as a share of [dur]. */
        val tpk: Double,
        /** How far the view went past the end direction, and back behind the start, along the start-end great circle. */
        val over: Double, val back: Double,
        /** Speed peaks separated by a valley below [VALLEY] of the smaller one (as segment.js counts them). */
        val nsub: Int,
        /** Summed yaw change (unwrapped per step) and pitch change; views at the start and the end. */
        val dYaw: Double, val dPitch: Double, val y0: Double, val p0: Double, val y1: Double, val p1: Double,
        /** Pitch reaching half its way minus yaw reaching half of its own (> 0: yaw first), NaN unless both are over 5°. */
        val halfLag: Double,
        val mAmp: Double, val mPath: Double, val mPeak: Double,
        /** First and last step's speed as a share of [peak]. */
        val startShare: Double, val endShare: Double,
        val strokes: List<Stroke>,
        /** The first split, for movements of 2 or more speed peaks split by [VALLEY] (null otherwise). */
        val valley: Valley?
    ) {
        val straight get() = if (path > 0) amp / path else 1.0
        val mStraight get() = if (mPath > 0) mAmp / mPath else 1.0
    }

    /** A run cut into movements, with every step's great-circle change, speed, yaw and pitch change (index 0 unused). */
    class Segmentation(val run: List<Tick>, val moves: List<Move>, val step: DoubleArray, val speed: DoubleArray, val dYaw: DoubleArray, val dPitch: DoubleArray) {
        /** Steps inside a movement, cut ones too. */
        val inMove = BooleanArray(run.size).also { a -> moves.forEach { m -> for (i in m.first .. m.last) a[i] = true } }
    }

    /**
     * Contiguous runs of ticks from `rot` lines in file order. With tick counters a step takes 50 ms whatever the wall
     * clock says (a short tick is no speed spike), and the ticks a sparse recorder left out were still: the change up to
     * the next line happened in its last tick. Without them, lines more than [MAX_GAP_MS] apart start a new run. A
     * teleport line, and a step faster than [ARTEFACT_DEG_S], start one too, so neither is read as a movement.
     */
    fun runs(lines: List<Line>): List<List<Tick>> {
        val out = ArrayList<List<Tick>>()
        var cur = ArrayList<Tick>()
        var prev: Line? = null
        for (line in lines) {
            val p = prev
            prev = line
            if (p == null) {
                cur += Tick(line.t, line.yaw, line.pitch, 0.0)
                continue
            }
            val counted = p.tick >= 0 && line.tick >= 0
            val ticks = if (counted) line.tick - p.tick else 0L
            val dt = if (counted) TICK_MS else (line.t - p.t).toDouble()
            var broken = line.tp || (if (counted) ticks <= 0 || ticks > MAX_SPARSE_TICKS else line.t - p.t > MAX_GAP_MS || line.t <= p.t)
            if (! broken) {
                val gc = angle(p.yaw, p.pitch, line.yaw, line.pitch) / dt * 1000
                val mouse = hypot(wrap(line.yaw - p.yaw), line.pitch - p.pitch) / dt * 1000
                broken = gc > ARTEFACT_DEG_S || mouse > ARTEFACT_DEG_S
            }
            if (broken) {
                if (cur.size > 1) out += cur
                cur = ArrayList()
                cur += Tick(line.t, line.yaw, line.pitch, 0.0)
                continue
            }
            for (k in 1 until ticks) cur += Tick(p.t + (line.t - p.t) * k / ticks, p.yaw, p.pitch, TICK_MS)
            cur += Tick(line.t, line.yaw, line.pitch, dt)
        }
        if (cur.size > 1) out += cur
        return out
    }

    /** [run] cut into movements (see [ViewMotion]). */
    fun segment(run: List<Tick>): Segmentation {
        val n = run.size
        val step = DoubleArray(n)
        val speed = DoubleArray(n)
        val dYaw = DoubleArray(n)
        val dPitch = DoubleArray(n)
        // time of every tick from the run's start
        val at = DoubleArray(n)
        for (i in 1 until n) {
            val a = run[i - 1]
            val b = run[i]
            step[i] = angle(a.yaw, a.pitch, b.yaw, b.pitch)
            speed[i] = step[i] / b.dt * 1000
            dYaw[i] = wrap(b.yaw - a.yaw)
            dPitch[i] = b.pitch - a.pitch
            at[i] = at[i - 1] + b.dt
        }
        val moves = ArrayList<Move>()
        var i = 1
        while (i < n) {
            if (speed[i] <= THRESHOLD) {
                i ++
                continue
            }
            val first = i
            var last = i
            var still = 0
            var j = i + 1
            while (j < n) {
                if (speed[j] > THRESHOLD) {
                    last = j
                    still = 0
                }
                else if (++ still >= STILL_TICKS) break
                j ++
            }
            moves += describe(run, at, step, speed, dYaw, dPitch, first, last, cut = j >= n && still < STILL_TICKS)
            i = last + 1
        }
        return Segmentation(run, moves, step, speed, dYaw, dPitch)
    }

    private fun describe(run: List<Tick>, at: DoubleArray, step: DoubleArray, v: DoubleArray, dy: DoubleArray, dp: DoubleArray, first: Int, last: Int, cut: Boolean): Move {
        val a = run[first - 1]
        val b = run[last]
        val dur = at[last] - at[first - 1]
        val amp = angle(a.yaw, a.pitch, b.yaw, b.pitch)
        var path = 0.0
        var peak = 0.0
        var pk = first
        for (i in first .. last) {
            path += step[i]
            if (v[i] > peak) {
                peak = v[i]
                pk = i
            }
        }
        val tpk = ((at[pk - 1] + at[pk]) / 2 - at[first - 1]) / dur

        // progress along the start-end great circle: past the end is overshoot, behind the start a move away first
        var maxProg = 1.0
        var minProg = 0.0
        var over = 0.0
        if (amp > 0.5) {
            val u = dir(a.yaw, a.pitch)
            val w = dir(b.yaw, b.pitch)
            val c = dot(u, w)
            val arc = acos(c.coerceIn(- 1.0, 1.0))
            val along = DoubleArray(3) { (w[it] - u[it] * c) / sin(arc) }
            for (i in first .. last) {
                val d = dir(run[i].yaw, run[i].pitch)
                val pr = atan2(dot(d, along), dot(d, u)) / arc
                if (pr > maxProg) maxProg = pr
                if (pr < minProg) minProg = pr
            }
            over = (maxProg - 1) * amp
        }

        val vs = DoubleArray(last - first + 1) { v[first + it] }
        val peaks = vs.indices.filter { k -> vs[k] > (if (k > 0) vs[k - 1] else 0.0) && vs[k] >= (if (k < vs.size - 1) vs[k + 1] else 0.0) }
        var nsub = if (peaks.isEmpty()) 0 else 1
        for (k in 1 until peaks.size) {
            var mn = Double.MAX_VALUE
            for (m in peaks[k - 1] .. peaks[k]) mn = min(mn, vs[m])
            if (mn < VALLEY * min(vs[peaks[k - 1]], vs[peaks[k]])) nsub ++
        }

        // each axis's half-way time
        val cys = DoubleArray(vs.size + 1)
        val cps = DoubleArray(vs.size + 1)
        val ts = DoubleArray(vs.size + 1)
        ts[0] = at[first - 1]
        for (k in vs.indices) {
            cys[k + 1] = cys[k] + dy[first + k]
            cps[k + 1] = cps[k] + dp[first + k]
            ts[k + 1] = at[first + k]
        }
        fun half(arr: DoubleArray, tot: Double): Double {
            for (k in 1 until arr.size) if (abs(arr[k]) >= abs(tot) / 2) {
                val span = (abs(arr[k]) - abs(arr[k - 1])).takeIf { it != 0.0 } ?: 1.0
                return ts[k - 1] + (abs(tot) / 2 - abs(arr[k - 1])) / span * (ts[k] - ts[k - 1])
            }
            return Double.NaN
        }
        val cy = cys.last()
        val cp = cps.last()
        val yawOnSphere = cy * cos(Math.toRadians((a.pitch + b.pitch) / 2))
        val halfLag = if (abs(yawOnSphere) > 5 && abs(cp) > 5) half(cps, cp) - half(cys, cy) else Double.NaN

        var mPath = 0.0
        var mPeak = 0.0
        for (i in first .. last) {
            val h = hypot(dy[i], dp[i])
            mPath += h
            mPeak = max(mPeak, h / run[i].dt * 1000)
        }

        // sub-strokes: a split at the lowest tick between the last kept peak and the next one when that is a valley
        val kept = mutableListOf(peaks.firstOrNull() ?: 0)
        val cuts = mutableListOf<Int>()
        for (k in 1 until peaks.size) {
            var mn = Double.MAX_VALUE
            var lowAt = - 1
            for (m in kept.last() .. peaks[k]) if (vs[m] < mn) {
                mn = vs[m]
                lowAt = m
            }
            if (mn < VALLEY * min(vs[kept.last()], vs[peaks[k]])) {
                cuts += lowAt
                kept += peaks[k]
            }
            else if (vs[peaks[k]] > vs[kept.last()]) kept[kept.lastIndex] = peaks[k]
        }
        // a valley tick belongs half to each stroke
        val bounds = listOf(first - 1) + cuts.map { first + it } + last
        val strokes = (0 until bounds.size - 1).map { k ->
            val ra = run[bounds[k]]
            val rb = run[bounds[k + 1]]
            var d = at[bounds[k + 1]] - at[bounds[k]]
            if (k > 0) d -= TICK_MS / 2
            if (k + 2 < bounds.size) d -= TICK_MS / 2
            var pv = 0.0
            for (i in bounds[k] + 1 .. bounds[k + 1]) pv = max(pv, v[i])
            Stroke(angle(ra.yaw, ra.pitch, rb.yaw, rb.pitch), d, pv)
        }
        val valley = if (nsub >= 2 && cuts.isNotEmpty()) {
            val end = run[first + cuts[0]]
            val gx = wrap(b.yaw - a.yaw)
            val gy = b.pitch - a.pitch
            val g2 = gx * gx + gy * gy
            val px = wrap(end.yaw - a.yaw)
            val py = end.pitch - a.pitch
            var still = 0
            var k = cuts[0]
            while (k < vs.size && vs[k] <= THRESHOLD) {
                still ++
                k ++
            }
            if (g2 > 1e-9) Valley((px * gx + py * gy) / g2, abs(px * gy - py * gx) / g2, still * TICK_MS) else null
        }
        else null

        return Move(
            first, last, cut, a.t, b.t, dur, amp, path, peak, tpk, over, - minProg * amp, nsub, cy, cp, a.yaw, a.pitch, b.yaw, b.pitch, halfLag,
            hypot(cy, cp), mPath, mPeak, v[first] / peak, v[last] / peak, strokes, valley
        )
    }

    /** Great-circle angle between two views in degrees (haversine, as the analysis scripts). */
    fun angle(yaw1: Double, pitch1: Double, yaw2: Double, pitch2: Double): Double {
        val a = Math.toRadians(pitch1)
        val b = Math.toRadians(pitch2)
        val sp = sin((b - a) / 2)
        val sy = sin(Math.toRadians(wrap(yaw2 - yaw1)) / 2)
        val h = sp * sp + cos(a) * cos(b) * sy * sy
        return Math.toDegrees(2 * asin(sqrt(h.coerceIn(0.0, 1.0))))
    }

    /** [deg] into -180..180. */
    fun wrap(deg: Double) = ((deg + 180) % 360 + 360) % 360 - 180

    /** Unit view vector of a Minecraft yaw and pitch (yaw 0 looks to +z, pitch 90 straight down). */
    fun dir(yaw: Double, pitch: Double): DoubleArray {
        val y = Math.toRadians(yaw)
        val p = Math.toRadians(pitch)
        return doubleArrayOf(- sin(y) * cos(p), - sin(p), cos(y) * cos(p))
    }

    private fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    /** The [p] quantile with linear interpolation (as the analysis scripts), NaN for no values. */
    fun quantile(values: Collection<Double>, p: Double): Double {
        if (values.isEmpty()) return Double.NaN
        val s = values.sorted()
        val i = (s.size - 1) * p
        val lo = i.toInt()
        val hi = minOf(lo + 1, s.size - 1)
        return s[lo] + (s[hi] - s[lo]) * (i - lo)
    }
}

//#endif
