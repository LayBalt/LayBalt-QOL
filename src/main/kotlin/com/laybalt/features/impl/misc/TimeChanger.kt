package com.laybalt.features.impl.misc

import com.laybalt.config.types.DropdownSetting
import com.laybalt.features.Feature
import java.time.LocalTime

/**
 * @see com.laybalt.mixin.MixinClientPacketListener
 */
object TimeChanger: Feature("Changes the world time.") {
    private val timeChangerMode by DropdownSetting("Time", 0, listOf("Day", "Noon", "Sunset", "Night", "Midnight", "Sunrise", "Real Time")).onChange { setTime() }
    private val TIME_VALUES = longArrayOf(1000L, 6000L, 12000L, 13000L, 18000L, 23000L)

    override fun onEnable() {
        super.onEnable()
        setTime()
    }

    @JvmStatic
    fun setTime() {
        val customTime = TIME_VALUES.getOrElse(timeChangerMode.value) { getTickTime() }
        mc.level?.setTimeFromServer(customTime)
    }

    private fun getTickTime(): Long {
        val now = LocalTime.now()
        var ticks = (now.hour * 1000) + (now.minute * 16.66).toLong() - 6000
        if (ticks < 0) ticks += 24000
        return ticks
    }
}