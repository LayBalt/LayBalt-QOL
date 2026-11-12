package com.laybalt.features.impl.misc

import com.laybalt.config.types.ToggleSetting
import com.laybalt.features.Feature
import com.laybalt.utils.items.ItemUtils.skyblockId
import com.laybalt.utils.location.LocationUtils
import net.minecraft.world.item.ItemStack

/**
 * Small quality of life tweaks.
 *
 * @see com.laybalt.mixin.MixinAbstractRecipeBookScreenTweaks
 * @see com.laybalt.mixin.MixinRecipeBookComponentTweaks
 * @see com.laybalt.mixin.MixinGuiGraphicsExtractorTweaks
 * @see com.laybalt.mixin.MixinHudTweaks
 * @see com.laybalt.mixin.MixinPathCreation
 */
object Tweaks: Feature("Small quality of life tweaks.") {
    @JvmStatic val hideRecipeBook by ToggleSetting("Hide Recipe Book")
        .withDescription("Removes the green recipe book button from the inventory, crafting table and furnace screens.")
    @JvmStatic val closeRecipeBook by ToggleSetting("Close Recipe Book")
        .withDescription("Also closes the recipe book if it was left open, so the screen opens without it.").showIf { hideRecipeBook.value }
    @JvmStatic val hideItemCooldowns by ToggleSetting("Hide Item Cooldowns")
        .withDescription("Hides the white cooldown overlay on items in the hotbar and inventories. Only on SkyBlock, where every ability item shows one.")

    @JvmStatic val hideHotbarTooltips by ToggleSetting("Hide Hotbar Tooltips")
        .withDescription("Hides the item name that pops up above the hotbar when you switch items (same as No Render > Held Item Name).")

    @JvmStatic val noAotvPaths by ToggleSetting("No AOTV Paths")
        .withDescription("Prevents the Aspect of the Void from creating ghost paths and playing shovel sounds.")

    @JvmStatic fun shouldHideRecipeBook() = enabled && hideRecipeBook.value
    @JvmStatic fun shouldCloseRecipeBook() = enabled && hideRecipeBook.value && closeRecipeBook.value
    @JvmStatic fun shouldHideHotbarTooltip() = enabled && hideHotbarTooltips.value
    @JvmStatic fun shouldHideItemCooldownOverlay() = enabled && hideItemCooldowns.value && LocationUtils.inSkyblock
    @JvmStatic fun shouldPreventAotvPaths(stack: ItemStack) = enabled && noAotvPaths.value && LocationUtils.inSkyblock && stack.skyblockId == "ASPECT_OF_THE_VOID"
}
