package com.laybalt.features.impl.render

import com.laybalt.config.types.DropdownSetting
import com.laybalt.config.types.SliderSetting
import com.laybalt.config.types.ToggleSetting
import com.laybalt.event.impl.CheckEntityGlowEvent
import com.laybalt.event.impl.RenderWorldEvent
import com.laybalt.event.priority.EventPriority
import com.laybalt.features.Feature
import com.laybalt.features.impl.render.WorldBoxKit.drawBox
import com.laybalt.interfaces.IGlowingEntity
import com.laybalt.utils.ColorUtils.withAlpha
import com.laybalt.utils.equalsOneOf
import com.laybalt.utils.render.RenderHelper.renderBoundingBox

/**
 * Turns every glow highlight other features request (Star Mob ESP, Custom ESP, Teammate ESP...) into a 3D box.
 *
 * Runs last on [CheckEntityGlowEvent]: it keeps the requested colour on the entity, cancels the vanilla glow and
 * draws the box in [RenderWorldEvent]. Moved from the NoammAddons dev category; the config key is unchanged.
 */
object Box3D: Feature("Replaces the Glow ESP with 3D boxes") {
    private val mode by DropdownSetting("Mode", 2, listOf("Outline", "Fill", "Filled Outline"))
        .withDescription("Draw only the outline, only a filled box, or both.")
    private val outlineStyle by DropdownSetting("Outline Style", 0, WorldBoxKit.OUTLINE_STYLES)
        .withDescription("Full draws every edge, Corners draws only short brackets at the eight corners.").hideIf { mode.value == 1 }
    private val lineWidth by SliderSetting("Line Width", 2.5, 1, 10, 0.1)
        .withDescription("Thickness of the outline.").hideIf { mode.value == 1 }
    // NoammAddons drew the outline fully opaque whatever its "Outline Opacity" said, so the key changed to keep that look.
    private val outlineOpacity by SliderSetting("Outline Opacity", 100, 0, 100, 1)
        .withDescription("Transparency of the outline in percent (100 is fully opaque).").hideIf { mode.value == 1 }.jsonName("Outline Alpha")
    private val fillOpacity by SliderSetting("Fill Opacity", 35, 0, 100, 1)
        .withDescription("Transparency of the filled box in percent.").hideIf { mode.value == 0 }
    private val expand by SliderSetting("Expand", 0.1, 0.0, 0.5, 0.05, " blocks")
        .withDescription("How far the box reaches past the entity's hitbox on every side.")
    private val phase by ToggleSetting("Phase", true)
        .withDescription("Draws the boxes through walls.")

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
            if (! outline && ! fill) return@register

            val player = mc.player
            for (entity in level.entitiesForRendering()) {
                if (entity == player) continue
                val glow = entity as IGlowingEntity
                if (! glow.`lbqmod$isGlowing`()) continue
                val color = glow.`lbqmod$glowColor`()

                event.ctx.drawBox(
                    entity.renderBoundingBox.inflate(expand.value),
                    color.withAlpha(outlineOpacity.value / 100f),
                    color.withAlpha(fillOpacity.value / 100f),
                    outline = outline,
                    fill = fill,
                    phase = phase.value,
                    lineWidth = lineWidth.value.toFloat(),
                    corners = outlineStyle.value == 1
                )
            }
        }
    }
}
