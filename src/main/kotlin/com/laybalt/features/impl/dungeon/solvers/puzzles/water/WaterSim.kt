package com.laybalt.features.impl.dungeon.solvers.puzzles.water

/** Optional observer of a simulation run. */
interface SimListener {
    fun placed(cell: Int, level: Int, tick: Int) {}
    fun removed(cell: Int, tick: Int) {}

    /** A cell that already held water changed its level. */
    fun changed(cell: Int, level: Int, tick: Int) {}
    fun arrival(outlet: Int, tick: Int) {}
}

/**
 * Event-driven, tick-exact water simulation on a 2-D board, following the 1.8 liquid rules that Hypixel's server runs
 * (written from their observable behaviour; nothing is transcribed from Minecraft code):
 *
 * - **Update** of a flowing cell (every [WaterRules.tickDelay] ticks while it changes): its level is recomputed from its
 *   sideways neighbours (falling water counts as strength 0) plus one, or taken from the water above it (falling).
 *   A cell whose level does not change becomes still water and stops ticking until a neighbour changes.
 * - **Down** first: over an open cell the water falls (levels 8+). A cell that just vanished over air still leaves a
 *   level-7 drop below it (the 1.8 "drip").
 * - **Sideways** only on top of a solid block: the level weakens by one per block (seven blocks from a falling cell),
 *   towards the nearest hole within five blocks; equally near holes, or none, split the flow both ways.
 * - **Lever flips** move toggle blocks between the board and the plane behind it: a block moving in destroys the water in
 *   its cell, a block moving out leaves air. Both wake the neighbours.
 *
 * Arrivals are placements into an outlet cell (each dry -> wet transition of an outlet).
 */
object WaterSim {
    private val SIDES = intArrayOf(Dir.LEFT, Dir.RIGHT)
    private const val NO_HOLE = 1000

    /** Runs one tick: due external changes, then the scheduled cell updates in the order they were scheduled. */
    fun step(s: BoardState, listener: SimListener? = null) {
        val t = s.tick + 1
        s.tick = t
        while (s.nextExternal < s.externals.size && s.externals[s.nextExternal].tick <= t) {
            apply(s, s.externals[s.nextExternal ++], t, listener)
        }

        val bucket = s.wheel[t and (BoardState.WHEEL - 1)]
        val count = bucket.size
        if (count == 0) return
        if (s.scratch.size < count) s.scratch = IntArray(count * 2)
        val cells = s.scratch
        for (i in 0 until count) cells[i] = bucket[i]
        bucket.clear()
        s.pending -= count
        for (i in 0 until count) s.due[cells[i]] = BoardState.NONE
        for (i in 0 until count) {
            val c = cells[i]
            if (s.level[c] >= 0 && s.dynamic[c]) update(s, c, t, listener)
        }
    }

    /** Steps until [until] (inclusive), or until nothing can change any more when [stopWhenSettled]. */
    fun run(s: BoardState, until: Int, listener: SimListener? = null, stopWhenSettled: Boolean = true) {
        while (s.tick < until) {
            if (stopWhenSettled && s.settled) return
            step(s, listener)
        }
    }

    private fun apply(s: BoardState, change: External, t: Int, listener: SimListener?) {
        val wake = t + s.rules.externalDelay
        when (change) {
            is External.Flip -> {
                s.mask = s.mask xor (1 shl change.lever)
                for (c in s.layout.toggleCells[change.lever]) {
                    if (s.layout.extended(c, s.mask) && s.level[c] >= 0) {
                        s.level[c] = - 1
                        s.dynamic[c] = false
                        s.lastChange = t
                        listener?.removed(c, t)
                    }
                    notify(s, c, wake)
                }
            }

            is External.Source -> {
                s.sourceOn = change.on
                val src = s.layout.source
                if (src < 0) return
                if (change.on) {
                    if (s.level[src] < 0 && ! s.solid(src)) place(s, src, 8, t, listener)
                }
                else if (s.level[src] >= 0 && ! s.dynamic[src]) {
                    s.dynamic[src] = true
                    s.schedule(src, wake)
                }
            }
        }
    }

    /** Level of the water feeding [cell] from above, -1 if none. */
    private fun above(s: BoardState, cell: Int): Int {
        if (cell == s.layout.source) return if (s.sourceOn) 8 else - 1
        val up = s.layout.neighbour(cell, Dir.UP)
        return if (up < 0) - 1 else s.level[up].toInt()
    }

    private fun update(s: BoardState, c: Int, t: Int, listener: SimListener?) {
        val layout = s.layout
        var i = s.level[c].toInt()
        if (i > 0) {
            var m = - 100
            for (dir in SIDES) {
                val n = layout.neighbour(c, dir)
                if (n < 0) continue
                var v = s.level[n].toInt()
                if (v < 0) continue
                if (v >= 8) v = 0
                m = if (m >= 0 && v >= m) m else v
            }
            var next = m + 1
            if (next >= 8 || m < 0) next = - 1
            val a = above(s, c)
            if (a >= 0) next = if (a >= 8) a else a + 8

            if (next == i) s.dynamic[c] = false
            else {
                i = next
                if (next < 0) remove(s, c, t, listener)
                else {
                    s.level[c] = next.toByte()
                    s.lastChange = t
                    s.schedule(c, t + s.rules.tickDelay)
                    notify(s, c, t + s.rules.tickDelay)
                    listener?.changed(c, next, t)
                }
            }
        }
        else s.dynamic[c] = false

        val below = layout.neighbour(c, Dir.DOWN)
        if (canFlowInto(s, below)) {
            if (i < 0 && ! s.rules.dripQuirk) return
            place(s, below, if (i >= 8) i else i + 8, t, listener, drip = i < 0)
        }
        else if (i >= 0 && (i == 0 || s.solid(below))) {
            val dirs = flowDirections(s, c)
            if (dirs == 0) return
            val k = if (i >= 8) 1 else i + 1
            if (k >= 8) return
            for (dir in s.rules.sideOrder) {
                if (dirs and (1 shl dir) == 0) continue
                val n = layout.neighbour(c, dir)
                if (canFlowInto(s, n)) place(s, n, k, t, listener)
            }
        }
    }

    /** Bit set of the sideways directions water spreads to from [c]: towards the nearest hole within reach. */
    fun flowDirections(s: BoardState, c: Int): Int {
        var best = NO_HOLE
        var set = 0
        for (dir in SIDES) {
            val n = s.layout.neighbour(c, dir)
            if (n < 0 || s.solid(n) || s.level[n].toInt() == 0) continue
            val cost = if (! s.solid(s.layout.neighbour(n, Dir.DOWN))) 0 else holeDistance(s, n, dir)
            if (cost < best) set = 0
            if (cost <= best) {
                set = set or (1 shl dir)
                best = cost
            }
        }
        return set
    }

    /** Cost of the nearest hole beyond [from] in direction [dir] (offset - 1), [NO_HOLE] if none within reach. */
    private fun holeDistance(s: BoardState, from: Int, dir: Int): Int {
        var p = from
        var dist = 1
        while (true) {
            val q = s.layout.neighbour(p, dir)
            if (q < 0 || s.solid(q) || s.level[q].toInt() == 0) return NO_HOLE
            if (! s.solid(s.layout.neighbour(q, Dir.DOWN))) return dist
            if (dist >= s.rules.slopeDepth) return NO_HOLE
            p = q
            dist ++
        }
    }

    private fun canFlowInto(s: BoardState, cell: Int) = cell >= 0 && s.level[cell] < 0 && ! s.solid(cell)

    private fun place(s: BoardState, cell: Int, lvl: Int, t: Int, listener: SimListener?, drip: Boolean = false) {
        s.level[cell] = lvl.toByte()
        s.dynamic[cell] = true
        s.lastChange = t
        s.schedule(cell, t + s.rules.tickDelay)
        notify(s, cell, t + s.rules.tickDelay)
        listener?.placed(cell, lvl, t)
        val outlet = s.layout.outletOf(cell)
        if (outlet >= 0 && (! drip || s.rules.dripArrivals)) {
            s.arrivals[outlet] ++
            if (s.firstArrival[outlet] < 0) s.firstArrival[outlet] = t
            s.lastArrival[outlet] = t
            listener?.arrival(outlet, t)
        }
    }

    private fun remove(s: BoardState, cell: Int, t: Int, listener: SimListener?) {
        s.level[cell] = - 1
        s.dynamic[cell] = false
        s.lastChange = t
        notify(s, cell, t + s.rules.tickDelay)
        listener?.removed(cell, t)
    }

    /** Wakes the still-water neighbours of [cell]: they become flowing and tick at [at]. */
    private fun notify(s: BoardState, cell: Int, at: Int) {
        for (dir in s.rules.notifyOrder) {
            val n = s.layout.neighbour(cell, dir)
            if (n >= 0 && s.level[n] >= 0 && ! s.dynamic[n]) {
                s.dynamic[n] = true
                s.schedule(n, at)
            }
        }
    }
}
