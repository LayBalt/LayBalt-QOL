package com.laybalt.features.impl.floor7.terminals

import com.laybalt.event.EventBus
import com.laybalt.event.impl.TerminalEvent
import com.laybalt.features.impl.floor7.terminals.impl.Terminal
import com.laybalt.init.types.ISelfInit

object HumanClickOrder: ISelfInit {
    private const val NEIGHBOR_RADIUS_SQR = 20.0 * 20.0

    var lastClickedSlot: Int? = null

    fun getBestClick(handler: Terminal) = selectClick(handler, worst = false)
    fun getWorstClick(handler: Terminal) = selectClick(handler, worst = true)

    private fun selectClick(handler: Terminal, worst: Boolean): TerminalClick {
        if (handler.solution.isEmpty()) error("Solution list is empty")

        val lastSlot = lastClickedSlot ?: (handler.slotCount / 2).also(::lastClickedSlot::set)

        val comparator = Comparator<TerminalClick> { clickA, clickB ->
            val distanceComparison = getDistanceSqr(clickA.slotId, lastSlot).compareTo(getDistanceSqr(clickB.slotId, lastSlot))
            if (distanceComparison != 0) distanceComparison
            else countNeighbors(clickA.slotId, handler.solution).compareTo(countNeighbors(clickB.slotId, handler.solution))
        }

        val shuffled = handler.solution.shuffled()
        val selected = if (worst) shuffled.asReversed().maxWith(comparator)
        else shuffled.minWith(comparator)

        lastClickedSlot = selected.slotId
        return selected
    }

    private fun countNeighbors(targetId: Int, allClicks: List<TerminalClick>): Int {
        return allClicks.count { other ->
            other.slotId != targetId && getDistanceSqr(targetId, other.slotId) <= NEIGHBOR_RADIUS_SQR
        }
    }

    // NoammAddons cff93729: computed from the slot index (18px chest grid) instead of player.containerMenu,
    // which is the player's inventory while the terminal simulator is open. Same result on Hypixel.
    private fun getDistanceSqr(id1: Int, id2: Int): Double {
        val dx = ((id1 % 9 - id2 % 9) * 18).toDouble()
        val dy = ((id1 / 9 - id2 / 9) * 18).toDouble()

        return dx * dx + dy * dy
    }

    override fun init() {
        EventBus.register<TerminalEvent.Close> { lastClickedSlot = null }
    }
}