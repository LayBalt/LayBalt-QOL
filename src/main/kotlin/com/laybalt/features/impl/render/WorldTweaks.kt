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
 * Sky and clouds: the sky disc colour is replaced after SkyRenderer sampled it, the cloud colour / height after the
 * level extractor read them from the camera environment attributes (a zero-alpha colour skips the cloud pass).
 *
 * @see com.laybalt.mixin.MixinAtmosphericFogEnvironmentWorldVisuals
 * @see com.laybalt.mixin.MixinClientClockManagerWorldTweaks
 * @see com.laybalt.mixin.MixinSkyRendererWorldTweaks
 * @see com.laybalt.mixin.MixinLevelExtractorWorldTweaks
 */
object WorldTweaks: Feature("Customises the open-world fog, sky and clouds, and can lock the time of day on your client.") {
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

    private val customSky by ToggleSetting("Custom Sky")
        .withDescription("Paints the sky with your own colour. Works without Custom Fog; with Tint Sky on the fog colour covers it near the horizon").section("Sky")

    private val skyColor by ColorSetting("Sky Color", Color(0x3A, 0x2A, 0x6E), false)
        .withDescription("Colour of the sky").showIf { customSky.value }

    private val hideClouds by ToggleSetting("Hide Clouds")
        .withDescription("Removes the clouds completely, whatever the vanilla Clouds option is set to").section("Clouds")

    private val customCloudHeight by ToggleSetting("Custom Cloud Height")
        .withDescription("Moves the cloud layer to another height").showIf { ! hideClouds.value }

    private val cloudHeight by SliderSetting("Cloud Height", 192, 0, 400, 1, " Y")
        .withDescription("Y level of the bottom of the clouds. Vanilla is about 192").showIf { ! hideClouds.value && customCloudHeight.value }

    private val customCloudColor by ToggleSetting("Custom Cloud Color")
        .withDescription("Recolours the clouds").showIf { ! hideClouds.value }

    private val cloudColor by ColorSetting("Cloud Color", Color(0xE6, 0xDC, 0xFF, 0xCC))
        .withDescription("Colour and transparency of the clouds").showIf { ! hideClouds.value && customCloudColor.value }

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

    /** ARGB sky disc colour for this frame; the vanilla alpha is kept. */
    @JvmStatic
    fun modifySkyColor(original: Int): Int {
        if (! enabled || ! customSky.value) return original
        return (original and - 0x1000000) or (skyColor.value.rgb and 0xFFFFFF)
    }

    /** ARGB cloud colour for this frame. 0 hides the clouds; dimensions without clouds (alpha 0) stay without them. */
    @JvmStatic
    fun modifyCloudColor(original: Int): Int {
        if (! enabled) return original
        if (hideClouds.value) return 0
        if (! customCloudColor.value || (original ushr 24) == 0) return original
        return cloudColor.value.rgb
    }

    @JvmStatic
    fun modifyCloudHeight(original: Float): Float {
        if (! enabled || hideClouds.value || ! customCloudHeight.value) return original
        return cloudHeight.value.toFloat()
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
