package com.laybalt.AutoClicker.RightClick;

import net.minecraft.client.Minecraft;
import net.minecraft.util.ChatComponentTranslation;

public class AClickerMessageRight {
    public static void sendMessage() {
        String key = AClickerRight.isAutoClicking() ? "Right AutoClicker is now on" : "Right AutoClicker is now off";
        Minecraft.getMinecraft().thePlayer.addChatMessage(new ChatComponentTranslation(key));
    }
}
