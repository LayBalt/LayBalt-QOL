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

    init {
        initialize()
    }
}