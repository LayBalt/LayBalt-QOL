package com.laybalt.features.impl.general

import com.laybalt.LayBaltQOL.MOD_ID
import com.laybalt.config.types.*
import com.laybalt.event.impl.ContainerEvent
import com.laybalt.features.Feature
import com.laybalt.utils.ColorUtils.withAlpha
import com.laybalt.utils.items.ItemUtils
import com.laybalt.utils.location.LocationUtils
import com.laybalt.utils.render.Render2D.checkSlot
import com.laybalt.utils.render.Render2D.drawBorder
import com.laybalt.utils.render.Render2D.drawTexture
import com.laybalt.utils.render.Render2D.drawVerticalGradient
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.resources.Identifier
import net.minecraft.world.item.ItemStack
import java.awt.Color
import com.laybalt.utils.items.ItemRarity as Rarity

/**
 * Draws the SkyBlock rarity colour behind item slots.
 * Hotbar slots are drawn through [com.laybalt.mixin.MixinGuiItemRarity]; the storage overlay calls [onSlotDraw] directly.
 */
object ItemRarity: Feature(name = "Item Rarity", description = "Draws the rarity of item behind the slot.") {
    @JvmStatic val drawOnHotbar by ToggleSetting("Draw on Hotbar", true).withDescription("Also draws the rarity background behind the items in your hotbar")
    private val rarityOpacity by SliderSetting("Rarity Opacity", 30f, 10f, 100f, 1f).withDescription("How opaque the rarity background is, in percent")
    private val style by DropdownSetting("Rarity Style", 0, listOf("Filled", "Outline", "Filled Outline", "Circle", "Rounded", "Corner", "Bottom Bar", "Gradient", "Glow"))
        .withDescription("The shape drawn behind the item: a filled square, an outline, both, a circle, a square with rounded corners, a triangle in the top-left corner, a bar along the bottom, a fade from the bottom up, or a soft glow from the edges")
    private val colorStyle by DropdownSetting("Color Style", 0, listOf("Default", "Hypixel")).withDescription("Default uses the Minecraft chat colour of the rarity, Hypixel uses the exact colours of the in-game rarity text")

    private val outlineOpacity by SliderSetting("Outline Opacity", 100f, 10f, 100f, 1f).section("Shape").withDescription("How opaque the outline is, in percent (Outline, Filled Outline and Rounded styles)")
    private val outlineThickness by SliderSetting("Outline Thickness", 1, 1, 3, 1).withDescription("How many pixels wide the outline is (Outline, Filled Outline and Glow styles)")
    private val slotPadding by SliderSetting("Slot Padding", 0, 0, 4, 1).withDescription("Shrinks the shape by this many pixels on every side, leaving a gap to the slot edge")
    private val hotbarOpacity by SliderSetting("Hotbar Opacity", 30f, 10f, 100f, 1f).withDescription("How opaque the rarity background is in the hotbar, in percent").showIf { drawOnHotbar.value }

    private val circleTexture = Identifier.fromNamespaceAndPath(MOD_ID, "textures/item_rarity/circle.png")

    override fun init() {
        register<ContainerEvent.Render.Slot.Pre> {
            onSlotDraw(event.context, event.slot.item, event.slot.x, event.slot.y)
        }
    }

    @JvmStatic
    fun onSlotDraw(ctx: GuiGraphicsExtractor, stack: ItemStack?, x: Int, y: Int) = draw(ctx, stack, x, y, rarityOpacity.value)

    /** Called by the hotbar mixin, which uses its own opacity. */
    @JvmStatic
    fun onHotbarSlotDraw(ctx: GuiGraphicsExtractor, stack: ItemStack?, x: Int, y: Int) = draw(ctx, stack, x, y, hotbarOpacity.value)

    private fun draw(ctx: GuiGraphicsExtractor, stack: ItemStack?, x: Int, y: Int, opacity: Float) {
        if (! LocationUtils.inSkyblock) return
        if (stack == null || stack.isEmpty) return

        val rarity = ItemUtils.getRarity(stack)
        if (rarity == Rarity.NONE) return
        val rarityColor = if (colorStyle.value == 1) Rarity.getHypixelColor(rarity) else rarity.color
        val color = rarityColor.withAlpha(opacity / 100)
        val outline = rarityColor.withAlpha(outlineOpacity.value / 100)

        if (! checkSlot(x, y, - 1)) return

        val pad = slotPadding.value
        val x0 = x + pad
        val y0 = y + pad
        val size = 16 - pad * 2
        val thickness = outlineThickness.value.coerceAtMost(size / 2)

        when (style.value) {
            0 -> ctx.fill(x0, y0, x0 + size, y0 + size, color.rgb)
            1 -> ctx.drawBorder(x0, y0, size, size, outline, thickness)
            2 -> {
                ctx.fill(x0, y0, x0 + size, y0 + size, color.rgb)
                ctx.drawBorder(x0, y0, size, size, outline, thickness)
            }

            3 -> ctx.drawTexture(circleTexture, x0, y0, size, size, color)
            4 -> rounded(ctx, x0, y0, size, color, outline)
            5 -> corner(ctx, x0, y0, size, color)
            6 -> ctx.fill(x0, y0 + size - 2, x0 + size, y0 + size, rarityColor.withAlpha((opacity / 100 * 2.5f).coerceAtMost(1f)).rgb)
            7 -> ctx.drawVerticalGradient(x0, y0, size, size, rarityColor.withAlpha(0), color)
            8 -> glow(ctx, x0, y0, size, thickness, rarityColor, opacity / 100)
        }
    }

    /** A square with its four corner pixels cut off and a matching outline. */
    private fun rounded(ctx: GuiGraphicsExtractor, x: Int, y: Int, size: Int, fill: Color, outline: Color) {
        ctx.fill(x + 1, y + 1, x + size - 1, y + size - 1, fill.rgb)
        ctx.fill(x + 1, y, x + size - 1, y + 1, outline.rgb)
        ctx.fill(x + 1, y + size - 1, x + size - 1, y + size, outline.rgb)
        ctx.fill(x, y + 1, x + 1, y + size - 1, outline.rgb)
        ctx.fill(x + size - 1, y + 1, x + size, y + size - 1, outline.rgb)
    }

    /** A right triangle in the top-left corner, built from one-pixel rows. */
    private fun corner(ctx: GuiGraphicsExtractor, x: Int, y: Int, size: Int, color: Color) {
        val legs = (size / 2).coerceAtLeast(3)
        val solid = color.withAlpha((color.alpha * 2.5f).toInt().coerceAtMost(255))
        for (row in 0 until legs) ctx.fill(x, y + row, x + legs - row, y + row + 1, solid.rgb)
    }

    /** Rings that fade out towards the centre of the slot. */
    private fun glow(ctx: GuiGraphicsExtractor, x: Int, y: Int, size: Int, thickness: Int, color: Color, opacity: Float) {
        val rings = (thickness + 2).coerceAtMost(size / 2)
        for (ring in 0 until rings) {
            val alpha = (opacity * 1.6f * (1f - ring.toFloat() / rings)).coerceIn(0f, 1f)
            ctx.drawBorder(x + ring, y + ring, size - ring * 2, size - ring * 2, color.withAlpha(alpha))
        }
    }
}
