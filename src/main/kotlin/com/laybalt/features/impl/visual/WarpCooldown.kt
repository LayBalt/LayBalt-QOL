package com.laybalt.features.impl.visual

import com.laybalt.LayBaltQOL.MOD_NAME
import com.laybalt.config.types.ColorSetting
import com.laybalt.config.types.DropdownSetting
import com.laybalt.config.types.TextInputSetting
import com.laybalt.config.types.ToggleSetting
import com.laybalt.event.impl.ChatMessageEvent
import com.laybalt.event.impl.TickEvent
import com.laybalt.features.Feature
import com.laybalt.ui.notification.NotificationManager
import com.laybalt.utils.dungeons.DungeonUtils
import com.laybalt.utils.location.LocationUtils
import com.laybalt.utils.render.Render2D.drawRect
import com.laybalt.utils.render.Render2D.drawString
import net.minecraft.client.gui.GuiGraphicsExtractor
import java.awt.Color
import java.util.Locale
import kotlin.math.ceil

object WarpCooldown: Feature("Displays on screen how long until you can start another dungeon run.") {
    private val label by TextInputSetting("Label", "Warp Cooldown").withDescription("Text shown in front of the time; leave empty for none.").section("Display")
    private val decimals by ToggleSetting("Show Tenths", false).withDescription("Shows the remaining time with one decimal (e.g. 12.4s).")
    private val progressBar by ToggleSetting("Progress Bar", true).withDescription("Draws a bar under the text that empties as the cooldown runs out.")
    private val readyAlert by ToggleSetting("Ready Alert", true).withDescription("Shows a notification when you can warp into a new run again.")

    private val labelColor by ColorSetting("Label Color", Color(85, 255, 255), false).withDescription("Colour of the label.").section("Colors")
    private val timeColor by ColorSetting("Time Color", HudWidgetKit.defaultText, false).withDescription("Colour of the remaining time.")
    private val barColor by ColorSetting("Bar Color", HudWidgetKit.defaultAccent).withDescription("Colour of the progress bar.").showIf { progressBar.value }
    private val barBackground by ColorSetting("Bar Background", Color(0, 0, 0, 150)).withDescription("Colour of the empty part of the bar.").showIf { progressBar.value }
    private val textShadow by ToggleSetting("Text Shadow", true).withDescription("Draws the text with a shadow.")

    private val bgMode by DropdownSetting("Background", 0, HudWidgetKit.BACKGROUND_MODES).withDescription("Panel drawn behind the whole widget.").section("Panel")
    private val bgColor by ColorSetting("Background Color", HudWidgetKit.defaultBackground).withDescription("Colour of the panel behind the widget.").showIf { bgMode.value != 0 }
    private val accentColor by ColorSetting("Accent Color", HudWidgetKit.defaultAccent).withDescription("Colour of the panel outline, accent bar or glass hairline.").showIf { bgMode.value >= 2 }

    private const val COOLDOWN_MS = 30_000L
    private var startTime = 0L
    private var onCd = false

    private val remainingMillis get() = COOLDOWN_MS - (System.currentTimeMillis() - startTime)
    private val remainingSeconds get() = ceil(remainingMillis / 1000.0).toInt()

    override fun init() {
        hudElement("WarpCooldown", shouldDraw = { onCd && remainingSeconds > 0 }) { ctx, example -> draw(ctx, example) } defaults {
            x = 5f
            y = 351f
        }

        register<ChatMessageEvent> {
            if (! LocationUtils.onHypixel) return@register
            if (onCd && remainingSeconds > 0) return@register
            if (! event.unformattedText.matches(DungeonUtils.floorEnterRegex)) return@register
            startTime = System.currentTimeMillis()
            onCd = true
        }

        register<TickEvent.Start> {
            if (onCd && remainingMillis <= 0) {
                onCd = false
                if (readyAlert.value) NotificationManager.push(MOD_NAME, "Warp cooldown is over, you can start a new run.")
            }
        }
    }

    private fun draw(ctx: GuiGraphicsExtractor, example: Boolean): Pair<Number, Number> {
        val font = mc.font
        val millis = if (example) 18_400L else remainingMillis.coerceAtLeast(0L)
        val time = if (decimals.value) String.format(Locale.US, "%.1fs", millis / 1000.0) else "${ceil(millis / 1000.0).toInt()}s"
        val name = label.value.takeIf { it.isNotBlank() }?.let { "$it: " } ?: ""

        val pad = HudWidgetKit.padding(bgMode.value)
        val left = HudWidgetKit.leftPadding(bgMode.value)
        val textW = font.width(name) + font.width(time)
        val bar = progressBar.value
        val totalW = textW + left + pad
        val totalH = 8 + (if (bar) 4 else 0) + pad * 2
        HudWidgetKit.drawBackground(ctx, totalW, totalH, bgMode.value, bgColor.value, accentColor.value)

        if (name.isNotEmpty()) ctx.drawString(name, left, pad, labelColor.value, shadow = textShadow.value)
        ctx.drawString(time, left + font.width(name), pad, timeColor.value, shadow = textShadow.value)
        if (bar) {
            val fraction = (millis.toFloat() / COOLDOWN_MS).coerceIn(0f, 1f)
            ctx.drawRect(left, pad + 10, textW, 2, barBackground.value)
            ctx.drawRect(left, pad + 10, textW * fraction, 2, barColor.value)
        }
        return totalW to totalH
    }
}
