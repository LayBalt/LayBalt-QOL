package com.laybalt.features.impl.dungeon.solvers.puzzles.water

/**
 * The static part of a board, as a grid of [width] x [height] cells (row 0 at the top, column 0 at relative -x).
 * Immutable; built from a reader snapshot ([fromCodes]) or from an ASCII toy board in tests ([fromAscii]).
 *
 * A toggle cell belongs to one block lever. It blocks water when its block is in the board plane ("extended"):
 * extended = [refExtended] xor the lever's bit in the parity mask (the mask is relative to the reference snapshot).
 */
class BoardLayout(
    val width: Int,
    val height: Int,
    /** Static solid cells. */
    val wall: BooleanArray,
    /** Lever ordinal (0..5) of each toggle cell, -1 for other cells. */
    val toggleLever: ByteArray,
    /** Whether the toggle block was in the board plane when the reference snapshot was taken. */
    val refExtended: BooleanArray,
    /** The cell water enters through; fed from a virtual cell above it while the source is on. -1 for none. */
    val source: Int,
    /** Cells whose filling counts as an arrival at that outlet, ordered by column descending (relative +x first). */
    val outlets: IntArray,
    val variant: Int = - 1,
) {
    val size = width * height

    /** Toggle cells of each block lever. */
    val toggleCells: Array<IntArray> = Array(WaterLever.blockLevers.size) { l ->
        (0 until size).filter { toggleLever[it].toInt() == l }.toIntArray()
    }

    private val outletIndex = IntArray(size) { - 1 }.also { idx -> outlets.forEachIndexed { i, c -> idx[c] = i } }

    /** Index into [outlets] of [cell], -1 if it is not an outlet. */
    fun outletOf(cell: Int) = outletIndex[cell]

    fun col(cell: Int) = cell % width
    fun row(cell: Int) = cell / width

    /** The neighbour of [cell] in direction [dir] ([Dir]), or -1 outside the grid. */
    fun neighbour(cell: Int, dir: Int): Int {
        val c = cell % width
        val r = cell / width
        return when (dir) {
            Dir.LEFT -> if (c > 0) cell - 1 else - 1
            Dir.RIGHT -> if (c < width - 1) cell + 1 else - 1
            Dir.DOWN -> if (r < height - 1) cell + width else - 1
            else -> if (r > 0) cell - width else - 1
        }
    }

    /** Whether the toggle block of [cell] is in the board plane for lever parity [mask]. */
    fun extended(cell: Int, mask: Int): Boolean {
        val l = toggleLever[cell].toInt()
        return l >= 0 && (refExtended[cell] xor ((mask shr l) and 1 == 1))
    }

    /** Whether [cell] is solid for parity [mask] (cells outside the grid are solid). */
    fun solid(cell: Int, mask: Int): Boolean = cell < 0 || wall[cell] || extended(cell, mask)

    /** Hash of the static board (walls, toggles and their reference planes), for matching recordings. */
    val fingerprint: Long by lazy {
        var h = - 0x340d631b7bdddcdbL
        for (c in 0 until size) {
            val v = if (wall[c]) 1 else if (toggleLever[c] >= 0) 2 + toggleLever[c] * 2 + (if (refExtended[c]) 1 else 0) else 0
            h = (h xor v.toLong()) * 0x100000001b3L
        }
        h
    }

    companion object {
        /**
         * Toy board for tests. Characters: `#` wall, `.` air, `S` water entry (fed from above while the source is on),
         * `O` outlet (air), `A`-`F` toggle of lever 0-5 in the board plane, `a`-`f` the same toggle behind the board.
         * Outlets are ordered by column descending.
         */
        fun fromAscii(rows: List<String>, variant: Int = - 1): BoardLayout {
            val height = rows.size
            val width = rows.maxOf { it.length }
            val size = width * height
            val wall = BooleanArray(size)
            val toggle = ByteArray(size) { - 1 }
            val ref = BooleanArray(size)
            var source = - 1
            val outlets = mutableListOf<Int>()
            for (r in 0 until height) for (c in 0 until width) {
                val cell = r * width + c
                when (val ch = rows[r].getOrElse(c) { '#' }) {
                    '#' -> wall[cell] = true
                    'S' -> source = cell
                    'O' -> outlets += cell
                    in 'A' .. 'F' -> {
                        toggle[cell] = (ch - 'A').toByte()
                        ref[cell] = true
                    }

                    in 'a' .. 'f' -> toggle[cell] = (ch - 'a').toByte()
                    else -> {}
                }
            }
            outlets.sortByDescending { it % width }
            return BoardLayout(width, height, wall, toggle, ref, source, outlets.toIntArray(), variant)
        }

        /**
         * The real board from reader material codes ([BoardCodes]) of the board plane and the hidden plane, both over the
         * [BoardGeometry] grid. Toggle blocks only count inside the playable area; the shaft above the entry hole is not
         * simulated (the source feeds the entry hole directly).
         */
        fun fromCodes(front: ByteArray, back: ByteArray, variant: Int): BoardLayout {
            val size = BoardGeometry.N
            val wall = BooleanArray(size)
            val toggle = ByteArray(size) { - 1 }
            val ref = BooleanArray(size)
            val outlets = mutableListOf<Int>()
            for (cell in 0 until size) {
                val x = BoardGeometry.x(cell)
                val y = BoardGeometry.y(cell)
                val f = front[cell]
                val b = back[cell]
                when {
                    y > BoardGeometry.ENTRY_Y -> wall[cell] = true
                    BoardGeometry.inToggleArea(x, y) && (BoardCodes.isToggle(f) || BoardCodes.isToggle(b)) -> {
                        val lever = if (BoardCodes.isToggle(f)) BoardCodes.lever(f) else BoardCodes.lever(b)
                        toggle[cell] = lever.toByte()
                        ref[cell] = BoardCodes.isToggle(f)
                    }

                    BoardCodes.blocks(f) -> wall[cell] = true
                }
                if (y == BoardGeometry.OUTLET_Y && x in BoardGeometry.PLAY_MIN_X .. BoardGeometry.PLAY_MAX_X && ! wall[cell] && toggle[cell] < 0) {
                    outlets += cell
                }
            }
            outlets.sortByDescending { BoardGeometry.x(it) }
            return BoardLayout(BoardGeometry.W, BoardGeometry.H, wall, toggle, ref, BoardGeometry.ENTRY_CELL, outlets.toIntArray(), variant)
        }
    }
}

/** Material codes the reader stores per cell. Pure, so the layout and tests can use them. */
object BoardCodes {
    const val AIR: Byte = 0
    const val WATER: Byte = 1

    /** Any block that stops water (stone bricks, glass, wool, ...). */
    const val WALL: Byte = 2

    /** Piston, piston head or a block being moved by a piston: solid, and a sign that something is in transit. */
    const val PISTON: Byte = 3

    /** A block water flows into (torch, button, carpet, ...). The 1.8 rules treat it like air. */
    const val OPEN: Byte = 4

    /** Unknown (chunk not loaded / never read). */
    const val UNKNOWN: Byte = 5

    /** Toggle material of lever l is `TOGGLE + l`. */
    const val TOGGLE: Byte = 8

    fun toggle(lever: Int): Byte = (TOGGLE + lever).toByte()
    fun isToggle(code: Byte) = code >= TOGGLE
    fun lever(code: Byte) = code - TOGGLE
    fun blocks(code: Byte) = code == WALL || code == PISTON || isToggle(code)
    fun passable(code: Byte) = code == AIR || code == WATER || code == OPEN

    fun char(code: Byte): Char = when {
        code == AIR -> '.'
        code == WATER -> '~'
        code == WALL -> '#'
        code == PISTON -> 'P'
        code == OPEN -> '?'
        code == UNKNOWN -> ' '
        else -> "QGKDET"[lever(code)]
    }
}
