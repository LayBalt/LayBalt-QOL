package com.laybalt.skyblock.AutoClicker.LeftClick;

import com.laybalt.GUI.LBQConfig;
import com.laybalt.util.KeyBinds;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.KeyMapping;

public class AClickerKeyBindLeft {
    public static KeyMapping keyBinding;

    public AClickerKeyBindLeft() {
        // Unbound by default (was Keyboard.KEY_NONE); shown as "Left AutoClicker" in the LayBalt category.
        keyBinding = KeyBinds.registerUnbound("key.lbqmod.auto_clicker_left");
    }

    public void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (keyBinding.consumeClick()) {
                LBQConfig.INSTANCE.setLeftClickerSwitch(!LBQConfig.INSTANCE.getLeftClickerSwitch());
                AClickerMessageLeft.sendMessage();

                AClickerLeft clicker = AClickerLeft.getInstance();
                if (LBQConfig.INSTANCE.getLeftClickerSwitch()) {
                    clicker.startAutoClick();
                } else {
                    clicker.stopAutoClick();
                }
            }
        });
    }
}
