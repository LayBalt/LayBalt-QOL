// Ported from Odin (https://github.com/odtheking/Odin), BSD-3-Clause, Copyright (c) 2025 odtheking (parts of this file)
package com.laybalt.features.impl.general

import net.minecraft.network.chat.TextColor
import com.laybalt.LayBaltQOL
import com.laybalt.commands.CommandBuilder
import com.laybalt.config.ConfigManager
import com.laybalt.config.PogObject
import com.laybalt.config.types.ToggleSetting
import com.laybalt.event.impl.*
import com.laybalt.features.Feature
import com.laybalt.init.DataDownloader
import com.laybalt.init.types.ICommandProvider
import com.laybalt.interfaces.IChatComponent
import com.laybalt.ui.notification.NotificationManager
import com.laybalt.utils.*
import com.laybalt.utils.ChatUtils.removeFormatting
import com.mojang.brigadier.arguments.StringArgumentType
import gg.essential.universal.UKeyboard
import gg.essential.universal.UMinecraft
import net.minecraft.ChatFormatting
import net.minecraft.client.gui.screens.ChatScreen
import net.minecraft.client.multiplayer.chat.GuiMessage
import net.minecraft.network.chat.*
import com.mojang.blaze3d.platform.InputConstants
//#if CHEAT
import net.minecraft.network.protocol.common.ServerboundCustomClickActionPacket
//#endif

object ChatFeatures: Feature(jsonName = "Chat", description = "Useful tweaks for the chat such as Ctrl + Click to copy messages."), ICommandProvider {
    private val ctrlClickToCopy by ToggleSetting("Ctrl Click to Copy", true).withDescription("Ctrl + Left Click a message to copy it to your clipboard.")
    private val removeUselessMessages by ToggleSetting("Remove useless messages", true).withDescription("Removes a lot of useless messages from the chat.")
    // NoammAddons 3d0e212a: the per-enemy Explosive Shot damage has its own toggle (it used to follow "Remove useless messages")
    private val explosiveShotDamage by ToggleSetting("Explosive Shot Damage", true).withDescription("Shows the damage per enemy dealt by your Explosive Shot.")
    private val chatEmoji by ToggleSetting("Chat Emoji", true).withDescription("Lets you use [MVP++] emojis in chat")

    //#if CHEAT
    private val autoDialogue by ToggleSetting("Auto dialogue").withDescription("Clicks the first reply option when an NPC dialogue asks you to choose.")
    //#endif

    private val uselessMessages by lazy { DataDownloader.loadJson<List<String>>("uselessMessages.json").map(::Regex) }
    private val customHiders = PogObject("customHiders", mutableListOf<Regex>())

    private val explosiveShotRegex = Regex("^Your Explosive Shot hit (\\d+) (enemy|enemies) for ([\\d,.]+) damage\\.$") // https://regex101.com/r/xsBn8E/1
    private var lastMessageBlank = false

    override fun CommandBuilder.command() {
        setName("chathider")
        alsoUnderLbq("chathider")
        category(com.laybalt.commands.LbqHelp.Category.MISC)
        description("Chat Hider: hide chat messages matching a regex: /chathider <add|remove|list>")
        requires("Enable the $name Feature to use /chathider.") { enabled }
        runs { ChatUtils.modMessage("&bUsage: /chathider <add|remove|list>") }

        literal("add") {
            argument("regex", StringArgumentType.greedyString()) {
                runs {
                    val str = StringArgumentType.getString(it, "regex")
                    val list = customHiders.get().map(Regex::pattern)
                    if (str in list) return@runs ChatUtils.modMessage("&cRegex is Already in the list!")
                    val regex = catch { Regex(str) } ?: return@runs ChatUtils.modMessage("&cInvalid Regex syntax!")
                    customHiders.get().add(regex)
                    customHiders.save()
                    ChatUtils.modMessage("&aSaved new Regex.")
                }
            }
        }

        literal("remove") {
            argument("regex", StringArgumentType.greedyString()) {
                suggests { customHiders.get().map(Regex::pattern) }
                runs {
                    val str = StringArgumentType.getString(it, "regex")
                    val removed = customHiders.get().removeIf { it.pattern == str }
                    ChatUtils.modMessage(if (removed) "&aRemoved Regex." else "&cNo matching regex found.")
                    customHiders.save()
                }
            }
        }

        literal("list") {
            runs {
                ChatUtils.modMessage("customHiders:")
                ChatUtils.chat(customHiders.get().joinToString("\n") { it.pattern })
            }
        }
    }

    override fun init() {
        register<MouseClickEvent> {
            if (! ctrlClickToCopy.value) return@register
            if (UMinecraft.currentScreenObj !is ChatScreen) return@register
            if (event.button != InputConstants.MOUSE_BUTTON_LEFT) return@register
            if (event.action != InputConstants.PRESS) return@register
            if (! UKeyboard.isCtrlKeyDown()) return@register
            val message = getHoveredMsg().takeUnless(String::isBlank) ?: return@register

            NotificationManager.push("Message copied to clipboard", message)
            mc.keyboardHandler.clipboard = message
            event.isCanceled = true
        }

        // Configs saved before the split keep what they had: no damage line while "Remove useless messages" was off.
        ConfigManager.oneShotDefault("chat-explosive-shot-damage") { if (! removeUselessMessages.value) explosiveShotDamage.value = false }

        register<ChatMessageEvent> {
            if (! explosiveShotDamage.value) return@register
            val (sHits, _, fDamage) = explosiveShotRegex.find(event.unformattedText)?.destructured ?: return@register
            val hits = sHits.toIntOrNull() ?: return@register
            val totalDamage = fDamage.replace(",", "").toDoubleOrNull() ?: return@register
            val damagePerEntity = if (hits > 0) totalDamage / hits else totalDamage
            ChatUtils.modMessage("&aExplosive shot did &e${NumbersUtils.format(damagePerEntity.toLong())}&a damage per enemy.")
        }

        register<MessageSentEvent> {
            if (! chatEmoji.value) return@register
            event.message = escapedKeys.replace(event.message) { emotes[it.value] ?: it.value }
                .let { replaceWordEmotes(it, event.isCommand) }
        }

        // NoammAddons 29ed9775: hides only the printed line, so ChatMessageEvent listeners still see the message.
        register<PrintMessageEvent> {
            if (! removeUselessMessages.value) return@register
            val msg = event.unformattedText

            if (msg.isBlank()) {
                if (lastMessageBlank) event.cancel()
                else lastMessageBlank = true
                return@register
            }

            if (uselessMessages.any { it.matches(msg) } || customHiders.get().any { it.matches(msg) }) return@register event.cancel()

            lastMessageBlank = false
        }

        //#if CHEAT
        // Idea from quoi's AutoDialogue (no code taken): https://github.com/jcnlk/quoi/blob/6e74cc3536aa1db91fe4a134254668a96c2ea072/src/main/kotlin/quoi/module/impl/general/chat/impl/AutoDialogue.kt#L4
        register<ChatMessageEvent> {
            if (! autoDialogue.value) return@register
            if ("[BARBARIANS] [MAGES]" in event.unformattedText) return@register
            if (! event.unformattedText.startsWith("Select an option: ")) return@register
            // NoammAddons 2fdd97e4 / 97a1f00b: the first option may sit deeper than siblings[0] and may be a custom click action.
            fun firstOption(c: Component): ClickEvent? = (c.style.clickEvent as? ClickEvent.RunCommand ?: c.style.clickEvent as? ClickEvent.Custom)
                ?: c.siblings.firstNotNullOfOrNull { firstOption(it) }
            when (val option = firstOption(event.component)) {
                is ClickEvent.RunCommand -> ChatUtils.sendCommand(option.command)
                is ClickEvent.Custom -> mc.connection?.send(ServerboundCustomClickActionPacket(option.id, option.payload))
                else -> {}
            }
        }
        //#endif
    }

    private fun getHoveredMsg(): String {
        val chatHud = (UMinecraft.getChatGUI() as? IChatComponent) ?: return ""
        val i = chatHud.getLineIndex().takeUnless { it < 0 || it >= chatHud.visibleMessages.size } ?: return ""

        val lines = ArrayList<GuiMessage.Line>()
        val builder = StringBuilder()

        for (j in i.toInt() + 1 until chatHud.visibleMessages.size) {
            val line = chatHud.visibleMessages[j]
            if (line.endOfEntry()) break
            lines.add(0, line)
        }

        for (j in i.toInt() downTo 0) {
            val line = chatHud.visibleMessages[j]
            lines.add(line)
            if (line.endOfEntry()) break
        }

        for (line in lines) {
            var lastStyle: Style? = null
            line.content().accept { _, style, codePoint ->
                if (style != lastStyle) {
                    style.color?.let { textColor ->
                        ChatFormatting.entries.firstOrNull { TextColor.fromLegacyFormat(it)?.value == textColor.value }?.let {
                            builder.append(it)
                        }
                    }

                    if (style.isBold) builder.append(ChatFormatting.BOLD)
                    if (style.isItalic) builder.append(ChatFormatting.ITALIC)
                    if (style.isUnderlined) builder.append(ChatFormatting.UNDERLINE)
                    if (style.isStrikethrough) builder.append(ChatFormatting.STRIKETHROUGH)
                    if (style.isObfuscated) builder.append(ChatFormatting.OBFUSCATED)
                    lastStyle = style
                }
                builder.appendCodePoint(codePoint)
                true
            }
        }

        val text = builder.toString()
        return if ("chat" in LayBaltQOL.debugFlags) text else text.removeFormatting()
    }

    private val escapedKeys by lazy { emotes.keys.joinToString("|", transform = Regex::escape).toRegex() }
    private val emotes = mapOf(
        "<3" to "❤",
        "h/" to "ヽ(^◇^*)/",
        "o/" to "( ﾟ◡ﾟ)/",
        ":star:" to "✮",
        ":yes:" to "✔",
        ":no:" to "✖",
        ":java:" to "☕",
        ":arrow:" to "➜",
        ":shrug:" to "¯\\_(ツ)_/¯",
        ":tableflip:" to "(╯°□°）╯︵ ┻━┻",
        ":totem:" to "☉_☉",
        ":typing:" to "✎...",
        ":maths:" to "√(π+x)=L",
        ":snail:" to "@'-'",
        ":thinking:" to "(0.o?)",
        ":gimme:" to "༼つ◕_◕༽つ",
        ":wizard:" to "('-')⊃━☆ﾟ.*･｡ﾟ",
        ":pvp:" to "⚔",
        ":peace:" to "✌",
        ":puffer:" to "<('O')>",
        ":yey:" to "ヽ (◕◡◕) ﾉ",
        ":cat:" to "= ＾● ⋏ ●＾ =",
        ":dab:" to "<o/",
        ":dj:" to "ヽ(⌐■_■)ノ♬",
        ":snow:" to "☃",
        ":sloth:" to "(・⊝・)",
        ":cute:" to "(✿◠‿◠)",
        ":dog:" to "(ᵔᴥᵔ)",
        ":sob:" to ".ᐟ(つ╥﹏╥)つ",
        ":joy:" to "৻(≧ᗜ≦৻)",
        ":skull:" to "☠",
        ":bum:" to "♿" // from Odin ChatCommands emotes (https://github.com/odtheking/Odin, BSD-3-Clause, Copyright (c) 2025 odtheking)
    )

    /** Emotes that are plain words, so they are only replaced as a whole word (Odin ChatCommands). */
    private val wordEmotes = mapOf("ez" to "ｅｚ")

    /**
     * Commands that send chat text (Odin ChatCommands whitelist: /pc /ac /gc /cc /msg /w /r, plus their aliases),
     * mapped to how many leading words (command, recipient) must never be replaced. Any other command is left alone,
     * so commands that take player names or search terms (/ah, /trade, /duel, ...) are never rewritten.
     */
    private val chatCommands = mapOf(
        "pc" to 1, "ac" to 1, "gc" to 1, "cc" to 1, "oc" to 1, "r" to 1, "reply" to 1,
        "msg" to 2, "w" to 2, "tell" to 2, "whisper" to 2, "message" to 2
    )

    private fun replaceWordEmotes(message: String, isCommand: Boolean): String {
        val words = message.split(" ")
        val keep = if (isCommand) chatCommands[words.first().lowercase()] ?: return message else 0
        return words.mapIndexed { i, word -> if (i < keep) word else wordEmotes[word] ?: word }.joinToString(" ")
    }
}
