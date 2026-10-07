// Ported from OdinClient (https://github.com/skies-starred/OdinClient), BSD-3-Clause, Copyright (c) 2026 Starred
package com.laybalt.features.impl.general

//#if CHEAT

import com.laybalt.LayBaltQOL
import com.laybalt.config.types.KeybindSetting
import com.laybalt.config.types.SliderSetting
import com.laybalt.event.EventBus
import com.laybalt.event.impl.KeyboardEvent
import com.laybalt.event.impl.MainThreadPacketReceivedEvent
import com.laybalt.event.impl.MouseClickEvent
import com.laybalt.event.impl.PacketEvent
import com.laybalt.event.impl.TickEvent
import com.laybalt.event.impl.WorldChangeEvent
import com.laybalt.features.Feature
import com.laybalt.utils.ChatUtils
import com.laybalt.utils.location.LocationUtils
import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.protocol.game.ClientboundContainerClosePacket
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket
import net.minecraft.world.inventory.ChestMenu
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.inventory.MenuType
import net.minecraft.world.item.Items

/**
 * Plays Melody's Harp ("Harp - <song>" menus). Replaces the old LayBalt Auto Melody.
 *
 * The note to hit is the quartz block in the bottom note row (slots 37..43). Instead of a fixed-delay poll (which
 * double-clicked a note that stayed for several ticks) the row is hashed every tick and a note is clicked once per
 * board change, like OdinClient. Unlike the old Auto Melody it does not need a hard-coded song list.
 */
object AutoHarp: Feature("Plays the Melody's Harp minigame for you.") {
    private val minClickDelay by SliderSetting("Min Click Delay", 0, 0, 300, 5, "ms")
        .withDescription("Extra minimum time between two notes (from the old Auto Melody delay). 0 = react instantly to each new note; a note that arrives earlier is clicked as soon as the delay has passed.")
    private val toggleKey by KeybindSetting("Toggle Key")
        .withDescription("Turns Auto Harp on or off. Works in game and inside the harp menu. Unbound by default.")

    private val NOTE_ROW = 37 .. 43

    private var inHarp = false
    private var hash = 0
    private var pendingSlot = - 1
    private var lastClick = 0L
    private var lastSeenScreen: AbstractContainerScreen<*>? = null

    override fun init() {
        // The toggle key must also turn the feature ON, so it is registered globally (not feature-scoped).
        EventBus.register<KeyboardEvent.KeyPressed> {
            if (event.action != InputConstants.PRESS || ! canToggleHere()) return@register
            if (! toggleKey.matches(event.keyEvent.key, mouse = false)) return@register
            toggle()
            event.isCanceled = true
        }

        EventBus.register<MouseClickEvent> {
            if (event.action != InputConstants.PRESS || ! canToggleHere()) return@register
            if (! toggleKey.matches(event.button, mouse = true)) return@register
            toggle()
            event.isCanceled = true
        }

        register<MainThreadPacketReceivedEvent.Post> {
            when (val packet = event.packet) {
                is ClientboundOpenScreenPacket -> {
                    inHarp = LocationUtils.inSkyblock && packet.title.string.startsWith("Harp - ") && packet.type == MenuType.GENERIC_9x6
                    reset()
                }

                is ClientboundContainerClosePacket -> inHarp = false
            }
        }

        register<PacketEvent.Sent> { if (event.packet is ServerboundContainerClosePacket) inHarp = false }

        register<WorldChangeEvent> { inHarp = false }

        register<TickEvent.Start> {
            val screen = mc.gui.screen() as? AbstractContainerScreen<*> ?: return@register
            if (! inHarp) {
                // Enabled while the harp was already open (its open packet was missed): detect it once per screen.
                if (screen === lastSeenScreen) return@register
                lastSeenScreen = screen
                inHarp = LocationUtils.inSkyblock && screen.title.string.startsWith("Harp - ") && (screen.menu as? ChestMenu)?.rowCount == 6
                if (! inHarp) return@register
                reset()
            }
            lastSeenScreen = screen
            val slots = screen.menu.slots

            var newHash = 0
            var slot = - 1
            for (i in NOTE_ROW) {
                val quartz = slots.getOrNull(i)?.item?.item == Items.QUARTZ_BLOCK
                newHash = (newHash shl 1) or if (quartz) 1 else 0
                if (slot == - 1 && quartz) slot = i
            }

            if (newHash != hash) {
                hash = newHash
                pendingSlot = slot
            }

            if (pendingSlot == - 1) return@register
            val now = System.currentTimeMillis()
            if (now - lastClick < minClickDelay.value) return@register
            // The note may have moved on while waiting for the delay.
            if (slots.getOrNull(pendingSlot)?.item?.item != Items.QUARTZ_BLOCK) return@register run { pendingSlot = - 1 }

            val player = mc.player ?: return@register
            mc.gameMode?.handleContainerInput(screen.menu.containerId, pendingSlot, 0, ContainerInput.CLONE, player)
            lastClick = now
            pendingSlot = - 1
        }
    }

    override fun toggle() {
        super.toggle()
        if (mc.player != null && LayBaltQOL.isLoaded) ChatUtils.modMessage("§7AutoHarp is now ${if (enabled) "§aon" else "§coff"}")
    }

    override fun onDisable() {
        super.onDisable()
        inHarp = false
        reset()
    }

    private fun reset() {
        hash = 0
        pendingSlot = - 1
    }

    private fun canToggleHere(): Boolean {
        val screen = mc.gui.screen() ?: return true
        return screen is AbstractContainerScreen<*> && screen.title.string.startsWith("Harp - ")
    }
}

//#endif
