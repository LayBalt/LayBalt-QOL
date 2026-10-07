// Ported from Odin (https://github.com/odtheking/Odin), BSD-3-Clause, Copyright (c) 2025 odtheking
package com.laybalt.features.impl.render

import com.laybalt.config.types.DropdownSetting
import com.laybalt.config.types.SliderSetting
import com.laybalt.features.Feature
import com.laybalt.utils.PartyUtils
import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.renderer.entity.state.AvatarRenderState
import net.minecraft.world.entity.player.Player

/**
 * Client-side player scaling (Odin's "dev size" for your own player). Applied in the player renderer's scale step
 * (hook: `MixinAvatarRendererPlayerSize`); a negative Y flips the model upside down.
 */
object PlayerSize: Feature("Changes the size of the player (client-side only).") {
    private val sizeX by SliderSetting("Size X", 1f, - 1f, 3f, 0.1f)
        .withDescription("Width scale of the player model (negative values mirror it)")
    private val sizeY by SliderSetting("Size Y", 1f, - 1f, 3f, 0.1f)
        .withDescription("Height scale of the player model (negative values turn it upside down)")
    private val sizeZ by SliderSetting("Size Z", 1f, - 1f, 3f, 0.1f)
        .withDescription("Depth scale of the player model (negative values mirror it)")
    private val applyTo by DropdownSetting("Apply To", 0, listOf("Yourself", "Party", "Everyone"))
        .withDescription("Whose model is scaled: only yours (as in Odin), yours and your party members', or every real player's")

    @JvmStatic
    fun scaleHook(state: AvatarRenderState, poseStack: PoseStack) {
        if (! enabled) return
        val self = mc.player ?: return
        if (state.id != self.id) {
            if (applyTo.value == 0) return
            val other = mc.level?.getEntity(state.id) as? Player ?: return
            if (other.uuid.version() != 4) return
            if (applyTo.value == 1 && PartyUtils.members.none { it.equals(other.name.string, true) }) return
        }

        val (x, y, z) = Triple(sizeX.value, sizeY.value, sizeZ.value)
        if (y < 0) poseStack.translate(0f, y * 2, 0f)
        poseStack.scale(x, y, z)
    }
}
