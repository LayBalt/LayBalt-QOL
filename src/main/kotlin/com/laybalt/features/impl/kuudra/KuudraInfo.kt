// Ported from Odin (https://github.com/odtheking/Odin), BSD-3-Clause, Copyright (c) 2025 odtheking
package com.laybalt.features.impl.kuudra

import com.laybalt.config.types.ColorSetting
import com.laybalt.config.types.SliderSetting
import com.laybalt.config.types.ToggleSetting
import com.laybalt.event.impl.RenderWorldEvent
import com.laybalt.features.Feature
import com.laybalt.utils.kuudra.KuudraUtils
import com.laybalt.utils.render.Render2D.drawString
import com.laybalt.utils.render.RenderHelper.renderBoundingBox
import com.laybalt.utils.render.RenderHelper.renderVec
import com.laybalt.utils.render.RenderHelper.width
import com.laybalt.utils.render.world.Render3D.renderBoxBounds
import com.laybalt.utils.render.world.Render3D.renderString
import java.awt.Color
import java.util.*

object KuudraInfo: Feature("Displays information about the Kuudra boss itself: a highlight box and its health.") {
    private val highlightKuudra by ToggleSetting("Highlight Kuudra", true)
        .withDescription("Draws an outline box around Kuudra.").section("Highlight")
    private val kuudraColor by ColorSetting("Kuudra Color", Color(0xFF5555), true)
        .withDescription("Colour of the Kuudra outline box.").showIf { highlightKuudra.value }

    private val kuudraHPDisplay by ToggleSetting("Kuudra HP", true)
        .withDescription("Renders Kuudra's health in the world, in front of its head.").section("Health")
    private val healthSize by SliderSetting("Health Size", 4f, 3f, 8f, 0.1f)
        .withDescription("Size of the in-world health text.").showIf { kuudraHPDisplay.value }
    private val scaledHealth by ToggleSetting("Use Scaled", true)
        .withDescription("In tier 5 (Infernal) Kuudra's last 25k health is shown scaled to the real 240M health pool.")

    override fun init() {
        hudElement("Health Display", shouldDraw = { KuudraUtils.inKuudra && KuudraUtils.kuudraEntity != null }) { ctx, example ->
            val text = if (example) "§a99.975M§7/§a240M§c❤" else getCurrentHealthDisplay(KuudraUtils.kuudraEntity?.health ?: return@hudElement 0 to 0)
            ctx.drawString(text, 0, 0)
            text.width() to 9
        }

        register<RenderWorldEvent> {
            if (! KuudraUtils.inKuudra) return@register
            val kuudra = KuudraUtils.kuudraEntity ?: return@register

            if (highlightKuudra.value) event.ctx.renderBoxBounds(kuudra.renderBoundingBox, kuudraColor.value, fill = false, lineWidth = 3)

            if (kuudraHPDisplay.value) {
                val pos = kuudra.renderVec.add(kuudra.lookAngle.scale(13.0)).add(0.0, 10.0, 0.0)
                event.ctx.renderString(getCurrentHealthDisplay(kuudra.health), pos, scale = healthSize.value)
            }
        }
    }

    private fun getCurrentHealthDisplay(kuudraHP: Float): String {
        val color = when {
            kuudraHP > 99000 -> "§a"
            kuudraHP > 75000 -> "§2"
            kuudraHP > 50000 -> "§e"
            kuudraHP > 25000 -> "§6"
            kuudraHP > 10000 -> "§c"
            else -> "§4"
        }
        val health = kuudraHP / 1000.0

        return if (kuudraHP <= 25000 && scaledHealth.value && KuudraUtils.kuudraTier == 5) "$color${format(health * 9.6)}M§7/§a240M§c❤"
        else "$color${format(health)}K§7/§a100k§c❤"
    }

    /** Up to 3 decimals without trailing zeros (Odin prints the raw float). */
    private fun format(value: Double) = String.format(Locale.US, "%.3f", value).trimEnd('0').trimEnd('.')
}
