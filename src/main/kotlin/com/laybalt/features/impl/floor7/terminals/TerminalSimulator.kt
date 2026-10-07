// Ported from Odin (https://github.com/odtheking/Odin), BSD-3-Clause, Copyright (c) 2025 odtheking
package com.laybalt.features.impl.floor7.terminals

import com.laybalt.commands.CommandBuilder
import com.laybalt.config.types.ButtonSetting
import com.laybalt.config.types.SliderSetting
import com.laybalt.config.types.ToggleSetting
import com.laybalt.features.Feature
import com.laybalt.features.impl.floor7.terminals.termsim.SimType
import com.laybalt.features.impl.floor7.terminals.termsim.StartGUI
import com.laybalt.init.types.ICommandProvider
import com.laybalt.utils.ChatUtils
import com.laybalt.utils.ThreadUtils
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType

/**
 * Opens a local floor 7 terminal simulator (no server involved). Toggling the feature in the GUI opens the
 * simulator instead of enabling anything, like Odin. Also available as `/lbq termsim [ping]` and
 * `/lbq termsim <type> [ping]`.
 */
object TerminalSimulator: Feature("Practice floor 7 terminals in a local simulator. Clicking the toggle opens it; also /lbq termsim [type] [ping]."), ICommandProvider {
    private val ping by SliderSetting("Ping", 0, 0, 500, 50, "ms").withDescription("Simulated delay before a click is applied, like playing with that ping.")
    val disableFirstClickProt by ToggleSetting("Disable First Click Prot").withDescription("Turns the solver's first click protection off while you are in the simulator.")
    private val openButton by ButtonSetting("Open Simulator") { open() }.withDescription("Opens the terminal simulator start menu.")
    private val resetButton by ButtonSetting("Reset Simulator PBs") {
        TerminalPBs.reset(simulator = true)
        ChatUtils.modMessage("§6Terminal simulator PBs §fhave been reset.")
    }.withDescription("Deletes your terminal simulator personal bests.")

    override fun toggle() = open()

    fun open(type: SimType? = null, pingOverride: Int? = null) {
        if (mc.player == null) return
        val simPing = (pingOverride ?: ping.value).toLong().coerceIn(0, 2000)
        // Delayed by a tick so the chat screen (when used from a command) is closed first.
        ThreadUtils.scheduledTask(1) {
            if (mc.player == null) return@scheduledTask
            (type?.factory?.invoke() ?: StartGUI()).open(simPing)
        }
    }

    override fun CommandBuilder.command() {
        setName("lbq", "laybalt")

        literal("termsim") {
            description("Opens the terminal simulator: /lbq termsim [ping] or /lbq termsim <type> [ping]")
            runs { open() }

            argument("type", StringArgumentType.word()) {
                description("Opens one terminal type (or random); a number instead of a type is the ping")
                suggests { SimType.entries.map { it.commandName } + "random" }
                runs { openType(StringArgumentType.getString(it, "type"), null) } // a number alone is the ping

                argument("ping", IntegerArgumentType.integer(0, 2000)) {
                    runs { openType(StringArgumentType.getString(it, "type"), IntegerArgumentType.getInteger(it, "ping")) }
                }
            }
        }
    }

    private fun openType(name: String, ping: Int?) {
        name.toIntOrNull()?.let { return open(pingOverride = it) }
        val type = if (name.equals("random", true)) SimType.random()
        else SimType.entries.find { it.commandName.equals(name, true) }
            ?: return ChatUtils.modMessage("§cUnknown terminal type. Use one of: ${SimType.entries.joinToString { it.commandName }}, random")
        open(type, ping)
    }
}
