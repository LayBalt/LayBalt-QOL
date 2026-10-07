package com.laybalt.websocket.packets

import com.laybalt.utils.ChatUtils
import com.laybalt.websocket.WebSocketPacket

class S2CPacketSocketInfo(var connectedUsers: Int, var usersInLobby: Int, var lobby: String = ""): WebSocketPacket {
    override fun handle() {
        ChatUtils.chat("§b§m--------------------------------")
        ChatUtils.chat("§6§lWebSocket Stats")
        ChatUtils.chat("")
        ChatUtils.chat(" §fTotal Online: §a$connectedUsers")
        ChatUtils.chat(" §fUsers in your World: §a$usersInLobby")
        ChatUtils.chat(" §fCurrent Hash: §7$lobby")
        ChatUtils.chat("§b§m--------------------------------")
    }
}