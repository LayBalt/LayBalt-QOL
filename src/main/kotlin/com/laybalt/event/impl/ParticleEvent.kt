// Ported from Odin (https://github.com/odtheking/Odin), BSD-3-Clause, Copyright (c) 2025 odtheking
package com.laybalt.event.impl

import com.laybalt.event.Event
import net.minecraft.core.particles.ParticleOptions

/**
 * A particle the client is about to add (ClientLevel.doAddParticle, Odin 1acdc46d / b06b9d2a).
 * Cancelling it drops the particle.
 */
class ParticleEvent(
    val particle: ParticleOptions,
    val overrideLimiter: Boolean,
    val alwaysShowParticles: Boolean,
    val x: Double, val y: Double, val z: Double,
    val xd: Double, val yd: Double, val zd: Double
): Event(cancelable = true)
