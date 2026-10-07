package com.laybalt.ui.notification

import com.laybalt.LayBaltQOL.mc
import com.laybalt.event.EventBus
import com.laybalt.event.impl.ScreenEvent
import com.laybalt.init.types.ISelfInit
import com.laybalt.ui.Palette
import com.laybalt.ui.clickgui.UI
import com.laybalt.ui.render.Icon
import com.laybalt.ui.text.UIFont
import com.laybalt.ui.utils.Animation
import com.laybalt.utils.ChatUtils.addColor
import gg.essential.universal.UMouse
import net.minecraft.client.gui.GuiGraphicsExtractor
import java.util.concurrent.*
import kotlin.math.min

/** Rounded toast cards in the bottom-right corner with an accent progress line; hovering pauses the timer. */
object NotificationManager: ISelfInit {
    private const val WIDTH = 172f
    private const val PAD = 8f
    private const val TITLE_SIZE = 7.2f
    private const val BODY_SIZE = 6.6f
    private const val TEXT_X = 26f

    private val notifications = CopyOnWriteArrayList<Notification>()
    private var lastFrameTime = System.currentTimeMillis()

    fun push(title: String, message: String, duration: Long = 3000L) {
        val t = title.addColor()
        val m = message.addColor()
        if (notifications.any { it.title == t && it.message == m && it.duration == duration }) return
        mc.execute { notifications.add(Notification(t, m, duration)) }
    }

    override fun init() {
        EventBus.register<ScreenEvent.PostRender> { render(event.context) }
    }

    /**
     * Draws the toasts in the LayBalt canvas units (the same density as the LayBalt windows, see
     * [com.laybalt.ui.utils.Resolution]), never smaller than 1.5 physical pixels per unit so they stay readable in
     * small windows. Called after every screen and on the in-game HUD.
     */
    @JvmStatic
    fun render(ctx: GuiGraphicsExtractor) {
        if (notifications.isEmpty()) {
            lastFrameTime = System.currentTimeMillis()
            return
        }
        val guiW = ctx.guiWidth().toFloat()
        val guiH = ctx.guiHeight().toFloat()
        val physical = mc.window.screenWidth.toFloat() / guiW
        val k = min(guiW / 960f, guiH / 540f).coerceAtLeast(1.5f / physical)
        val savedMeasure = UIFont.measureScale
        UIFont.measureScale = k * mc.window.guiScale
        ctx.pose().pushMatrix()
        ctx.pose().scale(k)
        try {
            draw(ctx, guiW / k, guiH / k, k)
        }
        finally {
            ctx.pose().popMatrix()
            UIFont.measureScale = savedMeasure
        }
    }

    private fun draw(ctx: GuiGraphicsExtractor, screenW: Float, screenH: Float, scale: Float) {
        val now = System.currentTimeMillis()
        val delta = now - lastFrameTime
        lastFrameTime = now

        val mX = UMouse.Scaled.x.toFloat() / scale
        val mY = UMouse.Scaled.y.toFloat() / scale

        val savedAlpha = UI.alpha
        var offset = 0f
        ctx.nextStratum()
        for (notify in notifications) {
            if (notify.isDead) {
                notifications.remove(notify)
                continue
            }

            val alive = notify.elapsedTime < notify.duration
            notify.anim.update(if (alive) 1f else 0f)
            if (!alive && notify.anim.value <= 0.01f) {
                notify.isDead = true
                continue
            }

            val t = notify.anim.value
            val height = notify.height()
            val x = screenW - (WIDTH + 10f) * (0.35f + 0.65f * t) + (1f - t) * 12f
            val y = screenH - height - 10f - offset

            val hovered = mX >= x && mX <= x + WIDTH && mY >= y && mY <= y + height
            if (!hovered && alive) notify.elapsedTime += delta

            UI.alpha = t
            UI.shadow(ctx, x, y, WIDTH, height, 8f, 12f, Palette.shadowStrong, offsetY = 3f)
            UI.rect(ctx, x, y, WIDTH, height, 8f, Palette.popover)
            UI.outline(ctx, x, y, WIDTH, height, 8f, if (hovered) Palette.accentBorder else Palette.outlineHover)

            // icon badge
            UI.circle(ctx, x + PAD + 6f, y + PAD + 6f, 7f, Palette.accentPill)
            UI.icon(ctx, Icon.INFO, x + PAD + 1.5f, y + PAD + 1.5f, 9f, Palette.accentText)

            UI.text(ctx, UIFont.trim(notify.title, WIDTH - TEXT_X - PAD, TITLE_SIZE, UIFont.Weight.SEMIBOLD), x + TEXT_X, y + PAD - 1f, Palette.textPrimary, TITLE_SIZE, UIFont.Weight.SEMIBOLD)
            var lineY = y + PAD + UIFont.lineHeight(TITLE_SIZE) + 1f
            for (line in notify.lines()) {
                UI.text(ctx, line, x + TEXT_X, lineY, Palette.textSecondary, BODY_SIZE)
                lineY += UIFont.lineHeight(BODY_SIZE) + 1f
            }

            // accent progress line
            val progress = (notify.elapsedTime.toFloat() / notify.duration.toFloat()).coerceIn(0f, 1f)
            val barW = (WIDTH - 16f) * (1f - progress)
            UI.rect(ctx, x + 8f, y + height - 3.5f, WIDTH - 16f, 1.5f, 0.75f, Palette.chip)
            if (alive && barW > 0.5f) UI.rect(ctx, x + 8f, y + height - 3.5f, barW, 1.5f, 0.75f, Palette.accent)

            offset += (height + 6f) * t
        }
        UI.alpha = savedAlpha
    }

    private class Notification(val title: String, val message: String, val duration: Long) {
        val anim = Animation(320L)
        var elapsedTime = 0L
        var isDead = false

        private var cached: List<String>? = null

        fun lines() = cached ?: UIFont.wrap(message, WIDTH - TEXT_X - PAD, BODY_SIZE, maxLines = 6).also { cached = it }

        fun height() = PAD + UIFont.lineHeight(TITLE_SIZE) + 1f + lines().size * (UIFont.lineHeight(BODY_SIZE) + 1f) + PAD
    }
}
