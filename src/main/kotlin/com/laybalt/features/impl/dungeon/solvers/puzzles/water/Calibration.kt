package com.laybalt.features.impl.dungeon.solvers.puzzles.water

import kotlin.math.abs

/** One shipped play replayed in the simulator. [doors]: the doors it toggled (after the outlet mapping). */
data class PlayCheck(
    val key: String,
    val passed: Boolean,
    val doors: Int,
    val rewet: Boolean,
    /** Tick (after the water click) of the play's last arrival, -1 if none. */
    val lastArrival: Int,
    val lastClick: Int,
)

/**
 * Verdict of the calibration gate on one board: how far the simulator can be trusted, the timing it fitted, which outlet
 * opens which door, and (as a by-product) which doors each static lever setting opens from a dry board.
 */
class CalibrationResult(
    val trust: SimTrust,
    val rules: WaterRules,
    /** Door digit of each outlet (index into [BoardLayout.outlets]); null when it could not be fitted. */
    val outletToDoor: IntArray?,
    /** The reference snapshot relative to the untouched board (possibly corrected by the gate). */
    val startOffset: Int,
    val startOffsetChanged: Boolean,
    val passes: Int,
    val total: Int,
    val checks: List<PlayCheck>,
    /**
     * For every lever setting (relative to the untouched board): the doors that pulling the water from a dry board
     * toggles, or -1 when that run wets an outlet twice or does not settle.
     */
    val staticFromDry: IntArray?,
    val summary: String,
    /** For every lever setting: ticks after the water click until its last door opens (-1 when unusable). */
    val staticTicks: IntArray? = null,
) {
    /** Door bits toggled by [outlets] (outlet bits). */
    fun doorsOf(outlets: Int): Int = mapDoors(outlets, outletToDoor ?: return 0)

    companion object {
        fun mapDoors(outlets: Int, outletToDoor: IntArray): Int {
            var doors = 0
            for (o in outletToDoor.indices) if (outlets and (1 shl o) != 0) doors = doors or (1 shl outletToDoor[o])
            return doors
        }

        fun pending() = CalibrationResult(SimTrust.PENDING, WaterRules(), null, 0, false, 0, 0, emptyList(), null, "not run yet")
    }
}

/** Inputs of the calibration gate. */
class CalibrationInput(
    val layout: BoardLayout,
    val table: ShippedTable,
    /** Vanilla rules plus the room rotation; the timing fields are fitted. */
    val base: WaterRules,
    val startOffset: Int,
    /** Levers whose untouched position is a guess; the gate may correct them. */
    val uncertain: Int,
    /** Measured (water entry delay - lever delay) from our own clicks, if known: narrows the timing grid. */
    val measuredWaterMinusLever: Int? = null,
    /** Measured (on delay - off delay) at the entry hole from our own clicks, if known: narrows [WaterRules.sourceOffLead]. */
    val measuredOffLead: Int? = null,
)

/**
 * The calibration gate: the simulator is only trusted on a board if it reproduces the shipped solutions of that board.
 * It replays every shipped play of the variant on the live layout over a grid of the unknown timings (lever delay,
 * water delay, how much sooner the water stops than starts, the delay of the updates a lever change wakes) and fits the
 * outlet-to-door mapping (120 permutations).
 * Every play must toggle exactly its own doors, each once.
 */
object Calibration {
    /**
     * Only the water delay relative to the lever delay changes what a play does (shifting both shifts the whole run), so
     * the lever delay stays fixed and the water delay runs over [WATER_SPAN] ticks after it.
     */
    private val LEVER_DELAYS = intArrayOf(1)
    private val WAKE_DELAYS = intArrayOf(4, 5)
    private const val WATER_SPAN = 36

    /**
     * How much sooner the entry dries after an off click than it gets wet after an on click: 0 if the feed change travels
     * down the shaft at the same speed both ways, one water tick if the lever plugs the shaft right above the entry.
     */
    private val OFF_LEADS = intArrayOf(0, 5)

    /** A play counts as reproduced only if its last door opens within this long after its last click. */
    const val COMPLETION_SLACK = 200

    fun calibrate(input: CalibrationInput, cancel: CancelToken = CancelToken.NONE): CalibrationResult {
        val layout = input.layout
        val plays = input.table.plays.values.sortedBy { it.key }
        if (layout.outlets.size != Door.entries.size) {
            return untrusted(input, "the board has ${layout.outlets.size} outlets instead of 5")
        }
        if (plays.isEmpty()) return untrusted(input, "no shipped plays for this layout")
        val perms = permutations(Door.entries.size)

        var best: Fit? = null
        // Every fit with the best pass count so far (see the end: the timing kept when no timing passes every play).
        val bestFits = ArrayList<Fit>()
        val fullPasses = ArrayList<Fit>()
        for (drips in booleanArrayOf(true, false)) {
            for (rules in grid(input, drips)) {
                cancel.check()
                val outcomes = plays.map { SimRunner.runPlay(layout, rules, it, input.startOffset) }
                val fit = bestPermutation(plays, outcomes, perms, rules) ?: continue
                val top = best
                if (top == null || fit.passes > top.passes) {
                    best = fit
                    bestFits.clear()
                    bestFits += fit
                }
                else if (fit.passes == top.passes) bestFits += fit
                if (fit.passes == plays.size) fullPasses += fit
            }
            if (fullPasses.isNotEmpty()) break
        }
        var chosen = best ?: return untrusted(input, "no timing made any shipped play reach its doors")
        if (fullPasses.isNotEmpty()) chosen = middle(fullPasses)

        // A wrong guess of the untouched lever state breaks plays that need those levers: try the uncertain ones.
        var offset = input.startOffset
        if (chosen.passes < plays.size && input.uncertain != 0) {
            var alt: Pair<Int, Fit>? = null
            var tie = false
            var sub = input.uncertain
            while (sub != 0) {
                cancel.check()
                val candidate = input.startOffset xor sub
                val outcomes = plays.map { SimRunner.runPlay(layout, chosen.rules, it, candidate) }
                val fit = bestPermutation(plays, outcomes, perms, chosen.rules)
                if (fit != null && fit.passes > chosen.passes && fit.passes >= plays.size - 1) {
                    if (alt == null || fit.passes > alt.second.passes) {
                        alt = candidate to fit
                        tie = false
                    }
                    else if (fit.passes == alt.second.passes) tie = true
                }
                sub = (sub - 1) and input.uncertain
            }
            if (alt != null && ! tie) {
                // Fit the timing again from scratch for the corrected start (the first fit was made for the wrong one).
                val refit = calibrate(
                    CalibrationInput(layout, input.table, input.base, alt.first, 0, input.measuredWaterMinusLever, input.measuredOffLead), cancel,
                )
                if (refit.passes >= alt.second.passes) {
                    return CalibrationResult(
                        refit.trust, refit.rules, refit.outletToDoor, alt.first, true, refit.passes, refit.total, refit.checks,
                        refit.staticFromDry, refit.summary + ", untouched levers corrected: ${WaterLever.names(alt.first xor input.startOffset)}",
                        refit.staticTicks,
                    )
                }
                offset = alt.first
                chosen = alt.second
            }
        }
        // No timing passes every play and the start stayed: the first grid point that reaches the most passes is an
        // accident of the grid order (3.2.4 field logs: "water +1" on variant 0, while one water step alone takes 5
        // ticks). The middle of the widest run of equally good timings is kept instead, as for full passes.
        if (fullPasses.isEmpty() && offset == input.startOffset && bestFits.size > 1) chosen = middle(bestFits)

        val trust = when {
            chosen.passes == plays.size -> SimTrust.TRUSTED
            chosen.passes >= plays.size - 3 && plays.size >= 7 -> SimTrust.DEGRADED
            else -> SimTrust.UNTRUSTED
        }
        val static = if (trust.simulates) staticTable(layout, chosen.rules, chosen.perm, offset, cancel) else null
        val staticTicks = static?.let { staticTicks(layout, chosen.rules, offset, it, cancel) }
        val summary = "${chosen.passes}/${plays.size} shipped plays reproduced (${chosen.rules.describe()})" +
            (if (offset != input.startOffset) ", untouched levers corrected: ${WaterLever.names(offset xor input.startOffset)}" else "") +
            failing(chosen.checks)
        return CalibrationResult(
            trust, chosen.rules, chosen.perm, offset, offset != input.startOffset,
            chosen.passes, plays.size, chosen.checks, static, summary, staticTicks,
        )
    }

    /** ", failing: 024 (toggles 02; wets an outlet twice)" for the plays the simulation did not reproduce (log and trace). */
    fun failing(checks: List<PlayCheck>): String {
        val failed = checks.filterNot { it.passed }
        if (failed.isEmpty()) return ""
        return ", failing: " + failed.joinToString(", ") { c ->
            val what = buildList {
                add(if (c.doors == 0) "reaches no door" else "toggles ${Door.keyOf(c.doors)}")
                if (c.rewet) add("wets an outlet twice")
                if (c.lastArrival > c.lastClick + COMPLETION_SLACK) add("last door ${c.lastArrival - c.lastClick} ticks after the last click")
            }
            "${c.key} (${what.joinToString("; ")})"
        }
    }

    /** Ticks after the water click until the last door of each usable static setting opens. */
    private fun staticTicks(layout: BoardLayout, rules: WaterRules, startOffset: Int, doors: IntArray, cancel: CancelToken): IntArray =
        IntArray(64) { m ->
            if (doors[m] <= 0) return@IntArray - 1
            cancel.check()
            val outcome = SimRunner.runStatic(layout, rules, m xor startOffset)
            if (outcome.lastArrivalTick < 0) - 1 else outcome.lastArrivalTick - SimRunner.BASE + rules.doorDelay
        }

    /** Doors toggled by every static lever setting (relative to the untouched board) from a dry board. */
    fun staticTable(layout: BoardLayout, rules: WaterRules, outletToDoor: IntArray, startOffset: Int, cancel: CancelToken): IntArray =
        IntArray(64) { m ->
            cancel.check()
            val outcome = SimRunner.runStatic(layout, rules, m xor startOffset)
            if (! outcome.settled || outcome.rewet) - 1 else CalibrationResult.mapDoors(outcome.toggled, outletToDoor)
        }

    private class Fit(val rules: WaterRules, val perm: IntArray, val passes: Int, val checks: List<PlayCheck>)

    private fun bestPermutation(plays: List<ShippedPlay>, outcomes: List<RunOutcome>, perms: List<IntArray>, rules: WaterRules): Fit? {
        if (outcomes.all { it.arrived == 0 }) return null
        var best: IntArray? = null
        var bestPasses = - 1
        for (perm in perms) {
            var passes = 0
            for (i in plays.indices) if (passes(plays[i], outcomes[i], perm)) passes ++
            if (passes > bestPasses) {
                bestPasses = passes
                best = perm
            }
        }
        val perm = best ?: return null
        val checks = plays.indices.map { i ->
            val o = outcomes[i]
            PlayCheck(
                plays[i].key, passes(plays[i], o, perm), CalibrationResult.mapDoors(o.toggled, perm), o.rewet,
                if (o.lastArrivalTick < 0) - 1 else o.lastArrivalTick - SimRunner.BASE, plays[i].lastClickTick,
            )
        }
        return Fit(rules, perm, bestPasses, checks)
    }

    private fun passes(play: ShippedPlay, outcome: RunOutcome, perm: IntArray): Boolean {
        if (outcome.rewet || outcome.arrived == 0) return false
        if (CalibrationResult.mapDoors(outcome.arrived, perm) != play.doors) return false
        return outcome.lastArrivalTick - SimRunner.BASE <= play.lastClickTick + COMPLETION_SLACK
    }

    /** Timing candidates, the most plausible first (measured delays when we have them). */
    private fun grid(input: CalibrationInput, drips: Boolean): List<WaterRules> {
        val out = ArrayList<WaterRules>()
        val measured = input.measuredWaterMinusLever
        val leads = input.measuredOffLead?.let { m -> OFF_LEADS.filter { abs(it - m) <= 2 }.ifEmpty { null } } ?: OFF_LEADS.toList()
        for (lead in leads) for (lever in LEVER_DELAYS) for (wake in WAKE_DELAYS) {
            val waters = (0 .. WATER_SPAN).map { lever + it }.let { all ->
                if (measured == null) all
                else all.filter { abs(it - lever - measured) <= 3 }.ifEmpty { all }
            }
            for (water in waters) {
                out += input.base.copy(leverDelay = lever, sourceDelay = water, sourceOffLead = lead, externalDelay = wake, dripArrivals = drips)
            }
        }
        return out
    }

    /** Of several fully passing timings, the one in the middle of the widest run (most room for error either way). */
    private fun middle(fits: List<Fit>): Fit {
        val byKey = fits.groupBy { it.rules.copy(sourceDelay = 0) }
        var bestRun: List<Fit> = emptyList()
        val order = compareBy<WaterRules>({ it.sourceOffLead }, { it.leverDelay }, { it.externalDelay }, { ! it.dripArrivals })
        for ((_, group) in byKey.entries.sortedWith(compareBy(order) { it.key })) {
            val sorted = group.sortedBy { it.rules.sourceDelay }
            var run = ArrayList<Fit>()
            for (f in sorted) {
                if (run.isNotEmpty() && f.rules.sourceDelay != run.last().rules.sourceDelay + 1) {
                    if (run.size > bestRun.size) bestRun = run
                    run = ArrayList()
                }
                run += f
            }
            if (run.size > bestRun.size) bestRun = run
        }
        return bestRun[bestRun.size / 2]
    }

    private fun untrusted(input: CalibrationInput, why: String) = CalibrationResult(
        SimTrust.UNTRUSTED, input.base, null, input.startOffset, false, 0, input.table.plays.size, emptyList(), null, why,
    )

    /** All permutations of 0 until [n], lexicographic. */
    fun permutations(n: Int): List<IntArray> {
        val out = ArrayList<IntArray>()
        fun rec(prefix: IntArray, used: Int, depth: Int) {
            if (depth == n) {
                out += prefix.copyOf()
                return
            }
            for (i in 0 until n) if (used and (1 shl i) == 0) {
                prefix[depth] = i
                rec(prefix, used or (1 shl i), depth + 1)
            }
        }
        rec(IntArray(n), 0, 0)
        return out
    }
}
