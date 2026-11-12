package com.laybalt.features.impl.fishing

//#if CHEAT

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.laybalt.features.impl.fishing.GlancePolicy.Phase
import com.laybalt.features.impl.fishing.MotorModel.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Your movements of 6° or more by what they were (looks/SPEC §1, the scripts ctx.js and classify.js ported), and your
 * outward looks among them with everything [BehaviorProfile.Glances] learns: the phase each one started in, whether it
 * came back, after what dwell and how far short, whether it chained, whether it went where your creature just died, and
 * how it moved. The minutes in place per phase are measured with it. One recording at a time ([read], [measure]); the
 * learner sums them, and the replay test measures your recordings and the bot's with the same code. Pure: no game state.
 */
object GlanceMeasure {
    /** A step of more than this many blocks between neighbouring lines is a teleport. */
    const val TP_JUMP_M = 4.0
    /** A world change or command this close to a move makes it an artefact, and its lines not in place. */
    const val WORLD_MS = 3000L
    /** A step this short (ms) that turns faster than [SHORT_TICK_SPEED] deg/s is a clock artefact. */
    const val SHORT_TICK_MS = 30.0
    const val SHORT_TICK_SPEED = 1000.0
    /** Walking keys: forward, left, back, right and sprint (jump and sneak are no walking). */
    const val LOCO_KEYS = 79
    /** Walking keys or this much horizontal movement within this long of a line: not in place. */
    const val LOCO_WINDOW_MS = 1000L
    const val LOCO_MOVED_M = 0.5
    /** A click with an item that moves you this close to a move. */
    const val MOBILITY_MS = 1500L
    /** The jump key and a rise of more than this: swimming up. */
    const val SWIM_RISE_M = 0.5
    private const val JUMP_KEY = 16
    /** The attack or use key down while the view moves: a weapon fired, swept across your creatures. */
    const val FIRE_KEYS = 384
    /** A teleport blade's look-down ends this far down or more. */
    const val LOOKDOWN_END_PITCH = 60.0
    /** Less of the move's amplitude than this on the yaw (on the sphere) or the pitch: pitch-only or yaw-only. */
    const val PITCH_ONLY_SHARE = 0.3
    /** A move is back at its start within max([RETURN_MIN_DEG], [RETURN_SHARE] of its amplitude) of it, looked for this long. */
    const val RETURN_MIN_DEG = 4.0
    const val RETURN_SHARE = 0.3
    const val RETURN_WINDOW_MS = 15_000L
    /** A cast within this long from within [NEW_VIEW_END_DEG] of the move's end, [NEW_VIEW_HOME_DEG] or more from the last cast's view: a new fishing view. */
    const val NEW_VIEW_CAST_MS = 10_000L
    const val NEW_VIEW_END_DEG = 6.0
    const val NEW_VIEW_HOME_DEG = 8.0
    /** A look within this many degrees of the last cast's view starts at home. */
    const val HOME_DEG = BehaviorProfile.Glances.HOME_DEG
    /** A move closing an error to this share of its start or less moved toward it (the learner's turn rule). */
    const val TURN_CLOSES = 0.6
    /** The rod in, this long after the last death of a fight: after the kill. */
    const val AFTER_KILL_MS = 3000L
    /** Movements counted: this many degrees or more. */
    const val MIN_DEG = 6.0
    /** A look came back within this long; looks watched this long (or back sooner) are at risk of it. */
    const val RETURNED_MS = 10_000L
    /** A look starting within this long of the previous one's end, from its end view, chained. */
    const val CHAIN_MS = 3000L
    /** Looks within this long after a death of your creatures count toward the death boost. */
    const val DEATH_MS = 1000L
    /** A creature of yours dead this long or less is a dead spot to look at. */
    const val DEAD_MS = 3000L
    /** A target needs an error of this many degrees at the move's start. */
    private const val TARGET_MIN_DEG = 6.0
    /** Other players' creatures that came up this long before a move (or [OTHER_AFTER_MS] after its start). */
    private const val OTHER_MS = 20_000L
    private const val OTHER_AFTER_MS = 200L
    /** The reel spot is one for this long after the reel. */
    private const val REEL_SPOT_MS = 10_000L
    /** A menu open within this long of a move, and one never closed in a recording open for [OPEN_MENU_MS]. */
    private const val MENU_PAD_MS = 100L
    private const val OPEN_MENU_MS = 5000L
    /** A fight's clicks reach this long past its last death. */
    private const val FIGHT_CLICK_PAD_MS = 50L
    /** A weapon:button class whose clicks miss your nearest creature by this much at their p75 (from [AREA_CLICKS] clicks) is an area weapon. */
    private const val AREA_DEG = 90.0
    private const val AREA_CLICKS = 10
    /** A move's return starts at most this long before the view is back. */
    private const val RETURN_MOVE_MS = 60L
    /** Lines without tick counters further apart than this (ms) were not written in one go. */
    private const val DENSE_MS = 120L
    /** Eye height; the height of a creature without a mob; another player's creature is looked at this high. */
    private const val EYE = 1.62
    private const val DEFAULT_HEIGHT = 1.8
    private const val OTHER_HEIGHT = 0.9
    /** Chance: where your dead creatures were, turned around you by these yaws. */
    private val BEARINGS = listOf(60.0, 120.0, 180.0, 240.0, 300.0)

    /** What a movement was, in the order they are tested (looks/SPEC §1.5). */
    enum class Kind { ARTEFACT, AIM, LOCOMOTION, SWEEP, LOOK_DOWN, TOOL, TURN_BACK, RETURN, BACK_TO_FIGHT, NEW_VIEW, LOOK }

    /** A `rot` line: the view, your feet, the walking and mouse keys, your creatures (id -> x, y, z, height, width). */
    class Line(
        val t: Long, val yaw: Double, val pitch: Double, val x: Double, val y: Double, val z: Double, val keys: Int,
        val creatures: Map<Int, DoubleArray>, val tick: Long = - 1, val tp: Boolean = false,
    )

    /** A cast with its view (null: the line's view at that time), a reel with the bobber's position (NaN: unknown). */
    class Cast(val t: Long, val view: View?)
    class Reel(val t: Long, val x: Double, val y: Double, val z: Double)

    /** One of your creatures from its spawn (or the line that made it yours) to its death. */
    class Life(val id: Int, val spawn: Long, val death: Long)

    /** Another player's creature: where it came up, and when it died ([Long.MAX_VALUE]: not seen). */
    class Other(val t: Long, val x: Double, val y: Double, val z: Double, val death: Long)

    /** A click of yours (no cast, no automatic one): its item, button (`L`, `R`), a held repeat, its error to your nearest creature (NaN: none). */
    class Click(val t: Long, val item: String, val button: String, val hold: Boolean, val err: Double)

    /**
     * One recording as the measure needs it: its lines in order, casts, reels, your creatures' lives, other players'
     * creatures, menus open (open, close), world changes and commands, slot changes (time, item), your clicks; the items
     * you fish with, which items are weapons, and your area weapon:button classes ([areaClasses] over all your
     * recordings; null: this one's clicks).
     */
    class Session(
        val lines: List<Line>, val casts: List<Cast>, val reels: List<Reel>, val lives: List<Life>, val others: List<Other>,
        val menus: List<LongArray>, val worlds: List<Long>, val slots: List<Pair<Long, String>>, val clicks: List<Click>,
        val rods: Set<String>, val isWeapon: (String) -> Boolean, val area: Set<String>? = null,
    )

    /**
     * The weapon:button classes ([clicks] of all your recordings, with their error to your nearest creature) whose clicks
     * miss it by [AREA_DEG] or more at their p75, from [AREA_CLICKS] clicks: an area weapon (a fight with it needs no aim).
     */
    fun areaClasses(clicks: List<Click>): Set<String> =
        clicks.filter { it.err.isFinite() }.groupBy { classOf(it) }.filter { (_, cs) -> cs.size >= AREA_CLICKS && ViewMotion.quantile(cs.map { it.err }, 0.75) >= AREA_DEG }.keys

    /** A movement of 6° or more (not cut): what it was, the phase at its start (null: none) and whether it started in place. */
    class Measured(val move: ViewMotion.Move, val kind: Kind, val phase: Phase?, val inPlace: Boolean)

    /**
     * An outward look that started in place in a phase: from your fishing view ([home]); its amplitude, yaw change on the
     * sphere and pitch change; watched for 10 s or back sooner ([risk]) and back within 10 s ([returned]); for one back
     * within 15 s the dwell before its return and what that return left (-1, NaN: none); the gap from the look it chained
     * from (-1: none); in the second after a death of yours ([deathBoosted]); where one of your creatures just died: a
     * candidate, closed on it, and chance's share; its duration, sub-strokes and overshoot.
     */
    class Look(
        val t0: Long, val phase: Phase, val home: Boolean, val amp: Double, val dYaw: Double, val dPitch: Double, val startPitch: Double,
        val risk: Boolean, val returned: Boolean, val dwellMs: Long, val returnErr: Double, val chainGapMs: Long, val deathBoosted: Boolean,
        val deadCandidate: Boolean, val deadHit: Boolean, val deadChance: Double, val dur: Double, val strokes: Int, val over: Double,
    )

    /**
     * Everything measured: the movements, the outward looks, minutes in place per phase (and in the second after a death
     * of yours), all minutes of lines, and your phase and whether you stood in place at any time.
     */
    class Result(
        val moves: List<Measured>, val looks: List<Look>, val minutes: DoubleArray, val deathMinutes: DoubleArray, val rotMinutes: Double,
        val phaseAt: (Long) -> Phase?, val inPlaceAt: (Long) -> Boolean,
    )

    /** A `rot` line of a recording ([o], parsed JSON) as [read] keeps it. */
    fun line(o: JsonObject) = Line(o.long("t"), o.dbl("yaw"), o.dbl("pitch"), o.dbl("x"), o.dbl("y"), o.dbl("z"), o.int("keys"), creatures(o),
        if (o.has("tk")) o.long("tk") else - 1L, o.bool("tp"))

    /**
     * [lines] of one recording (parsed JSON, in file order) as a [Session]; automatic clicks and casts' clicks left out.
     * [parsed]: its `rot` lines already read ([line], in file order), for a caller that does not keep them as JSON (they
     * are most of a recording); [lines] then holds the others.
     */
    fun read(lines: List<JsonObject>, rods: Set<String>, isWeapon: (String) -> Boolean, area: Set<String>? = null, parsed: List<Line> = emptyList()): Session {
        val rots = ArrayList(parsed)
        val casts = ArrayList<Cast>()
        val reels = ArrayList<Reel>()
        val spawnAt = HashMap<Int, Long>()
        val ownAt = HashMap<Int, Long>()
        val deathAt = HashMap<Int, Long>()
        val own = HashSet<Int>()
        val spawns = ArrayList<JsonObject>()
        val menus = ArrayList<LongArray>()
        var open = - 1L
        val worlds = ArrayList<Long>()
        val slots = ArrayList<Pair<Long, String>>()
        val clicks = ArrayList<Click>()
        for (o in lines) {
            val t = o.long("t")
            when (o.str("e")) {
                "rot" -> rots += line(o)
                "cast" -> casts += Cast(t, if (o.has("yaw") && o.has("pitch")) View(o.dbl("yaw"), o.dbl("pitch")) else null)
                "reel" -> reels += Reel(t, o.dblOrNaN("x"), o.dblOrNaN("y"), o.dblOrNaN("z"))
                "spawn" -> {
                    val id = o.int("id")
                    spawnAt.putIfAbsent(id, t)
                    if (o.bool("own")) own += id
                    spawns += o
                }
                "own" -> if (o.bool("own")) {
                    val id = o.int("id")
                    own += id
                    ownAt.putIfAbsent(id, t)
                }
                "death" -> deathAt.putIfAbsent(o.int("id"), t)
                "menu" -> if (open < 0) open = t
                "menuClose" -> if (open >= 0) {
                    menus += longArrayOf(open, t)
                    open = - 1L
                }
                "world", "cmd" -> worlds += t
                "slot" -> slots += t to o.str("item")
                "click" -> if (! o.bool("cast") && ! o.bool("auto")) clicks += Click(t, o.str("item"), o.str("b"), o.bool("hold"), o.dblOrNaN("err"))
            }
        }
        if (open >= 0) menus += longArrayOf(open, open + OPEN_MENU_MS)
        val lives = own.mapNotNull { id ->
            val d = deathAt[id] ?: return@mapNotNull null
            val s = min(spawnAt[id] ?: Long.MAX_VALUE, ownAt[id] ?: Long.MAX_VALUE)
            if (s == Long.MAX_VALUE || d < s) null else Life(id, s, d)
        }.sortedBy { it.spawn }
        val others = spawns.filter { it.int("id") !in own && it.has("x") }.map { Other(it.long("t"), it.dbl("x"), it.dbl("y"), it.dbl("z"), deathAt[it.int("id")] ?: Long.MAX_VALUE) }
        return Session(rots, casts, reels, lives, others, menus, worlds.sorted(), slots, clicks, rods, isWeapon, area)
    }

    /** A fight: your creatures' lives merged where they overlap, its main attack class, whether it needs aim, and the view it started with. */
    private class Fight(val t0: Long, var t1: Long) {
        var aim = false
        var teleport = false
        var start = View(0.0, 0.0)
    }

    /** A movement with what the classification looks at. */
    private class Mv(val m: ViewMotion.Move, val run: Int, val i0: Int, val i1: Int) {
        val start get() = View(m.y0, m.p0)
        val end get() = View(m.y1, m.p1)
        /** The yaw change on the sphere. */
        val dYaw = m.dYaw * cos(Math.toRadians((m.p0 + m.p1) / 2))
        val pitchOnly get() = kotlin.math.abs(dYaw) < PITCH_ONLY_SHARE * m.amp
        var aim = false
        var sweep = false
        var lookDown = false
        /** The view was back within the return tolerance at [retT] (-1: not within 15 s), the move that brought it there, its dwell and error. */
        var retT = - 1L
        var retMove = - 1
        var dwell = - 1L
        var retErr = Double.NaN
        var returnOf = - 1
        val aimLike get() = aim || sweep || lookDown
    }

    /** Everything about one recording ([s]): see [Result]. */
    fun measure(s: Session): Result {
        val lines = s.lines
        val n = lines.size
        val none = Result(emptyList(), emptyList(), DoubleArray(Phase.entries.size), DoubleArray(Phase.entries.size), 0.0, { null }, { false })
        if (n < 2) return none
        val times = LongArray(n) { lines[it].t }
        // runs of lines written in one go, broken as ViewMotion.runs breaks them
        val run = IntArray(n)
        for (i in 1 until n) run[i] = run[i - 1] + if (broken(lines[i - 1], lines[i])) 1 else 0
        val runs = run[n - 1] + 1
        val runFirst = IntArray(runs) { - 1 }
        val runLast = IntArray(runs)
        for (i in 0 until n) {
            if (runFirst[run[i]] < 0) runFirst[run[i]] = i
            runLast[run[i]] = i
        }
        fun at(t: Long) = lines[max(0, lastAtOrBefore(times, t))]

        // fights and their kind: aimed when their main attack needs aim (rod melee, a teleport blade, a weapon you aim)
        val area = s.area ?: areaClasses(s.clicks)
        val fights = ArrayList<Fight>()
        for (l in s.lives) {
            val f = fights.lastOrNull()
            if (f != null && l.spawn <= f.t1) f.t1 = max(f.t1, l.death) else fights += Fight(l.spawn, l.death)
        }
        for (f in fights) {
            val counts = LinkedHashMap<String, Int>()
            for (c in s.clicks) {
                if (c.t < f.t0 || c.t > f.t1 + FIGHT_CLICK_PAD_MS) continue
                if (c.item in s.rods && c.button == "R" || c.item in WeaponPlanner.MOBILITY || c.item in WeaponPlanner.NOT_WEAPONS) continue
                counts.merge(classOf(c), 1, Int::plus)
            }
            val main = counts.maxByOrNull { it.value }?.key ?: "none"
            val item = main.substringBefore(':')
            f.teleport = item in WeaponPlanner.TELEPORT_BLADES
            f.aim = when {
                main == "none" -> false
                f.teleport -> true
                item in s.rods -> main.endsWith(":L")
                else -> main !in area
            }
            val v = at(f.t0)
            f.start = View(v.yaw, v.pitch)
        }
        val fightStarts = LongArray(fights.size) { fights[it].t0 }
        val fightEnds = LongArray(fights.size) { fights[it].t1 }
        val castTimes = LongArray(s.casts.size) { s.casts[it].t }
        val castViews = s.casts.map { c -> c.view ?: at(c.t).let { View(it.yaw, it.pitch) } }
        val reelTimes = LongArray(s.reels.size) { s.reels[it].t }
        val deaths = s.lives.map { it.death }.sorted().toLongArray()

        fun phaseAt(t: Long): Phase? {
            val fi = lastAtOrBefore(fightStarts, t)
            if (fi >= 0 && t <= fights[fi].t1) return if (fights[fi].aim) Phase.FIGHT_AIM else Phase.FIGHT_FREE
            // the line is out: waiting for a bite (or reacting to one)
            val c = lastAtOrBefore(castTimes, t)
            val r = lastAtOrBefore(reelTimes, t)
            if (c >= 0 && (r < 0 || reelTimes[r] < castTimes[c])) return Phase.WAITING
            val li = lastAtOrBefore(fightEnds, t)
            if (li >= 0 && t - fightEnds[li] <= AFTER_KILL_MS) return Phase.AFTER_KILL
            return null
        }
        fun sinceDeath(t: Long) = lastAtOrBefore(deaths, t).let { if (it < 0) Long.MAX_VALUE else t - deaths[it] }

        // in place: no walking keys or movement within a second of the line in its run, no menu, no world change close by
        val inPlace = BooleanArray(n) { i ->
            val r = lines[i]
            fun still(j: Int) = lines[j].keys and LOCO_KEYS == 0 && hypot(lines[j].x - r.x, lines[j].z - r.z) <= LOCO_MOVED_M
            var j = i
            var ok = true
            while (ok && j >= runFirst[run[i]] && r.t - lines[j].t <= LOCO_WINDOW_MS) ok = still(j--)
            j = i
            while (ok && j <= runLast[run[i]] && lines[j].t - r.t <= LOCO_WINDOW_MS) ok = still(j++)
            ok && s.menus.none { it[0] <= r.t && it[1] >= r.t } && ! near(s.worlds, r.t, WORLD_MS)
        }

        // exposure: every step of a run, in place, by the phase at its start
        val minutes = DoubleArray(Phase.entries.size)
        val deathMinutes = DoubleArray(Phase.entries.size)
        var rotMs = 0.0
        for (i in 1 until n) {
            if (run[i] != run[i - 1]) continue
            val p = lines[i - 1]
            val q = lines[i]
            val dt = if (p.tick >= 0 && q.tick >= 0) (q.tick - p.tick) * ViewMotion.TICK_MS else (q.t - p.t).toDouble()
            if (dt <= 0.0) continue
            rotMs += dt
            if (! inPlace[i - 1]) continue
            val ph = phaseAt(p.t) ?: continue
            minutes[ph.ordinal] += dt / 60_000.0
            if (sinceDeath(p.t) < DEATH_MS) deathMinutes[ph.ordinal] += dt / 60_000.0
        }

        // every movement, cut ones and small ones too: any of them can be the one a later move returns from
        val mvs = ArrayList<Mv>()
        for (r in 0 until runs) {
            val a = runFirst[r]
            val b = runLast[r]
            if (b <= a) continue
            val sub = (a .. b).map { lines[it].let { l -> ViewMotion.Line(l.t, l.yaw, l.pitch, l.tick, l.tp && it > a) } }
            for (ticks in ViewMotion.runs(sub)) for (m in ViewMotion.segment(ticks).moves) mvs += Mv(m, r, indexIn(times, a, b, m.t0), indexIn(times, a, b, m.t1))
        }
        val runEnd = LongArray(runs) { times[runLast[it]] }

        // what makes a move aim-like: closing an error to one of your creatures, a weapon fired while turning, a look-down
        val fightOf = { t: Long -> lastAtOrBefore(fightStarts, t).takeIf { it >= 0 && t <= fights[it].t1 + AFTER_KILL_MS }?.let { fights[it] } }
        for (o in mvs) {
            val a = lines[o.i0]
            val b = lines[o.i1]
            o.aim = a.creatures.any { (id, ca) -> b.creatures[id]?.let { cb -> errTo(b, cb) <= TURN_CLOSES * errTo(a, ca) } == true }
            var fire = 0
            for (i in o.i0 + 1 .. o.i1) fire = fire or (lines[i].keys and FIRE_KEYS)
            val held = lastItem(s.slots, o.m.t0)
            o.sweep = a.creatures.isNotEmpty() && fire != 0 && held != null && s.isWeapon(held) && held !in s.rods
            o.lookDown = a.creatures.isNotEmpty() && o.pitchOnly && o.m.dPitch > 0 && o.m.p1 >= LOOKDOWN_END_PITCH &&
                (held in WeaponPlanner.TELEPORT_BLADES || fightOf(o.m.t0)?.teleport == true)
        }
        // back at its start: the first line within 15 s, and the move that brought it there
        for ((k, o) in mvs.withIndex()) {
            val tol = max(RETURN_MIN_DEG, RETURN_SHARE * o.m.amp)
            var i = o.i1 + 1
            while (i <= runLast[o.run] && lines[i].t - o.m.t1 <= RETURN_WINDOW_MS) {
                if (MotorModel.angle(lines[i].yaw, lines[i].pitch, o.m.y0, o.m.p0) <= tol) {
                    o.retT = lines[i].t
                    break
                }
                i++
            }
            if (o.retT < 0) continue
            for (j in k + 1 until mvs.size) {
                val x = mvs[j]
                if (x.run != o.run || x.m.t0 >= o.retT) break
                if (x.m.t0 >= o.m.t1 && x.m.t1 >= o.retT - RETURN_MOVE_MS) {
                    o.retMove = j
                    break
                }
            }
            val rm = mvs.getOrNull(o.retMove)
            o.dwell = if (rm != null) rm.m.t0 - o.m.t1 else o.retT - o.m.t1
            o.retErr = if (rm != null) MotorModel.angle(rm.m.y1, rm.m.p1, o.m.y0, o.m.p0) else Double.NaN
        }
        for ((k, o) in mvs.withIndex()) mvs.getOrNull(o.retMove)?.let { if (it.returnOf < 0) it.returnOf = k }

        val measured = ArrayList<Measured>()
        val looks = ArrayList<Look>()
        var prevLook: Mv? = null
        for (o in mvs) {
            val m = o.m
            if (m.cut || m.amp < MIN_DEG) continue
            val a = lines[o.i0]
            val b = lines[o.i1]
            val start = o.start
            val end = o.end
            val hc = lastAtOrBefore(castTimes, m.t0)
            val homeA = if (hc >= 0) angle(start, castViews[hc]) else Double.NaN
            val homeB = if (hc >= 0) angle(end, castViews[hc]) else Double.NaN
            val kind = kindOf(s, o, mvs, lines, ::at, fightOf, castTimes, castViews, homeA, homeB, hc)
            val phase = phaseAt(m.t0)
            val place = inPlace[o.i0]
            measured += Measured(m, kind, phase, place)
            if (kind != Kind.LOOK) continue
            val prev = prevLook
            prevLook = o
            if (phase == null || ! place) continue

            val home = homeA <= HOME_DEG
            val after = if (o.retT >= 0) o.retT - m.t1 else Long.MAX_VALUE
            // a return in between would have taken the view back to the look's start, not left it at its end
            val gap = prev?.let { pv ->
                val g = m.t0 - pv.m.t1
                g.takeIf { it in 0 until CHAIN_MS && angle(start, pv.end) <= max(RETURN_MIN_DEG, RETURN_SHARE * pv.m.amp) }
            } ?: - 1L
            // where your creatures died in the last 3 s, and how often a random bearing would have been closed on as well
            val dead = deadSpots(s, m.t0, ::at)
            var best: Pair<Double, Double>? = null
            for (pt in dead) {
                val ea = errTo(a, pt[0], pt[1], pt[2])
                val eb = errTo(b, pt[0], pt[1], pt[2])
                if (eb <= TURN_CLOSES * ea && (best == null || ea - eb > best.first - best.second)) best = ea to eb
            }
            val chance = if (dead.isEmpty()) 0.0 else BEARINGS.count { deg ->
                val c = cos(Math.toRadians(deg))
                val sn = sin(Math.toRadians(deg))
                dead.any { pt ->
                    val dx = pt[0] - a.x
                    val dz = pt[2] - a.z
                    val qx = a.x + dx * c - dz * sn
                    val qz = a.z + dx * sn + dz * c
                    val ea = errTo(a, qx, pt[1], qz)
                    ea >= TARGET_MIN_DEG && errTo(b, qx, pt[1], qz) <= TURN_CLOSES * ea
                }
            }.toDouble() / BEARINGS.size
            looks += Look(
                m.t0, phase, home, m.amp, o.dYaw, m.dPitch, m.p0,
                after <= RETURNED_MS || runEnd[o.run] - m.t1 >= RETURNED_MS, after <= RETURNED_MS,
                if (home && o.retT >= 0) o.dwell else - 1L, if (home && o.retT >= 0) o.retErr else Double.NaN, gap,
                sinceDeath(m.t0) < DEATH_MS, dead.isNotEmpty(), best != null && best.first >= TARGET_MIN_DEG, chance,
                m.dur, m.strokes.size, m.over,
            )
        }
        val lineTimes = times
        return Result(measured, looks, minutes, deathMinutes, rotMs / 60_000.0, ::phaseAt) { t -> lastAtOrBefore(lineTimes, t).let { it >= 0 && inPlace[it] } }
    }

    /** looks/SPEC §1.5, in its order. */
    private fun kindOf(
        s: Session, o: Mv, mvs: List<Mv>, lines: List<Line>, at: (Long) -> Line, fightOf: (Long) -> Fight?, castTimes: LongArray, castViews: List<View>,
        homeA: Double, homeB: Double, hc: Int,
    ): Kind {
        val m = o.m
        val a = lines[o.i0]
        val b = lines[o.i1]
        // a teleport, a menu, a world change or command, a clock artefact
        var jump = 0.0
        var short = false
        for (i in max(o.i0 - 1, 1) .. min(o.i1 + 1, lines.size - 1)) {
            val p = lines[i - 1]
            val q = lines[i]
            jump = max(jump, kotlin.math.sqrt((q.x - p.x) * (q.x - p.x) + (q.y - p.y) * (q.y - p.y) + (q.z - p.z) * (q.z - p.z)))
            // with tick counters a step takes a tick whatever the clock says
            if (i <= o.i0 || i > o.i1 || p.tick >= 0 && q.tick >= 0) continue
            val dt = (q.t - p.t).toDouble()
            if (dt > 0.0 && dt < SHORT_TICK_MS && MotorModel.angle(p.yaw, p.pitch, q.yaw, q.pitch) / dt * 1000.0 > SHORT_TICK_SPEED) short = true
        }
        val menu = s.menus.any { it[0] <= m.t1 + MENU_PAD_MS && it[1] >= m.t0 - MENU_PAD_MS }
        if (jump > TP_JUMP_M || menu || near(s.worlds, m.t0, WORLD_MS) || short) return Kind.ARTEFACT
        if (o.aim) return Kind.AIM

        // walking, a mobility item, swimming up: around the move, within a second
        var keys = 0
        var moved = 0.0
        var rise = 0.0
        var i = o.i0
        while (i >= 0 && lines[i].t >= m.t0 - LOCO_WINDOW_MS) {
            keys = keys or lines[i].keys
            moved = max(moved, hypot(lines[i].x - a.x, lines[i].z - a.z))
            i--
        }
        i = o.i0
        while (i < lines.size && lines[i].t <= m.t1 + LOCO_WINDOW_MS) {
            keys = keys or lines[i].keys
            moved = max(moved, hypot(lines[i].x - a.x, lines[i].z - a.z))
            rise = max(rise, lines[i].y - a.y)
            i++
        }
        val mobility = s.clicks.any { it.item in WeaponPlanner.MOBILITY && it.t >= m.t0 - MOBILITY_MS && it.t <= m.t1 + MOBILITY_MS }
        val swim = keys and JUMP_KEY != 0 && rise > SWIM_RISE_M
        if (keys and LOCO_KEYS != 0 || moved > LOCO_MOVED_M || mobility || swim) return Kind.LOCOMOTION
        if (o.sweep) return Kind.SWEEP
        if (o.lookDown) return Kind.LOOK_DOWN

        // a tool in hand, swapped to or clicked around the move (radar, menu item, bait)
        fun tool(item: String?) = ! item.isNullOrEmpty() && item !in s.rods && ! s.isWeapon(item)
        val window = m.t0 - MOBILITY_MS .. m.t1 + MOBILITY_MS
        if (tool(lastItem(s.slots, m.t0)) || s.slots.any { it.first in window && tool(it.second) } || s.clicks.any { it.t in window && tool(it.item) }) return Kind.TOOL

        if (o.returnOf >= 0) return if (mvs[o.returnOf].aimLike) Kind.TURN_BACK else Kind.RETURN
        val fight = fightOf(m.t0)
        if (fight != null) {
            val startA = angle(o.start, fight.start)
            if (angle(o.end, fight.start) <= 0.5 * startA) return Kind.BACK_TO_FIGHT
        }
        // you turned somewhere and cast from there
        val nc = lastAtOrBefore(castTimes, m.t1) + 1
        val castNext = nc < castTimes.size && castTimes[nc] - m.t1 <= NEW_VIEW_CAST_MS
        if (castNext && angle(o.end, castViews[nc]) <= NEW_VIEW_END_DEG && homeB > NEW_VIEW_HOME_DEG && o.retT < 0) return Kind.NEW_VIEW
        if (target(s, o, a, b, at, hc, castTimes)) return Kind.LOOK
        if (homeA >= MIN_DEG && homeB <= 0.5 * homeA) return Kind.NEW_VIEW
        return Kind.LOOK
    }

    /** The look closed on something: your bobber, the reel spot, where your creature just died, another player's creature. */
    private fun target(s: Session, o: Mv, a: Line, b: Line, at: (Long) -> Line, hc: Int, castTimes: LongArray): Boolean {
        val t0 = o.m.t0
        fun hit(ea: Double, eb: Double) = ea >= TARGET_MIN_DEG && eb <= TURN_CLOSES * ea
        val lr = s.reels.lastOrNull { it.t <= t0 }
        val nr = s.reels.firstOrNull { it.t > t0 }
        if (hc >= 0 && (lr == null || lr.t < castTimes[hc]) && nr != null && nr.x.isFinite()) {
            if (hit(errTo(a, nr.x, nr.y, nr.z), errTo(b, nr.x, nr.y, nr.z))) return true
        }
        else if (lr != null && lr.x.isFinite() && t0 - lr.t <= REEL_SPOT_MS) {
            if (hit(errTo(a, lr.x, lr.y, lr.z), errTo(b, lr.x, lr.y, lr.z))) return true
        }
        fun bestStart(points: List<DoubleArray>): Double? {
            var best: Pair<Double, Double>? = null
            for (pt in points) {
                val ea = errTo(a, pt[0], pt[1], pt[2])
                val eb = errTo(b, pt[0], pt[1], pt[2])
                if (eb <= TURN_CLOSES * ea && (best == null || ea - eb > best.first - best.second)) best = ea to eb
            }
            return best?.first
        }
        if ((bestStart(deadSpots(s, t0, at)) ?: 0.0) >= TARGET_MIN_DEG) return true
        val others = s.others.filter { it.t >= t0 - OTHER_MS && it.t <= t0 + OTHER_AFTER_MS && it.death >= t0 }.map { doubleArrayOf(it.x, it.y + OTHER_HEIGHT, it.z) }
        return (bestStart(others) ?: 0.0) >= TARGET_MIN_DEG
    }

    /** Where your creatures that died in the [DEAD_MS] before [t0] were last seen, at half their height. */
    private fun deadSpots(s: Session, t0: Long, at: (Long) -> Line) = s.lives.filter { it.death <= t0 && it.death >= t0 - DEAD_MS }.mapNotNull { l ->
        at(l.death - 1).creatures[l.id]?.let { c -> doubleArrayOf(c[0], c[1] + height(c) * 0.5, c[2]) }
    }

    /** ViewMotion.runs's breaks: a teleport line, a gap (in ticks, or more than [DENSE_MS] without them), a step faster than a hand. */
    private fun broken(p: Line, q: Line): Boolean {
        if (q.tp) return true
        val counted = p.tick >= 0 && q.tick >= 0
        if (if (counted) q.tick - p.tick <= 0 || q.tick - p.tick > ViewMotion.MAX_SPARSE_TICKS else q.t - p.t > DENSE_MS || q.t <= p.t) return true
        val dt = if (counted) ViewMotion.TICK_MS else (q.t - p.t).toDouble()
        val gc = MotorModel.angle(p.yaw, p.pitch, q.yaw, q.pitch) / dt * 1000.0
        val mouse = hypot(ViewMotion.wrap(q.yaw - p.yaw), q.pitch - p.pitch) / dt * 1000.0
        return gc > ViewMotion.ARTEFACT_DEG_S || mouse > ViewMotion.ARTEFACT_DEG_S
    }

    private fun classOf(c: Click) = c.item.ifEmpty { "?" } + ":" + c.button + if (c.hold) "h" else ""

    private fun height(c: DoubleArray) = if (c[3] > 0) c[3] else DEFAULT_HEIGHT

    /** The error from the view of [l] to creature [c] at half its height. */
    private fun errTo(l: Line, c: DoubleArray) = errTo(l, c[0], c[1] + height(c) * 0.5, c[2])

    /** The error from the view of [l] (from its eye) to the point [px], [py], [pz]. */
    private fun errTo(l: Line, px: Double, py: Double, pz: Double): Double {
        val dx = px - l.x
        val dy = py - (l.y + EYE)
        val dz = pz - l.z
        return MotorModel.angle(l.yaw, l.pitch, Math.toDegrees(atan2(- dx, dz)), Math.toDegrees(- atan2(dy, hypot(dx, dz))))
    }

    private fun angle(a: View, b: View) = MotorModel.angle(a.yaw, a.pitch, b.yaw, b.pitch)

    /** The item in hand at [t] by the slot changes (null before the first). */
    private fun lastItem(slots: List<Pair<Long, String>>, t: Long) = slots.lastOrNull { it.first <= t }?.second

    /** A time of the sorted [times] within [ms] of [t]. */
    private fun near(times: List<Long>, t: Long, ms: Long): Boolean {
        var lo = 0
        var hi = times.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val v = times[mid]
            if (v < t - ms) lo = mid + 1 else if (v > t + ms) hi = mid - 1 else return true
        }
        return false
    }

    /** Index of the last line of [a]..[b] at or before [t] ([a] when none). */
    private fun indexIn(times: LongArray, a: Int, b: Int, t: Long): Int {
        var lo = a
        var hi = b
        var found = a
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

    private fun creatures(o: JsonObject): Map<Int, DoubleArray> {
        val arr = o.get("c") as? JsonArray ?: return emptyMap()
        return arr.mapNotNull { e ->
            val a = e as? JsonArray ?: return@mapNotNull null
            if (a.size() < 5) return@mapNotNull null
            a[0].asInt to doubleArrayOf(a[1].asDouble, a[2].asDouble, a[3].asDouble, a[4].asDouble, if (a.size() > 5) a[5].asDouble else - 1.0)
        }.toMap()
    }

    private fun JsonObject.str(key: String) = get(key)?.takeIf { it.isJsonPrimitive }?.asString ?: ""
    private fun JsonObject.long(key: String) = get(key)?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L
    private fun JsonObject.int(key: String) = get(key)?.takeIf { it.isJsonPrimitive }?.asInt ?: 0
    private fun JsonObject.dbl(key: String) = get(key)?.takeIf { it.isJsonPrimitive }?.asDouble ?: 0.0
    private fun JsonObject.dblOrNaN(key: String) = get(key)?.takeIf { it.isJsonPrimitive }?.asDouble ?: Double.NaN
    private fun JsonObject.bool(key: String) = get(key)?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
}

//#endif
