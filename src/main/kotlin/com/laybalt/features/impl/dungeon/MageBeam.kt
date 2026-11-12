package com.laybalt.features.impl.dungeon

// Depth Check, Hide Particles, Min Points and the world-change reset are ported from Odin
// (https://github.com/odtheking/Odin), BSD-3-Clause, Copyright (c) 2025 odtheking

import net.minecraft.world.entity.EntityTypes
import com.laybalt.config.types.ColorSetting
import com.laybalt.config.types.SliderSetting
import com.laybalt.config.types.ToggleSetting
import com.laybalt.event.impl.MainThreadPacketReceivedEvent
import com.laybalt.event.impl.RenderWorldEvent
import com.laybalt.event.impl.WorldChangeEvent
import com.laybalt.features.Feature
import com.laybalt.ui.utils.Animation
import com.laybalt.utils.ColorUtils.withAlpha
import com.laybalt.utils.ThreadUtils
import com.laybalt.utils.dungeons.DungeonListener
import com.laybalt.utils.location.LocationUtils
import com.laybalt.utils.render.world.Render3D.renderLine
import com.laybalt.utils.render.world.Render3D.renderRainbowLine
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket
import net.minecraft.world.phys.Vec3
import java.awt.Color
import kotlin.math.abs

object MageBeam: Feature("Renders a fully custom, animated beam whenever a mage casts their beam ability.") {
    private val color by ColorSetting("Primary Color", Color.WHITE, false).withDescription("The color of the beam line")
    private val lineWidth by SliderSetting("Line Width", 2, 1, 6, 1, "px").withDescription("Thickness of the line.")
    private val duration by SliderSetting("Duration", 40, 5, 100, 1, " ticks").withDescription("How long the beam shows.")
    private val minPoints by SliderSetting("Min Points", 6, 2, 10, 1).withDescription("How many beam particles must arrive in a line before the beam is drawn. Lower values draw short beams sooner but may draw stray firework sparks")
    private val depthCheck by ToggleSetting("Depth Check", true).withDescription("Hides the parts of the beam that are behind blocks. Turn off to see beams through walls")
    private val hideParticles by ToggleSetting("Hide Particles", true).withDescription("Hides the vanilla firework particles the beam is made of, leaving only the custom line")
    private val beamCancel by ToggleSetting("Cancel Mage Beam").withDescription("Hides mage beams completely: the particles are removed and no custom line is drawn")
    private val fade by ToggleSetting("Fade").withDescription("Animates the beam slowly disappearing")
    private val hideSheep by ToggleSetting("Hide Sheep", true).withDescription("Prevents the Sheep from spawning.")
    private val rainbow by ToggleSetting("&dI am Skizo!!!!").withDescription("Draws the beam as a moving rainbow instead of the primary color")

    private val beams = linkedSetOf<Beam>()

    override fun init() {
        register<WorldChangeEvent> { beams.clear() }

        register<MainThreadPacketReceivedEvent.Pre> {
            if (! LocationUtils.inDungeon) return@register
            when (val packet = event.packet) {
                is ClientboundLevelParticlesPacket -> {
                    if (packet.particle.type != ParticleTypes.FIREWORK) return@register
                    if (hideParticles.value || beamCancel.value) event.isCanceled = true
                    if (beamCancel.value) return@register
                    Beam.onPoint(Vec3(packet.x, packet.y, packet.z), DungeonListener.currentTime, beams.lastOrNull())
                }

                is ClientboundAddEntityPacket -> {
                    if (! hideSheep.value) return@register
                    if (packet.type != EntityTypes.SHEEP) return@register
                    if (player.position().distanceToSqr(packet.x, packet.y, packet.z) > 9) return@register
                    event.isCanceled = true
                }
            }
        }

        register<RenderWorldEvent> {
            for (beam in beams) {
                if (beam.points.size < minPoints.value) continue
                val alpha = if (fade.value) beam.anim.update(0f).let { beam.anim.value } else 1f
                if (rainbow.value) event.ctx.renderRainbowLine(beam.min, beam.max, lineWidth.value, alpha)
                else event.ctx.renderLine(beam.min, beam.max, color.value.withAlpha(alpha), lineWidth.value, ! depthCheck.value)
            }
        }
    }

    private class Beam(point: Vec3, var updateTick: Long) {
        val points = mutableListOf(point)

        val anim by lazy { Animation(duration.value * 50L, 1f) }
        val min get() = points.first()
        val max get() = points.last()

        fun inLine(point: Vec3): Boolean {
            if (points.size < 2) return true

            val onLine = abs(max.subtract(min).normalize().dot(point.subtract(max).normalize())) > 0.99
            val distSq = point.distanceToSqr(max)

            return onLine && distSq < pointSpace * pointSpace
        }

        companion object {
            const val pointSpace = 0.5 // avg distance between 2 particals

            fun onPoint(point: Vec3, tick: Long, beam: Beam?) {
                if (beams.any { point in it.points }) return // hypixel sends the same beam twise, thx hypixel

                if (beam != null && tick - beam.updateTick <= 1 && beam.inLine(point)) {
                    beam.points.add(point)
                    beam.updateTick = tick
                }
                else {
                    val newBeam = Beam(point, tick)
                    ThreadUtils.scheduledTaskServer(duration.value) { beams.remove(newBeam) }
                    beams.add(newBeam)
                }
            }
        }
    }
}
