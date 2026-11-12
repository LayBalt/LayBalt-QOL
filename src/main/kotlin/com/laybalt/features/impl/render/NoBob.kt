package com.laybalt.features.impl.render

import com.laybalt.config.types.DropdownSetting
import com.laybalt.config.types.SliderSetting
import com.laybalt.config.types.ToggleSetting
import com.laybalt.features.Feature
import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.renderer.state.level.CameraRenderState
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos

/**
 * Replaces the vanilla walking bob (sway + roll + pitch) with either a small vertical bounce or nothing.
 *
 * @see com.laybalt.mixin.MixinGameRendererNoBob
 */
object NoBob: Feature("Removes the side-to-side sway and tilt of the view while walking, keeping an optional small vertical bounce.") {
    private val mode by DropdownSetting("Mode", 0, listOf("Vertical Only", "Off"))
        .withDescription("Vertical Only keeps a gentle up and down bounce without any sway or tilt. Off removes view bobbing completely")

    private val strength by SliderSetting("Bounce Strength", 0.5f, 0.05f, 1f, 0.05f)
        .withDescription("How strong the vertical bounce is compared to vanilla").showIf { mode.value == 0 }

    @JvmStatic val affectHand by ToggleSetting("Affect Hand", true)
        .withDescription("Also applies to the bobbing of your hand and held item")

    /**
     * Called instead of vanilla `GameRenderer.bobView` while the feature is on.
     * Mirrors the vanilla vertical term, -|cos(walk * PI) * bob|, without the horizontal sway and the rotations.
     */
    @JvmStatic
    fun applyBob(state: CameraRenderState, poseStack: PoseStack) {
        if (mode.value != 0) return
        val entity = state.entityRenderState
        if (! entity.isPlayer) return

        val walk = entity.backwardsInterpolatedWalkDistance
        val bounce = abs(cos(walk * PI).toFloat() * entity.bob) * strength.value.toFloat()
        poseStack.translate(0f, - bounce, 0f)
    }
}
