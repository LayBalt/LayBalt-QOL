package com.laybalt.features.impl.visual

import com.laybalt.config.types.ColorSetting
import com.laybalt.config.types.DropdownSetting
import com.laybalt.config.types.SliderSetting
import com.laybalt.config.types.ToggleSetting
import com.laybalt.event.impl.TickEvent
import com.laybalt.event.impl.WorldChangeEvent
import com.laybalt.features.Feature
import com.laybalt.features.impl.visual.HudWidgetKit.SampleBuffer
import com.laybalt.utils.ColorUtils.withAlpha
import com.laybalt.utils.render.Render2D.drawString
import net.minecraft.client.gui.GuiGraphicsExtractor
import java.util.Locale
import kotlin.math.sin
import kotlin.math.sqrt

object SpeedHud: Feature("Shows how fast you are moving in blocks per second, with an optional speed graph.", name = "Speed HUD") {
    private val horizontalOnly by ToggleSetting("Horizontal Only", true).withDescription("Ignores vertical movement (falling, jumping) when measuring speed.").section("Speed")
    private val unit by DropdownSetting("Unit", 0, listOf("Blocks/s", "km/h", "Blocks/tick")).withDescription("Unit the speed is shown in.")
    private val decimals by SliderSetting("Decimals", 2, 0, 3, 1).withDescription("Digits after the decimal point.")
    private val smoothing by SliderSetting("Smoothing", 5, 1, 20, 1, " ticks").withDescription("Number of ticks averaged together; higher values give a calmer number.")
    private val showLabel by ToggleSetting("Show Label", true).withDescription("Writes \"Speed\" in front of the number.")
    private val showPeak by ToggleSetting("Show Peak", false).withDescription("Also shows the highest speed in the graph window.")

    private val showGraph by ToggleSetting("Graph", true).withDescription("Draws a rolling graph of your recent speed under the number.").section("Graph")
    private val graphStyle by DropdownSetting("Graph Style", 1, listOf("Line", "Area", "Bars")).withDescription("Look of the graph.").showIf { showGraph.value }
    private val graphLength by SliderSetting("Graph Length", 100, 20, 400, 10, " ticks").withDescription("How many ticks of history the graph shows (20 ticks = 1 second).").showIf { showGraph.value }
    private val graphWidth by SliderSetting("Graph Width", 90, 40, 220, 2, "px").withDescription("Width of the graph.").showIf { showGraph.value }
    private val graphHeight by SliderSetting("Graph Height", 24, 10, 80, 2, "px").withDescription("Height of the graph.").showIf { showGraph.value }
    private val graphColor by ColorSetting("Graph Color", HudWidgetKit.defaultAccent).withDescription("Colour of the graph line, area or bars.").showIf { showGraph.value }
    private val graphGrid by ToggleSetting("Graph Grid", true).withDescription("Draws faint guide lines at the top, middle and bottom of the graph.").showIf { showGraph.value }

    private val labelColor by ColorSetting("Label Color", HudWidgetKit.defaultAccent, false).withDescription("Colour of the label and unit.").section("Colors")
    private val valueColor by ColorSetting("Value Color", HudWidgetKit.defaultText, false).withDescription("Colour of the speed number.")
    private val textShadow by ToggleSetting("Text Shadow", true).withDescription("Draws the text with a shadow.")

    private val bgMode by DropdownSetting("Background", 3, HudWidgetKit.BACKGROUND_MODES).withDescription("Panel drawn behind the whole widget.").section("Panel")
    private val bgColor by ColorSetting("Background Color", HudWidgetKit.defaultBackground).withDescription("Colour of the panel behind the widget.").showIf { bgMode.value != 0 }
    private val accentColor by ColorSetting("Accent Color", HudWidgetKit.defaultAccent).withDescription("Colour of the panel outline, accent bar or glass hairline.").showIf { bgMode.value >= 2 }

    private val window = SampleBuffer(5)
    private val history = SampleBuffer(100)
    private var lastX = Double.NaN
    private var lastY = 0.0
    private var lastZ = 0.0
    private var speed = 0f

    private val exampleHistory by lazy {
        SampleBuffer(100).apply { for (i in 0 until 100) add((5.6 + 2.2 * sin(i / 9.0) + if (i % 23 < 4) 3.0 else 0.0).toFloat()) }
    }

    override fun init() {
        hudElement("SpeedHud", shouldDraw = { mc.player != null }) { ctx, example -> draw(ctx, example) } defaults {
            x = 140f
            y = 5f
        }

        register<TickEvent.End> {
            val player = mc.player ?: return@register
            window.resize(smoothing.value)
            history.resize(graphLength.value)
            if (lastX.isNaN()) {
                lastX = player.x; lastY = player.y; lastZ = player.z
                return@register
            }
            val dx = player.x - lastX
            val dy = if (horizontalOnly.value) 0.0 else player.y - lastY
            val dz = player.z - lastZ
            lastX = player.x; lastY = player.y; lastZ = player.z

            val perTick = sqrt(dx * dx + dy * dy + dz * dz)
            // Teleports (warps, ender pearls, etherwarp) would spike the graph; skip them.
            if (perTick > 20.0) return@register
            window.add((perTick * 20.0).toFloat())
            speed = window.average()
            history.add(speed)
        }

        register<WorldChangeEvent> { reset() }
    }

    override fun onDisable() {
        super.onDisable()
        reset()
    }

    private fun reset() {
        window.clear()
        history.clear()
        lastX = Double.NaN
        speed = 0f
    }

    private fun convert(blocksPerSecond: Float) = when (unit.value) {
        1 -> blocksPerSecond * 3.6f
        2 -> blocksPerSecond / 20f
        else -> blocksPerSecond
    }

    private fun unitName() = when (unit.value) {
        1 -> "km/h"
        2 -> "b/t"
        else -> "b/s"
    }

    private fun draw(ctx: GuiGraphicsExtractor, example: Boolean): Pair<Number, Number> {
        val graphData = if (example && history.size < 2) exampleHistory else history
        val current = if (example && history.size < 2) graphData.last() else speed
        val fmt = "%.${decimals.value}f"
        val font = mc.font

        val parts = mutableListOf<Pair<String, java.awt.Color>>()
        if (showLabel.value) parts += "Speed " to labelColor.value
        parts += String.format(Locale.US, fmt, convert(current)) to valueColor.value
        parts += " ${unitName()}" to labelColor.value
        if (showPeak.value) {
            parts += "  peak " to labelColor.value
            parts += String.format(Locale.US, fmt, convert(graphData.max())) to valueColor.value
        }
        val textW = parts.sumOf { font.width(it.first) }

        val pad = HudWidgetKit.padding(bgMode.value)
        val left = HudWidgetKit.leftPadding(bgMode.value)
        val gw = graphWidth.value
        val gh = graphHeight.value
        val contentW = if (showGraph.value) maxOf(textW, gw) else textW
        val contentH = 8 + if (showGraph.value) 3 + gh else 0
        val totalW = contentW + left + pad
        val totalH = contentH + pad * 2
        HudWidgetKit.drawBackground(ctx, totalW, totalH, bgMode.value, bgColor.value, accentColor.value)

        var x = left
        for ((text, color) in parts) {
            ctx.drawString(text, x, pad, color, shadow = textShadow.value)
            x += font.width(text)
        }

        if (showGraph.value) {
            val top = pad + 11f
            val maxV = maxOf(graphData.max() * 1.1f, 1f)
            HudWidgetKit.drawGraph(
                ctx, left.toFloat(), top, gw.toFloat(), gh.toFloat(), graphData, 0f, maxV,
                graphColor.value, graphStyle.value, if (graphGrid.value) labelColor.value.withAlpha(35) else null
            )
        }
        return totalW to totalH
    }
}
