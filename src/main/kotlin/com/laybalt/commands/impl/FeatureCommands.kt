package com.laybalt.commands.impl

import com.laybalt.commands.CommandBuilder
import com.laybalt.commands.LbqHelp
import com.laybalt.commands.LbqHelp.Category
import com.laybalt.config.ConfigManager
import com.laybalt.config.Savable
import com.laybalt.features.Feature
import com.laybalt.features.FeatureManager
import com.laybalt.init.types.ICommandProvider
import com.laybalt.ui.clickgui.GuiState
import com.laybalt.ui.hud.HudElement
import com.laybalt.utils.ChatUtils
import com.mojang.brigadier.arguments.StringArgumentType

/** `/lbq toggle <feature>` and `/lbq reset <feature|hud|gui>` (Odin's `/od toggle module` and `/od reset ...`). */
object FeatureCommands: ICommandProvider {
    /** Feature names with spaces replaced by underscores for Tab completion; "Info Display" and "Info_Display" both work. */
    private fun Feature.commandName() = name.replace(' ', '_')

    private fun featureNames() = FeatureManager.features.map { it.commandName() }.sorted()

    private fun findFeature(input: String): Feature? {
        val spaced = input.trim().replace('_', ' ')
        return FeatureManager.features.firstOrNull { it.commandName().equals(input.trim(), true) || it.name.equals(spaced, true) || it.jsonName.equals(spaced, true) }
            ?: run {
                ChatUtils.modMessage("§cNo feature named \"$input\". §7Press Tab after /lbq toggle for the list.")
                null
            }
    }

    override fun CommandBuilder.command() {
        setName(*LbqHelp.ROOTS.toTypedArray())

        literal("toggle") {
            category(Category.CONFIG)
            description("Turns a feature on or off: /lbq toggle <feature> (spaces or underscores)")
            runs { ChatUtils.modMessage("§cUsage: /lbq toggle <feature>") }
            argument("feature", StringArgumentType.greedyString()) {
                suggests { featureNames() }
                runs { toggle(StringArgumentType.getString(it, "feature")) }
            }
        }

        literal("reset") {
            category(Category.CONFIG)
            description("Resets a feature's settings to their defaults, the HUD positions or the GUI state: /lbq reset <feature|hud|gui>")
            runs { ChatUtils.modMessage("§cUsage: /lbq reset <feature|hud|gui>") }

            literal("hud") {
                description("Moves every HUD element back to its default position and size")
                runs {
                    FeatureManager.hudElements.forEach(HudElement::resetPosition)
                    ConfigManager.save()
                    ChatUtils.modMessage("§aReset HUD positions.")
                }
            }

            literal("gui") {
                description("Resets the remembered GUI category and scroll positions")
                runs { resetGui() }
            }

            literal("clickgui") { runs { resetGui() } }

            argument("feature", StringArgumentType.greedyString()) {
                suggests { featureNames() }
                runs { resetFeature(StringArgumentType.getString(it, "feature")) }
            }
        }
    }

    private fun toggle(input: String) {
        val feature = findFeature(input) ?: return
        if (feature.wip) return ChatUtils.modMessage("§c${feature.name} is work in progress and cannot be enabled yet.")
        feature.toggle()
        ConfigManager.save()
        ChatUtils.modMessage("§f${feature.name} §ahas been ${if (feature.enabled) "§aenabled" else "§cdisabled"}§a.")
    }

    private fun resetFeature(input: String) {
        val feature = findFeature(input) ?: return
        feature.configSettings.filter { it is Savable }.forEach { it.reset() }
        ConfigManager.save()
        ChatUtils.modMessage("§aSettings of §f${feature.name} §ahave been reset to their defaults.")
    }

    private fun resetGui() {
        GuiState.reset()
        ChatUtils.modMessage("§aReset the GUI state.")
    }
}
