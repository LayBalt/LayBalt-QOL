package com.laybalt.vamphelper

import com.laybalt.GUI.LBQConfig
import net.minecraft.client.settings.KeyBinding
import net.minecraftforge.fml.client.registry.ClientRegistry
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent
import net.minecraftforge.fml.common.gameevent.InputEvent.KeyInputEvent
import org.lwjgl.input.Keyboard

class VHelperKeyBind {
    init {
        ClientRegistry.registerKeyBinding(keyBinding)
    }

    @SubscribeEvent
    fun onKeyInput(event: KeyInputEvent?) {
        if (keyBinding.isPressed) {
            LBQConfig.vampireSlayerHelperEnabled = !LBQConfig.vampireSlayerHelperEnabled
            VHelperMessage.sendToggleMessage()
        }
    }

    companion object {
        var keyBinding: KeyBinding = KeyBinding("VampireHelper", Keyboard.KEY_NONE, "LayBalt")
    }
}