package com.laybalt;

import com.laybalt.GUI.LBQConfig;
import com.laybalt.util.KeyBinds;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

/** Right Shift opens the LayBalt config screen (same default as the Forge version). */
public class ConfigKeyBind {
    public static KeyMapping keyBinding;

    public ConfigKeyBind() {
        keyBinding = KeyBinds.register("key.lbqmod.open_config", GLFW.GLFW_KEY_RIGHT_SHIFT);
    }

    public void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (keyBinding.consumeClick()) {
                client.gui.setScreen(LBQConfig.INSTANCE.createScreen(client.gui.screen()));
            }
        });
    }
}
