package com.laybalt.autoexperiment

import com.laybalt.GUI.LBQConfig
import com.laybalt.autoexperiment.AExperimentMessage.Companion.sendMessage
import com.laybalt.util.KeyBinds
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.KeyMapping

class AExperimentKeyBind {
    init {
        // Unbound by default, like Keyboard.KEY_NONE in the Forge version.
        keyBinding = KeyBinds.registerUnbound("key.lbqmod.auto_experiment")
    }

    /** InputEvent.KeyInputEvent + isPressed() -> END_CLIENT_TICK + consumeClick(). */
    fun register() {
        ClientTickEvents.END_CLIENT_TICK.register { _ ->
            while (keyBinding.consumeClick()) {
                LBQConfig.AutoExperimentSwitch = !LBQConfig.AutoExperimentSwitch
                sendMessage()
            }
        }
    }

    companion object {
        lateinit var keyBinding: KeyMapping
    }
}
