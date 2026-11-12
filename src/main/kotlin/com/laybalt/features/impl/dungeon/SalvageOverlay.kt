package com.laybalt.features.impl.dungeon

import com.laybalt.config.types.ColorSetting
import com.laybalt.config.types.DropdownSetting
import com.laybalt.config.types.SliderSetting
import com.laybalt.config.types.ToggleSetting
import com.laybalt.event.impl.ContainerEvent
import com.laybalt.features.Feature
import com.laybalt.init.NetworkLoop
import com.laybalt.utils.ChatUtils.removeFormatting
import com.laybalt.utils.ChatUtils.unformattedText
import com.laybalt.utils.ColorUtils.withAlpha
import com.laybalt.utils.PlayerUtils
import com.laybalt.utils.items.ItemUtils.customData
import com.laybalt.utils.items.ItemUtils.lore
import com.laybalt.utils.items.ItemUtils.skyblockId
import com.laybalt.utils.location.LocationUtils
import com.laybalt.utils.render.Render2D.highlight
import net.minecraft.world.item.ItemStack
import java.awt.Color
import kotlin.jvm.optionals.getOrNull

object SalvageOverlay: Feature("Highlights dungeon gear that is safe to salvage.") {
    private val under50 by ColorSetting("Highlight Color", Color.CYAN.withAlpha(160))
        .withDescription("Colour used for salvageable dungeon gear")
    private val base50 by ColorSetting("50% stats Color", Color.RED.withAlpha(160))
        .withDescription("Colour used for gear that rolled the maximum 50% stat boost (often worth keeping)")

    private val onlyInSalvageMenu by ToggleSetting("Only In Salvage Menu", true).section("Filters")
        .withDescription("Only highlight while the Salvage Items menu of the blacksmith NPC is open. Turn off to highlight gear in every inventory")
    private val detectionMode by DropdownSetting("Detection", 2, listOf("Stat Boost", "Dungeon Rarity Line", "Either"))
        .withDescription("How dungeon gear is recognised. &bStat Boost&r: the item carries a dungeon stat-boost roll. &bDungeon Rarity Line&r: the lore has a rarity line such as RARE DUNGEON SWORD (the generic DUNGEON ITEM line does not count). &bEither&r: any of the two")
    private val maxValue by SliderSetting("Max Value", 100_000, 0, 5_000_000, 10_000, " coins")
        .withDescription("Only highlight gear whose market price is below this value, so expensive gear is never suggested for salvaging. Uses the bazaar/lowest-BIN price of the base item (stars, enchants and other upgrades are ignored). Set to 0 to disable the ceiling")
    private val highlightUnpriced by ToggleSetting("Highlight Unpriced Items", false)
        .withDescription("Also highlight gear that has no known market price. When off, items without price data stay unmarked while the price ceiling is enabled")
        .showIf { maxValue.value > 0 }

    private val blacklist = setOf("ICE_SPRAY_WAND", "STARRED_ICE_SPRAY_WAND")
    private val dungeonRarityLine = Regex("DUNGEON(?! ITEM)")
    private const val SALVAGE_MENU_TITLE = "Salvage Items"

    override fun init() {
        register<ContainerEvent.Render.Slot.Pre> {
            if (! LocationUtils.inSkyblock) return@register
            if (onlyInSalvageMenu.value && ! event.screen.title.unformattedText.startsWith(SALVAGE_MENU_TITLE)) return@register
            val stack = event.slot.item.takeUnless { it.isEmpty } ?: return@register
            if (stack in PlayerUtils.getArmor()) return@register
            val id = stack.skyblockId
            if (id in blacklist) return@register
            if (stack.hoverName.string.contains("✪")) return@register

            val statBoost = stack.customData.getInt("baseStatBoostPercentage").getOrNull()
            val isDungeonGear = when (detectionMode.value) {
                0 -> statBoost != null
                1 -> hasDungeonRarityLine(stack)
                else -> statBoost != null || hasDungeonRarityLine(stack)
            }
            if (! isDungeonGear) return@register
            if (! isBelowPriceCeiling(id)) return@register

            event.slot.highlight(event.context, if (statBoost == 50) base50.value else under50.value, 1)
        }
    }

    private fun hasDungeonRarityLine(stack: ItemStack) = stack.lore.any { dungeonRarityLine.containsMatchIn(it.removeFormatting()) }

    private fun isBelowPriceCeiling(id: String): Boolean {
        val ceiling = maxValue.value
        if (ceiling <= 0) return true
        if (id.isEmpty()) return highlightUnpriced.value
        val price = NetworkLoop.getPrice(id.removePrefix("STARRED_"))?.takeIf { it > 0 } ?: return highlightUnpriced.value
        return price < ceiling
    }
}
