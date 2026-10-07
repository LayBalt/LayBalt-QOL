package com.laybalt.features.impl.visual

import com.laybalt.config.types.ColorSetting
import com.laybalt.config.types.DropdownSetting
import com.laybalt.config.types.SliderSetting
import com.laybalt.config.types.ToggleSetting
import com.laybalt.features.Feature
import com.laybalt.utils.render.Render2D.drawRect
import com.laybalt.utils.render.Render2D.drawString
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import java.awt.Color
import kotlin.math.roundToInt

object ArmorHud: Feature("Shows your armor and held item with durability bars or percentages.", name = "Armor HUD") {
    private val layout by DropdownSetting("Layout", 0, listOf("Vertical", "Horizontal")).withDescription("Stack the items in a column or in a row.").section("Layout")
    private val showHeld by ToggleSetting("Held Item", true).withDescription("Also shows the item in your main hand.")
    private val showOffhand by ToggleSetting("Offhand Item", false).withDescription("Also shows the item in your offhand.")
    private val heldFirst by ToggleSetting("Held Item First", false).withDescription("Puts the held items before the armor instead of after it.").showIf { showHeld.value || showOffhand.value }
    private val hideEmpty by ToggleSetting("Hide Empty Slots", true).withDescription("Skips slots with nothing in them instead of leaving a gap.")

    private val durability by DropdownSetting("Durability", 2, listOf("None", "Bar", "Percent", "Bar and Percent", "Remaining", "Bar and Remaining"))
        .withDescription("How durability is shown: a bar under the item, a percentage, the remaining uses, or a mix.").section("Durability")
    private val colorByDurability by ToggleSetting("Color By Durability", true).withDescription("Colours the durability text and bar from green (full) to red (almost broken).")
    private val showCount by ToggleSetting("Stack Count", true).withDescription("Shows the stack size for stackable items such as arrows or ender pearls.")
    private val lowWarning by ToggleSetting("Low Durability Warning", true).withDescription("Pulses a red glow behind items that are about to break.")
    private val lowThreshold by SliderSetting("Warning Threshold", 10, 1, 50, 1, "%").withDescription("Durability percentage at or below which the warning starts.").showIf { lowWarning.value }

    private val textColor by ColorSetting("Text Color", HudWidgetKit.defaultText, false).withDescription("Colour of the text when Color By Durability is off.").section("Colors")
    private val barColor by ColorSetting("Bar Color", HudWidgetKit.defaultAccent, false).withDescription("Colour of the durability bar when Color By Durability is off.")
    private val barBackground by ColorSetting("Bar Background", Color(0, 0, 0, 170)).withDescription("Colour of the empty part of the durability bar.")
    private val textShadow by ToggleSetting("Text Shadow", true).withDescription("Draws the text with a shadow.")

    private val bgMode by DropdownSetting("Background", 3, HudWidgetKit.BACKGROUND_MODES).withDescription("Panel drawn behind the whole widget.").section("Panel")
    private val bgColor by ColorSetting("Background Color", HudWidgetKit.defaultBackground).withDescription("Colour of the panel behind the widget.").showIf { bgMode.value != 0 }
    private val accentColor by ColorSetting("Accent Color", HudWidgetKit.defaultAccent).withDescription("Colour of the panel outline, accent bar or glass hairline.").showIf { bgMode.value >= 2 }

    private val exampleItems by lazy {
        listOf(Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE, Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS, Items.DIAMOND_SWORD).mapIndexed { i, item ->
            ItemStack(item).apply { if (isDamageableItem) damageValue = (maxDamage * (0.15f + i * 0.17f)).toInt() }
        }
    }

    private val armorSlots = listOf(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET)

    override fun init() {
        hudElement("ArmorHud") { ctx, example -> draw(ctx, example) } defaults {
            x = 5f
            y = 160f
        }
    }

    private fun collect(example: Boolean): List<ItemStack> {
        val player = mc.player
        val armor = armorSlots.map { player?.getItemBySlot(it) ?: ItemStack.EMPTY }
        val held = buildList {
            if (showHeld.value) add(player?.mainHandItem ?: ItemStack.EMPTY)
            if (showOffhand.value) add(player?.offhandItem ?: ItemStack.EMPTY)
        }
        val all = if (heldFirst.value) held + armor else armor + held
        // Item components are only bound once a world is loaded; building example stacks earlier (title screen) throws.
        if (example && all.all { it.isEmpty }) return if (mc.level != null) exampleItems else emptyList()
        return if (hideEmpty.value) all.filterNot { it.isEmpty } else all
    }

    private fun durabilityFraction(stack: ItemStack): Float? {
        if (stack.isEmpty || ! stack.isDamageableItem || stack.maxDamage <= 0) return null
        return 1f - stack.damageValue.toFloat() / stack.maxDamage
    }

    private fun label(stack: ItemStack): String? {
        val fraction = durabilityFraction(stack)
        if (fraction == null) return if (showCount.value && stack.count > 1) stack.count.toString() else null
        return when (durability.value) {
            2, 3 -> "${(fraction * 100).roundToInt()}%"
            4, 5 -> (stack.maxDamage - stack.damageValue).toString()
            else -> null
        }
    }

    private fun showBar() = durability.value == 1 || durability.value == 3 || durability.value == 5

    private fun draw(ctx: GuiGraphicsExtractor, example: Boolean): Pair<Number, Number> {
        val items = collect(example)
        val pad = HudWidgetKit.padding(bgMode.value)
        val left = HudWidgetKit.leftPadding(bgMode.value)
        val bar = showBar()
        val labels = items.map(::label)
        val font = mc.font

        if (items.isEmpty()) {
            if (! example) return 16 to 16
            val text = "Armor HUD"
            ctx.drawString(text, 0, 0, Color.WHITE, 1, true)
            return font.width(text) to 9
        }

        val vertical = layout.value == 0
        val cellH = 16 + if (bar) 3 else 1
        val textScale = 0.75f
        val maxLabel = labels.maxOfOrNull { it?.let(font::width) ?: 0 } ?: 0

        val contentW: Int
        val contentH: Int
        val cellW: Int
        if (vertical) {
            cellW = 16 + if (maxLabel > 0) 3 + maxLabel else 0
            contentW = cellW
            contentH = items.size * cellH - 1
        }
        else {
            cellW = maxOf(16, (maxLabel * textScale).roundToInt()) + 2
            contentW = items.size * cellW - 2
            contentH = cellH + if (maxLabel > 0) (8 * textScale).roundToInt() + 1 else 0
        }

        val totalW = contentW + left + pad
        val totalH = contentH + pad * 2
        HudWidgetKit.drawBackground(ctx, totalW, totalH, bgMode.value, bgColor.value, accentColor.value)

        items.forEachIndexed { i, stack ->
            val cellX = left + if (vertical) 0 else i * cellW
            val cellY = pad + if (vertical) i * cellH else 0
            val iconX = if (vertical) cellX else cellX + (cellW - 2 - 16) / 2
            val fraction = durabilityFraction(stack)
            val tint = if (colorByDurability.value && fraction != null) HudWidgetKit.healthColor(fraction) else null

            if (lowWarning.value && fraction != null && fraction * 100f <= lowThreshold.value) {
                val pulse = ((kotlin.math.sin(System.currentTimeMillis() / 160.0) + 1.0) / 2.0).toFloat()
                ctx.drawRect(iconX - 1, cellY - 1, 18, 18, Color(240, 60, 70, (60 + 90 * pulse).toInt()))
            }

            if (! stack.isEmpty) ctx.item(stack, iconX, cellY)

            if (bar && fraction != null) {
                ctx.drawRect(iconX + 1, cellY + 16, 14, 2, barBackground.value)
                ctx.drawRect(iconX + 1, cellY + 16, 14f * fraction, 2, tint ?: barColor.value)
            }

            val text = labels[i] ?: return@forEachIndexed
            val color = (if (fraction != null) tint else null) ?: textColor.value
            if (vertical) ctx.drawString(text, cellX + 19, cellY + 4, color, shadow = textShadow.value)
            else {
                val tw = font.width(text) * textScale
                ctx.drawString(text, cellX + (cellW - 2 - tw) / 2f, cellY + cellH, color, textScale, textShadow.value)
            }
        }

        return totalW to totalH
    }

}
