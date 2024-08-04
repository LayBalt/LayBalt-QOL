package com.laybalt.AutoClicker.LeftClick;

import net.minecraft.client.Minecraft;
import net.minecraft.util.ChatComponentTranslation;

public class AClickerMessageLeft {
    public static void sendMessage() {
        String key = AClickerLeft.isAutoClicking() ? "Left AutoClicker is now on" : "Left AutoClicker is now off";
        Minecraft.getMinecraft().thePlayer.addChatMessage(new ChatComponentTranslation(key));
    }
}
