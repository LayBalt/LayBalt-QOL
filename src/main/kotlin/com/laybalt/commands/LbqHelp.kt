package com.laybalt.commands

import com.laybalt.LayBaltQOL
import com.laybalt.utils.ChatUtils
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent

/**
 * `/lbq help`: every `/lbq` subcommand, from all [ICommandProvider][com.laybalt.init.types.ICommandProvider]s that
 * contribute to the /lbq root or mount themselves under it ([CommandBuilder.alsoUnderLbq]). [entries] is refilled
 * each time the client commands are registered.
 */
object LbqHelp {
    val ROOTS = listOf("lbq", "laybalt")

    enum class Category(val title: String, val key: String) {
        DUNGEONS("Dungeons & Kuudra", "dungeons"),
        SACKS("Sacks", "sacks"),
        TERMINALS("Terminals & F7", "terminals"), // not "f7": /lbq help f7 is the floor command
        WAYPOINTS("Waypoints", "waypoints"),
        PARTY("Party & Players", "party"),
        ITEMS("Items & Inventory", "items"),
        CONFIG("Config & Imports", "config"),
        MISC("Misc", "misc"),
    }

    @Volatile var entries: List<CommandBuilder.HelpEntry> = emptyList()

    /** Top-level subcommand names and category keys, for `/lbq help <topic>` completion. */
    fun topics(): List<String> = (entries.flatMap { listOf(it.top) + it.aliases }.filter { it.isNotEmpty() && ! it.startsWith("<") && ".." !in it } + Category.entries.map { it.key }).distinct()

    fun print(topic: String?) {
        if (topic.isNullOrBlank()) return printOverview()
        val key = topic.trim().lowercase()
        Category.entries.firstOrNull { it.key == key || it.name.equals(key, true) }?.let { return printCategory(it) }

        val tops = entries.filter { it.matches(key) }.map { it.top }.toSet()
        val matching = entries.filter { it.top in tops }
        if (matching.isEmpty()) return ChatUtils.modMessage("§cNo /lbq subcommand or help group \"$topic\". §7Groups: ${Category.entries.joinToString { it.key }}")
        ChatUtils.modMessage("§b/lbq $key §7commands:")
        matching.sortedBy { it.depth }.distinctBy { it.usage }.forEach { ChatUtils.chat(line(it)) }
    }

    private fun topLevel(): Map<Category, List<CommandBuilder.HelpEntry>> {
        val byTop = entries.groupBy { it.top }.filterKeys { it.isNotEmpty() }
        return byTop.values.mapNotNull { group ->
            group.filter { it.depth == 1 }.minByOrNull { it.usage.length } ?: group.minByOrNull { it.depth }
        }.groupBy { it.category ?: Category.MISC }
    }

    private fun printOverview() {
        val grouped = topLevel()
        ChatUtils.chat(LayBaltQOL.PREFIX.copy().append(Component.literal(" §fCommands §7(click to fill in, §e/lbq help <command|group>§7 for details)")))
        for (category in Category.entries) {
            val list = grouped[category]?.sortedBy { it.top } ?: continue
            ChatUtils.chat(Component.literal("§b§l${category.title} §8(§7/lbq help ${category.key}§8)").withStyle {
                it.withClickEvent(ClickEvent.RunCommand("/lbq help ${category.key}"))
                    .withHoverEvent(HoverEvent.ShowText(Component.literal("§7Show every ${category.title} command")))
            })
            list.forEach { ChatUtils.chat(line(it)) }
        }
    }

    private fun printCategory(category: Category) {
        val tops = topLevel()[category].orEmpty().map { it.top }.toSet()
        ChatUtils.chat(LayBaltQOL.PREFIX.copy().append(Component.literal(" §b§l${category.title}")))
        entries.filter { it.top in tops }.sortedWith(compareBy({ it.top }, { it.depth })).distinctBy { it.usage }.forEach { ChatUtils.chat(line(it)) }
    }

    private fun line(entry: CommandBuilder.HelpEntry): Component {
        val usage = "/${entry.usage}"
        val alias = listOfNotNull(entry.aliasOf?.let { "/$it" }, entry.aliases.takeIf { it.isNotEmpty() }?.joinToString(prefix = "also ") { it })
            .takeIf { it.isNotEmpty() }?.joinToString(prefix = " §8(", postfix = ")").orEmpty()
        val fill = usage.substringBefore(" <").substringBefore(" [").substringBefore("..").let { if (it.endsWith(' ')) it else "$it " }
        return Component.literal(" §e$usage$alias §7- ${entry.description}").withStyle {
            it.withClickEvent(ClickEvent.SuggestCommand(fill))
                .withHoverEvent(HoverEvent.ShowText(Component.literal("§e$usage\n§7${entry.description}\n§8Click to type it")))
        }
    }
}
