package com.laybalt.recorder

import com.laybalt.event.EventBus
import com.laybalt.event.impl.ChatMessageEvent
import com.laybalt.event.impl.ContainerEvent
import com.laybalt.event.impl.MessageSentEvent
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import net.minecraft.world.inventory.ContainerInput

/**
 * What the recorder's mixins post: the same events LayBalt QOL's own mixins post at the same points. Nothing is
 * cancelled or changed: the recorder only watches.
 */
object RecorderHooks {
    /** A system chat line from the server (action bar lines excluded), as it arrives. */
    @JvmStatic
    fun systemChat(content: Component) {
        EventBus.post(ChatMessageEvent(content))
    }

    /** A chat message or a command ([command], without its slash) you send. */
    @JvmStatic
    fun messageSent(message: String, command: Boolean) {
        EventBus.post(MessageSentEvent(message, command))
    }

    /** A click on a slot of an open container screen. */
    @JvmStatic
    fun slotClick(screen: Screen, slotId: Int, button: Int, input: ContainerInput) {
        EventBus.post(ContainerEvent.SlotClick(screen, slotId, button, input))
    }
}
