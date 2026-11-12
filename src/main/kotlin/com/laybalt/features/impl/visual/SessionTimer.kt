package com.laybalt.features.impl.visual

import com.laybalt.LayBaltQOL.MOD_NAME
import com.laybalt.config.types.ButtonSetting
import com.laybalt.config.types.ColorSetting
import com.laybalt.config.types.DropdownSetting
import com.laybalt.config.types.SliderSetting
import com.laybalt.config.types.TextInputSetting
import com.laybalt.config.types.ToggleSetting
import com.laybalt.event.EventBus
import com.laybalt.event.impl.TickEvent
import com.laybalt.features.Feature
import com.laybalt.ui.notification.NotificationManager
import com.laybalt.utils.render.Render2D.drawString
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.client.gui.GuiGraphicsExtractor
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object SessionTimer: Feature("Shows how long you have been playing this session. Can be reset at any time.") {
    private val countMode by DropdownSetting("Count", 0, listOf("Time In World", "Since Game Start")).withDescription("Count only the time spent in a world or server, or all time since the game started.").section("Timer")
    private val pauseAfk by ToggleSetting("Pause When AFK", false).withDescription("Stops counting while you do not move or look around (In World mode only).").showIf { countMode.value == 0 }
    private val afkMinutes by SliderSetting("AFK After", 3, 1, 30, 1, "m").withDescription("Minutes without movement after which you count as AFK.").showIf { countMode.value == 0 && pauseAfk.value }
    private val resetOnLeave by ToggleSetting("Reset On Disconnect", false).withDescription("Starts a new session every time you leave a world or server.").showIf { countMode.value == 0 }
    private val reset by ButtonSetting("Reset Timer") { resetTimer() }.withDescription("Starts the session timer from zero.")

    private val label by TextInputSetting("Label", "Session").withDescription("Text shown in front of the time; leave empty for none.").section("Display")
    private val showSeconds by ToggleSetting("Show Seconds", true).withDescription("Includes seconds in the time.")
    private val showStart by ToggleSetting("Show Start Time", false).withDescription("Adds the clock time the session started at.")
    private val showAfk by ToggleSetting("Show AFK State", true).withDescription("Adds \"AFK\" while the timer is paused because you are AFK.").showIf { countMode.value == 0 && pauseAfk.value }
    private val labelColor by ColorSetting("Label Color", HudWidgetKit.defaultAccent, false).withDescription("Colour of the label.").section("Colors")
    private val timeColor by ColorSetting("Time Color", HudWidgetKit.defaultText, false).withDescription("Colour of the time.")
    private val mutedColor by ColorSetting("Extra Color", HudWidgetKit.defaultMuted, false).withDescription("Colour of the start time and AFK note.")
    private val textShadow by ToggleSetting("Text Shadow", true).withDescription("Draws the text with a shadow.")

    private val bgMode by DropdownSetting("Background", 3, HudWidgetKit.BACKGROUND_MODES).withDescription("Panel drawn behind the whole widget.").section("Panel")
    private val bgColor by ColorSetting("Background Color", HudWidgetKit.defaultBackground).withDescription("Colour of the panel behind the widget.").showIf { bgMode.value != 0 }
    private val accentColor by ColorSetting("Accent Color", HudWidgetKit.defaultAccent).withDescription("Colour of the panel outline, accent bar or glass hairline.").showIf { bgMode.value >= 2 }

    /** Wall-clock start of the game (JVM start time). */
    private val gameStart = runCatching { java.lang.management.ManagementFactory.getRuntimeMXBean().startTime }.getOrDefault(System.currentTimeMillis())
    private var launchOffset = gameStart

    private var inWorldMillis = 0L
    private var lastTick = 0L
    private var sessionStart = System.currentTimeMillis()
    private var isAfk = false

    private var lastX = 0.0
    private var lastY = 0.0
    private var lastZ = 0.0
    private var lastYaw = 0f
    private var lastPitch = 0f
    private var lastActivity = System.currentTimeMillis()

    private val clockFormat = DateTimeFormatter.ofPattern("HH:mm")

    override fun init() {
        hudElement("SessionTimer") { ctx, _ -> draw(ctx) } defaults {
            x = 5f
            y = 80f
        }

        // The timer keeps counting while the widget is turned off, so turning it on shows the real session time.
        // TickEvent.Start is only posted while a level is loaded, so joining and leaving are tracked with the connection events.
        EventBus.register<TickEvent.Start> { tick() }
        ClientPlayConnectionEvents.JOIN.register { _, _, client -> client.execute { onJoin() } }
        ClientPlayConnectionEvents.DISCONNECT.register { _, client -> client.execute { onLeave() } }
    }

    private fun onJoin() {
        lastTick = 0L
        lastActivity = System.currentTimeMillis()
    }

    private fun onLeave() {
        // Forget the last in-world tick so the time spent outside a world is never credited on the next join.
        lastTick = 0L
        if (resetOnLeave.value && countMode.value == 0) resetTimer()
    }

    private fun tick() {
        val now = System.currentTimeMillis()
        val dt = if (lastTick == 0L) 0L else (now - lastTick).coerceIn(0L, 5000L)
        lastTick = now

        val player = mc.player ?: return

        if (player.x != lastX || player.y != lastY || player.z != lastZ || player.yRot != lastYaw || player.xRot != lastPitch) {
            lastActivity = now
            lastX = player.x; lastY = player.y; lastZ = player.z
            lastYaw = player.yRot; lastPitch = player.xRot
        }
        isAfk = pauseAfk.value && now - lastActivity > afkMinutes.value * 60_000L
        if (! isAfk) inWorldMillis += dt
    }

    private fun resetTimer() {
        inWorldMillis = 0L
        launchOffset = System.currentTimeMillis()
        sessionStart = launchOffset
        lastActivity = launchOffset
        if (enabled) NotificationManager.push(MOD_NAME, "Session timer reset")
    }

    private fun elapsed() = if (countMode.value == 0) inWorldMillis else System.currentTimeMillis() - launchOffset

    private fun startMillis() = if (countMode.value == 0) sessionStart else launchOffset

    private fun draw(ctx: GuiGraphicsExtractor): Pair<Number, Number> {
        val font = mc.font
        val parts = mutableListOf<Pair<String, java.awt.Color>>()
        if (label.value.isNotBlank()) parts += "${label.value} " to labelColor.value
        parts += HudWidgetKit.formatDuration(elapsed(), showSeconds.value) to timeColor.value
        if (showStart.value) parts += "  since ${Instant.ofEpochMilli(startMillis()).atZone(ZoneId.systemDefault()).format(clockFormat)}" to mutedColor.value
        if (showAfk.value && isAfk && countMode.value == 0) parts += "  AFK" to mutedColor.value

        val pad = HudWidgetKit.padding(bgMode.value)
        val left = HudWidgetKit.leftPadding(bgMode.value)
        val textW = parts.sumOf { font.width(it.first) }
        val totalW = textW + left + pad
        val totalH = 8 + pad * 2
        HudWidgetKit.drawBackground(ctx, totalW, totalH, bgMode.value, bgColor.value, accentColor.value)

        var x = left
        for ((text, color) in parts) {
            ctx.drawString(text, x, pad, color, shadow = textShadow.value)
            x += font.width(text)
        }
        return totalW to totalH
    }
}
