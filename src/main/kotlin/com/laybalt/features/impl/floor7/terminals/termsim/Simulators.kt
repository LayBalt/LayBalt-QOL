// Ported from Odin (https://github.com/odtheking/Odin), BSD-3-Clause, Copyright (c) 2025 odtheking
package com.laybalt.features.impl.floor7.terminals.termsim

import com.laybalt.features.impl.floor7.terminals.impl.*
import com.laybalt.utils.ChatUtils
import com.laybalt.utils.items.ItemUtils.hasGlint
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.DyeColor
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.StainedGlassPaneBlock

/** The terminal types the simulator can open. */
enum class SimType(val label: String, val terminal: Terminal, val factory: () -> TermSimGUI) {
    PANES("Correct all the panes!", RedGreenTerminal, ::PanesSim),
    RUBIX("Change all to same color!", RubixTerminal, ::RubixSim),
    NUMBERS("Click in order!", NumberTerminal, ::NumbersSim),
    STARTS_WITH("What starts with: '*'?", StartWithTerminal, { StartsWithSim() }),
    SELECT("Select all the * items!", ColorsTerminal, { SelectAllSim() }),
    MELODY("Click the button on time!", MelodyTerminal, ::MelodySim);

    val commandName = name.lowercase().replace("_", "")
    val pbName get() = terminal.displayName

    companion object {
        fun random() = listOf(PANES, RUBIX, NUMBERS, STARTS_WITH, SELECT).random()
    }
}

/** Red-Green ("Correct all the panes!"). */
class PanesSim: TermSimGUI("Correct all the panes!", 45) {
    override fun create() = setSlots {
        if (it.index / 9 in 1 .. 3 && it.index % 9 in 2 .. 6) pane(if (Math.random() > 0.75) DyeColor.LIME else DyeColor.RED)
        else blackPane
    }

    override fun slotClick(slot: Slot, button: Int) {
        slot.setSlot(pane(if (slot.item.item == Items.STAINED_GLASS_PANE.pick(DyeColor.RED)) DyeColor.LIME else DyeColor.RED))
        if (guiInventorySlots.none { it.item.item == Items.STAINED_GLASS_PANE.pick(DyeColor.RED) }) return complete()
        super.slotClick(slot, button)
    }
}

/** Rubix ("Change all to same color!"): left click advances the colour, right click goes back. */
class RubixSim: TermSimGUI("Change all to same color!", 45) {
    private val order = listOf(DyeColor.ORANGE, DyeColor.YELLOW, DyeColor.GREEN, DyeColor.BLUE, DyeColor.RED)
    private val indices = listOf(12, 13, 14, 21, 22, 23, 30, 31, 32)

    override fun create() = setSlots {
        if (it.index / 9 in 1 .. 3 && it.index % 9 in 3 .. 5) pane(order.random())
        else blackPane
    }

    override fun slotClick(slot: Slot, button: Int) {
        val color = ((slot.item.item as? BlockItem)?.block as? StainedGlassPaneBlock)?.color ?: return
        val current = order.indexOf(color).takeIf { it != - 1 } ?: return
        val next = if (button == 1) (current - 1 + order.size) % order.size else (current + 1) % order.size
        slot.setSlot(pane(order[next]))

        if (indices.all { guiInventorySlots[it].item.item == guiInventorySlots[12].item.item }) return complete()
        super.slotClick(slot, button)
    }
}

/** Numbers ("Click in order!"). */
class NumbersSim: TermSimGUI("Click in order!", 36) {
    override fun create() {
        val numbers = (1 .. 14).shuffled().toMutableList()
        setSlots {
            if (it.index / 9 in 1 .. 2 && it.index % 9 in 1 .. 7) {
                val number = numbers.removeFirst()
                named(ItemStack(Items.STAINED_GLASS_PANE.pick(DyeColor.RED), number), "§a$number")
            }
            else blackPane
        }
    }

    override fun slotClick(slot: Slot, button: Int) {
        val red = Items.STAINED_GLASS_PANE.pick(DyeColor.RED)
        if (guiInventorySlots.minByOrNull { if (it.item.item == red) it.item.count else 1000 } != slot) return
        slot.setSlot(named(ItemStack(Items.STAINED_GLASS_PANE.pick(DyeColor.LIME), slot.item.count)))

        if (guiInventorySlots.none { it.item.item == red }) return complete()
        super.slotClick(slot, button)
    }
}

/** Starts With ("What starts with: 'X'?"). */
class StartsWithSim(private val letter: String = listOf("A", "B", "C", "G", "D", "M", "N", "R", "S", "T", "W").random()): TermSimGUI("What starts with: '$letter'?", 45) {
    private val clickedOverrides = mutableListOf<Int>()

    override fun create() {
        val guaranteed = (10 .. 16).random()
        setSlots {
            when {
                it.index / 9 !in 1 .. 3 || it.index % 9 !in 1 .. 7 -> blackPane
                it.index == guaranteed -> letterItem()
                Math.random() > 0.7 -> letterItem()
                else -> letterItem(true)
            }
        }
    }

    override fun slotClick(slot: Slot, button: Int) {
        if (! slot.item.hoverName.string.startsWith(letter, true)) return ChatUtils.modMessage("§cThat item does not start with: '$letter'!")
        if (slot.item.hasRealGlint() || slot.index in clickedOverrides) return ChatUtils.modMessage("§cAlready selected!")

        if (slot.item.item in enchantOverrides) clickedOverrides.add(slot.index)
        slot.setSlot(slot.item.copy().apply { set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true) })

        if (guiInventorySlots.all { ! it.item.hoverName.string.startsWith(letter, true) || it.item.hasRealGlint() || it.index in clickedOverrides }) return complete()
        super.slotClick(slot, button)
    }

    private fun letterItem(other: Boolean = false): ItemStack {
        val item = BuiltInRegistries.ITEM.filter { item ->
            val id = BuiltInRegistries.ITEM.getKey(item).path
            id.startsWith(letter, true) != other && ! id.contains("pane", true) && item != Items.AIR
        }.randomOrNull() ?: return ItemStack.EMPTY
        return ItemStack(item)
    }

    private fun ItemStack.hasRealGlint() = hasGlint() && item !in enchantOverrides

    private companion object {
        val enchantOverrides by lazy { BuiltInRegistries.ITEM.filter { it.components().has(DataComponents.ENCHANTMENT_GLINT_OVERRIDE) } + Items.GOLDEN_APPLE }
    }
}

/** Colors ("Select all the X items!"). */
class SelectAllSim(private val color: DyeColor = DyeColor.entries.random()): TermSimGUI(
    "Select all the ${color.name.replace("LIGHT_GRAY", "SILVER").replace("_", " ")} items!", 54
) {
    override fun create() {
        val guaranteed = ((10 .. 16) + (19 .. 25) + (28 .. 34) + (37 .. 43)).random()
        setSlots { slot ->
            if (slot.index / 9 in 1 .. 4 && slot.index % 9 in 1 .. 7) {
                if (slot.index == guaranteed || Math.random() > 0.75) ItemStack(possibleItems(color).random())
                else ItemStack(possibleItems(DyeColor.entries.filter { it != color }.random()).random())
            }
            else blackPane
        }
    }

    override fun slotClick(slot: Slot, button: Int) {
        val possible = possibleItems(color)
        if (slot.item.item !in possible) return ChatUtils.modMessage("§cThat item is not: ${color.name.uppercase()}!")
        if (slot.item.hasGlint()) return ChatUtils.modMessage("§cAlready selected!")

        slot.setSlot(slot.item.copy().apply { set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true) })

        if (guiInventorySlots.none { ! it.item.hasGlint() && it.item.item in possible }) return complete()
        super.slotClick(slot, button)
    }

    private fun possibleItems(color: DyeColor): List<Item> {
        fun item(path: String) = BuiltInRegistries.ITEM.get(Identifier.fromNamespaceAndPath("minecraft", path)).get().value()
        val name = color.name.lowercase()
        return listOf(
            item("${name}_stained_glass"),
            item("${name}_wool"),
            item("${name}_concrete"),
            when (color) {
                DyeColor.WHITE -> Items.BONE_MEAL
                DyeColor.BLUE -> Items.LAPIS_LAZULI
                DyeColor.BLACK -> Items.INK_SAC
                DyeColor.BROWN -> Items.COCOA_BEANS
                else -> item("${name}_dye")
            }
        )
    }
}

/** Melody ("Click the button on time!"): click the row's clay when the green pane is in the purple column. */
class MelodySim: TermSimGUI("Click the button on time!", 54) {
    private var magentaColumn = 1
    private var limeColumn = 1
    private var currentRow = 1
    private var limeDirection = 1
    private var counter = 0

    override fun create() {
        currentRow = 1
        magentaColumn = (1 .. 5).random()
        limeColumn = 1
        limeDirection = 1
        setSlots { it.generateItemStack() }
    }

    override fun containerTick() {
        super.containerTick()
        if (counter ++ % 10 != 0 || currentRow > 4) return
        limeColumn += limeDirection
        if (limeColumn == 1 || limeColumn == 5) limeDirection *= - 1
        updateRow(currentRow)
    }

    override fun slotClick(slot: Slot, button: Int) {
        if (slot.index % 9 != 7 || limeColumn != magentaColumn || slot.index / 9 != currentRow) return

        val oldRow = currentRow
        magentaColumn = (1 until 5).random()
        currentRow ++
        updateRow(0)
        updateRow(5)
        updateRow(oldRow)
        if (currentRow <= 4) updateRow(currentRow)

        if (currentRow >= 5) return complete()
        super.slotClick(slot, button)
    }

    private fun updateRow(row: Int) {
        for (col in 0 .. 8) guiInventorySlots[row * 9 + col].let { it.setSlot(it.generateItemStack()) }
    }

    private fun Slot.generateItemStack(): ItemStack {
        val row = index / 9
        val col = index % 9
        return when {
            col == magentaColumn && row !in 1 .. 4 -> pane(DyeColor.MAGENTA)
            col == limeColumn && row == currentRow -> pane(DyeColor.LIME)
            col in 1 .. 5 && row == currentRow -> pane(DyeColor.RED)
            col == 7 && row == currentRow -> named(ItemStack(Items.DYED_TERRACOTTA.pick(DyeColor.LIME)))
            col == 7 && row in 1 .. 4 -> named(ItemStack(Items.DYED_TERRACOTTA.pick(DyeColor.RED)))
            col in 1 .. 5 && row in 1 .. 4 -> pane(DyeColor.WHITE)
            else -> blackPane
        }
    }
}
