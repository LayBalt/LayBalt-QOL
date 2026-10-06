package com.laybalt.util;

import com.laybalt.Main;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;

/** Client-side chat / action bar helpers. All methods are safe to call from any thread. */
public final class ChatUtil {
    private ChatUtil() {
    }

    /** Sends {@code text} (already containing § colour codes) to the local chat, prefixed with the mod prefix. */
    public static void sendMessage(String text) {
        sendRaw(Main.getModPrefix() + text);
    }

    /** Sends {@code text} to the local chat without any prefix. */
    public static void sendRaw(String text) {
        ClientActions.runOnClientThread(() -> {
            LocalPlayer player = Minecraft.getInstance().player;
            if (player != null) {
                player.sendSystemMessage(Component.literal(text));
            }
        });
    }

    /** Shows {@code text} in the action bar (above the hotbar). */
    public static void sendActionBar(String text) {
        ClientActions.runOnClientThread(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.gui != null && mc.player != null) {
                mc.gui.hud.setOverlayMessage(Component.literal(text), false);
            }
        });
    }
}
