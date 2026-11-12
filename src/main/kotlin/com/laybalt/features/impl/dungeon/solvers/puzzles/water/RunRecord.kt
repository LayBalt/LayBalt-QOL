package com.laybalt.features.impl.dungeon.solvers.puzzles.water

/**
 * What the board did during one run, as observed: cell changes, lever swaps, water switches, doors and our clicks,
 * all stamped with the client's server-tick counter. Feeds the simulator self-check and the recorder. Bounded: once
 * [MAX_CELL_CHANGES] cell changes are stored, further ones are dropped and [truncated] is set.
 */
class RunRecord(
    val startTick: Int,
    val variant: Int,
    val key: String?,
    /** Lever bits relative to the reference snapshot when the record started. */
    val mask: Int,
    /** Water levels of the board plane when the record started. */
    val levels: ByteArray,
) {
    /** Triples (tick, cell, level). */
    val cells = IntList(256)

    /** Triples (tick, lever ordinal, source ordinal). */
    val flips = IntList()

    /** Triples (tick, on 0/1, source ordinal). */
    val waters = IntList()

    /** Triples (tick, door digit, closed 0/1). */
    val doors = IntList()

    /** Pairs (tick, lever ordinal). */
    val clicks = IntList()

    var truncated = false
        private set
    var endTick = - 1
    var result = ""

    fun cell(tick: Int, cell: Int, level: Int) {
        if (cells.size >= MAX_CELL_CHANGES * 3) {
            truncated = true
            return
        }
        cells.add(tick)
        cells.add(cell)
        cells.add(level)
    }

    fun flip(tick: Int, lever: WaterLever, source: FlipSource) {
        flips.add(tick)
        flips.add(lever.ordinal)
        flips.add(source.ordinal)
    }

    fun water(tick: Int, on: Boolean, source: FlipSource) {
        waters.add(tick)
        waters.add(if (on) 1 else 0)
        waters.add(source.ordinal)
    }

    fun door(tick: Int, door: Int, closed: Boolean) {
        doors.add(tick)
        doors.add(door)
        doors.add(if (closed) 1 else 0)
    }

    fun click(tick: Int, lever: WaterLever) {
        clicks.add(tick)
        clicks.add(lever.ordinal)
    }

    companion object {
        const val MAX_CELL_CHANGES = 20_000
    }
}
