package com.laybalt.ui.clickgui.components.settings.impl

import com.laybalt.config.types.TextInputSetting
import com.laybalt.ui.Palette
import com.laybalt.ui.clickgui.UI
import com.laybalt.ui.clickgui.components.settings.Widget
import com.laybalt.ui.text.UIFont
import com.laybalt.ui.utils.TextInputHandler
import com.mojang.blaze3d.platform.cursor.CursorTypes
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.input.MouseButtonInfo

/** Label with a rounded text field (caret, selection, clipboard, undo) under it. */
class TextInputWidget(config: TextInputSetting): Widget<String>(config) {
    private val handler = TextInputHandler(
        textProvider = { value },
        textSetter = { value = it }
    ).apply {
        fontSize = VALUE_SIZE + 0.2f
        padding = 7f
    }

    override val height get() = 48f
    override val typing get() = handler.listening

    private fun fieldY() = y + 23f

    override fun draw(ctx: GuiGraphicsExtractor, mx: Float, my: Float) {
        drawRow(ctx, mx, my, active = handler.listening)
        UI.textMid(ctx, UIFont.trim(name, width - PAD * 2f, LABEL_SIZE, UIFont.Weight.MEDIUM), x + PAD, y + 12.5f, Palette.textPrimary, LABEL_SIZE, UIFont.Weight.MEDIUM)

        val fx = x + PAD
        val fw = width - PAD * 2f
        val fy = fieldY()
        val fieldHover = UI.inside(mx, my, fx, fy, fw, 18f)
        if (fieldHover) ctx.requestCursor(CursorTypes.IBEAM)
        UI.rect(ctx, fx, fy, fw, 18f, 5f, if (handler.listening || fieldHover) Palette.inputHover else Palette.input)
        UI.outline(ctx, fx, fy, fw, 18f, 5f, if (handler.listening) Palette.accentBorder else if (fieldHover) Palette.outlineHover else Palette.outline)

        handler.x = fx
        handler.y = fy
        handler.width = fw
        handler.height = 18f
        handler.opacity = UI.alpha
        if (value.isEmpty() && !handler.listening) UI.textMid(ctx, "Type here…", fx + 7f, fy + 9f, Palette.textMuted, VALUE_SIZE + 0.2f)
        else handler.draw(ctx, mx, my)
    }

    override fun clearFocus() = handler.resetState()

    override fun mouseClicked(mx: Float, my: Float, button: Int): Boolean {
        val consumed = handler.mouseClicked(mx, my, MouseButtonEvent(mx.toDouble(), my.toDouble(), MouseButtonInfo(button, 0)))
        if (consumed) return true
        // Clicking the label part of the row also focuses the field.
        if (isHovered(mx, my) && button == 0) {
            handler.focusEnd()
            return true
        }
        return false
    }

    override fun mouseReleased(button: Int) = handler.mouseReleased()
    override fun charTyped(event: CharacterEvent) = handler.keyTyped(event)
    override fun keyPressed(event: KeyEvent) = handler.keyPressed(event)
}
