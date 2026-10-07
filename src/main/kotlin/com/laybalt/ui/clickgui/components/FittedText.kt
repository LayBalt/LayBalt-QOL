package com.laybalt.ui.clickgui.components

import com.laybalt.utils.render.Render2D.drawCenteredString
import com.laybalt.utils.render.RenderHelper.height
import com.laybalt.utils.render.RenderHelper.width
import net.minecraft.client.gui.GuiGraphicsExtractor
import java.awt.Color

private const val MIN_FIT_SCALE = 0.6f
private const val ELLIPSIS = "…"

/**
 * Draws [prefix] + [text] centred on [centerX] within [maxWidth] px. Text that is too wide is scaled down
 * uniformly (not below 0.6); if it still does not fit at 0.6 it is cut with "…".
 * [y] is the top of the text at scale 1; scaled text is moved down so its vertical centre stays the same.
 */
internal fun GuiGraphicsExtractor.drawFittedCenteredString(
    text: String, centerX: Number, y: Number, maxWidth: Number, color: Color,
    prefix: String = "", shadow: Boolean = true
) {
    val available = maxWidth.toFloat()
    var str = prefix + text
    val fullWidth = str.width()
    if (fullWidth <= available || available <= 0f) {
        drawCenteredString(str, centerX, y, color, shadow = shadow)
        return
    }

    var scale = available / fullWidth
    if (scale < MIN_FIT_SCALE) {
        scale = MIN_FIT_SCALE
        val limit = available / MIN_FIT_SCALE
        var cut = text
        while (cut.isNotEmpty() && (prefix + cut.trimEnd() + ELLIPSIS).width() > limit) cut = cut.dropLast(1)
        str = prefix + cut.trimEnd() + ELLIPSIS
    }

    val fontHeight = "A".height()
    drawCenteredString(str, centerX, y.toFloat() + fontHeight * (1f - scale) / 2f, color, scale, shadow)
}
