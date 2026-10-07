package com.laybalt.features.impl.render.effects

import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.util.Mth
import java.awt.Color
import kotlin.math.sqrt

/**
 * A tiny client-side particle with bounce physics, shared by Hit Particles and Trails.
 * Ticked on the client tick, drawn with interpolation between [px]/[x] etc.
 */
class EffectParticle(
    @JvmField var x: Double, @JvmField var y: Double, @JvmField var z: Double,
    @JvmField var vx: Double, @JvmField var vy: Double, @JvmField var vz: Double,
    val lifeMs: Long,
    val color: Color,
    /** Start roll in degrees and roll speed in degrees per second. */
    val roll: Float = 0f,
    val rollSpeed: Float = 0f,
    val text: String? = null,
) {
    @JvmField var px = x
    @JvmField var py = y
    @JvmField var pz = z
    val born = System.currentTimeMillis()

    fun age(now: Long) = ((now - born).toFloat() / lifeMs).coerceIn(0f, 1f)
    fun expired(now: Long) = now - born >= lifeMs
    fun rollAt(now: Long) = Math.toRadians(roll + (now - born) / 1000.0 * rollSpeed).toFloat()

    fun ix(pt: Float) = px + (x - px) * pt
    fun iy(pt: Float) = py + (y - py) * pt
    fun iz(pt: Float) = pz + (z - pz) * pt

    /**
     * One physics step: bounce off the block below, reverse horizontally when a block is beside it,
     * optional gravity, a little air drag, then move.
     */
    fun tick(level: ClientLevel, gravity: Double, radius: Double, fall: Boolean) {
        px = x
        py = y
        pz = z

        if (solid(level, x, y - radius, z)) {
            vy = - vy / 1.1
            vx /= 1.1
            vz /= 1.1
        }
        else {
            val h = sqrt(vx * vx + vz * vz).coerceAtLeast(radius)
            var blocked = false
            loop@ for (dx in - 1 .. 1) for (dz in - 1 .. 1) {
                if (dx == 0 && dz == 0) continue
                if (solid(level, x + dx * h, y, z + dz * h)) {
                    blocked = true
                    break@loop
                }
            }
            if (blocked) {
                vx = - vx
                vz = - vz
            }
        }

        if (fall) vy -= gravity
        vx /= 1.005
        vy /= 1.005
        vz /= 1.005

        x += vx
        y += vy
        z += vz
    }

    companion object {
        private val mutable = BlockPos.MutableBlockPos()

        fun solid(level: ClientLevel, x: Double, y: Double, z: Double): Boolean {
            val state = level.getBlockState(mutable.set(Mth.floor(x), Mth.floor(y), Mth.floor(z)))
            return ! state.isAir && state.fluidState.isEmpty
        }
    }
}

object EffectColors {
    /** Time-animated rainbow ("sync") colour; [offset] shifts the hue in degrees. */
    fun sync(offset: Int, alpha: Int = 255): Color {
        val hue = (System.currentTimeMillis() / 20 + offset).mod(360L) / 360f
        val rgb = Color.HSBtoRGB(hue, 1f, 1f)
        return Color((rgb and 0xFFFFFF) or (alpha.coerceIn(0, 255) shl 24), true)
    }

    /** Fixed hue (0..1) at full saturation and brightness. */
    fun hue(hue: Float, alpha: Int = 255): Color {
        val rgb = Color.HSBtoRGB(hue, 1f, 1f)
        return Color((rgb and 0xFFFFFF) or (alpha.coerceIn(0, 255) shl 24), true)
    }

    fun lerp(a: Color, b: Color, t: Float): Color {
        val f = t.coerceIn(0f, 1f)
        return Color(
            (a.red + (b.red - a.red) * f).toInt(),
            (a.green + (b.green - a.green) * f).toInt(),
            (a.blue + (b.blue - a.blue) * f).toInt(),
            (a.alpha + (b.alpha - a.alpha) * f).toInt(),
        )
    }
}
