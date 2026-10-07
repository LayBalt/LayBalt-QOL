package com.laybalt.websocket.packets

import com.laybalt.utils.dungeons.map.handlers.DungeonScanner
import com.laybalt.websocket.WebSocketPacket

class S2CPacketRoomSecrets(val room: String, val secrets: Int): WebSocketPacket {
    override fun handle() {
        DungeonScanner.uniqueRooms[room]?.let {
            if (it.foundSecrets < secrets) it.foundSecrets = secrets
        }
    }
}