package com.laybalt.features.impl.render

import com.laybalt.config.types.ColorSetting
import com.laybalt.config.types.DropdownSetting
import com.laybalt.config.types.SliderSetting
import com.laybalt.config.types.ToggleSetting
import com.laybalt.event.impl.RenderWorldEvent
import com.laybalt.event.impl.TickEvent
import com.laybalt.event.impl.WorldChangeEvent
import com.laybalt.features.Feature
import com.laybalt.features.impl.render.effects.EffectColors
import com.laybalt.features.impl.render.effects.EffectParticle
import com.laybalt.features.impl.render.effects.EffectPlane
import com.laybalt.features.impl.render.effects.EffectShapes
import com.laybalt.utils.render.RenderHelper
import com.laybalt.utils.render.world.EffectBatcher
import com.laybalt.utils.render.world.Render3D.renderString
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.phys.Vec3
import java.awt.Color
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Decorative particles that burst out of hurt players (or mobs), or floating damage / heal numbers.
 * Particles are simulated on the client tick (see [EffectParticle]) and drawn as procedural sprites.
 */
object HitParticles: Feature("Spawns decorative particles around players while they are hurt, or floating damage and heal numbers above entities whose health changes.") {
    private const val TEXT = 4
    private const val MAX_PARTICLES = 1500

    private val mode by DropdownSetting("Mode", 1, listOf("Orbiz", "Stars", "Hearts", "Bloom", "Text"))
        .withDescription("Orbiz: soft stacked discs. Stars / Hearts / Bloom: glowing sprites. Text: floating numbers showing how much health an entity lost or gained")
        .onChange { particles.clear() }
    private val physics by DropdownSetting("Physics", 0, listOf("Fall", "Fly"))
        .withDescription("Fall: particles drop with gravity and bounce off blocks. Fly: they drift freely and only bounce off blocks")
    private val onlySelf by ToggleSetting("Self")
        .withDescription("Only spawns particles (or numbers) for your own player")
    private val includeMobs by ToggleSetting("Include Mobs")
        .withDescription("Also spawns particles for hurt mobs, not only players. Text mode always includes mobs").hideIf { onlySelf.value }
    private val amount by SliderSetting("Amount", 2, 1, 5, 1)
        .withDescription("Particles spawned per tick while an entity is hurt (a hit lasts about 10 ticks)").hideIf { mode.value == TEXT }
    private val lifeTime by SliderSetting("Life Time", 2, 1, 10, 1, "s")
        .withDescription("How long each particle or number stays before it disappears")
    private val speed by SliderSetting("Speed", 2, 1, 20, 1)
        .withDescription("How fast particles fly away from the entity")
    private val scale by SliderSetting("Scale", 3f, 1f, 10f, 0.5f)
        .withDescription("Size of the sprites and numbers").hideIf { mode.value == 0 }
    private val throughWalls by ToggleSetting("Through Walls", true)
        .withDescription("Draws the particles on top of blocks so they stay visible behind walls")

    private val colorMode by DropdownSetting("Color Mode", 1, listOf("Custom", "Rainbow"))
        .withDescription("Custom uses the colour below; Rainbow gives each particle a colour from the animated rainbow").hideIf { mode.value == TEXT }.section("Colors")
    private val color by ColorSetting("Color", Color(0x8800FF00.toInt(), true))
        .withDescription("Particle colour; its alpha sets how strong the glow is").showIf { colorMode.value == 0 && mode.value != TEXT }
    private val healColor by ColorSetting("Heal Color", Color(0x2FF410), false)
        .withDescription("Colour of numbers when an entity gains health").showIf { mode.value == TEXT }
    private val damageColor by ColorSetting("Damage Color", Color(0xF14833), false)
        .withDescription("Colour of numbers when an entity loses health").showIf { mode.value == TEXT }

    private val particles = ArrayList<EffectParticle>()
    private val lastHealth = HashMap<Int, Float>()

    override fun init() {
        register<WorldChangeEvent> { clear() }

        register<TickEvent.End> {
            val level = mc.level ?: return@register
            val self = mc.player ?: return@register
            val now = System.currentTimeMillis()

            particles.removeIf { it.expired(now) }
            val fall = physics.value == 0
            val radius = if (mode.value == TEXT) 0.1 else scale.value / 10.0
            for (p in particles) p.tick(level, 0.035, radius, fall)

            if (mode.value == TEXT) {
                trackHealth(self)
                return@register
            }
            lastHealth.clear()

            val targets: List<LivingEntity> = when {
                onlySelf.value -> listOf(self)
                includeMobs.value -> level.entitiesForRendering().filterIsInstance<LivingEntity>().filter { it !is ArmorStand && it.distanceToSqr(self) < 64 * 64 }
                else -> level.players()
            }

            for (entity in targets) {
                if (entity.hurtTime <= 0 || ! entity.isAlive) continue
                repeat(amount.value) { spawn(entity.x, Random.nextDouble(entity.y, entity.y + entity.bbHeight), entity.z, null, null) }
            }
        }

        register<RenderWorldEvent> {
            if (particles.isEmpty()) return@register
            val ctx = event.ctx
            val pt = RenderHelper.partialTicks
            val now = System.currentTimeMillis()

            if (mode.value == TEXT) {
                for (p in particles) {
                    val text = p.text ?: continue
                    val fade = fade(p.age(now))
                    val c = p.color
                    val alpha = (255 * fade).toInt().coerceIn(8, 255)
                    ctx.renderString(text, Vec3(p.ix(pt), p.iy(pt) + 0.1, p.iz(pt)), Color(c.red, c.green, c.blue, alpha), scale.value, throughWalls.value)
                }
                return@register
            }

            val sprite = EffectShapes.Sprite.entries[mode.value.coerceIn(0, 3)]
            val plane = EffectPlane(ctx)
            plane.batch = EffectBatcher.batch(additive = sprite != EffectShapes.Sprite.ORBIZ, seeThrough = throughWalls.value)
            val half = if (sprite == EffectShapes.Sprite.ORBIZ) 0.1f else scale.value * 0.05f

            for (p in particles) {
                plane.billboard(p.ix(pt), p.iy(pt) + 0.1, p.iz(pt), half, p.rollAt(now)).color(p.color, fade(p.age(now)))
                EffectShapes.sprite(plane, sprite)
            }
            EffectBatcher.flush(ctx.collector)
        }
    }

    override fun onDisable() {
        super.onDisable()
        clear()
    }

    private fun clear() {
        particles.clear()
        lastHealth.clear()
    }

    /** Full strength for most of the life, fading out over the last 30 %. */
    private fun fade(age: Float) = if (age < 0.7f) 1f else ((1f - age) / 0.3f).coerceIn(0f, 1f)

    private fun spawn(x: Double, y: Double, z: Double, text: String?, textColor: Color?) {
        if (particles.size >= MAX_PARTICLES) particles.removeAt(0)
        val s = speed.value / 50.0
        val c = textColor ?: if (colorMode.value == 0) color.value else EffectColors.sync(Random.nextInt(360), color.value.alpha.coerceAtLeast(160))
        particles += EffectParticle(
            x, y, z,
            Random.nextDouble(- s, s), Random.nextDouble(- s, s), Random.nextDouble(- s, s),
            lifeTime.value * 1000L, c,
            roll = Random.nextFloat() * 180f,
            rollSpeed = 10f + Random.nextFloat() * 50f,
            text = text
        )
    }

    /** Text mode: compares health + absorption of nearby living entities with the previous tick. */
    private fun trackHealth(self: LivingEntity) {
        val level = mc.level ?: return
        val seen = HashSet<Int>()
        val candidates = if (onlySelf.value) listOf(self)
        else level.entitiesForRendering().filterIsInstance<LivingEntity>().filter { it !is ArmorStand && it.distanceToSqr(self) < 16 * 16 }

        for (entity in candidates) {
            val id = entity.id
            seen += id
            val hp = entity.health + entity.absorptionAmount
            val previous = lastHealth.put(id, hp) ?: continue
            val delta = hp - previous
            if (abs(delta) < 0.05f || ! entity.isAlive && delta > 0) continue
            val text = (if (delta > 0) "+" else "-") + formatDelta(abs(delta))
            spawn(entity.x, entity.y + entity.bbHeight * 0.75, entity.z, text, if (delta > 0) healColor.value else damageColor.value)
        }
        lastHealth.keys.retainAll(seen)
    }

    private fun formatDelta(value: Float): String {
        val rounded = (value * 10).roundToInt() / 10f
        return if (rounded % 1f == 0f) rounded.toInt().toString() else rounded.toString()
    }
}
