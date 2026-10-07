package com.laybalt.ui.clickgui.components.settings.impl

import com.mojang.blaze3d.platform.InputConstants

import com.laybalt.config.types.DropdownSetting
import com.laybalt.ui.clickgui.components.settings.Style
import com.laybalt.ui.clickgui.components.settings.Widget
import com.laybalt.ui.utils.Animation
import com.laybalt.utils.render.Render2D.drawRect
import com.laybalt.utils.render.Render2D.drawString
import com.laybalt.utils.render.Render2D.scissor
import com.laybalt.utils.render.RenderHelper.width
import net.minecraft.client.gui.GuiGraphicsExtractor
import com.laybalt.ui.Palette

class DropdownWidget(config: DropdownSetting): Widget<Int>(config) {
    private inline val cfg get() = config as DropdownSetting

    private var expanded = false
    private val openAnim = Animation(250)
    private val hoverAnim = Animation(200)

    override val height get() = 20 + (openAnim.value * (cfg.options.size * 16)).toInt()

    override fun draw(ctx: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val isHovered = mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + 20
        openAnim.update(if (expanded) 1f else 0f)
        hoverAnim.update(if (isHovered) 1f else 0f)

        ctx.drawRect(x, y, width, 20f, Style.settingBackgroundColor)
        Style.drawHoverBar(ctx, x, y, 20f, hoverAnim.value)
        Style.drawNudgedText(ctx, name, x + 8f, y + 6f, hoverAnim.value)

        val valStr = cfg.options[value]
        ctx.drawString(valStr, x + width - valStr.width() - 8f, y + 6f, Palette.textSecondary, scale = 1f)

        ctx.scissor(x, y, width, height)

        if (expanded) {
            var oy = y + 20f
            ctx.drawRect(x + 4f, oy, width - 8f, (cfg.options.size * 16) * openAnim.value, Palette.bgInset)
            cfg.options.forEachIndexed { index, opt ->
                val hov = mouseX >= x + 4 && mouseX <= x + width - 4 && mouseY >= oy && mouseY <= oy + 16
                if (hov) ctx.drawRect(x + 4f, oy, width - 8f, 16f, Palette.hover)
                if (index == value) ctx.drawRect(x + 4f, oy + 2f, 1.5f, 12f, Style.accentColor)

                val color = if (index == value) Style.accentColor else if (hov) Palette.textPrimary else Palette.textSecondary
                ctx.drawString(opt, x + 12f, oy + 4f, color, 1f)
                oy += 16
            }
        }

        ctx.disableScissor()
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + 20) {
            if (button == InputConstants.MOUSE_BUTTON_LEFT) {
                expanded = ! expanded
                Style.playClickSound(1f)
                return true
            }
        }

        if (expanded && mouseX >= x && mouseX <= x + width && mouseY >= y + 20 && mouseY <= y + height) {
            var optionY = y + 20
            cfg.options.forEachIndexed { index, _ ->
                if (mouseX >= x && mouseX <= x + width && mouseY >= optionY && mouseY <= optionY + 16) {
                    value = index
                    Style.playClickSound(1f)
                    expanded = false
                    return true
                }
                optionY += 16
            }
        }

        if (expanded) expanded = false
        return false
    }
}