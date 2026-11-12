package com.laybalt.features.impl.general

import com.laybalt.config.types.ColorSetting
import com.laybalt.config.types.DropdownSetting
import com.laybalt.config.types.SliderSetting
import com.laybalt.config.types.ToggleSetting
import com.laybalt.event.impl.ContainerEvent
import com.laybalt.event.impl.ScreenEvent
import com.laybalt.features.Feature
import com.laybalt.features.impl.general.storageoverlay.StorageOverlay
import com.laybalt.mixin.IAbstractContainerScreen
import com.laybalt.ui.Palette
import com.laybalt.ui.utils.Resolution
import com.laybalt.utils.ChatUtils.removeFormatting
import com.laybalt.utils.ChatUtils.unformattedText
import com.laybalt.utils.items.ItemUtils.skyblockId
import com.laybalt.utils.location.LocationUtils
import com.laybalt.utils.render.Render2D.drawBorder
import com.laybalt.utils.render.Render2D.highlight
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.gui.screens.inventory.ContainerScreen
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.Slot
import java.awt.Color

/**
 * Adds up the lowest-BIN / Bazaar value of everything in the open container and shows it in a card
 * next to the container (or at a HUD position), with the most valuable items listed underneath.
 */
object ContainerValue: Feature("Shows the total Bazaar / lowest BIN value of the open chest, backpack or ender chest page.") {
    private val storageOnly by ToggleSetting("Storage Containers Only", true).withDescription("Only shows the value in chests, backpacks, ender chest pages, minions and the personal vault, not in menus such as the auction house")
    private val includePlayerInventory by ToggleSetting("Include Player Inventory").withDescription("Also counts the items in your own inventory below the container")
    private val priceSource by DropdownSetting("Bazaar Price", 0, listOf("Instant Sell", "Instant Buy")).withDescription("Which Bazaar price is used: Instant Sell is what you get selling right now, Instant Buy is what it costs to buy right now")
    private val npcFallback by ToggleSetting("NPC Price Fallback", true).withDescription("Uses the NPC sell price for items that cannot be sold on the Bazaar or auction house")
    private val topItems by SliderSetting("Top Items", 5, 0, 10, 1).withDescription("How many of the most valuable items are listed under the total")
    private val compactNumbers by ToggleSetting("Compact Numbers", true).withDescription("Shows prices as 1.2M instead of 1,200,000")
    private val attachToContainer by ToggleSetting("Attach to Container", true).withDescription("Draws the card right next to the container; turn off to place it with the HUD editor instead")

    private val highlightTop by ToggleSetting("Highlight Top Items").section("Highlight").withDescription("Outlines the slots that hold the listed most valuable items")
    private val highlightColor by ColorSetting("Highlight Color", Color(246, 196, 92, 200)).withDescription("The colour of the outline drawn around the most valuable items").showIf { highlightTop.value }

    private val storageTitles = listOf(
        Regex("^(Large |Small |Trapped )?Chest$"),
        Regex("^Ender Chest( \\(\\d+/\\d+\\))?$"),
        Regex("^.*Backpack( \\(Slot #\\d+\\))?$"),
        Regex("^.* Minion [IVXL]+$"),
        Regex("^Personal Vault$"),
        Regex("^Barrel$"),
        Regex("^Shulker Box$")
    )

    private data class Entry(val name: String, val count: Int, val value: Long, val slots: List<Slot>)
    private data class Result(val total: Long, val counted: Int, val entries: List<Entry>)

    private var result: Result? = null
    private var resultScreen: AbstractContainerScreen<*>? = null
    private var computedAt = 0L
    private var topSlots: Set<Slot> = emptySet()

    private val sampleCard by lazy {
        ValueCard("Container Value", Palette.accent, listOf(
            ValueCard.Row("Total (27 items)", "182.4M"),
            ValueCard.Row("Necron's Handle", "96.5M", labelColor = Palette.textPrimary),
            ValueCard.Row("Enchanted Diamond x64", "11.2M", labelColor = Palette.textPrimary),
            ValueCard.Row("Recombobulator 3000", "6.1M", labelColor = Palette.textPrimary)
        ))
    }

    private val hud = hudElement("Container Value", shouldDraw = { false }) { ctx, example ->
        val card = if (example) sampleCard else buildCard() ?: return@hudElement 0 to 0
        card.draw(ctx)
    } defaults {
        x = 10f
        y = 10f
    }

    override fun init() {
        register<ScreenEvent.PostRender> {
            if (! LocationUtils.inSkyblock) return@register
            val screen = event.screen as? AbstractContainerScreen<*> ?: return@register
            if (! isTracked(screen)) {
                clear()
                return@register
            }

            refresh(screen)
            val card = buildCard() ?: return@register

            if (! attachToContainer.value) {
                Resolution.push(event.context)
                hud.renderElement(event.context, false)
                Resolution.pop(event.context)
                return@register
            }

            val (width, _) = card.measure()
            val accessor = screen as IAbstractContainerScreen
            val rightEdge = screen.menu.slots.maxOfOrNull { it.x + 16 + 8 }?.coerceAtLeast(176) ?: 176
            var x = accessor.leftPos + rightEdge + 4f
            if (x + width > screen.width) x = accessor.leftPos - width - 4f
            if (x < 0f) return@register

            event.context.pose().pushMatrix()
            event.context.pose().translate(x, accessor.topPos.toFloat())
            card.draw(event.context)
            event.context.pose().popMatrix()
        }

        register<ContainerEvent.Render.Slot.Pre> {
            if (! highlightTop.value || event.slot !in topSlots) return@register
            event.context.drawBorder(event.slot.x, event.slot.y, 16, 16, highlightColor.value)
            event.slot.highlight(event.context, Color(highlightColor.value.red, highlightColor.value.green, highlightColor.value.blue, 40), 1)
        }

        register<ContainerEvent.Close> { clear() }
    }

    override fun onDisable() {
        super.onDisable()
        clear()
    }

    private fun clear() {
        result = null
        resultScreen = null
        topSlots = emptySet()
    }

    private fun isTracked(screen: AbstractContainerScreen<*>): Boolean {
        if (screen is ContainerScreen && StorageOverlay.activeFor(screen) != null) return false
        if (! storageOnly.value) return true
        val title = screen.title.string.removeFormatting().trim()
        return storageTitles.any { it.matches(title) }
    }

    /** Recomputes the value at most every 250 ms, and immediately when a different container opens. */
    private fun refresh(screen: AbstractContainerScreen<*>) {
        val now = System.currentTimeMillis()
        if (screen === resultScreen && now - computedAt < 250L) return
        resultScreen = screen
        computedAt = now

        val grouped = LinkedHashMap<String, MutableList<Slot>>()
        val values = HashMap<String, Long>()
        val counts = HashMap<String, Int>()
        val names = HashMap<String, String>()
        var total = 0L
        var counted = 0

        for (slot in screen.menu.slots) {
            if (! includePlayerInventory.value && slot.container is Inventory) continue
            val stack = slot.item
            if (stack.isEmpty) continue
            val id = stack.skyblockId.takeIf { it.isNotEmpty() } ?: continue
            val value = ItemValues.valueOf(id, stack.count, priceSource.value == 1, npcFallback.value) ?: continue

            total += value.total
            counted += stack.count
            grouped.getOrPut(id) { mutableListOf() }.add(slot)
            values[id] = (values[id] ?: 0L) + value.total
            counts[id] = (counts[id] ?: 0) + stack.count
            names.putIfAbsent(id, stack.hoverName.unformattedText)
        }

        val entries = grouped.map { (id, slots) -> Entry(names[id].orEmpty(), counts[id] ?: 0, values[id] ?: 0L, slots) }
            .sortedByDescending { it.value }

        result = Result(total, counted, entries)
        topSlots = entries.take(topItems.value).flatMap { it.slots }.toSet()
    }

    private fun buildCard(): ValueCard? {
        val result = result?.takeIf { it.total > 0L } ?: return null
        val compact = compactNumbers.value
        val rows = mutableListOf(ValueCard.Row("Total (${result.counted} items)", ItemValues.coins(result.total, compact)))

        for (entry in result.entries.take(topItems.value)) {
            val name = entry.name.let { if (it.length > 22) it.take(20) + "..." else it }
            val label = if (entry.count > 1) "$name x${entry.count}" else name
            rows += ValueCard.Row(label, ItemValues.coins(entry.value, compact), labelColor = Palette.textPrimary)
        }

        return ValueCard("Container Value", Palette.accent, rows)
    }
}
