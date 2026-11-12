// Ported from Odin (https://github.com/odtheking/Odin), BSD-3-Clause, Copyright (c) 2025 odtheking
package com.laybalt.features.impl.floor7.terminals.impl

import com.laybalt.features.impl.floor7.terminals.TerminalClick
import com.laybalt.features.impl.floor7.terminals.TerminalSolver
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import java.awt.Color

object NumberTerminal: Terminal() {
    override val titleRegex = Regex("^Click in order!$")
    override val displayName = "Numbers"
    override val gridSize = 5 to 2
    override val slotCount = 36

    private val slotCounts = mutableMapOf<Int, Int>()

    override fun onSlotUpdate(items: Map<Int, ItemStack>, title: String, updatedSlot: Int, updatedItem: ItemStack): Boolean {
        items.filter { it.value.item == Items.STAINED_GLASS_PANE.red() }
            .toList().sortedBy { it.second.count }
            .forEach { (slot, stack) ->
                slotCounts[slot] = stack.count
                solution.add(TerminalClick(slot))
            }

        return true
    }

    override fun getClickForSlot(slot: Int) = solution.firstOrNull()?.takeIf { it.slotId == slot }

    override fun renderSlot(ctx: GuiGraphicsExtractor, x: Number, y: Number, index: Int, click: TerminalClick, baseColor: Color) {
        if (index > 3) return

        val color = when (index) {
            0 -> TerminalSolver.firstColor.value
            1 -> TerminalSolver.secondColor.value
            2 -> TerminalSolver.thirdColor.value
            else -> TerminalSolver.fourthColor.value
        }

        TerminalSolver.drawSlot(ctx, x, y, color)

        if (TerminalSolver.showNumbers.value) {
            val count = slotCounts[click.slotId] ?: 0
            TerminalSolver.drawCenteredText(ctx, count.toString(), x, y)
        }
    }

    override fun renderVanillaSlot(ctx: GuiGraphicsExtractor, slot: Int, x: Int, y: Int, baseColor: Color): Boolean {
        val index = solution.indexOfFirst { it.slotId == slot }
        if (index !in 0 .. 3) return false
        renderSlot(ctx, x, y, index, solution[index], baseColor)
        return true
    }

    override fun reset() {
        super.reset()
        slotCounts.clear()
    }
}
