package com.laybalt.ui.clickgui.components.settings.impl

import com.laybalt.config.types.ToggleSetting
import com.laybalt.ui.clickgui.UI
import com.laybalt.ui.clickgui.components.settings.Style
import com.laybalt.ui.clickgui.components.settings.Widget
import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.gui.GuiGraphicsExtractor

/** On/off row with a switch on the right; the whole row toggles. */
class ToggleWidget(config: ToggleSetting): Widget<Boolean>(config) {
    private val knob = UI.Smooth(if (config.value) 1f else 0f, 18f)

    override fun draw(ctx: GuiGraphicsExtractor, mx: Float, my: Float) {
        drawRow(ctx, mx, my)
        drawLabel(ctx, width - PAD * 2f - UI.SWITCH_W - 8f)
        knob.update(value)
        UI.switch(ctx, x + width - PAD - UI.SWITCH_W, y + (ROW_H - UI.SWITCH_H) / 2f, knob.value, hover.value)
    }

    override fun mouseClicked(mx: Float, my: Float, button: Int): Boolean {
        if (button != InputConstants.MOUSE_BUTTON_LEFT || !isHovered(mx, my)) return false
        value = !value
        Style.playClickSound(if (value) 1.1f else 0.9f)
        return true
    }
}
