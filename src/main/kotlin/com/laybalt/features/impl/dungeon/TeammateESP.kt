package com.laybalt.features.impl.dungeon

import com.laybalt.config.types.ToggleSetting
import com.laybalt.event.impl.CheckEntityGlowEvent
import com.laybalt.event.impl.RenderWorldEvent
import com.laybalt.features.Feature
import com.laybalt.utils.dungeons.DungeonListener
import com.laybalt.utils.location.LocationUtils
import com.laybalt.utils.render.RenderHelper.renderVec
import com.laybalt.utils.render.world.Render3D.renderString
import net.minecraft.client.player.AbstractClientPlayer
import net.minecraft.world.entity.Entity

object TeammateESP: Feature(
    "Highlights your dungeon party.",
    //#if LEGIT
    //$name = "Teammate Highlight",
    //#endif
    jsonName = "Teammate ESP"
) {
    private val highlight by ToggleSetting("Highlight Teammates", true)
    private val drawName by ToggleSetting("Show Teammate Name", true)

    private val cache = HashMap<Int, Boolean>()

    override fun init() {
        register<CheckEntityGlowEvent> {
            if (! highlight.value) return@register
            if (! LocationUtils.inDungeon) return@register
            if (event.entity !is AbstractClientPlayer) return@register
            if (event.entity.uuid.version() != 4) return@register

            for (teammate in DungeonListener.dungeonTeammates) {
                if (teammate.entity?.id != event.entity.id) continue
                event.color = teammate.clazz.color
            }
        }

        register<RenderWorldEvent> {
            if (! drawName.value) return@register
            if (! LocationUtils.inDungeon) return@register
            for (teammate in DungeonListener.dungeonTeammatesNoSelf) {
                val entity = teammate.entity ?: continue
                val color = teammate.clazz.code
                val renderVec = entity.renderVec
                val distance = renderVec.distanceTo(player.renderVec)
                val scale = (distance * 0.12f).coerceAtLeast(1.0)

                event.ctx.renderString(
                    "&e[${teammate.clazz.name[0]}&e] $color${teammate.name}",
                    renderVec.x,
                    renderVec.y + entity.bbHeight + 0.7 + distance * 0.015f,
                    renderVec.z,
                    scale = scale,
                    phase = true
                )

                cache.clear()
            }
        }
    }

    @JvmStatic
    fun shouldHideNametag(entity: Entity): Boolean {
        return cache.getOrPut(entity.id) {
            if (! enabled) return@getOrPut false
            if (! drawName.value) return@getOrPut false
            if (! LocationUtils.inDungeon) return@getOrPut false
            DungeonListener.dungeonTeammatesNoSelf.any { it.entity?.id == entity.id }
        }
    }
}