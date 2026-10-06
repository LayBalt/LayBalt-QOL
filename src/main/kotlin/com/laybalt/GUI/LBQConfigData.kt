package com.laybalt.GUI

import dev.isxander.yacl3.config.v2.api.SerialEntry

/**
 * Plain data holder that YACL serialises to `config/LBQConfig.json`.
 *
 * Field names are the JSON keys, so keep them stable. Everything else (the property facade used by
 * the rest of the mod and the config screen) lives in [LBQConfig].
 */
class LBQConfigData {
    // ---- Fishing / Auto Fishing
    @JvmField @field:SerialEntry var fishingSwitch: Boolean = false
    @JvmField @field:SerialEntry var fishingReelDelayNumber: Int = 250
    @JvmField @field:SerialEntry var fishingCastDelayNumber: Int = 250
    @JvmField @field:SerialEntry var fishingStopMoveHead: Boolean = false
    @JvmField @field:SerialEntry var fishingShakeHead: Boolean = true

    // ---- Fishing / Auto Mob Killer
    @JvmField @field:SerialEntry var fishingMobKillerSwitch: Boolean = false
    @JvmField @field:SerialEntry var fishingAttackDelay: Int = 500
    @JvmField @field:SerialEntry var fishingMobDetectionRadius: Int = 6
    @JvmField @field:SerialEntry var fishingSwordSlot: Int = 1
    @JvmField @field:SerialEntry var fishingRodSlot: Int = 2

    // ---- Fishing / Auto Path
    @JvmField @field:SerialEntry var autoPathSwitch: Boolean = true
    @JvmField @field:SerialEntry var largeSelector: Int = 0

    // ---- QOL
    @JvmField @field:SerialEntry var melodySwitch: Boolean = false
    @JvmField @field:SerialEntry var melodyDelay: Int = 125
    @JvmField @field:SerialEntry var danseSolverSwitch: Boolean = false
    @JvmField @field:SerialEntry var autoExperimentSwitch: Boolean = false
    @JvmField @field:SerialEntry var autoExperimentSlider: Int = 200
    @JvmField @field:SerialEntry var autoExperimentSerumSlider: Int = 3
    @JvmField @field:SerialEntry var customESP: Boolean = false
    /** ARGB colour (default: opaque red). */
    @JvmField @field:SerialEntry var customESPColor: Int = 0xFFFF0000.toInt()
    @JvmField @field:SerialEntry var customESPLineWidth: Int = 2
    @JvmField @field:SerialEntry var customESPThroughWalls: Boolean = true
    @JvmField @field:SerialEntry var customESPPlayers: Boolean = true
    @JvmField @field:SerialEntry var customESPMobs: Boolean = true
    @JvmField @field:SerialEntry var customESPItems: Boolean = false

    // ---- Combat
    @JvmField @field:SerialEntry var leftClickerSwitch: Boolean = false
    @JvmField @field:SerialEntry var leftClickNumber: Int = 23
    @JvmField @field:SerialEntry var rightClickerSwitch: Boolean = false
    @JvmField @field:SerialEntry var rightClickNumber: Int = 25

    // ---- Vampire Slayer
    @JvmField @field:SerialEntry var vampireSlayerHelperEnabled: Boolean = false
    @JvmField @field:SerialEntry var vampireSlayerSwordSlot: Int = 1
    @JvmField @field:SerialEntry var vampireSlayerHealingMelonSlot: Int = 1
    @JvmField @field:SerialEntry var vampireSlayerHolyIceSlot: Int = 3
    @JvmField @field:SerialEntry var vampireSlayerAutoJump: Boolean = false
    @JvmField @field:SerialEntry var vampireSlayerAutoSneak: Boolean = false
    @JvmField @field:SerialEntry var vampireSlayerAutoClickUp: Boolean = false
    @JvmField @field:SerialEntry var vampireSlayerAutoClickDown: Boolean = false
    @JvmField @field:SerialEntry var vampireSlayerAutoHeal: Boolean = false
    @JvmField @field:SerialEntry var vampireSlayerAutoTwinClaws: Boolean = false

    // ---- Click GUI
    /** RGB accent colour of the click GUI (default: LayBalt aqua). */
    @JvmField @field:SerialEntry var clickGuiAccentColor: Int = 0xFF2EC4F0.toInt()
    /** Feature order inside the category panels: 0 = A-Z, 1 = by width, 2 = as declared. */
    @JvmField @field:SerialEntry var clickGuiSorting: Int = 0
}
