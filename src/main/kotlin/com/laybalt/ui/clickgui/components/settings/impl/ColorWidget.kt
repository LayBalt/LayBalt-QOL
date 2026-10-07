package com.laybalt.ui.clickgui.components.settings.impl

import com.laybalt.LayBaltQOL.mc
import com.laybalt.config.types.ColorSetting
import com.laybalt.ui.Palette
import com.laybalt.ui.clickgui.UI
import com.laybalt.ui.clickgui.components.Popover
import com.laybalt.ui.clickgui.components.Popovers
import com.laybalt.ui.clickgui.components.Rect
import com.laybalt.ui.clickgui.components.settings.Style
import com.laybalt.ui.clickgui.components.settings.Widget
import com.laybalt.ui.render.Shapes.roundedRectGradient
import com.laybalt.ui.render.Shapes.roundedRectHorizontalGradient
import com.laybalt.ui.text.UIFont
import com.laybalt.utils.ColorUtils.withAlpha
import com.laybalt.utils.catch
import com.laybalt.utils.render.Render2D.drawVerticalGradient
import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import java.awt.Color
import java.util.*

/** Swatch + hex on the row; a popover holds the HSV square, hue and alpha bars and a hex field. */
class ColorWidget(config: ColorSetting): Widget<Color>(config) {
    private inline val cfg get() = config as ColorSetting

    private var h = 0f
    private var s = 0f
    private var b = 0f
    private var a = 1f
    private var lastSynced = config.value

    private var box = Rect(0f, 0f, 0f, 0f)
    private val picker = Picker()

    init {
        syncFromValue()
    }

    override val typing get() = picker.hexFocused

    private fun hexOf(c: Color) = (if (cfg.withAlpha) String.format(Locale.US, "%02X%02X%02X%02X", c.red, c.green, c.blue, c.alpha)
    else String.format(Locale.US, "%02X%02X%02X", c.red, c.green, c.blue))

    private fun syncFromValue() {
        val hsb = Color.RGBtoHSB(value.red, value.green, value.blue, null)
        // Keep the hue while the colour is grey/black so dragging the square does not jump the hue bar.
        if (hsb[1] > 0.001f && hsb[2] > 0.001f) h = hsb[0]
        s = hsb[1]
        b = hsb[2]
        a = value.alpha / 255f
        picker.hexText = hexOf(value)
        lastSynced = value
    }

    private fun updateFromHsb() {
        value = Color(Color.HSBtoRGB(h, s, b)).withAlpha((a * 255).toInt())
        lastSynced = value
        picker.hexText = hexOf(value)
    }

    override fun draw(ctx: GuiGraphicsExtractor, mx: Float, my: Float) {
        if (value != lastSynced) syncFromValue()
        val open = Popovers.isOpen(picker)
        drawRow(ctx, mx, my, active = open)
        val text = "#" + hexOf(value)
        box = Rect(x + width - PAD - 84f + 3f, y + 4f, 84f, 18f)
        drawLabel(ctx, box.x - x - PAD - 6f)
        val hot = open || box.contains(mx, my)
        UI.rect(ctx, box.x, box.y, box.w, box.h, 5f, if (hot) Palette.inputHover else Palette.input)
        UI.outline(ctx, box.x, box.y, box.w, box.h, 5f, if (open) Palette.accentBorder else if (hot) Palette.outlineHover else Palette.outline)
        drawSwatch(ctx, box.x + 4f, box.y + 3f, 12f, 12f, value)
        UI.textMid(ctx, text, box.x + 21f, box.y + box.h / 2f, if (open) Palette.textPrimary else Palette.textSecondary, VALUE_SIZE - 0.4f, UIFont.Weight.MEDIUM)
    }

    private fun drawSwatch(ctx: GuiGraphicsExtractor, x: Float, y: Float, w: Float, h: Float, color: Color) {
        if (color.alpha < 255) drawChecker(ctx, x, y, w, h, 3f)
        UI.rect(ctx, x, y, w, h, 3f, color)
        UI.outline(ctx, x, y, w, h, 3f, Palette.outlineHover)
    }

    private fun drawChecker(ctx: GuiGraphicsExtractor, x: Float, y: Float, w: Float, h: Float, cell: Float) {
        UI.rect(ctx, x, y, w, h, 3f, Color(205, 200, 215))
        var i = 0
        var cy = y
        while (cy < y + h - 0.01f) {
            var cx = x + if (i % 2 == 0) 0f else cell
            val ch = cell.coerceAtMost(y + h - cy)
            while (cx < x + w - 0.01f) {
                UI.rect(ctx, cx, cy, cell.coerceAtMost(x + w - cx), ch, 0f, Color(150, 144, 165))
                cx += cell * 2f
            }
            cy += cell
            i++
        }
    }

    override fun clearFocus() {
        picker.hexFocused = false
    }

    override fun mouseClicked(mx: Float, my: Float, button: Int): Boolean {
        if (button != InputConstants.MOUSE_BUTTON_LEFT || !isHovered(mx, my)) return false
        if (value != lastSynced) syncFromValue()
        Popovers.open(picker)
        Style.playClickSound(1f)
        return true
    }

    private inner class Picker: Popover() {
        var hexText = ""
        var hexFocused = false
            set(v) {
                if (field == v) return
                field = v
                mc.textInputManager().onTextInputFocusChange(v)
            }

        private var dragSV = false
        private var dragHue = false
        private var dragAlpha = false

        private val pad = 9f
        private val svH = 100f
        private val bar = 10f
        private val barGap = 7f

        override val width get() = 176f
        override val height get() = pad + svH + 8f + 18f + pad

        override fun anchor() = box

        private fun bars() = if (cfg.withAlpha) 2 else 1
        private fun svW() = width - pad * 2f - bars() * (bar + barGap)
        private fun svX() = x + pad
        private fun svY() = y + pad
        private fun hueX() = svX() + svW() + barGap
        private fun alphaX() = hueX() + bar + barGap
        private fun hexRect() = Rect(x + pad + 24f, svY() + svH + 8f, width - pad * 2f - 24f, 18f)

        override fun drawContent(ctx: GuiGraphicsExtractor, mx: Float, my: Float) {
            if (value != lastSynced) syncFromValue()
            handleDrag(mx, my)

            val sx = svX(); val sy = svY(); val sw = svW()
            val hue = Color(Color.HSBtoRGB(h, 1f, 1f))
            ctx.roundedRectHorizontalGradient(sx, sy, sw, svH, 5f, UI.c(Color.WHITE), UI.c(hue))
            ctx.roundedRectGradient(sx, sy, sw, svH, 5f, UI.c(Color(0, 0, 0, 0)), UI.c(Color.BLACK))
            UI.outline(ctx, sx, sy, sw, svH, 5f, Palette.outline)
            val ix = sx + s * sw
            val iy = sy + (1f - b) * svH
            UI.circle(ctx, ix, iy, 5f, Color(255, 255, 255))
            UI.circle(ctx, ix, iy, 3.6f, Color(Color.HSBtoRGB(h, s, b)))

            // hue bar
            val hx = hueX()
            val segments = 12
            val step = svH / segments
            for (i in 0 until segments) {
                val c1 = Color.getHSBColor(i / segments.toFloat(), 1f, 1f)
                val c2 = Color.getHSBColor((i + 1) / segments.toFloat(), 1f, 1f)
                ctx.drawVerticalGradient(hx, sy + i * step, bar, step + 0.3f, UI.c(c1), UI.c(c2))
            }
            UI.outline(ctx, hx, sy, bar, svH, 2f, Palette.outline)
            drawHandle(ctx, hx, sy + h * svH)

            if (cfg.withAlpha) {
                val ax = alphaX()
                drawChecker(ctx, ax, sy, bar, svH, 3.333f)
                val base = Color(Color.HSBtoRGB(h, s, b))
                ctx.drawVerticalGradient(ax, sy, bar, svH, UI.c(base.withAlpha(255)), UI.c(base.withAlpha(0)))
                UI.outline(ctx, ax, sy, bar, svH, 2f, Palette.outline)
                drawHandle(ctx, ax, sy + (1f - a) * svH)
            }

            // preview + hex field
            val hr = hexRect()
            drawSwatch(ctx, x + pad, hr.y, 18f, 18f, value)
            UI.rect(ctx, hr.x, hr.y, hr.w, hr.h, 5f, Palette.input)
            UI.outline(ctx, hr.x, hr.y, hr.w, hr.h, 5f, if (hexFocused) Palette.accentBorder else if (hr.contains(mx, my)) Palette.outlineHover else Palette.outline)
            UI.textMid(ctx, "#", hr.x + 7f, hr.y + hr.h / 2f, Palette.textMuted, VALUE_SIZE, UIFont.Weight.MEDIUM)
            val tx = hr.x + 7f + UI.width("#", VALUE_SIZE, UIFont.Weight.MEDIUM) + 1.5f
            UI.textMid(ctx, hexText, tx, hr.y + hr.h / 2f, Palette.textPrimary, VALUE_SIZE, UIFont.Weight.MEDIUM)
            if (hexFocused && (System.currentTimeMillis() / 530) % 2 == 0L) {
                val capH = UIFont.capHeight(VALUE_SIZE)
                UI.rect(ctx, tx + UI.width(hexText, VALUE_SIZE, UIFont.Weight.MEDIUM) + 0.6f, hr.y + (hr.h - capH * 1.6f) / 2f, 0.9f, capH * 1.6f, 0f, Palette.accent)
            }
            val label = if (cfg.withAlpha) "RGBA" else "RGB"
            UI.textMidRight(ctx, label, hr.right - 7f, hr.y + hr.h / 2f, Palette.textMuted, 6.2f, UIFont.Weight.MEDIUM)
        }

        private fun drawHandle(ctx: GuiGraphicsExtractor, bx: Float, cy: Float) {
            UI.shadow(ctx, bx - 2f, cy - 2.5f, bar + 4f, 5f, 2.5f, 2f, Palette.shadowStrong)
            UI.rect(ctx, bx - 2f, cy - 2.5f, bar + 4f, 5f, 2.5f, Color(255, 255, 255))
        }

        private fun handleDrag(mx: Float, my: Float) {
            if (!dragSV && !dragHue && !dragAlpha) return
            val sy = svY()
            if (dragAlpha) a = (1f - (my - sy) / svH).coerceIn(0f, 1f)
            if (dragHue) h = ((my - sy) / svH).coerceIn(0f, 0.9999f)
            if (dragSV) {
                s = ((mx - svX()) / svW()).coerceIn(0f, 1f)
                b = (1f - (my - sy) / svH).coerceIn(0f, 1f)
            }
            updateFromHsb()
        }

        override fun mouseClicked(mx: Float, my: Float, button: Int): Boolean {
            if (button != InputConstants.MOUSE_BUTTON_LEFT) return true
            val sy = svY()
            val inY = my >= sy - 3f && my <= sy + svH + 3f
            dragSV = inY && mx >= svX() && mx <= svX() + svW()
            dragHue = inY && mx >= hueX() - 2f && mx <= hueX() + bar + 2f
            dragAlpha = cfg.withAlpha && inY && mx >= alphaX() - 2f && mx <= alphaX() + bar + 2f
            if (dragSV || dragHue || dragAlpha) {
                hexFocused = false
                handleDrag(mx, my)
                return true
            }
            hexFocused = hexRect().contains(mx, my)
            return true
        }

        override fun mouseReleased(button: Int) {
            dragSV = false
            dragHue = false
            dragAlpha = false
        }

        override fun keyPressed(event: KeyEvent): Boolean {
            if (!hexFocused) return false
            when (event.key) {
                InputConstants.KEY_BACKSPACE -> if (hexText.isNotEmpty()) {
                    hexText = hexText.dropLast(1)
                    tryHex()
                }
                InputConstants.KEY_RETURN, InputConstants.KEY_NUMPADENTER, InputConstants.KEY_ESCAPE -> hexFocused = false
                InputConstants.KEY_V -> if (event.hasControlDown()) {
                    val pasted = mc.keyboardHandler.clipboard.trim().removePrefix("#").filter { it in "0123456789ABCDEFabcdef" }.uppercase()
                    if (pasted.isNotEmpty()) {
                        hexText = pasted.take(if (cfg.withAlpha) 8 else 6)
                        tryHex()
                    }
                }
            }
            return true
        }

        override fun charTyped(event: CharacterEvent): Boolean {
            if (!hexFocused) return false
            val c = event.codepoint.toChar()
            if (c in "0123456789ABCDEFabcdef" && hexText.length < (if (cfg.withAlpha) 8 else 6)) {
                hexText += c.uppercaseChar()
                tryHex()
            }
            return true
        }

        private fun tryHex() {
            val required = if (cfg.withAlpha) 8 else 6
            if (hexText.length != required) return
            catch {
                val v = hexText.toLong(16)
                val c = if (cfg.withAlpha) Color(((v shr 24) and 0xFF).toInt(), ((v shr 16) and 0xFF).toInt(), ((v shr 8) and 0xFF).toInt(), (v and 0xFF).toInt())
                else Color((v and 0xFFFFFFL).toInt())
                val hsb = Color.RGBtoHSB(c.red, c.green, c.blue, null)
                h = hsb[0]; s = hsb[1]; b = hsb[2]
                a = c.alpha / 255f
                value = c
                lastSynced = c
            }
        }

        override fun onClose() {
            hexFocused = false
            mouseReleased(0)
        }
    }
}
