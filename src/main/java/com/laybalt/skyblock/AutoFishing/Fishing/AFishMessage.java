package com.laybalt.skyblock.AutoFishing.Fishing;

import com.laybalt.GUI.LBQConfig;
import com.laybalt.Main;
import net.minecraft.client.Minecraft;
import net.minecraft.util.ChatComponentTranslation;

public class AFishMessage {
    public static void sendMessage() {
        String key = LBQConfig.INSTANCE.getFishingSwitch() ? "§7AutoFishing is now §aon" : "§7AutoFishing is now §coff";
        String message = Main.getModPrefix() + key;
        Minecraft.getMinecraft().thePlayer.addChatMessage(new ChatComponentTranslation(message));
    }

    public static void sendDead(String reason) {
        String message = "§7AFishing is now §coff §8[Killed: §c" + reason + "§8]";
        String key = Main.getModPrefix() + message;
        Minecraft.getMinecraft().thePlayer.addChatMessage(new ChatComponentTranslation(key));
    }

    public static void sendWarped() {
        String message = "§7AFishing is now §coff §8[§cWarped§8]";
        String key = Main.getModPrefix() + message;
        Minecraft.getMinecraft().thePlayer.addChatMessage(new ChatComponentTranslation(key));
    }

    public static void sendGuiOpenMessage() {
        if (Minecraft.getMinecraft().thePlayer == null) {
            return;
        }
        String message = "§7AFishing is now §coff §8[§cGUI Opened§8]";
        String key = Main.getModPrefix() + message;
        Minecraft.getMinecraft().thePlayer.addChatMessage(new ChatComponentTranslation(key));
    }
}