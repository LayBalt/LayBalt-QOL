package com.laybalt.features.impl.visual

import com.laybalt.config.types.ColorSetting
import com.laybalt.config.types.DropdownSetting
import com.laybalt.config.types.SliderSetting
import com.laybalt.config.types.ToggleSetting
import com.laybalt.features.Feature
import com.laybalt.ui.render.ItemTextures
import com.laybalt.utils.render.Render2D.drawRect
import com.laybalt.utils.render.Render2D.drawString
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.resources.Identifier
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

    /** One drawn cell: a real stack, or a texture-only preview entry for the HUD editor before a world is loaded. */
    private class Slot(val stack: ItemStack?, val texture: Identifier?, val fraction: Float?, val remaining: Int, val count: Int)

    private val exampleItems by lazy {
        listOf(Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE, Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS, Items.DIAMOND_SWORD).mapIndexed { i, item ->
            ItemStack(item).apply { if (isDamageableItem) damageValue = (maxDamage * (0.15f + i * 0.17f)).toInt() }
        }
    }

    /**
     * Same example set drawn from the item textures: item components are only bound once a world is loaded, so on the
     * title screen (HUD editor opened from Mod Menu) no ItemStack can be built.
     */
    private val previewArmor = listOf("diamond_helmet" to 363, "diamond_chestplate" to 528, "diamond_leggings" to 495, "diamond_boots" to 429)
        .mapIndexed { i, (name, max) -> previewSlot(name, max, 0.15f + i * 0.17f) }
    private val previewHeld = previewSlot("diamond_sword", 1561, 0.83f)
    private val previewOffhand = Slot(null, ItemTextures.item("arrow"), null, 0, 64)

    private fun previewSlot(texture: String, maxDamage: Int, damaged: Float): Slot {
        val damage = (maxDamage * damaged).toInt()
        return Slot(null, ItemTextures.item(texture), 1f - damage.toFloat() / maxDamage, maxDamage - damage, 1)
    }

    private val armorSlots = listOf(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET)

    override fun init() {
        hudElement("ArmorHud") { ctx, example -> draw(ctx, example) } defaults {
            x = 5f
            y = 238f
        }
    }

    private fun slotOf(stack: ItemStack): Slot? {
        if (stack.isEmpty) return null
        val damageable = stack.isDamageableItem && stack.maxDamage > 0
        return Slot(
            stack, null,
            if (damageable) 1f - stack.damageValue.toFloat() / stack.maxDamage else null,
            if (damageable) stack.maxDamage - stack.damageValue else 0,
            stack.count
        )
    }

    /** Slots to draw; null entries are empty slots kept as gaps when Hide Empty Slots is off. */
    private fun collect(example: Boolean): List<Slot?> {
        val player = mc.player
        if (example && mc.level == null) {
            val held = buildList {
                if (showHeld.value) add(previewHeld)
                if (showOffhand.value) add(previewOffhand)
            }
            return if (heldFirst.value) held + previewArmor else previewArmor + held
        }
        val armor = armorSlots.map { player?.getItemBySlot(it) ?: ItemStack.EMPTY }
        val held = buildList {
            if (showHeld.value) add(player?.mainHandItem ?: ItemStack.EMPTY)
            if (showOffhand.value) add(player?.offhandItem ?: ItemStack.EMPTY)
        }
        val all = if (heldFirst.value) held + armor else armor + held
        if (example && all.all { it.isEmpty }) return exampleItems.map(::slotOf)
        return if (hideEmpty.value) all.mapNotNull(::slotOf) else all.map(::slotOf)
    }

    private fun label(slot: Slot?): String? {
        if (slot == null) return null
        val fraction = slot.fraction
        if (fraction == null) return if (showCount.value && slot.count > 1) slot.count.toString() else null
        return when (durability.value) {
            2, 3 -> "${(fraction * 100).roundToInt()}%"
            4, 5 -> slot.remaining.toString()
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

        if (items.isEmpty()) return 16 to 16

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

        items.forEachIndexed { i, slot ->
            val cellX = left + if (vertical) 0 else i * cellW
            val cellY = pad + if (vertical) i * cellH else 0
            val iconX = if (vertical) cellX else cellX + (cellW - 2 - 16) / 2
            if (slot == null) return@forEachIndexed
            val fraction = slot.fraction
            val tint = if (colorByDurability.value && fraction != null) HudWidgetKit.healthColor(fraction) else null

            if (lowWarning.value && fraction != null && fraction * 100f <= lowThreshold.value) {
                val pulse = ((kotlin.math.sin(System.currentTimeMillis() / 160.0) + 1.0) / 2.0).toFloat()
                ctx.drawRect(iconX - 1, cellY - 1, 18, 18, Color(240, 60, 70, (60 + 90 * pulse).toInt()))
            }

            when {
                slot.stack != null -> ctx.item(slot.stack, iconX, cellY)
                slot.texture != null -> ItemTextures.draw(ctx, slot.texture, iconX.toFloat(), cellY.toFloat())
            }

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
