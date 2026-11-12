package com.laybalt.ui.clickgui.components.settings.impl

import com.laybalt.config.types.ButtonSetting
import com.laybalt.ui.clickgui.UI
import com.laybalt.ui.clickgui.components.settings.Style
import com.laybalt.ui.clickgui.components.settings.Widget
import com.mojang.blaze3d.platform.InputConstants
import com.mojang.blaze3d.platform.cursor.CursorTypes
import net.minecraft.client.gui.GuiGraphicsExtractor

/**
 * Full-width rounded button. Buttons that reset or delete something use the danger style, "open"-style actions the
 * accent style, everything else the secondary style.
 */
class ButtonWidget(config: ButtonSetting): Widget<Unit>(config) {
    private inline val cfg get() = config as ButtonSetting
    private val press = UI.Smooth(0f, 24f)
    private var pressedAt = 0L

    override val height get() = 24f

    private val style = name.lowercase().let {
        when {
            it.startsWith("reset") || it.startsWith("clear") || it.startsWith("delete") || it.startsWith("remove") -> UI.ButtonStyle.DANGER
            it.startsWith("open") || it.startsWith("edit") -> UI.ButtonStyle.PRIMARY
            else -> UI.ButtonStyle.SECONDARY
        }
    }

    override fun draw(ctx: GuiGraphicsExtractor, mx: Float, my: Float) {
        val hot = hover.update(isHovered(mx, my))
        if (hot > 0.5f) ctx.requestCursor(CursorTypes.POINTING_HAND)
        val p = press.update(System.currentTimeMillis() - pressedAt < 110)
        val inset = p * 1f
        UI.button(ctx, name, x + inset, y + inset, width - inset * 2f, height - inset * 2f, hot, style)
    }

    override fun mouseClicked(mx: Float, my: Float, button: Int): Boolean {
        if (button != InputConstants.MOUSE_BUTTON_LEFT || !isHovered(mx, my)) return false
        if (cfg.clickSound) Style.playClickSound(1f)
        pressedAt = System.currentTimeMillis()
        cfg.invoke()
        return true
    }
}
