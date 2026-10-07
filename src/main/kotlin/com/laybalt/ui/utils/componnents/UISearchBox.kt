package com.laybalt.ui.utils.componnents

import com.laybalt.LayBaltQOL.mc
import com.laybalt.ui.Palette
import com.laybalt.ui.clickgui.components.settings.Style
import com.laybalt.utils.render.Render2D.drawRect
import gg.essential.universal.UGraphics
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.EditBox
import net.minecraft.network.chat.Component

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

        guiGraphics.drawRect(x.toFloat(), y.toFloat(), width.toFloat(), height.toFloat(), Palette.bgInset)

        val borderColor = if (this.isFocused) Style.accentColor else Palette.border
        guiGraphics.drawRect(x.toFloat(), (y + height - 2).toFloat(), width.toFloat(), 2f, borderColor)

        val pose = guiGraphics.pose()
        pose.pushMatrix()
        pose.translate(5f, (height / 2f) - UGraphics.getFontHeight() / 2)
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