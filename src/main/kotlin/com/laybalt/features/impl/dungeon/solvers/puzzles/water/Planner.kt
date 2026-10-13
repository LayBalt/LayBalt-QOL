package com.laybalt.features.impl.dungeon.solvers.puzzles.water

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max

/** One click of a plan, in the click frame (the client's server-tick counter at the moment the player clicks). */
data class PlanStep(val lever: WaterLever, val clickTick: Int)

enum class PlanFamily(val label: String) {
    /** The run's own remaining flips, possibly with the stray flip undone or the missed one done now. */
    REPAIR("repair"),

    /** Set the levers to one static setting as fast as possible and let the water finish. */
    STATIC("re-route"),

    /** A searched sequence of timed flips. */
    TIMED("timed continuation"),
}

/** A way to finish the puzzle from the board as it is, checked in the simulator. */
class ContinuationPlan(
    val family: PlanFamily,
    /** Clicks, soonest first. */
    val steps: List<PlanStep>,
    /** Observed tick at which the last closed door should open. */
    val predictedEnd: Int,
    /** The doors it opens (the doors closed when it was planned). */
    val doors: Int,
    /** Largest timing error (ticks) it survived in the simulator, every click shifted on its own and all together. */
    val margin: Int,
    val summary: String,
) {
    val lastClick: Int get() = steps.maxOfOrNull { it.clickTick } ?: 0

    fun withMargin(margin: Int) = ContinuationPlan(family, steps, predictedEnd, doors, margin, summary)
    override fun toString() = "$family ${steps.joinToString { "${it.lever.displayName}@${it.clickTick}" }} -> $predictedEnd"
}

/**
 * How the player turns a plan into clicks: reaction time, walking between the levers (room-relative positions) and how
 * long the board takes to show a click.
 */
data class HumanModel(
    /** Ticks from a click on a block lever to its blocks moving on the board. */
    val flipLag: Int,
    /** Ticks from a click on the water lever to the water reaching the entry hole. */
    val waterLag: Int,
    /** From the plan appearing to the first click. */
    val reaction: Int = 10,
    /** Room-relative (x, z) of the player when the plan is made, null if unknown. */
    val playerX: Double? = null,
    val playerZ: Double? = null,
    /** Ticks from a click that turns the water off to the entry hole drying (measured apart from [waterLag]). */
    val waterOffLag: Int = waterLag,
    /** Blocks per tick (walking is about 0.22, sprinting 0.28). */
    val speed: Double = 0.25,
    /** Levers can be clicked from about this far. */
    val reach: Double = 3.0,
) {
    private fun pos(lever: WaterLever) = BoardGeometry.LEVER_POS[lever.ordinal]

    private fun walk(dx: Double, dz: Double): Int = max(0.0, ceil((hypot(dx, dz) - reach) / speed)).toInt()

    /** Earliest click on [lever] for a plan shown at [now]. */
    fun firstClick(now: Int, lever: WaterLever): Int {
        val p = pos(lever)
        val walk = if (playerX == null || playerZ == null) 0 else walk(p[0] - playerX, p[2] - playerZ)
        return now + reaction + walk
    }

    /** Ticks between clicking [a] and clicking [b]. */
    fun gap(a: WaterLever, b: WaterLever): Int {
        val fixed = when {
            a == b -> 6
            a == WaterLever.WATER || b == WaterLever.WATER -> 16
            pos(a)[0] == pos(b)[0] -> 8
            else -> 12
        }
        val pa = pos(a)
        val pb = pos(b)
        return max(fixed, walk((pa[0] - pb[0]).toDouble(), (pa[2] - pb[2]).toDouble()))
    }

    /** Observed tick at which a click on [lever] at [click] shows on the board ([on]: the water state it switches to). */
    fun observed(lever: WaterLever, click: Int, on: Boolean = true) = click + when {
        lever != WaterLever.WATER -> flipLag
        on -> waterLag
        else -> waterOffLag
    }
}

/** Search limits of one planning request. */
data class SearchBudget(
    /** Ticks after the request a simulated plan may take to finish the puzzle. */
    val horizon: Int = 600,
    /** How far each click may be off and the plan still has to work. */
    val robustDelta: Int = 3,
    /** Wall-clock guard (milliseconds, 0 = none): results then depend on the machine, so tests switch it off. */
    val wallClockMs: Long = 700,
    /** Timed search (trusted boards only). */
    val timed: Boolean = false,
    val beamWidth: Int = 48,
    val maxNodes: Int = 12_000,
    val maxFlips: Int = 4,
    val decisionStep: Int = 5,
    /** Layers searched on after the first solved one. */
    val collectLayers: Int = 8,
    /** Solved lines handed to the robustness check at most. */
    val maxSolutions: Int = 24,
)

/** A lever that left the plan: what the board did and which lever. */
data class PlanFault(val lever: WaterLever, val kind: PlanFollower.Kind)

/** Everything the planner needs; all of it is owned by the request (copies), so it can run on any thread. */
class PlanRequest(
    val layout: BoardLayout,
    val rules: WaterRules,
    /** The board synced at [now], in the observed frame. */
    val state: BoardState,
    val now: Int,
    /** Doors closed right now. */
    val closed: Int,
    val outletToDoor: IntArray,
    val human: HumanModel,
    /** The remaining clicks of the plan being followed (click frame), null when there is none. */
    val remaining: List<PlanStep>?,
    val fault: PlanFault?,
    /** Observed tick at which the drain-and-restart fallback would finish; plans must beat it. */
    val restartEnd: Int,
    val budget: SearchBudget = SearchBudget(),
    /** Static re-routes allowed (degraded boards allow them only while the run matches the simulation). */
    val allowStatic: Boolean = true,
    /** Doors whose water has just arrived but which have not toggled yet: they toggle on their own. */
    val pendingDoors: Int = 0,
    /** When the plan will reach the player (now plus the planner's expected delay): no click is planned before. */
    val startAt: Int = now,
) {
    /** The doors once the pending toggles happened: each needs water exactly once more, the others none. */
    val target: Int get() = (closed xor pendingDoors) and Door.ALL

    /** Arrivals each door has had already (a door with a pending toggle has had one). */
    fun initialCounts() = IntArray(Door.entries.size) { if (pendingDoors and (1 shl it) != 0) 1 else 0 }
}

class PlanResult(val best: ContinuationPlan?, val evaluated: Int, val solutions: Int, val note: String)

/** What the simulator says about a list of clicks played from a board. */
class Evaluation(
    val solved: Boolean,
    /** Observed tick the last closed door opens, -1 unless solved. */
    val end: Int,
    /** Door that got water although it had to stay dry (or got it twice), -1 if none. */
    val wrongDoor: Int,
    /** Closed doors the water never reached. */
    val missing: Int,
)

/**
 * Plans how to finish the puzzle from the board as it is (pure, deterministic). Families, cheapest first:
 * [PlanFamily.REPAIR] (the run's own remaining clicks, with the faulty lever undone, done now or skipped),
 * [PlanFamily.STATIC] (every static lever setting, with the water kept or switched, the levers flipped nearest first) and,
 * on trusted boards, [PlanFamily.TIMED] ([BeamSearch]). A plan counts only if the simulator says it opens every closed
 * door exactly once without wetting an open one, still does so with every click off by [SearchBudget.robustDelta], and
 * finishes before the drain-and-restart fallback would.
 */
object Planner {
    /** A repair within this many ticks of the best plan wins: it keeps the countdowns the player already follows. */
    const val FAMILIAR_BONUS = 20

    /** Timed plans must beat static ones by this much (they are harder to play). */
    const val TIMED_PENALTY = 20

    /** Timed candidates that are also tried with later clicks. */
    const val ROBUSTIFY_CANDIDATES = 4

    /** Plans whose timing margin is checked at most (in order of speed). */
    const val MARGIN_CHECKS = 12

    fun plan(req: PlanRequest, cancel: CancelToken = CancelToken.NONE): PlanResult {
        val started = System.nanoTime()
        fun outOfTime() = req.budget.wallClockMs > 0 && (System.nanoTime() - started) / 1_000_000 > req.budget.wallClockMs
        var evaluated = 0
        // Plans that work when played exactly; whether they also work a little off is checked only for the ones chosen.
        val solved = ArrayList<ContinuationPlan>()

        fun consider(family: PlanFamily, steps: List<PlanStep>, summary: (List<PlanStep>) -> String): ContinuationPlan? {
            cancel.check()
            evaluated ++
            val e = evaluate(req, steps)
            if (! e.solved) return null
            return ContinuationPlan(family, steps, e.end, req.closed, - 1, summary(steps)).also { solved += it }
        }

        // Repairs of the plan being followed.
        req.remaining?.let { remaining ->
            for ((steps, text) in repairs(req, remaining)) consider(PlanFamily.REPAIR, steps) { text }
        }

        // Static re-routes.
        if (req.allowStatic && ! outOfTime()) {
            val waterOn = currentWater(req.state)
            for (m in 0 until 64) for (toggleWater in booleanArrayOf(false, true)) {
                if (outOfTime()) break
                val flips = WaterLever.blockLevers.filter { (req.state.mask xor m) and it.bit != 0 }
                if (flips.isEmpty() && ! toggleWater) {
                    consider(PlanFamily.STATIC, emptyList()) { "nothing to flip - let the water finish" }
                    continue
                }
                val order = nearestFirst(req.human, flips)
                val levers = when {
                    ! toggleWater -> order
                    waterOn -> listOf(WaterLever.WATER) + order
                    else -> order + WaterLever.WATER
                }
                val steps = timeline(req.human, req.startAt, levers)
                consider(PlanFamily.STATIC, steps) { staticSummary(it, waterOn) }
            }
        }
        var best = pickRobust(req, solved, cancel, ::outOfTime)

        // Timed search, only when nothing cheap comes close to the best possible.
        if (req.budget.timed && ! outOfTime()) {
            val bestSoFar = best?.predictedEnd ?: req.restartEnd
            val seeds = solved.map { it.steps }
            // Soonest first; the first one with room for error is taken. The search lands on the earliest tick a click
            // works, which is often right at the edge: the first few are also tried with their later clicks moved later.
            val candidates = BeamSearch.search(req, seeds, bestSoFar, cancel).take(req.budget.maxSolutions)
            var timed: ContinuationPlan? = null
            for ((i, candidate) in candidates.withIndex()) {
                if (outOfTime()) break
                val plan = consider(PlanFamily.TIMED, candidate) { timedSummary(it) } ?: continue
                val m = margin(req, plan.steps)
                if (m >= req.budget.robustDelta) {
                    timed = plan.withMargin(m)
                    break
                }
                if (i < ROBUSTIFY_CANDIDATES) {
                    val moved = robustify(req, candidate, cancel)?.let { consider(PlanFamily.TIMED, it) { s -> timedSummary(s) } } ?: continue
                    timed = moved.withMargin(margin(req, moved.steps))
                    break
                }
            }
            if (timed != null) best = choose(listOfNotNull(best, timed), req.restartEnd)
        }

        val chosen = best?.takeIf { score(it) < req.restartEnd }
        val note = "${solved.size} working plans out of $evaluated" + (if (outOfTime()) " (time limit)" else "")
        return PlanResult(chosen, evaluated, solved.size, note)
    }

    private fun score(p: ContinuationPlan) = p.predictedEnd + if (p.family == PlanFamily.TIMED) TIMED_PENALTY else 0

    private val ORDER = compareBy<ContinuationPlan>({ score(it) }, { it.steps.size }, { it.family.ordinal }, { it.steps.joinToString { s -> "${s.lever.ordinal}:${s.clickTick}" } })

    /**
     * The fastest plan that also works with every click [SearchBudget.robustDelta] ticks off, or a repair that is nearly as
     * fast. Margins are only computed in order of speed, for at most [MARGIN_CHECKS] plans.
     */
    private fun pickRobust(req: PlanRequest, plans: List<ContinuationPlan>, cancel: CancelToken, outOfTime: () -> Boolean): ContinuationPlan? {
        val ordered = plans.sortedWith(ORDER)
        var checks = 0
        fun robust(p: ContinuationPlan): ContinuationPlan? {
            if (checks ++ >= MARGIN_CHECKS || outOfTime()) return null
            cancel.check()
            val m = margin(req, p.steps)
            return if (m >= req.budget.robustDelta) p.withMargin(m) else null
        }
        var best: ContinuationPlan? = null
        for (p in ordered) {
            if (score(p) >= req.restartEnd) break
            best = robust(p) ?: continue
            break
        }
        val found = best ?: return null
        if (found.family == PlanFamily.REPAIR) return found
        for (p in ordered) {
            if (p.family != PlanFamily.REPAIR) continue
            if (score(p) > score(found) + FAMILIAR_BONUS) break
            robust(p)?.let { return it }
        }
        return found
    }

    /** The plan to follow among robust ones: fastest, a repair if it is nearly as fast, nothing if restarting is faster. */
    fun choose(plans: List<ContinuationPlan>, restartEnd: Int): ContinuationPlan? {
        val ordered = plans.sortedWith(ORDER)
        val best = ordered.firstOrNull() ?: return null
        val repair = ordered.firstOrNull { it.family == PlanFamily.REPAIR && score(it) <= score(best) + FAMILIAR_BONUS }
        val chosen = repair ?: best
        return if (score(chosen) < restartEnd) chosen else null
    }

    /**
     * Is [steps] still going to solve the puzzle if the player clicks them on time? (Only the nominal timing: this
     * judges a plan already being followed - a miss here means it fails even when played perfectly.)
     */
    fun check(req: PlanRequest, steps: List<PlanStep>, cancel: CancelToken = CancelToken.NONE): Evaluation {
        cancel.check()
        return evaluate(req, steps)
    }

    // ================================================================================================================
    // Simulation
    // ================================================================================================================

    /** Plays [steps] from the request's board in the simulator. */
    fun evaluate(req: PlanRequest, steps: List<PlanStep>): Evaluation {
        val s = req.state.copy()
        var on = currentWater(s)
        for (step in steps.sortedBy { it.clickTick }) {
            val at = max(req.human.observed(step.lever, step.clickTick, ! on), s.tick + 1)
            if (step.lever == WaterLever.WATER) {
                on = ! on
                s.addExternal(External.Source(at, on))
            }
            else s.addExternal(External.Flip(at, step.lever.ordinal))
        }
        val counts = req.initialCounts()
        var wrong = - 1
        var last = - 1
        val listener = object : SimListener {
            override fun arrival(outlet: Int, tick: Int) {
                val door = req.outletToDoor.getOrNull(outlet) ?: return
                counts[door] ++
                if (req.target and (1 shl door) == 0 || counts[door] > 1) {
                    if (wrong < 0) wrong = door
                }
                else last = max(last, tick)
            }
        }
        val until = req.now + req.budget.horizon
        while (s.tick < until && wrong < 0) {
            if (s.settled) break
            WaterSim.step(s, listener)
        }
        var missing = 0
        for (d in 0 until Door.entries.size) if (req.target and (1 shl d) != 0 && counts[d] != 1) missing = missing or (1 shl d)
        val solved = wrong < 0 && missing == 0
        return Evaluation(solved, if (solved) last + req.rules.doorDelay else - 1, wrong, missing)
    }

    /** Largest delta (up to 8) by which every click may be off, one at a time and all together, and the plan still works. */
    fun margin(req: PlanRequest, steps: List<PlanStep>): Int {
        if (steps.isEmpty()) return 8
        var ok = 0
        for (delta in intArrayOf(req.budget.robustDelta, 6, 8)) {
            if (delta <= ok) continue
            if (works(req, steps, delta)) ok = delta else break
        }
        return ok
    }

    /**
     * A version of [steps] with one click and everything after it moved later, that works with every click off by
     * [SearchBudget.robustDelta]; null if none is found.
     */
    fun robustify(req: PlanRequest, steps: List<PlanStep>, cancel: CancelToken = CancelToken.NONE): List<PlanStep>? {
        val delta = req.budget.robustDelta
        for (shift in intArrayOf(delta, 2 * delta)) for (i in steps.indices) {
            cancel.check()
            val moved = steps.mapIndexed { j, s -> if (j >= i) s.copy(clickTick = s.clickTick + shift) else s }
            if (! evaluate(req, moved).solved) continue
            if (works(req, moved, delta)) return moved
        }
        return null
    }

    /** Does [steps] still work with every click off by [delta], one at a time and all together? */
    private fun works(req: PlanRequest, steps: List<PlanStep>, delta: Int): Boolean {
        if (! evaluate(req, steps.map { it.copy(clickTick = it.clickTick + delta) }).solved) return false
        for (i in steps.indices) for (sign in intArrayOf(- 1, 1)) {
            if (! evaluate(req, steps.mapIndexed { j, s -> if (j == i) s.copy(clickTick = s.clickTick + sign * delta) else s }).solved) return false
        }
        return true
    }

    /** Water state after the changes the board has not shown yet. */
    private fun currentWater(s: BoardState): Boolean {
        var on = s.sourceOn
        for (i in s.nextExternal until s.externals.size) {
            val e = s.externals[i]
            if (e is External.Source) on = e.on
        }
        return on
    }

    // ================================================================================================================
    // Candidates
    // ================================================================================================================

    private fun repairs(req: PlanRequest, remaining: List<PlanStep>): List<Pair<List<PlanStep>, String>> {
        val out = ArrayList<Pair<List<PlanStep>, String>>()
        val base = retime(req.human, req.startAt, remaining)
        out += base to if (base == remaining) "keep following the timers" else "follow the updated timers"
        val lever = req.fault?.lever ?: return out
        val first = remaining.indexOfFirst { it.lever == lever }
        val withoutFirst = if (first >= 0) remaining.filterIndexed { i, _ -> i != first } else null
        val nowStep = listOf(PlanStep(lever, req.human.firstClick(req.startAt, lever)))
        out += retime(req.human, req.startAt, nowStep + remaining) to "flip ${lever.displayName} now, then follow the timers"
        if (withoutFirst != null) {
            out += retime(req.human, req.startAt, withoutFirst) to "skip ${lever.displayName}'s next flip, then follow the timers"
            out += retime(req.human, req.startAt, nowStep + withoutFirst) to "flip ${lever.displayName} now instead of later, then follow the timers"
        }
        return out.distinctBy { (steps, _) -> steps }
    }

    /** Keeps the planned order and times, moving clicks that cannot be made in time to the earliest moment they can. */
    fun retime(human: HumanModel, now: Int, steps: List<PlanStep>): List<PlanStep> {
        val out = ArrayList<PlanStep>(steps.size)
        var prev: PlanStep? = null
        for (step in steps.sortedBy { it.clickTick }) {
            val earliest = if (prev == null) human.firstClick(now, step.lever) else prev.clickTick + human.gap(prev.lever, step.lever)
            val t = max(step.clickTick, earliest)
            val placed = PlanStep(step.lever, t)
            out += placed
            prev = placed
        }
        return out
    }

    /** Clicks on [levers] in this order, each as early as the player can make it. */
    fun timeline(human: HumanModel, now: Int, levers: List<WaterLever>): List<PlanStep> {
        val out = ArrayList<PlanStep>(levers.size)
        var prev: PlanStep? = null
        for (lever in levers) {
            val t = if (prev == null) human.firstClick(now, lever) else prev.clickTick + human.gap(prev.lever, lever)
            val step = PlanStep(lever, t)
            out += step
            prev = step
        }
        return out
    }

    /** [levers] in walking order: the nearest to the player first, then the nearest to the last one. */
    fun nearestFirst(human: HumanModel, levers: List<WaterLever>): List<WaterLever> {
        val left = levers.toMutableList()
        val out = ArrayList<WaterLever>(levers.size)
        var x = human.playerX ?: 0.0
        var z = human.playerZ ?: 0.0
        while (left.isNotEmpty()) {
            val next = left.minWith(compareBy<WaterLever>({ hypot(BoardGeometry.LEVER_POS[it.ordinal][0] - x, BoardGeometry.LEVER_POS[it.ordinal][2] - z) }, { it.ordinal }))
            left.remove(next)
            out += next
            x = BoardGeometry.LEVER_POS[next.ordinal][0].toDouble()
            z = BoardGeometry.LEVER_POS[next.ordinal][2].toDouble()
        }
        return out
    }

    private fun staticSummary(steps: List<PlanStep>, waterOn: Boolean): String {
        val parts = steps.map { if (it.lever == WaterLever.WATER) (if (waterOn) "turn the water off" else "pull the water") else "flip ${it.lever.displayName}" }
        return parts.joinToString(", ") + if (steps.none { it.lever == WaterLever.WATER } && waterOn) " - the water does the rest" else ""
    }

    private fun timedSummary(steps: List<PlanStep>): String {
        val names = steps.map { if (it.lever == WaterLever.WATER) "the water" else it.lever.displayName }
        val listed = if (names.size <= 1) names.joinToString() else names.dropLast(1).joinToString(", ") + " and " + names.last()
        return "flip $listed on the timers"
    }

    /** Distance between two plans' clicks (for tests and logs). */
    fun distance(a: List<PlanStep>, b: List<PlanStep>): Int =
        if (a.size != b.size) Int.MAX_VALUE else a.zip(b).sumOf { (x, y) -> if (x.lever != y.lever) 1000 else abs(x.clickTick - y.clickTick) }
}
