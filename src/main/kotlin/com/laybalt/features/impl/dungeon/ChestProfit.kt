// Ported from Odin (https://github.com/odtheking/Odin), BSD-3-Clause, Copyright (c) 2025 odtheking
// (Odin parts: Include Key / hide-any-claimed logic, Kismet outline, chest counter HUD with limit warning, per-item overlay list)
package com.laybalt.features.impl.dungeon

import com.laybalt.LayBaltQOL
import com.laybalt.config.types.*
import com.laybalt.event.impl.*
import com.laybalt.features.Feature
import com.laybalt.init.DataDownloader
import com.laybalt.init.NetworkLoop
import com.laybalt.ui.notification.NotificationManager
import com.laybalt.utils.*
import com.laybalt.utils.ChatUtils.formattedText
import com.laybalt.utils.ChatUtils.removeFormatting
import com.laybalt.utils.ChatUtils.unformattedText
import com.laybalt.utils.ColorUtils.withAlpha
import com.laybalt.utils.MathUtils.vec
import com.laybalt.utils.NumbersUtils.romanToDecimal
import com.laybalt.utils.items.ItemRarity
import com.laybalt.utils.items.ItemUtils
import com.laybalt.utils.items.ItemUtils.lore
import com.laybalt.utils.items.ItemUtils.skyblockId
import com.laybalt.utils.location.LocationUtils
import com.laybalt.utils.location.WorldType
import com.laybalt.utils.render.Render2D.drawBorder
import com.laybalt.utils.render.Render2D.drawString
import com.laybalt.utils.render.Render2D.highlight
import com.laybalt.utils.render.RenderHelper.width
import gg.essential.universal.UChat
import gg.essential.universal.USound
import kotlinx.coroutines.launch
import net.minecraft.client.gui.screens.inventory.ContainerScreen
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.ItemLore
import java.awt.Color
import java.util.*
import kotlin.math.abs

object ChestProfit: Feature("Dungeon Chest Profit Calculator") {
    private val hud by ToggleSetting("HUD Display", true).section("General")
    private val rerollValue by SliderSetting("Reroll Value", 5, 0, 100, 1, "M").withDescription("Prevents you from rerolling a chest if its profit is &b&lbigger&r then the &avalue in milions&r.\n\n&eoverride by pressing CTRL&r.\nset to 0 to disable")
    private val includeEssence by ToggleSetting("Includes Essence", true)
    private val neutralThreshold by SliderSetting("Neutral Threshold", 1000, 0, 1_000_000, 1000, " coins")
        .withDescription("Profits whose absolute value is below this amount are shown in &8dark gray&r instead of green/red, since they are not worth caring about")
    private val showItems by ToggleSetting("Show Chest Items", false)
        .withDescription("List every item of a chest with its price under the chest's profit in the overlay drawn next to the chest and Croesus menus. When off only the profit per chest is shown")
    private val chestValueTooltip by ToggleSetting("Chest Value Tooltip", true)
        .withDescription("Adds a &7Chest value&r line to the tooltip of each chest in the Croesus run preview. A &9blue&r note marks values where some item had no price data")
    private val refreshPrices by ButtonSetting("Refresh Prices") {
        LayBaltQOL.scope.launch {
            NetworkLoop.refreshPrices()
            ChatUtils.modMessage("&aChest profit prices refreshed.")
        }
    }.withDescription("Re-downloads the bazaar and lowest-BIN prices used for the profit calculations right now instead of waiting for the next automatic refresh")

    private val croesusChestsProfit by ToggleSetting("Croesus Chests Profit", true).section("Croesus")
    private val bestChestColor by ColorSetting("Best Chest Color", Color.GREEN)
        .withDescription("Highlight colour of the most profitable chest in the Croesus run preview")
        .showIf { croesusChestsProfit.value }
    private val secondChestColor by ColorSetting("Second Chest Color", Color.GREEN.darker().darker().darker())
        .withDescription("Highlight colour of the second most profitable chest in the Croesus run preview")
        .showIf { croesusChestsProfit.value }
    private val secondChestKeyCheck by ToggleSetting("Second Chest Key Check", true)
        .withDescription("Only highlight the second best chest when its profit is more than twice the price of a Dungeon Chest Key, because opening a second chest costs a key")
        .showIf { croesusChestsProfit.value }
    private val croesusChestHighlight by ToggleSetting("Highlight Croesus Chests", true)
    private val highlightStyle by DropdownSetting("Highlight Style", 0, listOf("LayBalt", "Skyblocker"))
        .withDescription("&bLayBalt&r: green = nothing opened, yellow = a chest opened, red = no chests left. &bSkyblocker&r: gray = a chest opened, red = no chests left, unopened runs stay unmarked")
        .showIf { croesusChestHighlight.value }
    private val includeVesuvius by ToggleSetting("Include Vesuvius", true)
        .withDescription("Also apply the run highlighting and hiding to the Kuudra runs listed by Vesuvius")
    private val hideRedChests by ToggleSetting("Hide Opened Chests", true)
    private val hideMode by DropdownSetting("Hide Which Runs", 0, listOf("Fully Claimed", "Any Opened"))
        .withDescription("&bFully Claimed&r: hide only runs with no chests left to open. &bAny Opened&r: also hide runs where you already opened a chest")
        .showIf { hideRedChests.value }
    private val requireKeyUsed by ToggleSetting("Only When Key Used", false)
        .withDescription("Only hide a dungeon run when its Dungeon Chest Key is already used (struck through in the lore), so runs where you can still buy a second chest stay visible")
        .showIf { hideRedChests.value }
    private val croesusKismetDisplay by ToggleSetting("Mark Rerolled Chests", true).jsonName("Highlight Rerolled Chests")
    private val kismetOutline by ToggleSetting("Rerolled Outline", false)
        .withDescription("Draws an aqua outline around runs whose Kismet Feather was already used (struck through in the lore)")

    private val chestCountHud by ToggleSetting("Chest Count HUD", false).section("Chest Counter")
        .withDescription("Shows how many unclaimed dungeon chests you have. Read from the tab list (Unclaimed chests) and counted up after every run")
    private val chestWarning by SliderSetting("Chest Warning Threshold", 55, 0, 60, 1)
        .withDescription("Shows a warning when your unclaimed chest count goes above this number (Croesus holds at most 60 runs). Set to 0 to disable")
        .showIf { chestCountHud.value }

    private val blackList by lazy { DataDownloader.loadJson<List<String>>("blacklistDrops.json") }

    private val essenceRegex = Regex("§d(?<type>\\w+) Essence §8x(?<count>\\d+)")
    private val croesusChestRegex = Regex("^(Master )?Catacombs - Flo(or (IV|V?I{0,3}))?$")
    private val croesusMenuRegex = Regex("""^(?:\(\d+/\d+\) )?(?:Croesus|Vesuvius)$""")
    private val unclaimedChestsRegex = Regex("^Unclaimed chests: (\\d+)$")

    private const val NO_MORE_CHESTS = "No more chests to open!"
    private const val NO_CHESTS_OPENED = "No chests opened yet!"
    private const val OPENED_CHEST = "Opened Chest: "
    private const val EXTRA_STATS = "> EXTRA STATS <"

    private val chestsToHighlight = mutableListOf<DungeonChest>()
    private var sortedChestsCache = emptyList<DungeonChest>()

    private val feather by lazy { ItemStack(Items.FEATHER) }
    private val npcLoc = vec(- 28, 119, 35)

    private var unclaimedChests = - 1
    private var tabTicks = 0

    private val isDebugEnabled get() = LayBaltQOL.debugFlags.contains("chest")

    override fun init() {
        register<WorldChangeEvent> {
            DungeonChest.entries.forEach { it.reset() }
            chestsToHighlight.clear()
            sortedChestsCache = emptyList()
        }

        hudElement(
            name = "Chest Profit",
            enabled = { hud.value },
            shouldDraw = {
                val world = LocationUtils.world
                val distance = player.distanceToSqr(npcLoc) <= 150
                world == WorldType.Catacombs || (world == WorldType.DungeonHub && distance)
            }
        ) { ctx, example ->
            val text = if (example) DungeonChest.example
            else {
                val chests = sortedChestsCache.takeIf { it.isNotEmpty() } ?: DungeonChest.entries.filter { it.openedInSequence }.sortedByDescending { it.profit }
                if (chests.isEmpty()) return@hudElement 0f to 0f

                chests.map { chest -> chest to "${chest.displayText}: ${profitText(chest.profit, chest.incomplete)}" }
            }

            var maxWidth = 0
            text.forEachIndexed { i, (chest, str) ->
                ctx.drawString(str, 0f, i * 9f, chest.color)
                maxWidth = maxOf(maxWidth, str.width())
            }

            maxWidth to text.size * 9f
        }

        hudElement(
            name = "Croesus Chest Count",
            enabled = { chestCountHud.value },
            shouldDraw = { LocationUtils.world.equalsOneOf(WorldType.Catacombs, WorldType.DungeonHub) }
        ) { ctx, example ->
            val count = if (example) 42 else unclaimedChests.takeIf { it >= 0 } ?: return@hudElement 0f to 0f
            val warn = chestWarning.value in 1 ..< count
            val text = "§6Chests: ${if (warn) "§c" else "§a"}$count"
            ctx.drawString(text, 0f, 0f)
            text.width() to 9f
        }

        register<TickEvent.End> {
            if (! chestCountHud.value || ! LocationUtils.inSkyblock) return@register
            if (++ tabTicks < 20) return@register
            tabTicks = 0
            for ((component, _) in TabListUtils.getTabList()) {
                val count = unclaimedChestsRegex.find(component.unformattedText.trim())?.groupValues?.get(1)?.toIntOrNull() ?: continue
                if (count != unclaimedChests) updateChestCount(count)
                break
            }
        }

        register<ChatMessageEvent> {
            if (! chestCountHud.value || ! LocationUtils.inBoss) return@register
            if (event.unformattedText.trim() != EXTRA_STATS) return@register
            if (unclaimedChests >= 0) updateChestCount(unclaimedChests + 1)
        }

        register<ContainerFullyOpenedEvent> {
            if (! LocationUtils.world.equalsOneOf(WorldType.DungeonHub, WorldType.Catacombs)) return@register
            val chestName = event.title.unformattedText
            val currentChest = DungeonChest.getFromName(chestName)

            chestsToHighlight.clear()
            sortedChestsCache = emptyList()

            when {
                currentChest != null -> {
                    val rewardItem = event.items[31] ?: return@register

                    val lore = rewardItem.lore.map { it.removeFormatting() }
                    if ("Cost" !in lore) return@register

                    val chestCost = getChestCost(lore)
                    var profit = - chestCost
                    var incomplete = false
                    val items = mutableListOf<ChestItem>()

                    event.items.forEach { (_, stack) ->
                        if (stack.item == Items.STAINED_GLASS_PANE.black()) return@forEach
                        val item = getItemValue(stack) ?: return@forEach
                        profit += item.value
                        if (! item.priced) incomplete = true
                        items += item
                    }

                    currentChest.profit = profit
                    currentChest.incomplete = incomplete
                    currentChest.items = items
                    currentChest.openedInSequence = true

                    if (isDebugEnabled) printDebug("§6§l${currentChest.displayText} §7opened", chestCost, items, profit)
                }

                croesusChestsProfit.value && chestName.matches(croesusChestRegex) -> {
                    for (i in 10 .. 16) {
                        val stack = event.items[i] ?: continue
                        if (stack.item == Items.STAINED_GLASS_PANE.gray()) continue
                        val chestType = DungeonChest.getFromName(stack.hoverName.unformattedText) ?: continue
                        val lore = stack.lore
                        if (lore.lastOrNull() == "§aAlready opened!") continue

                        val contentIndex = lore.indexOfFirst { it.contains("Contents") }.takeUnless { it == - 1 } ?: continue

                        chestType.slot = i
                        val chestCost = getChestCost(lore.map { it.removeFormatting() })
                        var profit = - chestCost
                        var incomplete = false
                        val items = mutableListOf<ChestItem>()

                        lore.drop(contentIndex + 1).takeWhile { it.isNotBlank() }.forEach { line ->
                            val item = getPreviewLineValue(line)
                            profit += item.value
                            if (! item.priced) incomplete = true
                            items += item
                        }

                        chestType.profit = profit
                        chestType.incomplete = incomplete
                        chestType.items = items
                        chestType.openedInSequence = true

                        if (chestsToHighlight.none { it == chestType }) chestsToHighlight.add(chestType)

                        if (isDebugEnabled) printDebug("§6§l${chestType.displayText} §7(Croesus)", chestCost, items, profit)
                    }

                    sortedChestsCache = chestsToHighlight.sortedByDescending { it.profit }
                }
            }
        }

        register<ContainerEvent.Render.Tooltip> {
            if (! chestValueTooltip.value || ! croesusChestsProfit.value) return@register
            if (event.screen !is ContainerScreen) return@register
            if (! LocationUtils.world.equalsOneOf(WorldType.DungeonHub, WorldType.Catacombs)) return@register
            if (! croesusChestRegex.matches(event.screen.title.unformattedText)) return@register
            val chest = DungeonChest.getFromName(event.stack.hoverName.unformattedText) ?: return@register
            if (chest !in chestsToHighlight) return@register

            val incomplete = if (chest.incomplete) " §9(incomplete price data)" else ""
            event.lore.add(Component.literal("§7Chest value: ${profitText(chest.profit, false)}$incomplete"))
        }

        register<ContainerEvent.Render.Slot.Pre> {
            if (event.screen !is ContainerScreen) return@register
            val titleName = event.screen.title.unformattedText

            if (titleName.matches(croesusMenuRegex)) {
                handleCroesusMenuSlot(titleName.endsWith("Vesuvius"))
                return@register
            }

            if (! LocationUtils.world.equalsOneOf(WorldType.DungeonHub, WorldType.Catacombs)) return@register
            val isPreview = croesusChestsProfit.value && croesusChestRegex.matches(titleName)

            if (event.slot.index == 0) {
                val width = 176f
                val height = 166f

                DungeonChest.getFromName(titleName)?.let { chest ->
                    val text = "Profit: ${profitText(chest.profit, chest.incomplete)}  "
                    event.context.drawString(text, width - text.width(), 6f)
                    if (showItems.value) drawItemList(chest.items, width * 1.05f, height / 6f)
                } ?: run {
                    if (! isPreview) return@run
                    var y = height / 6f
                    sortedChestsCache.forEach { chest ->
                        val text = "${chest.displayText}: ${profitText(chest.profit, chest.incomplete)}§r"
                        event.context.drawString(text, width * 1.15f, y, chest.color)
                        y += 9f
                        if (showItems.value) y = drawItemList(chest.items, width * 1.15f + 6f, y) + 2f
                    }
                }
            }

            if (isPreview) {
                val keyThreshold = if (secondChestKeyCheck.value) 2 * (NetworkLoop.getPrice("DUNGEON_CHEST_KEY") ?: 0L) else 0L
                sortedChestsCache.take(2).forEachIndexed { index, chest ->
                    if (chest.slot != event.slot.index) return@forEachIndexed
                    if (chest.profit <= 0) return@forEachIndexed
                    if (index == 1 && chest.profit <= keyThreshold) return@forEachIndexed
                    event.slot.highlight(event.context, if (index == 0) bestChestColor.value else secondChestColor.value)
                }
            }
        }

        register<ContainerEvent.SlotClick> {
            if (rerollValue.value == 0) return@register
            if (event.slotId != 50) return@register
            if (event.screen !is ContainerScreen) return@register
            if (mc.hasControlDown()) return@register
            if (! LocationUtils.world.equalsOneOf(WorldType.DungeonHub, WorldType.Catacombs)) return@register
            val chest = DungeonChest.getFromName(event.screen.title.unformattedText) ?: return@register
            if (chest.profit <= rerollValue.value * 1_000_000L) return@register
            val lastLine = player.containerMenu.getSlot(50).item.lore.lastOrNull()?.removeFormatting() ?: return@register
            if (lastLine == "You already rerolled a chest!") return@register
            if (lastLine != "Click to reroll this chest!") return@register
            event.isCanceled = true
            NotificationManager.push("Blocked Rerolling Chest", "Its profit dumbass.\nPress CTRL to override")
        }
    }

    /** Croesus / Vesuvius run list: status highlight, hiding claimed runs and the Kismet markers. */
    private fun com.laybalt.event.EventContext<ContainerEvent.Render.Slot.Pre>.handleCroesusMenuSlot(vesuvius: Boolean) {
        if (! LocationUtils.inSkyblock) return
        if (vesuvius && ! includeVesuvius.value) return
        if (! vesuvius && ! LocationUtils.world.equalsOneOf(WorldType.DungeonHub, WorldType.Catacombs)) return

        val stack = event.slot.item
        if (stack.isEmpty) return
        if (! vesuvius) {
            if (stack.item != Items.PLAYER_HEAD) return
            if (! stack.hoverName.unformattedText.equalsOneOf("The Catacombs", "Master Mode The Catacombs")) return
        }

        val lore = stack.lore
        val cleanLore = lore.map { it.removeFormatting() }
        val fullyClaimed = cleanLore.any { it == NO_MORE_CHESTS }
        val opened = ! fullyClaimed && cleanLore.any { it.startsWith(OPENED_CHEST) }
        val nothingOpened = ! fullyClaimed && ! opened && cleanLore.any { it == NO_CHESTS_OPENED }
        // Vesuvius run entries are recognised by their chest-status lore alone (their item names are not verified)
        if (vesuvius && ! fullyClaimed && ! opened && ! nothingOpened) return

        if (hideRedChests.value) {
            val candidate = if (hideMode.value == 0) fullyClaimed else fullyClaimed || opened
            val keyOk = vesuvius || ! requireKeyUsed.value || hasStrikethrough(stack, "Dungeon Chest Key")
            if (candidate && keyOk) {
                event.isCanceled = true
                return
            }
        }

        if (croesusChestHighlight.value) {
            val color = if (highlightStyle.value == 1) when {
                fullyClaimed -> Color(255, 0, 0, 64)
                opened -> Color(64, 64, 64, 128)
                else -> null
            }
            else when {
                fullyClaimed -> Color.RED.withAlpha(100)
                opened -> Color.YELLOW.withAlpha(100)
                nothingOpened -> Color.GREEN.withAlpha(100)
                else -> null
            }
            color?.let { event.slot.highlight(event.context, it) }
        }

        val kismetUsed = hasStrikethrough(stack, "Kismet Feather")

        if (kismetOutline.value && kismetUsed) {
            event.context.drawBorder(event.slot.x, event.slot.y, 16, 16, Color(85, 255, 255))
        }

        if (! vesuvius && croesusKismetDisplay.value && lore.getOrNull(lore.lastIndex - 3)?.let { it != "§5 §9Kismet Feather" } == true) {
            val pose = event.context.pose()
            pose.pushMatrix()
            pose.scale(0.7f)
            pose.translate((event.slot.x + 7) / 0.7f, (event.slot.y + 7) / 0.7f)
            event.context.item(feather, 0, 0)
            pose.popMatrix()
        }
    }

    private fun com.laybalt.event.EventContext<ContainerEvent.Render.Slot.Pre>.drawItemList(items: List<ChestItem>, x: Float, startY: Float): Float {
        var y = startY
        items.forEach { item ->
            val price = if (item.priced) "§a${NumbersUtils.format(item.value)}" else "§9?"
            event.context.drawString("§7${item.name}§7: $price", x, y)
            y += 9f
        }
        return y
    }

    private fun updateChestCount(count: Int) {
        val previous = unclaimedChests
        unclaimedChests = count
        val limit = chestWarning.value
        if (limit > 0 && count > limit && count > previous) {
            ChatUtils.showTitle("§cChest limit reached!", "§7$count unclaimed chests")
            USound.playSoundStatic(SoundEvents.NOTE_BLOCK_PLING, 0.5f, 1f)
        }
    }

    private fun hasStrikethrough(stack: ItemStack, text: String): Boolean =
        stack.getOrDefault(DataComponents.LORE, ItemLore.EMPTY).lines().any { line ->
            var found = false
            line.visit({ style, string ->
                if (style.isStrikethrough && string.contains(text)) found = true
                Optional.empty<Unit>()
            }, Style.EMPTY)
            found
        }

    private fun profitText(profit: Long, incomplete: Boolean): String {
        val color = when {
            abs(profit) < neutralThreshold.value -> "§8"
            profit < 0 -> "§4"
            else -> "§a"
        }
        return "$color${NumbersUtils.format(profit)}${if (incomplete) "§9*" else ""}"
    }

    private fun printDebug(header: String, chestCost: Long, items: List<ChestItem>, profit: Long) {
        UChat.chat("")
        ChatUtils.debug("chest", header)
        ChatUtils.debug("chest", "§7Cost: §c-${NumbersUtils.format(chestCost)}")
        ChatUtils.debug("chest", "§7--- Items ---")
        items.forEach { item ->
            val priceColor = if (item.value > 0) "§a" else "§7"
            ChatUtils.debug("chest", "§f${item.name.removeFormatting()} §7→ §e${item.id} §7= $priceColor${NumbersUtils.format(item.value)}")
        }
        val profitColor = if (profit < 0) "§4" else "§a"
        ChatUtils.debug("chest", "§7--- Total ---")
        ChatUtils.debug("chest", "§7Profit: $profitColor${NumbersUtils.format(profit)}")
        UChat.chat("")
    }

    /** Value of an item inside an opened dungeon reward chest; null when the slot holds nothing worth listing. */
    private fun getItemValue(stack: ItemStack): ChestItem? {
        val itemName = stack.hoverName.formattedText
        val essence = getEssenceValue(itemName)
        if (essence != null) return essence

        val itemId = stack.skyblockId.removePrefix("STARRED_")
        if (itemId.isEmpty()) return null
        val price = priceOf(itemId)
        return ChestItem(itemName, itemId, price ?: 0L, price != null)
    }

    /** Value of one "Contents" lore line of a chest in the Croesus run preview. */
    private fun getPreviewLineValue(line: String): ChestItem {
        getEssenceValue(line)?.let { return it }
        val id = getIdFromName(line)
        val price = id?.let { priceOf(it) }
        return ChestItem(line, id ?: "???", price ?: 0L, price != null)
    }

    private fun getChestCost(cleanLore: List<String>): Long {
        return cleanLore.firstNotNullOfOrNull { line ->
            if (line.contains("FREE")) 0L
            else if (line.contains(" Coins")) line.substringBefore(" ").replace(",", "").toLongOrNull()
            else null
        } ?: 0L
    }

    /** Null when [text] is not an essence entry; a zero-value entry when essence is excluded from the calculation. */
    private fun getEssenceValue(text: String): ChestItem? {
        val match = essenceRegex.find(text) ?: return null
        val type = match.groups["type"]?.value?.uppercase() ?: return null
        val count = match.groups["count"]?.value?.toLongOrNull() ?: 0L
        val id = "ESSENCE_$type"
        if (! includeEssence.value) return ChestItem(text, id, 0L, true)
        val price = NetworkLoop.getPrice(id)
        return ChestItem(text, id, (price ?: 0L) * count, price != null)
    }

    private fun getIdFromName(name: String): String? {
        val cleanName = name.removeFormatting()
        if (cleanName.startsWith("Enchanted Book (")) return enchantNameToID(name.substringAfter("(").substringBefore(")"))
        if (cleanName.startsWith("[Lvl 1] ")) {
            val nameSection = name.substringAfter("] ")
            val rarity = ItemRarity.entries.find { it.baseColor.char == nameSection.getOrNull(1) }
            val petName = nameSection.removeFormatting().uppercase()
            return "PET-$petName-$rarity"
        }

        return NetworkLoop.nameToIdMap[cleanName.remove("Shiny ")]?.removePrefix("STARRED_") ?: ItemUtils.getShardIdFromName(cleanName).takeIf { NetworkLoop.getBazaarPrice(it) != null }
    }

    private fun enchantNameToID(enchant: String): String {
        val enchantName = enchant.substringBeforeLast(" ")
        val name = enchantName.removeFormatting().uppercase().replace(" ", "_")

        val isUltimate = enchantName.startsWithOneOf("§9§d§l", "§d§l", "§7§l")
        val enchantId = if (isUltimate && ! name.contains("ULTIMATE_")) "ULTIMATE_$name" else name

        val levelStr = enchant.substringAfterLast(" ").removeFormatting()
        val level = levelStr.toIntOrNull() ?: levelStr.romanToDecimal()
        return "ENCHANTMENT_${enchantId}_$level"
    }

    /** 0 for blacklisted (worthless) drops, null when no price is known. */
    private fun priceOf(id: String): Long? = if (id in blackList) 0L else NetworkLoop.getPrice(id)

    data class ChestItem(val name: String, val id: String, val value: Long, val priced: Boolean)

    enum class DungeonChest(val displayText: String, val color: Color) {
        WOOD("Wood Chest", Color(100, 64, 1)),
        GOLD("Gold Chest", Color.YELLOW),
        DIAMOND("Diamond Chest", Color.CYAN),
        EMERALD("Emerald Chest", Color(0, 128, 0)),
        OBSIDIAN("Obsidian Chest", Color(128, 0, 128)),
        BEDROCK("Bedrock Chest", Color.DARK_GRAY);

        var slot = 0
        var profit = 0L
        var incomplete = false
        var items = emptyList<ChestItem>()
        var openedInSequence: Boolean = false

        fun reset() {
            openedInSequence = false
            profit = 0
            slot = 0
            incomplete = false
            items = emptyList()
        }

        companion object {
            fun getFromName(name: String?): DungeonChest? {
                if (name.isNullOrBlank()) return null
                return entries.find {
                    it.displayText.remove(" Chest") == name.remove(" Chest")
                }
            }

            val example = listOf(
                WOOD to "Wood Chest: §a75k",
                GOLD to "Gold Chest: §4-62k",
                DIAMOND to "Diamond Chest: §a24k",
                EMERALD to "Emerald Chest: §4-442k",
                OBSIDIAN to "Obsidian Chest: §4-624k",
                BEDROCK to "Bedrock Chest: §a5m"
            )
        }
    }
}
