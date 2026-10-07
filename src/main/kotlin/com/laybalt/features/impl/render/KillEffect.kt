package com.laybalt.features.impl.render

import com.laybalt.config.types.ButtonSetting
import com.laybalt.config.types.ColorSetting
import com.laybalt.config.types.DropdownSetting
import com.laybalt.config.types.SliderSetting
import com.laybalt.config.types.SoundSetting
import com.laybalt.config.types.ToggleSetting
import com.laybalt.event.impl.RenderWorldEvent
import com.laybalt.event.impl.TickEvent
import com.laybalt.event.impl.WorldChangeEvent
import com.laybalt.features.Feature
import com.laybalt.utils.render.world.RenderBatcher
import gg.essential.universal.USound
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.LightningBolt
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.Vec3
import java.awt.Color
import kotlin.math.sqrt

/**
 * A visual (and optional sound) when a nearby player or mob dies: a floating cross, a shower of falling lava
 * particles or a client-side lightning bolt. Everything is client-side only.
 */
object KillEffect: Feature("Plays an effect when a nearby player (or mob) dies: a floating cross with a sound, falling lava drops or a lightning bolt.") {
    private const val ORTHODOX = 0
    private const val FALLING_LAVA = 1
    private const val LIGHTNING = 2

    private const val CROSS_MS = 3000L
    private const val COOLDOWN_MS = 5000L

    private val mode by DropdownSetting("Mode", 0, listOf("Orthodox", "Falling Lava", "Lightning Bolt"))
        .withDescription("Orthodox: a glowing cross floats over the body for 3 seconds. Falling Lava: a body-shaped burst of dripping lava. Lightning Bolt: a harmless client-side lightning strike")
    private val mobs by ToggleSetting("Mobs")
        .withDescription("Also triggers for mobs, not only for other players")
    private val ySpeed by SliderSetting("Y Speed", 0, - 10, 10, 1)
        .withDescription("How fast the cross drifts up (positive) or sinks (negative). 0 keeps it still").showIf { mode.value == ORTHODOX }
    private val color by ColorSetting("Color", Color(255, 255, 0, 150))
        .withDescription("Colour of the cross").showIf { mode.value == ORTHODOX }
    private val lineWidth by SliderSetting("Line Width", 3f, 1f, 8f, 0.5f)
        .withDescription("Thickness of the cross lines").showIf { mode.value == ORTHODOX }
    private val throughWalls by ToggleSetting("Through Walls", true)
        .withDescription("Draws the cross on top of blocks").showIf { mode.value == ORTHODOX }

    private val playSound by ToggleSetting("Play Sound", true)
        .withDescription("Plays a sound when the cross appears").showIf { mode.value == ORTHODOX }.section("Sound")
    private val sound by SoundSetting("Sound", SoundEvents.BELL_BLOCK)
        .withDescription("The Minecraft sound to play").showIf { mode.value == ORTHODOX && playSound.value }
    private val volume by SliderSetting("Volume", 100, 0, 100, 1, "%")
        .withDescription("Volume of the sound").showIf { mode.value == ORTHODOX && playSound.value }
    private val testSound by ButtonSetting("Test Sound") { mc.execute { playKillSound() } }
        .withDescription("Plays the selected sound once").showIf { mode.value == ORTHODOX && playSound.value }

    private class Death(val pos: Vec3, val time: Long)

    /** Entity id -> death; kept for [COOLDOWN_MS] so one death triggers once. */
    private val deaths = HashMap<Int, Death>()

    override fun init() {
        register<WorldChangeEvent> { deaths.clear() }

        register<TickEvent.End> {
            val level = mc.level ?: return@register
            val self = mc.player ?: return@register
            val now = System.currentTimeMillis()
            deaths.values.removeIf { now - it.time > COOLDOWN_MS }

            for (entity in level.entitiesForRendering()) {
                if (entity !is LivingEntity || entity === self || entity is ArmorStand) continue
                if (entity !is Player && ! mobs.value) continue
                if (entity.isAlive && entity.health > 0f) continue
                if (entity.distanceToSqr(self) > 128 * 128) continue
                if (deaths.containsKey(entity.id)) continue

                val death = Death(entity.position(), now)
                deaths[entity.id] = death
                when (mode.value) {
                    ORTHODOX -> if (playSound.value) playKillSound()
                    FALLING_LAVA -> lavaBurst(entity)
                    LIGHTNING -> lightning(entity.position())
                }
            }
        }

        register<RenderWorldEvent> {
            if (mode.value != ORTHODOX || deaths.isEmpty()) return@register
            val ctx = event.ctx
            val now = System.currentTimeMillis()
            val cam = ctx.camera.pos

            ctx.matrixStack.pushPose()
            ctx.matrixStack.translate(cam.reverse())
            val pose = ctx.uMatrixStack()
            val batch = RenderBatcher.lineBatch(throughWalls.value)
            val base = color.value
            val width = lineWidth.value

            for (death in deaths.values) {
                val elapsed = now - death.time
                if (elapsed > CROSS_MS) continue
                val fade = if (elapsed < CROSS_MS - 500) 1f else (CROSS_MS - elapsed) / 500f
                val r = base.red / 255f
                val g = base.green / 255f
                val b = base.blue / 255f
                val a = base.alpha / 255f * fade

                // Cross bar points sideways to the viewer so it is always seen from the front.
                val dx = death.pos.x - cam.x
                val dz = death.pos.z - cam.z
                val len = sqrt(dx * dx + dz * dz).takeIf { it > 1e-4 } ?: 1.0
                val sx = (- dz / len).toFloat()
                val sz = (dx / len).toFloat()

                val s = ySpeed.value / 100.0 * (elapsed / 50.0)
                val x = death.pos.x.toFloat()
                val y = (death.pos.y + s).toFloat()
                val z = death.pos.z.toFloat()

                fun line(a1: Float, y1: Float, a2: Float, y2: Float) =
                    batch.line(pose, x + sx * a1, y + y1, z + sz * a1, x + sx * a2, y + y2, z + sz * a2, r, g, b, a, width)

                line(0f, 0f, 0f, 3f)
                line(1f, 2.3f, - 1f, 2.3f)
                line(0.5f, 1.2f, - 0.5f, 0.8f)
            }
            ctx.matrixStack.popPose()
        }
    }

    override fun onDisable() {
        super.onDisable()
        deaths.clear()
    }

    private fun playKillSound() = USound.playSoundStatic(sound.value, volume.value / 100f, 1f)

    /** Fills the dead entity's bounding box with a 0.1-block grid of falling lava drops. */
    private fun lavaBurst(entity: Entity) {
        val level = mc.level ?: return
        val w = entity.bbWidth.toDouble()
        val h = entity.bbHeight.toDouble()
        val nx = (w * 10).toInt().coerceIn(1, 20)
        val ny = (h * 10).toInt().coerceIn(1, 40)
        for (ix in 0 until nx) for (iy in 0 until ny) for (iz in 0 until nx) {
            level.addParticle(
                ParticleTypes.FALLING_LAVA,
                entity.x - w / 2 + ix * 0.1, entity.y + iy * 0.1, entity.z - w / 2 + iz * 0.1,
                0.0, 0.0, 0.0
            )
        }
    }

    private var nextBoltId = - 1_000_000

    /** Adds a visual-only lightning bolt to the client world; it removes itself once its flashes are over. */
    private fun lightning(pos: Vec3) {
        val level = mc.level ?: return
        val bolt = LightningBolt(EntityTypes.LIGHTNING_BOLT, level)
        bolt.setVisualOnly(true)
        bolt.snapTo(pos)
        bolt.setId(nextBoltId--)
        if (nextBoltId < - 2_000_000) nextBoltId = - 1_000_000
        level.addEntity(bolt)
    }
}
