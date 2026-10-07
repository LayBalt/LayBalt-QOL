package com.laybalt.features.impl.visual

import com.laybalt.config.types.ColorSetting
import com.laybalt.config.types.DropdownSetting
import com.laybalt.config.types.SliderSetting
import com.laybalt.config.types.ToggleSetting
import com.laybalt.features.Feature
import com.laybalt.utils.render.Render2D.drawString
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.util.Mth
import net.minecraft.world.level.Level
import java.util.Locale
import kotlin.math.floor

object CoordsHud: Feature("Shows your coordinates, facing direction, biome, chunk and the world time.", name = "Coords HUD") {
    private val decimals by SliderSetting("Decimals", 1, 0, 3, 1).withDescription("Digits after the decimal point for X, Y and Z.").section("Content")
    private val showFacing by ToggleSetting("Facing", true).withDescription("Shows the compass direction you look at and the axis it points along, e.g. North (-Z).")
    private val showRotation by ToggleSetting("Yaw and Pitch", false).withDescription("Shows your exact yaw and pitch angles.")
    private val showBiome by ToggleSetting("Biome", true).withDescription("Shows the biome you are standing in.")
    private val showChunk by ToggleSetting("Chunk", false).withDescription("Shows the chunk coordinates and your position inside the chunk.")
    private val showNether by ToggleSetting("Dimension Coords", false).withDescription("Shows the matching Nether coordinates in the Overworld and Overworld coordinates in the Nether.")
    private val showTime by ToggleSetting("World Time", false).withDescription("Shows the in-game time of day as a clock (server time).")
    private val showDay by ToggleSetting("Day", false).withDescription("Shows the in-game day counter.")
    private val singleLine by DropdownSetting("Layout", 0, listOf("Lines", "Single Line")).withDescription("One entry per line, or everything on one line separated by dots.")

    private val labelColor by ColorSetting("Label Color", HudWidgetKit.defaultAccent, false).withDescription("Colour of the labels such as XYZ or Biome.").section("Colors")
    private val valueColor by ColorSetting("Value Color", HudWidgetKit.defaultText, false).withDescription("Colour of the values.")
    private val textShadow by ToggleSetting("Text Shadow", true).withDescription("Draws the text with a shadow.")

    private val bgMode by DropdownSetting("Background", 3, HudWidgetKit.BACKGROUND_MODES).withDescription("Panel drawn behind the whole widget.").section("Panel")
    private val bgColor by ColorSetting("Background Color", HudWidgetKit.defaultBackground).withDescription("Colour of the panel behind the widget.").showIf { bgMode.value != 0 }
    private val accentColor by ColorSetting("Accent Color", HudWidgetKit.defaultAccent).withDescription("Colour of the panel outline, accent bar or glass hairline.").showIf { bgMode.value >= 2 }

    private val directions = listOf("South" to "+Z", "West" to "-X", "North" to "-Z", "East" to "+X")

    override fun init() {
        hudElement("CoordsHud") { ctx, example -> draw(ctx, example) } defaults {
            x = 5f
            y = 5f
        }
    }

    private fun fmt(v: Double) = String.format(Locale.US, "%.${decimals.value}f", v)

    private fun entries(example: Boolean): List<Pair<String, String>> {
        val player = mc.player
        val level = mc.level
        if (player == null || level == null) {
            if (! example) return emptyList()
            return listOf("XYZ" to "-12.5 72.0 103.3", "Facing" to "North (-Z)", "Biome" to "Plains")
        }
        val out = mutableListOf<Pair<String, String>>()
        out += "XYZ" to "${fmt(player.x)} ${fmt(player.y)} ${fmt(player.z)}"

        if (showFacing.value) {
            val yaw = Mth.wrapDegrees(player.yRot)
            val (name, axis) = directions[Math.floorMod(floor((yaw + 45f) / 90f).toInt(), 4)]
            out += "Facing" to "$name ($axis)"
        }
        if (showRotation.value) out += "Rot" to String.format(Locale.US, "%.1f / %.1f", Mth.wrapDegrees(player.yRot), player.xRot)

        if (showBiome.value) {
            val biome = level.getBiome(BlockPos.containing(player.x, player.y, player.z)).unwrapKey().orElse(null)?.identifier()
            val name = biome?.let { Component.translatable("biome.${it.namespace}.${it.path}").string } ?: "Unknown"
            out += "Biome" to name
        }
        if (showChunk.value) {
            val bx = floor(player.x).toInt()
            val bz = floor(player.z).toInt()
            out += "Chunk" to "${bx shr 4} ${bz shr 4} [${bx and 15} ${bz and 15}]"
        }
        if (showNether.value) {
            val dim = level.dimension()
            if (dim == Level.OVERWORLD) out += "Nether" to "${fmt(player.x / 8)} ${fmt(player.z / 8)}"
            else if (dim == Level.NETHER) out += "Overworld" to "${fmt(player.x * 8)} ${fmt(player.z * 8)}"
        }
        if (showTime.value || showDay.value) {
            val clock = level.overworldClockTime
            if (showTime.value) {
                val ofDay = Math.floorMod(clock, 24000L)
                val hours = ((ofDay / 1000) + 6) % 24
                val minutes = (ofDay % 1000) * 60 / 1000
                out += "Time" to String.format(Locale.US, "%02d:%02d", hours, minutes)
            }
            if (showDay.value) out += "Day" to (Math.floorDiv(clock, 24000L) + 1).toString()
        }
        return out
    }

    private fun draw(ctx: GuiGraphicsExtractor, example: Boolean): Pair<Number, Number> {
        val entries = entries(example)
        if (entries.isEmpty()) return 60 to 10

        val font = mc.font
        val pad = HudWidgetKit.padding(bgMode.value)
        val left = HudWidgetKit.leftPadding(bgMode.value)
        val lineH = 10
        val label = labelColor.value
        val value = valueColor.value

        if (singleLine.value == 1) {
            val sep = "  ·  "
            var w = 0
            entries.forEachIndexed { i, (k, v) ->
                if (i > 0) w += font.width(sep)
                w += font.width("$k ") + font.width(v)
            }
            val totalW = w + left + pad
            val totalH = 8 + pad * 2
            HudWidgetKit.drawBackground(ctx, totalW, totalH, bgMode.value, bgColor.value, accentColor.value)
            var x = left
            entries.forEachIndexed { i, (k, v) ->
                if (i > 0) {
                    ctx.drawString(sep, x, pad, label, shadow = textShadow.value)
                    x += font.width(sep)
                }
                ctx.drawString("$k ", x, pad, label, shadow = textShadow.value)
                x += font.width("$k ")
                ctx.drawString(v, x, pad, value, shadow = textShadow.value)
                x += font.width(v)
            }
            return totalW to totalH
        }

        val labelW = entries.maxOf { font.width(it.first) } + 5
        val contentW = labelW + entries.maxOf { font.width(it.second) }
        val totalW = contentW + left + pad
        val totalH = entries.size * lineH - 2 + pad * 2
        HudWidgetKit.drawBackground(ctx, totalW, totalH, bgMode.value, bgColor.value, accentColor.value)

        var y = pad
        for ((k, v) in entries) {
            ctx.drawString(k, left, y, label, shadow = textShadow.value)
            ctx.drawString(v, left + labelW, y, value, shadow = textShadow.value)
            y += lineH
        }
        return totalW to totalH
    }

}
