package com.laybalt.features.impl.fishing

//#if CHEAT

import com.laybalt.LayBaltQOL
import com.laybalt.config.types.ButtonSetting
import com.laybalt.config.types.DropdownSetting
import com.laybalt.config.types.SliderSetting
import com.laybalt.features.Feature
import com.laybalt.ui.notification.NotificationManager

/**
 * Work in progress: the legacy LayBalt "Auto Path" never had an implementation (empty class, no route data).
 * The settings are kept so users can pre-configure the route; the walking logic and route recording come later.
 * As a WIP feature it registers no listeners at all.
 */
object AutoPath: Feature("Walks a recorded route to the fishing spot. Works only while Auto Fishing is enabled.") {
    override val wip = true

    val routes = listOf(
        "Hub - Wizard Tower",
        "Hub - The Barn",
        "The Park - Pond",
        "Spider's Den - Pond",
        "Mushroom Desert - Pond",
        "Custom - 1",
        "Custom - 2",
        "Custom - 3"
    )

    val route by DropdownSetting("Route", 0, routes)
        .withDescription("The route to walk to the fishing spot. The Custom routes are recorded by yourself.")
    val rotationTime by SliderSetting("Rotation Time", 300, 100, 1000, 50, "ms")
        .withDescription("How long turning towards the next waypoint takes. Higher looks more human.")

    private val recordRoute by ButtonSetting("Record Custom Route") { notReady() }
        .withDescription("Starts recording the selected Custom route from your current position (not available yet).")
    private val stopRecording by ButtonSetting("Stop Recording") { notReady() }
        .withDescription("Stops recording and saves the selected Custom route (not available yet).")
    private val clearRoute by ButtonSetting("Clear Custom Route") { notReady() }
        .withDescription("Deletes the waypoints of the selected Custom route (not available yet).")

    private fun notReady() = NotificationManager.push(LayBaltQOL.MOD_NAME, "$name is work in progress: route recording is not available yet.")
}

//#endif
