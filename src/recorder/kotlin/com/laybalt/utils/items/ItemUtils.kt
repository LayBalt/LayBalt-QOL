package com.laybalt.utils.items

import com.laybalt.utils.ChatUtils.formattedText
import com.laybalt.utils.ChatUtils.removeFormatting
import com.laybalt.utils.ChatUtils.unformattedText
import com.laybalt.utils.equalsOneOf
import com.laybalt.utils.remove
import com.laybalt.utils.startsWithOneOf
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.minecraft.core.component.DataComponents
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.CustomData
import net.minecraft.world.item.component.ItemLore
import java.util.TreeMap
import kotlin.jvm.optionals.getOrNull

/**
 * The recorder-only jar's item helpers: LayBalt QOL's [skyblockId], same code, so a recording names items (weapons,
 * rods) exactly as the full mod's recordings do.
 */
object ItemUtils {
    @Serializable
    private data class PetSummary(val type: String, val tier: String)

    private val json = Json { ignoreUnknownKeys = true }

    val ItemStack.customData get() = getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag()
    val ItemStack.lore get() = getOrDefault(DataComponents.LORE, ItemLore.EMPTY).styledLines().map { it.formattedText }
    val ItemStack.skyblockId: String
        get() {
            if (isEmpty) return ""
            val name = hoverName.unformattedText
            val customData = customData
            var sbItemID: String? = null

            if (customData.contains("id")) sbItemID = customData.getString("id").getOrNull()?.replace(":", "-")

            if (sbItemID == "PET") {
                val petInfoRaw = customData.getString("petInfo").getOrNull()?.takeIf { it.isNotEmpty() } ?: return sbItemID
                val petInfo = json.decodeFromString<PetSummary>(petInfoRaw)
                return "PET-${petInfo.type}-${petInfo.tier}"
            }

            if (sbItemID == "ENCHANTED_BOOK") {
                customData.getCompound("enchantments").getOrNull()?.let { enchantments ->
                    val enchantId = enchantments.keySet().singleOrNull()
                    val level = enchantId?.let { enchantments.getIntOr(it, 0) } ?: 0
                    if (enchantId != null && level > 0) return "ENCHANTMENT_${enchantId.uppercase()}_$level"
                }

                val lore = lore
                val bookName = lore[0].takeIf { it != "§8Combinable in Anvil" } ?: lore[2]
                val enchantName = bookName.substringBeforeLast(" ")
                val levelStr = bookName.substringAfterLast(" ").removeFormatting()

                val name = enchantName.removeFormatting().uppercase().replace(" ", "_")
                val level = levelStr.toIntOrNull() ?: levelStr.romanToDecimal()
                val isUltimate = enchantName.startsWithOneOf("§9§d§l", "§d§l", "§7§l") && ! name.contains("ULTIMATE_")

                return "ENCHANTMENT_${if (isUltimate) "ULTIMATE_" else ""}${name}_$level"
            }

            if (sbItemID.equalsOneOf("RUNE", "UNIQUE_RUNE")) {
                val runes = customData.getCompound("runes").getOrNull() ?: return ""
                val runeId = runes.keySet().singleOrNull() ?: return ""
                val level = runes.getIntOr(runeId, 0)
                if (level <= 0) return ""
                return "RUNE-${runeId.uppercase()}-$level"
            }

            if (sbItemID == "POTION") {
                val potion = customData.getString("potion").getOrNull()?.takeIf(String::isNotEmpty) ?: return ""
                val level = customData.getIntOr("potion_level", 0)
                if (level <= 0) return ""

                return "POTION-${potion.uppercase()}-$level${if (customData.getBooleanOr("enhanced", false)) "-ENHANCED" else ""}"
            }

            if (sbItemID == "ATTRIBUTE_SHARD" || (sbItemID == null && isShard(name, lore))) return getShardIdFromName(name)

            return sbItemID.orEmpty()
        }

    fun isShard(displayName: String, lore: List<String>) = " Shard " in displayName || displayName.endsWith(" Shard") || lore.lastOrNull()?.removeFormatting()?.substringBefore('(')?.trimEnd()?.endsWith(" SHARD") == true
    fun getShardIdFromName(displayName: String): String {
        val name = displayName.removeFormatting().uppercase().remove(shardCountSuffix).removeSuffix(" SHARD").replace(" ", "_")
        return shardIdOverrides[name] ?: "SHARD_$name"
    }

    private val romanNumbers = TreeMap(mapOf('m' to 1000, 'd' to 500, 'c' to 100, 'l' to 50, 'x' to 10, 'v' to 5, 'i' to 1))

    private fun String.romanToDecimal(): Int {
        var lastValue = 0
        var decimal = 0

        for (i in lastIndex downTo 0) {
            val value = romanNumbers[get(i).lowercaseChar()] ?: continue
            decimal += if (value < lastValue) - value else value
            lastValue = value
        }

        return decimal
    }

    private val shardCountSuffix = Regex(" X\\d+$")
    private val shardIdOverrides = mapOf(
        "BOGGED" to "SHARD_SEA_ARCHER",
        "LOTUSFISH" to "SHARD_LOTUS_FISH",
        "INKLING" to "SHARD_NIGHT_SQUID",
        "LOCH_EMPEROR" to "SHARD_SEA_EMPEROR",
        "INFERNO_DEMONLORD" to "SHARD_BURNINGSOUL",
        "END_STONE_PROTECTOR" to "SHARD_ENDSTONE_PROTECTOR",
        "CINDERBAT" to "SHARD_CINDER_BAT",
        "BEETLE" to "SHARD_CROPEETLE",
        "ABYSSAL_LANTERNFISH" to "SHARD_ABYSSAL_LANTERN",
        "SEASHINE" to "SHARD_SEA_SHINE",
        "WITHER_SPECTRE" to "SHARD_WITHER_SPECTER",
        "FIELD_MOUSE" to "SHARD_PEST",
        "ZEALOT_BRUISER" to "SHARD_BRUISER",
        "STRIDERSURFER" to "SHARD_STRIDER_SURFER",
        "EARTHWORM" to "SHARD_TERMITE",
        "FLIPFLOPPER" to "SHARD_FLIP_FLOPPER"
    )
}
