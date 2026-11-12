// Ported from Odin (https://github.com/odtheking/Odin), BSD-3-Clause, Copyright (c) 2025 odtheking
package com.laybalt.features.impl.render

import com.laybalt.config.types.SliderSetting
import com.laybalt.config.types.ToggleSetting
import com.laybalt.event.impl.CheckEntityRenderEvent
import com.laybalt.features.Feature
import com.laybalt.utils.PartyUtils
import com.laybalt.utils.location.LocationUtils
import net.minecraft.world.entity.player.Player

/** Stops other real players (UUID version 4, so not NPCs) near you from being rendered. */
object HidePlayers: Feature("Hides players in your vicinity.") {
    private val onlyInDungeons by ToggleSetting("Only in Dungeons")
        .withDescription("Only hides players while you are in a dungeon")
    private val hideAll by ToggleSetting("Hide all")
        .withDescription("Hides every other player, no matter how far away they are")
    private val distance by SliderSetting("Distance", 3f, 0f, 32f, 0.5f, " blocks").showIf { ! hideAll.value }
        .withDescription("Players closer than this many blocks are hidden")
    private val keepParty by ToggleSetting("Show Party Members")
        .withDescription("Never hides the members of your party")

    override fun init() {
        register<CheckEntityRenderEvent> {
            val entity = event.entity as? Player ?: return@register
            if (entity == mc.player || entity.uuid.version() != 4) return@register
            if (onlyInDungeons.value && ! LocationUtils.inDungeon) return@register
            if (keepParty.value && PartyUtils.members.any { it.equals(entity.name.string, true) }) return@register
            if (! hideAll.value && entity.distanceToSqr(mc.player ?: return@register) > distance.value * distance.value) return@register
            event.isCanceled = true
        }
    }
}
