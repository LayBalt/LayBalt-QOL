package com.laybalt.skyblock.AutoClicker.RightClick;

import com.laybalt.GUI.LBQConfig;
import com.laybalt.util.ChatUtil;

public class AClickerMessageRight {
    public static void sendMessage() {
        String key = LBQConfig.INSTANCE.getRightClickerSwitch() ? "§7Right AutoClicker is now §aon" : "§7Right AutoClicker is now §coff";
        // ChatUtil adds the mod prefix and is null-safe when no player is present.
        ChatUtil.sendMessage(key);
    }
}
