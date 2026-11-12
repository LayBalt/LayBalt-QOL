package com.laybalt.features.impl.misc

import com.laybalt.config.types.ToggleSetting
import com.laybalt.event.impl.TickEvent
import com.laybalt.features.Feature
import gg.essential.universal.UMinecraft

object AutoSprint: Feature("Automatically sprint for you.") {
    private val disableInWater by ToggleSetting("Disable In Water").withDescription("Stops sprinting while you are in water.")

    override fun init() {
        register<TickEvent.Start> {
            if (UMinecraft.currentScreenObj != null) return@register
            if (player.isSprinting) return@register
            if (disableInWater.value && player.isInWater) return@register
            mc.options.keySprint.isDown = true
        }
    }
}