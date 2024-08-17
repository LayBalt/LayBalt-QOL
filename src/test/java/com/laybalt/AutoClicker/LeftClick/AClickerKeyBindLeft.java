package com.laybalt.AutoClicker.LeftClick;//package com.laybalt.AutoClicker.LeftClick;
//
//import com.laybalt.GUI.LBQConfig;
//import net.minecraft.client.settings.KeyBinding;
//import net.minecraftforge.fml.client.registry.ClientRegistry;
//import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
//import net.minecraftforge.fml.common.gameevent.InputEvent;
//import org.lwjgl.input.Keyboard;
//
//public class AClickerKeyBindLeft {
//    public static KeyBinding keyBinding;
//
//    public AClickerKeyBindLeft() {
//        keyBinding = new KeyBinding("Left AutoClicker", Keyboard.KEY_C, "LayBalt");
//        ClientRegistry.registerKeyBinding(keyBinding);
//    }
//
//    @SubscribeEvent
//    public void onKeyInput(InputEvent.KeyInputEvent event) {
//        if (keyBinding.isPressed()) {
//            LBQConfig.INSTANCE.setLeftClickerSwitch(!LBQConfig.INSTANCE.getLeftClickerSwitch());
//            AClickerMessageLeft.sendMessage();
//
//            AClickerLeft clicker = AClickerLeft.getInstance();
//            if (LBQConfig.INSTANCE.getLeftClickerSwitch()) {
//                clicker.startAutoClick();
//            } else {
//                clicker.stopAutoClick();
//            }
//        }
//    }
//}