package com.laybalt.AutoMelody;

import com.laybalt.GUI.LBQConfig;
import com.laybalt.skyblock.AutoMelody.AMelodyMessage;
import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.InputEvent;
import org.lwjgl.input.Keyboard;

public class AMelodyKeyBind {
    public static KeyBinding keyBinding;

    public AMelodyKeyBind() {
        keyBinding = new KeyBinding("AutoMelody", Keyboard.KEY_M, "LayBalt");
        ClientRegistry.registerKeyBinding(keyBinding);
    }

    @SubscribeEvent
    public void onKeyInput(InputEvent.KeyInputEvent event) {
        if (keyBinding.isPressed()) {
            LBQConfig.INSTANCE.setMelodySwitch(!LBQConfig.INSTANCE.getMelodySwitch());
            AMelodyMessage.sendMessage();
        }
    }
}
