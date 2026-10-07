package com.laybalt.ui.clickgui.components.settings.impl

import com.laybalt.config.types.UnitSetting
import com.laybalt.ui.clickgui.components.Rect
import com.laybalt.ui.clickgui.components.settings.Widget
import net.minecraft.client.gui.GuiGraphicsExtractor

/** Extra breathing room between two sections. */
class SeparatorWidget(name: String = ""): Widget<Unit>(UnitSetting(name)) {
    override val height get() = 4f
    override fun draw(ctx: GuiGraphicsExtractor, mx: Float, my: Float) = Unit
    override fun tooltipArea() = Rect(0f, 0f, 0f, 0f)
}
