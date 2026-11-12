// Page keybinds ported from Odin (https://github.com/odtheking/Odin), BSD-3-Clause, Copyright (c) 2025 odtheking
package com.laybalt.features.impl.general

import com.laybalt.config.types.KeybindSetting
import com.laybalt.config.types.MultiCheckboxSetting
import com.laybalt.config.types.ToggleSetting
import com.laybalt.event.impl.*
import com.laybalt.features.Feature
import com.laybalt.mixin.IKeyMapping
import com.laybalt.utils.*
import com.laybalt.utils.ChatUtils.unformattedText
import com.laybalt.utils.items.ItemUtils.lore
import com.mojang.blaze3d.platform.InputConstants
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.protocol.game.*
import net.minecraft.world.inventory.Slot

object LoadoutKeybinds: Feature("Allows you to bind loadout slots to your keyboard/mouse.") {
    private val blockBarrierClick by ToggleSetting("Block Barrier Click")
    //#if CHEAT
    private val closeAfterUse by ToggleSetting("Auto Close On Use")
    private val keepOpenSlots by MultiCheckboxSetting("Keep Open On", (1 .. 12).associate { "Slot $it" to false }.toMutableMap())
        .withDescription("Loadout slots that won't auto close the menu").showIf { closeAfterUse.value }
    //#endif
    private val nextPageKey by KeybindSetting("Next Page", InputConstants.KEY_RIGHT).withDescription("Goes to the next loadout page (if there is one) while the Loadouts menu is open.").section("Pages")
    private val previousPageKey by KeybindSetting("Previous Page", InputConstants.KEY_LEFT).withDescription("Goes to the previous loadout page (if there is one) while the Loadouts menu is open.")

    private val useHotbarBinds by ToggleSetting("Use Hotbar Binds").withDescription("Uses your vanilla hotbar keys (1-9) instead of the Loadout Slot keybinds below.").section("Loadout Slots")
    private val keybinds = (1 .. 12).mapIndexed { index, slot ->
        KeybindSetting("Loadout Slot $slot", when (index) {
            in 0 .. 8 -> InputConstants.KEY_1 + index
            9 -> InputConstants.KEY_0
            10 -> InputConstants.KEY_MINUS
            11 -> InputConstants.KEY_EQUALS
            else -> InputConstants.UNKNOWN.value
        }).hideIf { useHotbarBinds.value }.apply(configSettings::add)
    }

    private val loadoutMenuRegex = Regex("""^\((\d+)/(\d+)\) Loadouts?$""")
    private var lastClick = System.currentTimeMillis()
    private var inLoadoutMenu = false
    //#if CHEAT
    private var pendingAutoClose = false
    //#endif
    private val slots = listOf(
        14, 15, 16,
        23, 24, 25,
        32, 33, 34,
        41, 42, 43
    )

    /**
     * The hotbar slot bound to [code], read live so rebinding takes effect without a restart.
     * An unbound hotbar key (InputConstants.UNKNOWN) never matches, and a mouse bind never matches a keyboard code (Odin d2d267cd).
     */
    private fun hotbarIndex(code: Int, mouse: Boolean): Int? {
        val type = if (mouse) InputConstants.Type.MOUSE else InputConstants.Type.KEYSYM
        return mc.options.keyHotbarSlots.indexOfFirst {
            val key = (it as IKeyMapping).key
            key != InputConstants.UNKNOWN && key.type == type && key.value == code
        }.takeUnless { it == - 1 }
    }

    override fun init() {
        register<MainThreadPacketReceivedEvent.Pre> {
            if (event.packet is ClientboundOpenScreenPacket) inLoadoutMenu = event.packet.title.unformattedText.matches(loadoutMenuRegex)
            else if (event.packet is ClientboundContainerClosePacket && inLoadoutMenu) inLoadoutMenu = false
        }

        register<PacketEvent.Sent> {
            if (! inLoadoutMenu) return@register
            if (event.packet !is ServerboundContainerClosePacket) return@register
            inLoadoutMenu = false
            //#if CHEAT
            pendingAutoClose = false
            //#endif
        }

        //#if CHEAT
        register<MainThreadPacketReceivedEvent.Post> {
            if (! pendingAutoClose) return@register
            val packet = event.packet as? ClientboundOpenScreenPacket ?: return@register
            if (! packet.title.unformattedText.matches(loadoutMenuRegex)) return@register

            player.closeContainer()
            pendingAutoClose = false
        }
        //#endif

        register<ContainerEvent.Keyboard> {
            if (! inLoadoutMenu) return@register
            if (System.currentTimeMillis() - lastClick < 300) return@register
            if (event.key.equalsOneOf(InputConstants.KEY_ESCAPE, KeyMappingHelper.getBoundKeyOf(mc.options.keyInventory).value)) return@register
            if (handlePageKey(event.screen, event.key, mouse = false)) return@register event.cancel()
            val index = if (useHotbarBinds.value) hotbarIndex(event.key, mouse = false) ?: return@register
            else keybinds.indexOfFirst(KeybindSetting::isDown).takeUnless { it == - 1 } ?: return@register
            event.isCanceled = true
            click(index)
        }

        register<ContainerEvent.MouseClick> {
            if (! inLoadoutMenu) return@register
            if (System.currentTimeMillis() - lastClick < 300) return@register
            if (event.button.equalsOneOf(InputConstants.MOUSE_BUTTON_LEFT, InputConstants.MOUSE_BUTTON_RIGHT)) return@register
            if (handlePageKey(event.screen, event.button, mouse = true)) return@register event.cancel()
            val index = if (useHotbarBinds.value) hotbarIndex(event.button, mouse = true) ?: return@register
            else keybinds.indexOfFirst { it.matches(event.button, mouse = true) }.takeUnless { it == - 1 } ?: return@register
            event.isCanceled = true
            click(index)
        }

        register<ContainerEvent.SlotClick> {
            if (! blockBarrierClick.value) return@register
            if (! inLoadoutMenu) return@register
            if (event.slotId != 49) return@register
            event.isCanceled = true
        }
    }

    /**
     * Next / previous page keys (Odin LoadoutKeybinds): the page is read from the "(current/total) Loadouts" title,
     * next page is the arrow in slot 44 and previous page the arrow in slot 17.
     */
    private fun handlePageKey(screen: AbstractContainerScreen<*>, code: Int, mouse: Boolean): Boolean {
        val next = nextPageKey.matches(code, mouse)
        if (! next && ! previousPageKey.matches(code, mouse)) return false
        val match = loadoutMenuRegex.find(screen.title.unformattedText) ?: return false
        val current = match.groupValues[1].toIntOrNull() ?: return false
        val total = match.groupValues[2].toIntOrNull() ?: return false

        val slot = when {
            next && current < total -> 44
            ! next && current > 1 -> 17
            else -> return true
        }

        GuiUtils.clickSlot(slot, GuiUtils.ButtonType.LEFT)
        lastClick = System.currentTimeMillis()
        return true
    }

    private fun click(index: Int) {
        val slot = slots[index].takeIf(::isSlotEquipable) ?: return
        GuiUtils.clickSlot(slot, GuiUtils.ButtonType.LEFT)

        lastClick = System.currentTimeMillis()
        //#if CHEAT
        if (closeAfterUse.value && ! keepOpenSlots["Slot ${index + 1}"]) {
            player.closeContainer()
            ThreadUtils.setTimeout(3000) { pendingAutoClose = false }
            pendingAutoClose = true
        }
        //#endif
    }

    private fun isSlotEquipable(slot: Int) = player.containerMenu.getSlot(slot).takeIf(Slot::hasItem)?.item?.lore?.any { it.contains("Left-click to equip!") } ?: false
}