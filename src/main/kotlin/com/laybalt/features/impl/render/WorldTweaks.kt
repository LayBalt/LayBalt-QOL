package com.laybalt.features.impl.render

import com.laybalt.config.types.ColorSetting
import com.laybalt.config.types.SliderSetting
import com.laybalt.config.types.ToggleSetting
import com.laybalt.features.Feature
import net.minecraft.client.renderer.fog.FogData
import java.awt.Color

/**
 * Custom open-world fog (distance + colour, optionally tinting the sky) and a client-side fixed time of day.
 *
 * Time is done on the 26.2 world-clock: every clock reading on the client is replaced by the chosen time
 * of the current day, so the real server time keeps being tracked and turning the option off restores it instantly.
 *
 * @see com.laybalt.mixin.MixinAtmosphericFogEnvironmentWorldVisuals
 * @see com.laybalt.mixin.MixinClientClockManagerWorldTweaks
 */
object WorldTweaks: Feature("Customises the open-world fog and its colour, and can lock the time of day on your client.") {
    private val customFog by ToggleSetting("Custom Fog", true)
        .withDescription("Replaces the open-world fog with your own distance and colour. Water, lava and effect fog stay vanilla").section("Fog")

    private val fogStart by SliderSetting("Fog Start", 0, 0, 256, 1, " blocks")
        .withDescription("Distance where the fog begins").showIf { customFog.value }

    private val fogEnd by SliderSetting("Fog End", 64, 10, 256, 1, " blocks")
        .withDescription("Distance where the fog becomes fully opaque").showIf { customFog.value }

    private val fogColor by ColorSetting("Fog Color", Color(0xA9, 0x00, 0xFF), false)
        .withDescription("Colour of the fog").showIf { customFog.value }

    private val tintSky by ToggleSetting("Tint Sky", true)
        .withDescription("Paints the sky and clouds with the fog colour as well").showIf { customFog.value }

    private val changeTime by ToggleSetting("Change Time")
        .withDescription("Locks the time of day on your client. The server time is not changed. Overrides the Time Changer feature while both are on").section("Time")

    private val time by SliderSetting("Time", 21, 0, 23, 1, ":00")
        .withDescription("Hour of the day to show. 6 is sunrise, 12 is noon, 18 is sunset, 0 is midnight").showIf { changeTime.value }

    private const val TICKS_PER_DAY = 24000L

    /** Applied after vanilla filled [fog] for the open world (no fluid / effect fog active). */
    @JvmStatic
    fun applyFog(fog: FogData) {
        if (! enabled || ! customFog.value) return
        val start = fogStart.value.toFloat()
        val end = fogEnd.value.toFloat().coerceAtLeast(start + 1f)
        val color = fogColor.value

        fog.environmentalStart = start
        fog.environmentalEnd = end
        fog.color.set(color.red / 255f, color.green / 255f, color.blue / 255f, 1f)

        if (tintSky.value) {
            fog.skyEnd = 0.001f
            fog.cloudEnd = minOf(fog.cloudEnd, end)
        }
    }

    /**
     * Maps a world-clock reading to the chosen hour of the same day. Minecraft day ticks start at 06:00,
     * so hour h is tick (h - 6) * 1000.
     */
    @JvmStatic
    fun modifyClockTicks(totalTicks: Long): Long {
        if (! enabled || ! changeTime.value) return totalTicks
        val dayStart = Math.floorDiv(totalTicks, TICKS_PER_DAY) * TICKS_PER_DAY
        val tickOfDay = Math.floorMod((time.value.toLong() - 6L) * 1000L, TICKS_PER_DAY)
        return dayStart + tickOfDay
    }
}
