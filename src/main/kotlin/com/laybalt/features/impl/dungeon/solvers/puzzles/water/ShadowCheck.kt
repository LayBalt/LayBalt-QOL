package com.laybalt.features.impl.dungeon.solvers.puzzles.water

/**
 * Keeps the simulator honest during real runs: a shadow simulation of the run, started from the dry board when the water
 * is pulled and fed every lever move the board shows and every change of the water at the entry hole, is compared with
 * the real water every tick.
 *
 * Lever moves are reported once their blocks settle, a few ticks after they started, so the shadow runs [lag] ticks
 * behind the board and compares against the board as it was then. Errors add up in a run that never re-reads the board,
 * so a simulator that is wrong anywhere the water goes is caught: more than [tolerance] cells with another water level
 * for [streakLimit] ticks in a row demotes the simulator for the session.
 */
class ShadowCheck(
    val layout: BoardLayout,
    val rules: WaterRules,
    val lag: Int = 6,
    val tolerance: Int = 3,
    val streakLimit: Int = 10,
) {
    private var sim: BoardState? = null
    private val history = ArrayDeque<Pair<Int, ByteArray>>()
    private val flips = ArrayList<IntArray>() // [lever, tick]

    var ticksCompared = 0
        private set
    var totalDiff = 0L
        private set
    var maxDiff = 0
        private set
    var badStreak = 0
        private set
    var firstDivergence = - 1
        private set

    /** The simulation disagreed with the board for [streakLimit] ticks in a row. */
    var demoted = false
        private set

    val running get() = sim != null
    val meanDiff get() = if (ticksCompared == 0) 0.0 else totalDiff.toDouble() / ticksCompared

    /** No tick has been off by more than the tolerance yet in this run. */
    val clean get() = firstDivergence < 0 && ! demoted

    /** The run starts at [tick] from a dry board with lever parity [mask] (relative to the layout's reference). */
    fun start(tick: Int, mask: Int) {
        sim = BoardState(layout, rules).also {
            it.tick = tick
            it.mask = mask and SimRunner.ALL_LEVERS
        }
        history.clear()
        flips.clear()
    }

    /** A lever's blocks started moving at [tick]. */
    fun onFlip(lever: Int, tick: Int) {
        flips += intArrayOf(lever, tick)
    }

    /** Advances to [now] with [levels] the board's water right now. */
    fun tick(now: Int, levels: ByteArray) {
        history.addLast(now to levels.copyOf())
        while (history.size > lag + 8) history.removeFirst()
        val s = sim ?: return
        val src = layout.source
        val target = now - lag
        while (s.tick < target) {
            val t = s.tick + 1
            for (f in flips) if (f[1] == t) s.addExternal(External.Flip(t, f[0]))
            if (src >= 0) {
                // Water appearing at the entry: the source is on from then. Water leaving it: the feed stopped
                // [WaterRules.externalDelay] ticks before (that is when the simulator's entry starts to drain).
                val nowWet = levelAt(t, src) >= 0
                val wasWet = levelAt(t - 1, src) >= 0
                if (nowWet && ! wasWet && ! s.sourceOn) s.addExternal(External.Source(t, true))
                val dryAt = t + rules.externalDelay
                if (dryAt <= now && levelAt(dryAt, src) < 0 && levelAt(dryAt - 1, src) >= 0 && s.sourceOn) s.addExternal(External.Source(t, false))
            }
            WaterSim.step(s)
            val observed = history.firstOrNull { it.first == t }?.second ?: continue
            compare(s, observed, t)
        }
        flips.removeAll { it[1] < target - 2 }
    }

    /** The run is over: forget the shadow until the next one starts. */
    fun stop() {
        sim = null
    }

    private fun levelAt(tick: Int, cell: Int): Int = history.firstOrNull { it.first == tick }?.second?.get(cell)?.toInt()
        ?: history.lastOrNull { it.first < tick }?.second?.get(cell)?.toInt() ?: - 1

    private fun compare(s: BoardState, observed: ByteArray, t: Int) {
        var diff = 0
        for (c in 0 until layout.size) if (s.level[c] != observed[c] && (s.level[c] >= 0 || observed[c] >= 0)) diff ++
        ticksCompared ++
        totalDiff += diff
        if (diff > maxDiff) maxDiff = diff
        if (diff > tolerance) {
            if (firstDivergence < 0) firstDivergence = t
            if (++ badStreak >= streakLimit) demoted = true
        }
        else badStreak = 0
    }

    /** One line for `selfcheck.jsonl`. */
    fun summaryJson(variant: Int, key: String?, rulesText: String): String =
        "{\"variant\":$variant,\"key\":${key?.let { "\"$it\"" } ?: "null"},\"ticks\":$ticksCompared," +
            "\"meanDiff\":${"%.2f".format(java.util.Locale.ROOT, meanDiff)},\"maxDiff\":$maxDiff,\"firstDivergence\":$firstDivergence," +
            "\"demoted\":$demoted,\"rules\":\"$rulesText\"}"
}
