package com.laybalt.AutoExperiments

import com.laybalt.GUI.LBQConfig.AutoExperimentSwitch
import net.minecraft.client.Minecraft
import net.minecraft.util.ChatComponentTranslation

class AExperimentMessage {
    companion object {
        fun sendMessage() {
            val key = if (AutoExperimentSwitch) "AutoExperiment is now on" else "AutoExperiment is now off"
            Minecraft.getMinecraft().thePlayer.addChatMessage(ChatComponentTranslation(key))
        }
    }
}
