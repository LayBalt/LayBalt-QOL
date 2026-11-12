// Ported from Odin (https://github.com/odtheking/Odin), BSD-3-Clause, Copyright (c) 2025 odtheking
package com.laybalt.features.impl.floor7

import com.laybalt.config.types.ButtonSetting
import com.laybalt.config.types.ToggleSetting
import com.laybalt.event.impl.ChatMessageEvent
import com.laybalt.event.impl.TerminalActionEvent
import com.laybalt.event.impl.TickEvent
import com.laybalt.event.impl.WorldChangeEvent
import com.laybalt.features.Feature
import com.laybalt.features.impl.floor7.terminals.TerminalPBs
import com.laybalt.utils.ChatUtils
import com.laybalt.utils.NumbersUtils.toFixed
import com.laybalt.event.priority.EventPriority
import net.minecraft.network.chat.Component

object TerminalTimes: Feature("Times every terminal you complete in floor 7 (with personal bests per terminal type) and adds section splits to the terminal messages.") {
    private val terminalTimes by ToggleSetting("Terminal Times", true).withDescription("Sends the time you needed for each terminal in chat, with your personal best for that terminal type.")
    private val resetPBs by ButtonSetting("Reset PBs") {
        TerminalPBs.reset(simulator = false)
        ChatUtils.modMessage("§6Terminal PBs §fhave been reset.")
    }.withDescription("Deletes your terminal personal bests (the terminal simulator keeps its own).")
    private val terminalSplits by ToggleSetting("Terminal Splits", true).withDescription("Adds the section and phase time to every \"activated a terminal/lever/device\" message and sends all section times plus the total when the Core opens.")
    private val useRealTime by ToggleSetting("Use Real Time", true).withDescription("Measures the splits in real time instead of server ticks (server ticks ignore lag spikes but slow down with the server).").showIf { terminalSplits.value }

    private val terminalCompleteRegex = Regex("^(.{1,16}) (activated|completed) a (terminal|lever|device)! \\((\\d)/(\\d)\\)$")
    private val gateDestroyedRegex = Regex("^The gate has been destroyed!$")
    private val goldorRegex = Regex("^\\[BOSS] Goldor: Who dares trespass into my domain\\?$")
    private val coreOpeningRegex = Regex("^The Core entrance is opening!$")

    private var completed = 0 to 7
    private val times = mutableListOf<Float>()
    private var gateBlown = false
    private var sectionTimer = 0L
    private var phaseTimer = 0L
    private var currentTick = 0L

    override fun init() {
        register<TerminalActionEvent.Solve> {
            // Simulator solves are recorded and announced by the simulator itself.
            if (event.simulated) return@register
            TerminalPBs.recordSolve(event.handler, event.openTime, simulated = false, announce = terminalTimes.value)
        }

        // Lowest priority: the line is replaced only after every other feature saw the original message.
        register<ChatMessageEvent>(EventPriority.LOWEST) {
            if (! terminalSplits.value) return@register
            val text = event.unformattedText

            when {
                gateDestroyedRegex.matches(text) -> if (completed.first == completed.second) resetSection() else gateBlown = true
                goldorRegex.matches(text) -> resetSection(true)
                coreOpeningRegex.matches(text) -> {
                    resetSection()
                    ChatUtils.modMessage("§bTimes: §a${times.joinToString(" §8| ") { "§a${it.toFixed(2)}s" }}§8, §bTotal: §a${phaseTimer.seconds.toFixed(2)}s")
                }
            }

            val (name, action, type, current, total) = terminalCompleteRegex.find(text)?.destructured ?: return@register
            val currentInt = current.toIntOrNull() ?: return@register
            val totalInt = total.toIntOrNull() ?: return@register

            event.isCanceled = true
            ChatUtils.chat(Component.literal("§6$name §a$action a $type! (§c$current§a/$total) §8(§7${sectionTimer.seconds.toFixed(2)}s §8| §7${phaseTimer.seconds.toFixed(2)}s§8)"))

            if ((currentInt == totalInt && gateBlown) || currentInt < completed.first) resetSection()
            else completed = currentInt to totalInt
        }

        register<TickEvent.Server> {
            if (terminalSplits.value && ! useRealTime.value) currentTick += 50
        }

        register<WorldChangeEvent> { resetSection(true) }
    }

    private val now get() = if (useRealTime.value) System.currentTimeMillis() else currentTick

    private val Long.seconds get() = (now - this) / 1000f

    private fun resetSection(full: Boolean = false) {
        if (full) {
            times.clear()
            phaseTimer = now
        }
        else times.add(sectionTimer.seconds)
        completed = 0 to 7
        sectionTimer = now
        gateBlown = false
    }
}
