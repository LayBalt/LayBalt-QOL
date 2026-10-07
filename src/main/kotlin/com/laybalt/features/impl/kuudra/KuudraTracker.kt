// Ported from Odin (https://github.com/odtheking/Odin), BSD-3-Clause, Copyright (c) 2025 odtheking
package com.laybalt.features.impl.kuudra

import com.laybalt.config.PogObject
import com.laybalt.config.types.ButtonSetting
import com.laybalt.config.types.SliderSetting
import com.laybalt.event.impl.ChatMessageEvent
import com.laybalt.event.impl.ContainerEvent
import com.laybalt.event.impl.ScreenEvent
import com.laybalt.features.Feature
import com.laybalt.ui.utils.Resolution
import com.laybalt.utils.ChatUtils
import com.laybalt.utils.ChatUtils.removeFormatting
import com.laybalt.utils.equalsOneOf
import com.laybalt.utils.items.ItemUtils.lore
import com.laybalt.utils.kuudra.KuudraPrices
import com.laybalt.utils.render.Render2D.drawString
import com.laybalt.utils.render.RenderHelper.width
import gg.essential.universal.UMinecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import java.awt.Color
import java.util.*

object KuudraTracker: Feature("Tracks the loot and profit of every Kuudra chest you open.") {
    private val lineAmount by SliderSetting("Lines", 10, 0, 40, 1)
        .withDescription("How many of the most valuable items the tracker lists.")
    private val reset by ButtonSetting("Reset Profit") {
        data.set(TrackerData())
        last = null
        updateDisplay()
        ChatUtils.modMessage("§aKuudra tracker reset.")
    }.withDescription("Clears every tracked item, chest and key.")

    private val chestRegex = Regex("^(Free|Paid) Chest$")
    private val hudRegex = Regex("^((Free|Paid) Chest)|(Kuudra - .+)|(.+)?Vesuvius$")
    private val uselessLinesRegex = Regex("^Contents|Cost|Click to open!|FREE|Already opened!|Can't open another chest!|Paid Chest|[\\d,]+ Coins|$")
    private val amountRegex = Regex("^(.+)? x(\\d+)$")

    private val lightPurpleRegex = Regex("Fatal Tempo|Inferno|Crimson Essence")
    private val whiteRegex = Regex("Book")
    private val darkPurpleRegex = Regex("Kuudra Teeth|Fire Eel Shard|Kuudra Mandible|Barbarian Duke X Shard|XYZ Shard|Hellstorm Wand|Tentacle Dye")
    private val blueRegex = Regex("Kada Knight Shard|Wheel of Fate|Ananke Feather|Burning Kuudra Core|Matcho Shard|Kuudra Tentacle|Lava Flame Shard|Wither Spectre Shard")
    private val goldRegex = Regex("Boots|Leggings|Chestplate|Helmet|Cloak|Aurora Staff|Hollow Wand|Heavy Pearl|Kraken Shard|Hellwisp Shard|Ananke Shard|Moltenfish Shard|Tormentor")

    /** Persisted tracker state (config/<mod>/kuudra_tracker.json). [totalKeys] holds the keys used per tier 1-5. */
    data class TrackerData(
        var singleItems: MutableMap<String, Int> = mutableMapOf(),
        var multiItems: MutableMap<String, Int> = mutableMapOf(),
        var paid: Int = 0,
        var free: Int = 0,
        var totalKeys: MutableList<Int> = mutableListOf(0, 0, 0, 0, 0)
    )

    private val data = PogObject("kuudra_tracker", TrackerData())
    private val stats: TrackerData get() = data.get().also(::sanitize)

    /** What the last chest click added, so it can be undone if Hypixel refuses the opening. */
    private class LastAdded(val single: MutableList<String> = mutableListOf(), val multi: MutableList<Pair<String, Int>> = mutableListOf(), var keyTier: Int = 0, val paid: Boolean)

    private var last: LastAdded? = null
    private var toDisplay = listOf<Pair<String, Double>>()
    private var lastDisplayUpdate = 0L

    private val sampleProfitData = listOf(
        "§6Fervor Helmet" to 748000.0,
        "§fEnchanted Book (§dFerocious Mana V§f)" to 118500000.0,
        "§dCrimson Essence §8x2000" to 2420000.0,
        "§5Kuudra Teeth §8x4" to 35480.0,
        "§6Kraken Shard §8x2" to 821226.0
    )

    private val hud = hudElement("Kuudra Tracker HUD", shouldDraw = { false }) { ctx, example -> ctx.drawOverlay(example) }

    override fun init() {
        register<ScreenEvent.PostRender> {
            if (! event.screen.title.string.removeFormatting().matches(hudRegex)) return@register
            if (System.currentTimeMillis() - lastDisplayUpdate > 5000) updateDisplay()
            Resolution.push(event.context)
            hud.renderElement(event.context, false)
            Resolution.pop(event.context)
        }

        register<ChatMessageEvent> {
            if (! event.unformattedText.equalsOneOf("You cannot afford this!", "Whoa! Slow down there!")) return@register
            val title = (UMinecraft.currentScreenObj as? Screen)?.title?.string?.removeFormatting() ?: return@register
            if (! title.matches(chestRegex)) return@register
            val added = last ?: return@register
            val s = stats

            added.single.forEach { s.singleItems[it] = (s.singleItems.getOrDefault(it, 0) - 1).coerceAtLeast(0) }
            added.multi.forEach { (name, amount) -> s.multiItems[name] = (s.multiItems.getOrDefault(name, 0) - amount).coerceAtLeast(0) }
            if (added.keyTier in 1 .. 5) s.totalKeys[added.keyTier - 1] = (s.totalKeys[added.keyTier - 1] - 1).coerceAtLeast(0)
            if (added.paid) s.paid = (s.paid - 1).coerceAtLeast(0) else s.free = (s.free - 1).coerceAtLeast(0)

            last = null
            save()
        }

        register<ContainerEvent.SlotClick> {
            val title = event.screen.title.string.removeFormatting()
            if (! title.matches(chestRegex)) return@register

            val chest = findChestStack(event) ?: return@register
            val loreLines = chest.lore.map { it.removeFormatting().trim() }
            if (loreLines.any { it.equalsOneOf("Already opened!", "Can't open another chest!") }) return@register

            val s = stats
            val added = LastAdded(paid = title == "Paid Chest")

            for (line in loreLines) {
                if (line.matches(uselessLinesRegex)) continue

                if (line.contains("Kuudra Key")) {
                    KuudraPrices.keys.find { it.type == line }?.let { key ->
                        s.totalKeys[key.tier - 1]++
                        added.keyTier = key.tier
                    }
                    continue
                }

                amountRegex.find(line)?.destructured?.let { (name, amount) ->
                    val count = amount.toIntOrNull() ?: 0
                    s.multiItems[name] = s.multiItems.getOrDefault(name, 0) + count
                    added.multi.add(name to count)
                } ?: run {
                    s.singleItems[line] = s.singleItems.getOrDefault(line, 0) + 1
                    added.single.add(line)
                }
            }

            if (added.paid) s.paid++ else s.free++
            last = added
            save()
        }
    }

    /** The reward chest item: the clicked slot (slot 31 of the chest menu) or, as in Odin, the item on the cursor. */
    private fun findChestStack(event: ContainerEvent.SlotClick): ItemStack? {
        val carried = mc.player?.containerMenu?.carried
        if (carried != null && carried.`is`(Items.CHEST)) return carried
        val slot = event.screen.menu.slots.getOrNull(event.slotId) ?: return null
        return slot.item.takeIf { it.`is`(Items.CHEST) && event.slotId == 31 }
    }

    private fun save() {
        data.save()
        updateDisplay()
    }

    /**
     * Takes over Odin's Kuudra Tracker totals (used by [com.laybalt.config.ForeignConfigImport]), but only while this
     * tracker has no data of its own, so a repeated import never counts chests twice. @return true if imported.
     */
    fun importTotals(single: Map<String, Int>, multi: Map<String, Int>, paid: Int, free: Int, keys: List<Int>): Boolean {
        val d = stats
        val empty = d.singleItems.isEmpty() && d.multiItems.isEmpty() && d.paid == 0 && d.free == 0 && d.totalKeys.all { it == 0 }
        val incoming = single.isNotEmpty() || multi.isNotEmpty() || paid > 0 || free > 0 || keys.any { it > 0 }
        if (! empty || ! incoming) return false
        d.singleItems = single.toMutableMap()
        d.multiItems = multi.toMutableMap()
        d.paid = paid
        d.free = free
        d.totalKeys = MutableList(5) { keys.getOrNull(it) ?: 0 }
        data.save()
        return true
    }

    private fun sanitize(d: TrackerData) {
        @Suppress("SENSELESS_COMPARISON")
        if (d.singleItems == null) d.singleItems = mutableMapOf()
        @Suppress("SENSELESS_COMPARISON")
        if (d.multiItems == null) d.multiItems = mutableMapOf()
        // Gson can leave fields null when the file is missing them
        val keys: List<Int?>? = d.totalKeys
        if (keys == null || keys.size != 5) d.totalKeys = MutableList(5) { i -> keys?.getOrNull(i) ?: 0 }
    }

    private fun updateDisplay() {
        lastDisplayUpdate = System.currentTimeMillis()
        val s = stats
        val bonus = Vesuvius.essenceBonus
        val salvage = Vesuvius.useSalvage
        val display = mutableListOf<Pair<String, Double>>()

        s.singleItems.forEach { (name, amount) ->
            if (amount == 0) return@forEach
            val price = KuudraPrices.parseItemValue(Component.literal(name), bonus, salvage)?.times(amount) ?: return@forEach
            display.add("${getColor(name)}$name §8x$amount" to price)
        }

        s.multiItems.forEach { (name, amount) ->
            if (amount == 0) return@forEach
            val price = KuudraPrices.parseItemValue(Component.literal("$name x$amount"), bonus, salvage) ?: return@forEach
            display.add("${getColor(name)}$name §8x$amount" to price)
        }

        toDisplay = display.sortedByDescending { it.second }
    }

    private fun getKeyPrices(): Double {
        val keys = stats.totalKeys
        return (1 .. 5).sumOf { tier -> KuudraPrices.getPriceOfKey(tier) * keys[tier - 1] }
    }

    private fun format(value: Double) = String.format(Locale.US, "%,.0f", value)

    private fun GuiGraphicsExtractor.drawOverlay(isEditing: Boolean): Pair<Int, Int> {
        var y = 0
        val maxWidth = 300
        var profit = 0.0

        val display = if (isEditing) sampleProfitData.sortedByDescending { it.second } else toDisplay
        display.forEachIndexed { index, (name, value) ->
            profit += value
            if (index >= lineAmount.value) return@forEachIndexed
            val price = format(value)
            drawString(name, 0, y)
            drawString(price, maxWidth - price.width(), y, Color(0x555555))
            y += 9
        }

        y += 6
        val keyCost = if (isEditing) KuudraPrices.getPriceOfKey("Infernal Kuudra Key") else getKeyPrices()
        val keyPrices = format(keyCost)
        drawString("§cTotal Key Costs:", 0, y)
        drawString(keyPrices, maxWidth - keyPrices.width(), y, Color(0xFF5555))
        y += 12

        val totalProfit = profit - keyCost
        val profitStr = format(totalProfit)
        drawString("§aProfit:", 0, y)
        drawString(profitStr, maxWidth - profitStr.width(), y, Color(0x55FF55))
        y += 15

        val freeChests = if (isEditing) 0 else stats.free
        val paidChests = if (isEditing) 1 else stats.paid
        val total = freeChests + paidChests
        val avg = "Avg [${format(if (total == 0) 0.0 else totalProfit / total)}]"
        drawString("Total Chests: Free [$freeChests], Paid [$paidChests]", 0, y, Color(0xFFFF55))
        drawString(avg, maxWidth - avg.width(), y, Color(0xFFAA00))
        y += 9

        return maxWidth to y
    }

    private fun getColor(name: String) = when {
        name.contains(whiteRegex) -> "§f"
        name.contains(goldRegex) -> "§6"
        name.contains(darkPurpleRegex) -> "§5"
        name.contains(lightPurpleRegex) -> "§d"
        name.contains(blueRegex) -> "§9"
        else -> "§f"
    }
}
