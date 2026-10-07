package com.laybalt.ui.clickgui.components.settings.impl

import com.laybalt.config.types.MultiCheckboxSetting
import com.laybalt.ui.Palette
import com.laybalt.ui.clickgui.UI
import com.laybalt.ui.clickgui.components.Rect
import com.laybalt.ui.clickgui.components.settings.Style
import com.laybalt.ui.clickgui.components.settings.Widget
import com.laybalt.ui.render.Icon
import com.laybalt.ui.text.UIFont
import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.gui.GuiGraphicsExtractor

/** Every option is a chip under the label; a click toggles it. */
class MultiCheckboxWidget(config: MultiCheckboxSetting): Widget<MutableMap<String, Boolean>>(config) {
    private companion object {
        const val CHIP_H = 16f
        const val GAP = 4f
        const val TEXT = 7f
    }

    private val chipHover = HashMap<String, UI.Smooth>()
    private var layoutWidth = -1f
    private var layout = emptyList<Pair<String, Rect>>()
    private var rows = 1

    override val height get() = ROW_H + rows * (CHIP_H + GAP) + 4f

    private fun chipWidth(option: String) = UI.width(option, TEXT, UIFont.Weight.MEDIUM) + 22f

    /** Chip rectangles relative to the widget's top-left, wrapped to the current width. */
    private fun relayout() {
        if (layoutWidth == width && layout.size == value.size) return
        layoutWidth = width
        val maxX = width - PAD
        var cx = PAD
        var cy = ROW_H - 2f
        var count = 1
        layout = value.keys.map { option ->
            val w = chipWidth(option).coerceAtMost(maxX - PAD)
            if (cx + w > maxX && cx > PAD) {
                cx = PAD
                cy += CHIP_H + GAP
                count++
            }
            val r = Rect(cx, cy, w, CHIP_H)
            cx += w + GAP
            option to r
        }
        rows = if (layout.isEmpty()) 0 else count
    }

    override fun draw(ctx: GuiGraphicsExtractor, mx: Float, my: Float) {
        relayout()
        drawRow(ctx, mx, my)
        drawLabel(ctx, width - PAD * 2f - 40f)
        val enabledCount = value.count { it.value }
        UI.textMidRight(ctx, "$enabledCount/${value.size}", x + width - PAD, y + ROW_H / 2f, Palette.textMuted, VALUE_SIZE)

        for ((option, rel) in layout) {
            val r = Rect(x + rel.x, y + rel.y, rel.w, rel.h)
            val on = value[option] == true
            val hot = chipHover.getOrPut(option) { UI.Smooth() }.update(r.contains(mx, my))
            val bg = if (on) UI.mix(Palette.accentPill, Palette.accentHover.brighterAccent(), hot) else UI.mix(Palette.chip, Palette.outline, hot)
            UI.rect(ctx, r.x, r.y, r.w, r.h, r.h / 2f, bg)
            UI.outline(ctx, r.x, r.y, r.w, r.h, r.h / 2f, if (on) Palette.accentBorder else UI.mix(Palette.outline, Palette.outlineHover, hot))
            val fg = if (on) Palette.accentText else UI.mix(Palette.textSecondary, Palette.textPrimary, hot)
            if (on) UI.icon(ctx, Icon.CHECK, r.x + 5f, r.y + (r.h - 8f) / 2f, 8f, fg)
            else UI.circle(ctx, r.x + 9f, r.y + r.h / 2f, 2f, Palette.textMuted)
            UI.textMid(ctx, UIFont.trim(option, r.w - 22f, TEXT, UIFont.Weight.MEDIUM), r.x + 15f, r.y + r.h / 2f, fg, TEXT, UIFont.Weight.MEDIUM)
        }
    }

    private fun java.awt.Color.brighterAccent() = UI.mix(this, Palette.accent, 0.35f)

    override fun mouseClicked(mx: Float, my: Float, button: Int): Boolean {
        if (button != InputConstants.MOUSE_BUTTON_LEFT || !isHovered(mx, my)) return false
        for ((option, rel) in layout) {
            if (!UI.inside(mx, my, x + rel.x, y + rel.y, rel.w, rel.h)) continue
            val now = !(value[option] ?: false)
            value[option] = now
            Style.playClickSound(if (now) 1.1f else 0.9f)
            return true
        }
        return true
    }
}
