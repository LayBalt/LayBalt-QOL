package com.laybalt.features.impl.render

import com.laybalt.config.types.ColorSetting
import com.laybalt.config.types.DropdownSetting
import com.laybalt.config.types.SliderSetting
import com.laybalt.config.types.ToggleSetting
import com.laybalt.event.impl.PacketEvent
import com.laybalt.event.impl.RenderWorldEvent
import com.laybalt.event.impl.WorldChangeEvent
import com.laybalt.features.Feature
import com.laybalt.features.impl.render.effects.EffectColors
import com.laybalt.features.impl.render.effects.EffectPlane
import com.laybalt.features.impl.render.effects.EffectShapes
import com.laybalt.utils.render.world.EffectBatcher
import net.minecraft.network.protocol.game.ServerboundAttackPacket
import net.minecraft.world.phys.EntityHitResult
import net.minecraft.world.phys.Vec3
import org.joml.Quaternionf
import org.joml.Vector3f
import java.awt.Color

/**
 * Every time you attack an entity, a glowing bubble appears where your crosshair hit it.
 * The bubble faces the direction you were looking, spins, grows and fades out.
 */
object HitBubbles: Feature("Draws an expanding, spinning and fading bubble at the spot where you hit an entity.") {
    private val lifeTime by SliderSetting("Life Time", 30, 1, 150, 1, " ticks")
        .withDescription("How long each bubble lives. It grows the whole time and fades out towards the end")
    private val throughWalls by ToggleSetting("Through Walls", true)
        .withDescription("Draws bubbles on top of blocks so they are never hidden")
    private val colorMode by DropdownSetting("Color Mode", 1, listOf("Custom", "Rainbow"))
        .withDescription("Rainbow gives each corner of the bubble a different, animated rainbow colour; Custom uses the colour below")
    private val color by ColorSetting("Color", Color(120, 200, 255))
        .withDescription("Bubble colour in Custom mode").showIf { colorMode.value == 0 }

    private class Bubble(val pos: Vec3, val yaw: Float, val pitch: Float) {
        val time = System.currentTimeMillis()
    }

    private val bubbles = ArrayList<Bubble>()
    private val axisX = Vector3f(1f, 0f, 0f)
    private val axisY = Vector3f(0f, 1f, 0f)

    override fun init() {
        register<WorldChangeEvent> { bubbles.clear() }

        register<PacketEvent.Sent> {
            val packet = event.packet as? ServerboundAttackPacket ?: return@register
            val id = packet.entityId
            mc.execute {
                val player = mc.player ?: return@execute
                val entity = mc.level?.getEntity(id) ?: return@execute
                val hit = mc.hitResult
                val point = if (hit is EntityHitResult && hit.entity.id == id) hit.location else entity.boundingBox.center
                bubbles += Bubble(point, - player.yRot, player.xRot)
                if (bubbles.size > 64) bubbles.removeAt(0)
            }
        }

        register<RenderWorldEvent> {
            if (bubbles.isEmpty()) return@register
            val now = System.currentTimeMillis()
            val lifeMs = lifeTime.value * 50L
            bubbles.removeIf { now - it.time >= lifeMs }
            if (bubbles.isEmpty()) return@register

            val ctx = event.ctx
            val plane = EffectPlane(ctx)
            plane.batch = EffectBatcher.batch(additive = true, seeThrough = throughWalls.value)
            val u = Vector3f()
            val v = Vector3f()
            val q = Quaternionf()

            for (bubble in bubbles) {
                val elapsed = (now - bubble.time).toFloat()
                val factor = (elapsed / lifeMs).coerceIn(0f, 1f)
                val alpha = 1f - factor
                val half = elapsed / 1500f
                if (half <= 0f) continue

                q.rotationY(Math.toRadians(bubble.yaw.toDouble()).toFloat()).rotateX(Math.toRadians(bubble.pitch.toDouble()).toFloat())
                axisX.rotate(q, u)
                axisY.rotate(q, v)
                plane.place(bubble.pos.x, bubble.pos.y, bubble.pos.z, u, v, half, Math.toRadians(- elapsed / 4.0).toFloat())

                if (colorMode.value == 1) plane.gradient(EffectColors.sync(270), EffectColors.sync(0), EffectColors.sync(180), EffectColors.sync(90), alpha)
                else plane.color(color.value, alpha)
                EffectShapes.bubble(plane)
            }
            EffectBatcher.flush(ctx.collector)
        }
    }

    override fun onDisable() {
        super.onDisable()
        bubbles.clear()
    }
}
