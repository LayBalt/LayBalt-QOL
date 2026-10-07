package com.laybalt.features.impl.dev

import com.laybalt.config.types.DropdownSetting
import com.laybalt.config.types.SliderSetting
import com.laybalt.config.types.ToggleSetting
import com.laybalt.event.impl.CheckEntityGlowEvent
import com.laybalt.event.impl.RenderWorldEvent
import com.laybalt.event.priority.EventPriority
import com.laybalt.features.Feature
import com.laybalt.interfaces.IGlowingEntity
import com.laybalt.utils.ColorUtils.withAlpha
import com.laybalt.utils.equalsOneOf
import com.laybalt.utils.render.world.Render3D.renderBoxBounds
import com.laybalt.utils.render.RenderHelper.renderBoundingBox

object Box3D: Feature("Replaces the Glow ESP with 3D boxes") {
    private val mode by DropdownSetting("Mode", 2, listOf("Outline", "Fill", "Filled Outline"))
    private val lineWidth by SliderSetting("Line Width", 2.5, 1, 10, 0.1).hideIf { mode.value == 1 }
    private val outlineOpacity by SliderSetting("Outline Opacity", 35, 0, 100, 1).hideIf { mode.value == 1 }
    private val fillOpacity by SliderSetting("Fill Opacity", 35, 0, 100, 1).hideIf { mode.value == 0 }

    //#if CHEAT
    private val phase by ToggleSetting("Phase", true)
    //#endif

    override fun init() {
        register<CheckEntityGlowEvent>(EventPriority.LOWEST) {
            if (! event.shouldGlow) return@register
            val glow = event.entity as? IGlowingEntity ?: return@register
            glow.`lbqmod$isGlowing`(true)
            glow.`lbqmod$glowColor`(event.color)
            event.isCanceled = true
        }

        register<RenderWorldEvent> {
            val outline = mode.value.equalsOneOf(0, 2)
            val fill = mode.value.equalsOneOf(1, 2)
            val phase = run {
                //#if CHEAT
                phase.value
                //#else
                //$false
                //#endif
            }

            if (! outline && ! fill) return@register
            val iterator = level.entitiesForRendering().iterator()

            while (iterator.hasNext()) {
                val entity = iterator.next()
                if (! (entity as IGlowingEntity).`lbqmod$isGlowing`()) continue
                if (entity == player) continue
                val color = entity.`lbqmod$glowColor`()

                event.ctx.renderBoxBounds(
                    entity.renderBoundingBox.inflate(0.1), color.withAlpha(outlineOpacity.value.toFloat() / 100),
                    color.withAlpha(fillOpacity.value.toFloat() / 100),
                    outline = outline,
                    fill = fill,
                    phase = phase,
                    lineWidth = lineWidth.value
                )
            }
        }
    }
}