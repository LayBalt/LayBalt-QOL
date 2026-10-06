package com.laybalt.skyblock.AutoMelody;

import com.laybalt.GUI.LBQConfig;
import com.laybalt.util.KeyBinds;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.KeyMapping;

public class AMelodyKeyBind {
    public static KeyMapping keyBinding;

    public AMelodyKeyBind() {
        // Keyboard.KEY_NONE -> unbound by default; category "LayBalt" is handled by KeyBinds.
        keyBinding = KeyBinds.registerUnbound("key.lbqmod.auto_melody");
    }

    /** Fabric replacement for the Forge {@code InputEvent.KeyInputEvent} handler. */
    public void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (keyBinding.consumeClick()) {
                LBQConfig.INSTANCE.setMelodySwitch(!LBQConfig.INSTANCE.getMelodySwitch());
                AMelodyMessage.sendMessage();
            }
        });
    }
}
