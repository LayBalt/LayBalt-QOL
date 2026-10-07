package com.laybalt.features.impl.render

import com.laybalt.config.types.SliderSetting
import com.laybalt.config.types.ToggleSetting
import com.laybalt.features.Feature

/**
 * Absolute world field of view plus an independent field of view for the held item / hand.
 *
 * @see com.laybalt.mixin.MixinCameraFOV
 */
object FOV: Feature("Sets your field of view beyond the vanilla limits and lets the held item use its own field of view. Do not combine with the Custom FOV option of Camera Helper.", name = "FOV") {
    @JvmStatic val fov by SliderSetting("FOV", 120, 30, 179, 1)
        .withDescription("Field of view of the world in degrees. Vanilla allows 30 to 110")

    @JvmStatic val dynamicFov by ToggleSetting("Dynamic FOV")
        .withDescription("Keeps the zoom changes from sprinting, speed effects and bows on top of the custom value. Off locks the FOV to exactly the value above")

    @JvmStatic val vanillaInFluids by ToggleSetting("Vanilla In Fluids", true)
        .withDescription("Uses the normal FOV while your camera is under water or in lava")

    @JvmStatic val itemFov by ToggleSetting("Item FOV")
        .withDescription("Renders your hand and held item with their own field of view. Lower values make the item look bigger and closer").section("Held Item")

    @JvmStatic val itemFovValue by SliderSetting("Item FOV Value", 120, 30, 179, 1)
        .withDescription("Field of view used for the hand and held item. Vanilla uses 70").showIf { itemFov.value }
}
