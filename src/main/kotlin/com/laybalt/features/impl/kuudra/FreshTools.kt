// Ported from Odin (https://github.com/odtheking/Odin), BSD-3-Clause, Copyright (c) 2025 odtheking
package com.laybalt.features.impl.kuudra

import com.laybalt.config.types.ToggleSetting
import com.laybalt.event.impl.ChatMessageEvent
import com.laybalt.features.Feature
import com.laybalt.utils.ChatUtils
import com.laybalt.utils.NumbersUtils.toFixed
import com.laybalt.utils.kuudra.KuudraUtils
import com.laybalt.utils.render.Render2D.drawString
import com.laybalt.utils.render.RenderHelper.width

object FreshTools: Feature("Tracks the Fresh Tools perk of your party during the Kuudra build phase.") {
    private val notifyFresh by ToggleSetting("Notify Fresh", true)
        .withDescription("Sends \"FRESH\" in party chat when your Fresh Tools perk procs, so your party's Fresh timer and Build Helper can track it.")

    private val ownFreshRegex = Regex("^Your Fresh Tools Perk bonus doubles your building speed for the next 10 seconds!$")
    private const val FRESH_DURATION = 10_000L

    override fun init() {
        hudElement("Fresh timer", shouldDraw = { KuudraUtils.inKuudra && KuudraUtils.phase == 2 }) { ctx, example ->
            val lines = if (example) listOf("§6Player1§f: 9.00s", "§6Player2§f: 5.00s")
            else {
                val now = System.currentTimeMillis()
                KuudraUtils.freshers.mapNotNull { (name, time) ->
                    val timeLeft = time?.let { FRESH_DURATION - (now - it) }?.takeIf { it > 0 } ?: return@mapNotNull null
                    "§6$name§f: ${(timeLeft / 1000.0).toFixed(2)}s"
                }
            }

            lines.forEachIndexed { i, line -> ctx.drawString(line, 0, i * 9) }
            (lines.maxOfOrNull { it.width() } ?: 0) to lines.size * 9
        }

        register<ChatMessageEvent> {
            if (notifyFresh.value && KuudraUtils.inKuudra && ownFreshRegex.matches(event.unformattedText))
                ChatUtils.sendCommand("pc FRESH")
        }
    }
}
