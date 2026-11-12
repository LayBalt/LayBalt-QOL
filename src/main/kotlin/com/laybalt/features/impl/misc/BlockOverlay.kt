package com.laybalt.features.impl.misc

import com.laybalt.config.types.ColorSetting
import com.laybalt.config.types.DropdownSetting
import com.laybalt.config.types.SliderSetting
import com.laybalt.config.types.ToggleSetting
import com.laybalt.features.Feature
import com.laybalt.features.impl.render.WorldBoxKit
import com.laybalt.features.impl.render.WorldBoxKit.drawBox
import com.laybalt.utils.ColorUtils.withAlpha
import com.laybalt.utils.Utils
import com.laybalt.utils.equalsOneOf
import com.laybalt.utils.items.ItemUtils.customData
import com.laybalt.utils.items.ItemUtils.skyblockId
import com.laybalt.utils.render.world.RenderContext
import net.minecraft.client.renderer.state.level.BlockOutlineRenderState
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.AABB

/**
 * Replaces the vanilla block selection outline with a configurable box.
 *
 * NoammAddons 0c926c3b (Lunar Client compatibility): [com.laybalt.mixin.MixinLevelRenderer] cancels the vanilla outline
 * at the head of LevelRenderer.submitBlockOutline and calls [renderOverlay] there with the outline render state vanilla
 * extracted for this frame (this used to be Fabric's BEFORE_BLOCK_OUTLINE plus a RenderWorldEvent listener), so the box
 * only shows when vanilla would show an outline and never lags a frame behind.
 */
object BlockOverlay: Feature("Replaces the block selection outline with your own outline, fill, colours and animation.") {
    private val mode by DropdownSetting("Mode", 2, listOf("Outline", "Fill", "Filled Outline"))
        .withDescription("Draw only the outline, only a filled box, or both.")
    private val outlineStyle by DropdownSetting("Outline Style", 0, WorldBoxKit.OUTLINE_STYLES)
        .withDescription("Full draws every edge, Corners draws only short brackets at the eight corners.").hideIf { mode.value == 1 }
    private val cornerLength by SliderSetting("Corner Length", 25, 10, 50, 1, "%")
        .withDescription("Length of each corner bracket as a share of the edge it sits on.").showIf { mode.value != 1 && outlineStyle.value == 1 }
    private val fillColor by ColorSetting("Fill Color", Utils.favoriteColor.withAlpha(50))
        .withDescription("Colour and transparency of the filled box.").hideIf { mode.value == 0 }
    private val outlineColor by ColorSetting("Outline Color", Utils.favoriteColor, false)
        .withDescription("Colour of the outline.").hideIf { mode.value == 1 }
    private val lineWidth by SliderSetting("Line Width", 2.5, 1, 10, 0.1)
        .withDescription("Thickness of the outline.").hideIf { mode.value == 1 }
    private val colorMode by DropdownSetting("Color Mode", 0, WorldBoxKit.COLOR_MODES)
        .withDescription("Static uses your colours as they are, Rainbow cycles the hue, Breathing pulses the transparency.")
    private val animationSpeed by SliderSetting("Animation Speed", 0.5, 0.1, 3.0, 0.1, "/s")
        .withDescription("How many colour cycles or pulses happen per second.").showIf { colorMode.value != 0 }
    private val phase by ToggleSetting("See Through")
        .withDescription("Draws the overlay through other blocks instead of letting them cover it.").jsonName("Phase")
    private val fullBlock by ToggleSetting("Full Block")
        .withDescription("Always draws a full 1x1x1 cube instead of following the block's shape (slabs, stairs, flowers).")
    private val expand by SliderSetting("Expand", 0.0, 0.0, 0.2, 0.01, " blocks")
        .withDescription("Grows the box outward on every side.")
    private val hideDuringEtherwarp by ToggleSetting("Hide with Etherwarp")
        .withDescription("Hides the overlay while you sneak holding an item that can etherwarp, so it does not cover etherwarp helpers.")

    /**
     * Called by [com.laybalt.mixin.MixinLevelRenderer] at the head of LevelRenderer.submitBlockOutline while the feature
     * is enabled. Returns true when the vanilla outline must be cancelled (false leaves it alone, e.g. with F1).
     */
    @JvmStatic
    fun renderOverlay(ctx: RenderContext, state: BlockOutlineRenderState): Boolean {
        if (mc.gui.hud.isHidden) return false
        if (hideDuringEtherwarp.value && shouldHide()) return true

        val pos = state.pos()
        val shape = state.shape()
        val local = if (fullBlock.value || shape.isEmpty) AABB(0.0, 0.0, 0.0, 1.0, 1.0, 1.0) else shape.bounds()
        val box = local.move(pos).inflate(expand.value)

        val speed = animationSpeed.value.toFloat()
        ctx.drawBox(
            box,
            WorldBoxKit.animated(outlineColor.value, colorMode.value, speed),
            WorldBoxKit.animated(fillColor.value, colorMode.value, speed, 0.1f),
            outline = mode.value.equalsOneOf(0, 2),
            fill = mode.value.equalsOneOf(1, 2),
            phase = phase.value,
            lineWidth = lineWidth.value.toFloat(),
            corners = outlineStyle.value == 1,
            cornerFraction = cornerLength.value / 100f
        )
        return true
    }

    private fun shouldHide(): Boolean {
        val player = mc.player ?: return false
        return player.isCrouching && canEtherwarp(player.mainHandItem)
    }

    /** Aspect of the Void / End with Ethermerge, or an Etherwarp Conduit. */
    private fun canEtherwarp(stack: ItemStack): Boolean {
        val id = stack.skyblockId
        if (id == "ETHERWARP_CONDUIT") return true
        if (! id.equalsOneOf("ASPECT_OF_THE_VOID", "ASPECT_OF_THE_END")) return false
        return stack.customData.getByte("ethermerge").orElse(0) == 1.toByte()
    }
}
