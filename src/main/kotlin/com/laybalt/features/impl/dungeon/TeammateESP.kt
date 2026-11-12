package com.laybalt.features.impl.dungeon

import com.laybalt.config.types.ToggleSetting
import com.laybalt.event.impl.CheckEntityGlowEvent
import com.laybalt.event.impl.RenderWorldEvent
import com.laybalt.event.impl.WorldChangeEvent
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
    private val highlight by ToggleSetting("Highlight Teammates", true).withDescription("Makes your dungeon teammates glow in their class color.")
    private val drawName by ToggleSetting("Show Teammate Name", true).withDescription("Draws each teammate's class and name above them, visible through walls, in place of the vanilla nametag.")

    /** Entity ids whose vanilla nametag is hidden; rebuilt from the party list every world render. */
    @Volatile private var hiddenNametags = emptySet<Int>()

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
            if (! drawName.value || ! LocationUtils.inDungeon) {
                hiddenNametags = emptySet()
                return@register
            }

            val hidden = HashSet<Int>()
            for (teammate in DungeonListener.dungeonTeammatesNoSelf) {
                val entity = teammate.entity ?: continue
                hidden.add(entity.id)
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
            }
            hiddenNametags = hidden
        }

        register<WorldChangeEvent> { hiddenNametags = emptySet() }
    }

    override fun onDisable() {
        super.onDisable()
        hiddenNametags = emptySet()
    }

    /** Called from MixinAvatarRendererTeammateESP: hides the vanilla nametag of teammates whose label is drawn by this feature. */
    @JvmStatic
    fun shouldHideNametag(entity: Entity): Boolean {
        if (! enabled || ! drawName.value || ! LocationUtils.inDungeon) return false
        return entity.id in hiddenNametags
    }
}
