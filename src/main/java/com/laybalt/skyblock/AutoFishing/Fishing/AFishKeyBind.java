package com.laybalt.skyblock.AutoFishing.Fishing;

import com.laybalt.GUI.LBQConfig;
import com.laybalt.util.KeyBinds;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

public class AFishKeyBind {
    public static KeyMapping keyBinding;

    public AFishKeyBind() {
        keyBinding = KeyBinds.register("key.lbqmod.auto_fishing", GLFW.GLFW_KEY_F);
    }

    public void register() {
        // InputEvent.KeyInputEvent + isPressed() -> end-of-tick consumeClick() loop.
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (keyBinding.consumeClick()) {
                LBQConfig.INSTANCE.setFishingSwitch(!LBQConfig.INSTANCE.getFishingSwitch());
                AFishMessage.sendMessage();
            }
        });
    }
}
