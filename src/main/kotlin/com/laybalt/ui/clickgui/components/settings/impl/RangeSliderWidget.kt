package com.laybalt.ui.clickgui.components.settings.impl

import com.laybalt.LayBaltQOL.mc
import com.laybalt.config.types.RangeSliderSetting
import com.laybalt.ui.Palette
import com.laybalt.ui.clickgui.UI
import com.laybalt.ui.clickgui.components.Rect
import com.laybalt.ui.clickgui.components.settings.Widget
import com.laybalt.ui.text.UIFont
import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import kotlin.math.abs

/**
 * [SliderWidget] with two knobs (NoammAddons RangeSliderWidget, drawn the LayBalt way): a press on the track drags the
 * nearer knob, and a knob cannot pass the other one. The value chip can be clicked to type "120-150" or a single value.
 */
class RangeSliderWidget<T: Number>(config: RangeSliderSetting<T>): Widget<Pair<T, T>>(config) {
    private inline val cfg get() = config as RangeSliderSetting<T>

    override val height get() = 38f

    private enum class Knob { START, END }

    private var dragging: Knob? = null
    private var typingValue = false
        set(value) {
            if (field == value) return
            field = value
            mc.textInputManager().onTextInputFocusChange(value)
        }
    private var buffer = ""
    /** The current value is shown selected until the first keystroke replaces it. */
    private var replaceOnType = false

    private val startFill = UI.Smooth(cfg.getPercent(config.value.first), 20f)
    private val endFill = UI.Smooth(cfg.getPercent(config.value.second), 20f)
    private val startHover = UI.Smooth()
    private val endHover = UI.Smooth()

    override val typing get() = typingValue

    private fun trackX() = x + PAD + 2f
    private fun trackW() = width - PAD * 2f - 4f
    private fun trackY() = y + 28f

    private fun editText() =
        if (value.first.toDouble() == value.second.toDouble()) cfg.stringfy(value.first)
        else "${cfg.stringfy(value.first)}-${cfg.stringfy(value.second)}"

    private fun chip(): Rect {
        val text = if (typingValue) buffer else cfg.display(value)
        val w = (UI.width(text, VALUE_SIZE, UIFont.Weight.MEDIUM) + 12f).coerceAtLeast(if (typingValue) 52f else 24f)
        return Rect(x + width - PAD - w, y + 6f, w, 15f)
    }

    private fun valueAt(mx: Float): T {
        val pct = ((mx - trackX()) / trackW()).coerceIn(0f, 1f)
        return cfg.snapToStep(cfg.min.toDouble() + (cfg.max.toDouble() - cfg.min.toDouble()) * pct)
    }

    override fun draw(ctx: GuiGraphicsExtractor, mx: Float, my: Float) {
        val knob = dragging
        if (knob != null && ! typingValue) {
            val snapped = valueAt(mx)
            value = when (knob) {
                Knob.START -> if (snapped.toDouble() > value.second.toDouble()) value.second to value.second else snapped to value.second
                Knob.END -> if (snapped.toDouble() < value.first.toDouble()) value.first to value.first else value.first to snapped
            }
        }
        val startTarget = cfg.getPercent(value.first).coerceIn(0f, 1f)
        val endTarget = cfg.getPercent(value.second).coerceIn(0f, 1f)
        if (knob != null) {
            startFill.snap(startTarget)
            endFill.snap(endTarget)
        }
        else {
            startFill.update(startTarget)
            endFill.update(endTarget)
        }

        drawRow(ctx, mx, my, active = knob != null || typingValue)
        val chip = chip()
        UI.textMid(ctx, UIFont.trim(name, chip.x - x - PAD - 6f, LABEL_SIZE, UIFont.Weight.MEDIUM), x + PAD, y + 13.5f, Palette.textPrimary, LABEL_SIZE, UIFont.Weight.MEDIUM)

        // value chip
        val chipHot = typingValue || chip.contains(mx, my)
        UI.rect(ctx, chip.x, chip.y, chip.w, chip.h, 4.5f, if (typingValue) Palette.input else if (chipHot) Palette.chip else UI.fade(Palette.chip, 0.6f))
        if (typingValue) UI.outline(ctx, chip.x, chip.y, chip.w, chip.h, 4.5f, Palette.accentBorder)
        val text = if (typingValue) buffer else cfg.display(value)
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

        // track, with the chosen range filled
        val tX = trackX()
        val tW = trackW()
        val tY = trackY()
        UI.rect(ctx, tX, tY - 2f, tW, 4f, 2f, Palette.input)
        val sx = tX + tW * startFill.value
        val ex = tX + tW * endFill.value
        UI.rect(ctx, sx - 2f, tY - 2f, (ex - sx + 4f).coerceAtLeast(4f), 4f, 2f, Palette.accent)

        drawKnob(ctx, sx, tY, startHover.update(knob == Knob.START || (knob == null && UI.inside(mx, my, sx - 7f, tY - 7f, 14f, 14f))))
        drawKnob(ctx, ex, tY, endHover.update(knob == Knob.END || (knob == null && UI.inside(mx, my, ex - 7f, tY - 7f, 14f, 14f))))
    }

    private fun drawKnob(ctx: GuiGraphicsExtractor, kx: Float, ky: Float, hot: Float) {
        val r = 4.5f + hot * 1f
        if (hot > 0.01f) UI.circle(ctx, kx, ky, r + 3f, UI.fade(Palette.accent, 0.22f * hot))
        UI.shadow(ctx, kx - r, ky - r, r * 2f, r * 2f, r, 2f, Palette.shadowSoft, offsetY = 0.6f)
        UI.circle(ctx, kx, ky, r, Palette.switchKnob)
        UI.circle(ctx, kx, ky, r * 0.42f, Palette.accent)
    }

    override fun clearFocus() {
        typingValue = false
    }

    override fun mouseClicked(mx: Float, my: Float, button: Int): Boolean {
        if (button != InputConstants.MOUSE_BUTTON_LEFT || ! isHovered(mx, my)) {
            if (typingValue) commit()
            return false
        }
        if (chip().contains(mx, my)) {
            if (! typingValue) {
                typingValue = true
                buffer = editText()
                replaceOnType = true
            }
            dragging = null
            return true
        }
        if (typingValue) commit()
        if (my >= y + 18f) dragging = pickKnob(mx)
        return true
    }

    /** The knob nearer to [mx]; when both sit on one spot, the side of the press decides (left moves the start). */
    private fun pickKnob(mx: Float): Knob {
        val pct = ((mx - trackX()) / trackW()).coerceIn(0f, 1f)
        val startPct = cfg.getPercent(value.first)
        val endPct = cfg.getPercent(value.second)
        val pickStart = if (startPct == endPct) pct <= startPct else abs(pct - startPct) <= abs(pct - endPct)
        return if (pickStart) Knob.START else Knob.END
    }

    private fun commit() {
        cfg.parse(buffer)?.let { value = it }
        typingValue = false
    }

    override fun mouseReleased(button: Int) {
        dragging = null
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        if (! typingValue) return false
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
        if (! typingValue) return false
        val c = event.codepoint.toChar()
        val replacing = replaceOnType
        replaceOnType = false
        if ((c.isDigit() || c == '.' || c == '-' || c == ' ' || c == ',') && (replacing || buffer.length < 24)) {
            if (replacing) buffer = ""
            buffer += c
        }
        return true
    }
}
