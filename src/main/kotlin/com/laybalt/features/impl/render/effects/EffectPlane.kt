package com.laybalt.features.impl.render.effects

import com.laybalt.utils.render.world.RenderContext
import com.laybalt.utils.render.world.batches.FilledBatch
import gg.essential.universal.UMatrixStack
import net.minecraft.world.phys.Vec3
import org.joml.Vector3f
import java.awt.Color
import kotlin.math.cos
import kotlin.math.sin

/**
 * A 2D drawing surface placed somewhere in the world: a centre, two axis vectors and a size.
 * Shapes in [EffectShapes] are written in unit space (roughly -1..1 on both axes) and go through [vertex],
 * which maps them into camera-relative world space and applies either one colour or a four-corner gradient.
 *
 * One instance is reused for every sprite of a frame; call [place] / [color] / [gradient] before each sprite.
 */
class EffectPlane(ctx: RenderContext) {
    val pose: UMatrixStack = ctx.uMatrixStack()
    private val cam: Vec3 = ctx.camera.pos

    /** Camera right / up vectors in world space, for camera-facing sprites. */
    val camRight: Vector3f = Vector3f(1f, 0f, 0f).rotate(ctx.camera.orientation)
    val camUp: Vector3f = Vector3f(0f, 1f, 0f).rotate(ctx.camera.orientation)

    var batch: FilledBatch? = null

    private var cx = 0f
    private var cy = 0f
    private var cz = 0f
    private val u = Vector3f()
    private val v = Vector3f()
    private var size = 1f

    private var solid = true
    private var r = 1f
    private var g = 1f
    private var b = 1f
    private var a = 1f
    private val corners = FloatArray(16)

    /** Places the surface at a world position with the given axes, half-size and roll (radians, about the normal). */
    fun place(x: Double, y: Double, z: Double, axisU: Vector3f, axisV: Vector3f, halfSize: Float, roll: Float = 0f): EffectPlane {
        cx = (x - cam.x).toFloat()
        cy = (y - cam.y).toFloat()
        cz = (z - cam.z).toFloat()
        size = halfSize
        if (roll == 0f) {
            u.set(axisU)
            v.set(axisV)
        }
        else {
            val c = cos(roll)
            val s = sin(roll)
            u.set(axisU.x * c + axisV.x * s, axisU.y * c + axisV.y * s, axisU.z * c + axisV.z * s)
            v.set(axisV.x * c - axisU.x * s, axisV.y * c - axisU.y * s, axisV.z * c - axisU.z * s)
        }
        return this
    }

    /** Camera-facing placement. */
    fun billboard(x: Double, y: Double, z: Double, halfSize: Float, roll: Float = 0f) = place(x, y, z, camRight, camUp, halfSize, roll)

    fun color(color: Color, alphaMul: Float = 1f): EffectPlane {
        solid = true
        r = color.red / 255f
        g = color.green / 255f
        b = color.blue / 255f
        a = color.alpha / 255f * alphaMul
        return this
    }

    /** Four-corner gradient: [c0] at (-1,-1), [c1] at (1,-1), [c2] at (1,1), [c3] at (-1,1). */
    fun gradient(c0: Color, c1: Color, c2: Color, c3: Color, alphaMul: Float = 1f): EffectPlane {
        solid = false
        for ((i, c) in arrayOf(c0, c1, c2, c3).withIndex()) {
            corners[i * 4] = c.red / 255f
            corners[i * 4 + 1] = c.green / 255f
            corners[i * 4 + 2] = c.blue / 255f
            corners[i * 4 + 3] = c.alpha / 255f * alphaMul
        }
        return this
    }

    /** Emits one vertex at unit-space ([lx], [ly]) with the current colour times [alpha]. */
    fun vertex(lx: Float, ly: Float, alpha: Float) {
        val batch = batch ?: return
        val x = cx + (u.x * lx + v.x * ly) * size
        val y = cy + (u.y * lx + v.y * ly) * size
        val z = cz + (u.z * lx + v.z * ly) * size

        if (solid) return batch.vertex(pose, x, y, z, r, g, b, a * alpha)

        val s = ((lx + 1f) * 0.5f).coerceIn(0f, 1f)
        val t = ((ly + 1f) * 0.5f).coerceIn(0f, 1f)
        val w0 = (1 - s) * (1 - t)
        val w1 = s * (1 - t)
        val w2 = s * t
        val w3 = (1 - s) * t
        fun mix(o: Int) = corners[o] * w0 + corners[4 + o] * w1 + corners[8 + o] * w2 + corners[12 + o] * w3
        batch.vertex(pose, x, y, z, mix(0), mix(1), mix(2), mix(3) * alpha)
    }
}
