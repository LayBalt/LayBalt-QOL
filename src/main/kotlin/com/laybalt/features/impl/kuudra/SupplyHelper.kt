// Ported from Odin (https://github.com/odtheking/Odin), BSD-3-Clause, Copyright (c) 2025 odtheking
package com.laybalt.features.impl.kuudra

import com.laybalt.config.types.ColorSetting
import com.laybalt.config.types.ToggleSetting
import com.laybalt.event.impl.ChatMessageEvent
import com.laybalt.event.impl.RenderWorldEvent
import com.laybalt.features.Feature
import com.laybalt.utils.ChatUtils
import com.laybalt.utils.kuudra.KuudraRender.renderBeacon
import com.laybalt.utils.kuudra.KuudraUtils
import com.laybalt.utils.kuudra.Supply
import com.laybalt.utils.MathUtils.center
import com.laybalt.utils.render.world.Render3D.renderString
import net.minecraft.core.BlockPos
import java.awt.Color
import kotlin.math.cos
import kotlin.math.sin

object SupplyHelper: Feature("Visual aid for the Kuudra supply phase: crate pick-up and drop-off waypoints and supply times.") {
    private val suppliesWaypoints by ToggleSetting("Supplies Waypoints", true)
        .withDescription("Marks every supply crate carried by a giant with a \"Pick Up!\" waypoint.").section("Waypoints")
    private val supplyWaypointColor by ColorSetting("Supply Waypoint Color", Color(0xFFFF55), true)
        .withDescription("Colour of the supply pick-up waypoints.").showIf { suppliesWaypoints.value }
    private val supplyDropWaypoints by ToggleSetting("Supply Drop Waypoints", true)
        .withDescription("Marks every pile on the main platform that still needs a supply with a \"Place Here!\" waypoint. The pile of the supply your party reported missing is green, the others are red.")
    private val renderArea by ToggleSetting("Render Area", true)
        .withDescription("Shows the name of each supply spot (Triangle, X, Equals, Slash, Shop, xCannon, Square) above it.").showIf { supplyDropWaypoints.value }
    private val sendSupplyTime by ToggleSetting("Send Supply Time", true)
        .withDescription("Replaces the \"recovered one of Elle's supplies\" message with one that shows how long after the start of the run the supply was placed.").section("Chat")

    private val supplyPickUpRegex = Regex("^(?:\\[[^]]*])? ?(\\w{1,16}) recovered one of Elle's supplies! \\((\\d)/(\\d)\\)$")

    override fun init() {
        register<ChatMessageEvent> {
            if (! KuudraUtils.inKuudra || ! sendSupplyTime.value || KuudraUtils.phase != 1) return@register
            val (name, current, total) = supplyPickUpRegex.find(event.unformattedText)?.destructured ?: return@register
            val time = if (KuudraUtils.runStartTime == 0L) "?" else KuudraUtils.formatTime(System.currentTimeMillis() - KuudraUtils.runStartTime)

            event.isCanceled = true
            ChatUtils.modMessage("§6$name §a§lrecovered a supply in $time! §r§8($current/$total)")
        }

        register<RenderWorldEvent> {
            if (! KuudraUtils.inKuudra || KuudraUtils.phase != 1) return@register

            if (supplyDropWaypoints.value) Supply.piles.forEach { type ->
                if (! type.isActive) return@forEach
                val color = if (KuudraUtils.missing == type) Color(0x55FF55) else Color(0xFF5555)
                event.ctx.renderBeacon("§ePlace Here!", type.dropOffSpot, color, increase = false, distance = false)
            }

            if (suppliesWaypoints.value) KuudraUtils.giantZombies.forEach {
                val angle = Math.toRadians(it.yRot + 130.0)
                val pos = BlockPos((it.x + 3.7 * cos(angle)).toInt(), 73, (it.z + 3.7 * sin(angle)).toInt())
                event.ctx.renderBeacon("Pick Up!", pos, supplyWaypointColor.value, increase = false)
            }

            if (renderArea.value) Supply.entries.forEach { type ->
                if (type == Supply.None) return@forEach
                event.ctx.renderString("§e${type.name}", type.pickUpSpot.center, scale = 2f)
            }
        }
    }
}
