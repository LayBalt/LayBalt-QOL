// Ported from OdinClient (https://github.com/skies-starred/OdinClient), BSD-3-Clause, Copyright (c) 2026 Starred
/**
 * Taken from OdinClient 1.8.9
 * odtheking's BSD-3 Clause License applies to this file.
 *
 * BSD 3-Clause License
 *
 * Copyright (c) 2023-2025, odtheking
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its
 *    contributors may be used to endorse or promote products derived from
 *    this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.laybalt.features.impl.dungeon

//#if CHEAT

import com.laybalt.commands.CommandBuilder
import com.laybalt.config.ConfigManager
import com.laybalt.config.types.*
import com.laybalt.event.impl.TickEvent
import com.laybalt.features.Feature
import com.laybalt.init.types.ICommandProvider
import com.laybalt.utils.ChatUtils
import com.laybalt.utils.ChatUtils.removeFormatting
import com.laybalt.utils.ChatUtils.unformattedText
import com.laybalt.utils.GuiUtils
import com.laybalt.utils.items.ItemUtils.lore
import com.mojang.brigadier.arguments.StringArgumentType
import gg.essential.universal.UMinecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.item.Items

object AutoSell: Feature("Automatically sells listed items in trade and Booster Cookie sell menus. Manage the list with /lbqsell."), ICommandProvider {
    private val sellList by ListSetting("Sell List", mutableListOf<String>())
    private val delay by SliderSetting("Delay", 6, 2, 10, 1, " ticks").withDescription("The delay between two sell clicks.")
    private val randomization by SliderSetting("Randomization", 1, 0, 5, 1, " ticks").withDescription("A random extra delay (0 to this value) added to every click.")
    private val clickType by DropdownSetting("Click Type", 0, listOf("Shift", "Middle", "Left"))
        .withDescription("The click used to sell an item: shift-click, middle-click or a normal left click.")
    private val addDefaults by ButtonSetting("Add Defaults") {
        val added = defaultItems.count { item -> item !in sellList.value && sellList.value.add(item) }
        ChatUtils.modMessage("&aAdded $added default dungeon items to the Auto Sell list.")
        ConfigManager.save()
    }.withDescription("Adds the default dungeon loot (mob drops, armor, trash items) to the sell list.")
    private val printList by ButtonSetting("Print List") { printSellList() }
        .withDescription("Prints the current sell list in chat (the list itself is edited with /lbqsell).")
    private val clearList by ButtonSetting("Clear List") {
        sellList.value.clear()
        ChatUtils.modMessage("&aAuto Sell list cleared.")
        ConfigManager.save()
    }.withDescription("Removes every entry from the sell list.")

    private val defaultItems = listOf(
        "enchanted ice", "superboom tnt", "rotten", "skeleton master", "skeleton grunt", "cutlass",
        "skeleton lord", "skeleton soldier", "zombie soldier", "zombie knight", "zombie commander", "zombie lord",
        "skeletor", "super heavy", "heavy", "sniper helmet", "dreadlord", "earth shard", "zombie commander whip",
        "machine gun", "sniper bow", "soulstealer bow", "silent death", "training weight",
        "beating heart", "premium flesh", "mimic fragment", "enchanted rotten flesh", "sign",
        "enchanted bone", "defuse kit", "optical lens", "tripwire hook", "button", "carpet", "lever", "diamond atom",
        "healing viii splash potion", "healing 8 splash potion", "candycomb"
    )

    private val blacklist = listOf("skeleton master chestplate")

    private var last = 0L
    private var next = 0L

    override fun init() {
        register<TickEvent.Start> {
            if (sellList.value.isEmpty()) return@register
            val menu = (UMinecraft.currentScreenObj as? AbstractContainerScreen<*>)?.menu ?: return@register
            val now = System.currentTimeMillis()
            if (now - last < next) return@register

            val sellSlot = menu.slots.getOrNull(49)?.item ?: return@register
            val isSellButton = sellSlot.item == Items.HOPPER && sellSlot.hoverName.unformattedText == "Sell Item"
            val isBuyback = sellSlot.lore.lastOrNull()?.removeFormatting() == "Click to buyback!"
            if (! isSellButton && ! isBuyback) return@register

            for (slot in menu.slots) {
                if (slot.container !is Inventory) continue
                val stack = slot.item.takeUnless { it.isEmpty } ?: continue
                val name = stack.hoverName.unformattedText

                if (sellList.value.none { name.contains(it, true) }) continue
                if (blacklist.any { name.contains(it, true) }) continue

                when (clickType.value) {
                    1 -> GuiUtils.clickSlot(slot.index, GuiUtils.ButtonType.MIDDLE)
                    2 -> GuiUtils.clickSlot(slot.index, GuiUtils.ButtonType.LEFT)
                    else -> GuiUtils.clickSlot(slot.index, GuiUtils.ButtonType.LEFT, shift = true)
                }
                last = now
                next = (delay.value + (0 .. randomization.value).random()) * 50L
                break
            }
        }
    }

    private fun heldItemName() = mc.player?.mainHandItem?.takeUnless { it.isEmpty }?.hoverName?.unformattedText?.lowercase()

    private fun addItem(name: String?) {
        val entry = name?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return ChatUtils.modMessage("&cHold an item or write an item name to add it to Auto Sell.")
        if (entry in sellList.value) return ChatUtils.modMessage("&e\"$entry\" &cis already in the Auto Sell list.")
        sellList.value.add(entry)
        ConfigManager.save()
        ChatUtils.modMessage("&aAdded &e\"$entry\" &ato the Auto Sell list.")
    }

    private fun removeItem(name: String?) {
        val entry = name?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return ChatUtils.modMessage("&cHold an item or write an item name to remove it from Auto Sell.")
        if (! sellList.value.remove(entry)) return ChatUtils.modMessage("&e\"$entry\" &cis not in the Auto Sell list.")
        ConfigManager.save()
        ChatUtils.modMessage("&aRemoved &e\"$entry\" &afrom the Auto Sell list.")
    }

    private fun printSellList() {
        ChatUtils.modMessage("&aAuto Sell list (${sellList.value.size}):")
        sellList.value.forEach { ChatUtils.modMessage("  &b$it") }
    }

    override fun CommandBuilder.command() {
        setName("lbqsell")
        alsoUnderLbq("sell")
        category(com.laybalt.commands.LbqHelp.Category.ITEMS)
        description("Manage the Auto Sell list")
        runs { ChatUtils.modMessage("&bUsage: /lbqsell <add|remove|clear|list|defaults> [item name]") }

        literal("add") {
            description("Add the held item (or the given name) to Auto Sell")
            runs { addItem(heldItemName()) }
            argument("name", StringArgumentType.greedyString()) {
                runs { ctx -> addItem(StringArgumentType.getString(ctx, "name")) }
            }
        }

        literal("remove") {
            description("Remove the held item (or the given name) from Auto Sell")
            runs { removeItem(heldItemName()) }
            argument("name", StringArgumentType.greedyString()) {
                suggests { sellList.value }
                runs { ctx -> removeItem(StringArgumentType.getString(ctx, "name")) }
            }
        }

        literal("clear") {
            description("Clear the Auto Sell list")
            runs { clearList() }
        }

        literal("list") {
            description("Show the Auto Sell list")
            runs { printSellList() }
        }

        literal("defaults") {
            description("Add the default dungeon items to Auto Sell")
            runs { addDefaults() }
        }
    }
}
//#endif
