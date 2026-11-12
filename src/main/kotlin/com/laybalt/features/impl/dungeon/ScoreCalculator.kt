// Map Info part ported from Odin (https://github.com/odtheking/Odin), BSD-3-Clause, Copyright (c) 2025 odtheking
package com.laybalt.features.impl.dungeon

import com.laybalt.config.ConfigHolder
import com.laybalt.config.types.*
import com.laybalt.event.impl.*
import com.laybalt.features.Feature
import com.laybalt.utils.*
import com.laybalt.utils.dungeons.map.core.RoomState
import com.laybalt.utils.dungeons.map.core.RoomType
import com.laybalt.utils.dungeons.map.handlers.DungeonScanner
import com.laybalt.utils.dungeons.map.handlers.ScoreCalculation
import com.laybalt.utils.dungeons.map.utils.ScanUtils
import com.laybalt.utils.location.LocationUtils
import com.laybalt.utils.render.Render2D.drawRect
import com.laybalt.utils.render.Render2D.drawString
import com.laybalt.utils.render.RenderHelper.width
import com.laybalt.utils.render.world.Render3D.renderBoxBounds
import gg.essential.universal.USound
import net.minecraft.core.BlockPos
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.phys.AABB
import java.awt.Color

object ScoreCalculator: Feature("Shows the score of the dungeon run, plus Odin's Map Info score and secret HUDs.") {
    val forcePaul by ToggleSetting("Force Paul")
        .withDescription("Always count Paul's +10 bonus score, even if the election data does not show him as mayor. /lbq paul <auto|on|off> overrides this until you restart the game")
    private val hudElement by ToggleSetting("HUD Element")
        .withDescription("Shows a small \"Score: X\" HUD element (move it with the HUD editor)")
    // NoammAddons 6322fcb2
    private val hideInBoss by ToggleSetting("Hide in Boss").showIf { hudElement.value }
        .withDescription("Hides the small score HUD element in the dungeon boss room")
    val sendMimic by ToggleSetting("Send Mimic Message", true)
        .withDescription("Sends \"Mimic Killed\" in party chat when you see the mimic die (only while the Mimic feature is off, which has its own messages)")
    val sendPrince by ToggleSetting("Send Prince Message", true)
        .withDescription("Sends \"Prince Killed\" in party chat when you kill a prince (only while the Mimic feature is off)")
    val sendBat by ToggleSetting("Send Bat Message", true)
        .withDescription("Sends \"Bat Killed\" in party chat when you kill a bonus-score bat (only while the Mimic feature is off)")

    private val sendMsg270 by ToggleSetting("270 score message").section("270")
        .withDescription("Sends a party message when the run reaches 270 score")
    private val msg270 by TextInputSetting("Message", "270 Score!").showIf { sendMsg270.value }
        .withDescription("The party message sent at 270 score")
    private val title270 by ToggleSetting("270 score Title")
        .withDescription("Shows a title on screen when the run reaches 270 score")
    private val titleMsg270 by TextInputSetting("Title Message", "&e270 Score!").showIf { title270.value }
        .withDescription("The title shown at 270 score (& colour codes work)")

    private val sendMsg300 by ToggleSetting("300 score message").section("300")
        .withDescription("Sends a party message when the run reaches 300 score")
    private val msg300 by TextInputSetting("Message ", "300 Score!").showIf { sendMsg300.value }
        .withDescription("The party message sent at 300 score")
    private val title300 by ToggleSetting("300 score Title")
        .withDescription("Shows a title on screen when the run reaches 300 score")
    private val titleMsg300 by TextInputSetting("Title Message ", "&c300 Score!").showIf { title300.value }
        .withDescription("The title shown at 300 score (& colour codes work)")

    private val highlightPortal by ToggleSetting("Highlight Portal", true).section("Map Info")
        .withDescription("Highlights the blood room portal once the run has reached 300 score")
    private val portalColor by ColorSetting("Portal Color", Color(85, 255, 85, 128)).showIf { highlightPortal.value }
        .withDescription("Colour of the blood room portal highlight")
    private val roomSecretsHud by ToggleSetting("Room Secrets HUD")
        .withDescription("Shows the found/total secrets of the room you are in (move it with the HUD editor)")
    private val fullHud by ToggleSetting("Full HUD")
        .withDescription("Shows a panel with score, secrets, crypts, deaths and mimic/prince/bat state")
    private val disableInBoss by ToggleSetting("Disable In Boss", true).showIf { fullHud.value }
        .withDescription("Hides the full HUD while you are in the boss room")
    private val includeRemaining by ToggleSetting("Include Remaining").showIf { fullHud.value }
        .withDescription("Adds the number of secrets still needed to the secrets line")
    private val remainingSecrets by DropdownSetting("Remaining Secrets", 0, listOf("Minimum", "Remaining")).showIf { fullHud.value && ! includeRemaining.value }
        .withDescription("Minimum: secrets still needed for 300 score. Remaining: total secrets needed for 300 score")
    private val deathsLine by DropdownSetting("Deaths", 0, listOf("Deaths", "Unfound")).showIf { fullHud.value }
        .withDescription("Second line shows the team deaths, or the secrets in rooms nobody has opened yet")
    private val fullWidth by SliderSetting("Width", 150, 140, 200, 1).showIf { fullHud.value }
        .withDescription("Width of the full HUD in pixels")
    private val fullBackground by ColorSetting("Background Color", Color(24, 20, 36, 150)).showIf { fullHud.value }
        .withDescription("Background of the full HUD; set the alpha to 0 to hide it")
    private val accentStripe by ToggleSetting("Accent Stripe", true).showIf { fullHud.value }
        .withDescription("Draws a thin coloured stripe on the left of the full HUD that turns green at 300 score")

    private data class Milestone(
        val score: Int, val sendMessage: ToggleSetting,
        val message: ConfigHolder<String>, val sendTitle: ToggleSetting,
        val title: ConfigHolder<String>
    )

    private val milestones by lazy {
        listOf(
            Milestone(270, sendMsg270, msg270, title270, titleMsg270),
            Milestone(300, sendMsg300, msg300, title300, titleMsg300)
        )
    }

    private val sentBonus = mutableSetOf<DungeonBonusEvent.Type>()
    private var portalAABB: AABB? = null

    override fun init() {
        // NoammAddons 6322fcb2 / b9301688: optionally hidden in the boss room
        hudElement(
            "ScoreCalculator",
            enabled = hudElement::value,
            shouldDraw = { LocationUtils.inDungeon && (! hideInBoss.value || ! LocationUtils.inBoss) }
        ) { ctx, demoMode ->
            val text = if (demoMode) "&eScore: &a300"
            else "&eScore: " + ColorUtils.colorizeScore(ScoreCalculation.score)

            ctx.drawString(text, 0, 0)
            return@hudElement text.width() to 9f
        }

        hudElement("ScoreRoomSecrets", enabled = { roomSecretsHud.value }, shouldDraw = { LocationUtils.inDungeon && ! LocationUtils.inBoss }) { ctx, example ->
            val (found, max) = if (example) 0 to 2 else {
                val room = ScanUtils.currentRoom ?: return@hudElement 0 to 0
                if (room.data.secrets == 0) return@hudElement 0 to 0
                room.foundSecrets to room.data.secrets
            }
            val color = when {
                found * 2 < max -> "§c"
                found * 4 < max * 3 -> "§e"
                else -> "§a"
            }
            val text = "$color$found§7/$color$max"
            ctx.drawString(text, 0, 0)
            text.width() to 9
        }

        hudElement("ScoreFullHud", enabled = { fullHud.value }, shouldDraw = { LocationUtils.inDungeon && ! (disableInBoss.value && LocationUtils.inBoss) }) { ctx, _ ->
            drawFullHud(ctx)
        }

        register<DungeonEvent.Score> {
            milestones.filter { it.score > event.oldScore && it.score <= event.score }.forEach(::triggerMilestone)
        }

        register<DungeonBonusEvent> {
            if (! sentBonus.add(event.type)) return@register
            // the Mimic feature announces the same kills with its own messages; avoid double party messages
            if (Mimic.enabled) return@register
            when (event.type) {
                DungeonBonusEvent.Type.MIMIC -> if (sendMimic.value) ChatUtils.sendPartyMessage("Mimic Killed")
                DungeonBonusEvent.Type.PRINCE -> if (sendPrince.value) ChatUtils.sendPartyMessage("Prince Killed")
                DungeonBonusEvent.Type.BAT -> if (sendBat.value) ChatUtils.sendPartyMessage("Bat Killed")
            }
        }

        register<RenderWorldEvent> {
            if (! highlightPortal.value || ! LocationUtils.inDungeon || LocationUtils.inBoss) return@register
            if (ScoreCalculation.score < 300) return@register
            val aabb = portalAABB ?: findPortal()?.also { portalAABB = it } ?: return@register
            event.ctx.renderBoxBounds(aabb, portalColor.value, outline = false, fill = true, phase = false)
        }

        register<WorldChangeEvent> {
            sentBonus.clear()
            portalAABB = null
        }
    }

    /** Blood room portal: blocks 14..16, 69, 29 of the room in clay-relative coordinates (Odin's coordinates). */
    private fun findPortal(): AABB? {
        val blood = runCatching { DungeonScanner.uniqueRooms.values.toList() }.getOrNull()
            ?.firstOrNull { it.data.type == RoomType.BLOOD } ?: return null
        val corner = blood.clayPos ?: return null
        val rotation = blood.rotation ?: return null
        val a = ScanUtils.getRealCoord(BlockPos(16, 69, 29), corner, 360 - rotation)
        val b = ScanUtils.getRealCoord(BlockPos(14, 69, 29), corner, 360 - rotation)
        return AABB.encapsulatingFullBlocks(a, b).inflate(0.0, 4.0, 0.0)
    }

    private fun drawFullHud(ctx: net.minecraft.client.gui.GuiGraphicsExtractor): Pair<Number, Number> {
        val width = fullWidth.value
        val score = ScoreCalculation.score
        val found = ScoreCalculation.foundSecrets
        val total = ScoreCalculation.totalSecrets
        val needed = ScoreCalculation.neededSecretsAmount

        val scoreText = "§7Score: ${ColorUtils.colorizeScore(score)}"
        val secretText = buildString {
            append("§7Secrets: §b").append(found)
            if (includeRemaining.value) append("§7-§d").append((needed - found).coerceAtLeast(0))
            append("§7-§e")
            append(if (remainingSecrets.value == 1 || includeRemaining.value) needed else (needed - found).coerceAtLeast(0))
            append("§7-§c").append(total)
        }
        val secondText = if (deathsLine.value == 0) "§7Deaths: ${colorizeDeaths(ScoreCalculation.deathCount)}"
        else "§7Unfound: §e${(total - knownSecrets()).coerceAtLeast(0)}"
        val bonusText = buildString {
            if ((LocationUtils.dungeonFloorNumber ?: 0) >= 6) append("${if (ScoreCalculation.mimicKilled) "§a" else "§c"}M §8| ")
            append("${if (ScoreCalculation.princeKilled) "§a" else "§c"}P §8| ")
            append("${if (ScoreCalculation.batKilled) "§a" else "§c"}B")
        }
        val cryptText = "§7Crypts: ${colorizeCrypts(ScoreCalculation.cryptsCount.coerceAtMost(5))}"

        val left = if (accentStripe.value) 4 else 1
        if (fullBackground.value.alpha > 0) ctx.drawRect(0, 0, width, 20, fullBackground.value)
        if (accentStripe.value) ctx.drawRect(0, 0, 2, 20, if (score >= 300) Color(85, 255, 85) else if (score >= 270) Color(255, 255, 85) else Color(158, 119, 237))

        // Odin 47efad1f: score top-right, crypts bottom-right, the bonus text centred in the gap between deaths and crypts
        val scoreWidth = scoreText.width()
        ctx.drawString(secretText, left, 1)
        ctx.drawString(scoreText, width - 1 - scoreWidth, 1)
        val secondWidth = secondText.width()
        ctx.drawString(secondText, left, 11)
        val cryptWidth = cryptText.width()
        val bonusWidth = bonusText.width()
        val gapStart = left + secondWidth
        val gapEnd = width - 1 - cryptWidth
        ctx.drawString(bonusText, gapStart + (gapEnd - gapStart - bonusWidth) / 2, 11)
        ctx.drawString(cryptText, gapEnd, 11)
        return width to 20
    }

    /** Secrets of rooms that are visible on the map (opened by someone), Odin's "known secrets". */
    private fun knownSecrets() = runCatching {
        DungeonScanner.uniqueRooms.values.toList().filter {
            ! it.mainRoom.state.equalsOneOf(RoomState.UNDISCOVERED, RoomState.UNOPENED)
        }.sumOf { it.data.secrets }
    }.getOrDefault(0)

    private fun colorizeCrypts(count: Int) = when {
        count < 3 -> "§c$count"
        count < 5 -> "§e$count"
        else -> "§a$count"
    }

    private fun colorizeDeaths(count: Int): String {
        val floor = LocationUtils.dungeonFloorNumber ?: 0
        return when {
            count == 0 -> "§a0"
            count <= if (floor < 6) 2 else 3 -> "§e$count"
            count == if (floor < 6) 3 else 4 -> "§c$count"
            else -> "§4$count"
        }
    }

    private fun triggerMilestone(m: Milestone) {
        if (m.sendMessage.value) ChatUtils.sendPartyMessage(m.message.value)
        if (m.sendTitle.value) ChatUtils.showTitle(m.title.value)

        val timeStr = NumbersUtils.formatTime(ScoreCalculation.secondsElapsed * 1000).ifEmpty { "0s" }
        val floorColor = if (LocationUtils.isMasterMode) "&c" else "&a"
        val floorName = LocationUtils.dungeonFloor ?: "?"

        ChatUtils.modMessage("&e${m.score}&a score reached in &6$timeStr &f|| $floorColor$floorName.")
        repeat(2) { USound.playSoundStatic(SoundEvents.EXPERIENCE_ORB_PICKUP, 0.25f, 0f) }
    }

}
