// Ported from Odin (https://github.com/odtheking/Odin), BSD-3-Clause, Copyright (c) 2025 odtheking
package com.laybalt.features.impl.floor7.terminals

import com.laybalt.LayBaltQOL.mc
import com.laybalt.event.EventBus
import com.laybalt.event.impl.*
import com.laybalt.features.impl.floor7.terminals.impl.*
import com.laybalt.features.impl.floor7.terminals.termsim.TermSimGUI
import com.laybalt.init.types.ISelfInit
import com.laybalt.utils.ChatUtils
import com.laybalt.utils.ChatUtils.unformattedText
import com.laybalt.utils.ThreadUtils
import com.laybalt.utils.dungeons.DungeonListener
import com.laybalt.utils.location.LocationUtils
import net.minecraft.network.protocol.game.*
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

object TerminalListener: ISelfInit {
    var interactCooldown = 0

    @JvmField var inTerm = false
    var currentHandler: Terminal? = null
    var initialOpenTick = 0L
    var initialOpenTime = 0L

    /**
     * NoammAddons 5dff2334: the First Click Protection time of the open terminal, rolled from its range when the terminal
     * opens (upstream rolls on every check, which leans towards the short end; one roll per terminal keeps it gaussian).
     */
    var firstClickProtMs = 0
        private set

    var currentTitle = ""
    var lastWindowId = - 1
    var currentItems = mutableMapOf<Int, ItemStack>()

    val clickedSlots = mutableListOf<TerminalClick>()
    var lastClick: Long? = null

    /** The terminal simulator screen that currently acts as the open terminal, if any. */
    var simScreen: TermSimGUI? = null
        private set

    /** The last terminal you opened and when (kept after closing; used for the "solved" signal). */
    var lastHandler: Terminal? = null
        private set
    var lastOpenTime = 0L
        private set

    private val termSolvedRegex = Regex("^(.{1,16}) activated a terminal! \\((\\d)/(\\d)\\)$")

    override fun init() {
        EventBus.register<ContainerFullyOpenedEvent> {
            if (LocationUtils.F7Phase != 3) return@register
            val handler = Terminal.fromTitle(event.title.string) ?: return@register reset()
            open(handler, event.title.string, event.windowId, event.items)
        }

        EventBus.register<MainThreadPacketReceivedEvent.Post> {
            when (val packet = event.packet) {
                is ClientboundContainerSetSlotPacket -> {
                    if (! inTerm || simScreen != null || packet.containerId != lastWindowId) return@register
                    val container = mc.player?.containerMenu ?: return@register
                    onSlotUpdate(container, packet)
                }

                is ClientboundContainerClosePacket -> if (inTerm && simScreen == null) ThreadUtils.scheduledTask(1, ::reset)
            }
        }

        EventBus.register<PacketEvent.Sent> {
            fun hit(id: Int) {
                val entity = mc.level?.getEntity(id) as? ArmorStand ?: return
                if (entity.displayName.unformattedText != "Inactive Terminal") return

                if (interactCooldown > 0 || lastWindowId != - 1) event.isCanceled = true else interactCooldown = 15
            }

            when (event.packet) {
                is ServerboundContainerClickPacket -> {
                    if (! inTerm || simScreen != null) return@register
                    val isMelody = currentHandler is MelodyTerminal
                    if ((checkFcDelay() && ! isMelody)) {
                        event.isCanceled = true
                        return@register
                    }

                    if (isMelody) return@register
                    lastClick = System.currentTimeMillis()
                }

                is ServerboundContainerClosePacket -> if (inTerm) reset()

                is ServerboundInteractPacket -> hit(event.packet.entityId)
                is ServerboundAttackPacket -> hit(event.packet.entityId)
            }
        }

        EventBus.register<TickEvent.Server> {
            if (interactCooldown > 0) interactCooldown --

            val click = lastClick ?: return@register
            val handler = currentHandler ?: return@register
            val container = simScreen?.simMenu() ?: mc.player?.containerMenu ?: return@register
            if (System.currentTimeMillis() - click < TerminalSolver.breakTimeout.value) return@register
            ChatUtils.debug("terminal", "TERMINAL BROKE!!!!!!!")

            handler.sync(container)

            clickedSlots.clear()
            lastClick = System.currentTimeMillis()
        }

        EventBus.register<ChatMessageEvent> {
            val match = termSolvedRegex.find(event.unformattedText) ?: return@register
            if (match.groupValues[1] != mc.player?.name?.string) return@register
            val handler = lastHandler ?: return@register
            EventBus.post(TerminalActionEvent.Solve(handler, lastOpenTime, false))
        }

        EventBus.register<WorldChangeEvent> { reset() }
    }

    private fun open(handler: Terminal, title: String, windowId: Int, items: Map<Int, ItemStack>, simulator: TermSimGUI? = null) {
        if (inTerm) reset()

        inTerm = true
        simScreen = simulator
        initialOpenTick = DungeonListener.currentTime
        initialOpenTime = System.currentTimeMillis()
        firstClickProtMs = TerminalSolver.firstClickProt.random().coerceAtLeast(0)
        lastHandler = handler
        lastOpenTime = initialOpenTime
        currentTitle = title
        lastWindowId = windowId
        currentItems.clear()
        currentItems.putAll(items)
        clickedSlots.clear()
        currentHandler = handler

        if (handler is MelodyTerminal) currentItems.forEach { (slot, stack) ->
            handler.solve(currentItems, currentTitle, slot, stack)
        }
        else handler.solve(currentItems, currentTitle, 0, ItemStack.EMPTY)

        EventBus.post(TerminalEvent.Open(handler))
    }

    private fun onSlotUpdate(container: AbstractContainerMenu, packet: ClientboundContainerSetSlotPacket) {
        val handler = currentHandler ?: return
        if (packet.slot !in 0 until handler.slotCount) return
        if (packet.item.`is`(Items.STAINED_GLASS_PANE.black())) return
        // Rubix predictions are not idempotent: once a click was recorded only the prediction is trusted
        // (the break timeout re-syncs). Without recorded clicks (Client Prediction off) the server state is used.
        if (handler is RubixTerminal && clickedSlots.isNotEmpty()) return

        handler.sync(container, packet.slot, packet.item)

        if (handler !is MelodyTerminal) for (slot in clickedSlots) handler.predict(slot)
        EventBus.post(TerminalEvent.SlotUpdate(handler, packet))
        ChatUtils.debug("terminal", "slot updated: ${packet.slot}")
    }

    /** Called by [TermSimGUI] once its slots are filled: the simulator becomes the open terminal. */
    fun openSimulated(screen: TermSimGUI) {
        val handler = Terminal.fromTitle(screen.title.string) ?: return
        val menu = screen.simMenu()
        val items = (0 until screen.size).associateWith { menu.getSlot(it).item }
        open(handler, screen.title.string, menu.containerId, items, screen)
    }

    /** Called by [TermSimGUI] whenever it changes one of its own slots. */
    fun simSlotUpdated(screen: TermSimGUI, slot: Int, stack: ItemStack) {
        if (! inTerm || simScreen !== screen) return
        val menu = screen.simMenu()
        onSlotUpdate(menu, ClientboundContainerSetSlotPacket(menu.containerId, menu.stateId, slot, stack))
    }

    /** Called by [TermSimGUI] when it is removed. */
    fun simClosed(screen: TermSimGUI) {
        if (simScreen === screen) reset()
    }

    fun checkFcDelay(): Boolean {
        val simulated = simScreen != null
        if (simulated && TerminalSimulator.disableFirstClickProt.value) return false
        val isMelody = currentHandler is MelodyTerminal

        val protMs = firstClickProtMs + if (isMelody) 150 else 0
        if (System.currentTimeMillis() - initialOpenTime < protMs) return true
        // Server ticks do not advance in the simulator, so only the time based protection applies there.
        if (simulated || ! TerminalSolver.lagProtection.value) return false

        var delay = TerminalSolver.fcDelay.value
        if (isMelody && delay != 0) delay += 3 // NoammAddons cff93729: 0 ticks turns it off for Melody too
        return DungeonListener.currentTime - initialOpenTick < delay
    }

    private fun Terminal.sync(container: AbstractContainerMenu, slotId: Int = 0, stack: ItemStack = ItemStack.EMPTY) {
        val items = container.items
        items.subList(0, minOf(items.size, slotCount)).forEachIndexed { index, stack ->
            currentItems[index] = stack
        }

        solve(currentItems, currentTitle, slotId, stack)

        EventBus.post(TerminalEvent.Break(this))
    }

    private fun reset() {
        val wasInTerm = inTerm
        inTerm = false
        currentHandler = null
        currentTitle = ""
        currentItems.clear()
        clickedSlots.clear()
        lastWindowId = - 1
        lastClick = null
        initialOpenTick = 0
        initialOpenTime = 0
        firstClickProtMs = 0
        simScreen = null

        Terminal.all.forEach(Terminal::reset)
        if (wasInTerm) EventBus.post(TerminalEvent.Close)
    }
}
