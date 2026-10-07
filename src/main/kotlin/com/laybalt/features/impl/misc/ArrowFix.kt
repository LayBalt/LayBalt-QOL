package com.laybalt.features.impl.misc

import com.laybalt.features.Feature
import com.laybalt.utils.items.ItemUtils.skyblockId
import net.minecraft.core.component.DataComponents
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

/**
 * @see com.laybalt.mixin.MixinPlayer
 */
object ArrowFix: Feature("Disables Bow Pullback on Shortbows.") {
    private val notBowCache = HashSet<String>()
    private val bowCache = HashSet<String>()

    @JvmStatic
    fun isShortbow(item: ItemStack?): Boolean {
        if (item == null || item.isEmpty) return false
        if (! item.`is`(Items.BOW)) return false
        val id = item.skyblockId
        if (id in bowCache) return true
        if (id in notBowCache) return false
        val lore = item.get(DataComponents.LORE)?.lines() ?: return false

        for (i in (lore.size - 3) downTo 0) {
            val line = lore[i].string
            if ("Shortbow: Instantly shoots!" in line) {
                bowCache.add(id)
                return true
            }
        }

        notBowCache.add(id)
        return false
    }
}