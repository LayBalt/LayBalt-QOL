// Ported from Odin (https://github.com/odtheking/Odin), BSD-3-Clause, Copyright (c) 2025 odtheking
package com.laybalt.features.impl.floor7.terminals.termsim

import com.laybalt.LayBaltQOL.mc
import com.laybalt.event.EventBus
import com.laybalt.event.impl.TerminalActionEvent
import com.laybalt.features.impl.floor7.terminals.TerminalListener
import com.laybalt.features.impl.floor7.terminals.TerminalPBs
import com.laybalt.features.impl.floor7.terminals.TerminalSolver
import com.laybalt.utils.ThreadUtils
import gg.essential.universal.USound
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.SimpleContainer
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.PlayerEquipment
import net.minecraft.world.inventory.ChestMenu
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.inventory.MenuType
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.DyeColor
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

private fun menuTypeFor(size: Int): MenuType<ChestMenu> = when {
    size <= 9 -> MenuType.GENERIC_9x1
    size <= 18 -> MenuType.GENERIC_9x2
    size <= 27 -> MenuType.GENERIC_9x3
    size <= 36 -> MenuType.GENERIC_9x4
    size <= 45 -> MenuType.GENERIC_9x5
    else -> MenuType.GENERIC_9x6
}

private fun fakeInventory() = Inventory(mc.player !!, PlayerEquipment(mc.player !!))

/**
 * A local chest screen that imitates a floor 7 terminal. While it is open it acts as the current terminal
 * for [TerminalListener], so the Terminal Solver, sounds and Terminal Times work in it like on Hypixel.
 * Clicks never reach the server.
 */
open class TermSimGUI(
    val name: String,
    val size: Int,
    private val inv: SimpleContainer = SimpleContainer(size)
): NullSafeSlotScreen(ChestMenu(menuTypeFor(size), 0, fakeInventory(), inv, size / 9), fakeInventory(), Component.literal(name)) {
    protected val blackPane: ItemStack get() = named(ItemStack(Items.STAINED_GLASS_PANE.pick(DyeColor.BLACK)))
    protected val guiInventorySlots: List<Slot> get() = menu.slots.subList(0, size)
    protected var ping = 0L

    fun simMenu(): ChestMenu = menu

    open fun create() {
        setSlots { blackPane }
    }

    fun open(terminalPing: Long = 0L) {
        ping = terminalPing
        mc.setScreenAndShow(this)
        create()
        TerminalListener.openSimulated(this)
    }

    /** Handles a click that already passed the simulated ping. */
    protected open fun slotClick(slot: Slot, button: Int) {
        playTermSimSound()
    }

    /** Called by a simulator once the terminal is done: records the solve and goes back to the start menu. */
    protected fun complete() {
        val handler = TerminalListener.currentHandler
        if (handler != null && TerminalListener.simScreen === this) {
            TerminalPBs.recordSolve(handler, TerminalListener.initialOpenTime, simulated = true, announce = true)
            EventBus.post(TerminalActionEvent.Solve(handler, TerminalListener.initialOpenTime, true))
        }
        StartGUI().open(ping)
    }

    override fun removed() {
        super.removed()
        TerminalListener.simClosed(this)
    }

    override fun onSlotClicked(slot: Slot, slotId: Int, button: Int, clickType: ContainerInput) {
        if (clickType == ContainerInput.PICKUP_ALL) return
        delaySlotClick(slot, if (clickType == ContainerInput.PICKUP && button == 1) 1 else 0)
    }

    /** Entry point for solver/auto clicks routed by [com.laybalt.features.impl.floor7.terminals.TerminalClick]. */
    fun clickIndex(index: Int, button: Int) {
        val slot = guiInventorySlots.getOrNull(index) ?: return
        delaySlotClick(slot, button)
    }

    private fun delaySlotClick(slot: Slot, button: Int) {
        if (this is StartGUI) return slotClick(slot, button)
        if (slot.container !== inv || slot.item.item == Items.STAINED_GLASS_PANE.pick(DyeColor.BLACK)) return
        if (ping <= 0L) return slotClick(slot, button)
        ThreadUtils.scheduledTask((ping / 50).toInt().coerceAtLeast(0)) {
            if (mc.gui.screen() === this) slotClick(slot, button)
        }
    }

    protected fun setSlots(block: (Slot) -> ItemStack) {
        guiInventorySlots.forEach { it.setSlot(block(it)) }
    }

    protected fun Slot.setSlot(stack: ItemStack) {
        menu.setItem(index, menu.incrementStateId(), stack)
        TerminalListener.simSlotUpdated(this@TermSimGUI, index, stack)
    }

    protected fun playTermSimSound() {
        if (! TerminalSolver.replacesSounds) USound.playSoundStatic(SoundEvents.EXPERIENCE_ORB_PICKUP, 1f, 1f)
    }
}

internal fun named(stack: ItemStack, name: String = ""): ItemStack = stack.apply { set(DataComponents.CUSTOM_NAME, Component.literal(name)) }
internal fun pane(color: DyeColor) = named(ItemStack(Items.STAINED_GLASS_PANE.pick(color)))
