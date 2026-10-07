package com.laybalt.features.impl.misc

import com.laybalt.config.types.ToggleSetting
import com.laybalt.features.Feature
import com.laybalt.utils.location.LocationUtils

/**
 * @see com.laybalt.mixin.MixinAbstractRecipeBookScreen
 * @see com.laybalt.mixin.MixinRecipeBookComponent
 * @see com.laybalt.mixin.MixinGuiGraphicsExtractor
 */
object Tweaks: Feature("Small quality of life tweaks.") {
    @JvmStatic val hideRecipeBook by ToggleSetting("Hide Recipe Book").withDescription("Hides the recipe book button in inventory GUIs.")
    @JvmStatic val closeRecipeBook by ToggleSetting("Close Recipe Book").withDescription("Also closes the recipe book screen.").showIf { hideRecipeBook.value }
    @JvmStatic val hideItemCooldowns by ToggleSetting("Hide Item Cooldowns").withDescription("Hides the hotbar cooldown overlay for items.")
    @JvmStatic val hideHotbarTooltips by ToggleSetting("Hide Hotbar Tooltips").withDescription("Hides the item tooltip when switching items in hotbar.")

    @JvmStatic fun shouldHideItemCooldownOverlay() = enabled && hideItemCooldowns.value && LocationUtils.inSkyblock
}