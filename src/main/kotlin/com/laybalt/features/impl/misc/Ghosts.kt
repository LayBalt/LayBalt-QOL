// Ported from OdinClient (https://github.com/skies-starred/OdinClient), BSD-3-Clause, Copyright (c) 2026 Starred
package com.laybalt.features.impl.misc

//#if CHEAT

import com.laybalt.config.types.ToggleSetting
import com.laybalt.event.impl.EntityRenderStateEvent
import com.laybalt.features.Feature
import com.laybalt.utils.location.LocationUtils
import com.laybalt.utils.location.WorldType
import net.minecraft.client.renderer.entity.state.CreeperRenderState
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.monster.Creeper

object Ghosts: Feature("Rendering tweaks for the invisible ghost creepers in the Dwarven Mines (The Mist).") {
    private val showGhosts by ToggleSetting("Show Ghosts", false)
        .withDescription("Renders ghosts' creeper bodies even though they are invisible.")
    private val showPowered by ToggleSetting("Show Powered Layer", true)
        .withDescription("Keeps the charged-creeper lightning layer around ghosts. Turn off to hide it.")

    override fun init() {
        register<EntityRenderStateEvent> {
            if (LocationUtils.world != WorldType.DwarvenMines) return@register
            val state = event.renderState as? CreeperRenderState ?: return@register
            val creeper = event.entity as? Creeper ?: return@register
            if (creeper.getAttributeBaseValue(Attributes.MAX_HEALTH) < 1_000_000) return@register

            if (state.isPowered && ! showPowered.value) state.isPowered = false
            if (state.isInvisible && showGhosts.value) state.isInvisible = false
        }
    }
}
//#endif
