package com.laybalt.GUI.clickgui

import com.laybalt.GUI.clickgui.util.Render2D.drawRect
import com.laybalt.GUI.clickgui.util.Render2D.drawString
import com.laybalt.GUI.clickgui.util.Render2D.fontHeight
import com.laybalt.GUI.clickgui.util.Render2D.width
import com.laybalt.GUI.clickgui.util.Render2D.wrap
import net.minecraft.client.gui.GuiGraphicsExtractor
import java.awt.Color
import kotlin.math.abs

/** Shows a description box after the cursor rests on something for [DISPLAY_DELAY] ms. */
object TooltipManager {
    private const val DISPLAY_DELAY = 500L
    private val backgroundColor = Color(10, 10, 10, 240)

    private var hoveredText: String? = null
    private var displayStartTime = 0L
    private var lastMouseX = 0
    private var lastMouseY = 0

    fun hover(text: String?, mouseX: Int, mouseY: Int) {
        if (text.isNullOrBlank()) return
        if (abs(mouseX - lastMouseX) > 5 || abs(mouseY - lastMouseY) > 5) displayStartTime = System.currentTimeMillis()

        lastMouseX = mouseX
        lastMouseY = mouseY
        hoveredText = text
    }

    fun draw(ctx: GuiGraphicsExtractor, screenWidth: Float, screenHeight: Float) {
        val text = hoveredText ?: return
        hoveredText = null
        if (System.currentTimeMillis() - displayStartTime < DISPLAY_DELAY) return

        val lines = text.wrap(150)
        val padding = 6f
        val lineStep = fontHeight + 2
        val textWidth = lines.maxOf { it.width() }
        val textHeight = lines.size * lineStep

        var tx = lastMouseX + 12f
        var ty = lastMouseY + 12f
        if (tx + textWidth + padding * 2 > screenWidth) tx = lastMouseX - textWidth - padding * 2 - 4f
        if (ty + textHeight + padding * 2 > screenHeight) ty = screenHeight - textHeight - padding * 2 - 4f

        ctx.drawRect(tx, ty, textWidth + padding * 2, textHeight + padding * 1.5f, backgroundColor)
        ctx.drawRect(tx, ty, textWidth + padding * 2, 1.5f, Style.accentColor)

        var currentY = ty + padding
        lines.forEach { line ->
            ctx.drawString(line, tx + padding, currentY)
            currentY += lineStep
        }
    }
}
