package com.laybalt.AutoFishing;

import com.laybalt.GUI.LBQConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.util.ChatComponentTranslation;

public class AFishMessage {
    public static void sendMessage() {
        String key = LBQConfig.INSTANCE.getFishingSwitch() ? "AutoFishing is now on" : "AutoFishing is now off";
        Minecraft.getMinecraft().thePlayer.addChatMessage(new ChatComponentTranslation(key));
    }
}