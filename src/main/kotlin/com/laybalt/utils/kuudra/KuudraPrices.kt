// Ported from Odin (https://github.com/odtheking/Odin), BSD-3-Clause, Copyright (c) 2025 odtheking
package com.laybalt.utils.kuudra

import com.laybalt.init.NetworkLoop
import com.laybalt.utils.ChatUtils.removeFormatting
import com.laybalt.utils.NumbersUtils.romanToDecimal
import com.laybalt.utils.items.ItemUtils
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.TextColor
import java.util.*

/**
 * Prices of Kuudra chest contents and keys, used by Vesuvius and the Kuudra Tracker.
 * Prices come from [NetworkLoop] (bazaar sell-offer price, falling back to the lowest BIN).
 */
object KuudraPrices {
    /** A Kuudra key: base coin price, amount of crafting material (enchanted red sand / mycelium) and its tier. */
    data class Key(val type: String, val coins: Int, val quantity: Int, val tier: Int)

    val keys = listOf(
        Key("Kuudra Key", 155200, 2, 1),
        Key("Hot Kuudra Key", 310400, 4, 2),
        Key("Burning Kuudra Key", 582000, 16, 3),
        Key("Fiery Kuudra Key", 1164000, 40, 4),
        Key("Infernal Kuudra Key", 2328000, 80, 5)
    )

    private val enchantedBookRegex = Regex("^Enchanted Book \\(?([\\w ]+) (\\w+)\\)$")
    private val essenceRegex = Regex("^(\\w+) Essence x(\\d+)$")
    private val shardRegex = Regex("^([A-Za-z' ]+) Shard(?: x(\\d+))?$")
    private val teethRegex = Regex("^Kuudra Teeth x(\\d+)$")
    private val pearlRegex = Regex("^Heavy Pearl x(\\d+)$")
    private val salvageItemsRegex = Regex("Boots|Leggings|Chestplate|Helmet|Cloak|Aurora Staff|Hollow Wand")

    private val ultimateEnchants = setOf("Fatal Tempo", "Inferno")

    private val itemReplacements = mapOf(
        "Hellstorm Wand" to "HELLSTORM_STAFF",
        "Aurora Staff" to "RUNIC_STAFF",
    )

    /** Book names whose bazaar id differs from the displayed name (NA id format `ENCHANTMENT_<NAME>_<level>`). */
    private val enchantReplacements = mapOf(
        "Vivacious Vitality" to "FEROCIOUS_MANA",
        "Hardened Vitality" to "HARDENED_MANA",
        "Strong Vitality" to "STRONG_MANA",
        "Vampiric Vitality" to "MANA_VAMPIRE",
    )

    /** Crimson essence gained by salvaging a Kuudra armour piece / weapon by its star count. */
    private val starCountToEssence = mapOf(
        0 to 120.0, 1 to 138.0, 2 to 158.0, 3 to 182.0,
        4 to 210.0, 5 to 240.0, 6 to 272.0, 7 to 308.0
    )

    fun price(id: String): Double? = NetworkLoop.getPrice(id)?.toDouble()

    /** Price of one key including its materials (cheapest of enchanted red sand / mycelium) and 2 corrupted nether stars. */
    fun getPriceOfKey(key: String): Double {
        val found = keys.find { it.type == key } ?: return 0.0
        val material = minOf(price("ENCHANTED_RED_SAND") ?: 0.0, price("ENCHANTED_MYCELIUM") ?: 0.0)
        val star = price("CORRUPTED_NETHER_STAR") ?: 0.0
        return found.coins + material * found.quantity + star * 2
    }

    fun getPriceOfKey(tier: Int) = keys.find { it.tier == tier }?.let { getPriceOfKey(it.type) } ?: 0.0

    /**
     * Value of one chest line such as `Crimson Essence x1200`, `Enchanted Book (Fatal Tempo I)` or `Kraken Shard x2`.
     * [component] keeps the original style so salvage prices can count gold (1) and light purple (2) stars.
     */
    fun parseItemValue(component: Component, essenceBonus: Double = 1.0, useSalvagePrices: Boolean = false): Double? {
        var starCount = 0
        val gold = TextColor.fromLegacyFormat(ChatFormatting.GOLD)
        val lightPurple = TextColor.fromLegacyFormat(ChatFormatting.LIGHT_PURPLE)
        component.visit({ style, str ->
            val count = str.count { it == '✪' }
            if (count > 0) when (style.color) {
                gold -> starCount += count
                lightPurple -> starCount += count * 2
            }
            Optional.empty<Unit>()
        }, Style.EMPTY)

        val text = component.string.removeFormatting()
        val item = text.replace("✪", "").trim()

        if (item.contains("Molten") && useSalvagePrices) return (price("ESSENCE_CRIMSON") ?: 0.0) * 600.0 * essenceBonus

        enchantedBookRegex.find(item)?.destructured?.let { (name, level) ->
            val lvl = level.toIntOrNull() ?: level.romanToDecimal()
            enchantReplacements[name]?.let { return price("ENCHANTMENT_${it}_$lvl") }
            val ult = if (name in ultimateEnchants) "ULTIMATE_" else ""
            return price("ENCHANTMENT_$ult${name.uppercase().replace(" ", "_")}_$lvl")
        }

        essenceRegex.find(item)?.destructured?.let { (name, quantity) ->
            val essence = price("ESSENCE_${name.uppercase()}") ?: return null
            return essence * quantity.toDouble() * essenceBonus
        }

        shardRegex.find(item)?.let { shard ->
            val shardPrice = price(ItemUtils.getShardIdFromName("${shard.groupValues[1]} Shard"))
                ?: price("SHARD_${shard.groupValues[1].uppercase().replace(" ", "_")}") ?: 0.0
            return shardPrice * (shard.groupValues.getOrNull(2)?.toDoubleOrNull() ?: 1.0)
        }

        teethRegex.find(item)?.destructured?.let { (quantity) -> return (price("KUUDRA_TEETH") ?: 0.0) * quantity.toDouble() }
        pearlRegex.find(item)?.destructured?.let { (quantity) -> return (price("HEAVY_PEARL") ?: 0.0) * quantity.toDouble() }

        if (useSalvagePrices && salvageItemsRegex.containsMatchIn(item)) {
            return (price("ESSENCE_CRIMSON") ?: 0.0) * (starCountToEssence[starCount] ?: 0.0)
        }

        itemReplacements[item]?.let { return price(it) }
        NetworkLoop.nameToIdMap[item]?.let { id -> price(id)?.let { return it } }
        return price(item.uppercase().replace("'", "").replace(" ", "_"))
    }
}
