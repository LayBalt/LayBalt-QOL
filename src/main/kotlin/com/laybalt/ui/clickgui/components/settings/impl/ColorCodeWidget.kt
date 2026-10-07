package com.laybalt.ui.clickgui.components.settings.impl

import com.laybalt.config.types.ColorCodeSetting
import com.laybalt.ui.Palette
import com.laybalt.ui.clickgui.UI
import com.laybalt.ui.clickgui.components.Popover
import com.laybalt.ui.clickgui.components.Popovers
import com.laybalt.ui.clickgui.components.Rect
import com.laybalt.ui.clickgui.components.settings.Style
import com.laybalt.ui.clickgui.components.settings.Widget
import com.laybalt.ui.text.UIFont
import com.mojang.blaze3d.platform.InputConstants
import gg.essential.universal.ChatColor
import net.minecraft.client.gui.GuiGraphicsExtractor
import java.awt.Color

/** Minecraft chat colour: a select with the colour's swatch and name, choices in a swatch grid popover. */
class ColorCodeWidget(config: ColorCodeSetting): Widget<ChatColor>(config) {
    private var box = Rect(0f, 0f, 0f, 0f)
    private val grid = Grid()

    private fun swatch(format: ChatColor): Color = format.color ?: ChatColor.WHITE.color!!
    private fun prettyName(format: ChatColor) = format.name.split('_').joinToString(" ") { it.lowercase().replaceFirstChar(Char::uppercase) }

    override fun draw(ctx: GuiGraphicsExtractor, mx: Float, my: Float) {
        val open = Popovers.isOpen(grid)
        drawRow(ctx, mx, my, active = open)
        val text = prettyName(value)
        box = selectBox(text).let { Rect(it.x - 12f, it.y, it.w + 12f, it.h) }
        drawLabel(ctx, box.x - x - PAD - 6f)
        drawSelectBox(ctx, box, text, mx, my, open, swatch(value))
    }

    override fun mouseClicked(mx: Float, my: Float, button: Int): Boolean {
        if (button != InputConstants.MOUSE_BUTTON_LEFT || !isHovered(mx, my)) return false
        Popovers.open(grid)
        Style.playClickSound(1f)
        return true
    }

    private inner class Grid: Popover() {
        private val cols = 8
        private val cell = 16f
        private val gap = 4f
        private val pad = 9f
        private val rows get() = (ColorCodeSetting.COLORS.size + cols - 1) / cols

        override val width get() = pad * 2f + cols * cell + (cols - 1) * gap
        override val height get() = pad * 2f + rows * cell + (rows - 1) * gap + 16f

        override fun anchor() = box

        private fun cellRect(index: Int) = Rect(x + pad + (index % cols) * (cell + gap), y + pad + (index / cols) * (cell + gap), cell, cell)

        override fun drawContent(ctx: GuiGraphicsExtractor, mx: Float, my: Float) {
            var hovered: ChatColor? = null
            ColorCodeSetting.COLORS.forEachIndexed { index, format ->
                val r = cellRect(index)
                val hot = r.contains(mx, my)
                if (hot) hovered = format
                val grow = if (hot) 1f else 0f
                UI.rect(ctx, r.x - grow, r.y - grow, r.w + grow * 2f, r.h + grow * 2f, 4.5f, swatch(format))
                if (format == value) {
                    UI.outline(ctx, r.x - 2.5f, r.y - 2.5f, r.w + 5f, r.h + 5f, 6.5f, Palette.accentText, 1.2f)
                }
                else UI.outline(ctx, r.x, r.y, r.w, r.h, 4.5f, Palette.outline)
            }
            val shown = hovered ?: value
            val labelY = y + height - pad - 6f
            UI.circle(ctx, x + pad + 3f, labelY, 3f, swatch(shown))
            UI.textMid(ctx, prettyName(shown), x + pad + 10f, labelY, Palette.textPrimary, VALUE_SIZE, UIFont.Weight.MEDIUM)
            UI.textMidRight(ctx, "&" + shown.char, x + width - pad, labelY, Palette.textMuted, 6.5f)
        }

        override fun mouseClicked(mx: Float, my: Float, button: Int): Boolean {
            if (button != InputConstants.MOUSE_BUTTON_LEFT) return true
            ColorCodeSetting.COLORS.forEachIndexed { index, format ->
                if (cellRect(index).contains(mx, my)) {
                    value = format
                    Style.playClickSound(1f)
                    Popovers.close()
                    return true
                }
            }
            return true
        }
    }
}
