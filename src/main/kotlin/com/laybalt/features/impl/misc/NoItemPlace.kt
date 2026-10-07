package com.laybalt.features.impl.misc

import com.laybalt.features.Feature
import com.laybalt.utils.ChatUtils.unformattedText
import com.laybalt.utils.dungeons.enums.WitherRelic
import com.laybalt.utils.endsWithOneOf
import com.laybalt.utils.equalsOneOf
import com.laybalt.utils.items.ItemUtils.skyblockId
import com.laybalt.utils.location.LocationUtils
import com.laybalt.utils.startsWithOneOf
import net.minecraft.world.item.context.BlockPlaceContext

/**
 * @see com.laybalt.mixin.MixinBlockItem
 */
object NoItemPlace: Feature("Stops you from placing skull blocks/items.") {

    @JvmStatic
    fun placeHook(context: BlockPlaceContext): Boolean {
        if (! enabled) return false
        val item = context.player?.mainHandItem ?: return false
        val name = item.hoverName.unformattedText
        val id = item.skyblockId

        return when {
            LocationUtils.F7Phase == 5 && WitherRelic.fromName(name) != null -> true

            id.startsWithOneOf("ABIPHONE") -> true

            id.endsWithOneOf(
                "_TUBA",
                "_POWER_ORB",
                "_POCKET_BLACK_HOLE",
                "_FISHING_NET"
            ) -> true

            else -> id.equalsOneOf(
                "BOUQUET_OF_LIES",
                "FLOWER_OF_TRUTH",
                "BAT_WAND",
                "STARRED_BAT_WAND",
                "INFINITE_SPIRIT_LEAP",
                "ROYAL_PIGEON",
                "ARROW_SWAPPER",
                "JINGLE_BELLS",
                "FIRE_FREEZE_STAFF",
                "UMBERELLA",
                "ETHERWARP_CONDUIT",
                "KUUDRA_SHOP_ITEM"
            )
        }
    }
}