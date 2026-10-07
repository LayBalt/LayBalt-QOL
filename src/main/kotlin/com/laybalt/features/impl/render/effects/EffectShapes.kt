package com.laybalt.features.impl.render.effects

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Procedural sprite shapes drawn into an [EffectPlane] with quads only (a triangle is a quad whose last two
 * vertices coincide). All shapes fit roughly in the unit circle. No textures are used anywhere.
 */
object EffectShapes {
    private const val TAU = (PI * 2).toFloat()

    private fun tri(p: EffectPlane, x1: Float, y1: Float, a1: Float, x2: Float, y2: Float, a2: Float, x3: Float, y3: Float, a3: Float) {
        p.vertex(x1, y1, a1)
        p.vertex(x2, y2, a2)
        p.vertex(x3, y3, a3)
        p.vertex(x3, y3, a3)
    }

    /** Annulus between [r1] (alpha [a1]) and [r2] (alpha [a2]); r1 = 0 gives a disc / fan. */
    fun band(p: EffectPlane, r1: Float, a1: Float, r2: Float, a2: Float, segments: Int = 24, startAngle: Float = 0f) {
        val step = TAU / segments
        for (i in 0 until segments) {
            val t0 = startAngle + i * step
            val t1 = t0 + step
            val c0 = cos(t0)
            val s0 = sin(t0)
            val c1 = cos(t1)
            val s1 = sin(t1)
            if (r1 <= 0f) tri(p, 0f, 0f, a1, r2 * c0, r2 * s0, a2, r2 * c1, r2 * s1, a2)
            else {
                p.vertex(r1 * c0, r1 * s0, a1)
                p.vertex(r2 * c0, r2 * s0, a2)
                p.vertex(r2 * c1, r2 * s1, a2)
                p.vertex(r1 * c1, r1 * s1, a1)
            }
        }
    }

    fun disc(p: EffectPlane, radius: Float, alpha: Float, segments: Int = 20) = band(p, 0f, alpha, radius, alpha, segments)

    /** Soft round glow: bright centre fading to nothing at [radius]. */
    fun glow(p: EffectPlane, radius: Float = 1f, alpha: Float = 1f, segments: Int = 20) {
        band(p, 0f, alpha, radius * 0.3f, alpha * 0.55f, segments)
        band(p, radius * 0.3f, alpha * 0.55f, radius, 0f, segments)
    }

    /** Ring with soft inner and outer edges, peaking at [peak]. */
    fun softRing(p: EffectPlane, inner: Float, peak: Float, outer: Float, alpha: Float = 1f, segments: Int = 48) {
        band(p, inner, 0f, peak, alpha, segments)
        band(p, peak, alpha, outer, 0f, segments)
    }

    /** Five-pointed star with a faint halo. */
    fun star(p: EffectPlane, alpha: Float = 1f) {
        glow(p, 1.25f, alpha * 0.35f, 16)
        val inner = 0.42f
        val arm = TAU / 5
        for (i in 0 until 5) {
            val tip = (PI / 2).toFloat() + i * arm
            val left = tip - arm / 2
            val right = tip + arm / 2
            p.vertex(0f, 0f, alpha)
            p.vertex(inner * cos(left), inner * sin(left), alpha)
            p.vertex(cos(tip), sin(tip), alpha)
            p.vertex(inner * cos(right), inner * sin(right), alpha)
        }
    }

    /** Heart from the classic parametric curve, with a faint halo. */
    fun heart(p: EffectPlane, alpha: Float = 1f, segments: Int = 36) {
        glow(p, 1.25f, alpha * 0.3f, 16)
        val scale = 1f / 16.5f
        fun hx(t: Float) = 16f * sin(t).let { it * it * it } * scale
        fun hy(t: Float) = (13f * cos(t) - 5f * cos(2 * t) - 2f * cos(3 * t) - cos(4 * t) + 2.5f) * scale
        val cy = - 0.05f
        val step = TAU / segments
        for (i in 0 until segments) {
            val t0 = i * step
            val t1 = t0 + step
            tri(p, 0f, cy, alpha, hx(t0), hy(t0), alpha, hx(t1), hy(t1), alpha)
        }
    }

    /** Three stacked translucent discs (brighter in the middle). */
    fun orbiz(p: EffectPlane, alpha: Float = 1f) {
        disc(p, 1f, 0.4f * alpha)
        disc(p, 0.71f, 0.4f * alpha)
        disc(p, 0.43f, 0.4f * alpha)
    }

    /** A soap-bubble look: soft rim plus a faint fill. */
    fun bubble(p: EffectPlane, alpha: Float = 1f) {
        band(p, 0f, alpha * 0.12f, 0.6f, alpha * 0.2f, 40)
        softRing(p, 0.6f, 0.86f, 1f, alpha, 48)
    }

    /** Swirling portal: three spiral arms around a glowing core inside a thin ring. [spin] in radians. */
    fun portal(p: EffectPlane, spin: Float, alpha: Float = 1f) {
        glow(p, 0.45f, alpha * 0.8f, 20)
        softRing(p, 0.82f, 0.92f, 1f, alpha * 0.9f, 48)
        val steps = 16
        val sweep = PI.toFloat() * 1.2f
        for (arm in 0 until 3) {
            val base = spin + arm * TAU / 3
            for (i in 0 until steps) {
                val f0 = i / steps.toFloat()
                val f1 = (i + 1) / steps.toFloat()
                val r0 = 0.15f + f0 * 0.75f
                val r1 = 0.15f + f1 * 0.75f
                val t0 = base + f0 * sweep
                val t1 = base + f1 * sweep
                val w0 = 0.16f * (1 - f0) + 0.02f
                val w1 = 0.16f * (1 - f1) + 0.02f
                val a0 = alpha * (1 - f0 * 0.7f)
                val a1 = alpha * (1 - f1 * 0.7f)
                p.vertex((r0 - w0) * cos(t0), (r0 - w0) * sin(t0), a0)
                p.vertex((r0 + w0) * cos(t0), (r0 + w0) * sin(t0), a0)
                p.vertex((r1 + w1) * cos(t1), (r1 + w1) * sin(t1), a1)
                p.vertex((r1 - w1) * cos(t1), (r1 - w1) * sin(t1), a1)
            }
        }
    }

    enum class Sprite { ORBIZ, STAR, HEART, BLOOM }

    fun sprite(p: EffectPlane, sprite: Sprite, alpha: Float = 1f) = when (sprite) {
        Sprite.ORBIZ -> orbiz(p, alpha)
        Sprite.STAR -> star(p, alpha)
        Sprite.HEART -> heart(p, alpha)
        Sprite.BLOOM -> glow(p, 1f, alpha)
    }
}
