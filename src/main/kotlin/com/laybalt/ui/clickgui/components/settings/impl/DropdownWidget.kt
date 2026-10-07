package com.laybalt.ui.clickgui.components.settings.impl

import com.laybalt.config.types.DropdownSetting
import com.laybalt.ui.Palette
import com.laybalt.ui.clickgui.UI
import com.laybalt.ui.clickgui.components.Popover
import com.laybalt.ui.clickgui.components.Popovers
import com.laybalt.ui.clickgui.components.Rect
import com.laybalt.ui.clickgui.components.ScrollArea
import com.laybalt.ui.clickgui.components.settings.Style
import com.laybalt.ui.clickgui.components.settings.Widget
import com.laybalt.ui.render.Icon
import com.laybalt.ui.text.UIFont
import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.input.KeyEvent

/** Rounded select showing the current option; opens a popover list. */
class DropdownWidget(config: DropdownSetting): Widget<Int>(config) {
    private inline val cfg get() = config as DropdownSetting

    private var box = Rect(0f, 0f, 0f, 0f)
    private val list = OptionList()

    override fun draw(ctx: GuiGraphicsExtractor, mx: Float, my: Float) {
        val open = Popovers.isOpen(list)
        drawRow(ctx, mx, my, active = open)
        val current = cfg.options.getOrElse(value) { "" }
        box = selectBox(current)
        drawLabel(ctx, box.x - x - PAD - 6f)
        drawSelectBox(ctx, box, current, mx, my, open)
    }

    override fun mouseClicked(mx: Float, my: Float, button: Int): Boolean {
        if (button != InputConstants.MOUSE_BUTTON_LEFT || !isHovered(mx, my)) return false
        list.highlighted = value
        list.scroll.set(((value - 3) * ITEM_H).coerceAtLeast(0f), instant = true)
        Popovers.open(list)
        Style.playClickSound(1f)
        return true
    }

    private inner class OptionList: Popover() {
        val scroll = ScrollArea(ITEM_H * 2f)
        var highlighted = 0

        override val width get() = (cfg.options.maxOfOrNull { UI.width(it, VALUE_SIZE) } ?: 40f).plus(40f).coerceAtLeast(box.w).coerceAtMost(260f)
        override val height get() = cfg.options.size.coerceAtMost(MAX_VISIBLE) * ITEM_H + PADDING * 2f

        override fun anchor() = box

        override fun drawContent(ctx: GuiGraphicsExtractor, mx: Float, my: Float) {
            val viewY = y + PADDING
            val viewH = height - PADDING * 2f
            scroll.update(x + width - 2f, viewY, viewH, cfg.options.size * ITEM_H, my)
            ctx.enableScissor(x.toInt(), viewY.toInt(), (x + width).toInt(), (viewY + viewH + 1).toInt())
            cfg.options.forEachIndexed { index, option ->
                val oy = viewY + index * ITEM_H - scroll.offset
                if (oy + ITEM_H < viewY || oy > viewY + viewH) return@forEachIndexed
                val hot = UI.inside(mx, my, x + 4f, oy, width - 8f, ITEM_H) && contains(mx, my)
                if (hot) highlighted = index
                val selected = index == value
                if (hot || index == highlighted) UI.rect(ctx, x + 4f, oy + 1f, width - 8f, ITEM_H - 2f, 5f, if (hot) Palette.accentPill else Palette.accentHover)
                val color = if (selected) Palette.accentText else if (hot) Palette.textPrimary else Palette.textSecondary
                UI.textMid(ctx, UIFont.trim(option, width - 34f, VALUE_SIZE), x + 11f, oy + ITEM_H / 2f, color, VALUE_SIZE, if (selected) UIFont.Weight.SEMIBOLD else UIFont.Weight.REGULAR)
                if (selected) UI.icon(ctx, Icon.CHECK, x + width - 19f, oy + (ITEM_H - 9f) / 2f, 9f, Palette.accentText)
            }
            ctx.disableScissor()
            scroll.draw(ctx, mx, my)
        }

        override fun mouseClicked(mx: Float, my: Float, button: Int): Boolean {
            if (button != InputConstants.MOUSE_BUTTON_LEFT) return true
            if (scroll.mouseClicked(mx, my)) return true
            val index = ((my - y - PADDING + scroll.offset) / ITEM_H).toInt()
            if (index in cfg.options.indices && my >= y + PADDING && my < y + height - PADDING) select(index)
            return true
        }

        private fun select(index: Int) {
            value = index
            Style.playClickSound(1f)
            Popovers.close()
        }

        override fun mouseReleased(button: Int) = scroll.mouseReleased()

        override fun mouseScrolled(mx: Float, my: Float, delta: Double): Boolean {
            scroll.scroll(delta)
            return true
        }

        override fun keyPressed(event: KeyEvent): Boolean {
            when (event.key) {
                InputConstants.KEY_DOWN -> highlighted = (highlighted + 1).coerceAtMost(cfg.options.lastIndex)
                InputConstants.KEY_UP -> highlighted = (highlighted - 1).coerceAtLeast(0)
                InputConstants.KEY_RETURN, InputConstants.KEY_NUMPADENTER, InputConstants.KEY_SPACE -> select(highlighted)
                else -> return false
            }
            scroll.reveal(highlighted * ITEM_H, ITEM_H, 4f)
            return true
        }
    }

    private companion object {
        const val MAX_VISIBLE = 8
        const val PADDING = 4f
        const val ITEM_H = 18f
    }
}
