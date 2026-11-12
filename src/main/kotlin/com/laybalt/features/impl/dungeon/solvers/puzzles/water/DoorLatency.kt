package com.laybalt.features.impl.dungeon.solvers.puzzles.water

import kotlin.math.max

/**
 * Ticks from water reaching an outlet cell to its door toggling, measured on one board. The outlet row sits two cells
 * above the basins, so if the server reacts to water in the basin rather than at the outlet the door follows the
 * outlet by a whole fall plus the door's own delay; nothing here assumes which. Pure.
 */
class DoorLatency {
    private val samples = ArrayDeque<Int>()

    val measured get() = samples.isNotEmpty()

    /** A door toggled [ticks] after its outlet got water. */
    fun learn(ticks: Int) {
        if (ticks !in 0 .. MAX_TICKS) return
        samples.addLast(ticks)
        if (samples.size > 9) samples.removeFirst()
    }

    /** The typical latency (median of the recent ones), [default] until a door has been seen toggling. */
    fun value(default: Int): Int = if (samples.isEmpty()) default else samples.sorted()[samples.size / 2]

    companion object {
        /** Latencies longer than this are not one arrival's door toggle. */
        const val MAX_TICKS = 40

        /** Water that reached an outlet this recently may still be on its way to toggling the door, at the least. */
        const val PENDING_MIN_TICKS = 10

        /** How long after an outlet got water its door counts as about to toggle, for a door [latency]. */
        fun pendingWindow(latency: Int) = max(PENDING_MIN_TICKS, latency + 6)
    }
}
