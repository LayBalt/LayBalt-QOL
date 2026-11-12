package com.laybalt.event.impl

import com.laybalt.event.Event
import com.laybalt.utils.ChatUtils.formattedText
import com.laybalt.utils.ChatUtils.unformattedText
import net.minecraft.network.chat.Component

class ChatMessageEvent(val component: Component): Event(cancelable = true) {
    val formattedText by lazy { component.formattedText }
    val unformattedText by lazy { component.unformattedText }
}
