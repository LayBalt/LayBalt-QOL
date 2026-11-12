// Colour-by-time display ported from Odin (https://github.com/odtheking/Odin), BSD-3-Clause, Copyright (c) 2025 odtheking
package com.laybalt.features.impl.dungeon

import com.laybalt.config.types.ColorSetting
import com.laybalt.config.types.SliderSetting
import com.laybalt.config.types.ToggleSetting
import com.laybalt.event.impl.BlockChangeEvent
import com.laybalt.event.impl.ChatMessageEvent
import com.laybalt.event.impl.RenderWorldEvent
import com.laybalt.event.impl.WorldChangeEvent
import com.laybalt.features.Feature
import com.laybalt.utils.NumbersUtils.toFixed
import com.laybalt.utils.ThreadUtils
import com.laybalt.utils.dungeons.DungeonListener
import com.laybalt.utils.location.LocationUtils
import com.laybalt.utils.render.world.Render3D.renderString
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.FlowerPotBlock
import net.minecraft.world.phys.Vec3
import java.awt.Color
import java.util.concurrent.*

object TerracottaTimer: Feature("Displays a timer until Terracottas respawn in F6/M6") {
    private val colorByTime by ToggleSetting("Color By Time", true)
        .withDescription("Colours the timer by the time left: green above 5s, gold above 2s, red below.")
    private val timerColor by ColorSetting("Timer Color", Color.WHITE, false)
        .withDescription("Colour of the timer text when Color By Time is off.")
        .hideIf { colorByTime.value }
    private val decimalPlaces by SliderSetting("Decimal Places", 1, 0, 2, 1)
        .withDescription("How many decimal places the timer shows.")
    private val textScale by SliderSetting("Text Scale", 1.35f, 0.5f, 3f, 0.05f)
        .withDescription("Size of the timer text.")
    private val showSuffix by ToggleSetting("Seconds Suffix", true)
        .withDescription("Appends an \"s\" after the time.")

    private var terracottaSpawns = CopyOnWriteArrayList<Pair<BlockPos, Long>>()

    override fun init() {
        register<WorldChangeEvent> { terracottaSpawns.clear() }

        register<BlockChangeEvent> {
            if (LocationUtils.dungeonFloorNumber != 6 || ! LocationUtils.inBoss) return@register
            if (event.newBlock !is FlowerPotBlock) return@register
            if (terracottaSpawns.any { it.first == event.pos }) return@register
            val time = if (LocationUtils.isMasterMode) 240 else 300
            val terracotta = Pair(event.pos, DungeonListener.currentTime + time)
            ThreadUtils.scheduledTaskServer(time) { terracottaSpawns.remove(terracotta) }
            terracottaSpawns.add(terracotta)
        }

        register<ChatMessageEvent> {
            if (LocationUtils.dungeonFloorNumber != 6 || ! LocationUtils.inBoss) return@register
            if (event.unformattedText == "[BOSS] Sadan: ENOUGH!") ThreadUtils.scheduledTaskServer(10) {
                terracottaSpawns.clear()
            }
        }

        register<RenderWorldEvent> {
            terracottaSpawns.ifEmpty { return@register }.forEach { (pos, time) ->
                val timeLeft = (time - DungeonListener.currentTime) / 20.0
                val colorCode = if (colorByTime.value) getColorCode(timeLeft) else ""
                val suffix = if (showSuffix.value) "s" else ""
                val text = colorCode + formatTime(timeLeft.coerceAtLeast(0.0)) + suffix
                val color = if (colorByTime.value) Color.WHITE else timerColor.value
                event.ctx.renderString(text, Vec3(pos).add(0.5, 0.5, 0.5), color, scale = textScale.value, phase = true)
            }
        }
    }

    private fun formatTime(time: Double) =
        if (decimalPlaces.value > 0) time.toFixed(decimalPlaces.value) else kotlin.math.ceil(time).toInt().toString()

    private fun getColorCode(time: Double) = when {
        time > 5.0 -> "§a"
        time > 2.0 -> "§6"
        else -> "§c"
    }
}
