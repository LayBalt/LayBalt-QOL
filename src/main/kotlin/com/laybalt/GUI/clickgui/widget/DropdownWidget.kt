package com.laybalt.GUI.clickgui.widget

import com.laybalt.GUI.clickgui.Style
import com.laybalt.GUI.clickgui.setting.DropdownSetting
import com.laybalt.GUI.clickgui.util.Animation
import com.laybalt.GUI.clickgui.util.Render2D.drawRect
import com.laybalt.GUI.clickgui.util.Render2D.drawString
import com.laybalt.GUI.clickgui.util.Render2D.scissor
import com.laybalt.GUI.clickgui.util.Render2D.width
import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.gui.GuiGraphicsExtractor
import java.awt.Color

class DropdownWidget(config: DropdownSetting): Widget<Int>(config) {
    private companion object {
        const val HEADER = 20
        const val OPTION = 16
    }

    private val cfg = config
    private var expanded = false
    private val openAnim = Animation(250)
    private val hoverAnim = Animation(200)

    override val height get() = HEADER + (openAnim.value * cfg.options.size * OPTION).toInt()

    override fun draw(ctx: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        openAnim.update(if (expanded) 1f else 0f)
        hoverAnim.update(if (isHovered(mouseX, mouseY, HEADER)) 1f else 0f)

        Style.drawBackground(ctx, x, y, width, HEADER)
        Style.drawHoverBar(ctx, x.toFloat(), y.toFloat(), HEADER.toFloat(), hoverAnim.value)
        Style.drawNudgedText(ctx, name, x + 8f, y + 6f, hoverAnim.value)

        val selected = "§7${cfg.options.getOrElse(value) { "?" }}"
        ctx.drawString(selected, x + width - selected.width() - 8f, y + 6f)

        if (openAnim.value <= 0.01f) return

        ctx.scissor(x, y, width, height)
        var oy = y + HEADER.toFloat()
        ctx.drawRect(x + 4f, oy, width - 8f, cfg.options.size * OPTION * openAnim.value, Color(5, 5, 5, 150))
        cfg.options.forEachIndexed { index, option ->
            val hovered = mouseX >= x + 4 && mouseX <= x + width - 4 && mouseY >= oy && mouseY <= oy + OPTION
            if (hovered) ctx.drawRect(x + 4f, oy, width - 8f, OPTION.toFloat(), Color(255, 255, 255, 20))
            if (index == value) ctx.drawRect(x + 4f, oy + 2f, 1.5f, 12f, Style.accentColor)

            val color = if (index == value) Style.accentColor else if (hovered) Color.WHITE else Color.GRAY
            ctx.drawString(option, x + 12f, oy + 4f, color)
            oy += OPTION
        }
        ctx.disableScissor()
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (isHovered(mouseX, mouseY, HEADER) && button == InputConstants.MOUSE_BUTTON_LEFT) {
            expanded = !expanded
            Style.playClickSound(1f)
            return true
        }

        if (expanded && isHovered(mouseX, mouseY)) {
            val index = ((mouseY - y - HEADER) / OPTION).toInt()
            if (index in cfg.options.indices) {
                value = index
                Style.playClickSound(1f)
                expanded = false
                return true
            }
        }

        expanded = false
        return false
    }
}
