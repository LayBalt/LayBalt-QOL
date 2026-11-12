package com.laybalt.features.impl.dungeon.solvers.puzzles.water

import java.util.EnumMap
import kotlin.math.roundToInt

/** A flip of [lever] [tick] ticks after the water click. */
data class TimedClick(val lever: WaterLever, val tick: Int)

/**
 * One entry of `waterSolutions.json` (Odin's non-optimised set, BSD-3-Clause, Copyright (c) 2025 odtheking): the
 * timed flips that open exactly the doors in [key] when played from the variant's untouched lever state.
 *
 * Times are seconds after the click on the water lever. A `0.0` entry of a block lever is a flip *before* the water;
 * the water lever's first `0.0` is the start itself and a later water entry turns the water off again (12 of the 40
 * entries do that and keep steering the remaining water with further flips).
 */
class ShippedPlay(
    val variant: Int,
    val key: String,
    val times: Map<WaterLever, List<Double>>,
    /** Made by LayBalt (a static lever setting checked in the simulator), not one of the shipped solutions. */
    val synthetic: Boolean = false,
    /** Ticks after the water click by which the simulator expects the last door to open (synthetic plays). */
    val expectedTicks: Int? = null,
) {
    /** Doors this play toggles. */
    val doors: Int = Door.bitsOf(key)

    /** Flip ticks per lever after the water click, sorted. Every lever has an entry (possibly empty). */
    val schedule: Map<WaterLever, List<Int>> = EnumMap<WaterLever, List<Int>>(WaterLever::class.java).apply {
        for (lever in WaterLever.entries) put(lever, times[lever].orEmpty().map(::ticks).sorted())
    }

    /** Number of flips before the water per block lever (the `0.0` entries), indexed by lever ordinal. */
    val preWater = IntArray(WaterLever.blockLevers.size) { i -> schedule.getValue(WaterLever.entries[i]).count { it == 0 } }

    /** Parity every block lever must have, relative to the untouched board, when the water is pulled. */
    val startParity: Int = WaterLever.blockLevers.fold(0) { acc, l -> if (preWater[l.ordinal] % 2 == 1) acc or l.bit else acc }

    /** Every flip after the water click (block levers and later water entries), sorted by tick, then lever order. */
    val timedClicks: List<TimedClick> = schedule.flatMap { (lever, ticks) ->
        val skip = if (lever == WaterLever.WATER) 1 else 0
        ticks.drop(skip).filter { lever == WaterLever.WATER || it > 0 }.map { TimedClick(lever, it) }
    }.sortedWith(compareBy({ it.tick }, { it.lever.ordinal }))

    /** Tick of the last timed click (0 when everything happens before the water). */
    val lastClickTick: Int = timedClicks.maxOfOrNull { it.tick } ?: 0

    /** Ticks after the start at which this play turns the water off itself. */
    val waterOffTicks: List<Int> = schedule.getValue(WaterLever.WATER).drop(1)
    val turnsWaterOff get() = waterOffTicks.size % 2 == 1

    /** Lever parity (relative to the untouched board) after the play: the start parity plus every timed block flip. */
    val endParity: Int = timedClicks.filter { it.lever.isBlockLever }.fold(startParity) { acc, c -> acc xor c.lever.bit }

    override fun toString() = (if (synthetic) "static " else "") + "v$variant/$key"

    companion object {
        fun ticks(seconds: Double) = (seconds * 20).roundToInt()

        /** One run with the block levers set to [parity] (relative to the untouched board) and no timed flips. */
        fun static(variant: Int, doors: Int, parity: Int, expectedTicks: Int?): ShippedPlay {
            val times = HashMap<WaterLever, List<Double>>()
            times[WaterLever.WATER] = listOf(0.0)
            for (lever in WaterLever.inMask(parity)) times[lever] = listOf(0.0)
            return ShippedPlay(variant, Door.keyOf(doors), times, synthetic = true, expectedTicks = expectedTicks)
        }
    }
}

/** The ten plays of one board variant, by door bits. */
class ShippedTable(val variant: Int, plays: Collection<ShippedPlay>) {
    val plays: Map<Int, ShippedPlay> = plays.associateBy { it.doors }

    /** Door bits of every play, ordered by key string (deterministic). */
    val keys: IntArray = plays.sortedBy { it.key }.map { it.doors }.toIntArray()

    fun play(doors: Int): ShippedPlay? = plays[doors]

    companion object {
        /**
         * Parses the whole `waterSolutions.json` structure (variant -> key -> lever key -> times). Unknown lever keys
         * are ignored; a variant without entries is left out.
         */
        fun parseAll(json: Map<String, Map<String, Map<String, List<Double>>>>): Map<Int, ShippedTable> =
            json.mapNotNull { (variantKey, entries) ->
                val variant = variantKey.toIntOrNull() ?: return@mapNotNull null
                val plays = entries.map { (key, levers) ->
                    val times = levers.mapNotNull { (k, t) -> WaterLever.fromKey(k)?.let { it to t } }.toMap()
                    ShippedPlay(variant, key, times)
                }
                if (plays.isEmpty()) null else variant to ShippedTable(variant, plays)
            }.toMap()
    }
}
