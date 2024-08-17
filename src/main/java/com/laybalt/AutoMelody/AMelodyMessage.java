package com.laybalt.AutoMelody;

import com.laybalt.GUI.LBQConfig;
import com.laybalt.Main;
import net.minecraft.client.Minecraft;
import net.minecraft.util.ChatComponentTranslation;

public class AMelodyMessage {
    public static void sendMessage() {
        String key = LBQConfig.INSTANCE.getMelodySwitch() ? "§7AutoMelody is now §aon" : "§7AutoMelody is now §coff";
        String message = Main.getModPrefix() + key;
        Minecraft.getMinecraft().thePlayer.addChatMessage(new ChatComponentTranslation(message));
    }
}