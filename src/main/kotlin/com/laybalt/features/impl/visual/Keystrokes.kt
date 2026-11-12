package com.laybalt.features.impl.visual

import com.mojang.blaze3d.platform.InputConstants
import gg.essential.universal.UMinecraft
import com.laybalt.config.types.ColorSetting
import com.laybalt.config.types.DropdownSetting
import com.laybalt.config.types.SliderSetting
import com.laybalt.config.types.ToggleSetting
import com.laybalt.event.impl.MouseClickEvent
import com.laybalt.event.impl.WorldChangeEvent
import com.laybalt.features.Feature
import com.laybalt.features.impl.visual.HudWidgetKit.lerp
import com.laybalt.utils.ColorUtils.withAlpha
import com.laybalt.utils.render.Render2D.drawBorder
import com.laybalt.utils.render.Render2D.drawRect
import com.laybalt.utils.render.Render2D.drawString
import net.minecraft.client.KeyMapping
import net.minecraft.client.gui.GuiGraphicsExtractor
import java.awt.Color

object Keystrokes: Feature("Shows your movement keys, jump and mouse buttons with live CPS and a press animation.") {
    private val showMouse by ToggleSetting("Mouse Buttons", true).withDescription("Shows the left and right mouse buttons under the movement keys.").section("Layout")
    private val showCps by ToggleSetting("Show CPS", true).withDescription("Shows the clicks per second of each mouse button inside its key.").showIf { showMouse.value }
    private val showSpace by ToggleSetting("Space Bar", true).withDescription("Shows the jump key as a wide bar at the bottom.")
    private val showSneakSprint by ToggleSetting("Sneak and Sprint", false).withDescription("Adds a row with the sneak and sprint keys.")
    private val boundKeyNames by ToggleSetting("Use Bound Keys", true).withDescription("Labels the keys with the key they are bound to instead of always W, A, S, D.")
    private val keySize by SliderSetting("Key Size", 22, 14, 32, 1, "px").withDescription("Width and height of one movement key.")
    private val gap by SliderSetting("Gap", 2, 0, 6, 1, "px").withDescription("Space between two keys.")
    private val textShadow by ToggleSetting("Text Shadow", true).withDescription("Draws the key labels with a shadow.")

    private val animate by ToggleSetting("Press Animation", true).withDescription("Smoothly fades keys in and out instead of switching instantly.").section("Animation")
    private val animTime by SliderSetting("Animation Time", 120, 30, 400, 10, "ms").withDescription("How long a key takes to reach its pressed look.").showIf { animate.value }
    private val pressStyle by DropdownSetting("Press Style", 1, listOf("Fade", "Fill From Bottom", "Grow From Center")).withDescription("How a pressed key fills with the pressed colour.")

    private val keyColor by ColorSetting("Key Color", HudWidgetKit.defaultBackground).withDescription("Background of a released key.").section("Colors")
    private val pressedColor by ColorSetting("Pressed Color", HudWidgetKit.defaultAccent.withAlpha(210)).withDescription("Background of a pressed key.")
    private val textColor by ColorSetting("Text Color", HudWidgetKit.defaultText, false).withDescription("Label colour of a released key.")
    private val pressedTextColor by ColorSetting("Pressed Text Color", Color(255, 255, 255), false).withDescription("Label colour of a pressed key.")
    private val outlineMode by DropdownSetting("Outline", 2, listOf("None", "Border", "Underline")).withDescription("Frame drawn around each key: a full border or only an underline.")
    private val outlineColor by ColorSetting("Outline Color", HudWidgetKit.defaultAccent.withAlpha(170)).withDescription("Colour of the key border or underline.").showIf { outlineMode.value != 0 }

    private val bgMode by DropdownSetting("Background", 0, HudWidgetKit.BACKGROUND_MODES).withDescription("Panel drawn behind the whole widget.").section("Panel")
    private val bgColor by ColorSetting("Background Color", HudWidgetKit.defaultBackground).withDescription("Colour of the panel behind the widget.").showIf { bgMode.value != 0 }
    private val accentColor by ColorSetting("Accent Color", HudWidgetKit.defaultAccent).withDescription("Colour of the panel outline, accent bar or glass hairline.").showIf { bgMode.value >= 2 }

    private val leftClicks = ArrayDeque<Long>()
    private val rightClicks = ArrayDeque<Long>()
    private val progress = HashMap<String, Float>()
    private var lastFrame = 0L

    override fun init() {
        hudElement("Keystrokes") { ctx, _ -> draw(ctx) } defaults {
            x = 5f
            y = 116f
        }

        register<MouseClickEvent>(receiveCancelled = true) {
            if (UMinecraft.currentScreenObj != null || event.action != InputConstants.PRESS) return@register
            val now = System.currentTimeMillis()
            when (event.button) {
                0 -> leftClicks.addLast(now)
                1 -> rightClicks.addLast(now)
            }
        }

        register<WorldChangeEvent> {
            leftClicks.clear()
            rightClicks.clear()
        }
    }

    private fun cps(clicks: ArrayDeque<Long>): Int {
        val now = System.currentTimeMillis()
        while (clicks.isNotEmpty() && now - clicks.first() > 1000) clicks.removeFirst()
        return clicks.size
    }

    private fun draw(ctx: GuiGraphicsExtractor): Pair<Number, Number> {
        val now = System.nanoTime()
        val dt = if (lastFrame == 0L) 0f else ((now - lastFrame) / 1_000_000f).coerceIn(0f, 250f)
        lastFrame = now

        val s = keySize.value.toFloat()
        val g = gap.value.toFloat()
        val full = s * 3 + g * 2
        val half = (full - g) / 2f
        val pad = HudWidgetKit.padding(bgMode.value).toFloat()
        val left = HudWidgetKit.leftPadding(bgMode.value).toFloat()

        var contentH = s * 2 + g
        if (showMouse.value) contentH += g + s
        if (showSneakSprint.value) contentH += g + s * 0.6f
        if (showSpace.value) contentH += g + maxOf(8f, s * 0.5f)

        val totalW = full + left + pad
        val totalH = contentH + pad * 2
        HudWidgetKit.drawBackground(ctx, totalW, totalH, bgMode.value, bgColor.value, accentColor.value)

        val opts = mc.options
        var y = pad
        drawKey(ctx, "up", opts.keyUp, "W", left + s + g, y, s, s, dt)
        y += s + g
        drawKey(ctx, "left", opts.keyLeft, "A", left, y, s, s, dt)
        drawKey(ctx, "down", opts.keyDown, "S", left + s + g, y, s, s, dt)
        drawKey(ctx, "right", opts.keyRight, "D", left + (s + g) * 2, y, s, s, dt)
        y += s

        if (showMouse.value) {
            y += g
            val l = if (showCps.value) "${cps(leftClicks)} CPS" else null
            val r = if (showCps.value) "${cps(rightClicks)} CPS" else null
            drawKey(ctx, "attack", opts.keyAttack, "LMB", left, y, half, s, dt, l, forceLabel = true)
            drawKey(ctx, "use", opts.keyUse, "RMB", left + half + g, y, half, s, dt, r, forceLabel = true)
            y += s
        }

        if (showSneakSprint.value) {
            y += g
            val h = s * 0.6f
            drawKey(ctx, "shift", opts.keyShift, "Sneak", left, y, half, h, dt, forceLabel = true)
            drawKey(ctx, "sprint", opts.keySprint, "Sprint", left + half + g, y, half, h, dt, forceLabel = true)
            y += h
        }

        if (showSpace.value) {
            y += g
            val h = maxOf(8f, s * 0.5f)
            drawKey(ctx, "jump", opts.keyJump, null, left, y, full, h, dt)
            val p = progress["jump"] ?: 0f
            val lineW = full / 3f
            ctx.drawRect(left + (full - lineW) / 2f, y + h / 2f - 0.5f, lineW, 1, lerp(textColor.value, pressedTextColor.value, p))
        }

        return totalW to totalH
    }

    private fun drawKey(
        ctx: GuiGraphicsExtractor, id: String, key: KeyMapping, fallback: String?,
        x: Float, y: Float, w: Float, h: Float, dt: Float, sub: String? = null, forceLabel: Boolean = false
    ) {
        val target = if (key.isDown) 1f else 0f
        val current = progress[id] ?: 0f
        val p = if (! animate.value) target else {
            val step = dt / animTime.value.toFloat()
            if (current < target) (current + step).coerceAtMost(target) else (current - step).coerceAtLeast(target)
        }
        progress[id] = p

        ctx.drawRect(x, y, w, h, keyColor.value)
        if (p > 0f) when (pressStyle.value) {
            0 -> ctx.drawRect(x, y, w, h, pressedColor.value.withAlpha((pressedColor.value.alpha * p).toInt()))
            1 -> ctx.drawRect(x, y + h * (1f - p), w, h * p, pressedColor.value)
            else -> {
                val pw = w * p
                val ph = h * p
                ctx.drawRect(x + (w - pw) / 2f, y + (h - ph) / 2f, pw, ph, pressedColor.value)
            }
        }

        when (outlineMode.value) {
            1 -> ctx.drawBorder(x, y, w, h, outlineColor.value)
            2 -> ctx.drawRect(x, y + h - 1f, w, 1, lerp(outlineColor.value, pressedTextColor.value.withAlpha(outlineColor.value.alpha), p))
        }

        if (fallback == null) return
        val label = if (boundKeyNames.value && ! forceLabel) keyLabel(key, fallback, w) else fallback
        val color = lerp(textColor.value, pressedTextColor.value, p)
        val font = mc.font
        if (sub == null) {
            ctx.drawString(label, x + (w - font.width(label)) / 2f, y + (h - 8f) / 2f, color, shadow = textShadow.value)
        }
        else {
            val subScale = 0.6f
            val blockH = 8f + 1f + 8f * subScale
            val top = y + (h - blockH) / 2f
            ctx.drawString(label, x + (w - font.width(label)) / 2f, top, color, shadow = textShadow.value)
            ctx.drawString(sub, x + (w - font.width(sub) * subScale) / 2f, top + 9f, color, subScale, textShadow.value)
        }
    }

    /** Bound key name, shortened so it fits inside the key ("Left Shift" -> "LSh"). */
    private fun keyLabel(key: KeyMapping, fallback: String, maxWidth: Float): String {
        val name = key.translatedKeyMessage.string.ifBlank { return fallback }
        if (mc.font.width(name) <= maxWidth - 4f) return name
        val words = name.split(' ').filter { it.isNotEmpty() }
        val short = if (words.size > 1) words.joinToString("") { it.take(1) } + words.last().drop(1).take(1) else name.take(3)
        return if (mc.font.width(short) <= maxWidth - 2f) short else name.take(1)
    }
}
