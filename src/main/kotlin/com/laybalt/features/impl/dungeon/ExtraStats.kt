// Ported from Odin (https://github.com/odtheking/Odin), BSD-3-Clause, Copyright (c) 2025 odtheking
package com.laybalt.features.impl.dungeon

import com.laybalt.config.types.DropdownSetting
import com.laybalt.config.types.ToggleSetting
import com.laybalt.event.impl.ChatMessageEvent
import com.laybalt.event.priority.EventPriority
import com.laybalt.event.impl.WorldChangeEvent
import com.laybalt.features.Feature
import com.laybalt.utils.ChatUtils
import com.laybalt.utils.ChatUtils.removeFormatting
import com.laybalt.utils.NumbersUtils
import com.laybalt.utils.dungeons.DungeonListener
import com.laybalt.utils.dungeons.map.handlers.ScoreCalculation
import com.laybalt.utils.location.LocationUtils
import net.minecraft.client.gui.components.ChatComponent
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent

object ExtraStats: Feature("Replaces Hypixel's end-of-run summary with a compact one that includes the extra stats.") {
    private val showBits by ToggleSetting("Show Bits", true)
        .withDescription("Shows the bits earned in the run")
    private val showClassEXP by ToggleSetting("Show Class EXP", true)
        .withDescription("Shows the class experience next to the Catacombs experience")
    private val showCombatStats by ToggleSetting("Show Combat Stats", true)
        .withDescription("Shows your damage, enemy kills and healing")
    private val teamStats by DropdownSetting("Show Team Stats", 3, listOf("Off", "Personal", "Team", "Both"))
        .withDescription("Shows your own secrets/deaths, the team's secrets/crypts/deaths, or both")
    private val showTeammates by ToggleSetting("Show Teammates")
        .withDescription("Lists your teammates with their class colours")

    private val personal get() = teamStats.value == 1 || teamStats.value == 3
    private val team get() = teamStats.value == 2 || teamStats.value == 3

    private val stats = PostDungeonStats()

    private val defeatedRegex = Regex("^\\s*☠ Defeated (.+) in 0?([\\dhms ]+?)\\s*(\\(NEW RECORD!\\))?$")
    private val teamScoreRegex = Regex("^\\s*Team Score: (\\d+) \\((.{1,2})\\)\\s?(\\(NEW RECORD!\\))?$")
    private val expRegex = Regex("^\\s*(\\+[\\d,.]+\\s?\\w+ Experience)\\s?(?:\\(.+\\))?$")
    private val bitsRegex = Regex("^\\s*(\\+\\d+ Bits)$")
    private val damageRegex = Regex("^\\s*(Total Damage as .+: ([\\d,.]+)\\s?(\\(NEW RECORD!\\))?)$")
    private val healRegex = Regex("^\\s*(Ally Healing: ([\\d,.]+)\\s?(\\(NEW RECORD!\\))?)$")
    private val enemyKillRegex = Regex("^\\s*(Enemies Killed: (\\d+)\\s?(\\(NEW RECORD!\\))?)$")
    private val deathsRegex = Regex("^\\s*(Deaths: (\\d+))$")
    private val secretsRegex = Regex("^\\s*(Secrets Found: (\\d+))$")
    private val extraStatsRegex = Regex(" {29}> EXTRA STATS <")

    private val cancelRegexes = listOf(
        Regex(" {29}> EXTRA STATS <"),
        Regex("^\\s*☠ Defeated (.+) in 0?([\\dhms ]+?)\\s*(\\(NEW RECORD!\\))?$"),
        Regex("^\\s*Team Score: \\d+ \\(.{1,2}\\)\\s?(?:\\(NEW RECORD!\\))?$"),
        Regex("^\\s*(\\+[\\d,.]+\\s?\\w+ Experience)\\s?(?:\\(.+\\))?$"),
        Regex("^\\s*(Master Mode)? ?(?:The)? Catacombs - (Entrance|Floor .{1,3})$"),
        Regex("^\\s*Secrets Found: \\d+$"),
        Regex("^▬+$"),
        Regex("^\\s*Total Damage as .+: [\\d,.]+\\s?(?:\\(NEW RECORD!\\))?$"),
        Regex("^\\s*Ally Healing: [\\d,.]+\\s?(?:\\(NEW RECORD!\\))?$"),
        Regex("^\\s*\\+0 Experience \\(No Class Milestone Reached\\)$"),
        Regex("^\\s*The Catacombs - .+ Stats$"),
        Regex("^\\s*Deaths: \\d+$"),
        Regex("^\\s*Master Mode Catacombs - .+ Stats$"),
        Regex("^\\s*Master Mode The Catacombs - .+ Stats$"),
        Regex("^\\s*\\+(\\d+) Bits$"),
        Regex("^\\s*Enemies Killed: \\d+\\s?(?:\\(NEW RECORD!\\))?$")
    )

    override fun init() {
        // lowest priority: the summary lines are hidden only after every other feature (splits, run end detection) saw them
        register<ChatMessageEvent>(EventPriority.LOWEST) {
            if (! LocationUtils.inDungeon) return@register
            val message = event.unformattedText

            // like Odin, the clickable "> EXTRA STATS <" line itself stays visible
            if (extraStatsRegex.matches(message)) return@register ChatUtils.sendCommand("showextrastats")
            if (cancelRegexes.any { it.matches(message) }) event.isCanceled = true

            defeatedRegex.find(message)?.let {
                stats.timePB = it.groupValues[3].isNotEmpty()
                stats.bossKilled = it.groupValues[1]
                return@register
            }

            teamScoreRegex.find(message)?.let {
                stats.score = it.groupValues[1].toIntOrNull() ?: 0
                stats.scorePB = it.groupValues[3].isNotEmpty()
                stats.scoreLetter = it.groupValues[2]
                return@register
            }

            expRegex.find(message)?.let {
                stats.xp.add("§3${it.groupValues[1].replace("Experience", "EXP").replace("Catacombs", "Cata")}")
                return@register
            }

            bitsRegex.find(message)?.let {
                stats.bits = it.groupValues[1]
                return@register
            }

            damageRegex.find(message)?.let {
                stats.damagePB = it.groupValues[3].isNotEmpty()
                stats.damage = formatNumber(it.groupValues[2]) + if (stats.damagePB) "(NEW PB!)" else ""
                stats.combatStats.add("§e${it.groupValues[1]}")
                return@register
            }

            healRegex.find(message)?.let {
                stats.healPB = it.groupValues[3].isNotEmpty()
                stats.heal = formatNumber(it.groupValues[2]) + if (stats.healPB) "(NEW PB!)" else ""
                stats.combatStats.add("§a${it.groupValues[1]}")
                return@register
            }

            enemyKillRegex.find(message)?.let {
                stats.enemyKillPB = it.groupValues[3].isNotEmpty()
                stats.enemyKill = (it.groupValues[2].toIntOrNull() ?: 0).toString() + if (stats.enemyKillPB) "(NEW PB!)" else ""
                stats.combatStats.add(stats.combatStats.size.coerceAtMost(1), "§b${it.groupValues[1]}")
                return@register
            }

            deathsRegex.find(message)?.let {
                stats.deaths = it.groupValues[2].toIntOrNull() ?: 0
                if (personal) stats.skillStats.add("§c${it.groupValues[1]}")
                return@register
            }

            secretsRegex.find(message)?.let {
                stats.secretsFound = it.groupValues[2].toIntOrNull() ?: 0
                if (personal) stats.skillStats.add(0, "§b${it.groupValues[1]}")
                if (teamStats.value == 3) stats.skillStats.add("")
                if (team) {
                    stats.skillStats.add("§bTeam Secrets Found: ${ScoreCalculation.foundSecrets}")
                    stats.skillStats.add("§6Crypt: ${ScoreCalculation.cryptsCount}")
                    stats.skillStats.add("§cTeam Deaths: ${ScoreCalculation.deathCount}")
                }
                printEndStats()
                return@register
            }
        }

        register<WorldChangeEvent> { stats.reset() }
    }

    private fun dungeonTime() = NumbersUtils.formatTime(ScoreCalculation.secondsElapsed * 1000L).ifEmpty { "0s" }

    private fun chatWidth() = ChatComponent.getWidth(mc.options.chatWidth().get())

    private fun centered(text: String): String {
        val stripped = text.removeFormatting()
        if (stripped.isEmpty()) return text
        val textWidth = mc.font.width(stripped)
        val chatWidth = chatWidth()
        if (textWidth >= chatWidth) return text
        return " ".repeat(((chatWidth - textWidth) / 2 / 4).coerceAtLeast(0)) + text
    }

    private fun chatBreak() = "§9§m" + "-".repeat(chatWidth() / mc.font.width("-").coerceAtLeast(1))

    private fun formatNumber(numStr: String): String {
        val num = numStr.replace(",", "").toDoubleOrNull() ?: return numStr
        return when {
            num >= 1_000_000_000 -> "%.2fB".format(num / 1_000_000_000)
            num >= 1_000_000 -> "%.2fM".format(num / 1_000_000)
            num >= 1_000 -> "%.2fK".format(num / 1_000)
            else -> "%.0f".format(num)
        }
    }

    private fun hoverLine(text: String, details: List<String>): Component = Component.literal(centered(text)).withStyle {
        it.withClickEvent(ClickEvent.SuggestCommand(details.joinToString("\n").removeFormatting()))
            .withHoverEvent(HoverEvent.ShowText(Component.literal(details.joinToString("\n"))))
    }

    private fun printEndStats() {
        val floor = LocationUtils.dungeonFloor ?: "?"
        val defeatedText = if (stats.bossKilled == null) "§c§lFAILED §a- §e${dungeonTime()}"
        else "§aDefeated §c${stats.bossKilled} §ain §e${dungeonTime()}${if (stats.timePB) " §d§l(NEW RECORD!)" else ""}"

        val message = Component.literal(chatBreak())
            .append("\n\n")
            .append(centered((if (LocationUtils.isMasterMode) "§cMaster Mode" else "§cThe Catacombs") + " §r- §e$floor"))
            .append("\n\n")
            .append(centered(defeatedText))
            .append("\n")
            .append(centered("§aScore: §6${stats.score} §a(§b${stats.scoreLetter}§a)${if (stats.scorePB) " §d§l(NEW RECORD!)" else ""}${if (stats.bits != null && showBits.value) "    §b${stats.bits}" else ""}"))
            .append("\n")

        val xpText = "${stats.xp.firstOrNull() ?: ""}${if (showClassEXP.value) "  ${stats.xp.getOrNull(1) ?: ""}" else ""}"
        message.append(hoverLine(xpText, stats.xp)).append("\n")

        if (showCombatStats.value) message.append(hoverLine("§e${stats.damage}§r-§b${stats.enemyKill}§r-§a${stats.heal}", stats.combatStats)).append("\n")

        if (teamStats.value != 0) {
            val teamText = "§b${ScoreCalculation.foundSecrets}§r-§6${ScoreCalculation.cryptsCount}§r-§c${ScoreCalculation.deathCount}"
            val statsText = when (teamStats.value) {
                1 -> "§b${stats.secretsFound}§r-§c${stats.deaths}"
                2 -> teamText
                else -> "§b${stats.secretsFound}§r-§c${stats.deaths} §r/ $teamText"
            }
            message.append(hoverLine(statsText, stats.skillStats)).append("\n")
        }

        if (showTeammates.value) {
            val teammates = DungeonListener.dungeonTeammatesNoSelf
            message.append(centered(
                if (teammates.isNotEmpty()) teammates.joinToString("§r, ") { "${it.clazz.code}${it.name}" } else "§3Solo"
            )).append("\n")
        }

        message.append("\n").append(Component.literal(chatBreak()))
        ChatUtils.chat(message)
    }

    private class PostDungeonStats(
        var score: Int = 0,
        var scoreLetter: String? = null,
        var bossKilled: String? = null,
        val xp: MutableList<String> = mutableListOf(),
        var timePB: Boolean = false,
        var scorePB: Boolean = false,
        val combatStats: MutableList<String> = mutableListOf(),
        var damage: String = "0",
        var damagePB: Boolean = false,
        var enemyKill: String = "0",
        var enemyKillPB: Boolean = false,
        var heal: String = "0",
        var healPB: Boolean = false,
        val skillStats: MutableList<String> = mutableListOf(),
        var deaths: Int = 0,
        var secretsFound: Int = 0,
        var bits: String? = null
    ) {
        fun reset() {
            scoreLetter = null
            bossKilled = null
            secretsFound = 0
            scorePB = false
            timePB = false
            xp.clear()
            score = 0
            deaths = 0
            heal = "0"
            healPB = false
            damage = "0"
            damagePB = false
            enemyKill = "0"
            enemyKillPB = false
            bits = null
            skillStats.clear()
            combatStats.clear()
        }
    }
}
