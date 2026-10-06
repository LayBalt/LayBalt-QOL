package com.laybalt.vamphelper

import com.laybalt.GUI.LBQConfig
import com.laybalt.util.KeyBinds
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.KeyMapping

/** Unbound by default; toggles the Vampire Slayer helper (same as the Forge version). */
class VHelperKeyBind {
    init {
        keyBinding = KeyBinds.registerUnbound("key.lbqmod.vampire_helper")
    }

    fun register() {
        ClientTickEvents.END_CLIENT_TICK.register { _ ->
            while (keyBinding.consumeClick()) {
                LBQConfig.vampireSlayerHelperEnabled = !LBQConfig.vampireSlayerHelperEnabled
                VHelperMessage.sendToggleMessage()
            }
        }
    }

    companion object {
        lateinit var keyBinding: KeyMapping
    }
}
