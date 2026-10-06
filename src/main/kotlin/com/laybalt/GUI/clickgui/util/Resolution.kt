package com.laybalt.GUI.clickgui.util

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import kotlin.math.min

/**
 * Virtual 960x540 canvas, so the GUI keeps the same proportions at every GUI scale.
 * Everything inside [push]/[pop] is laid out in these virtual units.
 */
object Resolution {
    private const val REFERENCE_WIDTH = 960f
    private const val REFERENCE_HEIGHT = 540f

    var scale = 1f
        private set
    var width = REFERENCE_WIDTH
        private set
    var height = REFERENCE_HEIGHT
        private set

    private fun refresh() {
        val window = Minecraft.getInstance().window
        val guiWidth = window.guiScaledWidth.toFloat()
        val guiHeight = window.guiScaledHeight.toFloat()

        scale = min(guiWidth / REFERENCE_WIDTH, guiHeight / REFERENCE_HEIGHT)
        width = guiWidth / scale
        height = guiHeight / scale
    }

    fun push(ctx: GuiGraphicsExtractor) {
        refresh()
        ctx.pose().pushMatrix()
        ctx.pose().scale(scale, scale)
    }

    fun pop(ctx: GuiGraphicsExtractor) {
        ctx.pose().popMatrix()
    }

    fun toVirtual(guiCoordinate: Double) = (guiCoordinate / scale).toInt()
}
