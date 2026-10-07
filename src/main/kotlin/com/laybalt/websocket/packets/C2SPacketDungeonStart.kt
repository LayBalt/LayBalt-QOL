package com.laybalt.websocket.packets

import com.laybalt.websocket.WebSocketPacket

class C2SPacketDungeonStart(
    val serverId: String,
    val floor: String,
    val members: List<String>,
    val entrance: Pair<Int, Int>
): WebSocketPacket