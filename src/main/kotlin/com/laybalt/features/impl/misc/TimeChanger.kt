package com.laybalt.features.impl.misc

import com.laybalt.config.types.DropdownSetting
import com.laybalt.config.types.SliderSetting
import com.laybalt.features.Feature
import java.time.LocalTime

/**
 * NoammAddons 375e7e53 / 04d91dcd: the world time is no longer overwritten (the server's time packets kept fighting
 * the fake one, so the sky jittered). Only the overworld clock read while the sky and the fog colour are extracted
 * sees the chosen time; everything else keeps the server's time.
 *
 * @see com.laybalt.mixin.MixinSkyRendererTimeChanger
 * @see com.laybalt.mixin.MixinAtmosphericFogEnvironmentTimeChanger
 * @see com.laybalt.mixin.MixinAttributeTrackSampler
 */
object TimeChanger: Feature("Changes the world time.") {
    // NoammAddons 1729e9d0: "Custom" is appended last, so saved indexes 0..6 keep their meaning
    private val timeChangerMode by DropdownSetting("Time", 0, listOf("Day", "Noon", "Sunset", "Night", "Midnight", "Sunrise", "Real Time", "Custom"))
    private val customTime by SliderSetting("Custom Time", 6000, 0, 23999, 100).withDescription("The world time in ticks.").showIf { timeChangerMode.value == 7 }
    private val TIME_VALUES = longArrayOf(1000L, 6000L, 12000L, 13000L, 18000L, 23000L)

    /** True only while the sky or the atmospheric fog colour is being extracted with the feature on. */
    @JvmField var extractingSky = false

    @JvmStatic fun getTime() = when (timeChangerMode.value) {
        6 -> getTickTime()
        7 -> customTime.value.toLong()
        else -> TIME_VALUES.getOrElse(timeChangerMode.value) { TIME_VALUES[0] }
    }

    private fun getTickTime() = LocalTime.now().let { Math.floorMod(it.hour * 1000L + (it.minute * 16.66).toLong() - 6000, 24000L) }
}
