package com.laybalt.features.impl.dungeon.solvers.puzzles.water

import java.util.concurrent.CancellationException

/**
 * How far the simulator may be trusted on the board in front of the player, decided by the calibration gate (it must
 * reproduce the shipped solutions of the live board) and kept honest by the shadow check during real runs.
 */
enum class SimTrust {
    /** The gate has not finished yet: board-truth guidance only. */
    PENDING,

    /** The simulator did not reproduce the shipped solutions: board-truth guidance only. */
    UNTRUSTED,

    /** Most shipped solutions reproduce: static plans, and repairs while the run matches the simulation. */
    DEGRADED,

    /** Every shipped solution reproduces: every plan family, timed searches included. */
    TRUSTED;

    val simulates get() = this == DEGRADED || this == TRUSTED
}

/** Cooperative cancellation for planner jobs: [check] throws once the job is stale. */
class CancelToken(private val cancelled: () -> Boolean) {
    fun check() {
        if (cancelled()) throw CancellationException("water planner job is stale")
    }

    companion object {
        val NONE = CancelToken { false }
    }
}

/** What one simulated run did at the outlets. */
class RunOutcome(
    val arrivals: IntArray,
    val firstArrival: IntArray,
    val lastArrival: IntArray,
    /** Tick the run stopped (settled or out of time). */
    val endTick: Int,
    val settled: Boolean,
) {
    /** Outlets that got water at least once (bit = outlet index). */
    val arrived: Int get() = arrivals.foldIndexed(0) { i, acc, n -> if (n > 0) acc or (1 shl i) else acc }

    /** Outlets whose door toggled an odd number of times. */
    val toggled: Int get() = arrivals.foldIndexed(0) { i, acc, n -> if (n % 2 == 1) acc or (1 shl i) else acc }

    /** Some outlet got water twice (its door toggled back). */
    val rewet: Boolean get() = arrivals.any { it > 1 }

    /** Tick of the last arrival, -1 if none. */
    val lastArrivalTick: Int get() = lastArrival.maxOrNull() ?: - 1
}

/**
 * Runs the simulator for calibration and planning. Times are in the simulator's frame: block levers move at
 * click + [WaterRules.leverDelay], water reaches (or stops reaching) the entry hole at click + [WaterRules.sourceDelay].
 */
object SimRunner {
    /** First tick of a run that starts from a dry board (the tick wheel needs a little room before it). */
    const val BASE = 1

    /**
     * Plays [play] from the dry reference board: the levers set to the play's start ([ShippedPlay.startParity], relative
     * to the untouched board; [startOffset] is the reference snapshot relative to the untouched board), the water click
     * at [BASE], every timed click at its time.
     */
    fun runPlay(layout: BoardLayout, rules: WaterRules, play: ShippedPlay, startOffset: Int, maxTicks: Int = 700): RunOutcome {
        val s = BoardState(layout, rules)
        s.mask = (play.startParity xor startOffset) and ALL_LEVERS
        s.addExternals(playExternals(play, rules, BASE))
        return run(s, BASE + maxTicks)
    }

    /** The lever and source changes of [play] when its water click is at [waterClick]. */
    fun playExternals(play: ShippedPlay, rules: WaterRules, waterClick: Int): List<External> {
        val out = ArrayList<External>()
        out += External.Source(waterClick + rules.sourceDelay, true)
        var on = true
        for (c in play.timedClicks) {
            if (c.lever == WaterLever.WATER) {
                on = ! on
                out += External.Source(waterClick + c.tick + rules.sourceDelay, on)
            }
            else out += External.Flip(waterClick + c.tick + rules.leverDelay, c.lever.ordinal)
        }
        return out.sortedBy { it.tick }
    }

    /** Water on from a dry board with lever mask [mask] (simulator frame) until it settles. */
    fun runStatic(layout: BoardLayout, rules: WaterRules, mask: Int, maxTicks: Int = 700): RunOutcome {
        val s = BoardState(layout, rules)
        s.mask = mask and ALL_LEVERS
        s.addExternal(External.Source(BASE + rules.sourceDelay, true))
        return run(s, BASE + maxTicks)
    }

    /** Steps [s] until it settles or reaches [until], collecting what happens at the outlets from now on. */
    fun run(s: BoardState, until: Int, listener: SimListener? = null): RunOutcome {
        val n = s.layout.outlets.size
        val arrivals = IntArray(n)
        val first = IntArray(n) { - 1 }
        val last = IntArray(n) { - 1 }
        val recorder = object : SimListener {
            override fun placed(cell: Int, level: Int, tick: Int) {
                listener?.placed(cell, level, tick)
            }

            override fun removed(cell: Int, tick: Int) {
                listener?.removed(cell, tick)
            }

            override fun arrival(outlet: Int, tick: Int) {
                arrivals[outlet] ++
                if (first[outlet] < 0) first[outlet] = tick
                last[outlet] = tick
                listener?.arrival(outlet, tick)
            }
        }
        WaterSim.run(s, until, recorder)
        return RunOutcome(arrivals, first, last, s.tick, s.settled)
    }

    const val ALL_LEVERS = 0b111111
}
