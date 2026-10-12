package com.laybalt.features.impl.dungeon.solvers.puzzles.water

/**
 * Which shipped plays open the doors that are closed right now.
 *
 * Water arriving at an outlet toggles that outlet's door, and draining toggles nothing, so a shipped play toggles exactly
 * its three doors whatever the doors are when it starts (the doors sit in the pit, away from the water's path). Over
 * GF(2)^5 the ten three-door keys of a variant span every door state: one play for three closed doors, two plays for two
 * or four, three plays for one or five. This needs no water simulation and stays correct as long as the doors are read
 * again after each drain.
 */
object DoorAlgebra {
    /**
     * Every combination of the smallest size (1 to [maxSize] distinct plays) whose door sets XOR to [closed], as ascending
     * index arrays into [keys]. Empty for [closed] = 0 or when no combination exists. Deterministic.
     */
    fun combos(closed: Int, keys: IntArray, maxSize: Int = 5): List<IntArray> {
        if (closed and Door.ALL == 0) return emptyList()
        for (size in 1 .. minOf(maxSize, keys.size)) {
            val found = mutableListOf<IntArray>()
            val pick = IntArray(size)
            fun rec(start: Int, depth: Int, acc: Int) {
                if (depth == size) {
                    if (acc == closed) found += pick.copyOf()
                    return
                }
                for (i in start until keys.size) {
                    pick[depth] = i
                    rec(i + 1, depth + 1, acc xor keys[i])
                }
            }
            rec(0, 0, 0)
            if (found.isNotEmpty()) return found
        }
        return emptyList()
    }

    /** Rough duration model of a restart chain, in ticks (only used to rank combinations). */
    data class CostModel(val drainTicks: Int = 160, val fixTicks: Int = 15, val settleTicks: Int = 60)

    /**
     * The ordered plays that open the [closed] doors fastest, starting from lever [parity] (relative to the untouched
     * board). [boardReady] is true when the board is dry and the water off, so the first play needs no drain. Ties are
     * broken by the plays' key strings, so equal inputs always give the same chain. Empty when nothing is closed or no
     * combination exists.
     */
    fun best(closed: Int, table: ShippedTable, parity: Int, boardReady: Boolean, model: CostModel = CostModel()): List<ShippedPlay> {
        val keys = table.keys
        var best: List<ShippedPlay> = emptyList()
        var bestCost = Int.MAX_VALUE
        var bestName = ""
        for (combo in combos(closed, keys)) {
            val plays = combo.map { table.play(keys[it]) ?: return@map null }.filterNotNull()
            if (plays.size != combo.size) continue
            for (order in permutations(plays)) {
                val cost = cost(order, parity, boardReady, model)
                val name = order.joinToString("|") { it.key }
                if (cost < bestCost || cost == bestCost && name < bestName) {
                    best = order
                    bestCost = cost
                    bestName = name
                }
            }
        }
        return best
    }

    /** Estimated ticks to play [chain] from lever [parity]. */
    fun cost(chain: List<ShippedPlay>, parity: Int, boardReady: Boolean, model: CostModel = CostModel()): Int {
        var total = 0
        var current = parity
        chain.forEachIndexed { i, play ->
            if (i > 0 || ! boardReady) total += model.drainTicks
            total += Integer.bitCount(current xor play.startParity) * model.fixTicks
            total += play.lastClickTick + model.settleTicks
            current = play.endParity
        }
        return total
    }

    private fun <T> permutations(items: List<T>): List<List<T>> {
        if (items.size <= 1) return listOf(items)
        return items.indices.flatMap { i ->
            val rest = items.toMutableList().also { it.removeAt(i) }
            permutations(rest).map { listOf(items[i]) + it }
        }
    }
}
