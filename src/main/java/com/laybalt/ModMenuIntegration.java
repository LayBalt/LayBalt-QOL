package com.laybalt;

import com.laybalt.GUI.LBQConfig;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/** Adds a "Config" button for the mod in Mod Menu (optional dependency). */
public class ModMenuIntegration implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return LBQConfig::createScreen; // createScreen is @JvmStatic, so the static reference is required
    }
}
