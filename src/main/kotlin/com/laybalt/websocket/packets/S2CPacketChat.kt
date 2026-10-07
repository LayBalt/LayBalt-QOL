package com.laybalt.websocket.packets

import com.laybalt.event.EventBus
import com.laybalt.event.impl.ChatMessageEvent
import com.laybalt.utils.ChatUtils
import com.laybalt.utils.ChatUtils.removeFormatting
import com.laybalt.utils.PartyUtils
import com.laybalt.utils.location.LocationUtils
import com.laybalt.websocket.WebSocketPacket
import net.minecraft.network.chat.Component

class S2CPacketChat(val message: String): WebSocketPacket {
    override fun handle() {
        ChatUtils.chat("§b[WS]§r $message")
        if (! LocationUtils.inDungeon) return
        val text = message.removeFormatting()
        if (PartyUtils.members.none { it.equals(text.substringBefore(":"), ignoreCase = true) }) return
        EventBus.post(ChatMessageEvent(Component.literal("Party > $text")))
    }
}