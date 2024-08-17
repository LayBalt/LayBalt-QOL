package com.laybalt.AutoExperiment

import com.laybalt.GUI.LBQConfig.AutoExperimentSwitch
import com.laybalt.utils.Prefix
import net.minecraft.client.Minecraft
import net.minecraft.util.ChatComponentTranslation

class AExperimentMessage {
    companion object {
        fun sendMessage() {
            val key = if (AutoExperimentSwitch) "§7AutoExperiment is now §aon" else "§7AutoExperiment is now §coff"
            val message = Prefix.getModPrefix() + key
            Minecraft.getMinecraft().thePlayer.addChatMessage(ChatComponentTranslation(message))
        }
    }
}