package com.laybalt.features.impl.dungeon.solvers.puzzles.water

/** A lever or source change applied to the simulation at [tick]. */
sealed interface External {
    val tick: Int

    /** Block lever [lever] (ordinal 0..5) moves its blocks. */
    data class Flip(override val tick: Int, val lever: Int) : External

    /** The water source turns on or off. */
    data class Source(override val tick: Int, val on: Boolean) : External
}

/** Growable int list without boxing. */
class IntList(capacity: Int = 16) {
    var data = IntArray(capacity)
        private set
    var size = 0
        private set

    fun add(v: Int) {
        if (size == data.size) data = data.copyOf(size * 2)
        data[size ++] = v
    }

    operator fun get(i: Int) = data[i]
    fun clear() {
        size = 0
    }

    fun copy() = IntList(maxOf(16, size)).also { other -> for (i in 0 until size) other.add(data[i]) }

    /** Makes this list equal to [other], reusing its array when it is big enough. */
    fun copyFrom(other: IntList) {
        if (data.size < other.size) data = IntArray(maxOf(16, other.size))
        other.data.copyInto(data, 0, 0, other.size)
        size = other.size
    }
}

/**
 * The changing part of a simulated board: water levels (1.8 encoding: -1 none, 1..7 flowing with 1 strongest,
 * 8..15 falling), which cells are flowing ("dynamic") water, the pending tick of each cell, lever parity and the
 * water source.
 *
 * Pending ticks behave like the 1.8 tick list: one entry per cell (later requests are dropped while one is pending), and
 * an entry is not cancelled when the water in its cell is destroyed; if water flows back in before it fires, the cell
 * ticks at the old time. The entries of the tick being processed are released first, so a cell can be scheduled again
 * while its current entry still fires.
 */
class BoardState(val layout: BoardLayout, val rules: WaterRules) {
    var tick = 0
    var mask = 0
    var sourceOn = false

    val level = ByteArray(layout.size) { - 1 }
    val dynamic = BooleanArray(layout.size)
    internal val due = IntArray(layout.size) { NONE }
    internal val wheel = Array(WHEEL) { IntList() }
    internal var pending = 0
    internal var scratch = IntArray(64)

    internal val externals = ArrayList<External>()
    internal var nextExternal = 0

    /** Placements into each outlet cell, and the first / last tick one happened (-1 if never). */
    val arrivals = IntArray(layout.outlets.size)
    val firstArrival = IntArray(layout.outlets.size) { - 1 }
    val lastArrival = IntArray(layout.outlets.size) { - 1 }

    /** Last tick any water cell was placed, removed or changed level. */
    var lastChange = 0

    /** Nothing is scheduled and no external change is left: the board cannot change on its own any more. */
    val settled get() = pending == 0 && nextExternal >= externals.size

    fun wet(cell: Int) = level[cell] >= 0
    fun waterCount() = level.count { it >= 0 }
    fun solid(cell: Int) = layout.solid(cell, mask)

    /** Queues an external change; changes are applied in tick order (stable for equal ticks). */
    fun addExternal(change: External) {
        var i = externals.size
        while (i > nextExternal && externals[i - 1].tick > change.tick) i --
        externals.add(i, change)
    }

    fun addExternals(changes: Iterable<External>) = changes.forEach(::addExternal)

    /** Puts water of [lvl] in [cell] without scheduling anything (test setup and sync). */
    fun setWater(cell: Int, lvl: Int, flowing: Boolean = false) {
        level[cell] = lvl.toByte()
        dynamic[cell] = flowing && lvl >= 0
    }

    /** Schedules [cell] to tick at [at], 1 to 7 ticks ahead (ignored while it has a pending tick). */
    fun schedule(cell: Int, at: Int) {
        require(at - tick in 1 until WHEEL) { "tick $at is out of the wheel's range at $tick" }
        if (due[cell] != NONE) return
        due[cell] = at
        wheel[at and (WHEEL - 1)].add(cell)
        pending ++
    }

    fun hasPendingTick(cell: Int) = due[cell] != NONE

    fun copy(): BoardState = BoardState(layout, rules).also(::copyInto)

    /** Makes [o] (a state of the same layout) an exact copy of this one, reusing its arrays (no allocation in the hot path). */
    fun copyInto(o: BoardState) {
        require(o.layout === layout) { "copyInto needs a state of the same layout" }
        o.tick = tick
        o.mask = mask
        o.sourceOn = sourceOn
        level.copyInto(o.level)
        dynamic.copyInto(o.dynamic)
        due.copyInto(o.due)
        for (i in 0 until WHEEL) o.wheel[i].copyFrom(wheel[i])
        o.pending = pending
        o.externals.clear()
        o.externals.addAll(externals)
        o.nextExternal = nextExternal
        arrivals.copyInto(o.arrivals)
        firstArrival.copyInto(o.firstArrival)
        lastArrival.copyInto(o.lastArrival)
        o.lastChange = lastChange
    }

    /**
     * Hash of everything that decides how this state evolves: levels, still/flowing cells and the offset of each pending
     * update, lever parity, the source, the changes still queued, and the arrivals so far. Equal states (up to the time
     * they are at) hash equal; used to drop duplicates in the timed search.
     */
    fun key(): Long {
        var h = - 0x340d631b7bdddcdbL
        fun mix(v: Long) {
            h = (h xor v) * 0x100000001b3L
        }
        for (c in 0 until layout.size) {
            val l = level[c].toInt()
            if (l < 0 && due[c] == NONE) continue
            mix(c.toLong())
            mix(l.toLong())
            mix(if (dynamic[c]) 1 else 0)
            mix(if (due[c] == NONE) - 1 else (due[c] - tick).toLong())
        }
        mix(mask.toLong())
        mix(if (sourceOn) 1 else 0)
        for (i in nextExternal until externals.size) {
            val e = externals[i]
            mix((e.tick - tick).toLong())
            mix(if (e is External.Flip) e.lever.toLong() else if ((e as External.Source).on) 100 else 101)
        }
        for (a in arrivals) mix(a.toLong())
        return h
    }

    companion object {
        const val NONE = Int.MIN_VALUE
        internal const val WHEEL = 8
    }
}
