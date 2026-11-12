package com.laybalt.ui.clickgui.components.settings.impl

import com.laybalt.LayBaltQOL.mc
import com.laybalt.config.types.SliderSetting
import com.laybalt.ui.Palette
import com.laybalt.ui.clickgui.UI
import com.laybalt.ui.clickgui.components.Rect
import com.laybalt.ui.clickgui.components.settings.Widget
import com.laybalt.ui.text.UIFont
import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent

/** Rounded track with a round knob; the value chip on the right can be clicked to type an exact value. */
class SliderWidget<T: Number>(config: SliderSetting<T>): Widget<T>(config) {
    private inline val cfg get() = config as SliderSetting<T>

    override val height get() = 38f

    private var dragging = false
    private var typingValue = false
        set(value) {
            if (field == value) return
            field = value
            mc.textInputManager().onTextInputFocusChange(value)
        }
    private var buffer = ""
    /** The current value is shown selected until the first keystroke replaces it. */
    private var replaceOnType = false

    private val fill = UI.Smooth(cfg.getPercent(config.value), 20f)
    private val knobHover = UI.Smooth()

    override val typing get() = typingValue

    private fun trackX() = x + PAD + 2f
    private fun trackW() = width - PAD * 2f - 4f
    private fun trackY() = y + 28f

    private fun chip(): Rect {
        val text = if (typingValue) buffer else cfg.stringfy(value) + cfg.suffix
        val w = (UI.width(text, VALUE_SIZE, UIFont.Weight.MEDIUM) + 12f).coerceAtLeast(if (typingValue) 40f else 24f)
        return Rect(x + width - PAD - w, y + 6f, w, 15f)
    }

    override fun draw(ctx: GuiGraphicsExtractor, mx: Float, my: Float) {
        if (dragging && !typingValue) {
            val pct = ((mx - trackX()) / trackW()).coerceIn(0f, 1f)
            value = cfg.snapToStep(cfg.min.toDouble() + (cfg.max.toDouble() - cfg.min.toDouble()) * pct)
        }
        val target = cfg.getPercent(value).coerceIn(0f, 1f)
        if (dragging) fill.snap(target) else fill.update(target)

        drawRow(ctx, mx, my, active = dragging || typingValue)
        val chip = chip()
        UI.textMid(ctx, UIFont.trim(name, chip.x - x - PAD - 6f, LABEL_SIZE, UIFont.Weight.MEDIUM), x + PAD, y + 13.5f, Palette.textPrimary, LABEL_SIZE, UIFont.Weight.MEDIUM)

        // value chip
        val chipHot = typingValue || chip.contains(mx, my)
        UI.rect(ctx, chip.x, chip.y, chip.w, chip.h, 4.5f, if (typingValue) Palette.input else if (chipHot) Palette.chip else UI.fade(Palette.chip, 0.6f))
        if (typingValue) UI.outline(ctx, chip.x, chip.y, chip.w, chip.h, 4.5f, Palette.accentBorder)
        val text = if (typingValue) buffer else cfg.stringfy(value) + cfg.suffix
        val textW = UI.width(text, VALUE_SIZE, UIFont.Weight.MEDIUM)
        val tx = chip.x + (chip.w - textW) / 2f
        UI.textMid(ctx, text, tx, chip.y + chip.h / 2f, if (typingValue) Palette.textPrimary else Palette.accentText, VALUE_SIZE, UIFont.Weight.MEDIUM)
        if (typingValue && replaceOnType && buffer.isNotEmpty()) {
            val capH = UIFont.capHeight(VALUE_SIZE)
            UI.rect(ctx, tx - 1f, chip.y + (chip.h - capH * 1.9f) / 2f, textW + 2f, capH * 1.9f, 1.5f, UI.fade(Palette.accent, 0.45f))
        }
        else if (typingValue && (System.currentTimeMillis() / 530) % 2 == 0L) {
            val capH = UIFont.capHeight(VALUE_SIZE)
            UI.rect(ctx, tx + textW + 0.5f, chip.y + (chip.h - capH * 1.6f) / 2f, 0.9f, capH * 1.6f, 0f, Palette.accent)
        }

        // track
        val tX = trackX()
        val tW = trackW()
        val tY = trackY()
        UI.rect(ctx, tX, tY - 2f, tW, 4f, 2f, Palette.input)
        val fx = tW * fill.value
        if (fx > 0.5f) UI.rect(ctx, tX, tY - 2f, fx.coerceAtLeast(4f), 4f, 2f, Palette.accent)
        val kx = tX + fx
        val kHot = knobHover.update(dragging || UI.inside(mx, my, kx - 7f, tY - 7f, 14f, 14f))
        val r = 4.5f + kHot * 1f
        if (kHot > 0.01f) UI.circle(ctx, kx, tY, r + 3f, UI.fade(Palette.accent, 0.22f * kHot))
        UI.shadow(ctx, kx - r, tY - r, r * 2f, r * 2f, r, 2f, Palette.shadowSoft, offsetY = 0.6f)
        UI.circle(ctx, kx, tY, r, Palette.switchKnob)
        UI.circle(ctx, kx, tY, r * 0.42f, Palette.accent)
    }

    override fun clearFocus() {
        typingValue = false
    }

    override fun mouseClicked(mx: Float, my: Float, button: Int): Boolean {
        if (button != InputConstants.MOUSE_BUTTON_LEFT || !isHovered(mx, my)) {
            if (typingValue) commit()
            return false
        }
        if (chip().contains(mx, my)) {
            if (!typingValue) {
                typingValue = true
                buffer = cfg.stringfy(value)
                replaceOnType = true
            }
            dragging = false
            return true
        }
        if (typingValue) commit()
        if (my >= y + 18f) dragging = true
        return true
    }

    private fun commit() {
        buffer.toDoubleOrNull()?.let { value = cfg.snapToStep(it) }
        typingValue = false
    }

    override fun mouseReleased(button: Int) {
        dragging = false
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        if (!typingValue) return false
        when (event.key) {
            InputConstants.KEY_RETURN, InputConstants.KEY_NUMPADENTER -> commit()
            InputConstants.KEY_ESCAPE -> typingValue = false
            InputConstants.KEY_BACKSPACE -> {
                buffer = if (replaceOnType) "" else buffer.dropLast(1)
                replaceOnType = false
            }
            InputConstants.KEY_LEFT, InputConstants.KEY_RIGHT, InputConstants.KEY_HOME, InputConstants.KEY_END -> replaceOnType = false
        }
        return true
    }

    override fun charTyped(event: CharacterEvent): Boolean {
        if (!typingValue) return false
        val c = event.codepoint.toChar()
        val replacing = replaceOnType
        replaceOnType = false
        if ((c.isDigit() || c == '.' || c == '-') && (replacing || buffer.length < 16)) {
            if (replacing) buffer = ""
            buffer += c
        }
        return true
    }
}
