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
    /** Ticks from a click on the water lever to water reaching the entry hole (the entry dries as long after an off click, minus [sourceOffLead]). */
    val sourceDelay: Int = 2,
    /**
     * Ticks by which the entry dries sooner after an off click than it gets wet after an on click: 0 when the change of the
     * feed travels down the shaft at the same speed both ways (the slug at the entry is exactly off click - on click), about
     * one water tick if the lever plugs the shaft right above the entry (fitted by calibration, measured live).
     */
    val sourceOffLead: Int = 0,
    /** Ticks from water reaching an outlet to its door toggling. */
    val doorDelay: Int = 2,
    /** Whether a lone 1.8 drip falling through an outlet toggles its door like real water (unknown; fitted by calibration). */
    val dripArrivals: Boolean = true,
) {
    val plusXFirst: Boolean get() = BoardRotation.plusXFirst(rotation)

    /** In-plane neighbours in notification order. */
    val notifyOrder: IntArray by lazy { BoardRotation.notifyOrder(rotation) }

    /** Sideways directions in the order 1.8 fills them. */
    val sideOrder: IntArray by lazy { if (plusXFirst) intArrayOf(Dir.RIGHT, Dir.LEFT) else intArrayOf(Dir.LEFT, Dir.RIGHT) }

    init {
        require(tickDelay in 1 .. 7 && externalDelay in 1 .. 7) { "delays must fit the 8-slot tick wheel" }
    }

    /** Simulator tick at which the water reaches (on) or stops reaching (off) the entry hole after a water click at [click]. */
    fun sourceTick(click: Int, on: Boolean) = click + sourceDelay - if (on) 0 else sourceOffLead

    /** Short description for logs and /lbq water status. */
    fun describe() = "lever +$leverDelay, water +$sourceDelay, wake +$externalDelay" +
        (if (sourceOffLead != 0) ", off -$sourceOffLead" else "") + (if (dripArrivals) "" else ", drips ignored")
}
