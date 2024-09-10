package com.laybalt.AutoFishing.Fishing;

import com.laybalt.GUI.LBQConfig;
import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.InputEvent;
import org.lwjgl.input.Keyboard;

public class AFishKeyBind {
    public static KeyBinding keyBinding;

    public AFishKeyBind() {
        keyBinding = new KeyBinding("AutoFishing", Keyboard.KEY_F, "LayBalt");
        ClientRegistry.registerKeyBinding(keyBinding);
    }

    @SubscribeEvent
    public void onKeyInput(InputEvent.KeyInputEvent event) {
        if (keyBinding.isPressed()) {
            LBQConfig.INSTANCE.setFishingSwitch(!LBQConfig.INSTANCE.getFishingSwitch());
            AFishMessage.sendMessage();
        }
    }
}