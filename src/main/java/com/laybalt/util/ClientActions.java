package com.laybalt.util;

import net.minecraft.client.Minecraft;

/**
 * Simulated input helpers.
 *
 * <p>The 1.8.9 version invoked {@code Minecraft.clickMouse()} / {@code rightClickMouse()} through
 * reflection, often from timer threads. Here the (access-widened) {@code startAttack()} /
 * {@code startUseItem()} methods are called directly and always on the client thread.
 */
public final class ClientActions {
    private ClientActions() {
    }

    /** Runs {@code task} on the Minecraft client thread (immediately if already on it). */
    public static void runOnClientThread(Runnable task) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.isSameThread()) {
            task.run();
        } else {
            mc.execute(task);
        }
    }

    /** Performs one left click (attack / mine), exactly like pressing the attack key once. */
    public static void leftClick() {
        runOnClientThread(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null && mc.level != null && mc.gameMode != null && mc.gui.screen() == null) {
                mc.startAttack();
            }
        });
    }

    /** Performs one right click (use item / interact), exactly like pressing the use key once. */
    public static void rightClick() {
        runOnClientThread(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null && mc.level != null && mc.gameMode != null && mc.gui.screen() == null) {
                mc.startUseItem();
            }
        });
    }

    /** @return true when a GUI (chest, inventory, chat, ...) is currently open. */
    public static boolean isScreenOpen() {
        return Minecraft.getInstance().gui.screen() != null;
    }
}
