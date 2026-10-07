package com.laybalt.features.impl.misc

import com.laybalt.config.types.ColorSetting
import com.laybalt.config.types.DropdownSetting
import com.laybalt.config.types.SliderSetting
import com.laybalt.config.types.ToggleSetting
import com.laybalt.features.Feature
import com.laybalt.utils.ColorUtils.withAlpha
import com.laybalt.utils.Utils
import com.laybalt.utils.equalsOneOf
import com.laybalt.utils.items.EtherwarpHelper
import com.laybalt.utils.render.world.Render3D.renderBlock
import com.laybalt.utils.render.world.RenderContext
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents

object BlockOverlay: Feature() {
    private val mode by DropdownSetting("Mode", 2, listOf("Outline", "Fill", "Filled Outline"))
    private val fillColor by ColorSetting("Fill Color", Utils.favoriteColor.withAlpha(50)).hideIf { mode.value == 0 }
    private val outlineColor by ColorSetting("Outline Color", Utils.favoriteColor, false).hideIf { mode.value == 1 }
    private val lineWidth by SliderSetting("Line Width", 2.5, 1, 10, 0.1).hideIf { mode.value == 1 }
    private val phase by ToggleSetting("Phase")
    private val hideDuringEtherwarp by ToggleSetting("Hide with Etherwarp")

    override fun init() {
        LevelRenderEvents.BEFORE_BLOCK_OUTLINE.register { context, blockOutlineContext ->
            if (! enabled) return@register true
            if (mc.gui.hud.isHidden) return@register true
            if (hideDuringEtherwarp.value && shouldHide()) return@register false

            RenderContext(context).renderBlock(
                blockOutlineContext.pos,
                outlineColor.value,
                fillColor.value,
                mode.value.equalsOneOf(0, 2),
                mode.value.equalsOneOf(1, 2),
                phase = phase.value,
                lineWidth.value
            )

            false
        }
    }

    private fun shouldHide() = player.isCrouching && EtherwarpHelper.getEtherwarpDistance(player.mainHandItem) != null

}
