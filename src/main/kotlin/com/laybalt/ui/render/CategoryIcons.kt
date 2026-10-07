package com.laybalt.ui.render

import com.laybalt.ui.Palette
import com.laybalt.ui.clickgui.enums.CategoryType
import com.laybalt.ui.render.Shapes.roundedRect
import com.laybalt.ui.text.UIFont
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

/**
 * Vanilla items used as the icon of each GUI category.
 *
 * Item components are only bound once a world's registries are loaded, so on the title screen (e.g. when the GUI is
 * opened from Mod Menu) an ItemStack cannot be created yet; the icon then falls back to a rounded tile with the
 * category's initial.
 */
object CategoryIcons {
    private val items = mapOf<CategoryType, Item>(
        CategoryType.GENERAL to Items.COMPASS,
        CategoryType.DUNGEON to Items.SKELETON_SKULL, // light grey: a wither skull vanishes on the dark sidebar
        CategoryType.FLOOR7 to Items.NETHER_STAR,
        CategoryType.VISUAL to Items.PAINTING,
        CategoryType.RENDER to Items.SPYGLASS,
        CategoryType.MISC to Items.REDSTONE,
        CategoryType.FISHING to Items.FISHING_ROD,
        CategoryType.RIFT to Items.ENDER_EYE,
        CategoryType.KUUDRA to Items.MAGMA_CREAM,
        CategoryType.DEV to Items.COMPARATOR
    )

    private val stacks = HashMap<CategoryType, ItemStack>()

    fun item(category: CategoryType): Item = items.getValue(category)

    /** The category's icon stack, or null while item components are not bound (no world loaded yet). */
    fun stack(category: CategoryType): ItemStack? {
        stacks[category]?.let { return it }
        val item = item(category)
        if (!item.builtInRegistryHolder().areComponentsBound()) return null
        return ItemStack(item).also { stacks[category] = it }
    }

    /** Draws the category's item at ([x], [y]) scaled to [size] GUI units (items are 16 units natively). */
    fun draw(ctx: GuiGraphicsExtractor, category: CategoryType, x: Number, y: Number, size: Number = 16) {
        val fx = x.toFloat()
        val fy = y.toFloat()
        val fs = size.toFloat()
        val stack = stack(category)
        if (stack == null) {
            drawFallback(ctx, category, fx, fy, fs)
            return
        }
        val s = fs / 16f
        ctx.pose().pushMatrix()
        ctx.pose().translate(fx, fy)
        if (s != 1f) ctx.pose().scale(s, s)
        ctx.fakeItem(stack, 0, 0)
        ctx.pose().popMatrix()
    }

    private fun drawFallback(ctx: GuiGraphicsExtractor, category: CategoryType, x: Float, y: Float, size: Float) {
        val inset = size * 0.08f
        val tile = size - inset * 2f
        ctx.roundedRect(x + inset, y + inset, tile, tile, tile * 0.28f, Palette.accentPill)
        val textSize = tile * 0.62f
        UIFont.drawCentered(ctx, category.displayName.take(1), x + size / 2f, y + (size - UIFont.lineHeight(textSize)) / 2f, Palette.accentText, textSize, UIFont.Weight.BOLD)
    }
}
