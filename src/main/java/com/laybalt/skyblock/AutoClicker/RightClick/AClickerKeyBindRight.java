package com.laybalt.skyblock.AutoClicker.RightClick;

import com.laybalt.GUI.LBQConfig;
import com.laybalt.util.KeyBinds;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.KeyMapping;

public class AClickerKeyBindRight {
    public static KeyMapping keyBinding;

    public AClickerKeyBindRight() {
        // Unbound by default (was Keyboard.KEY_NONE); shown as "Right AutoClicker" in the LayBalt category.
        keyBinding = KeyBinds.registerUnbound("key.lbqmod.auto_clicker_right");
    }

    public void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (keyBinding.consumeClick()) {
                LBQConfig.INSTANCE.setRightClickerSwitch(!LBQConfig.INSTANCE.getRightClickerSwitch());
                // save() replaces markDirty() + writeData() of the Vigilance config.
                LBQConfig.INSTANCE.save();
                AClickerMessageRight.sendMessage();

                AClickerRight clicker = AClickerRight.getInstance();
                if (LBQConfig.INSTANCE.getRightClickerSwitch()) {
                    clicker.startAutoClick();
                } else {
                    clicker.stopAutoClick();
                }
            }
        });
    }
}
