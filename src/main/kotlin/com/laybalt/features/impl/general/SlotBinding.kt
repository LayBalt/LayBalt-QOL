// Profiles, line width, shift display mode and chat feedback ported from Odin SlotBinds
// (https://github.com/odtheking/Odin), BSD-3-Clause, Copyright (c) 2025 odtheking
package com.laybalt.features.impl.general

import com.laybalt.config.PogObject
import com.laybalt.config.types.*
import com.laybalt.event.impl.ContainerEvent
import com.laybalt.features.Feature
import com.laybalt.mixin.IAbstractContainerScreen
import com.laybalt.utils.ChatUtils
import com.laybalt.utils.GuiUtils
import com.laybalt.utils.render.Render2D.drawBorder
import com.laybalt.utils.render.Render2D.drawLine
import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.gui.screens.inventory.InventoryScreen
import net.minecraft.world.inventory.ContainerInput
import java.awt.Color

object SlotBinding: Feature("Allows you to bind slots to hotbar slots for quick item swaps.") {
    private val profileNames = (1 .. 6).map { "Profile $it" }

    private val bindKey by KeybindSetting("Binding key", InputConstants.KEY_R).section("Keybind").withDescription("Press while hovering a hotbar slot and an inventory slot to link them.")
    private val profile by DropdownSetting("Profile", 0, profileNames).withDescription("Which set of slot binds is active. Every profile keeps its own binds, so you can switch between setups.")
    private val chatFeedback by ToggleSetting("Chat Feedback", true).withDescription("Sends a chat message when a bind is added or removed, or when a bind is invalid.")

    private val showBoundSlots by ToggleSetting("Show Bound Slots", true).section("Rendering")
    private val neuStyle by ToggleSetting("Hover Only", false).withDescription("Only shows bound slots when hovering over a them.").showIf { showBoundSlots.value }
    private val requireShift by ToggleSetting("Require Shift", false).withDescription("Only shows bound slots while Shift is held. Together with Hover Only this is the \"On Hover + Shift\" mode.").showIf { showBoundSlots.value }
    private val drawBorders by ToggleSetting("Draw Border", true).showIf { showBoundSlots.value }
    private val drawLines by ToggleSetting("Draw Line", true).showIf { showBoundSlots.value }
    private val lineWidth by SliderSetting("Line Width", 1f, 0.1f, 3f, 0.1f).withDescription("Thickness of the line drawn between bound slots.").showIf { showBoundSlots.value && drawLines.value }
    private val borderColor by ColorSetting("Border Color", Color.PINK, false).showIf { showBoundSlots.value && drawBorders.value }.section("Colors")
    private val lineColor by ColorSetting("Line Color", Color.WHITE, false).showIf { showBoundSlots.value && drawLines.value }

    /** Profile 1 stays in the original file, so binds made before profiles existed are kept. */
    private val legacyBinds = PogObject("slotbindings", mutableMapOf<Int, Int>())

    /** Profiles 2-6, keyed by profile name. */
    private val profileBinds = PogObject("slotbindings_profiles", mutableMapOf<String, MutableMap<Int, Int>>())
    private var previousSlot: Int? = null

    private val profileName get() = profileNames.getOrElse(profile.value) { profileNames[0] }

    private val binds: MutableMap<Int, Int>
        get() = if (profile.value <= 0) legacyBinds.get()
        else profileBinds.get().getOrPut(profileName) { mutableMapOf() }

    private fun save() = if (profile.value <= 0) legacyBinds.save() else profileBinds.save()

    /**
     * Merges slot binds imported from another mod (used by [com.laybalt.config.ForeignConfigImport]) into profile
     * [profileIndex] (0 = Profile 1). Each pair is normalised to inventory slot -> hotbar slot; pairs that do not link
     * exactly one hotbar slot are ignored, and an inventory slot that already has a bind keeps it.
     * @return the number of binds added.
     */
    fun importBinds(profileIndex: Int, pairs: Map<Int, Int>): Int {
        val name = profileNames.getOrNull(profileIndex) ?: return 0
        val target = if (profileIndex == 0) legacyBinds.get() else profileBinds.get().getOrPut(name) { mutableMapOf() }
        var added = 0
        for ((a, b) in pairs) {
            if (! isBindable(a) || ! isBindable(b) || isHotbar(a) == isHotbar(b)) continue
            val inv = if (isHotbar(a)) b else a
            val hb = if (isHotbar(a)) a else b
            if (inv in target) continue
            target[inv] = hb
            added ++
        }
        if (added > 0) if (profileIndex == 0) legacyBinds.save() else profileBinds.save()
        return added
    }

    private fun feedback(msg: String) {
        if (chatFeedback.value) ChatUtils.modMessage(msg)
    }

    private fun isBindable(slot: Int) = slot in 5 .. 44
    private fun isHotbar(slot: Int) = slot in 36 .. 44

    override fun init() {
        register<ContainerEvent.Keyboard> {
            val screen = event.screen as? InventoryScreen ?: return@register
            if (! bindKey.matches(event.key, mouse = false)) return@register
            val slotId = (screen as IAbstractContainerScreen).hoveredSlot?.index?.takeIf(::isBindable) ?: return@register

            event.isCanceled = true
            bindSlot(slotId)
        }

        register<ContainerEvent.MouseClick> {
            val screen = event.screen as? InventoryScreen ?: return@register
            val slotId = (screen as IAbstractContainerScreen).hoveredSlot?.index ?: return@register

            if (bindKey.matches(event.button, mouse = true)) {
                if (! isBindable(slotId)) return@register
                event.isCanceled = true
                bindSlot(slotId)
                return@register
            }

            val binds = binds
            val isShiftDown = (event.modifiers and InputConstants.MOD_SHIFT) != 0
            if (! isShiftDown || event.button != InputConstants.MOUSE_BUTTON_LEFT) return@register

            val boundPartner = binds[slotId] ?: binds.entries.find { it.value == slotId }?.key ?: return@register
            event.isCanceled = true

            val hotbarIndex = if (isHotbar(slotId)) slotId - 36 else boundPartner - 36
            val inventorySlot = if (isHotbar(slotId)) boundPartner else slotId

            gameMode.handleContainerInput(player.containerMenu.containerId, inventorySlot, hotbarIndex, ContainerInput.SWAP, player)
        }

        register<ContainerEvent.Close> { previousSlot = null }
    }

    private fun bindSlot(slotId: Int) {
        val binds = binds
        val currentPrev = previousSlot

        if (currentPrev != null) {
            previousSlot = null
            if (currentPrev == slotId) return feedback("&cYou can't bind a slot to itself.")

            val firstIsHb = isHotbar(currentPrev)
            val secondIsHb = isHotbar(slotId)
            if (firstIsHb == secondIsHb) return feedback("&cExactly one of the two slots must be in the hotbar.")

            val inv = if (firstIsHb) slotId else currentPrev
            val hb = if (firstIsHb) currentPrev else slotId
            binds[inv] = hb
            save()
            feedback("&aBound slot &b$inv &ato hotbar slot &d${hb - 35} &7($profileName).")
        }
        else {
            if (slotId in binds) {
                val hb = binds.remove(slotId) ?: return
                save()
                feedback("&cRemoved bind from slot &b$slotId &cto hotbar slot &d${hb - 35} &7($profileName).")
            }
            else if (binds.values.contains(slotId)) {
                binds.entries.removeIf { it.value == slotId }
                save()
                feedback("&cRemoved all binds to hotbar slot &d${slotId - 35} &7($profileName).")
            }
            else previousSlot = slotId
        }
    }

    private fun isShiftHeld() = InputConstants.isKeyDown(mc.window, InputConstants.KEY_LSHIFT) || InputConstants.isKeyDown(mc.window, InputConstants.KEY_RSHIFT)

    @JvmStatic
    fun drawSlotBinding(context: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, screen: AbstractContainerScreen<*>) {
        if (! enabled) return
        if (screen !is InventoryScreen) return
        val hoveredSlot = (screen as IAbstractContainerScreen).hoveredSlot?.index
        val binds = binds

        if (showBoundSlots.value && (! requireShift.value || isShiftHeld())) binds.forEach { (inv, hb) ->
            if (neuStyle.value && (hoveredSlot != inv && hoveredSlot != hb)) return@forEach

            val p1 = GuiUtils.getSlotPos(screen, inv) ?: return@forEach
            val p2 = GuiUtils.getSlotPos(screen, hb) ?: return@forEach

            if (drawLines.value) context.drawLine(
                p1.first + 8, p1.second + 8,
                p2.first + 8, p2.second + 8,
                lineColor.value, lineWidth.value
            )

            if (drawBorders.value) {
                context.drawBorder(p1.first.toInt(), p1.second.toInt(), 16, 16, borderColor.value)
                context.drawBorder(p2.first.toInt(), p2.second.toInt(), 16, 16, borderColor.value)
            }
        }

        previousSlot?.let {
            val p = GuiUtils.getSlotPos(screen, it) ?: return@let
            context.drawLine(p.first + 8, p.second + 8, mouseX, mouseY, lineColor.value, lineWidth.value)
        }
    }
}
