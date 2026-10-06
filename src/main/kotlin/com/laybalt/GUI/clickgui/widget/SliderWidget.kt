package com.laybalt.GUI.clickgui.widget

import com.laybalt.GUI.clickgui.Style
import com.laybalt.GUI.clickgui.setting.SliderSetting
import com.laybalt.GUI.clickgui.util.Animation
import com.laybalt.GUI.clickgui.util.Render2D.drawString
import com.laybalt.GUI.clickgui.util.Render2D.width
import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import java.awt.Color
import kotlin.math.abs

/** Drag the bar, or click the number to type an exact value (Enter to apply). */
class SliderWidget(config: SliderSetting): Widget<Int>(config) {
    private val cfg = config

    private var dragging = false
    private var inputBuffer = ""
    private var isTyping = false
        set(v) {
            if (field == v) return
            field = v
            Minecraft.getInstance().textInputManager().onTextInputFocusChange(v)
        }

    private val hoverAnim = Animation(200)
    private val sliderAnim = Animation(250, cfg.percent(config.value))

    override fun draw(ctx: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val target = cfg.percent(value)

        if (dragging && !isTyping) {
            val pct = ((mouseX - (x + 8.0)) / (width - 16.0)).coerceIn(0.0, 1.0)
            value = cfg.snap(cfg.min + (cfg.max - cfg.min) * pct)
            if (abs(sliderAnim.value - target) < 0.05f) sliderAnim.set(target)
        }

        hoverAnim.update(if (isHovered(mouseX, mouseY) || dragging || isTyping) 1f else 0f)
        sliderAnim.update(cfg.percent(value))

        Style.drawBackground(ctx, x, y, width, height)
        Style.drawHoverBar(ctx, x.toFloat(), y.toFloat(), height.toFloat(), hoverAnim.value)
        Style.drawNudgedText(ctx, name, x + 8f, y + 2f, hoverAnim.value)

        val valueText = if (isTyping) "$inputBuffer${if ((System.currentTimeMillis() / 500) % 2 == 0L) "_" else ""}" else "$value${cfg.suffix}"
        val textColor = if (isTyping) Style.accentColor else Color(180, 180, 180)
        ctx.drawString(valueText, x + width - valueText.width() - 8f, y + 2f, textColor)

        Style.drawSlider(ctx, x + 8f, y + 14f, width - 16f, sliderAnim.value, Style.accentColor)
    }

    override fun clearFocus() {
        isTyping = false
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (button != InputConstants.MOUSE_BUTTON_LEFT || !isHovered(mouseX, mouseY)) {
            isTyping = false
            return false
        }

        val textX = x + width - "$value${cfg.suffix}".width() - 8f
        if (mouseX >= textX && mouseY <= y + 12f) {
            isTyping = true
            inputBuffer = value.toString()
            dragging = false
        }
        else {
            isTyping = false
            dragging = true
        }
        return true
    }

    override fun mouseReleased(button: Int) {
        dragging = false
    }

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        if (!isTyping) return false
        when (keyCode) {
            InputConstants.KEY_RETURN, InputConstants.KEY_NUMPADENTER -> {
                inputBuffer.toDoubleOrNull()?.let { value = cfg.snap(it) }
                isTyping = false
            }
            InputConstants.KEY_ESCAPE -> isTyping = false
            InputConstants.KEY_BACKSPACE -> inputBuffer = inputBuffer.dropLast(1)
        }
        return true
    }

    override fun charTyped(codePoint: Char): Boolean {
        if (!isTyping) return false
        if (codePoint.isDigit() || codePoint == '-') inputBuffer += codePoint
        return true
    }
}
