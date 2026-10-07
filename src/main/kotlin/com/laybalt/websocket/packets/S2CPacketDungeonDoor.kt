package com.laybalt.websocket.packets

import com.laybalt.utils.dungeons.map.core.DoorTile
import com.laybalt.utils.dungeons.map.core.DoorType
import com.laybalt.utils.dungeons.map.core.Unknown
import com.laybalt.utils.dungeons.map.handlers.DungeonScanner
import com.laybalt.websocket.WebSocketPacket

class S2CPacketDungeonDoor(val x: Int, val z: Int, val col: Int, val row: Int, val doorType: DoorType): WebSocketPacket {
    override fun handle() {
        val idx = row * 11 + col
        if (DungeonScanner.dungeonList[idx] !is Unknown) return
        DungeonScanner.dungeonList[idx] = DoorTile(x, z, doorType)
    }
}