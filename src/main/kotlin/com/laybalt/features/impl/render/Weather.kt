package com.laybalt.features.impl.render

import com.laybalt.config.types.DropdownSetting
import com.laybalt.config.types.SliderSetting
import com.laybalt.features.Feature
import net.minecraft.world.level.biome.Biome

/**
 * Client-side weather: clear skies while the server says it rains, or rain / thunder whenever you like.
 *
 * It changes the rain and thunder strength the client reads (sky darkening, rain sound and splashes, the falling
 * columns), not the server weather. Only hiding the falling rain and snow while keeping the rest is
 * No Render > Weather; that toggle still wins when both are on.
 *
 * @see com.laybalt.mixin.MixinLevelWeather
 * @see com.laybalt.mixin.MixinWeatherEffectRendererWeather
 */
object Weather: Feature("Sets the weather on your client: always clear, or rain and thunder whenever you want. The server weather is not changed.") {
    private val mode by DropdownSetting("Weather", 1, listOf("Server", "Clear", "Rain", "Thunder"))
        .withDescription("Server keeps the real weather, Clear removes rain and thunder, Rain and Thunder start them on your client only.")
    private val strength by SliderSetting("Strength", 100, 10, 100, 5, "%")
        .withDescription("How heavy the client-side rain or thunder is; also how much the sky darkens.").showIf { mode.value >= 2 }
    private val precipitation by DropdownSetting("Precipitation", 0, listOf("Biome", "Rain", "Snow"))
        .withDescription("What falls from the sky. Biome follows the biome (snow in cold biomes, nothing in deserts); Rain and Snow force one everywhere.")

    @JvmStatic
    fun overridesWeather() = enabled && mode.value != 0

    @JvmStatic
    fun rainLevel(): Float = when (mode.value) {
        2, 3 -> strength.value / 100f
        else -> 0f
    }

    @JvmStatic
    fun thunderLevel(): Float = if (mode.value == 3) strength.value / 100f else 0f

    @JvmStatic
    fun modifyPrecipitation(original: Biome.Precipitation): Biome.Precipitation {
        if (! enabled) return original
        return when (precipitation.value) {
            1 -> Biome.Precipitation.RAIN
            2 -> Biome.Precipitation.SNOW
            else -> original
        }
    }
}
