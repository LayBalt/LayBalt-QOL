package com.laybalt.features.impl.general

import com.laybalt.config.types.DropdownSetting
import com.laybalt.config.types.ToggleSetting
import com.laybalt.event.impl.ContainerEvent
import com.laybalt.event.impl.ScreenEvent
import com.laybalt.features.Feature
import com.laybalt.init.NetworkLoop
import com.laybalt.mixin.IAbstractContainerScreen
import com.laybalt.ui.Palette
import com.laybalt.ui.utils.Resolution
import com.laybalt.utils.ChatUtils.unformattedText
import com.laybalt.utils.items.ItemUtils
import com.laybalt.utils.items.ItemUtils.skyblockId
import com.laybalt.utils.location.LocationUtils
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.world.item.ItemStack
import java.awt.Color
import com.laybalt.utils.items.ItemRarity as Rarity

/**
 * A small movable card that shows what the item under the mouse is worth while a container is open.
 * Its position is set in the HUD editor like any other HUD element.
 */
object ItemValueHud: Feature("Shows a compact price card for the item under your mouse in inventories.", name = "Item Value HUD") {
    private val priceSource by DropdownSetting("Bazaar Price", 0, listOf("Instant Sell", "Instant Buy")).withDescription("Which Bazaar price is used: Instant Sell is what you get selling right now, Instant Buy is what it costs to buy right now")
    private val showAllPrices by ToggleSetting("Show All Prices").withDescription("Lists every known price (Bazaar buy and sell, lowest BIN, NPC) instead of only the best one")
    private val showStackValue by ToggleSetting("Show Stack Value", true).withDescription("Adds a row with the value of the whole stack when it holds more than one item")
    private val showNpcPrice by ToggleSetting("Show NPC Price").withDescription("Adds the NPC sell price when the item has one").showIf { ! showAllPrices.value }
    private val compactNumbers by ToggleSetting("Compact Numbers", true).withDescription("Shows prices as 1.2M instead of 1,200,000")
    private val rarityAccent by ToggleSetting("Rarity Accent", true).withDescription("Colours the side strip of the card with the item's rarity instead of the GUI accent colour")

    private const val MAX_NAME = 28

    private var cachedStack: ItemStack? = null
    private var cachedCount = 0
    private var cachedAt = 0L
    private var cachedCard: ValueCard? = null

    private val sampleCard by lazy {
        ValueCard("Hyperion", Rarity.LEGENDARY.color, listOf(
            ValueCard.Row("Lowest BIN", "1.04B"),
            ValueCard.Row("NPC Sell", "100K")
        ))
    }

    private val hud = hudElement("Item Value HUD", shouldDraw = { false }) { ctx, example ->
        val card = if (example) sampleCard else cachedCard ?: return@hudElement 0 to 0
        card.draw(ctx)
    } defaults {
        x = 10f
        y = Resolution.height / 2f - 40f
    }

    override fun init() {
        register<ScreenEvent.PostRender> {
            if (! LocationUtils.inSkyblock) return@register
            val screen = event.screen as? AbstractContainerScreen<*> ?: return@register
            val stack = (screen as IAbstractContainerScreen).hoveredSlot?.item?.takeUnless { it.isEmpty } ?: return@register
            updateCard(stack)
            if (cachedCard == null) return@register

            Resolution.push(event.context)
            hud.renderElement(event.context, false)
            Resolution.pop(event.context)
        }

        register<ContainerEvent.Close> { clearCache() }
    }

    override fun onDisable() {
        super.onDisable()
        clearCache()
    }

    private fun clearCache() {
        cachedStack = null
        cachedCard = null
    }

    /** Rebuilds the card when the hovered stack changes, and once a second so new prices show up. */
    private fun updateCard(stack: ItemStack) {
        val now = System.currentTimeMillis()
        if (stack === cachedStack && stack.count == cachedCount && now - cachedAt < 1000L) return
        cachedStack = stack
        cachedCount = stack.count
        cachedAt = now
        cachedCard = buildCard(stack)
    }

    private fun buildCard(stack: ItemStack): ValueCard? {
        val id = stack.skyblockId.takeIf { it.isNotEmpty() } ?: return null
        val compact = compactNumbers.value
        val count = stack.count
        val rows = mutableListOf<ValueCard.Row>()

        if (showAllPrices.value) {
            NetworkLoop.getBazaarPrice(id)?.let { price ->
                if (price.sell > 0L) rows += ValueCard.Row("Bazaar Sell", ItemValues.coins(price.sell, compact))
                if (price.buy > 0L) rows += ValueCard.Row("Bazaar Buy", ItemValues.coins(price.buy, compact))
            }
            ItemValues.lowestBin(id)?.takeIf { it > 0L }?.let { rows += ValueCard.Row("Lowest BIN", ItemValues.coins(it, compact)) }
            NetworkLoop.getNpcSellPrice(id)?.takeIf { it > 0L }?.let { rows += ValueCard.Row("NPC Sell", ItemValues.coins(it, compact)) }
        }
        else {
            ItemValues.valueOf(id, count, priceSource.value == 1, npcFallback = false)?.let { rows += ValueCard.Row(it.source, ItemValues.coins(it.unitPrice, compact)) }
            if (showNpcPrice.value) NetworkLoop.getNpcSellPrice(id)?.takeIf { it > 0L }?.let { rows += ValueCard.Row("NPC Sell", ItemValues.coins(it, compact)) }
        }

        if (rows.isEmpty()) return null

        if (showStackValue.value && count > 1) {
            ItemValues.valueOf(id, count, priceSource.value == 1, npcFallback = true)?.let {
                rows += ValueCard.Row("Stack x$count", ItemValues.coins(it.total, compact), labelColor = Palette.textMuted)
            }
        }

        val name = stack.hoverName.unformattedText.let { if (it.length > MAX_NAME) it.take(MAX_NAME - 3) + "..." else it }
        return ValueCard(name, accentFor(stack), rows)
    }

    private fun accentFor(stack: ItemStack): Color {
        if (! rarityAccent.value) return Palette.accent
        val rarity = ItemUtils.getRarity(stack)
        return if (rarity == Rarity.NONE) Palette.accent else Rarity.getHypixelColor(rarity)
    }
}
