package com.laybalt.GUI.clickgui.widget

import com.laybalt.GUI.clickgui.Style
import com.laybalt.GUI.clickgui.setting.ColorSetting
import com.laybalt.GUI.clickgui.util.Animation
import com.laybalt.GUI.clickgui.util.Render2D.drawHorizontalGradient
import com.laybalt.GUI.clickgui.util.Render2D.drawRect
import com.laybalt.GUI.clickgui.util.Render2D.drawString
import com.laybalt.GUI.clickgui.util.Render2D.drawVerticalGradient
import com.laybalt.GUI.clickgui.util.Render2D.scissor
import com.laybalt.GUI.clickgui.util.Render2D.withAlpha
import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import java.awt.Color
import java.util.Locale

/** HSB picker: saturation/brightness box, hue bar, optional alpha bar and a hex field. */
class ColorWidget(config: ColorSetting): Widget<Color>(config) {
    private companion object {
        const val HEADER = 20
        const val PICKER_SIZE = 80f
        const val EXPANDED_EXTRA = 115
    }

    private val cfg = config
    private var expanded = false
    private val openAnim = Animation(250)
    private val hoverAnim = Animation(200)

    override val height get() = HEADER + (openAnim.value * EXPANDED_EXTRA).toInt()

    private var h = 0f
    private var s = 0f
    private var b = 0f
    private var a = 1f
    private var lastSynced: Color = config.value

    private var draggingSV = false
    private var draggingHue = false
    private var draggingAlpha = false

    private var hexText = ""
    private var hexFocused = false
        set(v) {
            if (field == v) return
            field = v
            Minecraft.getInstance().textInputManager().onTextInputFocusChange(v)
        }

    init {
        syncFromValue()
    }

    private val pickerY get() = y + 25f
    private val alphaX get() = x + 10f
    private val hueX get() = alphaX + if (cfg.withAlpha) 15f else 0f
    private val svX get() = hueX + 15f
    private val svW get() = (x + width - 10f) - svX
    private val hexY get() = pickerY + PICKER_SIZE + 5f

    private fun syncFromValue() {
        val v = value
        val hsb = Color.RGBtoHSB(v.red, v.green, v.blue, null)
        h = hsb[0]; s = hsb[1]; b = hsb[2]
        a = if (cfg.withAlpha) v.alpha / 255f else 1f
        updateHexText()
        lastSynced = v
    }

    private fun updateColorFromHSB() {
        val c = Color(Color.HSBtoRGB(h, s, b)).withAlpha((a * 255).toInt())
        super.value = c
        lastSynced = c
        updateHexText()
    }

    private fun updateHexText() {
        val v = value
        hexText = (if (cfg.withAlpha) String.format(Locale.US, "%02x%02x%02x%02x", v.red, v.green, v.blue, v.alpha)
        else String.format(Locale.US, "%02x%02x%02x", v.red, v.green, v.blue)).uppercase()
    }

    override fun draw(ctx: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        if (value != lastSynced) syncFromValue()

        openAnim.update(if (expanded) 1f else 0f)
        hoverAnim.update(if (isHovered(mouseX, mouseY, HEADER)) 1f else 0f)

        Style.drawBackground(ctx, x, y, width, HEADER)
        Style.drawHoverBar(ctx, x.toFloat(), y.toFloat(), HEADER.toFloat(), hoverAnim.value)
        Style.drawNudgedText(ctx, name, x + 8f, y + 6f, hoverAnim.value)

        val previewX = x + width - 18f
        if (cfg.withAlpha) drawCheckerboard(ctx, previewX, y + 6f, 8f, 8f)
        ctx.drawRect(previewX, y + 6f, 8f, 8f, value)

        if (openAnim.value <= 0.01f) return

        ctx.scissor(x, y, width, height)
        handleDrag(mouseX, mouseY)

        if (cfg.withAlpha) drawAlphaBar(ctx, alphaX, pickerY, 10f, PICKER_SIZE)
        drawHueBar(ctx, hueX, pickerY, 10f, PICKER_SIZE)
        drawSVBox(ctx, svX, pickerY, svW, PICKER_SIZE)

        val hexW = width - 20f
        ctx.drawRect(x + 10f, hexY, hexW, 12f, Color(10, 10, 10, 200))
        if (hexFocused) ctx.drawRect(x + 10f, hexY + 11f, hexW, 1f, Style.accentColor)
        val cursor = if (hexFocused && (System.currentTimeMillis() / 500) % 2 == 0L) "|" else ""
        ctx.drawString("Hex: §7#$hexText$cursor", x + 14f, hexY + 2f)
        ctx.disableScissor()
    }

    private fun handleDrag(mx: Int, my: Int) {
        if (hexFocused || (!draggingSV && !draggingHue && !draggingAlpha)) return

        if (draggingAlpha) a = (1f - (my - pickerY) / PICKER_SIZE).coerceIn(0f, 1f)
        if (draggingHue) h = ((my - pickerY) / PICKER_SIZE).coerceIn(0f, 1f)
        if (draggingSV) {
            s = ((mx - svX) / svW).coerceIn(0f, 1f)
            b = (1f - (my - pickerY) / PICKER_SIZE).coerceIn(0f, 1f)
        }
        updateColorFromHSB()
    }

    private fun drawSVBox(ctx: GuiGraphicsExtractor, sx: Float, sy: Float, sw: Float, sh: Float) {
        ctx.drawRect(sx, sy, sw, sh, Color.getHSBColor(h, 1f, 1f))
        ctx.drawHorizontalGradient(sx, sy, sw, sh, Color.WHITE, Color(255, 255, 255, 0))
        ctx.drawVerticalGradient(sx, sy, sw, sh, Color(0, 0, 0, 0), Color.BLACK)

        val ix = sx + s * sw
        val iy = sy + (1f - b) * sh
        ctx.drawRect(ix - 1.5f, iy - 1.5f, 3f, 3f)
        ctx.drawRect(ix - 0.5f, iy - 0.5f, 1f, 1f, Color.BLACK)
    }

    private fun drawHueBar(ctx: GuiGraphicsExtractor, hx: Float, hy: Float, hw: Float, hh: Float) {
        val segments = 12
        val step = hh / segments
        for (i in 0 until segments) {
            val top = Color.getHSBColor(i / segments.toFloat(), 1f, 1f)
            val bottom = Color.getHSBColor((i + 1) / segments.toFloat(), 1f, 1f)
            ctx.drawVerticalGradient(hx, hy + i * step, hw, step + 0.5f, top, bottom)
        }
        ctx.drawRect(hx - 1f, hy + h * hh - 0.5f, hw + 2f, 1f)
    }

    private fun drawAlphaBar(ctx: GuiGraphicsExtractor, ax: Float, ay: Float, aw: Float, ah: Float) {
        drawCheckerboard(ctx, ax, ay, aw, ah)
        val base = Color(Color.HSBtoRGB(h, s, b))
        ctx.drawVerticalGradient(ax, ay, aw, ah, base.withAlpha(255), base.withAlpha(0))
        ctx.drawRect(ax - 1f, ay + (1f - a) * ah - 0.5f, aw + 2f, 1f)
    }

    private fun drawCheckerboard(ctx: GuiGraphicsExtractor, cx: Float, cy: Float, cw: Float, ch: Float, size: Int = 2) {
        for (i in 0 until (cw / size).toInt()) {
            for (j in 0 until (ch / size).toInt()) {
                val color = if ((i + j) % 2 == 0) Color(50, 50, 50, 200) else Color(30, 30, 30, 200)
                ctx.drawRect(cx + i * size, cy + j * size, size, size, color)
            }
        }
    }

    override fun charTyped(codePoint: Char): Boolean {
        if (!expanded || !hexFocused) return false
        val maxLength = if (cfg.withAlpha) 8 else 6
        if (codePoint.lowercaseChar() in "0123456789abcdef" && hexText.length < maxLength) {
            hexText += codePoint.uppercaseChar()
            tryUpdateFromHex()
        }
        return true
    }

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        if (!expanded || !hexFocused) return false
        if (keyCode == InputConstants.KEY_BACKSPACE && hexText.isNotEmpty()) hexText = hexText.dropLast(1)
        if (keyCode == InputConstants.KEY_RETURN || keyCode == InputConstants.KEY_ESCAPE) hexFocused = false
        return true
    }

    private fun tryUpdateFromHex() {
        val required = if (cfg.withAlpha) 8 else 6
        if (hexText.length != required) return
        val longVal = hexText.toLongOrNull(16) ?: return

        val c = if (cfg.withAlpha) Color(
            ((longVal shr 24) and 0xFF).toInt(),
            ((longVal shr 16) and 0xFF).toInt(),
            ((longVal shr 8) and 0xFF).toInt(),
            (longVal and 0xFF).toInt()
        )
        else Color((longVal and 0xFFFFFF).toInt())

        val hsb = Color.RGBtoHSB(c.red, c.green, c.blue, null)
        h = hsb[0]; s = hsb[1]; b = hsb[2]
        a = c.alpha / 255f
        super.value = c
        lastSynced = c
    }

    override fun clearFocus() {
        hexFocused = false
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (button != InputConstants.MOUSE_BUTTON_LEFT) return false

        if (isHovered(mouseX, mouseY, HEADER)) {
            expanded = !expanded
            if (!expanded) clearFocus()
            Style.playClickSound(1f)
            return true
        }
        if (!expanded) return false

        val inPickerY = mouseY >= pickerY && mouseY <= pickerY + PICKER_SIZE
        draggingAlpha = cfg.withAlpha && inPickerY && mouseX >= alphaX && mouseX <= alphaX + 10
        draggingHue = inPickerY && mouseX >= hueX && mouseX <= hueX + 10
        draggingSV = inPickerY && mouseX >= svX && mouseX <= svX + svW
        if (draggingAlpha || draggingHue || draggingSV) {
            hexFocused = false
            return true
        }

        hexFocused = mouseX >= x + 10 && mouseX <= x + width - 10 && mouseY >= hexY && mouseY <= hexY + 12
        return hexFocused
    }

    override fun mouseReleased(button: Int) {
        if (button != InputConstants.MOUSE_BUTTON_LEFT) return
        draggingSV = false
        draggingHue = false
        draggingAlpha = false
    }
}
