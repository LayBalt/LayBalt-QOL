package com.laybalt.skyblock.AutoClicker.LeftClick;

import com.laybalt.GUI.LBQConfig;
import com.laybalt.util.ChatUtil;

public class AClickerMessageLeft {
    public static void sendMessage() {
        String key = LBQConfig.INSTANCE.getLeftClickerSwitch() ? "§7Left AutoClicker is now §aon" : "§7Left AutoClicker is now §coff";
        // ChatUtil adds the mod prefix and is null-safe when no player is present.
        ChatUtil.sendMessage(key);
    }
}
