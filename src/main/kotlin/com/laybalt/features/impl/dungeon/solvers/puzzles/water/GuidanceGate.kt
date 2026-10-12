package com.laybalt.features.impl.dungeon.solvers.puzzles.water

/**
 * Where guidance is shown. The Water Board session lives for the whole dungeon (leaving the room no longer resets it),
 * so its labels, tracers and HUD lines are only drawn while the player is in the room or close to it.
 */
object GuidanceGate {
    /** Blocks from the room centre within which guidance is drawn even when the map says another room. */
    const val NEAR_DISTANCE = 24.0

    fun visible(inRoom: Boolean, distanceToRoom: Double) = inRoom || distanceToRoom <= NEAR_DISTANCE

    /** [guidance] when the player is in or near the room, null otherwise. */
    fun forDisplay(guidance: Guidance, inRoom: Boolean, distanceToRoom: Double): Guidance? =
        if (visible(inRoom, distanceToRoom)) guidance else null
}
