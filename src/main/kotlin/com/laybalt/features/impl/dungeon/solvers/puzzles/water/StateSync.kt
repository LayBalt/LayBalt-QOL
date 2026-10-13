package com.laybalt.features.impl.dungeon.solvers.puzzles.water

/**
 * Turns the board as observed into a simulator state at tick [SyncInput.now] (the client's server-tick counter, which is
 * the simulator's time frame for live boards).
 *
 * Levels come straight from the board. Which cells still have an update pending is rebuilt from when they last changed:
 * a cell that changed less than [WaterRules.tickDelay] ticks ago updates again [WaterRules.tickDelay] ticks after its
 * change, and its change woke its still neighbours for the same tick; a lever that moved less than
 * [WaterRules.externalDelay] ticks ago woke the water next to its blocks. Everything else is still water. Updates due on
 * the same tick run in the order the changes behind them were seen in (the server sends a tick's changes in order;
 * across chunk sections that order can be lost, which the shadow check would notice).
 */
object StateSync {
    class SyncInput(
        val layout: BoardLayout,
        val rules: WaterRules,
        /** Water levels of the board plane (1.8 encoding, -1 none), over the layout's grid. */
        val levels: ByteArray,
        /** Tick of each cell's last change, and a running counter giving the order of those changes. */
        val changeTick: IntArray,
        val changeSeq: IntArray,
        /** Lever parity relative to the layout's reference snapshot. */
        val mask: Int,
        /** Water coming into the board right now. */
        val sourceOn: Boolean,
        val now: Int,
        /** Recent lever moves (lever ordinal, tick of the first block move). */
        val recentFlips: List<Pair<Int, Int>> = emptyList(),
        /** Changes that have happened but are not visible yet (the source reacting to a water lever flip). */
        val pending: List<External> = emptyList(),
    )

    fun sync(input: SyncInput): BoardState {
        val layout = input.layout
        val rules = input.rules
        val now = input.now
        val s = BoardState(layout, rules)
        s.tick = now
        s.mask = input.mask and SimRunner.ALL_LEVERS
        s.sourceOn = input.sourceOn
        s.lastChange = now
        for (c in 0 until layout.size) {
            val lvl = input.levels[c].toInt()
            if (lvl >= 0 && ! layout.solid(c, s.mask)) s.level[c] = lvl.toByte()
        }

        // due tick and order key of every cell that still has an update pending
        val due = IntArray(layout.size) { BoardState.NONE }
        val order = LongArray(layout.size)
        fun want(cell: Int, at: Int, key: Long) {
            if (at <= now || at - now >= BoardState.WHEEL) return
            if (s.level[cell] < 0) return
            if (due[cell] == BoardState.NONE || at < due[cell] || at == due[cell] && key < order[cell]) {
                due[cell] = at
                order[cell] = key
            }
        }

        val delay = rules.tickDelay
        for (c in 0 until layout.size) {
            val changed = input.changeTick[c]
            if (changed <= now - delay) continue
            // A change schedules the cell itself first, then wakes its still neighbours (in the notification order).
            val key = input.changeSeq[c].toLong() * 8
            want(c, changed + delay, key)
            for ((i, dir) in rules.notifyOrder.withIndex()) {
                val n = layout.neighbour(c, dir)
                if (n >= 0 && (input.changeTick[n] <= now - delay)) want(n, changed + delay, key + 1 + i)
            }
        }
        for ((lever, tick) in input.recentFlips) {
            if (tick <= now - rules.externalDelay || lever !in 0 until WaterLever.blockLevers.size) continue
            for (c in layout.toggleCells[lever]) for (dir in rules.notifyOrder) {
                val n = layout.neighbour(c, dir)
                if (n >= 0) want(n, tick + rules.externalDelay, Long.MIN_VALUE / 2 + tick)
            }
        }

        val cells = (0 until layout.size).filter { due[it] != BoardState.NONE }
            .sortedWith(compareBy({ due[it] }, { order[it] }, { it }))
        for (c in cells) {
            s.dynamic[c] = true
            s.schedule(c, due[c])
        }
        s.addExternals(input.pending.filter { it.tick > now })
        // Water still standing in the entry although nothing feeds it any more: let the simulator take the feed away.
        val src = layout.source
        if (! input.sourceOn && src >= 0 && s.level[src] >= 0 && input.pending.none { it is External.Source }) {
            s.sourceOn = true
            s.addExternal(External.Source(now + 1, false))
        }
        return s
    }
}
