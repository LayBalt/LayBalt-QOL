package com.laybalt.ui.clickgui

import com.laybalt.ui.Palette
import com.laybalt.ui.text.UIFont
import com.laybalt.ui.utils.Resolution
import net.minecraft.client.gui.GuiGraphicsExtractor
import kotlin.math.abs

/**
 * Delayed hover tooltips of the LayBalt screens: a rounded popover card with Inter text. Call [hover] while the
 * mouse is over something during a frame and [draw] once at the end of that frame.
 */
object TooltipManager {
    private const val DELAY = 450L
    private const val MAX_WIDTH = 190f
    private const val SIZE = 7.2f
    private const val PAD = 7f

    private var hoveredText: String? = null
    private var shownText: String? = null
    private var displayStartTime = 0L
    private var lastMouseX = 0f
    private var lastMouseY = 0f
    private val appear = UI.Smooth(0f, 20f)

    fun hover(text: String?, mouseX: Number, mouseY: Number) {
        if (text.isNullOrBlank()) return
        val mx = mouseX.toFloat()
        val my = mouseY.toFloat()
        if (abs(mx - lastMouseX) > 5 || abs(my - lastMouseY) > 5 || text != shownText) {
            displayStartTime = System.currentTimeMillis()
            appear.snap(0f)
        }
        lastMouseX = mx
        lastMouseY = my
        hoveredText = text
        shownText = text
    }

    fun draw(ctx: GuiGraphicsExtractor, logicalWidth: Float = Resolution.width, logicalHeight: Float = Resolution.height) {
        val text = hoveredText
        hoveredText = null
        if (text == null) {
            shownText = null
            return
        }
        if (System.currentTimeMillis() - displayStartTime < DELAY) return

        val lines = UIFont.wrap(text, MAX_WIDTH, SIZE)
        val lineH = UIFont.lineHeight(SIZE) + 1f
        val w = lines.maxOf { UIFont.width(it, SIZE) } + PAD * 2f
        val h = lines.size * lineH - 1f + PAD * 2f - 2f

        var tx = lastMouseX + 10f
        var ty = lastMouseY + 12f
        if (tx + w > logicalWidth - 4f) tx = lastMouseX - w - 6f
        if (ty + h > logicalHeight - 4f) ty = lastMouseY - h - 6f
        tx = tx.coerceAtLeast(4f)
        ty = ty.coerceAtLeast(4f)

        val t = appear.update(1f)
        ctx.nextStratum()
        val saved = UI.alpha
        UI.alpha = saved * t
        UI.shadow(ctx, tx, ty, w, h, 6f, 10f, Palette.shadowStrong, offsetY = 3f)
        UI.rect(ctx, tx, ty, w, h, 6f, Palette.popover)
        UI.outline(ctx, tx, ty, w, h, 6f, Palette.outlineHover)
        var ly = ty + PAD - 1f
        for (line in lines) {
            UI.text(ctx, line, tx + PAD, ly, Palette.textPrimary, SIZE)
            ly += lineH
        }
        UI.alpha = saved
    }
}
