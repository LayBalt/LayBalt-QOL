package com.laybalt.AutoMelody;

import com.laybalt.AutoFishing.AFish;
import com.laybalt.AutoFishing.AFishMessage;
import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.InputEvent;
import org.lwjgl.input.Keyboard;

public class AMelodyKeyBind {
    public static KeyBinding keyBinding;

    public AMelodyKeyBind() {
        keyBinding = new KeyBinding("AutoMelody", Keyboard.KEY_P, "LayBalt");
        ClientRegistry.registerKeyBinding(keyBinding);
    }

    @SubscribeEvent
    public void onKeyInput(InputEvent.KeyInputEvent event) {
        if (keyBinding.isPressed()) {
            AMelody.toggleAutoMelody();
            AMelodyMessage.sendMessage();
        }
    }
}
