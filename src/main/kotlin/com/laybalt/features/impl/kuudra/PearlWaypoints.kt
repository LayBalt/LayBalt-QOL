// Ported from Odin (https://github.com/odtheking/Odin), BSD-3-Clause, Copyright (c) 2025 odtheking
package com.laybalt.features.impl.kuudra

import com.laybalt.config.types.ColorSetting
import com.laybalt.config.types.ToggleSetting
import com.laybalt.event.impl.RenderWorldEvent
import com.laybalt.features.Feature
import com.laybalt.utils.kuudra.KuudraUtils
import com.laybalt.utils.kuudra.PearlLineups
import com.laybalt.utils.kuudra.Supply
import com.laybalt.utils.render.RenderHelper.renderVec
import com.laybalt.utils.render.world.Render3D.renderBoxBounds
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.awt.Color
import kotlin.math.*

object PearlWaypoints: Feature("Renders ender pearl lineups for the Kuudra supply phase.") {
    private val dynamicWaypoints by ToggleSetting("Dynamic Waypoints", false)
        .withDescription("Calculates the pearl angles from your current position to the drop-off pile and marks them with two small boxes (high and flat throw).")
    private val dynamicWaypointsColor by ColorSetting("Dynamic Color", Color(0xAA00AA), true)
        .withDescription("Colour of the dynamic pearl waypoints.").showIf { dynamicWaypoints.value }
    private val presetWaypoints by ToggleSetting("Preset Waypoints", true)
        .withDescription("Shows the preset lineups: an outline where to stand and a filled block to aim at.")
    private val hideFarWaypoints by ToggleSetting("Hide Far Waypoints", true)
        .withDescription("Only shows the lineup whose standing spot is closest to you.").showIf { presetWaypoints.value }

    override fun init() {
        register<RenderWorldEvent> {
            if (! KuudraUtils.inKuudra || KuudraUtils.phase != 1) return@register
            val playerPos = mc.player?.blockPosition() ?: return@register
            val missing = KuudraUtils.missing

            var closest = true
            for (lineup in PearlLineups.ordered(playerPos)) {
                if (presetWaypoints.value) lineup.startPos.forEach {
                    event.ctx.renderBoxBounds(AABB(it), lineup.color, fill = false, phase = true, lineWidth = 3)
                }

                for (blockPos in lineup.lineups) {
                    val squareFilter = missing == Supply.None || missing == Supply.Square ||
                        lineup.supply != Supply.Square || PearlLineups.squareLineupFor[missing] == blockPos
                    if (! squareFilter || (hideFarWaypoints.value && ! closest)) continue

                    if (presetWaypoints.value) event.ctx.renderBoxBounds(AABB(blockPos), lineup.color, outline = false, phase = true)

                    if (dynamicWaypoints.value) {
                        val destination = if (lineup.supply == Supply.Square) missing else lineup.supply
                        if (destination in Supply.piles) calculatePearl(destination.dropOffSpot)?.let { result ->
                            event.ctx.renderBoxBounds(AABB.ofSize(result.upAngle, 0.12, 0.12, 0.12), dynamicWaypointsColor.value, outline = false, phase = true)
                            event.ctx.renderBoxBounds(AABB.ofSize(result.flatAngle, 0.12, 0.12, 0.12), dynamicWaypointsColor.value, outline = false, phase = true)
                        }
                        // Odin outlines lineup.supply's pile, which is (0, 1, 0) for the Square lineup; use the actual destination instead.
                        if (destination in Supply.piles) event.ctx.renderBoxBounds(AABB(destination.dropOffSpot.above()), dynamicWaypointsColor.value, fill = false, phase = true, lineWidth = 3)
                    }
                }
                closest = false
            }
        }
    }

    private const val GRAV = 0.05
    private const val E_VEL = 1.67

    private data class PearlResult(val upAngle: Vec3, val flatAngle: Vec3, val upTiming: Int, val flatTiming: Int)

    // Made by Aidanmao
    private fun calculatePearl(targetPos: BlockPos): PearlResult? {
        val pos = mc.player?.renderVec ?: return null
        val posX = pos.x
        val posY = pos.y
        val posZ = pos.z

        val offX = targetPos.x - posX
        val offY = targetPos.y - (posY + 1.62)
        val offZ = targetPos.z - posZ
        val offHor = hypot(offX, offZ)

        val v2 = E_VEL * E_VEL
        val v4 = v2 * v2
        val g = GRAV * (1.0 + (offHor * 0.0012)) // drag

        val discriminant = v4 - g * (g * (offHor * offHor) + 2 * offY * v2)

        if (discriminant < 0) return PearlResult(Vec3(0.0, 9.0, 0.0), Vec3(0.0, 9.0, 0.0), 0, 0)

        val root = sqrt(discriminant)

        val angle1 = Math.toDegrees(atan((v2 + root) / (g * offHor)))
        val angle2 = Math.toDegrees(atan((v2 - root) / (g * offHor)))

        val uAngle = max(angle1, angle2)
        val fAngle = min(angle1, angle2)

        var uRes = Vec3(0.0, 10.0, 0.0)
        var fRes = Vec3(0.0, 10.0, 0.0)

        var uTiming = 0
        var fTiming = 0

        if (uAngle > 0.0) {
            val pitch = - uAngle
            val radP = Math.toRadians(pitch)
            val radY = - atan2(offX, offZ)

            val vY = E_VEL * sin(Math.toRadians(uAngle))
            val flightTimeFactor = (1.0012).pow(max(offHor / 15, 1.0)) * 0.8
            val fT = (vY + sqrt(vY * vY + 2 * GRAV * (posY + 1.62 - targetPos.y))) / GRAV
            uTiming = floor((fT / (0.992).pow(fT)) * flightTimeFactor).toInt() - 2

            val cosRadP = cos(radP)
            val fX = cosRadP * sin(radY)
            val fY = - sin(radP)
            val fZ = cosRadP * cos(radY)

            uRes = Vec3(posX - fX * 10, posY + fY * 10, posZ + fZ * 10)
        }

        if (fAngle > 0.0) {
            val pitch = - fAngle
            val radP = Math.toRadians(pitch)
            val radY = - atan2(offX, offZ)

            val vX = E_VEL * cos(Math.toRadians(fAngle))

            val drag = 0.978
            val ticks = ln(1 - (offHor * (1 - drag) / vX)) / ln(drag)

            fTiming = if (ticks.isNaN()) (offHor / vX).toInt() else ceil(ticks).toInt()

            val cosRadP = cos(radP)
            val fX = cosRadP * sin(radY)
            val fY = - sin(radP)
            val fZ = cosRadP * cos(radY)

            fRes = Vec3(posX - fX * 10, posY + 1.2 + fY * 10, posZ + fZ * 10)
        }

        return PearlResult(uRes, fRes, uTiming, fTiming)
    }
}
