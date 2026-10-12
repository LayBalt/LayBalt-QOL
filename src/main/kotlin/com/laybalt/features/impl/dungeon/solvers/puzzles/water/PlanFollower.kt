package com.laybalt.features.impl.dungeon.solvers.puzzles.water

/** Where an observed flip came from, as far as the client can tell. */
enum class FlipSource {
    /** One of our own clicks on that lever shortly before the blocks moved. */
    OWN,

    /** Nobody we know of: a teammate (or a click the client did not see). */
    OTHER,

    /** A second swap answering a single click of ours (shift-click / off-hand double flick). */
    DOUBLE,
}

/**
 * Follows one shipped play while it runs: matches observed flips to the play's schedule and reports the first
 * divergence. This is the timing logic of the 3.2.2 `WaterBoardTracker` (same tolerance rules), generalised to flips
 * read from the board instead of the player's clicks. Pure.
 *
 * All ticks are in the click frame: the client's server-tick counter at the moment a lever is clicked. Flips that were
 * only observed (the blocks moved) are converted to it by the caller (observed tick minus latency). [anchor] is the
 * click-frame tick of the water start.
 */
class PlanFollower(val play: ShippedPlay, val anchor: Int, var toleranceTicks: Int = 20) {

    enum class Kind {
        /** The lever's next planned flip is more than the tolerance away. */
        EARLY,

        /** A planned flip did not happen within the tolerance. */
        MISSED,

        /** The lever has no planned flips left. */
        EXTRA,

        /** The play never uses this lever. */
        WRONG_LEVER,

        /** The water started while a block lever was in a state the play does not start from. */
        START_STATE,
    }

    /** [offset]: how early (EARLY) or late (MISSED) the flip was, in ticks; 0 otherwise. */
    data class Divergence(val lever: WaterLever, val kind: Kind, val tick: Int, val offset: Int = 0, val source: FlipSource = FlipSource.OWN)

    private val consumed = IntArray(WaterLever.entries.size)

    /** How many planned flips of [lever] are done; the remaining ones are `schedule.drop(consumed)`. */
    fun consumed(lever: WaterLever) = consumed[lever.ordinal]

    /** Remaining planned flips of [lever], in ticks after the water start. */
    fun remaining(lever: WaterLever): List<Int> = play.schedule.getValue(lever).drop(consumed[lever.ordinal])

    /** All remaining flips (lever, ticks after the start), soonest first; at equal times block levers before the water. */
    fun pending(): List<TimedClick> = WaterLever.entries
        .flatMap { lever -> remaining(lever).map { TimedClick(lever, it) } }
        .sortedWith(compareBy({ it.tick }, { if (it.lever == WaterLever.WATER) 1 else 0 }, { it.lever.ordinal }))

    val finished get() = WaterLever.entries.all { consumed[it.ordinal] >= play.schedule.getValue(it).size }

    /** Click-frame tick of the last planned flip. */
    val lastPlannedTick get() = anchor + play.lastClickTick

    /**
     * Marks what is done when the water starts, from the block levers' [parity] (relative to the untouched board).
     * A lever that still misses its one pre-water flip may do it right after the water, within the tolerance; a lever
     * flipped although the play never flips it before the water is a [Kind.START_STATE] divergence.
     */
    fun start(parity: Int, tick: Int = anchor): Divergence? {
        consumed.fill(0)
        consumed[WaterLever.WATER.ordinal] = minOf(1, play.schedule.getValue(WaterLever.WATER).size)
        var wrong: WaterLever? = null
        for (lever in WaterLever.blockLevers) {
            val z = play.preWater[lever.ordinal]
            val atStart = (parity and lever.bit != 0) == (play.startParity and lever.bit != 0)
            consumed[lever.ordinal] = when {
                atStart -> z
                z > 0 -> z - 1
                else -> {
                    if (wrong == null) wrong = lever
                    0
                }
            }
        }
        return wrong?.let { Divergence(it, Kind.START_STATE, tick) }
    }

    /** A flip of [lever] at click-frame [tick]. Returns the divergence it causes, if any. */
    fun onFlip(lever: WaterLever, tick: Int, source: FlipSource = FlipSource.OWN): Divergence? {
        val times = play.schedule.getValue(lever)
        val c = consumed[lever.ordinal]
        val t = tick - anchor
        if (c < times.size && times[c] - t <= toleranceTicks) {
            consumed[lever.ordinal] ++
            return null
        }
        return when {
            times.isEmpty() -> Divergence(lever, Kind.WRONG_LEVER, tick, source = source)
            c >= times.size -> Divergence(lever, Kind.EXTRA, tick, source = source)
            else -> Divergence(lever, Kind.EARLY, tick, times[c] - t, source)
        }
    }

    /** Legacy display (recovery off): counts a flip that does not fit the timing anyway, like the 3.2.2 click counting. */
    fun forceConsume(lever: WaterLever) {
        if (consumed[lever.ordinal] < play.schedule.getValue(lever).size) consumed[lever.ordinal] ++
    }

    /** Advances to click-frame [now]: a planned flip more than the tolerance overdue is a [Kind.MISSED] divergence. */
    fun onTick(now: Int): Divergence? {
        val t = now - anchor
        for (lever in WaterLever.entries) {
            val times = play.schedule.getValue(lever)
            val c = consumed[lever.ordinal]
            if (c < times.size && t - times[c] > toleranceTicks) return Divergence(lever, Kind.MISSED, now, t - times[c])
        }
        return null
    }
}
