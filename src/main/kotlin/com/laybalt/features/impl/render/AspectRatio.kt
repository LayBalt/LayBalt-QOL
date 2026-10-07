package com.laybalt.features.impl.render

import com.laybalt.config.types.SliderSetting
import com.laybalt.features.Feature

/**
 * Forces the width / height ratio of the world projection (stretched-resolution look).
 * The HUD and the hand keep the real window ratio.
 *
 * @see com.laybalt.mixin.MixinCameraAspectRatio
 */
object AspectRatio: Feature("Stretches or squeezes the world view by forcing a custom aspect ratio. The HUD is not affected.") {
    @JvmStatic val ratio by SliderSetting("Ratio", 1.78f, 0.1f, 5.0f, 0.01f)
        .withDescription("Width divided by height used for the world view. 1.78 is 16:9, 1.33 is 4:3; lower values stretch the world wider")

    @JvmStatic
    fun ratioValue() = ratio.value.toFloat().coerceIn(0.1f, 5f)
}
