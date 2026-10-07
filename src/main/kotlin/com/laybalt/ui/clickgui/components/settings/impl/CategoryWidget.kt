package com.laybalt.ui.clickgui.components.settings.impl

import com.laybalt.config.types.UnitSetting
import com.laybalt.ui.Palette
import com.laybalt.ui.clickgui.UI
import com.laybalt.ui.clickgui.components.Rect
import com.laybalt.ui.clickgui.components.settings.Widget
import com.laybalt.ui.text.UIFont
import net.minecraft.client.gui.GuiGraphicsExtractor

/** Section header (from a setting's `.section()`): small accent caps label followed by a hairline. */
class CategoryWidget(name: String): Widget<Unit>(UnitSetting(name)) {
    override val height get() = 22f

    override fun draw(ctx: GuiGraphicsExtractor, mx: Float, my: Float) {
        val label = name.uppercase()
        val size = 6.6f
        val cy = y + height - 8f
        UI.rect(ctx, x + 2f, cy - 3.5f, 2.5f, 7f, 1.25f, Palette.accent)
        UI.textMid(ctx, label, x + 9f, cy, Palette.accentText, size, UIFont.Weight.BOLD)
        val lineX = x + 9f + UI.width(label, size, UIFont.Weight.BOLD) + 8f
        if (lineX < x + width - 4f) UI.hLine(ctx, lineX, cy, x + width - 4f - lineX, Palette.divider)
    }

    override fun tooltipArea() = Rect(0f, 0f, 0f, 0f)
}
