package com.laybalt.features.impl.render

import com.laybalt.utils.render.world.RenderBatcher
import com.laybalt.utils.render.world.RenderContext
import net.minecraft.world.phys.AABB
import java.awt.Color
import kotlin.math.PI
import kotlin.math.sin

/**
 * Shared world-box drawing for the block / entity box visuals (Block Overlay, Break Highlight, Box 3D).
 *
 * Unlike Render3D.renderBoxBounds it keeps the outline alpha, can draw corner-only outlines, and moves the box
 * into camera space in double precision before handing floats to the batcher (no jitter far from 0,0).
 * Not a feature: a plain helper object.
 */
object WorldBoxKit {
    val OUTLINE_STYLES = listOf("Full", "Corners")
    val COLOR_MODES = listOf("Static", "Rainbow", "Breathing")

    /**
     * Draws [box] as a filled box and/or outline. [cornerFraction] is the length of each corner segment
     * relative to the edge it sits on when [corners] is true (0.25 = a quarter of the edge from each end).
     */
    fun RenderContext.drawBox(
        box: AABB,
        outlineColor: Color,
        fillColor: Color,
        outline: Boolean,
        fill: Boolean,
        phase: Boolean,
        lineWidth: Float,
        corners: Boolean = false,
        cornerFraction: Float = 0.25f
    ) {
        if (! outline && ! fill) return
        val cam = camera.pos
        val x1 = (box.minX - cam.x).toFloat()
        val y1 = (box.minY - cam.y).toFloat()
        val z1 = (box.minZ - cam.z).toFloat()
        val x2 = (box.maxX - cam.x).toFloat()
        val y2 = (box.maxY - cam.y).toFloat()
        val z2 = (box.maxZ - cam.z).toFloat()
        val pose = uMatrixStack()

        if (fill && fillColor.alpha > 0) {
            val r = fillColor.red / 255f
            val g = fillColor.green / 255f
            val b = fillColor.blue / 255f
            val a = fillColor.alpha / 255f
            RenderBatcher.filledBatch(phase).addFilledBoxVertices(pose, x1.toDouble(), y1.toDouble(), z1.toDouble(), x2.toDouble(), y2.toDouble(), z2.toDouble(), r, g, b, a)
        }

        if (outline && outlineColor.alpha > 0) {
            val r = outlineColor.red / 255f
            val g = outlineColor.green / 255f
            val b = outlineColor.blue / 255f
            val a = outlineColor.alpha / 255f
            val lines = RenderBatcher.lineBatch(phase)
            if (! corners) {
                lines.addLineBoxVertices(pose, x1.toDouble(), y1.toDouble(), z1.toDouble(), x2.toDouble(), y2.toDouble(), z2.toDouble(), r, g, b, a, lineWidth)
                return
            }

            val f = cornerFraction.coerceIn(0.05f, 0.5f)
            val lx = (x2 - x1) * f
            val ly = (y2 - y1) * f
            val lz = (z2 - z1) * f
            for (cx in floatArrayOf(x1, x2)) for (cy in floatArrayOf(y1, y2)) for (cz in floatArrayOf(z1, z2)) {
                val dx = if (cx == x1) lx else - lx
                val dy = if (cy == y1) ly else - ly
                val dz = if (cz == z1) lz else - lz
                lines.line(pose, cx, cy, cz, cx + dx, cy, cz, r, g, b, a, lineWidth)
                lines.line(pose, cx, cy, cz, cx, cy + dy, cz, r, g, b, a, lineWidth)
                lines.line(pose, cx, cy, cz, cx, cy, cz + dz, r, g, b, a, lineWidth)
            }
        }
    }

    /**
     * Applies a [COLOR_MODES] animation to [base]. Rainbow keeps the base saturation, brightness and alpha;
     * Breathing pulses the alpha between 35% and 100% of the base alpha. [speed] is in cycles per second.
     */
    fun animated(base: Color, mode: Int, speed: Float, offset: Float = 0f): Color = when (mode) {
        1 -> {
            val hsb = Color.RGBtoHSB(base.red, base.green, base.blue, null)
            val hue = ((System.currentTimeMillis() % 100_000L) / 1000f * speed + offset) % 1f
            val rgb = Color.HSBtoRGB(hue, hsb[1].coerceAtLeast(0.55f), hsb[2].coerceAtLeast(0.6f))
            Color((rgb and 0xFFFFFF) or (base.alpha shl 24), true)
        }

        2 -> {
            val t = (sin((System.currentTimeMillis() % 100_000L) / 1000.0 * speed * 2 * PI) * 0.5 + 0.5).toFloat()
            Color(base.red, base.green, base.blue, (base.alpha * (0.35f + 0.65f * t)).toInt().coerceIn(0, 255))
        }

        else -> base
    }

    fun lerp(a: Color, b: Color, t: Float): Color {
        val k = t.coerceIn(0f, 1f)
        return Color(
            (a.red + (b.red - a.red) * k).toInt().coerceIn(0, 255),
            (a.green + (b.green - a.green) * k).toInt().coerceIn(0, 255),
            (a.blue + (b.blue - a.blue) * k).toInt().coerceIn(0, 255),
            (a.alpha + (b.alpha - a.alpha) * k).toInt().coerceIn(0, 255)
        )
    }
}
