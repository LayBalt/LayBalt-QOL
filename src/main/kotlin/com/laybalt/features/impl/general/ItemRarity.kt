package com.laybalt.features.impl.general

import com.laybalt.LayBaltQOL.MOD_ID
import com.laybalt.config.types.DropdownSetting
import com.laybalt.config.types.SliderSetting
import com.laybalt.config.types.ToggleSetting
import com.laybalt.event.impl.ContainerEvent
import com.laybalt.features.Feature
import com.laybalt.utils.ColorUtils.withAlpha
import com.laybalt.utils.items.ItemUtils
import com.laybalt.utils.location.LocationUtils
import com.laybalt.utils.render.Render2D.checkSlot
import com.laybalt.utils.render.Render2D.drawBorder
import com.laybalt.utils.render.Render2D.drawTexture
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.resources.Identifier
import net.minecraft.world.item.ItemStack
import com.laybalt.utils.items.ItemRarity as Rarity

/**
 * Draws the SkyBlock rarity colour behind item slots.
 * Hotbar slots are drawn through [com.laybalt.mixin.MixinGuiItemRarity]; the storage overlay calls [onSlotDraw] directly.
 */
object ItemRarity: Feature(name = "Item Rarity", description = "Draws the rarity of item behind the slot.") {
    @JvmStatic val drawOnHotbar by ToggleSetting("Draw on Hotbar", true).withDescription("Also draws the rarity background behind the items in your hotbar")
    private val rarityOpacity by SliderSetting("Rarity Opacity", 30f, 10f, 100f, 1f).withDescription("How opaque the rarity background is, in percent")
    private val style by DropdownSetting("Rarity Style", 0, listOf("Filled", "Outline", "Filled Outline", "Circle")).withDescription("The shape drawn behind the item")
    private val colorStyle by DropdownSetting("Color Style", 0, listOf("Default", "Hypixel")).withDescription("Default uses the Minecraft chat colour of the rarity, Hypixel uses the exact colours of the in-game rarity text")
    private val circleTexture = Identifier.fromNamespaceAndPath(MOD_ID, "textures/item_rarity/circle.png")

    override fun init() {
        register<ContainerEvent.Render.Slot.Pre> {
            onSlotDraw(event.context, event.slot.item, event.slot.x, event.slot.y)
        }
    }

    @JvmStatic
    fun onSlotDraw(ctx: GuiGraphicsExtractor, stack: ItemStack?, x: Int, y: Int) {
        if (! LocationUtils.inSkyblock) return
        if (stack == null || stack.isEmpty) return

        val rarity = ItemUtils.getRarity(stack)
        if (rarity == Rarity.NONE) return
        val rarityColor = if (colorStyle.value == 1) Rarity.getHypixelColor(rarity) else rarity.color
        val color = rarityColor.withAlpha(rarityOpacity.value / 100)

        if (checkSlot(x, y, - 1)) when (style.value) {
            0 -> ctx.fill(x, y, x + 16, y + 16, color.rgb)
            1 -> ctx.drawBorder(x, y, 16, 16, color)
            2 -> {
                ctx.fill(x, y, x + 16, y + 16, color.rgb)
                ctx.drawBorder(x, y, 16, 16, rarityColor)
            }

            3 -> ctx.drawTexture(circleTexture, x, y, 16, 16, color)
        }
    }
}
