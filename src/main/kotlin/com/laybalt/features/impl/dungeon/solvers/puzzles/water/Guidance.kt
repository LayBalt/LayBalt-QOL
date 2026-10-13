package com.laybalt.features.impl.dungeon.solvers.puzzles.water

/** How a lever label is drawn. */
enum class LabelStyle {
    /** The solution display (countdowns, CLICK): click / next-click colours. */
    SOLUTION,

    /** A recovery step (flip back, water off, pull to restart): fix colour with a box around the lever. */
    FIX,

    /** A recovery state without an action on that lever (draining): fix colour, no box. */
    FIX_NO_BOX,
}

/** One text above a lever; [stack] lifts it by half a block per step (several flips of the same lever). */
data class LeverLabel(val lever: WaterLever, val text: String, val style: LabelStyle, val stack: Int = 0)

/** What the solver shows right now. Total: every state of the supervisor maps to exactly one of these. */
sealed interface Guidance {
    /**
     * Something to do: [labels] above levers, a [tracer] to the lever to click first, a [line] from it to the one after,
     * and an optional HUD [headline].
     */
    data class Act(
        val labels: List<LeverLabel>,
        val tracer: WaterLever?,
        val line: Pair<WaterLever, WaterLever>?,
        val headline: String?,
    ) : Guidance

    /** Nothing to click: reading, waiting for a teammate's run, unknown layout ... shown on the HUD. */
    data class Status(val headline: String) : Guidance

    /** All doors are open. */
    data object Solved : Guidance

    /** The HUD line of this guidance, if any. */
    val hudLine: String?
        get() = when (this) {
            is Act -> headline
            is Status -> headline
            Solved -> null
        }

    /** True when the player gets no information at all (never allowed while the puzzle is unsolved). */
    val silent: Boolean
        get() = when (this) {
            is Act -> labels.isEmpty() && headline.isNullOrEmpty()
            is Status -> headline.isEmpty()
            Solved -> false
        }
}
