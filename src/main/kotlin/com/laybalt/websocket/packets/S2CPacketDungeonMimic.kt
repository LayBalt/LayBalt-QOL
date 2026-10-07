package com.laybalt.websocket.packets

import com.laybalt.utils.dungeons.map.handlers.ScoreCalculation
import com.laybalt.websocket.WebSocketPacket

object S2CPacketDungeonMimic: WebSocketPacket {
    override fun handle() = ScoreCalculation::mimicKilled.set(true)
}