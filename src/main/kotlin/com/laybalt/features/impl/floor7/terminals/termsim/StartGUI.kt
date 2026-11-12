// Ported from Odin (https://github.com/odtheking/Odin), BSD-3-Clause, Copyright (c) 2025 odtheking
package com.laybalt.features.impl.floor7.terminals.termsim

import com.laybalt.config.ConfigManager
import com.laybalt.features.impl.floor7.terminals.TerminalPBs
import com.laybalt.features.impl.floor7.terminals.TerminalSimulator
import com.laybalt.utils.ChatUtils
import com.laybalt.utils.NumbersUtils.toFixed
import com.laybalt.utils.ThreadUtils
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.DyeColor
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.ItemLore

/** Terminal simulator start menu: pick a terminal, a random one, or reset the simulator PBs. */
class StartGUI: TermSimGUI("Terminal Simulator", 27) {
    private fun termItem(type: SimType, item: Item, color: String) = named(ItemStack(item), "$color${type.label}").apply {
        val pb = TerminalPBs.get(TerminalPBs.simKey(type.pbName))
        val lore = if (pb == null) "§7No PB set!" else "§7Personal Best: §d${pb.toFixed(2)}s"
        set(DataComponents.LORE, ItemLore(listOf(Component.literal(lore))))
    }

    override fun create() {
        val termItems = listOf(
            termItem(SimType.PANES, Items.DYE.pick(DyeColor.LIME), "§a"),
            termItem(SimType.RUBIX, Items.DYE.pick(DyeColor.RED), "§6"),
            termItem(SimType.NUMBERS, Items.DYE.pick(DyeColor.CYAN), "§3"),
            termItem(SimType.STARTS_WITH, Items.DYE.pick(DyeColor.PINK), "§5"),
            termItem(SimType.SELECT, Items.DYE.pick(DyeColor.BROWN), "§b"),
            termItem(SimType.MELODY, Items.DYE.pick(DyeColor.PURPLE), "§d"),
        )

        setSlots {
            when (it.index) {
                4 -> named(ItemStack(Items.DYE.pick(DyeColor.BLACK)), "§cReset PBs!")
                13 -> named(ItemStack(Items.DYE.pick(DyeColor.WHITE)), "§7Random")
                in 10 .. 12 -> termItems[it.index - 10]
                in 14 .. 16 -> termItems[it.index - 11]
                22 -> autoNextItem()
                else -> blackPane
            }
        }
    }

    override fun slotClick(slot: Slot, button: Int) {
        when (slot.index) {
            4 -> {
                if (! confirmReset) {
                    ChatUtils.modMessage("§cAre you sure you want to reset your terminal simulator PBs? Click again to confirm.")
                    confirmReset = true
                    ThreadUtils.scheduledTask(60) {
                        if (! confirmReset) return@scheduledTask
                        ChatUtils.modMessage("§aPBs reset cancelled.")
                        confirmReset = false
                    }
                    return
                }
                TerminalPBs.reset(simulator = true)
                create()
                ChatUtils.modMessage("§aTerminal simulator PBs reset!")
                confirmReset = false
            }

            10 -> pick(SimType.PANES)
            11 -> pick(SimType.RUBIX)
            12 -> pick(SimType.NUMBERS)
            13 -> pick(SimType.random(), random = true)
            14 -> pick(SimType.STARTS_WITH)
            15 -> pick(SimType.SELECT)
            16 -> pick(SimType.MELODY)

            22 -> {
                TerminalSimulator.autoNext.value = ! TerminalSimulator.autoNext.value
                ConfigManager.save()
                create()
            }
        }
    }

    private fun pick(type: SimType, random: Boolean = false) {
        TerminalSimulator.lastPickWasRandom = random
        type.factory().open(ping)
    }

    private fun autoNextItem(): ItemStack {
        val on = TerminalSimulator.autoNext.value
        return named(ItemStack(Items.DYE.pick(if (on) DyeColor.LIME else DyeColor.GRAY)), "§eAuto Next: ${if (on) "§aON" else "§cOFF"}").apply {
            set(DataComponents.LORE, ItemLore(listOf(Component.literal("§7Opens the next terminal when you finish one."))))
        }
    }

    private companion object {
        var confirmReset = false
    }
}
