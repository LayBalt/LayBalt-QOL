package com.laybalt.features.impl.render

import com.laybalt.config.types.ColorSetting
import com.laybalt.config.types.DropdownSetting
import com.laybalt.config.types.SliderSetting
import com.laybalt.config.types.ToggleSetting
import com.laybalt.event.impl.TickEvent
import com.laybalt.features.Feature
import com.laybalt.mixin.IOverlayTextureHitColor
import net.minecraft.client.renderer.entity.state.AvatarRenderState
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.world.entity.EntityTypes
import java.awt.Color

/**
 * Custom hurt flash.
 *
 * Vanilla tints a hurt entity through the 16x16 "Entity Color Overlay" texture: the entity shader mixes the texel
 * picked by the overlay coordinates into the model colour (`mix(overlay.rgb, color.rgb, overlay.a)`). Rows 0-7 are the
 * red hurt colour and vanilla only ever samples row 3, so this feature paints its own colour into the unused row
 * [ROW] and points matching entities at it (`MixinLivingEntityRendererHitColor`). Entities that do not match keep the
 * vanilla red, so "Only Players" / "Only Mobs" work at the same time.
 */
object HitColor: Feature("Changes the colour and strength of the flash entities show when they are hurt.") {
    private const val ROW = 6
    private const val RECOLOR = 0

    private val mode by DropdownSetting("Mode", RECOLOR, listOf("Recolor", "No Flash"))
        .withDescription("Recolor: hurt entities flash in the colour below. No Flash: hurt entities do not flash at all.")
    private val color by ColorSetting("Color", Color(255, 0, 0, 77))
        .withDescription("Colour of the hurt flash. Its alpha is how strongly the entity is tinted (vanilla is red at about 30%).")
        .showIf { mode.value == RECOLOR }
    private val rainbow by ToggleSetting("Rainbow")
        .withDescription("Cycles the flash colour through the rainbow. The alpha of the colour above still sets the strength.")
        .showIf { mode.value == RECOLOR }
    private val rainbowSpeed by SliderSetting("Rainbow Speed", 1.0, 0.1, 5.0, 0.1, "x")
        .withDescription("How fast the rainbow cycles.")
        .showIf { mode.value == RECOLOR && rainbow.value }
    private val targets by DropdownSetting("Targets", 0, listOf("All", "Only Players", "Only Mobs"))
        .withDescription("Which hurt entities use the custom flash. The others keep the vanilla red flash.")
    private val ignoreSelf by ToggleSetting("Ignore Self")
        .withDescription("Keeps the vanilla red flash on your own player (visible in third person).")

    /** ARGB currently written into [ROW], or null when the row has to be (re)written. */
    private var uploaded: Int? = null

    override fun init() {
        register<TickEvent.Start> { updateTexture() }
    }

    override fun onEnable() {
        super.onEnable()
        uploaded = null
    }

    private fun currentColor(): Color {
        val base = color.value
        if (! rainbow.value) return base
        val hue = (System.currentTimeMillis() * rainbowSpeed.value / 4000.0 % 1.0).toFloat()
        val rgb = Color.HSBtoRGB(hue, 1f, 1f)
        return Color((rgb and 0xFFFFFF) or (base.alpha shl 24), true)
    }

    /** Writes the flash colour into the overlay texture row when it changed. Runs on the client (render) thread. */
    private fun updateTexture() {
        if (mode.value != RECOLOR) return
        val c = currentColor()
        // Texture alpha is the weight of the ORIGINAL colour, so a stronger flash needs a lower alpha.
        val argb = ((255 - c.alpha).coerceIn(0, 255) shl 24) or (c.rgb and 0xFFFFFF)
        if (argb == uploaded) return
        val texture = (mc.gameRenderer?.overlayTexture() as? IOverlayTextureHitColor)?.`lbqmod$getTexture`() ?: return
        val pixels = texture.pixels ?: return
        for (u in 0 until 16) pixels.setPixel(u, ROW, argb)
        texture.upload()
        uploaded = argb
    }

    /**
     * Overlay row to use for a hurt entity: [ROW] for the custom colour, the "no red" row for No Flash,
     * or -1 to leave vanilla alone. Called from `LivingEntityRenderer.getOverlayCoords`.
     */
    @JvmStatic
    fun overlayRow(state: LivingEntityRenderState): Int {
        if (! enabled || ! state.hasRedOverlay) return - 1
        val isPlayer = state.entityType == EntityTypes.PLAYER
        when (targets.value) {
            1 -> if (! isPlayer) return - 1
            2 -> if (isPlayer) return - 1
        }
        if (ignoreSelf.value && state is AvatarRenderState && state.id == mc.player?.id) return - 1
        if (mode.value != RECOLOR) return OverlayTexture.v(false)
        return if (uploaded != null) ROW else - 1
    }
}
