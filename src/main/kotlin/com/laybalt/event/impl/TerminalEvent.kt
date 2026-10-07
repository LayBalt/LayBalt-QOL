package com.laybalt.event.impl

import com.laybalt.event.Event
import com.laybalt.features.impl.floor7.terminals.impl.Terminal
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket

abstract class TerminalEvent(): Event(cancelable = false) {
    class Open(val handler: Terminal): TerminalEvent()
    object Close: TerminalEvent()
    class SlotUpdate(val handler: Terminal, val packet: ClientboundContainerSetSlotPacket): TerminalEvent()
    class Break(val handler: Terminal): TerminalEvent()
}