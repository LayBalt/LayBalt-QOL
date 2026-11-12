package com.laybalt.features.impl.visual

import com.laybalt.config.types.SliderSetting
import com.laybalt.config.types.ToggleSetting
import com.laybalt.features.Feature
import com.laybalt.utils.ColorUtils.withAlpha
import gg.essential.universal.UResolution
import net.minecraft.client.gui.GuiGraphicsExtractor
import java.awt.Color

/**
 * @see com.laybalt.mixin.MixinGui.onRenderHudPre
 * @see com.laybalt.mixin.MixinGui.onRenderHudPost
 */
object DarkMode: Feature("Darkens the screen") {
    private val opacity by SliderSetting("Opacity", 25, 1, 80, 1, "%").withDescription("The strength of the dark tint.")

    @JvmStatic
    val tintHud by ToggleSetting("Tint HUD").withDescription("Should the dark tint also apply to HUD elements?")

    @JvmStatic
    fun drawOverlay(ctx: GuiGraphicsExtractor) {
        if (enabled) ctx.fill(
            0, 0,
            UResolution.scaledWidth,
            UResolution.scaledHeight,
            Color.BLACK.withAlpha(opacity.value / 100f).rgb
        )
    }
}