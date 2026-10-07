// Ported from Odin (https://github.com/odtheking/Odin), BSD-3-Clause, Copyright (c) 2025 odtheking
package com.laybalt.features.impl.kuudra

import com.laybalt.config.types.ToggleSetting
import com.laybalt.event.impl.ContainerEvent
import com.laybalt.features.Feature
import com.laybalt.utils.ChatUtils.removeFormatting
import com.laybalt.utils.containsOneOf
import com.laybalt.utils.equalsOneOf

object RemovePerks: Feature("Hides useless perks in Kuudra's Perk Menu and blocks clicks on them, so you can't buy them by accident.") {
    private val renderStun by ToggleSetting("Show Stun", false)
        .withDescription("Keeps the stun role perk (Human Cannonball) visible and clickable. Turn this on if you are the stunner.")

    private const val PERK_MENU = "Perk Menu"

    override fun init() {
        register<ContainerEvent.Render.Slot.Pre> {
            if (! isPerkMenu(event.screen.title.string)) return@register
            if (isRemoved(event.slot.item.hoverName.string)) event.isCanceled = true
        }

        register<ContainerEvent.SlotClick> {
            if (! isPerkMenu(event.screen.title.string)) return@register
            val slot = event.screen.menu.slots.getOrNull(event.slotId) ?: return@register
            if (isRemoved(slot.item.hoverName.string)) event.isCanceled = true
        }
    }

    private fun isPerkMenu(title: String) = title.removeFormatting() == PERK_MENU

    private fun isRemoved(rawName: String): Boolean {
        val name = rawName.removeFormatting()
        if (name.isEmpty()) return false
        return name.containsOneOf("Steady Hands", "Bomberman", "Mining Frenzy") ||
            name.equalsOneOf("Elle's Lava Rod", "Elle's Pickaxe", "Auto Revive") ||
            (! renderStun.value && name.contains("Human Cannonball"))
    }
}
