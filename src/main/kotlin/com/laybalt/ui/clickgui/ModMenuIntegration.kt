package com.laybalt.ui.clickgui

import com.terraformersmc.modmenu.api.ConfigScreenFactory
import com.terraformersmc.modmenu.api.ModMenuApi

/**
 * Mod Menu "Config" button: opens the LayBalt screen and returns to Mod Menu when it is closed.
 * @see resources/fabric.mod.json5
 */
@Suppress("unused")
class ModMenuIntegration: ModMenuApi {
    override fun getModConfigScreenFactory(): ConfigScreenFactory<*> {
        return ConfigScreenFactory { parent -> LayBaltScreen(parent) }
    }
}
