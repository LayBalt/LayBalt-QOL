package com.laybalt.features.impl.visual

import com.laybalt.event.impl.ChatMessageEvent
import com.laybalt.features.Feature
import com.laybalt.utils.dungeons.DungeonUtils
import com.laybalt.utils.location.LocationUtils
import com.laybalt.utils.render.Render2D.drawString
import com.laybalt.utils.render.RenderHelper.width
import kotlin.math.ceil

object WarpCooldown: Feature("Displays on screen how long until you can start another dungeon run.") {
    private const val COOLDOWN_MS = 30_000L
    private var startTime = 0L
    private var onCd = false

    private val remainingSeconds get() = ceil((COOLDOWN_MS - (System.currentTimeMillis() - startTime)) / 1000.0).toInt()

    override fun init() {
        hudElement("WarpCooldown", shouldDraw = { onCd && remainingSeconds > 0 }) { ctx, example ->
            val remaining = if (example) 30 else remainingSeconds.coerceAtLeast(0)
            if (! example && remaining <= 0) onCd = false
            val text = "&bWarp Cooldown: &f${remaining}s"
            ctx.drawString(text, 0, 0)
            return@hudElement text.width() to 9
        }

        register<ChatMessageEvent> {
            if (! LocationUtils.onHypixel) return@register
            if (onCd && remainingSeconds > 0) return@register
            if (! event.unformattedText.matches(DungeonUtils.floorEnterRegex)) return@register
            startTime = System.currentTimeMillis()
            onCd = true
        }
    }
}
