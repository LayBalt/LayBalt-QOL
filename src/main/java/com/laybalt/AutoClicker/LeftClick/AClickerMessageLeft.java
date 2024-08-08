package com.laybalt.AutoClicker.LeftClick;

import com.laybalt.GUI.LBQConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.util.ChatComponentTranslation;

public class AClickerMessageLeft {
    public static void sendMessage() {
        String key = LBQConfig.INSTANCE.getLeftClickerSwitch() ? "Left AutoClicker is now on" : "Left AutoClicker is now off";
        Minecraft.getMinecraft().thePlayer.addChatMessage(new ChatComponentTranslation(key));
    }
}