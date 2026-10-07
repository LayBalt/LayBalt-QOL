package com.laybalt.ui.clickgui.components.settings.impl

import com.laybalt.config.types.UnitSetting
import com.laybalt.ui.clickgui.components.settings.Widget
import com.laybalt.utils.render.Render2D.drawRect
import net.minecraft.client.gui.GuiGraphicsExtractor
import com.laybalt.ui.Palette

class SeparatorWidget(name: String = ""): Widget<Unit>(UnitSetting(name)) {
    override val height = 10
    override fun draw(ctx: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        ctx.drawRect(x + 10f, y + 5f, width - 20f, 0.5f, Palette.border)
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int) = false
}