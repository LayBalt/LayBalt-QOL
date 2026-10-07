package com.laybalt.ui.render

import com.laybalt.LayBaltQOL.mc
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.navigation.ScreenRectangle
import net.minecraft.client.gui.render.TextureSetup
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.state.gui.GuiElementRenderState
import org.joml.Matrix3x2f
import java.awt.Color
import kotlin.math.*

/**
 * Anti-aliased vector primitives for the LayBalt GUI: rounded rectangles (filled, gradient, outlined, per-corner
 * radius), soft drop shadows, circles, rings, arcs and stroked polylines with round caps.
 *
 * Every call builds one [GuiElementRenderState] with its own vertices (QUADS on [RenderPipelines.GUI], triangles are
 * emitted as quads with a repeated vertex) and honours the current scissor. Edges get a one-physical-pixel alpha
 * fringe computed from the current pose and GUI scale, and axis-aligned rectangles are snapped to the physical pixel
 * grid, so shapes stay crisp at every GUI scale and on the scaled click-GUI canvas.
 *
 * Use opaque colours for strokes that cross themselves (e.g. the close X): overlapping translucent strokes blend twice.
 */
object Shapes {
    // ---------------------------------------------------------------- public API

    fun GuiGraphicsExtractor.roundedRect(x: Number, y: Number, width: Number, height: Number, radius: Number, color: Color) =
        roundedRect(x, y, width, height, radius, radius, radius, radius, color)

    /** Filled rounded rectangle with per-corner radii (top-left, top-right, bottom-right, bottom-left). */
    fun GuiGraphicsExtractor.roundedRect(x: Number, y: Number, width: Number, height: Number, tl: Number, tr: Number, br: Number, bl: Number, color: Color) {
        if (color.alpha == 0) return
        val c = color.rgb
        fillRounded(this, x.toFloat(), y.toFloat(), width.toFloat(), height.toFloat(), floatArrayOf(tl.toFloat(), tr.toFloat(), br.toFloat(), bl.toFloat())) { _, _ -> c }
    }

    /** Rounded rectangle with a vertical gradient from [top] to [bottom]. */
    fun GuiGraphicsExtractor.roundedRectGradient(x: Number, y: Number, width: Number, height: Number, radius: Number, top: Color, bottom: Color) {
        val fy = y.toFloat()
        val fh = height.toFloat().coerceAtLeast(0.0001f)
        val r = radius.toFloat()
        fillRounded(this, x.toFloat(), fy, width.toFloat(), fh, floatArrayOf(r, r, r, r)) { _, py -> lerpColor(top, bottom, (py - fy) / fh) }
    }

    /** Rounded rectangle with a horizontal gradient from [left] to [right]. */
    fun GuiGraphicsExtractor.roundedRectHorizontalGradient(x: Number, y: Number, width: Number, height: Number, radius: Number, left: Color, right: Color) {
        val fx = x.toFloat()
        val fw = width.toFloat().coerceAtLeast(0.0001f)
        val r = radius.toFloat()
        fillRounded(this, fx, y.toFloat(), fw, height.toFloat(), floatArrayOf(r, r, r, r)) { px, _ -> lerpColor(left, right, (px - fx) / fw) }
    }

    fun GuiGraphicsExtractor.roundedOutline(x: Number, y: Number, width: Number, height: Number, radius: Number, thickness: Number, color: Color) =
        roundedOutline(x, y, width, height, radius, radius, radius, radius, thickness, color)

    /** Rounded border drawn inside the rectangle bounds, [thickness] GUI units wide. */
    fun GuiGraphicsExtractor.roundedOutline(x: Number, y: Number, width: Number, height: Number, tl: Number, tr: Number, br: Number, bl: Number, thickness: Number, color: Color) {
        if (color.alpha == 0) return
        val t = thickness.toFloat()
        val px = Px(this)
        val (rx, ry, rw, rh) = px.snapRect(x.toFloat(), y.toFloat(), width.toFloat(), height.toFloat())
        val half = t / 2f
        val radii = clampRadii(floatArrayOf(tl.toFloat(), tr.toFloat(), br.toFloat(), bl.toFloat()), rw, rh)
        val inset = FloatArray(4) { max(radii[it] - half, 0f) }
        val path = roundedPath(rx + half, ry + half, rw - t, rh - t, inset, segmentsFor(radii.max(), px.scale))
        val mesh = Mesh()
        mesh.stroke(path.first, path.second, closed = true, thickness = t, color = color.rgb, feather = px.feather, cap = Cap.BUTT)
        mesh.submit(this)
    }

    /**
     * Soft drop shadow behind a rounded rectangle: a smooth falloff from [color] at [blur] inside the edge to fully
     * transparent [blur] outside it. [spread] grows the shape, [offsetX]/[offsetY] move it.
     */
    fun GuiGraphicsExtractor.shadow(x: Number, y: Number, width: Number, height: Number, radius: Number, blur: Number, color: Color, offsetX: Number = 0, offsetY: Number = 0, spread: Number = 0) {
        if (color.alpha == 0) return
        val b = blur.toFloat().coerceAtLeast(0.5f)
        val s = spread.toFloat()
        val bx = x.toFloat() + offsetX.toFloat() - s
        val by = y.toFloat() + offsetY.toFloat() - s
        val bw = width.toFloat() + s * 2f
        val bh = height.toFloat() + s * 2f
        val r = radius.toFloat() + s
        val px = Px(this)
        val segs = segmentsFor(r + b, px.scale)
        val rings = 10
        val mesh = Mesh()
        val argb = color.rgb
        val baseAlpha = color.alpha

        fun pathAt(d: Float): Pair<FloatArray, FloatArray> {
            val w = max(bw + d * 2f, 0.01f)
            val h = max(bh + d * 2f, 0.01f)
            val rr = max(r + d, 0f)
            return roundedPath(bx - d + (bw + d * 2f - w) / 2f, by - d + (bh + d * 2f - h) / 2f, w, h, floatArrayOf(rr, rr, rr, rr), segs)
        }

        fun alphaAt(d: Float): Int {
            val t = ((d + b) / (2f * b)).coerceIn(0f, 1f)
            val falloff = 1f - t * t * (3f - 2f * t)
            return withAlpha(argb, (baseAlpha * falloff).roundToInt())
        }

        var inner = pathAt(-b)
        var innerColor = alphaAt(-b)
        mesh.fan(inner.first, inner.second) { _, _ -> innerColor }
        for (i in 1..rings) {
            val d = -b + 2f * b * i / rings
            val outer = pathAt(d)
            val outerColor = alphaAt(d)
            mesh.band(inner.first, inner.second, innerColor, outer.first, outer.second, outerColor)
            inner = outer
            innerColor = outerColor
        }
        mesh.submit(this)
    }

    fun GuiGraphicsExtractor.circle(cx: Number, cy: Number, radius: Number, color: Color) {
        if (color.alpha == 0) return
        val px = Px(this)
        val r = radius.toFloat()
        val path = circlePath(cx.toFloat(), cy.toFloat(), r, circleSegments(r, px.scale))
        val mesh = Mesh()
        val c = color.rgb
        mesh.fillAA(path.first, path.second, px.feather) { _, _ -> c }
        mesh.submit(this)
    }

    /** Circle outline centred on [radius] (the stroke straddles it). */
    fun GuiGraphicsExtractor.ring(cx: Number, cy: Number, radius: Number, thickness: Number, color: Color) {
        if (color.alpha == 0) return
        val px = Px(this)
        val r = radius.toFloat()
        val path = circlePath(cx.toFloat(), cy.toFloat(), r, circleSegments(r, px.scale))
        val mesh = Mesh()
        mesh.stroke(path.first, path.second, closed = true, thickness = thickness.toFloat(), color = color.rgb, feather = px.feather, cap = Cap.BUTT)
        mesh.submit(this)
    }

    /** Arc stroke from [startDeg] to [endDeg] (0 = +x, clockwise on screen). */
    fun GuiGraphicsExtractor.arc(cx: Number, cy: Number, radius: Number, startDeg: Number, endDeg: Number, thickness: Number, color: Color, roundCaps: Boolean = true) {
        if (color.alpha == 0) return
        val px = Px(this)
        val r = radius.toFloat()
        val a0 = Math.toRadians(startDeg.toDouble())
        val a1 = Math.toRadians(endDeg.toDouble())
        val full = circleSegments(r, px.scale)
        val n = max(2, ceil(full * abs(a1 - a0) / (2 * PI)).toInt() + 1)
        val xs = FloatArray(n)
        val ys = FloatArray(n)
        for (i in 0 until n) {
            val a = a0 + (a1 - a0) * i / (n - 1)
            xs[i] = cx.toFloat() + cos(a).toFloat() * r
            ys[i] = cy.toFloat() + sin(a).toFloat() * r
        }
        val mesh = Mesh()
        mesh.stroke(xs, ys, closed = false, thickness = thickness.toFloat(), color = color.rgb, feather = px.feather, cap = if (roundCaps) Cap.ROUND else Cap.BUTT)
        mesh.submit(this)
    }

    /** Straight stroke with round (default) or butt caps. */
    fun GuiGraphicsExtractor.line(x1: Number, y1: Number, x2: Number, y2: Number, thickness: Number, color: Color, roundCaps: Boolean = true) =
        polyline(floatArrayOf(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat()), thickness, color, roundCaps = roundCaps)

    /** Stroked polyline through the points `[x0, y0, x1, y1, ...]` with mitred joins. */
    fun GuiGraphicsExtractor.polyline(points: FloatArray, thickness: Number, color: Color, closed: Boolean = false, roundCaps: Boolean = true) {
        if (color.alpha == 0 || points.size < 4) return
        val px = Px(this)
        val n = points.size / 2
        val xs = FloatArray(n) { points[it * 2] }
        val ys = FloatArray(n) { points[it * 2 + 1] }
        val mesh = Mesh()
        mesh.stroke(xs, ys, closed, thickness.toFloat(), color.rgb, px.feather, if (roundCaps && !closed) Cap.ROUND else Cap.BUTT)
        mesh.submit(this)
    }

    /** Filled convex polygon through `[x0, y0, x1, y1, ...]`. */
    fun GuiGraphicsExtractor.polygon(points: FloatArray, color: Color) {
        if (color.alpha == 0 || points.size < 6) return
        val px = Px(this)
        val n = points.size / 2
        val c = color.rgb
        val mesh = Mesh()
        mesh.fillAA(FloatArray(n) { points[it * 2] }, FloatArray(n) { points[it * 2 + 1] }, px.feather) { _, _ -> c }
        mesh.submit(this)
    }

    /**
     * Fills the area between two closed outlines with the same point count (outer star-shaped around the inner one),
     * e.g. a gear outline around its hole. Both edges are anti-aliased.
     */
    fun GuiGraphicsExtractor.fillBetween(outer: FloatArray, inner: FloatArray, color: Color) {
        if (color.alpha == 0 || outer.size != inner.size || outer.size < 6) return
        val px = Px(this)
        val n = outer.size / 2
        val mesh = Mesh()
        mesh.fillRing(FloatArray(n) { outer[it * 2] }, FloatArray(n) { outer[it * 2 + 1] }, FloatArray(n) { inner[it * 2] }, FloatArray(n) { inner[it * 2 + 1] }, px.feather, color.rgb)
        mesh.submit(this)
    }

    /** Size of one physical pixel in the current pose's units (useful for hairlines). */
    fun GuiGraphicsExtractor.pixel() = Px(this).feather

    fun lerpColor(a: Color, b: Color, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        fun ch(x: Int, y: Int) = (x + (y - x) * k).roundToInt().coerceIn(0, 255)
        return (ch(a.alpha, b.alpha) shl 24) or (ch(a.red, b.red) shl 16) or (ch(a.green, b.green) shl 8) or ch(a.blue, b.blue)
    }

    // ---------------------------------------------------------------- geometry

    internal enum class Cap { BUTT, ROUND }

    /** Pixel metrics of the current pose: [scale] physical pixels per local unit, [feather] = one physical pixel. */
    internal class Px(ctx: GuiGraphicsExtractor) {
        private val pose: Matrix3x2f = ctx.pose()
        private val gs = mc.window.guiScale.toFloat()
        val scale = (hypot(pose.m00(), pose.m01()) * gs).let { if (it.isFinite() && it > 0.01f) it else 1f }
        val feather = 1f / scale
        private val axisAligned = pose.m01() == 0f && pose.m10() == 0f && pose.m00() != 0f && pose.m11() != 0f

        private fun snapX(x: Float) = if (!axisAligned) x else (Math.round((pose.m00() * x + pose.m20()) * gs) / gs - pose.m20()) / pose.m00()
        private fun snapY(y: Float) = if (!axisAligned) y else (Math.round((pose.m11() * y + pose.m21()) * gs) / gs - pose.m21()) / pose.m11()

        /** Rectangle with its edges moved to the nearest physical pixel boundaries. */
        fun snapRect(x: Float, y: Float, w: Float, h: Float): FloatArray {
            // Tiny shapes (icon dots/squares) would be visibly distorted by snapping; thin lines still snap.
            if (w * scale < 6f && h * scale < 6f) return floatArrayOf(x, y, w, h)
            val x0 = snapX(x)
            val y0 = snapY(y)
            val x1 = snapX(x + w)
            val y1 = snapY(y + h)
            return floatArrayOf(x0, y0, max(x1 - x0, 0f), max(y1 - y0, 0f))
        }
    }

    private operator fun FloatArray.component4() = this[3]

    private fun fillRounded(ctx: GuiGraphicsExtractor, x: Float, y: Float, w: Float, h: Float, radii: FloatArray, colorAt: (Float, Float) -> Int) {
        if (w <= 0f || h <= 0f) return
        val px = Px(ctx)
        val (rx, ry, rw, rh) = px.snapRect(x, y, w, h)
        if (rw <= 0f || rh <= 0f) return
        val r = clampRadii(radii, rw, rh)
        val path = roundedPath(rx, ry, rw, rh, r, segmentsFor(r.max(), px.scale))
        val mesh = Mesh()
        mesh.fillAA(path.first, path.second, px.feather, colorAt)
        mesh.submit(ctx)
    }

    private fun clampRadii(r: FloatArray, w: Float, h: Float): FloatArray {
        val limit = min(w, h) / 2f
        return FloatArray(4) { r[it].coerceIn(0f, limit) }
    }

    /** Segments per quarter circle so the chord error stays under ~0.2 physical pixels. */
    private fun segmentsFor(radius: Float, scale: Float): Int {
        val rPx = radius * scale
        if (rPx < 0.5f) return 0
        val step = acos((1.0 - 0.2 / max(rPx.toDouble(), 0.21)).coerceIn(-1.0, 1.0))
        return ceil((PI / 2) / step).toInt().coerceIn(2, 24)
    }

    private fun circleSegments(radius: Float, scale: Float) = (segmentsFor(radius, scale) * 4).coerceAtLeast(12)

    /** Clockwise (on screen) rounded-rectangle outline; every corner gets `segs + 1` points so paths of equal segs correspond. */
    private fun roundedPath(x: Float, y: Float, w: Float, h: Float, r: FloatArray, segs: Int): Pair<FloatArray, FloatArray> {
        val per = segs + 1
        val xs = FloatArray(per * 4)
        val ys = FloatArray(per * 4)
        val cx = floatArrayOf(x + r[0], x + w - r[1], x + w - r[2], x + r[3])
        val cy = floatArrayOf(y + r[0], y + r[1], y + h - r[2], y + h - r[3])
        val start = doubleArrayOf(PI, PI * 1.5, 0.0, PI * 0.5)
        var k = 0
        for (corner in 0 until 4) {
            for (s in 0..segs) {
                val a = start[corner] + (PI / 2) * (if (segs == 0) 0.5 else s.toDouble() / segs)
                // With segs == 0 the single point is the sharp corner itself.
                if (segs == 0) {
                    xs[k] = if (corner == 0 || corner == 3) x else x + w
                    ys[k] = if (corner <= 1) y else y + h
                }
                else {
                    xs[k] = cx[corner] + cos(a).toFloat() * r[corner]
                    ys[k] = cy[corner] + sin(a).toFloat() * r[corner]
                }
                k++
            }
        }
        return xs to ys
    }

    private fun circlePath(cx: Float, cy: Float, r: Float, n: Int): Pair<FloatArray, FloatArray> {
        val xs = FloatArray(n)
        val ys = FloatArray(n)
        for (i in 0 until n) {
            val a = 2 * PI * i / n
            xs[i] = cx + cos(a).toFloat() * r
            ys[i] = cy + sin(a).toFloat() * r
        }
        return xs to ys
    }

    private fun withAlpha(argb: Int, alpha: Int) = (alpha.coerceIn(0, 255) shl 24) or (argb and 0xFFFFFF)

    /**
     * Mitred vertex normals of a path ("left" side of the walking direction for open paths, outward for closed
     * clockwise ones), scaled so offsetting by `n * d` keeps edges `d` away. Repeated points share their neighbour's normal.
     */
    private fun normals(xs: FloatArray, ys: FloatArray, closed: Boolean, outward: Boolean): Pair<FloatArray, FloatArray> {
        val n = xs.size
        val nx = FloatArray(n)
        val ny = FloatArray(n)
        var sign = 1f
        if (closed && outward) {
            var area = 0f
            for (i in 0 until n) {
                val j = (i + 1) % n
                area += xs[i] * ys[j] - xs[j] * ys[i]
            }
            sign = if (area >= 0f) 1f else -1f
        }
        val eps = 1e-5f

        fun edgeNormal(ax: Float, ay: Float, bx: Float, by: Float, out: FloatArray): Boolean {
            val dx = bx - ax
            val dy = by - ay
            val len = sqrt(dx * dx + dy * dy)
            if (len < eps) return false
            out[0] = dy / len * sign
            out[1] = -dx / len * sign
            return true
        }

        val prev = FloatArray(2)
        val next = FloatArray(2)
        for (i in 0 until n) {
            var hasPrev = false
            var hasNext = false
            // previous distinct point
            var p = i
            for (step in 1 until n) {
                val idx = if (closed) (i - step + n) % n else i - step
                if (idx < 0) break
                if (abs(xs[idx] - xs[i]) > eps || abs(ys[idx] - ys[i]) > eps) { p = idx; hasPrev = edgeNormal(xs[idx], ys[idx], xs[i], ys[i], prev); break }
            }
            var q = i
            for (step in 1 until n) {
                val idx = if (closed) (i + step) % n else i + step
                if (idx >= n) break
                if (abs(xs[idx] - xs[i]) > eps || abs(ys[idx] - ys[i]) > eps) { q = idx; hasNext = edgeNormal(xs[i], ys[i], xs[idx], ys[idx], next); break }
            }
            if (p == i && q == i) continue
            when {
                hasPrev && hasNext -> {
                    var mx = prev[0] + next[0]
                    var my = prev[1] + next[1]
                    val ml = sqrt(mx * mx + my * my)
                    if (ml < eps) { mx = next[0]; my = next[1] }
                    else {
                        mx /= ml; my /= ml
                        val cosHalf = (mx * next[0] + my * next[1]).coerceAtLeast(0.35f)
                        mx /= cosHalf; my /= cosHalf
                    }
                    nx[i] = mx; ny[i] = my
                }
                hasPrev -> { nx[i] = prev[0]; ny[i] = prev[1] }
                hasNext -> { nx[i] = next[0]; ny[i] = next[1] }
            }
        }
        return nx to ny
    }

    // ---------------------------------------------------------------- mesh

    private class Mesh {
        var xs = FloatArray(256)
        var ys = FloatArray(256)
        var cs = IntArray(256)
        var count = 0
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY

        fun v(x: Float, y: Float, c: Int) {
            if (count == xs.size) {
                xs = xs.copyOf(count * 2)
                ys = ys.copyOf(count * 2)
                cs = cs.copyOf(count * 2)
            }
            xs[count] = x; ys[count] = y; cs[count] = c
            count++
            if (x < minX) minX = x
            if (y < minY) minY = y
            if (x > maxX) maxX = x
            if (y > maxY) maxY = y
        }

        /**
         * The GUI pipeline culls back faces: vanilla quads wind counter-clockwise on screen (negative shoelace area in
         * y-down coordinates), so quads/triangles are re-ordered to that winding whatever order they were built in.
         */
        fun quad(x0: Float, y0: Float, c0: Int, x1: Float, y1: Float, c1: Int, x2: Float, y2: Float, c2: Int, x3: Float, y3: Float, c3: Int) {
            val area = (x0 * y1 - x1 * y0) + (x1 * y2 - x2 * y1) + (x2 * y3 - x3 * y2) + (x3 * y0 - x0 * y3)
            if (area > 0f) { v(x0, y0, c0); v(x3, y3, c3); v(x2, y2, c2); v(x1, y1, c1) }
            else { v(x0, y0, c0); v(x1, y1, c1); v(x2, y2, c2); v(x3, y3, c3) }
        }

        fun tri(x0: Float, y0: Float, c0: Int, x1: Float, y1: Float, c1: Int, x2: Float, y2: Float, c2: Int) {
            val area = (x1 - x0) * (y2 - y0) - (x2 - x0) * (y1 - y0)
            if (area > 0f) { v(x0, y0, c0); v(x2, y2, c2); v(x1, y1, c1); v(x1, y1, c1) }
            else { v(x0, y0, c0); v(x1, y1, c1); v(x2, y2, c2); v(x2, y2, c2) }
        }

        /** Triangle fan over a convex (or star-shaped around its centroid) closed path, no AA. */
        fun fan(xs: FloatArray, ys: FloatArray, colorAt: (Float, Float) -> Int) {
            val n = xs.size
            val cx = xs.average().toFloat()
            val cy = ys.average().toFloat()
            val cc = colorAt(cx, cy)
            for (i in 0 until n) {
                val j = (i + 1) % n
                tri(cx, cy, cc, xs[i], ys[i], colorAt(xs[i], ys[i]), xs[j], ys[j], colorAt(xs[j], ys[j]))
            }
        }

        /** Quad band between two corresponding closed paths. */
        fun band(ax: FloatArray, ay: FloatArray, ac: Int, bx: FloatArray, by: FloatArray, bc: Int) {
            val n = ax.size
            for (i in 0 until n) {
                val j = (i + 1) % n
                quad(ax[i], ay[i], ac, ax[j], ay[j], ac, bx[j], by[j], bc, bx[i], by[i], bc)
            }
        }

        /** Convex fill with a one-pixel alpha fringe centred on the edge. */
        fun fillAA(px: FloatArray, py: FloatArray, feather: Float, colorAt: (Float, Float) -> Int) {
            val n = px.size
            val (nx, ny) = normals(px, py, closed = true, outward = true)
            val h = feather / 2f
            val ix = FloatArray(n) { px[it] - nx[it] * h }
            val iy = FloatArray(n) { py[it] - ny[it] * h }
            val ic = IntArray(n) { colorAt(px[it], py[it]) }
            val cx = px.average().toFloat()
            val cy = py.average().toFloat()
            val cc = colorAt(cx, cy)
            for (i in 0 until n) {
                val j = (i + 1) % n
                tri(cx, cy, cc, ix[i], iy[i], ic[i], ix[j], iy[j], ic[j])
                quad(
                    ix[i], iy[i], ic[i],
                    ix[j], iy[j], ic[j],
                    px[j] + nx[j] * h, py[j] + ny[j] * h, ic[j] and 0xFFFFFF,
                    px[i] + nx[i] * h, py[i] + ny[i] * h, ic[i] and 0xFFFFFF
                )
            }
        }

        /** Area between corresponding outer/inner closed paths, both edges anti-aliased. */
        fun fillRing(ox: FloatArray, oy: FloatArray, ix: FloatArray, iy: FloatArray, feather: Float, c: Int) {
            val n = ox.size
            val (onx, ony) = normals(ox, oy, closed = true, outward = true)
            val (inx, iny) = normals(ix, iy, closed = true, outward = true)
            val h = feather / 2f
            val t = c and 0xFFFFFF
            for (i in 0 until n) {
                val j = (i + 1) % n
                // core
                quad(
                    ox[i] - onx[i] * h, oy[i] - ony[i] * h, c,
                    ox[j] - onx[j] * h, oy[j] - ony[j] * h, c,
                    ix[j] + inx[j] * h, iy[j] + iny[j] * h, c,
                    ix[i] + inx[i] * h, iy[i] + iny[i] * h, c
                )
                // outer fringe
                quad(
                    ox[i] - onx[i] * h, oy[i] - ony[i] * h, c,
                    ox[j] - onx[j] * h, oy[j] - ony[j] * h, c,
                    ox[j] + onx[j] * h, oy[j] + ony[j] * h, t,
                    ox[i] + onx[i] * h, oy[i] + ony[i] * h, t
                )
                // inner fringe
                quad(
                    ix[i] + inx[i] * h, iy[i] + iny[i] * h, c,
                    ix[j] + inx[j] * h, iy[j] + iny[j] * h, c,
                    ix[j] - inx[j] * h, iy[j] - iny[j] * h, t,
                    ix[i] - inx[i] * h, iy[i] - iny[i] * h, t
                )
            }
        }

        /** Stroke along a path with feathered sides; open paths get butt or round caps. */
        fun stroke(xs: FloatArray, ys: FloatArray, closed: Boolean, thickness: Float, color: Int, feather: Float, cap: Cap) {
            val n = xs.size
            if (n < 2) return
            var c = color
            var half = thickness / 2f
            if (thickness < feather) {
                // Hairline: keep one pixel of coverage and fade it instead.
                val a = ((color ushr 24) * (thickness / feather)).roundToInt()
                c = withAlpha(color, a)
                half = feather / 2f
            }
            val t = c and 0xFFFFFF
            val core = max(half - feather / 2f, 0f)
            val outer = half + feather / 2f
            val (nx, ny) = normals(xs, ys, closed, outward = false)
            val segments = if (closed) n else n - 1
            for (i in 0 until segments) {
                val j = (i + 1) % n
                if (core > 0f) quad(
                    xs[i] + nx[i] * core, ys[i] + ny[i] * core, c,
                    xs[j] + nx[j] * core, ys[j] + ny[j] * core, c,
                    xs[j] - nx[j] * core, ys[j] - ny[j] * core, c,
                    xs[i] - nx[i] * core, ys[i] - ny[i] * core, c
                )
                quad(
                    xs[i] + nx[i] * core, ys[i] + ny[i] * core, c,
                    xs[j] + nx[j] * core, ys[j] + ny[j] * core, c,
                    xs[j] + nx[j] * outer, ys[j] + ny[j] * outer, t,
                    xs[i] + nx[i] * outer, ys[i] + ny[i] * outer, t
                )
                quad(
                    xs[i] - nx[i] * core, ys[i] - ny[i] * core, c,
                    xs[j] - nx[j] * core, ys[j] - ny[j] * core, c,
                    xs[j] - nx[j] * outer, ys[j] - ny[j] * outer, t,
                    xs[i] - nx[i] * outer, ys[i] - ny[i] * outer, t
                )
            }
            if (closed) return
            cap(xs[0], ys[0], xs[0] - xs[1], ys[0] - ys[1], core, outer, c, t, cap, feather)
            cap(xs[n - 1], ys[n - 1], xs[n - 1] - xs[n - 2], ys[n - 1] - ys[n - 2], core, outer, c, t, cap, feather)
        }

        /** End cap at (x, y) facing direction (dx, dy). */
        private fun cap(x: Float, y: Float, dx: Float, dy: Float, core: Float, outer: Float, c: Int, t: Int, cap: Cap, feather: Float) {
            val len = sqrt(dx * dx + dy * dy)
            if (len < 1e-5f) return
            val tx = dx / len
            val ty = dy / len
            // normal (left of the outward direction)
            val nx = -ty
            val ny = tx
            if (cap == Cap.BUTT) {
                val f = feather
                quad(x + nx * core, y + ny * core, c, x - nx * core, y - ny * core, c, x - nx * core + tx * f, y - ny * core + ty * f, t, x + nx * core + tx * f, y + ny * core + ty * f, t)
                return
            }
            // Half disc from +n through +t to -n, plus its fringe; it does not overlap the stroke body.
            val steps = 8
            val base = atan2(ny, nx).toDouble()
            var prevCx = x + nx * core
            var prevCy = y + ny * core
            var prevOx = x + nx * outer
            var prevOy = y + ny * outer
            for (s in 1..steps) {
                // n is t rotated by +90 degrees, so decreasing the angle sweeps from +n through +t to -n.
                val a = base - PI * s / steps
                val ca = cos(a).toFloat()
                val sa = sin(a).toFloat()
                val cx = x + ca * core
                val cy = y + sa * core
                val ox = x + ca * outer
                val oy = y + sa * outer
                if (core > 0f) tri(x, y, c, prevCx, prevCy, c, cx, cy, c)
                quad(prevCx, prevCy, c, cx, cy, c, ox, oy, t, prevOx, prevOy, t)
                prevCx = cx; prevCy = cy; prevOx = ox; prevOy = oy
            }
        }

        fun submit(ctx: GuiGraphicsExtractor) {
            if (count == 0) return
            val pose = Matrix3x2f(ctx.pose())
            val rect = ScreenRectangle(
                floor(minX).toInt() - 1, floor(minY).toInt() - 1,
                ceil(maxX - minX).toInt() + 3, ceil(maxY - minY).toInt() + 3
            ).transformMaxBounds(pose)
            val scissor = ctx.scissorStack.peek()
            val bounds = if (scissor != null) scissor.intersection(rect) ?: return else rect
            ctx.guiRenderState.addGuiElement(MeshState(pose, xs, ys, cs, count, scissor, bounds))
        }
    }

    private class MeshState(
        private val pose: Matrix3x2f,
        private val xs: FloatArray,
        private val ys: FloatArray,
        private val cs: IntArray,
        private val count: Int,
        private val scissor: ScreenRectangle?,
        private val bounds: ScreenRectangle
    ): GuiElementRenderState {
        override fun buildVertices(buffer: VertexConsumer) {
            for (i in 0 until count) buffer.addVertexWith2DPose(pose, xs[i], ys[i]).setColor(cs[i])
        }

        override fun pipeline() = RenderPipelines.GUI
        override fun textureSetup(): TextureSetup = TextureSetup.noTexture()
        override fun scissorArea() = scissor
        override fun bounds() = bounds
    }
}
