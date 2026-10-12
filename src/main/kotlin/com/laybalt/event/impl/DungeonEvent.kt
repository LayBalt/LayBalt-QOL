package com.laybalt.event.impl

import com.laybalt.event.Event
import com.laybalt.utils.dungeons.DungeonPlayer
import com.laybalt.utils.dungeons.enums.SecretType
import com.laybalt.utils.dungeons.map.core.RoomState
import com.laybalt.utils.dungeons.map.core.Tile
import com.laybalt.utils.dungeons.map.core.UniqueRoom
import net.minecraft.core.BlockPos

abstract class DungeonEvent: Event(false) {
    abstract class RoomEvent(val room: UniqueRoom): DungeonEvent() {
        class onEnter(room: UniqueRoom): RoomEvent(room)
        class onExit(room: UniqueRoom): RoomEvent(room)
        class RotationFound(room: UniqueRoom, val rotation: Int, val corner: BlockPos): RoomEvent(room)

        class onStateChange(room: UniqueRoom, val oldState: RoomState, val newState: RoomState, val roomPlayers: List<DungeonPlayer>): RoomEvent(room)
    }

    class TileScannedEvent(val tile: Tile): DungeonEvent()

    class SecretEvent(val type: SecretType, val pos: BlockPos): DungeonEvent()

    class PlayerDeathEvent(val name: String, val reason: String): DungeonEvent()

    class Score(val oldScore: Int, val score: Int): DungeonEvent()
    /** Necron's boss bar emptied (F7/M7). Posted by [com.laybalt.utils.dungeons.F7Listener]. */
    object NecronDeathEvent: DungeonEvent()

    /** The Wither King's boss bar appeared (M7 phase 5). Posted by [com.laybalt.utils.dungeons.F7Listener]. */
    object WitherKingStartEvent: DungeonEvent()

    object BossEnterEvent: DungeonEvent()
    object RunStatedEvent: DungeonEvent()
    object RunEndedEvent: DungeonEvent()
}