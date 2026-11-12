package com.laybalt.features.impl.dungeon

import com.laybalt.config.types.ColorSetting
import com.laybalt.config.types.TextInputSetting
import com.laybalt.config.types.ToggleSetting
import com.laybalt.event.impl.ContainerEvent
import com.laybalt.features.Feature
import com.laybalt.utils.ChatUtils.unformattedText
import com.laybalt.utils.ColorUtils.withAlpha
import com.laybalt.utils.items.ItemUtils.customData
import com.laybalt.utils.location.LocationUtils
import com.laybalt.utils.render.Render2D.highlight
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.item.ItemStack
import java.awt.Color
import kotlin.jvm.optionals.getOrNull

/**
 * Marks dungeon junk in your inventory that can be sold to Ophelia (or through the Booster Cookie sell slot).
 * Behaviour inspired by Skyblocker's "Sellable Items Highlighter", re-implemented for LayBaltQOL.
 */
object SellableHighlighter: Feature(
    description = "Highlights dungeon junk in your inventory (Defuse Kits, Training Weights, Journal Entries, Revive Stones, Healing VIII splash potions) while the Ophelia or Booster Cookie menu is open, so you can sell it quickly.",
    name = "Sellable Items Highlighter",
    toggled = true
) {
    private val color by ColorSetting("Highlight Color", Color.YELLOW.withAlpha(128))
        .withDescription("Colour used to mark sellable items")
    private val healingPotions by ToggleSetting("Healing VIII Potions", true)
        .withDescription("Also mark Healing VIII Splash Potions picked up in dungeons")
    private val includeHotbar by ToggleSetting("Include Hotbar", false)
        .withDescription("Also mark items in your hotbar. Off by default so the items you are holding are not suggested for selling")
    private val extraIds by TextInputSetting("Extra Item IDs", "")
        .withDescription("Comma separated list of additional Skyblock item IDs to mark as sellable, for example ENCHANTED_ROTTEN_FLESH, CRYPT_DREADLORD_SWORD")

    private val sellMenus = setOf("Ophelia", "Booster Cookie")
    private val defaultIds = setOf("DEFUSE_KIT", "TRAINING_WEIGHTS", "DUNGEON_LORE_PAPER", "REVIVE_STONE")
    private const val HEALING_POTION_NAME = "Healing VIII Splash Potion"

    private var cachedExtraRaw: String? = null
    private var cachedExtraIds = emptySet<String>()

    override fun init() {
        register<ContainerEvent.Render.Slot.Pre> {
            if (! LocationUtils.inSkyblock) return@register
            if (event.screen.title.unformattedText !in sellMenus) return@register

            val slot = event.slot
            if (slot.container !is Inventory) return@register
            val invSlot = slot.containerSlot
            val inMainInventory = invSlot in 9 .. 35
            val inHotbar = invSlot in 0 .. 8
            if (! inMainInventory && ! (includeHotbar.value && inHotbar)) return@register

            val stack = slot.item.takeUnless { it.isEmpty } ?: return@register
            if (isSellable(stack)) slot.highlight(event.context, color.value, 2)
        }
    }

    private fun isSellable(stack: ItemStack): Boolean {
        val rawId = stack.customData.getString("id").getOrNull() ?: return false
        if (rawId in defaultIds || rawId in extraIdSet()) return true
        return healingPotions.value && rawId == "POTION" && stack.hoverName.unformattedText.contains(HEALING_POTION_NAME)
    }

    private fun extraIdSet(): Set<String> {
        val raw = extraIds.value
        if (raw != cachedExtraRaw) {
            cachedExtraRaw = raw
            cachedExtraIds = raw.split(',').map { it.trim().uppercase() }.filter { it.isNotEmpty() }.toSet()
        }
        return cachedExtraIds
    }
}
