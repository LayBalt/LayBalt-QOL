package com.laybalt.vamphelper

import com.laybalt.GUI.LBQConfig
import com.laybalt.util.ChatUtil

class VHelperMessage {
    companion object {
        fun sendToggleMessage() {
            val key =
                if (LBQConfig.vampireSlayerHelperEnabled) "§7VampireHelper is now §aon" else "§7VampireHelper is now §coff"
            // ChatUtil.sendMessage prepends the mod prefix (same string as PREFIX.getModPrefix()).
            ChatUtil.sendMessage(key)
        }

        fun sendMessage(message: String) {
            ChatUtil.sendMessage("§7$message")
        }

        fun sendActionBar(message: String) {
            // Old: ingameGUI.setRecordPlaying(msg, false)
            ChatUtil.sendActionBar("§6[VampireHelper] §r$message")
        }
    }
}
