package com.laybalt.ui.render

import com.laybalt.ui.clickgui.enums.CategoryType
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.resources.Identifier
import kotlin.math.roundToInt

/**
 * Vanilla item textures used as the icon of each GUI category. They are drawn from their PNGs (see [ItemTextures]),
 * so the sidebar shows the same distinct icons on the title screen (Mod Menu) as in a world, and they fade with the
 * window.
 */
object CategoryIcons {
    private class Icon(val texture: Identifier, val u: Float = 0f, val v: Float = 0f, val w: Int = 16, val h: Int = 16, val texW: Int = 16, val texH: Int = 16)

    private val icons = mapOf(
        CategoryType.GENERAL to Icon(ItemTextures.item("compass_00")),
        // The skull item is a 3D block model without a flat texture: use the 8x8 head front of the skeleton texture
        // (a wither skull would vanish on the dark sidebar).
        CategoryType.DUNGEON to Icon(Identifier.withDefaultNamespace("textures/entity/skeleton/skeleton.png"), 8f, 8f, 8, 8, 64, 32),
        CategoryType.FLOOR7 to Icon(ItemTextures.item("nether_star")),
        CategoryType.VISUAL to Icon(ItemTextures.item("painting")),
        CategoryType.RENDER to Icon(ItemTextures.item("spyglass")),
        CategoryType.MISC to Icon(ItemTextures.item("redstone")),
        CategoryType.FISHING to Icon(ItemTextures.item("fishing_rod")),
        CategoryType.RIFT to Icon(ItemTextures.item("ender_eye")),
        CategoryType.KUUDRA to Icon(ItemTextures.item("magma_cream")),
        CategoryType.DEV to Icon(ItemTextures.item("comparator"))
    )

    /** Draws the category's icon at ([x], [y]) scaled to [size] GUI units (item textures are 16 units natively). */
    fun draw(ctx: GuiGraphicsExtractor, category: CategoryType, x: Number, y: Number, size: Number = 16, alpha: Float = 1f) {
        if (alpha <= 0.02f) return
        val icon = icons.getValue(category)
        val fx = x.toFloat()
        val fy = y.toFloat()
        val fs = size.toFloat()
        val color = ((alpha.coerceIn(0f, 1f) * 255f).roundToInt() shl 24) or 0xFFFFFF
        // The head face fills its square edge to edge; inset it so it matches the visual size of the item sprites.
        if (icon.w == 8) ItemTextures.drawRegion(ctx, icon.texture, fx + fs * 0.12f, fy + fs * 0.12f, fs * 0.76f, icon.u, icon.v, icon.w, icon.h, icon.texW, icon.texH, color)
        else ItemTextures.drawRegion(ctx, icon.texture, fx, fy, fs, icon.u, icon.v, icon.w, icon.h, icon.texW, icon.texH, color)
    }
}
