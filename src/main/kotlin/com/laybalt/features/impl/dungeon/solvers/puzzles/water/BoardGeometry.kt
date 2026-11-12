package com.laybalt.features.impl.dungeon.solvers.puzzles.water

/**
 * The Water Board room in LayBalt room-centre coordinates (`ScanUtils.getRealCoord(rel, center, rotation)`).
 *
 * The board is the plane z = [BOARD_Z]; the six lever materials move between it and the hidden plane z = [HIDDEN_Z]
 * behind it. The grid covers x -10..10 and y 56..84 (door-pit floor to the top of the water shaft), row 0 = y 84.
 * Lever, door and pattern-indicator positions are Odin's (BSD-3-Clause, Copyright (c) 2025 odtheking), converted
 * from room-corner coordinates (minus 15 on x and z).
 */
object BoardGeometry {
    const val MIN_X = - 10
    const val MAX_X = 10
    const val MIN_Y = 56
    const val MAX_Y = 84
    const val W = MAX_X - MIN_X + 1
    const val H = MAX_Y - MIN_Y + 1
    const val N = W * H

    const val FRONT_Z = 10
    const val BOARD_Z = 11
    const val HIDDEN_Z = 12
    const val BACK_Z = 13

    /** The hole in the board's top wall; water enters the board here. */
    const val ENTRY_X = 0
    const val ENTRY_Y = 79

    /** Playable rows and columns: where toggle blocks may sit. */
    const val PLAY_MIN_X = - 9
    const val PLAY_MAX_X = 9
    const val TOGGLE_MIN_Y = 60
    const val TOGGLE_MAX_Y = 78

    /** The bottom wall; its open cells are the outlets. */
    const val OUTLET_Y = 60

    /** Water at y <= this (and not in the shaft above) counts as water on the board. */
    const val DRY_MAX_Y = 79

    fun cell(x: Int, y: Int): Int = (MAX_Y - y) * W + (x - MIN_X)
    fun x(cell: Int) = cell % W + MIN_X
    fun y(cell: Int) = MAX_Y - cell / W
    fun inGrid(x: Int, y: Int) = x in MIN_X .. MAX_X && y in MIN_Y .. MAX_Y
    fun inToggleArea(x: Int, y: Int) = x in PLAY_MIN_X .. PLAY_MAX_X && y in TOGGLE_MIN_Y .. TOGGLE_MAX_Y

    val ENTRY_CELL = cell(ENTRY_X, ENTRY_Y)

    /** Board cell right below the entry hole (the first cell water lands in). */
    val ENTRANCE_CELL = cell(ENTRY_X, ENTRY_Y - 1)

    /** Room-relative positions (x, y, z) of the levers, in [WaterLever] order (Odin's coordinates). */
    val LEVER_POS: Array<IntArray> = arrayOf(
        intArrayOf(5, 61, 5),     // QUARTZ
        intArrayOf(5, 61, 0),     // GOLD
        intArrayOf(5, 61, - 5),   // COAL
        intArrayOf(- 5, 61, 5),   // DIAMOND
        intArrayOf(- 5, 61, 0),   // EMERALD
        intArrayOf(- 5, 61, - 5), // CLAY
        intArrayOf(0, 60, - 10),  // WATER
    )

    /**
     * y of the door blocks: a door is closed while (0, DOOR_Y, Door.z) holds wool (3.2.3 / NoammAddons) or another solid
     * block (Odin), open while it is air, and in transit (last state kept) while a piston moves it.
     */
    const val DOOR_Y = 56

    /**
     * Odin's pattern indicators: (x, y, lever) per variant, checked in this order. Odin only looks at the hidden plane
     * (z 12), which assumes untouched levers; LayBalt checks both planes so a flipped lever does not hide the variant.
     */
    val INDICATORS: List<Triple<Int, Int, WaterLever>> = listOf(
        Triple(- 1, 77, WaterLever.CLAY),
        Triple(1, 78, WaterLever.EMERALD),
        Triple(- 1, 78, WaterLever.DIAMOND),
        Triple(- 1, 78, WaterLever.QUARTZ),
    )
}

/**
 * How the room rotation orders same-tick work in the 1.8 liquid code. [rot] is the rotation passed to
 * `ScanUtils.getRealCoord` (360 - room rotation); relative +x maps to world EAST, NORTH, WEST, SOUTH for 0, 90, 180, 270.
 */
object BoardRotation {
    /** World facings, numbered like 1.8 `EnumFacing` ordinals. */
    const val NORTH = 2
    const val SOUTH = 3
    const val WEST = 4
    const val EAST = 5

    fun normalize(rot: Int) = ((rot % 360) + 360) % 360

    /** World facing of relative +x. */
    fun plusXFacing(rot: Int) = when (normalize(rot)) {
        90 -> NORTH
        180 -> WEST
        270 -> SOUTH
        else -> EAST
    }

    private fun opposite(facing: Int) = when (facing) {
        NORTH -> SOUTH
        SOUTH -> NORTH
        WEST -> EAST
        else -> WEST
    }

    /**
     * Whether water spreading both ways fills relative +x first. 1.8 keeps the flow directions in an `EnumSet`, so they
     * are visited in `EnumFacing` ordinal order (north, south, west, east).
     */
    fun plusXFirst(rot: Int): Boolean {
        val plus = plusXFacing(rot)
        return plus < opposite(plus)
    }

    /**
     * The in-plane neighbours ([Dir] ordinals) in the order a block change notifies them. Vanilla 1.8 (as run by
     * Spigot) notifies west, east, down, up, north, south; the two facings along the board's z axis are not part of
     * the board.
     */
    fun notifyOrder(rot: Int): IntArray {
        val plus = plusXFacing(rot)
        val minus = opposite(plus)
        val order = ArrayList<Int>(4)
        for (facing in intArrayOf(WEST, EAST, - 1, - 2, NORTH, SOUTH)) when (facing) {
            - 1 -> order += Dir.DOWN
            - 2 -> order += Dir.UP
            plus -> order += Dir.RIGHT
            minus -> order += Dir.LEFT
        }
        return order.toIntArray()
    }
}

/** In-plane directions of the board grid (RIGHT = relative +x). */
object Dir {
    const val LEFT = 0
    const val RIGHT = 1
    const val DOWN = 2
    const val UP = 3
}
