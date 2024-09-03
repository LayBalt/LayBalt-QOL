package com.laybalt.GUI

import gg.essential.vigilance.Vigilant
import gg.essential.vigilance.data.Property
import gg.essential.vigilance.data.PropertyType
import java.io.File

object LBQConfig : Vigilant(File("./config/LBQConfig.toml")) {
    @Property(
            type = PropertyType.SWITCH,
            name = "Enable Auto Fishing",
            category = "Fishing",
            subcategory = "Auto Fishing"
    )
    var FishingSwitch = false

    @Property(
            type = PropertyType.NUMBER,
            name = "Fishing Reel Delay",
            description = "Delay when reeling in the fish (in ms).",
            category = "Fishing",
            subcategory = "Auto Fishing",
            min = 150,
            max = 600,
            increment = 25
    )
    var FishingReelDelayNumber = 250

    @Property(
            type = PropertyType.NUMBER,
            name = "Fishing Cast Delay",
            description = "Delay between each cast (in ms).",
            category = "Fishing",
            subcategory = "Auto Fishing",
            min = 150,
            max = 600,
            increment = 25
    )
    var FishingCastDelayNumber = 250

    @Property(
            type = PropertyType.SWITCH,
            name = "Shake head?",
            description = "Shakes head every 20-30 seconds when fishing.",
            category = "Fishing",
            subcategory = "Auto Fishing"
    )
    var FishingShakeHead = false

    @Property(
            type = PropertyType.NUMBER,
            name = "Fishing Attack Delay",
            description = "Delay between attacks during combat (in ms)",
            category = "Fishing",
            subcategory = "Auto Fishing",
            min = 250,
            max = 1000,
            increment = 50
    )
    var FishingAttackDelay = 500

    @Property(
            type = PropertyType.NUMBER,
            name = "Fishing Mob Detection Radius",
            description = "Radius for detecting hostile mobs while fishing",
            category = "Fishing",
            subcategory = "Auto Fishing",
            min = 1,
            max = 16
    )
    var FishingMobDetectionRadius = 5

    @Property(
            type = PropertyType.NUMBER,
            name = "Fishing Sword Slot",
            description = "Inventory slot number for the sword (1-9)",
            category = "Fishing",
            subcategory = "Auto Fishing",
            min = 1,
            max = 9
    )
    var FishingSwordSlot = 1

    @Property(
            type = PropertyType.NUMBER,
            name = "Fishing Rod Slot",
            description = "Inventory slot number for the fishing rod (1-9)",
            category = "Fishing",
            subcategory = "Auto Fishing",
            min = 1,
            max = 9
    )
    var FishingRodSlot = 2

    @Property(
            type = PropertyType.SWITCH,
            name = "Enable Auto Melody",
            category = "QOL",
            subcategory = "Auto Melody"
    )
    var MelodySwitch = false

    @Property(
            type = PropertyType.SLIDER,
            name = "Melody Delay",
            description = "Delay between each click.",
            category = "QOL",
            subcategory = "Auto Melody",
            min = 50,
            max = 300
    )
    var MelodyDelay = 125

    @Property(
            type = PropertyType.SWITCH,
            name = "Enable Dance Room Solver",
            description = "Soon...",
            category = "QOL",
            subcategory = "Dance Room Solver"
    )
    var DanseSolverSwitch = false

    @Property(
            type = PropertyType.SWITCH,
            name = "Enable Auto Enchanting",
            category = "QOL",
            subcategory = "Auto Enchanting"
    )
    var AutoExperimentSwitch = false

    @Property(
            type = PropertyType.SLIDER,
            name = "Click delay Auto Enchanting",
            category = "QOL",
            subcategory = "Auto Enchanting",
            min = 100,
            max = 400
    )
    var AutoExperimentSlider = 200

    @Property(
        type = PropertyType.SWITCH,
        name = "Enable Custom ESP",
        category = "QOL",
        subcategory = "ESP"
    )
    var CustomESP = false

    @Property(
        type = PropertyType.COLOR,
        name = "ESP Color",
        description = "Color of the ESP outline",
        category = "QOL",
        subcategory = "ESP"
    )
    var CustomESPColor = java.awt.Color(255, 0, 0) // Красный цвет по умолчанию

    @Property(
        type = PropertyType.SLIDER,
        name = "ESP Line Width",
        description = "Width of the ESP outline",
        category = "QOL",
        subcategory = "ESP",
        min = 1,
        max = 5
    )
    var CustomESPLineWidth = 2

    @Property(
        type = PropertyType.SWITCH,
        name = "ESP Through Walls",
        description = "Show ESP through walls",
        category = "QOL",
        subcategory = "ESP"
    )
    var CustomESPThroughWalls = true

    @Property(
        type = PropertyType.SWITCH,
        name = "ESP for Players",
        description = "Show ESP for players",
        category = "QOL",
        subcategory = "ESP"
    )
    var CustomESPPlayers = true

    @Property(
        type = PropertyType.SWITCH,
        name = "ESP for Mobs",
        description = "Show ESP for mobs",
        category = "QOL",
        subcategory = "ESP"
    )
    var CustomESPMobs = true

    @Property(
        type = PropertyType.SWITCH,
        name = "ESP for Items",
        description = "Show ESP for items",
        category = "QOL",
        subcategory = "ESP"
    )
    var CustomESPItems = false

    @Property(
            type = PropertyType.SWITCH,
            name = "Enable Auto Clicker (Left)",
            category = "Combat",
            subcategory = "Auto Clicker Left"
    )
    var LeftClickerSwitch = false

    @Property(
            type = PropertyType.NUMBER,
            name = "Auto Clicker CPS (Left)",
            category = "Combat",
            description = "It will have +- 2 CPS every time anyway!",
            subcategory = "Auto Clicker Left",
            min = 3,
            max = 100
    )
    var LeftClickNumber = 23

    @Property(
            type = PropertyType.SWITCH,
            name = "Enable Auto Clicker (Right)",
            category = "Combat",
            subcategory = "Auto Clicker Right"
    )
    var RightClickerSwitch = false

    @Property(
            type = PropertyType.NUMBER,
            name = "Auto Clicker CPS (Right)",
            category = "Combat",
            description = "It will have +- 2 CPS every time anyway!",
            subcategory = "Auto Clicker Right",
            min = 3,
            max = 100
    )
    var RightClickNumber = 25

    @Property(
        type = PropertyType.SWITCH,
        name = "Enable Vampire Slayer Helper",
        category = "Vampire Slayer",
        subcategory = "General"
    )
    var vampireSlayerHelperEnabled = false

    @Property(
        type = PropertyType.SLIDER,
        name = "Sword Slot",
        description = "Inventory slot number for the sword.",
        category = "Vampire Slayer",
        subcategory = "Slots",
        min = 1,
        max = 9
    )
    var vampireSlayerSwordSlot = 1

    @Property(
        type = PropertyType.SLIDER,
        name = "Healing Melon Slot",
        description = "Inventory slot number for the Healing Melon.",
        category = "Vampire Slayer",
        subcategory = "Slots",
        min = 1,
        max = 9
    )
    var vampireSlayerHealingMelonSlot = 1

    @Property(
        type = PropertyType.SLIDER,
        name = "Holy Ice Slot",
        description = "Inventory slot number for the Holy Ice. (TWINCLAWS)",
        category = "Vampire Slayer",
        subcategory = "Slots",
        min = 1,
        max = 9
    )
    var vampireSlayerHolyIceSlot = 3

    @Property(
        type = PropertyType.SWITCH,
        name = "Auto Jump",
        category = "Vampire Slayer",
        subcategory = "Actions"
    )
    var vampireSlayerAutoJump = false

    @Property(
        type = PropertyType.SWITCH,
        name = "Auto Sneak",
        category = "Vampire Slayer",
        subcategory = "Actions"
    )
    var vampireSlayerAutoSneak = false

    @Property(
        type = PropertyType.SWITCH,
        name = "Auto Click Up",
        category = "Vampire Slayer",
        subcategory = "Actions"
    )
    var vampireSlayerAutoClickUp = false

    @Property(
        type = PropertyType.SWITCH,
        name = "Auto Click Down",
        category = "Vampire Slayer",
        subcategory = "Actions"
    )
    var vampireSlayerAutoClickDown = false

    @Property(
        type = PropertyType.SWITCH,
        name = "Auto Heal",
        category = "Vampire Slayer",
        subcategory = "Actions"
    )
    var vampireSlayerAutoHeal = false

    @Property(
        type = PropertyType.SWITCH,
        name = "Auto Twin Claws",
        category = "Vampire Slayer",
        subcategory = "Actions"
    )
    var vampireSlayerAutoTwinClaws = false

    init {
        initialize()
    }
}