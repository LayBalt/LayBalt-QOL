package com.laybalt.util;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;

/** Helper for registering the mod key mappings under a single "LayBalt" controls category. */
public final class KeyBinds {
    /** Shown in the controls screen as "LayBalt" (see {@code key.category.lbqmod.laybalt} in en_us.json). */
    public static final KeyMapping.Category CATEGORY =
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath("lbqmod", "laybalt"));

    private KeyBinds() {
    }

    /**
     * Registers a keyboard key mapping.
     *
     * @param translationKey e.g. {@code key.lbqmod.auto_fishing} (must exist in en_us.json)
     * @param glfwKey        a {@code GLFW.GLFW_KEY_*} constant, or {@code -1} for "not bound"
     */
    public static KeyMapping register(String translationKey, int glfwKey) {
        return KeyMappingHelper.registerKeyMapping(
                new KeyMapping(translationKey, InputConstants.Type.KEYSYM, glfwKey, CATEGORY));
    }

    /** Registers a key mapping that is unbound by default. */
    public static KeyMapping registerUnbound(String translationKey) {
        return register(translationKey, -1);
    }
}
