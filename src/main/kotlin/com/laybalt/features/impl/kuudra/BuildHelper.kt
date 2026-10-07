// Ported from Odin (https://github.com/odtheking/Odin), BSD-3-Clause, Copyright (c) 2025 odtheking
package com.laybalt.features.impl.kuudra

import com.laybalt.config.types.SliderSetting
import com.laybalt.config.types.ToggleSetting
import com.laybalt.event.impl.RenderWorldEvent
import com.laybalt.event.impl.WorldChangeEvent
import com.laybalt.features.Feature
import com.laybalt.utils.ChatUtils
import com.laybalt.utils.ChatUtils.removeFormatting
import com.laybalt.utils.kuudra.KuudraRender.renderBeacon
import com.laybalt.utils.kuudra.KuudraUtils
import com.laybalt.utils.render.Render2D.drawString
import com.laybalt.utils.render.RenderHelper.width
import com.laybalt.utils.render.world.Render3D.renderString
import net.minecraft.world.phys.Vec3
import java.awt.Color

object BuildHelper: Feature("Shows the state of the ballista build in Kuudra: build %, builders, freshers and the unfinished piles.") {
    private val buildHelperDraw by ToggleSetting("Render on Ballista", false)
        .withDescription("Draws the build percentage and the number of builders in big text on the ballista.")
    private val unfinishedWaypoints by ToggleSetting("Unfinished Waypoints", true)
        .withDescription("Marks every unfinished ballista pile with a waypoint that shows its progress.")
    private val hideDefaultTag by ToggleSetting("Hide Default Tag", true)
        .withDescription("Hides Hypixel's own \"PROGRESS: x%\" name tag above unfinished piles (the waypoint already shows it).").showIf { unfinishedWaypoints.value }
    private val stunNotificationNumber by SliderSetting("Stun Percent", 93f, 0f, 100f, 1f, "%")
        .withDescription("In tier 3+ shows a \"Go to stun\" title once the build reaches this percentage. Set to 0 to disable.")

    private val pileColor = Color(0xAA0000)
    private val ballistaTop = Vec3(- 101.5, 82.0, - 105.5)
    private val ballistaBottom = Vec3(- 101.5, 81.0, - 105.5)

    /** One-shot flag so the stun title is shown once per crossing instead of every frame. */
    private var stunAlerted = false

    override fun init() {
        hudElement(name, shouldDraw = { KuudraUtils.inKuudra && KuudraUtils.phase == 2 }) { ctx, _ ->
            val lines = listOf(
                "§bFreshers: ${colorBuilders(KuudraUtils.freshers.values.count { it != null })}",
                "§bBuilders: ${colorBuilders(KuudraUtils.playersBuildingAmount)}",
                "§bBuild: ${colorBuild(KuudraUtils.buildDonePercentage)}%"
            )
            lines.forEachIndexed { i, line -> ctx.drawString(line, 0, i * 9) }
            lines.maxOf { it.width() } to lines.size * 9
        }

        register<RenderWorldEvent> {
            if (! KuudraUtils.inKuudra || KuudraUtils.phase != 2) return@register

            val stunAt = stunNotificationNumber.value
            if (stunAt != 0f && KuudraUtils.kuudraTier >= 3 && KuudraUtils.buildDonePercentage >= stunAt) {
                if (! stunAlerted) {
                    stunAlerted = true
                    ChatUtils.showTitle("§l§3Go to stun")
                }
            }
            else stunAlerted = false

            if (buildHelperDraw.value) {
                event.ctx.renderString("§bBuild §c${colorBuild(KuudraUtils.buildDonePercentage)}%", ballistaTop, scale = 3f)
                event.ctx.renderString("§bBuilders ${colorBuilders(KuudraUtils.playersBuildingAmount)}", ballistaBottom, scale = 3f)
            }

            if (unfinishedWaypoints.value) KuudraUtils.buildingPiles.forEach { pile ->
                if (pile.isRemoved) return@forEach
                event.ctx.renderBeacon("§4" + pile.name.string.removeFormatting(), pile.blockPosition(), pileColor, increase = false, distance = false)
                if (hideDefaultTag.value) pile.isCustomNameVisible = false
            }
        }

        register<WorldChangeEvent> { stunAlerted = false }
    }

    private fun colorBuild(build: Int) = when {
        build >= 75 -> "§a$build"
        build >= 50 -> "§e$build"
        build >= 25 -> "§6$build"
        else -> "§c$build"
    }

    private fun colorBuilders(builders: Int) = when {
        builders >= 3 -> "§a$builders"
        builders >= 2 -> "§e$builders"
        else -> "§c$builders"
    }
}
