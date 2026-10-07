package com.laybalt.ui.clickgui.components

import com.laybalt.ui.Palette
import com.laybalt.ui.clickgui.UI
import com.laybalt.ui.render.Icon
import com.laybalt.ui.utils.TextInputHandler
import com.mojang.blaze3d.platform.InputConstants
import com.mojang.blaze3d.platform.cursor.CursorTypes
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.input.MouseButtonInfo

/** Pill-shaped search field (top bar of the LayBalt screen, Sound Manager), with a clear button while it has text. */
class SearchField(
    private val placeholder: String = "Search features…",
    private val showHint: Boolean = true,
    private val onChange: (String) -> Unit
) {
    companion object {
        const val TEXT_SIZE = 7.8f
    }

    var query = ""
        private set

    private val handler = TextInputHandler({ query }, {
        query = it
        onChange(it)
    }).apply {
        fontSize = TEXT_SIZE
        padding = 0f
    }

    private var rect = Rect(0f, 0f, 0f, 0f)
    private val hover = UI.Smooth()
    private val focus = UI.Smooth(0f, 20f)
    private val clearHover = UI.Smooth()

    val focused get() = handler.listening

    private fun clearRect() = Rect(rect.right - 20f, rect.y + (rect.h - 16f) / 2f, 16f, 16f)
    private fun textX() = rect.x + 24f

    fun draw(ctx: GuiGraphicsExtractor, r: Rect, mx: Float, my: Float) {
        rect = r
        val hot = hover.update(r.contains(mx, my))
        val f = focus.update(handler.listening)
        if (hot > 0.5f && !clearRect().contains(mx, my)) ctx.requestCursor(CursorTypes.IBEAM)

        if (f > 0.01f) UI.rect(ctx, r.x - 2f, r.y - 2f, r.w + 4f, r.h + 4f, (r.h + 4f) / 2f, UI.fade(Palette.accent, 0.16f * f))
        UI.rect(ctx, r.x, r.y, r.w, r.h, r.h / 2f, UI.mix(Palette.input, Palette.inputHover, maxOf(hot, f)))
        UI.outline(ctx, r.x, r.y, r.w, r.h, r.h / 2f, UI.mix(UI.mix(Palette.outline, Palette.outlineHover, hot), Palette.accentBorder, f))
        UI.icon(ctx, Icon.SEARCH, r.x + 8f, r.y + (r.h - 11f) / 2f, 11f, UI.mix(Palette.textMuted, Palette.accentText, f))

        val right = if (query.isNotEmpty()) 24f else 10f
        handler.x = textX()
        handler.y = r.y
        handler.width = r.right - right - textX()
        handler.height = r.h
        handler.opacity = UI.alpha
        if (query.isEmpty()) {
            if (!handler.listening) UI.textMid(ctx, placeholder, textX(), r.y + r.h / 2f, Palette.textMuted, TEXT_SIZE)
            else {
                handler.draw(ctx, mx, my)
                UI.textMid(ctx, placeholder, textX() + 2f, r.y + r.h / 2f, UI.fade(Palette.textMuted, 0.6f), TEXT_SIZE)
            }
        }
        else handler.draw(ctx, mx, my)

        if (query.isNotEmpty()) {
            val c = clearRect()
            val ch = clearHover.update(c.contains(mx, my))
            if (ch > 0.5f) ctx.requestCursor(CursorTypes.POINTING_HAND)
            UI.circle(ctx, c.x + c.w / 2f, c.y + c.h / 2f, 6.5f, UI.mix(Palette.chip, Palette.outlineHover, ch))
            UI.icon(ctx, Icon.CLOSE, c.x + 4.5f, c.y + 4.5f, 7f, UI.mix(Palette.textSecondary, Palette.textPrimary, ch), 2f)
        }
        else {
            val hint = "Ctrl F"
            if (showHint && !handler.listening && r.w > 150f) UI.chip(ctx, hint, r.right - 10f - UI.chipWidth(hint, 6f), r.y + (r.h - 12f) / 2f, 12f, Palette.chip, Palette.textMuted, 6f)
        }
    }

    fun mouseClicked(mx: Float, my: Float, button: Int): Boolean {
        if (query.isNotEmpty() && clearRect().contains(mx, my) && button == InputConstants.MOUSE_BUTTON_LEFT) {
            clear()
            handler.focusEnd()
            return true
        }
        if (handler.mouseClicked(mx, my, MouseButtonEvent(mx.toDouble(), my.toDouble(), MouseButtonInfo(button, 0)))) return true
        if (rect.contains(mx, my)) {
            handler.focusEnd()
            return true
        }
        return false
    }

    fun mouseReleased() = handler.mouseReleased()

    fun focus() = handler.focusEnd()

    fun unfocus() = handler.resetState()

    fun clear() {
        if (query.isEmpty()) return
        val wasFocused = handler.listening
        query = ""
        onChange("")
        // Re-seat the caret on the now empty text.
        if (wasFocused) handler.focusEnd() else handler.resetState()
    }

    fun keyPressed(event: KeyEvent): Boolean {
        if (!handler.listening) return false
        if (event.key == InputConstants.KEY_ESCAPE) {
            // First Escape clears the text, the second one leaves the field.
            if (query.isNotEmpty()) clear() else unfocus()
            return true
        }
        return handler.keyPressed(event)
    }

    fun charTyped(event: CharacterEvent): Boolean = handler.keyTyped(event)
}
