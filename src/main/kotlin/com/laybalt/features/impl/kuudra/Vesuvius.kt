// Ported from Odin (https://github.com/odtheking/Odin), BSD-3-Clause, Copyright (c) 2025 odtheking
package com.laybalt.features.impl.kuudra

import com.laybalt.config.types.SliderSetting
import com.laybalt.config.types.ToggleSetting
import com.laybalt.event.impl.ContainerEvent
import com.laybalt.event.impl.MainThreadPacketReceivedEvent
import com.laybalt.event.impl.ScreenEvent
import com.laybalt.features.Feature
import com.laybalt.ui.utils.Resolution
import com.laybalt.utils.ChatUtils.formattedText
import com.laybalt.utils.ChatUtils.removeFormatting
import com.laybalt.utils.containsOneOf
import com.laybalt.utils.items.ItemUtils.lore
import com.laybalt.utils.kuudra.KuudraPrices
import com.laybalt.utils.render.Render2D.drawString
import com.laybalt.utils.render.RenderHelper.width
import gg.essential.universal.UMinecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.core.component.DataComponents
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.ItemLore
import java.awt.Color
import java.util.*

object Vesuvius: Feature("Shows the value of Kuudra reward chests and hides already claimed runs in the Vesuvius menu.") {
    private val hideClaimed by ToggleSetting("Hide Claimed", true)
        .withDescription("Hides runs whose chests have all been claimed (\"No more chests to open!\") in the Vesuvius / Croesus menu.")
    private val useSalvagePrices by ToggleSetting("Use Salvaged", false)
        .withDescription("Values Kuudra armour, Aurora Staff, Hollow Wand and Molten equipment by the Crimson Essence you get from salvaging them instead of their market price.").section("Pricing")
    private val kuudraPetBonus by SliderSetting("Kuudra Pet Bonus", 0.0, 0.0, 20.0, 0.05, "%")
        .withDescription("Extra essence from your Kuudra pet, applied to essence rewards.")
    private val lavaLeechBonus by SliderSetting("Lava Leech Bonus", 0.0, 0.0, 13.0, 0.05, "%")
        .withDescription("Extra essence from the Lava Leech shard, applied to essence rewards.")

    /** Essence multiplier from the Kuudra pet and the Lava Leech shard. */
    val essenceBonus get() = (1 + kuudraPetBonus.value / 100.0) * (1 + lavaLeechBonus.value / 100.0)
    val useSalvage get() = useSalvagePrices.value

    private val chestRegex = Regex("^((Free|Paid) Chest)|(Kuudra - .+)$")
    private val uselessLinesRegex = Regex("^Contents|Cost|Click to open!|FREE|Already opened!|Can't open another chest!|Paid Chest|[\\d,]+ Coins|$")

    private data class ChestItem(val name: String, val price: Double)
    private data class ChestData(val items: List<ChestItem>, val cost: Double, val profit: Double)

    private var currentChest: ChestData? = null

    private val sampleChestData = ChestData(
        items = listOf(
            ChestItem("§6Fervor Helmet", 748000.0),
            ChestItem("§fEnchanted Book (§dFerocious Mana V§f)", 118500000.0),
            ChestItem("§dCrimson Essence §8x2000", 2420000.0),
            ChestItem("§5Kuudra Teeth §8x4", 35480.0),
            ChestItem("§6Kraken Shard §8x2", 821226.0)
        ),
        cost = 3247000.0,
        profit = 118542706.0
    )

    private val hud = hudElement("Croesus Chest HUD", shouldDraw = { false }) { ctx, example ->
        val data = (if (example) sampleChestData else currentChest) ?: return@hudElement 0 to 0
        ctx.drawOverlay(data)
    }

    override fun init() {
        register<ScreenEvent.PostRender> {
            if (currentChest == null || ! event.screen.title.string.removeFormatting().matches(chestRegex)) return@register
            Resolution.push(event.context)
            hud.renderElement(event.context, false)
            Resolution.pop(event.context)
        }

        register<ContainerEvent.Render.Slot.Pre> {
            if (! hideClaimed.value || ! event.screen.title.string.containsOneOf("Vesuvius", "Croesus")) return@register
            val item = event.slot.item
            if (item.hoverName.string.removeFormatting() != "Kuudra's Hollow") return@register
            if (item.lore.any { it.removeFormatting() == "No more chests to open!" }) event.isCanceled = true
        }

        register<MainThreadPacketReceivedEvent.Post> {
            if (event.packet !is ClientboundContainerSetSlotPacket && event.packet !is ClientboundContainerSetContentPacket) return@register
            val screen = UMinecraft.currentScreenObj as? Screen ?: return@register
            if (! screen.title.string.removeFormatting().matches(chestRegex)) return@register
            val menu = mc.player?.containerMenu ?: return@register

            val reward = menu.slots.getOrNull(31)?.item?.takeIf { it.`is`(Items.CHEST) }
                ?: listOf(13, 14).firstNotNullOfOrNull { slot -> menu.slots.getOrNull(slot)?.item?.takeIf { it.`is`(Items.PLAYER_HEAD) } }
                ?: return@register
            handleKuudraChest(reward)
        }

        register<ContainerEvent.Close> { currentChest = null }
    }

    private fun handleKuudraChest(item: ItemStack) {
        val chestItems = mutableListOf<ChestItem>()
        var profit = 0.0
        var chestCost = 0.0

        item.getOrDefault(DataComponents.LORE, ItemLore.EMPTY).styledLines().forEach { component ->
            val string = component.string.removeFormatting()

            if (string.contains("Kuudra Key")) {
                chestCost = KuudraPrices.getPriceOfKey(string.trim())
                return@forEach
            }
            if (string.trim().matches(uselessLinesRegex)) return@forEach

            val price = KuudraPrices.parseItemValue(component, essenceBonus, useSalvage) ?: 0.0
            profit += price
            chestItems.add(ChestItem(component.formattedText, price))
        }
        currentChest = ChestData(chestItems, chestCost, profit - chestCost)
    }

    private fun format(value: Double) = String.format(Locale.US, "%,.0f", value)

    private fun GuiGraphicsExtractor.drawOverlay(data: ChestData): Pair<Int, Int> {
        var y = 0
        val maxWidth = 251

        data.items.forEach { item ->
            val price = format(item.price)
            drawString(item.name, 0, y)
            drawString(price, maxWidth - price.width(), y, Color(0xAAAAAA))
            y += 9
        }

        y += 6
        val cost = format(data.cost)
        drawString("§cKey Cost:", 0, y)
        drawString(cost, maxWidth - cost.width(), y, Color(0xFF5555))
        y += 12
        val profit = format(data.profit)
        drawString("§aProfit:", 0, y)
        drawString(profit, maxWidth - profit.width(), y, Color(0x55FF55))
        y += 9

        return maxWidth to y
    }
}
