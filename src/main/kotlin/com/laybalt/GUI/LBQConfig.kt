package com.laybalt.GUI

import gg.essential.vigilance.Vigilant
import gg.essential.vigilance.data.Property
import gg.essential.vigilance.data.PropertyType
import gg.essential.vigilance.gui.SettingsGui
import net.minecraft.client.gui.GuiScreen
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
}