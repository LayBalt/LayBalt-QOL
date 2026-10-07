package com.laybalt.ui.clickgui

import com.laybalt.ui.Palette
import com.laybalt.ui.render.Icon
import com.laybalt.ui.render.Icons
import com.laybalt.ui.render.Shapes.circle
import com.laybalt.ui.render.Shapes.line
import com.laybalt.ui.render.Shapes.pixel
import com.laybalt.ui.render.Shapes.roundedOutline
import com.laybalt.ui.render.Shapes.roundedRect
import com.laybalt.ui.render.Shapes.shadow
import com.laybalt.ui.text.UIFont
import com.laybalt.utils.render.Render2D.drawVerticalGradient
import net.minecraft.client.gui.GuiGraphicsExtractor
import java.awt.Color
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Drawing kit of the LayBalt screens: thin wrappers over [com.laybalt.ui.render.Shapes], [Icons] and [UIFont] that
 * apply the screen-wide fade ([alpha]) to every colour, plus the shared controls (switch, chips, buttons).
 */
object UI {
    /** Screen-wide opacity multiplier (open/close fade). */
    var alpha = 1f

    /** [color] with the current [alpha] applied. */
    fun c(color: Color): Color {
        if (alpha >= 0.999f) return color
        return Color(color.red, color.green, color.blue, (color.alpha * alpha).roundToInt().coerceIn(0, 255))
    }

    fun c(color: Color, extraAlpha: Float): Color =
        Color(color.red, color.green, color.blue, (color.alpha * alpha * extraAlpha).roundToInt().coerceIn(0, 255))

    /** [color] with its alpha multiplied by [f] (does not apply the screen fade; drawing helpers do that). */
    fun fade(color: Color, f: Float): Color =
        Color(color.red, color.green, color.blue, (color.alpha * f).roundToInt().coerceIn(0, 255))

    fun mix(a: Color, b: Color, t: Float): Color {
        val k = t.coerceIn(0f, 1f)
        fun ch(x: Int, y: Int) = (x + (y - x) * k).roundToInt().coerceIn(0, 255)
        return Color(ch(a.red, b.red), ch(a.green, b.green), ch(a.blue, b.blue), ch(a.alpha, b.alpha))
    }

    /** One hairline (1 physical pixel, 2 at very high scales) in the current pose's units. */
    fun hair(ctx: GuiGraphicsExtractor): Float {
        val p = ctx.pixel()
        return p * max(1, (1f / p / 2.5f).roundToInt())
    }

    fun inside(mx: Float, my: Float, x: Float, y: Float, w: Float, h: Float) = mx >= x && mx < x + w && my >= y && my < y + h

    // ---------------------------------------------------------------- primitives

    fun rect(ctx: GuiGraphicsExtractor, x: Float, y: Float, w: Float, h: Float, r: Float, color: Color) {
        if (w <= 0f || h <= 0f) return
        ctx.roundedRect(x, y, w, h, r, c(color))
    }

    fun rect(ctx: GuiGraphicsExtractor, x: Float, y: Float, w: Float, h: Float, tl: Float, tr: Float, br: Float, bl: Float, color: Color) {
        if (w <= 0f || h <= 0f) return
        ctx.roundedRect(x, y, w, h, tl, tr, br, bl, c(color))
    }

    fun outline(ctx: GuiGraphicsExtractor, x: Float, y: Float, w: Float, h: Float, r: Float, color: Color, thickness: Float = hair(ctx)) {
        if (w <= 0f || h <= 0f) return
        ctx.roundedOutline(x, y, w, h, r, thickness, c(color))
    }

    fun outline(ctx: GuiGraphicsExtractor, x: Float, y: Float, w: Float, h: Float, tl: Float, tr: Float, br: Float, bl: Float, color: Color, thickness: Float = hair(ctx)) {
        if (w <= 0f || h <= 0f) return
        ctx.roundedOutline(x, y, w, h, tl, tr, br, bl, thickness, c(color))
    }

    fun shadow(ctx: GuiGraphicsExtractor, x: Float, y: Float, w: Float, h: Float, r: Float, blur: Float, color: Color, offsetX: Float = 0f, offsetY: Float = 0f, spread: Float = 0f) =
        ctx.shadow(x, y, w, h, r, blur, c(color), offsetX, offsetY, spread)

    fun circle(ctx: GuiGraphicsExtractor, cx: Float, cy: Float, r: Float, color: Color) = ctx.circle(cx, cy, r, c(color))

    fun line(ctx: GuiGraphicsExtractor, x1: Float, y1: Float, x2: Float, y2: Float, color: Color, thickness: Float = hair(ctx)) =
        ctx.line(x1, y1, x2, y2, thickness, c(color), roundCaps = false)

    fun hLine(ctx: GuiGraphicsExtractor, x: Float, y: Float, w: Float, color: Color) =
        ctx.roundedRect(x, y, w, hair(ctx), 0f, c(color))

    fun vGradient(ctx: GuiGraphicsExtractor, x: Float, y: Float, w: Float, h: Float, top: Color, bottom: Color) =
        ctx.drawVerticalGradient(x, y, w, h, c(top), c(bottom))

    fun icon(ctx: GuiGraphicsExtractor, icon: Icon, x: Float, y: Float, size: Float, color: Color, stroke: Float = 1.6f) =
        Icons.draw(ctx, icon, x, y, size, c(color), stroke)

    // ---------------------------------------------------------------- text

    fun text(ctx: GuiGraphicsExtractor, text: String, x: Float, y: Float, color: Color, size: Float = UIFont.DEFAULT_SIZE, weight: UIFont.Weight = UIFont.Weight.REGULAR) =
        UIFont.draw(ctx, text, x, y, c(color), size, weight)

    /** Text whose line box is vertically centred on [centerY]. */
    fun textMid(ctx: GuiGraphicsExtractor, text: String, x: Float, centerY: Float, color: Color, size: Float = UIFont.DEFAULT_SIZE, weight: UIFont.Weight = UIFont.Weight.REGULAR) =
        UIFont.drawMiddle(ctx, text, x, centerY, c(color), size, weight)

    fun textMidRight(ctx: GuiGraphicsExtractor, text: String, rightX: Float, centerY: Float, color: Color, size: Float = UIFont.DEFAULT_SIZE, weight: UIFont.Weight = UIFont.Weight.REGULAR) =
        UIFont.drawMiddle(ctx, text, rightX - UIFont.width(text, size, weight), centerY, c(color), size, weight)

    fun textMidCentered(ctx: GuiGraphicsExtractor, text: String, centerX: Float, centerY: Float, color: Color, size: Float = UIFont.DEFAULT_SIZE, weight: UIFont.Weight = UIFont.Weight.REGULAR) =
        UIFont.drawMiddle(ctx, text, centerX - UIFont.width(text, size, weight) / 2f, centerY, c(color), size, weight)

    fun width(text: String, size: Float = UIFont.DEFAULT_SIZE, weight: UIFont.Weight = UIFont.Weight.REGULAR) = UIFont.width(text, size, weight)

    // ---------------------------------------------------------------- controls

    const val SWITCH_W = 22f
    const val SWITCH_H = 12f

    /**
     * iOS-style switch with its top-left at ([x], [y]). [progress] is the animated on-state (0 off .. 1 on),
     * [hover] brightens it, a disabled switch is drawn flat with a grey knob.
     */
    fun switch(ctx: GuiGraphicsExtractor, x: Float, y: Float, progress: Float, hover: Float = 0f, enabled: Boolean = true, w: Float = SWITCH_W, h: Float = SWITCH_H) {
        val r = h / 2f
        if (!enabled) {
            rect(ctx, x, y, w, h, r, Palette.switchOff.darker())
            circle(ctx, x + r, y + r, r - 2f, Palette.switchKnobDisabled)
            return
        }
        val off = mix(Palette.switchOff, Palette.switchOff.brighter(), hover * 0.35f)
        val on = mix(Palette.accent, Palette.accentText, hover * 0.25f)
        rect(ctx, x, y, w, h, r, mix(off, on, progress))
        val knobR = r - 2f
        val kx = x + r + (w - h) * progress
        shadow(ctx, kx - knobR, y + r - knobR, knobR * 2f, knobR * 2f, knobR, 1.5f, Palette.shadowSoft, offsetY = 0.5f)
        circle(ctx, kx, y + r, knobR, Palette.switchKnob)
    }

    /** Small rounded label; returns its width. */
    fun chip(ctx: GuiGraphicsExtractor, text: String, x: Float, y: Float, h: Float, bg: Color = Palette.chip, fg: Color = Palette.chipText, size: Float = 6.5f, weight: UIFont.Weight = UIFont.Weight.MEDIUM, padX: Float = 5f): Float {
        val w = width(text, size, weight) + padX * 2f
        rect(ctx, x, y, w, h, h / 2f, bg)
        textMid(ctx, text, x + padX, y + h / 2f, fg, size, weight)
        return w
    }

    fun chipWidth(text: String, size: Float = 6.5f, weight: UIFont.Weight = UIFont.Weight.MEDIUM, padX: Float = 5f) = width(text, size, weight) + padX * 2f

    enum class ButtonStyle { PRIMARY, SECONDARY, DANGER, GHOST }

    /** Rounded push button with centred label (and optional leading icon). */
    fun button(ctx: GuiGraphicsExtractor, label: String, x: Float, y: Float, w: Float, h: Float, hover: Float, style: ButtonStyle = ButtonStyle.SECONDARY, icon: Icon? = null, size: Float = 7.5f) {
        val r = 6f.coerceAtMost(h / 2f)
        val (bg, border, fg) = when (style) {
            ButtonStyle.PRIMARY -> Triple(mix(Palette.accent, Palette.accentText, hover * 0.3f), Palette.accentBorder, Color(250, 247, 255))
            ButtonStyle.DANGER -> Triple(mix(Palette.card, Palette.danger.withA(170), hover), mix(Palette.danger.withA(90), Palette.danger, hover), mix(Color(238, 160, 182), Color(255, 244, 248), hover))
            ButtonStyle.SECONDARY -> Triple(mix(Palette.card, Palette.cardHover, hover), mix(Palette.outline, Palette.accentBorder, hover), mix(Palette.textPrimary, Palette.accentText, hover))
            ButtonStyle.GHOST -> Triple(Palette.chip.withA((Palette.chip.alpha * hover).roundToInt()), Color(0, 0, 0, 0), mix(Palette.textSecondary, Palette.textPrimary, hover))
        }
        rect(ctx, x, y, w, h, r, bg)
        if (border.alpha > 0) outline(ctx, x, y, w, h, r, border)
        val iconSize = if (icon != null) size + 2f else 0f
        val gap = if (icon != null) 4f else 0f
        val textW = width(label, size, UIFont.Weight.MEDIUM)
        val total = iconSize + gap + textW
        var cx = x + (w - total) / 2f
        if (icon != null) {
            icon(ctx, icon, cx, y + (h - iconSize) / 2f, iconSize, fg)
            cx += iconSize + gap
        }
        textMid(ctx, label, cx, y + h / 2f, fg, size, UIFont.Weight.MEDIUM)
    }

    /** Square icon button (close X, gear...) with a rounded hover fill. */
    fun iconButton(ctx: GuiGraphicsExtractor, icon: Icon, x: Float, y: Float, size: Float, hover: Float, color: Color = Palette.textSecondary, hoverColor: Color = Palette.textPrimary, iconScale: Float = 0.6f) {
        if (hover > 0.01f) rect(ctx, x, y, size, size, size * 0.3f, Palette.chip.withA((Palette.chip.alpha * 1.6f * hover).roundToInt()))
        val s = size * iconScale
        icon(ctx, icon, x + (size - s) / 2f, y + (size - s) / 2f, s, mix(color, hoverColor, hover))
    }

    private fun Color.withA(a: Int) = Color(red, green, blue, a.coerceIn(0, 255))

    // ---------------------------------------------------------------- animation helpers

    private var lastFrame = System.nanoTime()

    /** Seconds since the previous call (clamped), call once per frame. */
    fun frameDelta(): Float {
        val now = System.nanoTime()
        val dt = ((now - lastFrame) / 1_000_000_000.0).toFloat().coerceIn(0f, 0.1f)
        lastFrame = now
        return dt
    }

    /** Frame-rate independent exponential approach of [current] toward [target]. */
    fun approach(current: Float, target: Float, dt: Float, speed: Float = 16f): Float {
        val next = current + (target - current) * (1f - exp(-speed * dt))
        return if (kotlin.math.abs(next - target) < 0.002f) target else next
    }

    fun easeOutCubic(t: Float): Float {
        val k = 1f - t.coerceIn(0f, 1f)
        return 1f - k * k * k
    }

    /** Seconds the current frame advances animations by; set once per frame by the screen via [beginFrame]. */
    var dt = 0f
        private set

    fun beginFrame() {
        dt = frameDelta()
    }

    /** A value that eases toward a target every frame (hover, press and toggle animations). */
    class Smooth(var value: Float = 0f, private val speed: Float = 16f) {
        fun update(target: Float): Float {
            value = approach(value, target, dt, speed)
            return value
        }

        fun update(on: Boolean) = update(if (on) 1f else 0f)
        fun snap(v: Float) { value = v }
    }
}
