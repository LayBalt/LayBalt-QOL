package com.laybalt.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.arguments.ArgumentType
import com.mojang.brigadier.builder.ArgumentBuilder
import com.mojang.brigadier.builder.RequiredArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.mojang.brigadier.suggestion.SuggestionProvider
import net.fabricmc.fabric.api.client.command.v2.ClientCommands
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.minecraft.commands.SharedSuggestionProvider

class CommandBuilder {
    private lateinit var names: Array<out String>
    private var executor: ((CommandContext<FabricClientCommandSource>) -> Unit)? = null
    private val children = mutableListOf<CommandBuilder>()
    private val requirements = mutableListOf<() -> Unit>()
    private var descriptionText: String? = null
    private var helpCategory: LbqHelp.Category? = null
    private var usageText: String? = null
    private val lbqAliases = mutableListOf<String>()
    private val helpAliasNames = mutableListOf<String>()
    private var hiddenFromHelp = false

    private var argumentType: ArgumentType<*>? = null
    private var argumentName: String? = null
    private var literalName: String? = null
    private var suggestionProvider: SuggestionProvider<FabricClientCommandSource>? = null
    private var suggestionLambda: (() -> Iterable<String>)? = null

    fun setName(vararg names: String) = ::names.set(names)

    fun requires(message: String, predicate: () -> Boolean) = requirements.add {
        if (! predicate()) throw SimpleCommandExceptionType(LiteralMessage(message)).create()
    }

    fun runs(block: (CommandContext<FabricClientCommandSource>) -> Unit) = ::executor.set(block)
    fun suggests(strings: () -> Iterable<String>) = ::suggestionLambda.set(strings)
    fun description(text: String) = ::descriptionText.set(text)

    /** `/lbq help` group of this node and everything below it (default: the parent's group, else Misc). */
    fun category(category: LbqHelp.Category) = ::helpCategory.set(category)

    /** Text shown in `/lbq help` instead of this node's own name, e.g. "f1..f7 | m1..m7". */
    fun helpUsage(text: String) = ::usageText.set(text)

    /** Other names of this subcommand (e.g. "waypoint" for "wp"): shown next to it and accepted by `/lbq help <name>`. */
    fun helpAliases(vararg names: String) = helpAliasNames.addAll(names)

    /** Leaves this node and everything below it out of `/lbq help`, e.g. the second literal of an alias pair. */
    fun hideFromHelp() = ::hiddenFromHelp.set(true)

    /**
     * Mounts this whole root command (children and [requires] checks included) under `/lbq <alias>` and
     * `/laybalt <alias>` as well, so a standalone command such as `/pfs` is also `/lbq pfs` without a second copy.
     */
    fun alsoUnderLbq(vararg aliases: String) = lbqAliases.addAll(aliases)

    fun literal(name: String, block: CommandBuilder.() -> Unit) = children.add(CommandBuilder().apply literal@{
        this@literal.literalName = name
        block()
    })

    fun <T: Any> argument(name: String, type: ArgumentType<T>, block: CommandBuilder.() -> Unit) = children.add(CommandBuilder().apply literal@{
        this@literal.argumentName = name
        this@literal.argumentType = type
        block()
    })

    fun build(): List<ArgumentBuilder<FabricClientCommandSource, *>> {
        if (! ::names.isInitialized) error("Command name must be initialized using setName(...)")
        val roots = names.map { ClientCommands.literal(it).also(::setup) }
        val mounted = lbqAliases.flatMap { alias ->
            LbqHelp.ROOTS.map { root -> ClientCommands.literal(root).then(ClientCommands.literal(alias).also(::setup)) }
        }
        return roots + mounted
    }

    fun helpEntries(): List<HelpEntry> {
        if (! ::names.isInitialized) return emptyList()
        return buildList {
            descriptionText?.let { add(HelpEntry(names.first(), it, helpCategory, 0)) }
            children.forEach { it.collect(names.first(), this, 1, helpCategory) }
        }
    }

    /** The `/lbq` help entries this command contributes: its subcommands when it is the /lbq root, plus its [alsoUnderLbq] mounts. */
    fun lbqHelpEntries(): List<HelpEntry> {
        if (! ::names.isInitialized) return emptyList()
        return buildList {
            if (names.any { it in LbqHelp.ROOTS }) children.forEach { it.collect(LbqHelp.ROOTS.first(), this, 1, helpCategory) }
            // Several mounts of the same root (e.g. "autoclicker" and "ac") are listed once, under the first one.
            lbqAliases.firstOrNull()?.let { alias ->
                val path = "${LbqHelp.ROOTS.first()} $alias"
                val description = descriptionText ?: "Same as /${names.first()}"
                add(HelpEntry(path, description, helpCategory, 1, aliasOf = names.first(), aliases = lbqAliases.drop(1) + helpAliasNames))
                children.forEach { it.collect(path, this, 2, helpCategory) }
            }
        }
    }

    /**
     * [depth] is 0 for a root, 1 for a direct subcommand. [aliasOf] names the standalone root a `/lbq` mount points to,
     * [aliases] the other names of the same subcommand.
     */
    data class HelpEntry(
        val usage: String,
        val description: String,
        val category: LbqHelp.Category? = null,
        val depth: Int = 0,
        val aliasOf: String? = null,
        val aliases: List<String> = emptyList(),
    ) {
        /** First word after the root, e.g. "wp" for "lbq wp add <x>". */
        val top get() = usage.split(' ').getOrNull(1).orEmpty()

        /**
         * Whether `/lbq help <key>` is about this entry: its [top] word, one of its [aliases], or a name inside a
         * range usage such as "f1..f7 | m1..m7" (f7, m3) or "t1..t5" (t5).
         */
        fun matches(key: String): Boolean {
            if (top.equals(key, true) || aliases.any { it.equals(key, true) }) return true
            if (depth != 1) return false
            val wanted = topicRegex.matchEntire(key.lowercase()) ?: return false
            val (prefix, number) = wanted.destructured
            return usage.substringAfter(' ').split(" | ").any { token ->
                val range = rangeRegex.matchEntire(token.trim().substringBefore(' ').lowercase()) ?: return@any false
                val (from, low, to, high) = range.destructured
                prefix == from && (to.isEmpty() || to == from) && number.toInt() in low.toInt() .. high.toInt()
            }
        }

        private companion object {
            val topicRegex = Regex("([a-z]+)(\\d+)")
            val rangeRegex = Regex("([a-z]+)(\\d+)\\.\\.([a-z]*)(\\d+)")
        }
    }

    private fun collect(prefix: String, out: MutableList<HelpEntry>, depth: Int, inheritedCategory: LbqHelp.Category?) {
        if (hiddenFromHelp) return
        val segment = usageText ?: literalName ?: argumentName?.let { "<$it>" } ?: return
        val path = "$prefix $segment"
        val category = helpCategory ?: inheritedCategory
        descriptionText?.let { out.add(HelpEntry(path, it, category, depth, aliases = helpAliasNames.toList())) }
        children.forEach { it.collect(path, out, depth + 1, category) }
    }

    private fun setup(target: ArgumentBuilder<FabricClientCommandSource, *>, inheritedRequirements: List<() -> Unit> = emptyList()) {
        val checks = inheritedRequirements + requirements
        executor?.let { exec ->
            target.executes { context ->
                checks.forEach { it() }
                exec(context)
                Command.SINGLE_SUCCESS
            }
        }

        val reqTarget = target as? RequiredArgumentBuilder<FabricClientCommandSource, *>
        if (reqTarget != null) {
            suggestionProvider?.let(reqTarget::suggests)
            suggestionLambda?.let { lambda ->
                reqTarget.suggests { _, suggestionsBuilder ->
                    SharedSuggestionProvider.suggest(lambda(), suggestionsBuilder)
                }
            }
        }

        children.forEach { child ->
            val childBuilder = if (child.literalName != null) ClientCommands.literal(child.literalName !!)
            else if (child.argumentName != null && child.argumentType != null) ClientCommands.argument(child.argumentName !!, child.argumentType !!)
            else return@forEach

            child.setup(childBuilder, checks)
            target.then(childBuilder)
        }
    }
}
