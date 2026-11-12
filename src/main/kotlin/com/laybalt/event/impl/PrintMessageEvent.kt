package com.laybalt.event.impl

import com.laybalt.event.Event
import com.laybalt.utils.ChatUtils.formattedText
import com.laybalt.utils.ChatUtils.unformattedText
import net.minecraft.network.chat.Component

/**
 * Posted when a server system message is about to be printed to the chat (NoammAddons 29ed9775).
 * Cancelling it hides the printed line only; [ChatMessageEvent] listeners have already seen the message.
 */
class PrintMessageEvent(val component: Component): Event(cancelable = true) {
    val formattedText by lazy { component.formattedText }
    val unformattedText by lazy { component.unformattedText }
}
