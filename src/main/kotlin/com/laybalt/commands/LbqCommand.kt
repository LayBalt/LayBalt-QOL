package com.laybalt.commands

import com.laybalt.LayBaltQOL
import com.laybalt.commands.LbqHelp.Category
import com.laybalt.config.ConfigManager
import com.laybalt.event.EventBus
import com.laybalt.event.impl.ChatMessageEvent
import com.laybalt.event.impl.LayBaltDebugFlagEvent
import com.laybalt.features.impl.dungeon.LeapMenu
import com.laybalt.init.types.ICommandProvider
import com.laybalt.ui.clickgui.LayBaltScreen
import com.laybalt.ui.hud.HudEditorScreen
import com.laybalt.utils.*
import com.laybalt.utils.ChatUtils.addColor
import com.laybalt.utils.dungeons.DungeonListener
import com.laybalt.utils.dungeons.DungeonUtils
import com.laybalt.utils.dungeons.enums.DungeonClass
import com.laybalt.utils.items.ItemUtils.skyblockId
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import kotlinx.coroutines.launch
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.minecraft.network.chat.Component

/**
 * The `/lbq` (`/laybalt`) root: GUI, help and the general subcommands. Feature subcommands come from their features
 * (other [ICommandProvider]s that register the /lbq root, or standalone roots mounted with [CommandBuilder.alsoUnderLbq])
 * and from the providers in [com.laybalt.commands.impl]; brigadier merges them all into one tree.
 */
object LbqCommand: ICommandProvider {
    override fun CommandBuilder.command() {
        setName(*LbqHelp.ROOTS.toTypedArray())
        description("opens the Config GUI")
        runs { GuiUtils.setScreen(LayBaltScreen()) }

        com.laybalt.features.impl.floor7.terminals.TerminalSimulator.registerCommand(this) // /lbq termsim (P2)

        literal("help") {
            category(Category.MISC)
            description("Lists every /lbq command by group; /lbq help <command|group> shows the details")
            runs { LbqHelp.print(null) }
            argument("topic", StringArgumentType.word()) {
                suggests { LbqHelp.topics() }
                runs { LbqHelp.print(StringArgumentType.getString(it, "topic")) }
            }
        }

        literal("discord") {
            category(Category.MISC)
            description("Opens the link to the Discord server")
            runs {
                Utils.openDiscordLink()
            }
        }

        literal("hud") {
            category(Category.CONFIG)
            description("Opens the HUD editor")
            runs { GuiUtils.setScreen(HudEditorScreen()) }
        }

        literal("edithud") {
            category(Category.CONFIG)
            description("Opens the HUD editor (same as /lbq hud)")
            runs { GuiUtils.setScreen(HudEditorScreen()) }
        }

        literal("ping") {
            category(Category.MISC)
            description("Shows your ping in chat")
            runs {
                ChatUtils.modMessage("§aPing: §f${ServerUtils.averagePing}ms")
            }
        }

        literal("tps") {
            category(Category.MISC)
            description("Shows the server's tps in chat")
            runs {
                ChatUtils.modMessage("§aTPS: §f${ServerUtils.tps}")
            }
        }

        literal("sendcoords") {
            category(Category.PARTY)
            description("Sends your coordinates in chat, optionally followed by a message")
            runs { ChatUtils.sendMessage(PlayerUtils.positionString()) }
            argument("message", StringArgumentType.greedyString()) {
                runs { ChatUtils.sendMessage("${PlayerUtils.positionString()} ${StringArgumentType.getString(it, "message")}") }
            }
        }

        literal("debug") {
            category(Category.MISC)
            description("Shows or toggles debug flags")
            runs {
                ChatUtils.modMessage("§7Flags: §f${LayBaltQOL.debugFlags.joinToString(", ")}")
            }

            argument("flag", StringArgumentType.greedyString()) {
                suggests { LayBaltQOL.availableDebugFlags }
                runs { ctx ->
                    StringArgumentType.getString(ctx, "flag").split(Regex("\\s+")).forEach { flag ->
                        val added = if (LayBaltQOL.debugFlags.remove(flag)) false else LayBaltQOL.debugFlags.add(flag)
                        ChatUtils.modMessage(if (added) "§aAdded debug flag: §b$flag" else "§cRemoved debug flag: §b$flag")
                        EventBus.post(if (added) LayBaltDebugFlagEvent.Add(flag) else LayBaltDebugFlagEvent.Remove(flag))
                    }
                }
            }
        }

        literal("sim") {
            category(Category.MISC)
            description("Simulates a received chat message (for testing features)")
            runs {
                ChatUtils.modMessage("§cInvalid Usage: §f/lbq sim <message>")
            }

            argument("message", StringArgumentType.greedyString()) {
                runs { ctx ->
                    val msg = StringArgumentType.getString(ctx, "message").addColor()
                    ChatUtils.modMessage(msg)
                    EventBus.post(ChatMessageEvent(Component.literal(msg)))
                }
            }
        }

        literal("leaporder") {
            category(Category.DUNGEONS)
            description("Custom leap menu order: /lbq leaporder <name|class> <player1> [player2] [player3] [player4]")
            val partyMembersSuggestion = { PartyUtils.members.map(String::lowercase) }
            argument("sorting", StringArgumentType.word()) {
                suggests { listOf("name", "class") }

                argument("player1", StringArgumentType.word()) {
                    suggests(partyMembersSuggestion)
                    runs { ctx -> setLeapOrder(ctx, 1) }

                    argument("player2", StringArgumentType.word()) {
                        suggests(partyMembersSuggestion)
                        runs { ctx -> setLeapOrder(ctx, 2) }

                        argument("player3", StringArgumentType.word()) {
                            suggests(partyMembersSuggestion)
                            runs { ctx -> setLeapOrder(ctx, 3) }

                            argument("player4", StringArgumentType.word()) {
                                suggests(partyMembersSuggestion)
                                runs { ctx -> setLeapOrder(ctx, 4) }
                            }
                        }
                    }
                }
            }
        }

        literal("config") {
            category(Category.CONFIG)
            description("Config profiles: /lbq config <load|create|delete> <name>")
            runs { ChatUtils.modMessage("§bConfigs: §f${ConfigManager.getConfigs().keys.sorted().joinToString(", ")} §7(/lbq config <load|create|delete> <name>)") }
            literal("load") {
                argument("config", StringArgumentType.string()) {
                    description("Loads a saved config profile")
                    suggests { ConfigManager.getConfigs().keys }
                    runs {
                        val configName = StringArgumentType.getString(it, "config")
                        ConfigManager.changeConfig(configName)
                    }
                }
            }
            literal("create") {
                argument("config", StringArgumentType.string()) {
                    description("Saves the current settings as a new config profile")
                    runs {
                        val configName = StringArgumentType.getString(it, "config").removeSuffix(".json")
                        ConfigManager.createConfig(configName)
                    }
                }
            }
            literal("delete") {
                argument("config", StringArgumentType.string()) {
                    description("Deletes a config profile")
                    suggests { ConfigManager.getConfigs().keys }
                    runs {
                        val configName = StringArgumentType.getString(it, "config").removeSuffix(".json")
                        ConfigManager.deleteConfig(configName)
                    }
                }
            }
        }

        //#if CHEAT
        literal("fishing") {
            category(Category.MISC)
            description("Auto Mob Killer: /lbq fishing <rules|record|relearn> (opens the Sea Creature Rules, starts/stops the Fishing Recorder, relearns from recordings)")
            runs { GuiUtils.setScreen(com.laybalt.ui.gui.SeaCreatureRulesScreen()) }
            literal("rules") {
                description("Opens the Sea Creature Rules window")
                runs { GuiUtils.setScreen(com.laybalt.ui.gui.SeaCreatureRulesScreen()) }
            }
            literal("record") {
                description("Starts or stops the Fishing Recorder")
                runs { com.laybalt.features.impl.fishing.FishingRecorder.toggle() }
            }
            literal("relearn") {
                description("Learns your fishing behaviour again from every recording")
                runs { com.laybalt.features.impl.fishing.BehaviorProfile.rebuildAsync(report = true) }
            }
        }

        literal("swapmask") {
            category(Category.ITEMS)
            description("Equips either Bonzo Mask or Spirit Mask")
            runs {
                LayBaltQOL.scope.launch {
                    PlayerUtils.changeMaskAction()
                }
            }
        }

        literal("rodswap") {
            category(Category.ITEMS)
            description("Automatically rodswaps for you")
            runs {
                LayBaltQOL.scope.launch {
                    PlayerUtils.rodSwap()
                }
            }
        }

        literal("swapto") {
            category(Category.ITEMS)
            description("Equips an inventory item in the equipment menu: /lbq swapto <skyblock id>")
            runs { ChatUtils.modMessage("missing skyblock id argument. /lbq swapto <ItemID>") }
            argument("skyblock id", StringArgumentType.word()) {
                suggests { LayBaltQOL.mc.player?.inventory?.nonEquipmentItems?.map { it.skyblockId }?.filter { it.isNotEmpty() }?.distinct().orEmpty() }
                runs {
                    LayBaltQOL.scope.launch {
                        val inv = LayBaltQOL.mc.player?.inventory?.nonEquipmentItems ?: return@launch
                        val item = StringArgumentType.getString(it, "skyblock id")
                        if (inv.none { it.skyblockId == item }) return@launch ChatUtils.modMessage("$item not found in inventory")
                        PlayerUtils.quickSwapAction(item)
                    }
                }
            }
        }

        literal("leap") {
            category(Category.DUNGEONS)
            description("Leaps to the teammate playing a class: /lbq leap <class>")
            argument("class", StringArgumentType.word()) {
                suggests { DungeonClass.entries.filterNot { it == DungeonClass.Empty }.map { it.name } }
                runs { ctx ->
                    val clazz = StringArgumentType.getString(ctx, "class")
                    val player = DungeonListener.dungeonTeammatesNoSelf.find { it.clazz.name == clazz } ?: return@runs ChatUtils.modMessage("leap target not found")
                    LayBaltQOL.scope.launch { PlayerUtils.leapAction(player) }
                }
            }
        }
        //#endif

        // Odin ffcea4cf
        literal("paul") {
            category(Category.DUNGEONS)
            description("Paul's +10 bonus score: /lbq paul <auto|on|off> (auto = Force Paul / mayor data, not saved)")
            runs { ChatUtils.modMessage("§aPaul bonus: §f${when (DungeonUtils.paulOverride) { null -> "auto"; true -> "on"; false -> "off" }}§a. §7(/lbq paul <auto|on|off>)") }
            listOf("auto", "on", "off").forEach { mode ->
                literal(mode) { runs { DungeonUtils.setPaulOverride(mode)?.let(ChatUtils::modMessage) } }
            }
        }
    }

    private fun setLeapOrder(ctx: CommandContext<FabricClientCommandSource>, count: Int) {
        val sortingType = StringArgumentType.getString(ctx, "sorting").lowercase()
        if (sortingType != "name" && sortingType != "class") return ChatUtils.modMessage("§cInvalid sorting type! Use 'name' or 'class'")

        val validPlayers = mutableListOf<String>()
        for (i in 1 .. count) {
            val inputName = StringArgumentType.getString(ctx, "player$i")
            validPlayers.add(inputName.lowercase())
        }

        LeapMenu.customLeapOrder = validPlayers
        LeapMenu.customLeapType = sortingType
        ConfigManager.save() // NoammAddons 9780376e: the order is a saved (hidden) setting now
        ChatUtils.modMessage("§aCustom leap order set to: §f$sortingType §awith players: §f${validPlayers.joinToString(", ")}")
    }
}
