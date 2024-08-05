package laybalt.AutoFishing;

import net.minecraft.client.Minecraft;
import net.minecraft.util.ChatComponentTranslation;

public class AFishMessage {
    public static void sendMessage() {
        String key = AFish.isAutoFishing() ? "AutoFishing is now on" : "AutoFishing is now off";
        Minecraft.getMinecraft().thePlayer.addChatMessage(new ChatComponentTranslation(key));
    }
}