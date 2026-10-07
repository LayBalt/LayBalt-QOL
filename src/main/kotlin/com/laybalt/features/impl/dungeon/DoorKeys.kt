package com.laybalt.features.impl.dungeon

import com.laybalt.config.types.ColorSetting
import com.laybalt.config.types.ToggleSetting
import com.laybalt.event.impl.*
import com.laybalt.features.Feature
import com.laybalt.utils.ChatUtils.unformattedText
import com.laybalt.utils.ColorUtils.withAlpha
import com.laybalt.utils.MathUtils.add
import com.laybalt.utils.location.LocationUtils
import com.laybalt.utils.render.RenderHelper.renderVec
import com.laybalt.utils.render.world.Render3D.renderBox
import com.laybalt.utils.render.world.Render3D.renderTracer
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.decoration.ArmorStand
import java.awt.Color

//#if LEGIT
//$import com.laybalt.utils.render.LegitEntityVisibility
//#endif

object DoorKeys: Feature("ESP box & Tracer for wither and blood doors.") {
    private val highlightWither by ToggleSetting("Wither Key").section("Keys")
    private val highlightBlood by ToggleSetting("Blood Key")
    private val witherColor by ColorSetting("Wither Key Color", Color.BLACK.withAlpha(60)).showIf { highlightWither.value }.section("Colors")
    private val bloodColor by ColorSetting("Blood Key Color", Color.RED.withAlpha(60)).showIf { highlightBlood.value }

    private var doorKey: Pair<Entity, Color>? = null

    override fun init() {
        register<WorldChangeEvent> { doorKey = null }

        register<MainThreadPacketReceivedEvent.Post> {
            if (! LocationUtils.inDungeon || LocationUtils.inBoss) return@register
            val packet = event.packet as? ClientboundSetEntityDataPacket ?: return@register
            val entity = level.getEntity(packet.id) as? ArmorStand ?: return@register

            doorKey = Pair(entity, when (entity.customName?.unformattedText) {
                "Wither Key" if highlightWither.value -> witherColor.value
                "Blood Key" if highlightBlood.value -> bloodColor.value
                else -> return@register
            })
        }

        register<RenderWorldEvent> {
            doorKey?.let { (entity, color) ->
                if (! entity.isAlive) {
                    doorKey = null
                    return@register
                }

                //#if LEGIT
                //$ if (! LegitEntityVisibility.isVisible(player, entity)) return@register
                //#endif

                event.ctx.renderTracer(entity.renderVec.add(y = 1.7), color, 2)
                event.ctx.renderBox(
                    entity.x,
                    entity.y + 1.2f,
                    entity.z,
                    width = 0.8,
                    height = 0.8, color,
                    phase = true,
                )
            }
        }
    }
}