package com.laybalt.ui.clickgui.components.settings.impl

import com.laybalt.config.types.KeybindSetting
import com.laybalt.ui.Palette
import com.laybalt.ui.clickgui.UI
import com.laybalt.ui.clickgui.components.Rect
import com.laybalt.ui.clickgui.components.settings.Style
import com.laybalt.ui.clickgui.components.settings.Widget
import com.laybalt.ui.text.UIFont
import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.input.KeyEvent
import kotlin.math.sin

/**
 * Key chip on the right. Click it, then press a key (Backspace clears, Escape cancels) or a mouse button to bind.
 */
class KeybindWidget(config: KeybindSetting): Widget<Int>(config) {
    private inline val cfg get() = config as KeybindSetting

    private var listening = false
    private var chip = Rect(0f, 0f, 0f, 0f)

    override val capturing get() = listening

    override fun draw(ctx: GuiGraphicsExtractor, mx: Float, my: Float) {
        drawRow(ctx, mx, my, active = listening)
        val text = if (listening) "Press a key…" else cfg.displayName().let { if (it == "NONE") "None" else it }
        val w = (UI.width(text, VALUE_SIZE, UIFont.Weight.MEDIUM) + 16f).coerceAtLeast(44f)
        chip = Rect(x + width - PAD - w + 3f, y + 4f, w, 18f)
        drawLabel(ctx, chip.x - x - PAD - 6f)

        val hot = listening || chip.contains(mx, my)
        UI.rect(ctx, chip.x, chip.y, chip.w, chip.h, 5f, if (listening) Palette.accentPill else if (hot) Palette.inputHover else Palette.input)
        if (listening) {
            val pulse = 0.55f + 0.45f * sin(System.currentTimeMillis() / 180.0).toFloat()
            UI.outline(ctx, chip.x, chip.y, chip.w, chip.h, 5f, UI.fade(Palette.accent, pulse), 1.1f)
        }
        else {
            UI.outline(ctx, chip.x, chip.y, chip.w, chip.h, 5f, if (hot) Palette.outlineHover else Palette.outline)
            // keycap bottom edge
            UI.rect(ctx, chip.x + 3f, chip.bottom - 1.6f, chip.w - 6f, 0.8f, 0.4f, Palette.outline)
        }
        val color = when {
            listening -> Palette.accentText
            cfg.value == InputConstants.UNKNOWN.value -> Palette.textMuted
            else -> Palette.textPrimary
        }
        UI.textMidCentered(ctx, text, chip.x + chip.w / 2f, chip.y + chip.h / 2f, color, VALUE_SIZE, UIFont.Weight.MEDIUM)
    }

    override fun mouseClicked(mx: Float, my: Float, button: Int): Boolean {
        if (listening) {
            value = button
            cfg.isMouse = true
            cfg.scanCode = 0
            listening = false
            Style.playClickSound(1f)
            return true
        }
        if (isHovered(mx, my)) {
            listening = true
            Style.playClickSound(1f)
            return true
        }
        return false
    }

    override fun clearFocus() {
        listening = false
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        if (!listening) return false
        when (event.key) {
            InputConstants.KEY_ESCAPE -> {}
            InputConstants.KEY_BACKSPACE -> {
                value = InputConstants.UNKNOWN.value
                cfg.isMouse = false
            }
            else -> {
                value = event.key
                cfg.scanCode = event.scancode
                cfg.isMouse = false
            }
        }
        listening = false
        Style.playClickSound(1.1f)
        return true
    }
}
