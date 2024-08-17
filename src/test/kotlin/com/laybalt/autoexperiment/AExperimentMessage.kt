package com.laybalt.autoexperiment

import com.laybalt.GUI.LBQConfig.AutoExperimentSwitch
import com.laybalt.utils.PREFIX
import net.minecraft.client.Minecraft
import net.minecraft.util.ChatComponentTranslation

class AExperimentMessage {
    companion object {
        fun sendMessage() {
            val key = if (AutoExperimentSwitch) "§7AutoExperiment is now §aon" else "§7AutoExperiment is now §coff"
            val message = PREFIX.getModPrefix() + key
            Minecraft.getMinecraft().thePlayer.addChatMessage(ChatComponentTranslation(message))
        }
    }
}