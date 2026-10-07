// Ported from Odin (https://github.com/odtheking/Odin), BSD-3-Clause, Copyright (c) 2025 odtheking
package com.laybalt.features.impl.floor7.terminals

import com.laybalt.config.PogObject
import com.laybalt.features.impl.floor7.terminals.impl.Terminal
import com.laybalt.utils.ChatUtils
import com.laybalt.utils.NumbersUtils.toFixed

/**
 * Personal bests (seconds) per terminal type, for real terminals and for the terminal simulator.
 * Stored in its own file so they can be reset without touching the other personal bests.
 */
object TerminalPBs {
    private const val SIM_PREFIX = "termsim_"
    private val data = PogObject("terminal_pbs", mutableMapOf<String, Double>())

    fun simKey(name: String) = SIM_PREFIX + name

    fun get(key: String): Double? = data.get()[key]

    /** Records [seconds] for [key]. @return the previous PB, and whether [seconds] beat it. */
    fun record(key: String, seconds: Double): Pair<Double?, Boolean> {
        val old = data.get()[key]
        val isNew = old == null || seconds < old
        if (isNew) {
            data.get()[key] = seconds
            data.save()
        }
        return old to isNew
    }

    /**
     * Records a solve of [terminal] and, if [announce], prints the time with the PB comparison (Odin's format).
     */
    fun recordSolve(terminal: Terminal, openTime: Long, simulated: Boolean, announce: Boolean) {
        val seconds = ((System.currentTimeMillis() - openTime) / 1000.0).toFixed(2).toDouble()
        val (oldPB, isNew) = record(if (simulated) simKey(terminal.displayName) else terminal.displayName, seconds)
        if (! announce) return

        val pbText = when {
            oldPB == null -> "§7(§d§lFirst PB§r§7)"
            isNew -> "§7(§d§lNew PB§r§7) Old PB was §8${oldPB.toFixed(2)}"
            else -> "§8(§7${oldPB.toFixed(2)}§8)"
        }
        val sim = if (simulated) " §7(termsim)" else ""
        ChatUtils.modMessage("§a${terminal.displayName}$sim §7solved in §6${seconds.toFixed(2)}s§7! $pbText")
    }

    /** Removes the simulator PBs ([simulator] = true) or the real terminal PBs. */
    fun reset(simulator: Boolean) {
        data.get().keys.removeIf { it.startsWith(SIM_PREFIX) == simulator }
        data.save()
    }
}
