package com.laybalt.features.impl.render

import com.laybalt.config.types.ColorSetting
import com.laybalt.config.types.DropdownSetting
import com.laybalt.config.types.SliderSetting
import com.laybalt.event.impl.RenderOverlayEvent
import com.laybalt.features.Feature
import com.laybalt.utils.ActionBarParser
import com.laybalt.utils.location.LocationUtils
import com.laybalt.utils.render.Render2D.drawHorizontalGradient
import com.laybalt.utils.render.Render2D.drawRect
import com.laybalt.utils.render.Render2D.drawVerticalGradient
import java.awt.Color

/**
 * Red screen tint that grows stronger as your health drops below a threshold.
 * On SkyBlock the health comes from the action bar (vanilla hearts are meaningless there).
 */
object DamageTint: Feature("Tints the screen red when your health is low; the lower your health, the stronger the tint.") {
    private const val EDGES = 0
    private const val SIDE = 1

    private val threshold by SliderSetting("Threshold", 40, 5, 100, 5, "%")
        .withDescription("The tint starts below this share of your maximum health and reaches full strength at 0. On SkyBlock the health from the action bar is used")
    private val maxOpacity by SliderSetting("Max Opacity", 67, 10, 100, 1, "%")
        .withDescription("How strong the tint is at zero health")
    private val style by DropdownSetting("Style", EDGES, listOf("Edges", "Side", "Uniform"))
        .withDescription("Edges: a vignette that creeps in from all screen edges. Side: a gradient from the left edge growing to full colour on the right. Uniform: the whole screen is tinted evenly")
    private val color by ColorSetting("Color", Color(255, 0, 0), false)
        .withDescription("Tint colour")

    override fun init() {
        register<RenderOverlayEvent> {
            val player = mc.player ?: return@register
            if (player.isCreative || player.isSpectator) return@register

            val fraction = healthFraction() ?: return@register
            val limit = threshold.value / 100f
            val factor = 1f - (fraction / limit).coerceIn(0f, 1f)
            if (factor <= 0f) return@register

            val ctx = event.context
            val w = ctx.guiWidth()
            val h = ctx.guiHeight()
            val base = color.value
            val alpha = (factor * maxOpacity.value / 100f).coerceIn(0f, 1f)
            val strong = Color(base.red, base.green, base.blue, (alpha * 255).toInt())
            val clear = Color(base.red, base.green, base.blue, 0)

            when (style.value) {
                EDGES -> {
                    val bandW = (w * (0.15f + 0.25f * factor)).toInt()
                    val bandH = (h * (0.15f + 0.25f * factor)).toInt()
                    ctx.drawHorizontalGradient(0, 0, bandW, h, strong, clear)
                    ctx.drawHorizontalGradient(w - bandW, 0, bandW, h, clear, strong)
                    ctx.drawVerticalGradient(0, 0, w, bandH, strong, clear)
                    ctx.drawVerticalGradient(0, h - bandH, w, bandH, clear, strong)
                }
                SIDE -> ctx.drawHorizontalGradient(0, 0, w, h, Color(base.red, base.green, base.blue, (alpha * 0.4f * 255).toInt()), strong)
                else -> ctx.drawRect(0, 0, w, h, Color(base.red, base.green, base.blue, (alpha * 0.6f * 255).toInt()))
            }
        }
    }

    /** Current / maximum health in 0..1, or null when unknown. */
    private fun healthFraction(): Float? {
        if (LocationUtils.inSkyblock && ActionBarParser.maxHealth > 0) {
            return ActionBarParser.currentHealth.toFloat() / ActionBarParser.maxHealth
        }
        val player = mc.player ?: return null
        if (player.maxHealth <= 0f) return null
        return (player.health + player.absorptionAmount).coerceAtMost(player.maxHealth) / player.maxHealth
    }
}
