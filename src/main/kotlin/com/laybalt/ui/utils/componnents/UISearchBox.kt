package com.laybalt.ui.utils.componnents

import com.laybalt.LayBaltQOL.mc
import com.laybalt.ui.Palette
import com.laybalt.ui.clickgui.UI
import com.laybalt.ui.clickgui.components.settings.Style
import gg.essential.universal.UGraphics
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.EditBox
import net.minecraft.network.chat.Component

/**
 * Vanilla [EditBox] in a LayBalt rounded field (input fill, hairline border, accent ring while focused). The text
 * itself keeps the vanilla font: EditBox measures caret and selection with it.
 */
class UISearchBox(
    x: Int,
    y: Int,
    width: Int,
    height: Int,
    message: Component
): EditBox(mc.font, x, y, width, height, message) {

    init {
        this.isBordered = false
        this.setTextColor(Palette.textPrimary.rgb)
    }

    override fun extractWidgetRenderState(guiGraphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        if (! this.isVisible) return

        val savedAlpha = UI.alpha
        UI.alpha = 1f
        val fx = x.toFloat()
        val fy = y.toFloat()
        val fw = width.toFloat()
        val fh = height.toFloat()
        val radius = (fh / 2f).coerceAtMost(6f)
        val hot = isHovered
        if (isFocused) UI.rect(guiGraphics, fx - 2f, fy - 2f, fw + 4f, fh + 4f, radius + 2f, UI.fade(Palette.accent, 0.18f))
        UI.rect(guiGraphics, fx, fy, fw, fh, radius, if (isFocused || hot) Palette.inputHover else Palette.input)
        UI.outline(guiGraphics, fx, fy, fw, fh, radius, if (isFocused) Palette.accentBorder else if (hot) Palette.outlineHover else Palette.outline)
        UI.alpha = savedAlpha

        val pose = guiGraphics.pose()
        pose.pushMatrix()
        pose.translate(6f, (height / 2f) - UGraphics.getFontHeight() / 2)
        super.extractWidgetRenderState(guiGraphics, mouseX, mouseY, partialTick)
        pose.popMatrix()
    }

    override fun setFocused(focused: Boolean) {
        super.setFocused(focused)
        if (focused) {
            Style.playClickSound(1.1f)
        }
    }
}
