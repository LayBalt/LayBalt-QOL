// Ported from Odin (https://github.com/odtheking/Odin), BSD-3-Clause, Copyright (c) 2025 odtheking
package com.laybalt.features.impl.dungeon

import com.laybalt.config.types.*
import com.laybalt.event.impl.MainThreadPacketReceivedEvent
import com.laybalt.event.impl.RenderWorldEvent
import com.laybalt.event.impl.TickEvent
import com.laybalt.event.impl.WorldChangeEvent
import com.laybalt.features.Feature
import com.laybalt.utils.ChatUtils
import com.laybalt.utils.ChatUtils.unformattedText
import com.laybalt.utils.ColorUtils.withAlpha
import com.laybalt.utils.dungeons.map.handlers.ScoreCalculation
import com.laybalt.utils.location.LocationUtils
import com.laybalt.utils.render.RenderHelper.renderBoundingBox
import com.laybalt.utils.render.world.Render3D.renderBoxBounds
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket
import net.minecraft.world.entity.monster.zombie.Zombie
import java.awt.Color
import java.util.concurrent.*

/**
 * Announces Mimic, Prince and Bat kills in dungeons.
 *
 * Kill state itself lives in [ScoreCalculation] (which also learns about kills from teammates' party messages);
 * this feature detects the kills that happen on this client one packet earlier (in [MainThreadPacketReceivedEvent.Pre],
 * before [ScoreCalculation] marks them), so it only announces kills nobody announced yet.
 */
object Mimic: Feature("Detects and announces Mimic, Prince and Bat kills in dungeons.") {
    private val sendMimic by ToggleSetting("Send Mimic Message", true).section("Party Messages")
        .withDescription("Sends a party chat message when the Mimic dies on F6/F7 (death packet of the baby zombie or the Mimic charm message).")
    private val mimicMessage by TextInputSetting("Mimic Message", "Mimic Killed!")
        .withDescription("The party chat message sent when the Mimic dies.")
        .showIf { sendMimic.value }
    private val sendPrince by ToggleSetting("Send Prince Message", true)
        .withDescription("Sends a party chat message when you kill a Prince (+1 bonus score).")
    private val princeMessage by TextInputSetting("Prince Message", "Prince Killed!")
        .withDescription("The party chat message sent when a Prince dies.")
        .showIf { sendPrince.value }
    private val sendBat by ToggleSetting("Send Bat Message", true)
        .withDescription("Sends a party chat message the first time you kill a bonus-score Bat in a run.")
    private val batMessage by TextInputSetting("Bat Message", "Bat Killed!")
        .withDescription("The party chat message sent when a bonus-score Bat dies.")
        .showIf { sendBat.value }

    private val mimicTitle by ToggleSetting("Mimic Title", false).section("Titles")
        .withDescription("Shows a title when the Mimic is marked as killed, including kills announced by teammates.")
    private val princeTitle by ToggleSetting("Prince Title", false)
        .withDescription("Shows a title when a Prince is marked as killed, including kills announced by teammates.")

    private val highlightMimic by ToggleSetting("Highlight Mimic", false).section("Highlight")
        .withDescription("Draws a box around the Mimic (baby zombie) on F6/F7 while it is alive, so it is easy to find after opening its chest.")
    private val highlightStyle by DropdownSetting("Box Style", 1, listOf("Fill", "Outline", "Filled Outline"))
        .withDescription("How the Mimic box is drawn.")
        .showIf { highlightMimic.value }
    private val highlightColor by ColorSetting("Box Color", Color(255, 85, 85).withAlpha(150))
        .withDescription("Colour of the Mimic box.")
        .showIf { highlightMimic.value }
    private val highlightPhase by ToggleSetting("Through Walls", false)
        .withDescription("Renders the Mimic box through blocks.")
        .showIf { highlightMimic.value }

    private val princeRegex = Regex("^A Prince falls\\. \\+1 Bonus Score$")
    private val batRegex = Regex("^A Bat has been slain\\. \\+1 Bonus Score$")
    private val mimicCharmRegex = Regex("^charm you charmed a mimic and captured (?:\\d+ shards from it|its shard)\\.$", RegexOption.IGNORE_CASE)

    private val mimics = CopyOnWriteArrayList<Zombie>()
    private var lastMimicKilled = false
    private var lastPrinceKilled = false
    private var batAnnounced = false
    private var tickCounter = 0

    private val inMimicFloor get() = LocationUtils.inDungeon && ! LocationUtils.inBoss && (LocationUtils.dungeonFloorNumber ?: 0) >= 6

    override fun init() {
        register<MainThreadPacketReceivedEvent.Pre> {
            when (val packet = event.packet) {
                is ClientboundEntityEventPacket -> {
                    if (packet.eventId.toInt() != 3 || ! inMimicFloor || ScoreCalculation.mimicKilled) return@register
                    val zombie = packet.getEntity(level) as? Zombie ?: return@register
                    if (! zombie.isBaby) return@register
                    onMimicKilled()
                }

                is ClientboundSystemChatPacket -> {
                    if (packet.overlay() || ! LocationUtils.inDungeon || LocationUtils.inBoss) return@register
                    val message = packet.content.unformattedText

                    when {
                        ! ScoreCalculation.mimicKilled && inMimicFloor && mimicCharmRegex.matches(message) -> onMimicKilled()

                        ! ScoreCalculation.princeKilled && princeRegex.matches(message) -> {
                            if (sendPrince.value) ChatUtils.sendPartyMessage(princeMessage.value)
                        }

                        ! batAnnounced && batRegex.matches(message) -> {
                            batAnnounced = true
                            if (sendBat.value) ChatUtils.sendPartyMessage(batMessage.value)
                        }
                    }
                }
            }
        }

        register<TickEvent.End> {
            if (! LocationUtils.inDungeon) return@register

            if (ScoreCalculation.mimicKilled != lastMimicKilled) {
                lastMimicKilled = ScoreCalculation.mimicKilled
                if (lastMimicKilled && mimicTitle.value) ChatUtils.showTitle("&c&lMimic Killed!")
            }
            if (ScoreCalculation.princeKilled != lastPrinceKilled) {
                lastPrinceKilled = ScoreCalculation.princeKilled
                if (lastPrinceKilled && princeTitle.value) ChatUtils.showTitle("&6&lPrince Killed!")
            }

            if (! highlightMimic.value) return@register
            if (++ tickCounter % 10 != 0) return@register
            mimics.clear()
            if (! inMimicFloor || ScoreCalculation.mimicKilled) return@register
            level.entitiesForRendering().forEach { entity ->
                if (entity is Zombie && entity.isBaby && entity.isAlive) mimics.add(entity)
            }
        }

        register<RenderWorldEvent> {
            if (! highlightMimic.value || mimics.isEmpty()) return@register
            if (! inMimicFloor || ScoreCalculation.mimicKilled) return@register
            val style = highlightStyle.value
            val color = highlightColor.value

            mimics.forEach { zombie ->
                if (! zombie.isAlive) return@forEach
                event.ctx.renderBoxBounds(
                    zombie.renderBoundingBox, color, color,
                    outline = style == 1 || style == 2,
                    fill = style == 0 || style == 2,
                    phase = highlightPhase.value
                )
            }
        }

        register<WorldChangeEvent> {
            mimics.clear()
            lastMimicKilled = false
            lastPrinceKilled = false
            batAnnounced = false
            tickCounter = 0
        }
    }

    private fun onMimicKilled() {
        if (sendMimic.value) ChatUtils.sendPartyMessage(mimicMessage.value)
        // ScoreCalculation marks the kill itself on the Post phase of the same packet; setting it here
        // keeps the state consistent even if the packet is cancelled by another listener.
        ScoreCalculation.mimicKilled = true
    }
}
