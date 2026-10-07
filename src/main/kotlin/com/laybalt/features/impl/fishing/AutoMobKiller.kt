package com.laybalt.features.impl.fishing

//#if CHEAT

import com.laybalt.config.types.DropdownSetting
import com.laybalt.config.types.SliderSetting
import com.laybalt.config.types.ToggleSetting
import com.laybalt.features.Feature

/**
 * Work in progress: the legacy LayBalt "Auto Mob Killer" was never implemented (empty class).
 * The settings are kept so users can pre-configure it; the killing logic comes later.
 * As a WIP feature it registers no listeners at all.
 */
object AutoMobKiller: Feature("Kills sea creatures near you while Auto Fishing is enabled.") {
    override val wip = true

    val attackDelay by SliderSetting("Attack Delay", 500, 250, 1000, 50, "ms")
        .withDescription("Time between two attacks on the target.")
    val radius by SliderSetting("Detection Radius", 6, 1, 12, 1, " blocks")
        .withDescription("Only mobs within this distance of you are attacked.")
    val swordSlot by SliderSetting("Sword Slot", 1, 1, 9, 1)
        .withDescription("Hotbar slot (1-9) of the weapon used to kill the mob.")
    val rodSlot by SliderSetting("Rod Slot", 2, 1, 9, 1)
        .withDescription("Hotbar slot (1-9) of your fishing rod, selected again after the kill.")
    val clickType by DropdownSetting("Attack With", 0, listOf("Left Click", "Right Click"))
        .withDescription("Left Click = melee hit, Right Click = use the weapon's ability (e.g. a mage weapon).")
    val rotationTime by SliderSetting("Rotation Time", 200, 50, 500, 10, "ms")
        .withDescription("How long turning towards the target takes.")
    val onlySeaCreatures by ToggleSetting("Only Sea Creatures", true)
        .withDescription("Only attacks mobs whose name tag is a known sea creature. Off = any hostile mob in range.")
}

//#endif
