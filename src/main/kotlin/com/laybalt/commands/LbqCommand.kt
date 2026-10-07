package com.laybalt.commands

import com.laybalt.LayBaltQOL
import com.laybalt.config.ConfigManager
import com.laybalt.event.EventBus
import com.laybalt.event.impl.ChatMessageEvent
import com.laybalt.event.impl.LayBaltDebugFlagEvent
import com.laybalt.features.impl.dungeon.LeapMenu
import com.laybalt.init.types.ICommandProvider
import com.laybalt.ui.clickgui.ClickGuiScreen
import com.laybalt.ui.hud.HudEditorScreen
import com.laybalt.utils.*
import com.laybalt.utils.ChatUtils.addColor
import com.laybalt.utils.dungeons.DungeonListener
import com.laybalt.utils.dungeons.enums.DungeonClass
import com.laybalt.utils.items.ItemUtils.skyblockId
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import kotlinx.coroutines.launch
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.minecraft.network.chat.Component

object LbqCommand: ICommandProvider {
    override fun CommandBuilder.command() {
        setName("lbq", "laybalt")
        description("opens the Config GUI")
        runs { GuiUtils.setScreen(ClickGuiScreen()) }

        com.laybalt.features.impl.floor7.terminals.TerminalSimulator.registerCommand(this) // /lbq termsim (P2)

        literal("help") {
            runs {
                val helpMenu = StringBuilder()
                this@command.helpEntries().forEach { (usage, desc) ->
                    helpMenu.append("§e/$usage §7- $desc\n")
                }
                ChatUtils.chat(helpMenu.toString().trim())
            }
        }

        literal("discord") {
            description("Opens the link to the Discord server")
            runs {
                Utils.openDiscordLink()
            }
        }

        literal("hud") {
            description("HUD editor")
            runs { GuiUtils.setScreen(HudEditorScreen()) }
        }

        literal("ping") {
            description("Shows your ping in chat")
            runs {
                ChatUtils.modMessage("§aPing: §f${ServerUtils.averagePing}ms")
            }
        }

        literal("tps") {
            description("Shows the server's tps in chat")
            runs {
                ChatUtils.modMessage("§aTPS: §f${ServerUtils.tps}")
            }
        }

        refillCommand("ep", "ENDER_PEARL", "ender_pearl", 16)
        refillCommand("ij", "INFLATABLE_JERRY", "inflatable_jerry", 64)
        refillCommand("sl", "SPIRIT_LEAP", "spirit_leap", 16)
        refillCommand("sb", "SUPERBOOM_TNT", "superboom_tnt", 64)
        refillCommand("dd", "DUNGEON_DECOY", "dungeon_decoy", 64)
        refillCommand("tap", "TOXIC_ARROW_POISON", "toxic_arrow_poison", 64)
        refillCommand("twap", "TWILIGHT_ARROW_POISON", "twilight_arrow_poison", 64)

        literal("debug") {
            description("Debug flags")
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
            runs {
                ChatUtils.modMessage("§cInvalid Usage: §f/lbq sim <message>")
            }

            argument("message", StringArgumentType.greedyString()) {
                description("Simulate chat message")
                runs { ctx ->
                    val msg = StringArgumentType.getString(ctx, "message").addColor()
                    ChatUtils.modMessage(msg)
                    EventBus.post(ChatMessageEvent(Component.literal(msg)))
                }
            }
        }

        literal("leaporder") {
            description("Configure custom leap sorting")
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
            literal("load") {
                argument("config", StringArgumentType.string()) {
                    suggests { ConfigManager.getConfigs().keys }
                    runs {
                        val configName = StringArgumentType.getString(it, "config")
                        ConfigManager.changeConfig(configName)
                    }
                }
            }
            literal("create") {
                argument("config", StringArgumentType.string()) {
                    runs {
                        val configName = StringArgumentType.getString(it, "config").removeSuffix(".json")
                        ConfigManager.createConfig(configName)
                    }
                }
            }
            literal("delete") {
                argument("config", StringArgumentType.string()) {
                    suggests { ConfigManager.getConfigs().keys }
                    runs {
                        val configName = StringArgumentType.getString(it, "config").removeSuffix(".json")
                        ConfigManager.deleteConfig(configName)
                    }
                }
            }
        }

        //#if CHEAT
        literal("swapmask") {
            description("Equips either Bonzo Mask or Spirit Mask")
            runs {
                LayBaltQOL.scope.launch {
                    PlayerUtils.changeMaskAction()
                }
            }
        }

        literal("rodswap") {
            description("Automatically rodswaps for you")
            runs {
                LayBaltQOL.scope.launch {
                    PlayerUtils.rodSwap()
                }
            }
        }

        literal("swapto") {
            runs { ChatUtils.modMessage("missing skyblock id argument. /lbq swapto <ItemID>") }
            argument("skyblock id", StringArgumentType.word()) {
                description("Automatically equips the item in the EQ menu")
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
            argument("class", StringArgumentType.word()) {
                suggests { DungeonClass.entries.filterNot { it == DungeonClass.Empty }.map { it.name } }
                description("Automatically leaps to the selected class")
                runs { ctx ->
                    val clazz = StringArgumentType.getString(ctx, "class")
                    val player = DungeonListener.dungeonTeammatesNoSelf.find { it.clazz.name == clazz } ?: return@runs ChatUtils.modMessage("leap target not found")
                    LayBaltQOL.scope.launch { PlayerUtils.leapAction(player) }
                }
            }
        }
        //#endif
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
        ChatUtils.modMessage("§aCustom leap order set to: §f$sortingType §awith players: §f${validPlayers.joinToString(", ")}")
    }

    private fun CommandBuilder.refillCommand(name: String, itemId: String, sackName: String, defaultAmount: Int) {
        literal(name) {
            description("Refills ${sackName.replace('_', ' ')} from sacks")
            runs { refill(itemId, sackName, defaultAmount) }
        }
    }

    private fun refill(itemId: String, sackName: String, amount: Int) {
        val inventory = LayBaltQOL.mc.player?.inventory ?: return
        val currentAmount = inventory.sumOf { stack -> if (stack.skyblockId == itemId) stack.count else 0 }
        val missingAmount = amount - currentAmount
        if (missingAmount > 0) ChatUtils.sendCommand("gfs $sackName $missingAmount")
    }
}