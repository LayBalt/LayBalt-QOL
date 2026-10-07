package com.laybalt.ui.render

import com.laybalt.ui.render.Shapes.arc
import com.laybalt.ui.render.Shapes.circle
import com.laybalt.ui.render.Shapes.fillBetween
import com.laybalt.ui.render.Shapes.line
import com.laybalt.ui.render.Shapes.polygon
import com.laybalt.ui.render.Shapes.polyline
import com.laybalt.ui.render.Shapes.ring
import com.laybalt.ui.render.Shapes.roundedOutline
import com.laybalt.ui.render.Shapes.roundedRect
import net.minecraft.client.gui.GuiGraphicsExtractor
import java.awt.Color
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Line-style vector icons for the LayBalt GUI, designed on a 16x16 grid and scaled to any size. */
enum class Icon { GEAR, SEARCH, CLOSE, CHEVRON_UP, CHEVRON_DOWN, CHEVRON_LEFT, CHEVRON_RIGHT, PLUS, MINUS, GRIP, CHECK, LAYOUT, CHAT, LINK, RESET, GRID, TRASH, INFO, DOT }

object Icons {
    /** Default stroke width on the 16-unit grid. */
    private const val STROKE = 1.6f

    /**
     * Draws [icon] in the square at ([x], [y]) with side [size]. [stroke] is in 16-grid units.
     * Pass an opaque [color]: crossing strokes (close X, plus) would otherwise blend twice where they meet.
     */
    fun draw(ctx: GuiGraphicsExtractor, icon: Icon, x: Number, y: Number, size: Number, color: Color, stroke: Float = STROKE) {
        val ox = x.toFloat()
        val oy = y.toFloat()
        val k = size.toFloat() / 16f
        val w = stroke * k
        fun px(v: Float) = ox + v * k
        fun py(v: Float) = oy + v * k
        fun pts(vararg v: Float) = FloatArray(v.size) { if (it % 2 == 0) px(v[it]) else py(v[it]) }

        when (icon) {
            Icon.GEAR -> gear(ctx, px(8f), py(8f), k, color)
            Icon.SEARCH -> {
                ctx.ring(px(7f), py(7f), 4.4f * k, w, color)
                ctx.line(px(10.4f), py(10.4f), px(13.6f), py(13.6f), w, color)
            }
            Icon.CLOSE -> {
                ctx.line(px(4.5f), py(4.5f), px(11.5f), py(11.5f), w, color)
                ctx.line(px(11.5f), py(4.5f), px(4.5f), py(11.5f), w, color)
            }
            Icon.CHEVRON_DOWN -> ctx.polyline(pts(4.5f, 6.5f, 8f, 10f, 11.5f, 6.5f), w, color)
            Icon.CHEVRON_UP -> ctx.polyline(pts(4.5f, 9.5f, 8f, 6f, 11.5f, 9.5f), w, color)
            Icon.CHEVRON_LEFT -> ctx.polyline(pts(9.5f, 4.5f, 6f, 8f, 9.5f, 11.5f), w, color)
            Icon.CHEVRON_RIGHT -> ctx.polyline(pts(6.5f, 4.5f, 10f, 8f, 6.5f, 11.5f), w, color)
            Icon.PLUS -> {
                ctx.line(px(8f), py(3.5f), px(8f), py(12.5f), w, color)
                ctx.line(px(3.5f), py(8f), px(7.2f), py(8f), w, color, roundCaps = false)
                ctx.line(px(8.8f), py(8f), px(12.5f), py(8f), w, color, roundCaps = false)
            }
            Icon.MINUS -> ctx.line(px(3.5f), py(8f), px(12.5f), py(8f), w, color)
            Icon.GRIP -> for (gx in floatArrayOf(6f, 10f)) for (gy in floatArrayOf(4f, 8f, 12f)) ctx.circle(px(gx), py(gy), 1.15f * k, color)
            Icon.CHECK -> ctx.polyline(pts(3.5f, 8.5f, 6.5f, 11.5f, 12.5f, 4.5f), w, color)
            Icon.LAYOUT -> {
                ctx.roundedOutline(px(2f), py(3f), 12f * k, 10f * k, 2f * k, w, color)
                ctx.line(px(6.5f), py(3.5f), px(6.5f), py(12.5f), w * 0.85f, color, roundCaps = false)
                ctx.line(px(7.2f), py(8f), px(13.5f), py(8f), w * 0.85f, color, roundCaps = false)
            }
            Icon.CHAT -> {
                ctx.roundedOutline(px(1.8f), py(2.5f), 12.4f * k, 9f * k, 2.6f * k, w, color)
                ctx.polygon(pts(4.5f, 11f, 8f, 11f, 4.5f, 14.2f), color)
                for (dx in floatArrayOf(5.3f, 8f, 10.7f)) ctx.circle(px(dx), py(7f), 0.95f * k, color)
            }
            Icon.LINK -> {
                ctx.polyline(pts(9.5f, 2.8f, 13.2f, 2.8f, 13.2f, 6.5f), w, color)
                ctx.line(px(12.8f), py(3.2f), px(7.6f), py(8.4f), w, color)
                ctx.polyline(pts(11.5f, 9.5f, 11.5f, 13.2f, 2.8f, 13.2f, 2.8f, 4.5f, 6.5f, 4.5f), w, color)
            }
            Icon.RESET -> {
                ctx.arc(px(8f), py(8.5f), 4.8f * k, -40, 250, w, color)
                // arrow head at the arc start (angle -40 deg), pointing along the sweep direction
                val a = Math.toRadians(-40.0)
                val hx = 8f + cos(a).toFloat() * 4.8f
                val hy = 8.5f + sin(a).toFloat() * 4.8f
                ctx.polygon(pts(hx + 0.4f, hy - 3.4f, hx + 2.6f, hy + 0.9f, hx - 2.0f, hy + 0.7f), color)
            }
            Icon.GRID -> for (gx in floatArrayOf(2.5f, 6.5f, 10.5f)) for (gy in floatArrayOf(2.5f, 6.5f, 10.5f)) ctx.roundedRect(px(gx), py(gy), 3f * k, 3f * k, 0.8f * k, color)
            Icon.TRASH -> {
                ctx.line(px(2.8f), py(4.5f), px(13.2f), py(4.5f), w, color)
                ctx.polyline(pts(6.2f, 4.2f, 6.2f, 2.6f, 9.8f, 2.6f, 9.8f, 4.2f), w * 0.9f, color, roundCaps = false)
                ctx.polyline(pts(4.4f, 5.6f, 5.2f, 13.4f, 10.8f, 13.4f, 11.6f, 5.6f), w, color)
            }
            Icon.INFO -> {
                ctx.ring(px(8f), py(8f), 6f * k, w, color)
                ctx.circle(px(8f), py(5.2f), 1.05f * k, color)
                ctx.line(px(8f), py(7.6f), px(8f), py(11.2f), w, color)
            }
            Icon.DOT -> ctx.circle(px(8f), py(8f), 3f * k, color)
        }
    }

    /** Eight-tooth gear with a round hole, built as one outline so the teeth are anti-aliased on every side. */
    private fun gear(ctx: GuiGraphicsExtractor, cx: Float, cy: Float, k: Float, color: Color) {
        val teeth = 8
        val rTip = 7.1f * k
        val rBase = 5.5f * k
        val rHole = 2.3f * k
        val tipHalf = PI / teeth * 0.38
        val baseHalf = PI / teeth * 0.62
        val valleySteps = 3
        val outer = ArrayList<Float>()
        val angles = ArrayList<Double>()
        fun add(angle: Double, r: Float) {
            outer += cx + cos(angle).toFloat() * r
            outer += cy + sin(angle).toFloat() * r
            angles += angle
        }
        for (i in 0 until teeth) {
            val center = 2 * PI * i / teeth - PI / 2
            add(center - baseHalf, rBase)
            add(center - tipHalf, rTip)
            add(center + tipHalf, rTip)
            add(center + baseHalf, rBase)
            val next = 2 * PI * (i + 1) / teeth - PI / 2
            val from = center + baseHalf
            val to = next - baseHalf
            for (s in 1 until valleySteps) add(from + (to - from) * s / valleySteps, rBase)
        }
        val inner = FloatArray(outer.size)
        angles.forEachIndexed { i, a ->
            inner[i * 2] = cx + cos(a).toFloat() * rHole
            inner[i * 2 + 1] = cy + sin(a).toFloat() * rHole
        }
        ctx.fillBetween(outer.toFloatArray(), inner, color)
    }
}
