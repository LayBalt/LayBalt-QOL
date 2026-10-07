package com.laybalt.features.impl.render

import com.laybalt.config.types.SliderSetting
import com.laybalt.config.types.ToggleSetting
import com.laybalt.features.Feature

/**
 * Raises the floor of the light map instead of forcing everything to full white,
 * so light and shadow stay readable while dark areas become visible.
 *
 * @see com.laybalt.mixin.MixinLightmapFullbright
 */
object Fullbright: Feature("Brightens dark areas by raising the minimum light level of the world. At 1.0 everything is fully lit. Do not combine with the Full Bright option of Camera Helper.") {
    @JvmStatic val minBrightness by SliderSetting("Min Brightness", 0.5f, 0f, 1f, 0.05f)
        .withDescription("The lowest brightness any block can have. 0 keeps vanilla darkness, 1 makes every block fully lit")

    @JvmStatic val maxGamma by ToggleSetting("Max Gamma", true)
        .withDescription("Also applies the vanilla brightness curve at its strongest, like setting the Brightness option to Bright")

    /** Applied to the light map's ambient colour; the shader adds sky/block light on top, so no texel ends up darker than this. */
    @JvmStatic
    fun floor() = minBrightness.value.toFloat().coerceIn(0f, 1f)
}
