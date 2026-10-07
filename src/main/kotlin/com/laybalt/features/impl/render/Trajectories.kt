// Ported from OdinClient (https://github.com/skies-starred/OdinClient), BSD-3-Clause, Copyright (c) 2026 Starred
/**
 * Taken from OdinClient 1.8.9
 * odtheking's BSD-3 Clause License applies to this file.
 *
 * BSD 3-Clause License
 *
 * Copyright (c) 2023-2025, odtheking
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its
 *    contributors may be used to endorse or promote products derived from
 *    this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.laybalt.features.impl.render

//#if CHEAT

import com.laybalt.config.types.ColorSetting
import com.laybalt.config.types.SliderSetting
import com.laybalt.config.types.ToggleSetting
import com.laybalt.event.impl.RenderWorldEvent
import com.laybalt.event.impl.TickEvent
import com.laybalt.features.Feature
import com.laybalt.utils.ColorUtils.withAlpha
import com.laybalt.utils.items.ItemUtils.skyblockId
import com.laybalt.utils.render.RenderHelper
import com.laybalt.utils.render.RenderHelper.renderBoundingBox
import com.laybalt.utils.render.RenderHelper.renderVec
import com.laybalt.utils.render.world.Render3D.renderBoxBounds
import com.laybalt.utils.render.world.Render3D.renderLine
import com.laybalt.utils.render.world.RenderContext
import net.minecraft.core.Direction
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.projectile.arrow.AbstractArrow
import net.minecraft.world.item.BowItem
import net.minecraft.world.item.EnderpearlItem
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import java.awt.Color
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

object Trajectories: Feature("Predicts and draws the flight path of bow arrows (all three Terminator arrows) and ender pearls, with impact boxes and the entities they will hit.") {
    private val bows by ToggleSetting("Bows", true)
        .withDescription("Show the trajectory of arrows while holding a bow. Terminators show all three arrows.")
    private val pearls by ToggleSetting("Pearls", true)
        .withDescription("Show the trajectory of ender pearls while holding one (Spirit Leaps are ignored).")
    private val plane by ToggleSetting("Show Plane", false)
        .withDescription("Draws a flat square on the face of the block that will be hit.")
    private val boxes by ToggleSetting("Show Boxes", true)
        .withDescription("Draws a small box where the arrow or pearl will land.")
    private val entities by ToggleSetting("Show Entities", true)
        .withDescription("Highlights the entity that an arrow will hit.")
    private val lines by ToggleSetting("Show Lines", true)
        .withDescription("Draws the predicted path as a line.")
    private val range by SliderSetting("Solver Range", 30, 1, 120, 1, " ticks")
        .withDescription("How many ticks of flight are simulated. Higher values show longer paths.")
    private val width by SliderSetting("Line Width", 1f, 0.1f, 5f, 0.1f)
        .withDescription("Thickness of the trajectory line and box outlines.")
    private val planeSize by SliderSetting("Plane Size", 2f, 0.1f, 5f, 0.1f)
        .withDescription("Size of the impact plane.")
        .showIf { plane.value }
    private val boxSize by SliderSetting("Box Size", 0.5f, 0.5f, 3f, 0.1f)
        .withDescription("Size of the impact box.")
        .showIf { boxes.value }
    private val color by ColorSetting("Color", Color(0, 170, 170), true)
        .withDescription("Color of the line, boxes and plane.")
    private val depth by ToggleSetting("Depth Check", true)
        .withDescription("When on, the trajectory is hidden behind blocks. Turn off to see it through walls.")

    private var charge = 0f
    private var lastCharge = 0f

    private class Trajectory(val points: List<Vec3>, val blockHit: BlockHitResult?, val impactBox: AABB?, val entity: Entity?)

    override fun init() {
        register<TickEvent.End> {
            val player = mc.player ?: return@register
            lastCharge = charge
            charge = min((72000 - player.useItemRemainingTicks) / 20f, 1f) * 2f
            if (lastCharge - charge > 1f) lastCharge = charge
        }

        register<RenderWorldEvent> {
            val player = mc.player ?: return@register
            val held = player.mainHandItem

            val trajectories = when {
                bows.value && held.item is BowItem -> {
                    if (held.skyblockId == "TERMINATOR") listOf(0f, -5f, 5f).mapNotNull { simulate(it, isPearl = false, useCharge = false) }
                    else listOfNotNull(simulate(0f, isPearl = false, useCharge = true))
                }
                pearls.value && held.item is EnderpearlItem && "Spirit" !in held.hoverName.string ->
                    listOfNotNull(simulate(0f, isPearl = true, useCharge = false))
                else -> return@register
            }

            for (trajectory in trajectories) event.ctx.draw(trajectory)
        }
    }

    private fun RenderContext.draw(trajectory: Trajectory) {
        val color = color.value
        val phase = ! depth.value
        val lineWidth = width.value

        if (lines.value) trajectory.points.zipWithNext { a, b -> renderLine(a, b, color, lineWidth, phase) }

        if (boxes.value) trajectory.impactBox?.let {
            renderBoxBounds(it, color, color.withAlpha((color.alpha * 0.3f).toInt()), outline = true, fill = true, phase = phase, lineWidth = lineWidth)
        }

        if (entities.value) trajectory.entity?.let {
            renderBoxBounds(it.renderBoundingBox, color, color.withAlpha((color.alpha * 0.3f).toInt()), outline = true, fill = true, phase = phase, lineWidth = lineWidth)
        }

        if (plane.value) trajectory.blockHit?.let { hit ->
            val s = 0.15 * planeSize.value
            val t = 0.02
            val (dx, dy, dz) = when (hit.direction.axis) {
                Direction.Axis.Y -> Triple(s, t, s)
                Direction.Axis.Z -> Triple(s, s, t)
                else -> Triple(t, s, s)
            }
            val l = hit.location
            renderBoxBounds(
                AABB(l.x - dx, l.y - dy, l.z - dz, l.x + dx, l.y + dy, l.z + dz),
                color, color.withAlpha((color.alpha * 0.5f).toInt()), outline = false, fill = true, phase = phase
            )
        }
    }

    /** Simulates one projectile tick by tick. Arrows also check entities along each step (not through walls); pearls only blocks. */
    private fun simulate(yawOffset: Float, isPearl: Boolean, useCharge: Boolean): Trajectory? {
        val player = mc.player ?: return null
        val level = mc.level ?: return null

        val yawRad = Math.toRadians(player.yRot.toDouble())
        var pos = player.renderVec.add(-cos(yawRad) * 0.16, player.eyeHeight - 0.1, -sin(yawRad) * 0.16)
        val speed = if (isPearl) 1.5 else pull(useCharge) * 3.0
        var motion = lookVector(player.yRot + yawOffset, player.xRot).normalize().scale(speed)

        val points = mutableListOf<Vec3>()
        val boxRadius = 0.15 * boxSize.value

        repeat(range.value) {
            points.add(pos)
            val next = pos.add(motion)

            val blockHit = level.clip(ClipContext(pos, next, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player))
            val hitsBlock = blockHit.type == HitResult.Type.BLOCK
            val segmentEnd = if (hitsBlock) blockHit.location else next

            if (! isPearl) {
                val hit = level.getEntities(player, AABB(pos, segmentEnd).inflate(1.0))
                    .asSequence()
                    .filter { it !is AbstractArrow && it !is ArmorStand }
                    .mapNotNull { entity ->
                        entity.boundingBox.inflate(entity.pickRadius.toDouble()).clip(pos, segmentEnd).map { entity to it }.orElse(null)
                    }
                    .minByOrNull { (_, hitPos) -> pos.distanceToSqr(hitPos) }

                if (hit != null) {
                    points.add(hit.second)
                    return Trajectory(points, null, null, hit.first)
                }
            }

            if (hitsBlock) {
                val l = blockHit.location
                points.add(l)
                val box = AABB(l.x - boxRadius, l.y - boxRadius, l.z - boxRadius, l.x + boxRadius, l.y + boxRadius, l.z + boxRadius)
                return Trajectory(points, blockHit, box, null)
            }

            if (isPearl) {
                motion = Vec3(motion.x * 0.99, (motion.y - 0.03) * 0.99, motion.z * 0.99)
                pos = pos.add(motion)
            }
            else {
                pos = next
                motion = Vec3(motion.x * 0.99, motion.y * 0.99 - 0.05, motion.z * 0.99)
            }
        }

        return Trajectory(points, null, null, null)
    }

    private fun lookVector(yaw: Float, pitch: Float): Vec3 {
        val f = - cos(- pitch * 0.017453292)
        return Vec3(
            sin(- yaw * 0.017453292 - Math.PI) * f,
            sin(- pitch * 0.017453292),
            cos(- yaw * 0.017453292 - Math.PI) * f
        )
    }

    /** Vanilla bow power for the current draw progress (1 when the charge is not used, e.g. Terminator). */
    private fun pull(useCharge: Boolean): Float {
        if (! useCharge) return 1f
        val t = lastCharge + (charge - lastCharge) * RenderHelper.partialTicks
        val f = (t / 2f).coerceIn(0f, 1f)
        return (f * f + f * 2f) / 3f
    }
}
//#endif
