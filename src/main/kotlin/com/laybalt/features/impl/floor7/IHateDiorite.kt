// Colour options ported from OdinClient's FuckDiorite (https://github.com/skies-starred/OdinClient), BSD-3-Clause, Copyright (c) 2026 Starred
package com.laybalt.features.impl.floor7

//#if CHEAT

import com.laybalt.config.types.DropdownSetting
import com.laybalt.config.types.ToggleSetting
import com.laybalt.event.impl.TickEvent
import com.laybalt.features.Feature
import com.laybalt.utils.WorldUtils
import com.laybalt.utils.location.LocationUtils
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState

object IHateDiorite: Feature("Replaces the diorite pillars in the Storm (P2) fight with glass.") {
    private val randomColors by ToggleSetting("Random Colors")
        .withDescription("Replaces every diorite block with a random stained glass colour.")
    private val pillarBasedColor by ToggleSetting("Pillar Based Color", true)
        .withDescription("Colours each pillar after the pad it belongs to (lime, yellow, purple, red).")
        .showIf { ! randomColors.value }
    private val glassColor by DropdownSetting("Glass Color", 0, listOf(
        "None", "White", "Orange", "Magenta", "Light Blue", "Yellow", "Lime", "Pink",
        "Gray", "Light Gray", "Cyan", "Purple", "Blue", "Brown", "Green", "Red", "Black"
    )).withDescription("Stained glass colour used for every pillar. \"None\" uses plain (clear) glass.")
        .showIf { ! randomColors.value && ! pillarBasedColor.value }

    /** White .. black, in dye order (matches the dropdown order after "None"). */
    private val stainedGlass: List<BlockState> = Blocks.STAINED_GLASS.asList().map { it.defaultBlockState() }
    private val plainGlass: BlockState = Blocks.GLASS.defaultBlockState()

    private val pillars = arrayOf(
        Pillar(BlockPos(46, 169, 41), Blocks.STAINED_GLASS.lime().defaultBlockState()),
        Pillar(BlockPos(46, 169, 65), Blocks.STAINED_GLASS.yellow().defaultBlockState()),
        Pillar(BlockPos(100, 169, 65), Blocks.STAINED_GLASS.purple().defaultBlockState()),
        Pillar(BlockPos(100, 169, 41), Blocks.STAINED_GLASS.red().defaultBlockState())
    )

    private val DIORITE_BLOCKS = setOf(Blocks.DIORITE, Blocks.POLISHED_DIORITE)

    override fun init() {
        register<TickEvent.Start> {
            if (LocationUtils.F7Phase != 2) return@register
            for (pillar in pillars) for (pos in pillar.area) {
                if (WorldUtils.getBlockAt(pos) !in DIORITE_BLOCKS) continue
                WorldUtils.setBlockAt(pos, glassFor(pillar))
            }
        }
    }

    private fun glassFor(pillar: Pillar): BlockState = when {
        randomColors.value -> stainedGlass.random()
        pillarBasedColor.value -> pillar.glass
        glassColor.value in 1 .. stainedGlass.size -> stainedGlass[glassColor.value - 1]
        else -> plainGlass
    }

    private class Pillar(pos: BlockPos, val glass: BlockState) {
        val area = BlockPos.betweenClosed(
            pos.offset(- RADIUS, 0, - RADIUS),
            pos.offset(RADIUS, HEIGHT, RADIUS)
        )

        private companion object {
            const val RADIUS = 3
            const val HEIGHT = 37
        }
    }
}
//#endif
