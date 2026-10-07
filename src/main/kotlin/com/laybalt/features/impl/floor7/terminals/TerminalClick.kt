package com.laybalt.features.impl.floor7.terminals

import com.laybalt.LayBaltQOL.mc
import com.laybalt.event.EventBus
import com.laybalt.event.impl.TerminalActionEvent
import com.laybalt.utils.ChatUtils
import com.laybalt.utils.Utils.send
import com.google.common.primitives.Shorts
import com.google.common.primitives.SignedBytes
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import net.minecraft.network.HashedStack
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.item.ItemStack

data class TerminalClick(val slotId: Int, val btn: Int = 0) {
    /**
     * Sends this click to the server, or to the open terminal simulator.
     *
     * @param record whether the click is remembered so the client prediction can be replayed on top of
     * the next server update (false when "Client Prediction" is turned off).
     */
    fun send(record: Boolean = true) {
        val handler = TerminalListener.currentHandler
        val simulator = TerminalListener.simScreen

        handler?.onSlotClick(slotId)
        if (simulator != null) simulator.clickIndex(slotId, if (btn == 0) 0 else 1)
        else {
            val connection = mc.connection ?: return
            val stateId = mc.player?.containerMenu?.stateId ?: return
            val windowId = TerminalListener.lastWindowId.takeUnless { it == - 1 } ?: return

            ServerboundContainerClickPacket(
                windowId, stateId,
                Shorts.checkedCast(slotId.toLong()),
                SignedBytes.checkedCast((if (btn == 0) 2 else btn).toLong()),
                if (btn == 0) ContainerInput.CLONE else ContainerInput.PICKUP,
                Int2ObjectOpenHashMap(),
                HashedStack.create(ItemStack.EMPTY, connection.decoratedHashOpsGenenerator())
            ).send()
        }

        ChatUtils.debug("terminal", "Clicked $slotId on ${handler?.displayName}${if (simulator != null) " (termsim)" else ""}")

        if (record) TerminalListener.clickedSlots.add(this)
        if (handler != null) EventBus.post(TerminalActionEvent.Click(handler, this, simulator != null))
    }
}
