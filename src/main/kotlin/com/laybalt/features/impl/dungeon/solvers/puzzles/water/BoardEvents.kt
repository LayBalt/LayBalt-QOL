package com.laybalt.features.impl.dungeon.solvers.puzzles.water

/** What the board did, read from the blocks (not from clicks). Ticks are the client's server-tick counter. */
sealed interface BoardEvent {
    val tick: Int

    /**
     * Every block of [lever] moved to the other plane. [tick] is when the first block moved, [clickTick] the click
     * that caused it (our own click when [source] is OWN or DOUBLE, otherwise [tick] minus the usual latency).
     */
    data class LeverFlipped(val lever: WaterLever, override val tick: Int, val clickTick: Int, val source: FlipSource) : BoardEvent

    /**
     * Water started or stopped coming into the board. [correction] marks the tracker fixing an earlier guess that the
     * board did not confirm (no lever was flipped).
     */
    data class WaterSwitched(
        val on: Boolean,
        override val tick: Int,
        val clickTick: Int,
        val source: FlipSource,
        val correction: Boolean = false,
    ) : BoardEvent

    /** A door opened or closed. */
    data class DoorToggled(val door: Door, val closed: Boolean, override val tick: Int) : BoardEvent
}

/** The board as the supervisor sees it. Implemented by [BoardTracker]; tests use their own fakes. */
interface BoardView {
    val now: Int

    /** Frame known, chunks loaded, layout captured and variant known: the other values are meaningful. */
    val ready: Boolean

    /** Closed doors (bit [Door.digit]). */
    val doors: Int

    /** Block-lever parity relative to the untouched board (bit [WaterLever.ordinal]). */
    val parity: Int

    /** Block levers whose blocks are moving right now (their parity bit is the last stable one). */
    val transit: Int

    /** Levers whose untouched position is a guess (the room was read after someone may have flipped them). */
    val parityUncertain: Int

    /** Water is coming into the board (best estimate). */
    val waterOn: Boolean

    /** We clicked the water lever recently and the board has not answered yet. */
    val waterClickPending: Boolean

    /** Water cells on the board (y <= 79, the shaft above excluded). */
    val boardWater: Int

    /** Last tick a water cell on the board appeared, vanished or changed level. */
    val lastWaterChange: Int
}
