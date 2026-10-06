package com.laybalt.skyblock.AutoFishing.Fishing;

import com.laybalt.GUI.LBQConfig;
import com.laybalt.util.ChatUtil;
import net.minecraft.client.Minecraft;

public class AFishMessage {
    // ChatUtil.sendMessage prepends Main.getModPrefix(), so the prefix is no longer added here.
    public static void sendMessage() {
        String key = LBQConfig.INSTANCE.getFishingSwitch() ? "§7AutoFishing is now §aon" : "§7AutoFishing is now §coff";
        ChatUtil.sendMessage(key);
    }

    public static void sendDead(String reason) {
        String message = "§7AFishing is now §coff §8[Killed: §c" + reason + "§8]";
        ChatUtil.sendMessage(message);
    }

    public static void sendWarped() {
        String message = "§7AFishing is now §coff §8[§cWarped§8]";
        ChatUtil.sendMessage(message);
    }

    public static void sendGuiOpenMessage() {
        if (Minecraft.getInstance().player == null) {
            return;
        }
        String message = "§7AFishing is now §coff §8[§cGUI Opened§8]";
        ChatUtil.sendMessage(message);
    }
}
