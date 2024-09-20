package com.laybalt.skyblock.AutoClicker.LeftClick;

import com.laybalt.GUI.LBQConfig;
import com.laybalt.Main;
import net.minecraft.client.Minecraft;
import net.minecraft.util.ChatComponentTranslation;

public class AClickerMessageLeft {
    public static void sendMessage() {
        String key = LBQConfig.INSTANCE.getLeftClickerSwitch() ? "§7Left AutoClicker is now §aon" : "§7Left AutoClicker is now §coff";
        String message = Main.getModPrefix() + key;
        Minecraft.getMinecraft().thePlayer.addChatMessage(new ChatComponentTranslation(message));
    }
}