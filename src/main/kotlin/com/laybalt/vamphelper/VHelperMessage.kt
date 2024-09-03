package com.laybalt.vamphelper

import com.laybalt.GUI.LBQConfig
import com.laybalt.utils.PREFIX
import net.minecraft.client.Minecraft
import net.minecraft.util.ChatComponentTranslation

class VHelperMessage {
    companion object {
        fun sendToggleMessage() {
            val key =
                if (LBQConfig.vampireSlayerHelperEnabled) "§7VampireHelper is now §aon" else "§7VampireHelper is now §coff"
            val message = PREFIX.getModPrefix() + key
            Minecraft.getMinecraft().thePlayer.addChatMessage(ChatComponentTranslation(message))
        }

        fun sendMessage(message: String) {
            val formattedMessage = PREFIX.getModPrefix() + "§7$message"
            Minecraft.getMinecraft().thePlayer.addChatMessage(ChatComponentTranslation(formattedMessage))
        }

        fun sendActionBar(message: String) {
            Minecraft.getMinecraft().ingameGUI.setRecordPlaying("§6[VampireHelper] §r$message", false)
        }
    }
}