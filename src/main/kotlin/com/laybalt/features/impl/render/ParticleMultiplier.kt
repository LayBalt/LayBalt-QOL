package com.laybalt.features.impl.render

import com.laybalt.config.types.SliderSetting
import com.laybalt.config.types.TextInputSetting
import com.laybalt.features.Feature
import net.minecraft.client.particle.Particle
import net.minecraft.client.particle.ParticleEngine
import net.minecraft.core.particles.ParticleOptions
import net.minecraft.core.particles.ParticleType
import net.minecraft.core.registries.BuiltInRegistries
import java.util.IdentityHashMap
import kotlin.random.Random

/**
 * Scales how many particles spawn, how big they are, caps how many can be alive and blocks chosen particle types.
 *
 * Fixed per-effect hides (explosions, block break, fireworks...) stay in No Render; this feature is the generic,
 * id-based counterpart. Ids are the particle registry names, e.g. "crit", "minecraft:flame", "enchanted_hit".
 *
 * @see com.laybalt.mixin.MixinParticleEngineParticleMultiplier
 */
object ParticleMultiplier: Feature("Spawns more or fewer particles, resizes them, caps how many exist and blocks particle types you list.") {
    private val amount by SliderSetting("Amount", 1.0, 0.0, 5.0, 0.1, "x")
        .withDescription("Particle count multiplier. 0.5 spawns half of them, 2 spawns twice as many. Extra particles are copies of the original one.").section("Spawning")
    private val spread by SliderSetting("Copy Spread", 0.15, 0.0, 1.0, 0.05, " blocks")
        .withDescription("How far extra copies are scattered around the original particle.").showIf { amount.value > 1.0 }
    private val size by SliderSetting("Size", 1.0, 0.25, 4.0, 0.05, "x")
        .withDescription("Scales the size of every affected particle.")
    private val onlyAffect by TextInputSetting("Only Affect", "")
        .withDescription("Comma-separated particle ids. When not empty, Amount and Size only change these particles, e.g. \"crit, enchanted_hit\".")

    private val blacklist by TextInputSetting("Blacklist", "")
        .withDescription("Comma-separated particle ids that never spawn, e.g. \"smoke, large_smoke, minecraft:dust\".").section("Filters")
    private val maxParticles by SliderSetting("Max Particles", 0, 0, 16000, 250)
        .withDescription("Highest number of particles alive at once; new ones are dropped above it. 0 keeps the vanilla limit.")

    private val idCache = IdentityHashMap<ParticleType<*>, String>()
    private var blacklistText: String? = null
    private var blacklistIds = emptySet<String>()
    private var onlyText: String? = null
    private var onlyIds = emptySet<String>()
    private var spawningCopies = false

    private fun idOf(type: ParticleType<*>) = idCache.getOrPut(type) { BuiltInRegistries.PARTICLE_TYPE.getKey(type)?.toString() ?: "" }

    private fun parse(text: String) = text.split(',', ';', ' ').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        .map { if (':' in it) it else "minecraft:$it" }.toSet()

    private fun blocked(): Set<String> {
        if (blacklistText != blacklist.value) {
            blacklistText = blacklist.value
            blacklistIds = parse(blacklist.value)
        }
        return blacklistIds
    }

    private fun affected(id: String): Boolean {
        if (onlyText != onlyAffect.value) {
            onlyText = onlyAffect.value
            onlyIds = parse(onlyAffect.value)
        }
        return onlyIds.isEmpty() || id in onlyIds
    }

    /** True when the particle should not be created at all (blacklisted, or dropped by an Amount below 1). */
    @JvmStatic
    fun shouldSkip(options: ParticleOptions): Boolean {
        if (! enabled) return false
        val id = idOf(options.type)
        if (id in blocked()) return true
        if (spawningCopies) return false
        val multiplier = amount.value
        return multiplier < 1.0 && affected(id) && Random.nextDouble() >= multiplier
    }

    @JvmStatic
    fun onCreated(engine: ParticleEngine, options: ParticleOptions, particle: Particle, x: Double, y: Double, z: Double, xd: Double, yd: Double, zd: Double) {
        if (! enabled) return
        val id = idOf(options.type)
        if (! affected(id)) return
        val scale = size.value.toFloat()
        if (scale != 1f) particle.scale(scale)
        if (spawningCopies || amount.value <= 1.0) return

        val extra = amount.value - 1.0
        val copies = extra.toInt() + if (Random.nextDouble() < extra % 1.0) 1 else 0
        if (copies <= 0) return
        val s = spread.value
        spawningCopies = true
        try {
            repeat(copies) {
                engine.createParticle(
                    options,
                    x + (Random.nextDouble() - 0.5) * 2 * s, y + (Random.nextDouble() - 0.5) * 2 * s, z + (Random.nextDouble() - 0.5) * 2 * s,
                    xd, yd, zd
                )
            }
        }
        finally {
            spawningCopies = false
        }
    }

    @JvmStatic
    fun hasLimit() = enabled && maxParticles.value > 0

    @JvmStatic
    fun limit() = maxParticles.value
}
