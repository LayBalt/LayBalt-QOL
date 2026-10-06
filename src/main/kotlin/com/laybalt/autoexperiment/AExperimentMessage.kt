package com.laybalt.autoexperiment

import com.laybalt.GUI.LBQConfig.AutoExperimentSwitch
import com.laybalt.util.ChatUtil
import com.laybalt.utils.PREFIX

class AExperimentMessage {
    companion object {
        fun sendMessage() {
            val key = if (AutoExperimentSwitch) "§7AutoExperiment is now §aon" else "§7AutoExperiment is now §coff"
            val message = PREFIX.getModPrefix() + key
            ChatUtil.sendRaw(message)
        }
    }
}
