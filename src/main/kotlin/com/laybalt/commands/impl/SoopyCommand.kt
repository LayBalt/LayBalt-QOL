// Ported from Odin's SoopyCommand (https://github.com/odtheking/Odin), BSD-3-Clause, Copyright (c) 2025 odtheking
package com.laybalt.commands.impl

import com.laybalt.LayBaltQOL
import com.laybalt.LayBaltQOL.mc
import com.laybalt.commands.CommandBuilder
import com.laybalt.commands.LbqHelp
import com.laybalt.commands.LbqHelp.Category
import com.laybalt.init.types.ICommandProvider
import com.laybalt.utils.ChatUtils
import com.laybalt.utils.PartyUtils
import com.laybalt.utils.network.WebUtils
import com.mojang.brigadier.arguments.StringArgumentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.net.URLEncoder

/**
 * `/lbq soopy <command> [player]` (Odin's `/soopycmd`): asks Soopy's public bot-command API
 * (soopy.dev/api/soopyv2/botcommand) and prints the answer. Only runs when you type the command.
 */
object SoopyCommand: ICommandProvider {
    private val commands = listOf(
        "auctions", "bestiary", "bank", "classaverage", "currdungeon", "dojo", "dungeon", "essence", "faction",
        "guildof", "kuudra", "nucleus", "nw", "overflowskillaverage", "overflowskills", "pet", "rtca", "sblvl",
        "secrets", "skillaverage", "skills"
    )

    override fun CommandBuilder.command() {
        setName(*LbqHelp.ROOTS.toTypedArray())
        literal("soopy") {
            category(Category.PARTY)
            helpUsage("soopy <command> [player]")
            description("Runs a Soopy stats command (soopy.dev) for you or another player: dungeon, secrets, nw, skills, ...")
            runs { ChatUtils.modMessage("§bSoopy commands: §f${commands.joinToString(", ")} §7(/lbq soopy <command> [player])") }
            argument("command", StringArgumentType.word()) {
                suggests { commands }
                runs { run(StringArgumentType.getString(it, "command"), null) }
                argument("player", StringArgumentType.word()) {
                    suggests { PartyUtils.members.toList() }
                    runs { run(StringArgumentType.getString(it, "command"), StringArgumentType.getString(it, "player")) }
                }
            }
        }
    }

    private fun run(command: String, user: String?) {
        if (command.lowercase() !in commands) return ChatUtils.modMessage("§cUnknown Soopy command \"$command\". §7Available: ${commands.joinToString(", ")}")
        val player = user ?: mc.user.name
        ChatUtils.modMessage("§7Running Soopy command §f$command §7for §f$player§7...")
        LayBaltQOL.scope.launch(Dispatchers.IO) {
            val url = "https://soopy.dev/api/soopyv2/botcommand?m=${command.lowercase()}&u=${URLEncoder.encode(player, Charsets.UTF_8)}"
            WebUtils.getAs<String>(url).fold(
                { ChatUtils.modMessage(it.trim()) },
                { ChatUtils.modMessage("§cSoopy did not answer: ${it.message?.take(120)}") }
            )
        }
    }
}
