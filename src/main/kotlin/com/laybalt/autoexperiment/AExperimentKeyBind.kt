package com.laybalt.autoexperiment

import com.laybalt.autoexperiment.AExperimentMessage.Companion.sendMessage
import com.laybalt.GUI.LBQConfig.AutoExperimentSwitch
import net.minecraft.client.settings.KeyBinding
import net.minecraftforge.fml.client.registry.ClientRegistry
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent
import net.minecraftforge.fml.common.gameevent.InputEvent.KeyInputEvent
import org.lwjgl.input.Keyboard

class AExperimentKeyBind {
    init {
        ClientRegistry.registerKeyBinding(keyBinding)
    }

    @SubscribeEvent
    fun onKeyInput(event: KeyInputEvent?) {
        if (keyBinding.isPressed) {
            AutoExperimentSwitch = !AutoExperimentSwitch
            sendMessage()
        }
    }

    companion object {
        var keyBinding: KeyBinding = KeyBinding("AutoExperiment", Keyboard.KEY_X, "LayBalt")
    }
}
