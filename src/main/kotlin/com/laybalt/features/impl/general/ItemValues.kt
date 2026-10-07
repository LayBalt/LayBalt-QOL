package com.laybalt.features.impl.general

import com.laybalt.init.NetworkLoop
import com.laybalt.ui.Palette
import com.laybalt.utils.NumbersUtils
import com.laybalt.utils.items.ItemUtils.skyblockId
import com.laybalt.utils.render.Render2D.drawRect
import com.laybalt.utils.render.Render2D.drawString
import com.laybalt.utils.render.RenderHelper.width
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.world.item.ItemStack
import java.awt.Color

/**
 * Price lookups shared by [ItemValueHud] and [ContainerValue].
 * Prices come from [NetworkLoop] (Hypixel Bazaar, lowest BIN and NPC sell price lists).
 */
internal object ItemValues {
    private val petTiers = listOf("COMMON", "UNCOMMON", "RARE", "EPIC", "LEGENDARY", "MYTHIC")

    /** Gold used for coin amounts in every value card. */
    val coinColor = Color(246, 196, 92)

    data class Value(val id: String, val unitPrice: Long, val count: Int, val source: String) {
        val total get() = unitPrice * count
    }

    /**
     * @param useBuyPrice use the instant-buy Bazaar price instead of the instant-sell price
     * @param npcFallback use the NPC sell price when the item has no Bazaar or auction price
     */
    fun valueOf(stack: ItemStack, useBuyPrice: Boolean, npcFallback: Boolean): Value? {
        if (stack.isEmpty) return null
        val id = stack.skyblockId.takeIf { it.isNotEmpty() } ?: return null
        return valueOf(id, stack.count, useBuyPrice, npcFallback)
    }

    fun valueOf(id: String, count: Int, useBuyPrice: Boolean, npcFallback: Boolean): Value? {
        NetworkLoop.getBazaarPrice(id)?.let { price ->
            val unit = if (useBuyPrice) price.buy else price.sell
            if (unit > 0L) return Value(id, unit, count, if (useBuyPrice) "Bazaar Buy" else "Bazaar Sell")
        }

        lowestBin(id)?.takeIf { it > 0L }?.let { return Value(id, it, count, "Lowest BIN") }
        if (npcFallback) NetworkLoop.getNpcSellPrice(id)?.takeIf { it > 0L }?.let { return Value(id, it, count, "NPC Sell") }
        return null
    }

    fun lowestBin(id: String): Long? = NetworkLoop.getLowestBin(id) ?: petBinId(id)?.let(NetworkLoop::getLowestBin)

    /** "PET-ENDER_DRAGON-LEGENDARY" -> "ENDER_DRAGON;4", the id format the lowest-BIN list uses for pets. */
    private fun petBinId(id: String): String? {
        if (! id.startsWith("PET-")) return null
        val body = id.removePrefix("PET-")
        val tier = petTiers.indexOf(body.substringAfterLast('-')).takeIf { it >= 0 } ?: return null
        return "${body.substringBeforeLast('-')};$tier"
    }

    fun coins(value: Long, compact: Boolean) = if (compact) NumbersUtils.format(value) else NumbersUtils.formatComma(value)
}

/**
 * The LayBalt "value card": a dark panel with a coloured strip on the left, a title row and label/value rows.
 * [measure] gives the size so callers can position the card before drawing it.
 */
internal class ValueCard(private val title: String, private val accent: Color, private val rows: List<Row>) {
    data class Row(val label: String, val value: String, val valueColor: Color = ItemValues.coinColor, val labelColor: Color = Palette.textSecondary)

    fun measure(): Pair<Float, Float> {
        val rowWidth = rows.maxOfOrNull { it.label.width() + GAP + it.value.width() } ?: 0
        val width = STRIP + PAD * 2 + maxOf(title.width(), rowWidth)
        val height = PAD * 2 + LINE + (if (rows.isEmpty()) 0 else 3 + rows.size * LINE)
        return width.toFloat() to height.toFloat()
    }

    fun draw(ctx: GuiGraphicsExtractor): Pair<Float, Float> {
        val (w, h) = measure()
        ctx.drawRect(2f, 2f, w, h, Palette.shadow)
        ctx.drawRect(0f, 0f, w, h, Palette.bgPanel)
        ctx.drawRect(0f, 0f, STRIP.toFloat(), h, accent)
        ctx.drawRect(STRIP.toFloat(), 0f, w - STRIP, 1f, Palette.border)
        ctx.drawRect(STRIP.toFloat(), h - 1f, w - STRIP, 1f, Palette.border)
        ctx.drawRect(w - 1f, 1f, 1f, h - 2f, Palette.border)

        val left = (STRIP + PAD).toFloat()
        var y = PAD.toFloat()
        ctx.drawString(title, left, y, Palette.textPrimary)
        y += LINE

        if (rows.isNotEmpty()) {
            ctx.drawRect(left, y, w - left - PAD, 1f, accent.let { Color(it.red, it.green, it.blue, 90) })
            y += 3
        }

        for (row in rows) {
            ctx.drawString(row.label, left, y, row.labelColor)
            ctx.drawString(row.value, w - PAD - row.value.width(), y, row.valueColor)
            y += LINE
        }

        return w to h
    }

    private companion object {
        const val STRIP = 2
        const val PAD = 5
        const val LINE = 10
        const val GAP = 12
    }
}
