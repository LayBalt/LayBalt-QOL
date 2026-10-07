package com.laybalt.utils.render

import com.laybalt.LayBaltQOL.mc
import net.minecraft.client.gui.GuiGraphicsExtractor
import org.joml.Matrix4f
import org.joml.Vector4f

/**
 * World -> GUI-pixel projection for HUD-space overlays (drawn from `RenderOverlayEvent`).
 *
 * Uses the main camera's view-rotation-projection matrix of the current frame, so a world position
 * (already interpolated by the caller, e.g. `entity.renderX`) lands where the world renderer drew it.
 * View bobbing is not part of that matrix, so labels can drift by a pixel or two while walking.
 *
 * Usage: `val proj = WorldToScreen.begin(ctx) ?: return; val p = proj.project(x, y, z) ?: continue`.
 */
object WorldToScreen {
    class Projection internal constructor(
        private val mvp: Matrix4f,
        private val camX: Double,
        private val camY: Double,
        private val camZ: Double,
        val width: Float,
        val height: Float,
    ) {
        private val tmp = Vector4f()

        /**
         * Returns the GUI position of a world point, or null when the point is behind the camera.
         * The returned [ScreenPoint.depth] is the normalised device depth (-1 near .. 1 far).
         */
        fun project(x: Double, y: Double, z: Double): ScreenPoint? {
            tmp.set((x - camX).toFloat(), (y - camY).toFloat(), (z - camZ).toFloat(), 1f)
            mvp.transform(tmp)
            if (tmp.w <= 1.0e-4f) return null
            val nx = tmp.x / tmp.w
            val ny = tmp.y / tmp.w
            val nz = tmp.z / tmp.w
            return ScreenPoint((nx + 1f) * 0.5f * width, (1f - ny) * 0.5f * height, nz)
        }

        /** True when the point lies inside the screen (plus [margin] pixels). */
        fun onScreen(p: ScreenPoint, margin: Float = 0f) =
            p.x >= - margin && p.y >= - margin && p.x <= width + margin && p.y <= height + margin

        /**
         * Projects the 8 corners of a box and returns their screen bounds (minX, minY, maxX, maxY),
         * or null when any corner is behind the camera (the box would wrap around the screen).
         */
        fun projectBox(minX: Double, minY: Double, minZ: Double, maxX: Double, maxY: Double, maxZ: Double): FloatArray? {
            var x0 = Float.MAX_VALUE
            var y0 = Float.MAX_VALUE
            var x1 = - Float.MAX_VALUE
            var y1 = - Float.MAX_VALUE
            for (i in 0 until 8) {
                val p = project(
                    if (i and 1 == 0) minX else maxX,
                    if (i and 2 == 0) minY else maxY,
                    if (i and 4 == 0) minZ else maxZ
                ) ?: return null
                if (p.depth >= 1f) return null
                if (p.x < x0) x0 = p.x
                if (p.y < y0) y0 = p.y
                if (p.x > x1) x1 = p.x
                if (p.y > y1) y1 = p.y
            }
            return floatArrayOf(x0, y0, x1, y1)
        }
    }

    data class ScreenPoint(val x: Float, val y: Float, val depth: Float)

    /** Snapshot of this frame's camera for repeated projections; null when no world is loaded. */
    fun begin(ctx: GuiGraphicsExtractor): Projection? {
        if (mc.level == null) return null
        val camera = mc.gameRenderer.mainCamera()
        val pos = camera.position()
        val mvp = camera.getViewRotationProjectionMatrix(Matrix4f())
        return Projection(Matrix4f(mvp), pos.x, pos.y, pos.z, ctx.guiWidth().toFloat(), ctx.guiHeight().toFloat())
    }
}
