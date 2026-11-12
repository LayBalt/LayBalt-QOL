package com.laybalt.features.impl.general

import com.laybalt.config.types.DropdownSetting
import com.laybalt.config.types.ToggleSetting
import com.laybalt.event.impl.ContainerEvent
import com.laybalt.features.Feature
import com.laybalt.ui.Palette
import com.laybalt.utils.ChatUtils.unformattedText
import com.laybalt.utils.ColorUtils.lerp
import com.laybalt.utils.NumbersUtils
import com.laybalt.utils.items.ItemUtils.customData
import com.laybalt.utils.items.ItemUtils.skyblockId
import com.laybalt.utils.location.LocationUtils
import com.laybalt.utils.remove
import com.laybalt.utils.render.Render2D.drawCenteredString
import com.laybalt.utils.render.Render2D.drawString
import com.laybalt.utils.render.RenderHelper.width
import com.laybalt.utils.uppercaseFirst
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.core.component.DataComponents
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.ItemLore
import java.awt.Color
import java.util.WeakHashMap
import kotlin.jvm.optionals.getOrDefault
import kotlin.jvm.optionals.getOrNull
import kotlin.math.roundToInt

object ItemOverlays: Feature("Display info about various items.") {
    private val cakeNumbers by ToggleSetting("Cake Numbers").withDescription("Displays the year of the cake in the New Year Cake Bag.")
    private val enchantedBookAbbreviation by ToggleSetting("Enchanted Book Abbreviation").withDescription("Shows the abbreviated name of books with only 1 enchantment")
    private val enchantedBookLevel by ToggleSetting("Enchanted Book Level").withDescription("Shows the tier of books with only 1 enchantment.")

    private val usageBars by ToggleSetting("Usage Bars").section("Usage Bars").withDescription("Draws a bar on SkyBlock items with their own fuel, durability, charges or uses: drills, the Pickonimbus, and any item whose lore has a line such as \"Fuel: 2,998/3k\"")
    private val barStyle by DropdownSetting("Bar Style", 0, listOf("Classic", "Slim", "Side")).withDescription("Classic is a short bar near the bottom like vanilla durability, Slim is a thin line along the whole bottom edge, Side is a vertical bar on the right edge").showIf { usageBars.value }
    private val barColor by DropdownSetting("Bar Color", 0, listOf("Red to Green", "Accent")).withDescription("Red to Green colours the bar by how full it is, Accent always uses the GUI accent colour").showIf { usageBars.value }
    private val hideWhenFull by ToggleSetting("Hide When Full", true).withDescription("Hides the bar while the item is completely full, like vanilla durability bars").showIf { usageBars.value }
    private val barsOnHotbar by ToggleSetting("Bars on Hotbar", true).withDescription("Also draws the usage bars on the items in your hotbar").showIf { usageBars.value }

    /** Lore lines of the form "<Label>: <current>/<max>"; the numbers may use commas and k/m/b suffixes. */
    private val usageLoreRegex = Regex("^(?:Fuel|Durability|Charges|Uses|Remaining Uses|Uses Remaining|Ammo|Energy): ([\\d,.]+[kKmMbB]?) ?/ ?([\\d,.]+[kKmMbB]?)(?:\\s.*)?$")
    private const val PICKONIMBUS_MAX = 5000

    private class CachedUsage(val lore: ItemLore?, val data: Any?, val ratio: Float?)
    private val usageCache = WeakHashMap<ItemStack, CachedUsage>()
    private val emptyColor = Color(200, 55, 70)
    private val fullColor = Color(90, 220, 120)
    private val trackColor = Color(10, 8, 16, 220).rgb

    override fun init() {
        register<ContainerEvent.Render.Slot.Post> {
            onSlotDraw(event.context, event.slot.item, event.slot.x, event.slot.y)
        }
    }

    fun onSlotDraw(context: GuiGraphicsExtractor, stack: ItemStack, x: Int, y: Int) {
        if (! enabled || ! LocationUtils.inSkyblock) return
        if (cakeNumbers.value && stack.`is`(Items.CAKE)) {
            val name = stack.hoverName.unformattedText
            if ("New Year Cake (Year " in name) {
                val year = name.remove("New Year Cake (Year ", ")").trim()
                context.drawCenteredString("&b$year", x + 8, y + 8, scale = 0.8)
            }
        }

        if ((enchantedBookAbbreviation.value || enchantedBookLevel.value) && stack.`is`(Items.ENCHANTED_BOOK) && stack.skyblockId.startsWith("ENCHANTMENT_")) {
            stack.customData.getCompoundOrEmpty("enchantments").takeIf { it.keySet().size == 1 }?.let { enchantments ->
                val name = enchantments.keySet().first()
                val level = enchantments.getInt(name).getOrDefault("").toString()
                var scale = 0.8

                val prefix = run {
                    val parts = name.split("_")
                    if (parts[0] == "ultimate") "§d§l" + parts.drop(1).joinToString("") { s -> s[0].uppercase() }.also {
                        if (parts.size > 3) scale = 0.6
                    }
                    else if (parts.size > 1) parts.joinToString("") { s -> s[0].uppercase() }
                    else parts[0].take(3).uppercaseFirst() + "."
                }

                if (enchantedBookAbbreviation.value) context.drawString(prefix, x, y, scale = scale)
                if (enchantedBookLevel.value && level.isNotEmpty()) context.drawString(level, x + 17 - level.width(), y + 9)
            }
        }

        drawUsageBar(context, stack, x, y)
    }

    /** Called by [com.laybalt.mixin.MixinGuiItemOverlays] after a hotbar slot is drawn. */
    @JvmStatic
    fun onHotbarSlotDraw(context: GuiGraphicsExtractor, stack: ItemStack, x: Int, y: Int) {
        if (! enabled || ! LocationUtils.inSkyblock || ! barsOnHotbar.value) return
        drawUsageBar(context, stack, x, y)
    }

    private fun drawUsageBar(context: GuiGraphicsExtractor, stack: ItemStack, x: Int, y: Int) {
        if (! usageBars.value || stack.isEmpty) return
        val ratio = usageRatio(stack) ?: return
        if (hideWhenFull.value && ratio >= 1f) return

        val color = (if (barColor.value == 1) Palette.accent else emptyColor.lerp(fullColor, ratio)).rgb

        when (barStyle.value) {
            0 -> {
                val width = (13 * ratio).roundToInt()
                context.fill(x + 2, y + 13, x + 15, y + 15, trackColor)
                context.fill(x + 2, y + 13, x + 2 + width, y + 14, color)
            }

            1 -> {
                val width = (16 * ratio).roundToInt()
                context.fill(x, y + 15, x + 16, y + 16, trackColor)
                context.fill(x, y + 15, x + width, y + 16, color)
            }

            2 -> {
                val height = (14 * ratio).roundToInt()
                context.fill(x + 14, y + 1, x + 16, y + 15, trackColor)
                context.fill(x + 14, y + 15 - height, x + 15, y + 15, color)
            }
        }
    }

    /** How full the item is (0..1), or null when it has no fuel, durability or charges. Cached until the item's lore or data changes. */
    private fun usageRatio(stack: ItemStack): Float? {
        val lore = stack.get(DataComponents.LORE)
        val data = stack.get(DataComponents.CUSTOM_DATA)
        usageCache[stack]?.let { if (it.lore === lore && it.data === data) return it.ratio }

        val ratio = computeUsageRatio(stack, lore)
        usageCache[stack] = CachedUsage(lore, data, ratio)
        return ratio
    }

    private fun computeUsageRatio(stack: ItemStack, lore: ItemLore?): Float? {
        if (stack.skyblockId == "PICKONIMBUS") {
            stack.customData.getInt("pickonimbus_durability").getOrNull()?.let { return (it.toFloat() / PICKONIMBUS_MAX).coerceIn(0f, 1f) }
        }

        lore ?: return null
        for (line in lore.lines()) {
            val match = usageLoreRegex.matchEntire(line.unformattedText.trim()) ?: continue
            val current = NumbersUtils.parseCompactNumberDouble(match.groupValues[1]) ?: continue
            val max = NumbersUtils.parseCompactNumberDouble(match.groupValues[2])?.takeIf { it > 0.0 } ?: continue
            return (current / max).toFloat().coerceIn(0f, 1f)
        }
        return null
    }
}
