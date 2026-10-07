// Ported from Odin (https://github.com/odtheking/Odin), BSD-3-Clause, Copyright (c) 2025 odtheking
package com.laybalt.features.impl.visual

import com.laybalt.config.types.ColorSetting
import com.laybalt.config.types.ToggleSetting
import com.laybalt.event.impl.TickEvent
import com.laybalt.event.impl.WorldChangeEvent
import com.laybalt.features.Feature
import com.laybalt.utils.ChatUtils.formattedText
import com.laybalt.utils.ChatUtils.removeFormatting
import com.laybalt.utils.items.ItemUtils.lore
import com.laybalt.utils.location.LocationUtils
import com.laybalt.utils.render.Render2D.drawString
import com.laybalt.utils.render.RenderHelper.width
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import java.awt.Color

object QuiverDisplay: Feature("Displays the arrow type and the number of arrows left in your quiver.") {
    private val showName by ToggleSetting("Show Name", true).withDescription("Shows the name of the selected arrow type before the count.")
    private val countColor by ColorSetting("Count Color", Color(85, 255, 85), false).withDescription("Colour of the arrow count.")

    private val arrowRegex = Regex("^Arrows Remaining: ([\\d,]+)$")
    private var arrowName: String? = null
    private var arrowCount: String? = null
    private var ticks = 0

    override fun init() {
        hudElement("Quiver HUD", shouldDraw = { LocationUtils.inSkyblock && arrowCount != null }) { ctx, example ->
            val name = if (example) "§fFlint Arrow" else arrowName.orEmpty()
            val count = if (example) "100" else arrowCount.orEmpty()

            var x = 0
            if (showName.value) {
                val prefix = "$name§8x"
                ctx.drawString(prefix, 0, 0)
                x += prefix.width()
            }
            ctx.drawString(count, x, 0, countColor.value)
            return@hudElement (x + count.width()) to 9
        }

        register<WorldChangeEvent> { clear() }

        register<TickEvent.End> {
            if (! LocationUtils.inSkyblock) return@register clear()
            if (++ ticks % 5 != 0) return@register
            val inventory = mc.player?.inventory ?: return@register

            // As in Odin: the quiver arrow sits in the last hotbar slot (inventory index 8), except during dungeon
            // clear, where that slot holds the dungeon map and the arrow moves to the first inventory slot (index 9).
            val index = if (LocationUtils.inDungeon && ! LocationUtils.inBoss) 9 else 8
            if (! read(inventory.getItem(index))) clear()
        }
    }

    private fun clear() {
        arrowName = null
        arrowCount = null
    }

    private fun read(stack: ItemStack): Boolean {
        if (stack.isEmpty || (! stack.`is`(Items.FEATHER) && ! stack.`is`(Items.ARROW))) return false
        val count = stack.lore.firstNotNullOfOrNull { arrowRegex.find(it.removeFormatting())?.groupValues?.get(1) } ?: return false
        arrowName = stack.hoverName.formattedText
        arrowCount = count
        return true
    }
}
