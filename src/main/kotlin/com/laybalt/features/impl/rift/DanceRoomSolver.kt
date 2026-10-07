package com.laybalt.features.impl.rift

//#if CHEAT

import com.laybalt.features.Feature

/**
 * Work in progress: the legacy LayBalt "Dance Room Solver" only ever existed as a config switch ("Coming soon"); no
 * implementation exists in the old mod or in any reference project.
 *
 * Planned design (same skeleton as [VampireHelper]): read the Mirrorverse dance room instructions from the title and
 * subtitle packets (`ClientboundSetTitleTextPacket` / `ClientboundSetSubtitleTextPacket`, exact Hypixel strings still to
 * be recorded in game) and hold the matching movement keys (move, stand still, sneak, jump, punch) for the display time.
 *
 * As a WIP feature it has no settings and registers no listeners, so it stays greyed out in the click GUI.
 */
object DanceRoomSolver: Feature("Solves the Rift dance room. Coming soon.") {
    override val wip = true
}

//#endif
