// Ported from OdinClient (https://github.com/skies-starred/OdinClient), BSD-3-Clause, Copyright (c) 2026 Starred
package com.laybalt.features.impl.dungeon

//#if CHEAT

import com.laybalt.config.types.DropdownSetting
import com.laybalt.config.types.SliderSetting
import com.laybalt.config.types.ToggleSetting
import com.laybalt.event.impl.MouseClickEvent
import com.laybalt.event.impl.TickEvent
import com.laybalt.event.impl.WorldChangeEvent
import com.laybalt.features.Feature
import com.laybalt.utils.PlayerUtils
import com.laybalt.utils.WorldUtils
import com.laybalt.utils.items.ItemUtils.skyblockId
import com.laybalt.utils.location.LocationUtils
import gg.essential.universal.UMinecraft
import net.minecraft.world.level.block.Blocks
import org.lwjgl.glfw.GLFW

object AutoSuperboom: Feature("Automatically swaps to Superboom TNT and uses it when you click a cracked (breakable) dungeon wall.") {
    private val trigger by DropdownSetting("Trigger", 0, listOf("Any Click", "Left Click", "Right Click"))
        .withDescription("Which mouse button on a cracked stone brick wall starts the swap.")
    private val minDelay by SliderSetting("Minimum Delay", 1, 1, 5, 1, " ticks").withDescription("The minimum delay before swapping to the Superboom.")
    private val maxDelay by SliderSetting("Maximum Delay", 3, 1, 10, 1, " ticks").withDescription("The maximum delay before swapping to the Superboom (a random delay between min and max is used).")
    private val swapBack by ToggleSetting("Swap Back").withDescription("Swap back to the item you were holding after the Superboom was used.")
    private val minSwapBack by SliderSetting("Min Swap Back Delay", 1, 1, 5, 1, " ticks").withDescription("The minimum delay before swapping back.").showIf { swapBack.value }
    private val maxSwapBack by SliderSetting("Max Swap Back Delay", 2, 1, 10, 1, " ticks").withDescription("The maximum delay before swapping back.").showIf { swapBack.value }

    private val superbooms = setOf("SUPERBOOM_TNT", "INFINITE_SUPERBOOM_TNT")

    private var tick = -1
    private var originalSlot = -1
    private var targetSlot = -1
    private var stage = 0

    override fun init() {
        register<MouseClickEvent> {
            if (event.action != GLFW.GLFW_PRESS) return@register
            when (trigger.value) {
                1 -> if (event.button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return@register
                2 -> if (event.button != GLFW.GLFW_MOUSE_BUTTON_RIGHT) return@register
            }
            if (UMinecraft.currentScreenObj != null) return@register
            if (! LocationUtils.inDungeon || LocationUtils.inBoss) return@register
            if (tick != -1) return@register
            val pos = PlayerUtils.getSelectionBlock() ?: return@register
            if (WorldUtils.getBlockAt(pos) != Blocks.CRACKED_STONE_BRICKS) return@register

            val current = player.inventory.selectedSlot
            val slot = PlayerUtils.findHotbarSlot { it.skyblockId in superbooms }?.takeIf { it != current } ?: return@register

            tick = randomBetween(minDelay.value, maxDelay.value)
            originalSlot = current
            targetSlot = slot
            stage = 0
            event.isCanceled = true
        }

        register<TickEvent.Start> {
            if (tick == -1) return@register
            if (tick -- > 0) return@register

            when (stage) {
                0 -> {
                    if (targetSlot == -1) return@register reset()
                    player.inventory.selectedSlot = targetSlot
                    stage = 1
                    tick = 1
                }

                1 -> {
                    PlayerUtils.leftClick()
                    if (! swapBack.value) return@register reset()
                    stage = 2
                    tick = randomBetween(minSwapBack.value, maxSwapBack.value)
                }

                2 -> {
                    if (originalSlot in 0 .. 8) player.inventory.selectedSlot = originalSlot
                    reset()
                }
            }
        }

        register<WorldChangeEvent> { reset() }
    }

    override fun onDisable() {
        super.onDisable()
        reset()
    }

    private fun randomBetween(min: Int, max: Int) = (min .. max.coerceAtLeast(min)).random()

    private fun reset() {
        tick = -1
        originalSlot = -1
        targetSlot = -1
        stage = 0
    }
}
//#endif
