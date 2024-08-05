package com.laybalt.AutoClicker.RightClick;

import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.InputEvent;
import org.lwjgl.input.Keyboard;

public class AClickerKeyBindRight {
    public static KeyBinding keyBinding;

    public AClickerKeyBindRight() {
        keyBinding = new KeyBinding("Right AutoClicker", Keyboard.KEY_C, "LayBalt");
        ClientRegistry.registerKeyBinding(keyBinding);
    }

    @SubscribeEvent
    public void onKeyInput(InputEvent.KeyInputEvent event) {
        if (keyBinding.isPressed()) {
            AClickerRight.toggleAutoClicking();
            AClickerMessageRight.sendMessage();
        }
    }
}