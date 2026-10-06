package com.laybalt.skyblock.AutoMelody;

import com.laybalt.GUI.LBQConfig;
import com.laybalt.util.ChatUtil;

public class AMelodyMessage {
    public static void sendMessage() {
        String key = LBQConfig.INSTANCE.getMelodySwitch() ? "§7AutoMelody is now §aon" : "§7AutoMelody is now §coff";
        // ChatUtil adds the mod prefix and sends on the client thread (replaces thePlayer.addChatMessage).
        ChatUtil.sendMessage(key);
    }
}
