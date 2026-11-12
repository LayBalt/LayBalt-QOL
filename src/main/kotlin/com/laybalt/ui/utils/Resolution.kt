package com.laybalt.ui.utils

import com.laybalt.LayBaltQOL.mc
import com.laybalt.ui.text.UIFont
import com.laybalt.utils.NumbersUtils.div
import gg.essential.universal.UResolution
import gg.essential.universal.UMouse
import net.minecraft.client.gui.GuiGraphicsExtractor
import kotlin.math.min

object Resolution {
    private const val REFERANCE_WIDTH = 960f
    private const val REFERENCE_HEIGHT = 540f

    var scale = 1f
        private set

    var width = 960f
        private set

    var height = 540f
        private set

    /** LayBalt windows never draw finer than this many physical pixels per canvas unit, so small text stays legible. */
    private const val UI_MIN_PIXELS_PER_UNIT = 1.25f
    /** ...but the canvas never gets smaller than this, so the main window (min 640 x 380 plus margins) still fits. */
    private const val UI_MIN_WIDTH = 660f
    private const val UI_MIN_HEIGHT = 390f

    private fun refresh(readable: Boolean) {
        val guiWidth = UResolution.scaledWidth.toFloat()
        val guiHeight = UResolution.scaledHeight.toFloat()

        scale = min(guiWidth / REFERANCE_WIDTH, guiHeight / REFERENCE_HEIGHT)
        if (readable) {
            val guiScale = mc.window.guiScale.toFloat().coerceAtLeast(1f)
            val ceiling = min(guiWidth / UI_MIN_WIDTH, guiHeight / UI_MIN_HEIGHT)
            scale = maxOf(scale, min(UI_MIN_PIXELS_PER_UNIT / guiScale, ceiling))
        }

        width = guiWidth / scale
        height = guiHeight / scale
    }

    /**
     * Canvas for the LayBalt windows (main screen and its sub screens): the reference 960 x 540 canvas, except that in
     * small windows each unit is enlarged to at least [UI_MIN_PIXELS_PER_UNIT] physical pixels, which shrinks the
     * logical canvas (down to [UI_MIN_WIDTH] x [UI_MIN_HEIGHT]) so small text stays legible. HUD positions use [push].
     */
    fun pushUi(ctx: GuiGraphicsExtractor) = push(ctx, true)

    fun push(ctx: GuiGraphicsExtractor) = push(ctx, false)

    private fun push(ctx: GuiGraphicsExtractor, readable: Boolean) {
        refresh(readable)
        // Text measured during this canvas's layout and input handling then matches what it draws.
        UIFont.measureScale = scale * mc.window.guiScale
        ctx.pose().pushMatrix()
        ctx.pose().scale(scale, scale)
    }

    fun pop(ctx: GuiGraphicsExtractor) {
        ctx.pose().popMatrix()
    }

    fun getMouseX(vanillaX: Number) = (vanillaX / scale).toInt()
    fun getMouseY(vanillaY: Number) = (vanillaY / scale).toInt()

    fun getMouseX() = (UMouse.Raw.x / UResolution.windowWidth * width).toInt()
    fun getMouseY() = (UMouse.Raw.y / UResolution.windowHeight * height).toInt()
}