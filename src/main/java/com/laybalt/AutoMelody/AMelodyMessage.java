package com.laybalt.AutoMelody;

import net.minecraft.client.Minecraft;
import net.minecraft.util.ChatComponentTranslation;

public class AMelodyMessage {
    public static void sendMessage() {
        String key = AMelody.isAutoMelody() ? "AutoMelody is now on" : "AutoMelody is now off";
        Minecraft.getMinecraft().thePlayer.addChatMessage(new ChatComponentTranslation(key));
    }
}
