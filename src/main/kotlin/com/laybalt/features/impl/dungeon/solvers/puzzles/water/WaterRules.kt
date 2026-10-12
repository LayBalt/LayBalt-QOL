package com.laybalt.features.impl.dungeon.solvers.puzzles.water

/**
 * Parameters of the water simulation. The first group is vanilla 1.8 behaviour (Hypixel runs a 1.8 server; the
 * client only mirrors it), the second group is timing that depends on Hypixel's lever mechanism and is fitted by the
 * calibration gate. All ticks are server ticks.
 */
data class WaterRules(
    /** Ticks between two updates of a water cell (1.8 water tick rate). */
    val tickDelay: Int = 5,
    /** Deepest recursion of the hole search: a hole up to `slopeDepth + 1` cells away attracts the flow. */
    val slopeDepth: Int = 4,
    /** 1.8 places a level-7 drop below a cell that vanishes over air (no guard on the level before the down branch). */
    val dripQuirk: Boolean = true,
    /** Rotation passed to `getRealCoord`; orders same-tick work (see [BoardRotation]). */
    val rotation: Int = 0,
    /** Delay of the ticks scheduled by a lever or source change (they happen before the world's tick counter moves on). */
    val externalDelay: Int = 4,
    /** Ticks from a click on a block lever to the blocks moving. */
    val leverDelay: Int = 1,
    /** Ticks from a click on the water lever to water entering (or no longer entering) the board. */
    val sourceDelay: Int = 2,
    /** Ticks from water reaching an outlet to its door toggling. */
    val doorDelay: Int = 2,
) {
    val plusXFirst: Boolean get() = BoardRotation.plusXFirst(rotation)

    /** In-plane neighbours in notification order. */
    val notifyOrder: IntArray by lazy { BoardRotation.notifyOrder(rotation) }

    /** Sideways directions in the order 1.8 fills them. */
    val sideOrder: IntArray by lazy { if (plusXFirst) intArrayOf(Dir.RIGHT, Dir.LEFT) else intArrayOf(Dir.LEFT, Dir.RIGHT) }

    init {
        require(tickDelay in 1 .. 7 && externalDelay in 1 .. 7) { "delays must fit the 8-slot tick wheel" }
    }
}
