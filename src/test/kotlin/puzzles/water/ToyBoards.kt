package puzzles.water

import com.laybalt.features.impl.dungeon.solvers.puzzles.water.*

/**
 * Self-authored toy boards for the simulator, calibration and planner tests (no Hypixel data).
 *
 * The comb: water enters at the top and lands on a channel; five holes in the channel floor (levers 0-4: A-E, closed in
 * the reference) lead down shafts to five outlets; lever 5 (F) is a block in the channel between holes B and C (open in
 * the reference). Water flows towards the nearest open hole within five blocks, otherwise spreads seven blocks both ways,
 * so static settings reach one or two outlets and timed flips can visit three in a row - like the real board.
 */
object ToyBoards {
    const val WIDTH = 23
    const val ENTRY = 11

    /** Hole columns of levers A-E; outlets sit right below them. */
    val HOLES = intArrayOf(4, 7, 11, 15, 18)
    const val F_COL = 9

    val comb: BoardLayout by lazy { BoardLayout.fromAscii(combRows()) }

    fun combRows(): List<String> {
        fun row(fill: Char, block: (CharArray) -> Unit = {}) = CharArray(WIDTH) { fill }.also { it[0] = '#'; it[WIDTH - 1] = '#'; block(it) }.concatToString()
        return listOf(
            row('#') { it[ENTRY] = 'S' },
            row('.') { it[F_COL] = 'f' },
            row('#') { r -> HOLES.forEachIndexed { l, c -> r[c] = 'A' + l } },
            row('#') { r -> HOLES.forEach { r[it] = '.' } },
            row('#') { r -> HOLES.forEach { r[it] = '.' } },
            row('#') { r -> HOLES.forEach { r[it] = 'O' } },
            row('#') { r -> HOLES.forEach { r[it] = '.' } },
            row('#'),
        )
    }

    /** Outlet index (in [BoardLayout.outlets], ordered by column descending) of the hole of lever [l]. */
    fun outletOfLever(l: Int): Int = comb.outlets.indexOfFirst { comb.col(it) == HOLES[l] }

    val A = WaterLever.QUARTZ
    val B = WaterLever.GOLD
    val C = WaterLever.COAL
    val D = WaterLever.DIAMOND
    val E = WaterLever.EMERALD
    val F = WaterLever.CLAY

    /** A play on the comb: [pre] flipped before the water, then [timed] (lever, seconds after the water click). */
    fun play(key: String, pre: Set<WaterLever>, vararg timed: Pair<WaterLever, Double>): ShippedPlay {
        val times = HashMap<WaterLever, MutableList<Double>>()
        times.getOrPut(WaterLever.WATER) { mutableListOf() } += 0.0
        pre.forEach { times.getOrPut(it) { mutableListOf() } += 0.0 }
        timed.forEach { (lever, t) -> times.getOrPut(lever) { mutableListOf() } += t }
        return ShippedPlay(0, key, times.mapValues { it.value.sorted() })
    }

    /** Hand-written candidate plays on the comb (their outlets depend on the timing rules). */
    val candidates: List<Pair<Set<WaterLever>, List<Pair<WaterLever, Double>>>> = listOf(
        setOf(C) to listOf(C to 2.0, B to 2.0, D to 2.0),                       // C, then B and D together
        setOf(C) to listOf(C to 2.0, A to 2.0, E to 2.0),                       // C, then A and E by spreading
        emptySet<WaterLever>() to listOf(B to 3.0, D to 3.0, A to 3.0),         // spread first, then open B, D and A
        setOf(D) to listOf(D to 2.5, E to 2.5, C to 5.0, E to 5.0),             // D, E, then C
        setOf(C) to listOf(C to 2.0, D to 2.0, D to 4.5, E to 4.5),             // C, D, then E
        setOf(C) to listOf(C to 2.0, B to 2.0, B to 4.5, A to 4.5),             // C, B, then A
        setOf(B) to listOf(B to 2.5, C to 2.5, C to 4.5, E to 4.5),             // B, C, then E
        setOf(D) to listOf(D to 2.5, C to 2.5, C to 4.5, A to 4.5),             // D, C, then A
        emptySet<WaterLever>() to listOf(A to 3.0, B to 3.0, E to 3.0),         // spread first, then open A, B and E
        emptySet<WaterLever>() to listOf(A to 3.0, D to 3.0, E to 3.0),         // spread first, then open A, D and E
        emptySet<WaterLever>() to listOf(B to 3.0, D to 3.0, E to 3.0),         // spread first, then open B, D and E
    )
}
