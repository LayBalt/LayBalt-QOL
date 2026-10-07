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
import com.laybalt.utils.ServerUtils
import com.laybalt.utils.render.Render2D.drawString
import net.minecraft.client.gui.GuiGraphicsExtractor
import java.awt.Color
import java.util.Locale
import kotlin.math.sin

object PerformanceGraph: Feature("Rolling graphs of your FPS, the server TPS and your ping.") {
    private val showFps by ToggleSetting("FPS", true).withDescription("Shows the frames-per-second graph.").section("Graphs")
    private val showTps by ToggleSetting("TPS", true).withDescription("Shows the server ticks-per-second graph (estimated from time updates).")
    private val showPing by ToggleSetting("Ping", true).withDescription("Shows the ping graph in milliseconds.")
    private val sampleInterval by SliderSetting("Sample Interval", 500, 100, 2000, 50, "ms").withDescription("Time between two samples.")
    private val samples by SliderSetting("Samples", 60, 10, 200, 5).withDescription("Number of samples each graph keeps (graph length = samples x interval).")
    private val showStats by ToggleSetting("Min / Max", true).withDescription("Shows the lowest and highest value of the window next to the current one.")
    private val tpsFixedScale by ToggleSetting("Fixed TPS Scale", true).withDescription("Always scales the TPS graph from 0 to 20 instead of to its own range.")

    private val graphStyle by DropdownSetting("Graph Style", 1, listOf("Line", "Area", "Bars")).withDescription("Look of the graphs.").section("Look")
    private val graphWidth by SliderSetting("Graph Width", 110, 50, 260, 2, "px").withDescription("Width of each graph.")
    private val graphHeight by SliderSetting("Graph Height", 22, 10, 80, 2, "px").withDescription("Height of each graph.")
    private val graphGrid by ToggleSetting("Graph Grid", true).withDescription("Draws faint guide lines at the top, middle and bottom of each graph.")
    private val fpsColor by ColorSetting("FPS Color", Color(158, 119, 237)).withDescription("Colour of the FPS graph and value.").section("Colors")
    private val tpsColor by ColorSetting("TPS Color", Color(110, 200, 170)).withDescription("Colour of the TPS graph and value.")
    private val pingColor by ColorSetting("Ping Color", Color(240, 170, 90)).withDescription("Colour of the ping graph and value.")
    private val labelColor by ColorSetting("Label Color", HudWidgetKit.defaultMuted, false).withDescription("Colour of the labels and min / max values.")
    private val textShadow by ToggleSetting("Text Shadow", true).withDescription("Draws the text with a shadow.")

    private val bgMode by DropdownSetting("Background", 4, HudWidgetKit.BACKGROUND_MODES).withDescription("Panel drawn behind the whole widget.").section("Panel")
    private val bgColor by ColorSetting("Background Color", HudWidgetKit.defaultBackground).withDescription("Colour of the panel behind the widget.").showIf { bgMode.value != 0 }
    private val accentColor by ColorSetting("Accent Color", HudWidgetKit.defaultAccent).withDescription("Colour of the panel outline, accent bar or glass hairline.").showIf { bgMode.value >= 2 }

    private val fps = SampleBuffer(60)
    private val tps = SampleBuffer(60)
    private val ping = SampleBuffer(60)
    private var lastSample = 0L

    private val exampleFps by lazy { SampleBuffer(60).apply { for (i in 0 until 60) add((140 + 25 * sin(i / 5.0) - if (i % 17 == 0) 60 else 0).toFloat()) } }
    private val exampleTps by lazy { SampleBuffer(60).apply { for (i in 0 until 60) add(if (i in 30..36) 16.5f else 19.9f) } }
    private val examplePing by lazy { SampleBuffer(60).apply { for (i in 0 until 60) add((70 + 12 * sin(i / 3.0)).toFloat()) } }

    private class Graph(val label: String, val data: SampleBuffer, val color: Color, val unit: String, val fixedMax: Float?, val decimals: Int)

    override fun init() {
        hudElement("PerformanceGraph") { ctx, example -> draw(ctx, example) } defaults {
            x = 240f
            y = 5f
        }

        register<TickEvent.Start> {
            val now = System.currentTimeMillis()
            if (now - lastSample < sampleInterval.value) return@register
            lastSample = now
            fps.resize(samples.value)
            tps.resize(samples.value)
            ping.resize(samples.value)
            fps.add(mc.fps.toFloat())
            if (mc.level != null) {
                tps.add(ServerUtils.tps)
                ping.add(ServerUtils.averagePing.toFloat())
            }
        }

        register<WorldChangeEvent> {
            tps.clear()
            ping.clear()
        }
    }

    private fun draw(ctx: GuiGraphicsExtractor, example: Boolean): Pair<Number, Number> {
        fun pick(live: SampleBuffer, fake: SampleBuffer) = if (example && live.size < 2) fake else live
        val graphs = buildList {
            if (showFps.value) add(Graph("FPS", pick(fps, exampleFps), fpsColor.value, "", null, 0))
            if (showTps.value) add(Graph("TPS", pick(tps, exampleTps), tpsColor.value, "", if (tpsFixedScale.value) 20f else null, 1))
            if (showPing.value) add(Graph("Ping", pick(ping, examplePing), pingColor.value, "ms", null, 0))
        }
        if (graphs.isEmpty()) return 60 to 10

        val font = mc.font
        val pad = HudWidgetKit.padding(bgMode.value)
        val left = HudWidgetKit.leftPadding(bgMode.value)
        val gw = graphWidth.value
        val gh = graphHeight.value
        val block = 10 + gh
        val spacing = 4
        val totalW = gw + left + pad
        val totalH = graphs.size * block + (graphs.size - 1) * spacing + pad * 2
        HudWidgetKit.drawBackground(ctx, totalW, totalH, bgMode.value, bgColor.value, accentColor.value)

        var y = pad
        for (g in graphs) {
            val fmt = "%.${g.decimals}f"
            val cur = String.format(Locale.US, fmt, g.data.last()) + g.unit
            ctx.drawString(g.label, left, y, labelColor.value, shadow = textShadow.value)
            ctx.drawString(cur, left + font.width(g.label) + 4, y, g.color, shadow = textShadow.value)
            if (showStats.value && g.data.size > 0) {
                val stats = String.format(Locale.US, "$fmt-$fmt", g.data.min(), g.data.max())
                ctx.drawString(stats, left + gw - font.width(stats) * 0.75f, y + 2, labelColor.value, 0.75f, textShadow.value)
            }
            val lo = if (g.fixedMax != null) 0f else g.data.min() * 0.9f
            val hi = g.fixedMax ?: maxOf(g.data.max() * 1.1f, lo + 1f)
            HudWidgetKit.drawGraph(
                ctx, left.toFloat(), (y + 10).toFloat(), gw.toFloat(), gh.toFloat(), g.data, lo, hi,
                g.color, graphStyle.value, if (graphGrid.value) labelColor.value.withAlpha(35) else null
            )
            y += block + spacing
        }
        return totalW to totalH
    }
}
