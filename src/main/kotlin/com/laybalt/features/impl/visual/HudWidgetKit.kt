package com.laybalt.features.impl.visual

import com.laybalt.LayBaltQOL.mc
import com.laybalt.utils.ColorUtils.withAlpha
import com.laybalt.utils.render.Render2D.drawBorder
import com.laybalt.utils.render.Render2D.drawLine
import com.laybalt.utils.render.Render2D.drawRect
import com.laybalt.utils.render.Render2D.drawVerticalGradient
import net.minecraft.client.gui.GuiGraphicsExtractor
import java.awt.Color
import kotlin.math.roundToInt

/**
 * Shared look for the LayBalt HUD widgets (Keystrokes, Armor HUD, Effects HUD, Coords HUD, Speed HUD,
 * Performance Graph, Session Timer): one background vocabulary, one set of default colours and a few
 * formatting helpers so the widgets read as one family. All text goes through the vanilla font.
 */
object HudWidgetKit {
    /** Options for every widget's "Background" dropdown; index 0 = no background. */
    val BACKGROUND_MODES = listOf("None", "Solid", "Outlined", "Accent Bar", "Glass")

    val defaultBackground = Color(16, 13, 24, 150)
    val defaultAccent = Color(158, 119, 237)
    val defaultText = Color(236, 230, 248)
    val defaultMuted = Color(170, 160, 196)

    const val PAD = 3

    val fontHeight get() = mc.font.lineHeight

    /** Inner padding applied around the content when a background is drawn. */
    fun padding(mode: Int) = if (mode == 0) 0 else PAD

    /** Left inset for the content: the accent bar needs a little more room on the left. */
    fun leftPadding(mode: Int) = when (mode) {
        0 -> 0
        3 -> PAD + 2
        else -> PAD
    }

    /**
     * Draws the widget background at the local origin.
     * Solid: flat fill. Outlined: fill + 1px accent frame. Accent Bar: fill + 2px accent stripe on the left.
     * Glass: vertical gradient from the background colour to a lighter tint, with an accent hairline on top.
     */
    fun drawBackground(ctx: GuiGraphicsExtractor, width: Number, height: Number, mode: Int, background: Color, accent: Color) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        when (mode) {
            1 -> ctx.drawRect(0, 0, w, h, background)
            2 -> {
                ctx.drawRect(0, 0, w, h, background)
                ctx.drawBorder(0, 0, w, h, accent.withAlpha((accent.alpha * 0.7f).roundToInt()))
            }
            3 -> {
                ctx.drawRect(0, 0, w, h, background)
                ctx.drawRect(0, 0, 2, h, accent)
            }
            4 -> {
                val top = lighten(background, 0.10f)
                ctx.drawVerticalGradient(0, 0, w, h, top, background)
                ctx.drawRect(0, 0, w, 1, accent.withAlpha((accent.alpha * 0.8f).roundToInt()))
            }
        }
    }

    /** Linear colour interpolation that keeps alpha (ColorUtils.lerpColor drops it). */
    fun lerp(a: Color, b: Color, t: Float): Color {
        val k = t.coerceIn(0f, 1f)
        return Color(
            (a.red + (b.red - a.red) * k).roundToInt().coerceIn(0, 255),
            (a.green + (b.green - a.green) * k).roundToInt().coerceIn(0, 255),
            (a.blue + (b.blue - a.blue) * k).roundToInt().coerceIn(0, 255),
            (a.alpha + (b.alpha - a.alpha) * k).roundToInt().coerceIn(0, 255)
        )
    }

    fun lighten(c: Color, amount: Float) = lerp(c, Color(255, 255, 255, c.alpha), amount)

    /** Green -> yellow -> red as [fraction] goes 1 -> 0.5 -> 0. */
    fun healthColor(fraction: Float): Color {
        val f = fraction.coerceIn(0f, 1f)
        return if (f > 0.5f) lerp(Color(255, 220, 80), Color(110, 230, 120), (f - 0.5f) * 2f)
        else lerp(Color(240, 80, 90), Color(255, 220, 80), f * 2f)
    }

    /** "1d 4h", "2h 05m", "4:07" style durations; [showSeconds] adds seconds to the hour formats. */
    fun formatDuration(millis: Long, showSeconds: Boolean = true): String {
        val total = (millis / 1000).coerceAtLeast(0)
        val d = total / 86400
        val h = (total % 86400) / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return when {
            d > 0 -> "${d}d ${h}h"
            h > 0 -> if (showSeconds) "${h}h %02dm %02ds".format(m, s) else "${h}h %02dm".format(m)
            else -> if (showSeconds) "$m:%02d".format(s) else "${m}m"
        }
    }

    private val romans = listOf(10 to "X", 9 to "IX", 5 to "V", 4 to "IV", 1 to "I")

    fun roman(number: Int): String {
        if (number <= 0 || number > 39) return number.toString()
        var n = number
        return buildString {
            for ((value, symbol) in romans) while (n >= value) {
                append(symbol)
                n -= value
            }
        }
    }

    fun textWidth(text: String) = mc.font.width(text)

    /** Fixed-size rolling window of samples, oldest first when iterated through [get]. */
    class SampleBuffer(capacity: Int) {
        private var data = FloatArray(capacity.coerceAtLeast(2))
        private var start = 0
        var size = 0
            private set
        val capacity get() = data.size

        fun add(value: Float) {
            if (size < data.size) {
                data[(start + size) % data.size] = value
                size ++
            }
            else {
                data[start] = value
                start = (start + 1) % data.size
            }
        }

        operator fun get(i: Int) = data[(start + i) % data.size]

        fun last() = if (size == 0) 0f else get(size - 1)
        fun max(): Float { var m = Float.NEGATIVE_INFINITY; for (i in 0 until size) m = maxOf(m, get(i)); return if (size == 0) 0f else m }
        fun min(): Float { var m = Float.POSITIVE_INFINITY; for (i in 0 until size) m = minOf(m, get(i)); return if (size == 0) 0f else m }
        fun average(): Float { if (size == 0) return 0f; var s = 0f; for (i in 0 until size) s += get(i); return s / size }

        fun clear() {
            start = 0
            size = 0
        }

        /** Changes the window length, keeping the newest samples. */
        fun resize(newCapacity: Int) {
            val cap = newCapacity.coerceAtLeast(2)
            if (cap == data.size) return
            val keep = minOf(size, cap)
            val next = FloatArray(cap)
            for (i in 0 until keep) next[i] = get(size - keep + i)
            data = next
            start = 0
            size = keep
        }
    }

    /**
     * Draws [samples] as a line graph (style 0), a filled area (style 1) or bars (style 2) inside the box
     * at ([x], [y]) sized [w] x [h]. Values are mapped from [minValue]..[maxValue]; the newest sample is on the right.
     */
    fun drawGraph(
        ctx: GuiGraphicsExtractor, x: Float, y: Float, w: Float, h: Float,
        samples: SampleBuffer, minValue: Float, maxValue: Float, color: Color, style: Int, gridColor: Color? = null
    ) {
        if (gridColor != null) {
            ctx.drawRect(x, y, w, 1, gridColor)
            ctx.drawRect(x, y + h / 2f, w, 1, gridColor)
            ctx.drawRect(x, y + h - 1f, w, 1, gridColor)
        }
        val n = samples.size
        if (n < 2) return
        val range = (maxValue - minValue).takeIf { it > 0.0001f } ?: 1f
        val step = w / (samples.capacity - 1).toFloat()
        val startX = x + w - step * (n - 1)
        fun py(v: Float) = y + h - 1f - ((v - minValue) / range).coerceIn(0f, 1f) * (h - 1f)
        // Vertical column from [top] down to the graph bottom, clipped horizontally to the graph box.
        fun column(cx: Float, cw: Float, top: Float, c: Color) {
            val l = maxOf(cx, x)
            val r = minOf(cx + cw, x + w)
            if (r > l) ctx.drawRect(l, top, r - l, y + h - top, c)
        }

        when (style) {
            2 -> {
                val barW = maxOf(step - 0.5f, 0.5f)
                for (i in 0 until n) {
                    val top = py(samples[i])
                    column(startX + i * step - barW / 2f, barW, top, color)
                }
            }
            else -> {
                if (style == 1) {
                    val fill = color.withAlpha((color.alpha * 0.35f).roundToInt())
                    for (i in 0 until n) {
                        val top = py(samples[i])
                        column(startX + i * step - step / 2f, step, top, fill)
                    }
                }
                for (i in 1 until n) {
                    ctx.drawLine(startX + (i - 1) * step, py(samples[i - 1]), startX + i * step, py(samples[i]), color, 1)
                }
            }
        }
    }
}
