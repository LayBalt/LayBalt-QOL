package com.laybalt.AutoClicker.RightClick;

import com.laybalt.GUI.LBQConfig;
import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.InputEvent;
import org.lwjgl.input.Keyboard;

public class AClickerKeyBindRight {
    public static KeyBinding keyBinding;

    public AClickerKeyBindRight() {
        keyBinding = new KeyBinding("Right AutoClicker", Keyboard.KEY_J, "LayBalt");
        ClientRegistry.registerKeyBinding(keyBinding);
    }

    @SubscribeEvent
    public void onKeyInput(InputEvent.KeyInputEvent event) {
        if (keyBinding.isPressed()) {
            LBQConfig.INSTANCE.setRightClickerSwitch(!LBQConfig.INSTANCE.getRightClickerSwitch());
            LBQConfig.INSTANCE.markDirty();
            LBQConfig.INSTANCE.writeData();
            AClickerMessageRight.sendMessage();

            AClickerRight clicker = AClickerRight.getInstance();
            if (LBQConfig.INSTANCE.getRightClickerSwitch()) {
                clicker.startAutoClick();
            } else {
                clicker.stopAutoClick();
            }
        }
    }
}