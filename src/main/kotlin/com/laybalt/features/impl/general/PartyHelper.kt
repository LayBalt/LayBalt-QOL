// Guild / private / co-op channels and the extra chat commands are ported from Odin ChatCommands
// (https://github.com/odtheking/Odin), BSD-3-Clause, Copyright (c) 2025 odtheking
package com.laybalt.features.impl.general

import com.laybalt.LayBaltQOL
import com.laybalt.commands.CommandBuilder
import com.laybalt.config.PogObject
import com.laybalt.config.types.MultiCheckboxSetting
import com.laybalt.config.types.ToggleSetting
import com.laybalt.event.impl.*
import com.laybalt.features.Feature
import com.laybalt.init.types.ICommandProvider
import com.laybalt.utils.*
import com.laybalt.utils.ChatUtils.addColor
import com.laybalt.utils.ChatUtils.unformattedText
import com.laybalt.utils.NumbersUtils.toFixed
import com.laybalt.utils.PartyUtils.isLeader
import com.laybalt.utils.dungeons.DungeonUtils
import com.laybalt.utils.location.LocationUtils
import com.mojang.brigadier.arguments.StringArgumentType
import gg.essential.universal.USound
import net.minecraft.network.chat.*
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket
import net.minecraft.sounds.SoundEvents
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

object PartyHelper: Feature("Chat commands for party, guild, private and co-op chat, plus party list reformatting."), ICommandProvider {
    private val partyCommands by ToggleSetting("Party Commands", true).withDescription("Answers chat commands (e.g. !warp, !coords) sent in party chat.").section("Party Commands")
    private val guildCommands by ToggleSetting("Guild Commands", false).withDescription("Answers chat commands sent in guild chat (only the non-party commands such as !coords, !cf, !8ball).")
    private val privateCommands by ToggleSetting("Private Commands", true).withDescription("Answers chat commands sent to you in private messages (/msg), including !invite.")
    private val coopCommands by ToggleSetting("Co-op Commands", true).withDescription("Answers chat commands sent in co-op chat (only the non-party commands).")
    private val partyLeaderCheck by ToggleSetting("Leader Only", false).withDescription("Only runs party management commands (warp, kick, transfer, queue...) while you are the party leader.").showIf { anyChannel() }
    private val commands by MultiCheckboxSetting("Enabled Commands", mutableMapOf(
        "!w" to true, "!f" to true, "!m" to true, "!t" to true, "!inv" to true,
        "!kick" to true, "!kickoffline" to false, "!dt" to true, "!ping" to true, "!tps" to true, "!fps" to true,
        "!pt" to true, "!promote" to false, "!demote" to false, "!reinvite" to false, "!ai" to true, "!coords" to true,
        "!gay" to true, "!help" to true, "!cf" to true, "!8ball" to true, "!dice" to true, "!boop" to true,
        "!time" to false, "!location" to true, "!holding" to true, "!invite (DM)" to true
    )).withDescription("Which commands are answered. !t queues Kuudra tiers, !invite (DM) invites whoever messages you !invite.").showIf { anyChannel() }
    private val autoConfirmInvite by ToggleSetting("Auto Confirm Invite", false).withDescription("Invites players who send you !invite in a private message right away instead of showing a clickable message.").showIf { privateCommands.value && commands["!invite (DM)"] }

    private val partyAddons by ToggleSetting("Reformat Party List", true).section("Party Addons")

    private val playerBlacklist = PogObject("party_command_blacklist", mutableSetOf<String>())
    private val party = mutableListOf<PartyMember>()
    val downtimeList = mutableMapOf<String, String>()
    private var awaitingDelimiter = 0

    private val partyStartPattern = Regex("^Party Members \\((\\d+)\\)$")
    private val playerPattern = Regex("(?<rank>.*?)(?<name>\\w+) ?§(?<status>[ac]) ?● ?")
    private val partyCommandRegex = Regex("^Party > (?:\\[[^]]+] )?(\\w{1,16})[^:]*: ?([!?.\\-@#`/])(.+)$")
    private val guildCommandRegex = Regex("^Guild > (?:\\[[^]]+] )?(\\w{1,16})(?: \\[[^]]*])?[^:]*: ?!(.+)$")
    private val coopCommandRegex = Regex("^Co-op > (?:\\[[^]]+] )?(\\w{1,16})[^:]*: ?!(.+)$")
    private val privateCommandRegex = Regex("^From (?:\\[[^]]+] )?(\\w{1,16}): ?!(.+)$")
    private val kuudraEndRegex = Regex("^\\[NPC] Elle: Good job everyone\\. A hard fought battle come to an end\\. Let's get out of here before we run into any more trouble!$")
    private val kuudraTiers = listOf("NORMAL", "HOT", "BURNING", "FIERY", "INFERNAL")

    private enum class Channel { PARTY, GUILD, PRIVATE, COOP }

    /** Commands available outside party chat (Odin ChatCommands guild / private / co-op maps). */
    private val generalCommands = listOf("!help", "!coords", "!boop", "!cf", "!8ball", "!dice", "!ping", "!tps", "!fps", "!time", "!location", "!holding", "!gay")

    private fun anyChannel() = partyCommands.value || guildCommands.value || privateCommands.value || coopCommands.value

    override fun CommandBuilder.command() {
        setName("partycommandsblacklist", "pcbl")
        alsoUnderLbq("pcbl")
        category(com.laybalt.commands.LbqHelp.Category.PARTY)
        description("Manages the party command blacklist")
        runs { showBlacklist() }

        literal("add") {
            argument("player", StringArgumentType.word()) {
                suggests { PartyUtils.members.filterNot(::isBlacklisted) }
                runs {
                    val name = StringArgumentType.getString(it, "player")
                    val added = playerBlacklist.get().add(name.lowercase())
                    if (added) playerBlacklist.save()
                    ChatUtils.modMessage(if (added) "&aAdded &b$name &ato the party command blacklist." else "&e$name is already blacklisted.")
                }
            }
        }

        literal("remove") {
            argument("player", StringArgumentType.word()) {
                suggests { playerBlacklist.get().sorted() }
                runs {
                    val name = StringArgumentType.getString(it, "player")
                    val removed = playerBlacklist.get().remove(name.lowercase())
                    if (removed) playerBlacklist.save()
                    ChatUtils.modMessage(if (removed) "&aRemoved &b$name &afrom the party command blacklist." else "&e$name is not blacklisted.")
                }
            }
        }

        literal("list") {
            runs { showBlacklist() }
        }

        literal("clear") {
            runs {
                playerBlacklist.get().clear()
                playerBlacklist.save()
                ChatUtils.modMessage("&aCleared the party command blacklist.")
            }
        }
    }

    override fun init() {
        register<PacketEvent.Sent> {
            if (! LocationUtils.onHypixel || ! partyAddons.value) return@register
            if (event.packet !is ServerboundChatCommandPacket) return@register
            if (event.packet.command.lowercase().equalsOneOf("pl", "party list", "p list")) {
                awaitingDelimiter = 2
            }
        }

        register<ChatMessageEvent> {
            if (! LocationUtils.onHypixel) return@register
            val text = event.unformattedText

            if (kuudraEndRegex.matches(text)) {
                ThreadUtils.scheduledTask(30) { announceDowntime() }
                return@register
            }

            if (parseChannelCommand(text)) return@register

            if (partyAddons.value && awaitingDelimiter > 0) {
                handlePartyListParsing(event)
            }
        }

        register<DungeonEvent.RunEndedEvent> { announceDowntime() }
    }

    /** @return true if the message was a chat command in one of the enabled channels. */
    private fun parseChannelCommand(text: String): Boolean {
        val (channel, match) = when {
            text.startsWith("Party > ") -> Channel.PARTY to (partyCommandRegex.find(text)?.takeIf { partyCommands.value } ?: return false)
            text.startsWith("Guild > ") -> Channel.GUILD to (guildCommandRegex.find(text)?.takeIf { guildCommands.value } ?: return false)
            text.startsWith("Co-op > ") -> Channel.COOP to (coopCommandRegex.find(text)?.takeIf { coopCommands.value } ?: return false)
            text.startsWith("From ") -> Channel.PRIVATE to (privateCommandRegex.find(text)?.takeIf { privateCommands.value } ?: return false)
            else -> return false
        }

        val name = match.groupValues[1]
        val cmdAll = match.groupValues.last()
        val args = cmdAll.trim().split(" ").filter(String::isNotEmpty).toMutableList()
        if (args.isEmpty()) return true
        val cmd = args.removeAt(0).lowercase()
        handleCommand(name, cmd, args, channel)
        return true
    }

    private fun enabled(key: String, channel: Channel) = commands.value[key] == true && when (channel) {
        Channel.PARTY -> true
        Channel.PRIVATE -> key in generalCommands || key == "!invite (DM)"
        else -> key in generalCommands
    }

    private fun handleCommand(sender: String, cmd: String, args: List<String>, channel: Channel) {
        if (isBlacklisted(sender)) return
        val arg = args.firstOrNull()?.takeIf { it.length <= 16 }
        val isParty = channel == Channel.PARTY

        when {
            cmd.equalsOneOf("help", "h") && enabled("!help", channel) -> {
                val available = commands.value.keys.filter { enabled(it, channel) && it != "!help" }.map { it.removeSuffix(" (DM)") }
                reply("Commands: ${available.joinToString(", ")}", sender, channel)
            }

            cmd == "fps" && enabled("!fps", channel) -> reply("FPS: ${mc.fps}", sender, channel)
            cmd == "ping" && enabled("!ping", channel) -> reply("Ping: ${ServerUtils.currentPing}ms", sender, channel)
            cmd == "tps" && enabled("!tps", channel) -> reply("TPS: ${ServerUtils.tps.toFixed(1)}", sender, channel)
            cmd == "time" && enabled("!time", channel) -> reply("Current Time: ${ZonedDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z"))}", sender, channel)
            cmd.equalsOneOf("coords", "cords", "co") && enabled("!coords", channel) -> reply("x: ${player.blockX}, y: ${player.blockY}, z: ${player.blockZ}", sender, channel)
            cmd.equalsOneOf("location", "loc") && enabled("!location", channel) -> reply("Current Location: ${locationString()}", sender, channel)
            cmd == "holding" && enabled("!holding", channel) -> reply("Holding: ${player.mainHandItem.takeUnless { it.isEmpty }?.hoverName?.unformattedText ?: "Nothing :("}", sender, channel)
            cmd == "cf" && enabled("!cf", channel) -> reply(if (Math.random() < 0.5) "heads" else "tails", sender, channel)
            cmd == "8ball" && enabled("!8ball", channel) -> reply(eightBallResponses.random(), sender, channel)
            cmd == "dice" && enabled("!dice", channel) -> reply((1 .. 6).random(), sender, channel)
            cmd == "boop" && enabled("!boop", channel) -> arg?.let { ChatUtils.sendCommand("boop $it") }

            cmd == "gay" && enabled("!gay", channel) -> {
                val target = arg ?: sender
                val gayPercentage = (Math.random() * 100).roundToInt().coerceIn(0, 100)
                reply("$target is $gayPercentage% gay.", sender, channel)
            }

            // private only
            cmd.equalsOneOf("invite", "inv") && channel == Channel.PRIVATE && enabled("!invite (DM)", channel) -> {
                if (autoConfirmInvite.value) return ChatUtils.sendCommand("p invite $sender")
                ChatUtils.chat(LayBaltQOL.PREFIX.copy().append(Component.literal(" §aClick here to invite §b$sender §ato your party!").withStyle {
                    it.withClickEvent(ClickEvent.RunCommand("/party invite $sender"))
                        .withHoverEvent(HoverEvent.ShowText(Component.literal("§6Click to invite $sender to your party.")))
                }))
                USound.playSoundStatic(SoundEvents.NOTE_BLOCK_PLING, 1f, 1f)
            }

            // everything below is party chat only
            ! isParty -> return

            commands["!t"] && Regex("t[1-5]").matches(cmd) -> {
                val tier = cmd.drop(1).toInt()
                ChatUtils.modMessage("&8Entering -> &eKuudra T$tier")
                runCommand("joininstance KUUDRA_${kuudraTiers[tier - 1]}", true)
            }

            commands["!f"] && cmd.startsWith("f") && cmd != "fps" -> {
                val floor = cmd.removePrefix("f").toIntOrNull() ?: args.getOrNull(0)?.toIntOrNull() ?: return
                if (floor in 0 .. 7) runCommand("joininstance CATACOMBS_FLOOR_${DungeonUtils.FLOOR_NAMES[floor]}", true)
            }

            commands["!m"] && cmd.startsWith("m") -> {
                val floor = cmd.removePrefix("m").toIntOrNull() ?: args.getOrNull(0)?.toIntOrNull() ?: return
                if (floor in 1 .. 7) runCommand("joininstance MASTER_CATACOMBS_FLOOR_${DungeonUtils.FLOOR_NAMES[floor]}", true)
            }

            commands["!pt"] && cmd.equalsOneOf("pt", "ptme", "transfer") -> {
                val target = arg?.let(::findPartyMember) ?: sender
                if (! target.equals(mc.user.name, true)) runCommand("p transfer $target", true)
            }

            commands["!promote"] && cmd == "promote" -> runCommand("p promote ${arg?.let(::findPartyMember) ?: sender}", true)
            commands["!demote"] && cmd == "demote" -> runCommand("p demote ${arg?.let(::findPartyMember) ?: sender}", true)

            commands["!dt"] && cmd.equalsOneOf("dt", "downtime") -> {
                if (sender in downtimeList) return ChatUtils.modMessage("&6$sender &calready has a downtime reminder!")
                downtimeList[sender] = args.joinToString(" ").ifBlank { "No reason" }
                ChatUtils.modMessage("&aDowntime reminder set for &6$sender &afor the end of the run.")
            }

            commands["!dt"] && cmd.equalsOneOf("undt", "undowntime") -> {
                if (downtimeList.remove(sender) == null) return ChatUtils.modMessage("&6$sender &chas no downtime reminder set!")
                ChatUtils.modMessage("&aDowntime reminder removed for &6$sender&a.")
            }

            commands["!w"] && cmd.equalsOneOf("warp", "w") -> runCommand("p warp", true)

            commands["!ai"] && cmd.equalsOneOf("ai", "allinvite", "allinv") -> runCommand("p settings allinvite", true)

            commands["!kickoffline"] && cmd.equalsOneOf("kickoffline", "ko") -> runCommand("p kickoffline", true)

            commands["!kick"] && cmd.equalsOneOf("kick", "k") -> {
                if (arg == null) return
                runCommand("p kick ${findPartyMember(arg)}", true)
            }

            commands["!reinvite"] && cmd.equalsOneOf("reinv", "reinvite") -> {
                if (sender.equals(mc.user.name, true)) return
                if (partyLeaderCheck.value && ! isLeader()) return
                ChatUtils.modMessage("&aReinviting &6$sender &ain 5 seconds...")
                ThreadUtils.scheduledTask(100) { ChatUtils.sendCommand("p invite $sender") }
            }

            commands["!inv"] && cmd.equalsOneOf("inv", "kidnap", "invite") -> {
                args.firstOrNull()?.let { runCommand("p invite $it", true) }
            }
        }
    }

    private fun announceDowntime() {
        if (downtimeList.isEmpty()) return
        val names = downtimeList.keys.joinToString(", ")
        val reasons = downtimeList.entries.groupBy({ it.value }, { it.key }).entries.joinToString(", ") { (reason, players) -> "${players.joinToString(", ")}: $reason" }
        ChatUtils.showTitle("&cDowntime!", "Players needing DT: $names")
        USound.playSoundStatic(SoundEvents.NOTE_BLOCK_PLING, 0.25f, 1f)
        ChatUtils.modMessage("&eDT Reasons: &f$reasons")
        ChatUtils.sendPartyMessage("Players needing DT: $names")
        downtimeList.clear()
    }

    private fun locationString(): String {
        val world = LocationUtils.world?.tabName ?: return if (LocationUtils.inSkyblock) "Unknown" else "Not in SkyBlock"
        return LocationUtils.dungeonFloor?.takeIf { LocationUtils.inDungeon }?.let { "$world ($it)" } ?: world
    }

    /** Odin findPartyMember: completes a partial name to a party member (prefix first, then substring). */
    private fun findPartyMember(partialName: String): String =
        PartyUtils.members.find { it.startsWith(partialName, true) }
            ?: PartyUtils.members.find { it.contains(partialName, true) }
            ?: partialName

    private fun reply(message: Any, sender: String, channel: Channel) {
        when (channel) {
            Channel.PARTY -> ChatUtils.sendCommand("pc $message")
            Channel.GUILD -> ChatUtils.sendCommand("gc $message")
            Channel.COOP -> ChatUtils.sendCommand("cc $message")
            Channel.PRIVATE -> ChatUtils.sendCommand("msg $sender $message")
        }
    }

    private fun isBlacklisted(name: String) = name.lowercase() in playerBlacklist.get()

    private fun showBlacklist() {
        val names = playerBlacklist.get().sorted()
        ChatUtils.modMessage(if (names.isEmpty()) "&eThe party command blacklist is empty." else "&aParty command blacklist: &f${names.joinToString(", ")}")
    }

    private fun handlePartyListParsing(event: ChatMessageEvent) {
        val unformatted = event.unformattedText
        val formatted = event.formattedText

        when {
            unformatted.isBlank() -> event.isCanceled = true

            partyStartPattern.matches(unformatted) -> {
                party.clear()
                event.isCanceled = true
            }

            unformatted.startsWithOneOf("Party Leader: ", "Party Moderators: ", "Party Members: ") -> {
                val type = when {
                    unformatted.startsWith("Party Leader") -> PartyMemberType.LEADER
                    unformatted.startsWith("Party Moderators") -> PartyMemberType.MODERATOR
                    else -> PartyMemberType.MEMBER
                }
                playerPattern.findAll(formatted.substringAfter(": ")).forEach {
                    val (rank, name, status) = it.destructured
                    party.add(PartyMember(name, type, "§$status", rank))
                }
                event.isCanceled = true
            }

            unformatted.startsWith("You are not currently in a party") -> {
                party.clear()
                awaitingDelimiter = 0
            }

            unformatted.startsWith("-----") -> {
                event.isCanceled = true
                awaitingDelimiter --
                if (awaitingDelimiter == 0 && party.isNotEmpty()) formatPartyList()
            }
        }
    }

    private fun formatPartyList() {
        val main = Component.literal("§9§m§l----------------------------------\n").append("  §aParty Members (${party.size})\n")

        val isLeader = party.any { it.name == mc.user.name && it.type == PartyMemberType.LEADER }

        if (isLeader) main.apply {
            append(createButton("  §9[Warp] ", "/p warp", "§7Warp Party"))
            append(createButton("§e[Invite] ", "/p settings allinvite", "§7Toggle AllInvite"))
            append(createButton("§4[Disband]\n", "/p disband", "§c§lBE CAREFUL"))
        }

        party.sortedBy { it.type }.forEach { m ->
            val color = if (m.status.contains("a") || m.status.contains("§a")) "§a" else "§c"
            val line = Component.literal("\n  $color● §r${m.rank}${m.name}")
            if (m.type == PartyMemberType.LEADER) line.append(" §e(Leader)")

            if (isLeader && m.name != mc.user.name) {
                line.append(createButton(" §c[Kick]", "/p kick ${m.name}", "§cKicks ${m.name}"))
                line.append(createButton(" §a[Transfer]", "/p Transfer ${m.name}", "§aTransfers the party to &f${m.name}"))
            }
            main.append(line)
        }

        main.append("\n§9§m§l----------------------------------")
        ChatUtils.chat(main)
    }

    private fun runCommand(cmd: String, leaderReq: Boolean = false) {
        if (leaderReq && partyLeaderCheck.value && ! isLeader()) return
        ChatUtils.sendCommand(cmd)
    }

    private fun createButton(text: String, command: String, hover: String): MutableComponent {
        return Component.literal(text.addColor()).withStyle {
            it.withClickEvent(ClickEvent.RunCommand(command))
                .withHoverEvent(HoverEvent.ShowText(Component.literal(hover.addColor())))
        }
    }

    private val eightBallResponses = arrayOf(
        "It is certain", "It is decidedly so", "Without a doubt",
        "Yes definitely", "You may rely on it", "As I see it, yes",
        "Most likely", "Outlook good", "Yes", "Signs point to yes",
        "Reply hazy try again", "Ask again later", "Better not tell you now",
        "Cannot predict now", "Concentrate and ask again", "Don't count on it",
        "My reply is no", "My sources say no", "Outlook not so good", "Very doubtful"
    )

    private data class PartyMember(val name: String, val type: PartyMemberType, val status: String, val rank: String)
    private enum class PartyMemberType { LEADER, MODERATOR, MEMBER }
}
