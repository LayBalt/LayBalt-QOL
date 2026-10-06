package com.laybalt.GUI.clickgui

import com.laybalt.GUI.LBQConfig
import com.laybalt.GUI.clickgui.setting.*
import net.minecraft.client.Minecraft
import net.minecraft.util.Util
import java.awt.Color

/** Every feature shown in the click GUI, wired to the existing [LBQConfig] properties. */
object GuiFeatures {
    const val DISCORD_URL = "https://discord.gg/laybalt"
    val DEFAULT_ACCENT = Color(0x2E, 0xC4, 0xF0)
    val SORTING_OPTIONS = listOf("A-Z Sorting", "Width Sorting", "No Sorting")
    private const val BANNABLE = "§c<!> Might be bannable! <!>"

    val all: List<GuiFeature> by lazy { build() }

    fun byCategory(category: Category) = all.filter { it.category == category }

    val clickGui get() = all.first { it.category == Category.LAYBALT }

    private fun toggle(name: String, get: () -> Boolean, set: (Boolean) -> Unit) = ToggleSetting(name, get, set)
    private fun slider(name: String, min: Int, max: Int, step: Int, suffix: String, get: () -> Int, set: (Int) -> Unit) =
        SliderSetting(name, min, max, step, suffix, get, set)

    private fun slot(name: String, get: () -> Int, set: (Int) -> Unit) =
        slider(name, 1, 9, 1, "", get, set).withDescription("Hotbar slot (1-9).")

    private fun build() = listOf(
        // ------------------------------------------------------------ Fishing
        GuiFeature(
            "Auto Fishing", "Automatically casts and reels in your fishing rod.", Category.FISHING,
            { LBQConfig.FishingSwitch }, { LBQConfig.FishingSwitch = it },
            listOf(
                KeybindSetting("Toggle Keybind", "key.lbqmod.auto_fishing"),
                slider("Reel Delay", 150, 600, 25, "ms", { LBQConfig.FishingReelDelayNumber }, { LBQConfig.FishingReelDelayNumber = it })
                    .withDescription("Delay when reeling in the fish."),
                slider("Cast Delay", 150, 600, 25, "ms", { LBQConfig.FishingCastDelayNumber }, { LBQConfig.FishingCastDelayNumber = it })
                    .withDescription("Delay between each cast."),
                toggle("Stop Moving Head", { LBQConfig.FishingStopMoveHead }, { LBQConfig.FishingStopMoveHead = it })
                    .withDescription("Sets mouse sensitivity to 0 while fishing."),
                toggle("Shake Head", { LBQConfig.FishingShakeHead }, { LBQConfig.FishingShakeHead = it })
                    .withDescription("Shakes your head every 20-30 seconds while fishing.")
            )
        ),
        GuiFeature(
            "Auto Path", "Walks the selected fishing route. Works only while Auto Fishing is enabled.", Category.FISHING,
            { LBQConfig.AutoPathSwitch }, { LBQConfig.AutoPathSwitch = it },
            listOf(
                DropdownSetting("Path", LBQConfig.AUTO_PATH_OPTIONS, { LBQConfig.largeSelector }, { LBQConfig.largeSelector = it })
                    .withDescription("Where you want to fish. The script walks the path you choose.")
            )
        ),
        GuiFeature(
            "Auto Mob Killer", "Kills sea creatures near you. Works only while Auto Fishing is enabled.", Category.FISHING,
            { LBQConfig.FishingMobKillerSwitch }, { LBQConfig.FishingMobKillerSwitch = it },
            listOf(
                slider("Attack Delay", 250, 1000, 50, "ms", { LBQConfig.FishingAttackDelay }, { LBQConfig.FishingAttackDelay = it })
                    .withDescription("Delay between attacks during combat."),
                slider("Detection Radius", 1, 6, 1, " blocks", { LBQConfig.FishingMobDetectionRadius }, { LBQConfig.FishingMobDetectionRadius = it })
                    .withDescription("Radius for detecting hostile mobs while fishing."),
                slot("Sword Slot", { LBQConfig.FishingSwordSlot }, { LBQConfig.FishingSwordSlot = it }),
                slot("Rod Slot", { LBQConfig.FishingRodSlot }, { LBQConfig.FishingRodSlot = it })
            )
        ),

        // ------------------------------------------------------------ QOL
        GuiFeature(
            "Auto Melody", "Plays the Melody harp minigame for you.", Category.QOL,
            { LBQConfig.MelodySwitch }, { LBQConfig.MelodySwitch = it },
            listOf(
                KeybindSetting("Toggle Keybind", "key.lbqmod.auto_melody"),
                slider("Click Delay", 50, 300, 1, "ms", { LBQConfig.MelodyDelay }, { LBQConfig.MelodyDelay = it })
                    .withDescription("Delay between each click.")
            )
        ),
        GuiFeature(
            "Dance Room Solver", "Coming soon.", Category.QOL,
            { LBQConfig.DanseSolverSwitch }, { LBQConfig.DanseSolverSwitch = it },
            emptyList()
        ),
        GuiFeature(
            "Auto Enchanting", "Solves the Experimentation Table add-ons for you.", Category.QOL,
            { LBQConfig.AutoExperimentSwitch }, { LBQConfig.AutoExperimentSwitch = it },
            listOf(
                KeybindSetting("Toggle Keybind", "key.lbqmod.auto_experiment"),
                slider("Click Delay", 100, 400, 1, "ms", { LBQConfig.AutoExperimentSlider }, { LBQConfig.AutoExperimentSlider = it }),
                slider("Serums Consumed", 0, 3, 1, "", { LBQConfig.AutoExperimentSerumSlider }, { LBQConfig.AutoExperimentSerumSlider = it })
                    .withDescription("How many serums you have consumed (reduces total clicks in experiments).")
            )
        ),
        GuiFeature(
            "Custom ESP", "Outlines entities. MIDDLE CLICK a mob to add or remove it from the ESP.", Category.QOL,
            { LBQConfig.CustomESP }, { LBQConfig.CustomESP = it },
            listOf(
                ColorSetting("ESP Color", true, { LBQConfig.CustomESPColor }, { LBQConfig.CustomESPColor = it })
                    .withDescription("Color of the ESP outline."),
                slider("Line Width", 1, 5, 1, "", { LBQConfig.CustomESPLineWidth }, { LBQConfig.CustomESPLineWidth = it })
                    .withDescription("Width of the ESP outline."),
                toggle("Through Walls", { LBQConfig.CustomESPThroughWalls }, { LBQConfig.CustomESPThroughWalls = it })
                    .withDescription("Show the ESP through walls.").section("Rendering"),
                toggle("Players", { LBQConfig.CustomESPPlayers }, { LBQConfig.CustomESPPlayers = it }).section("Targets"),
                toggle("Mobs", { LBQConfig.CustomESPMobs }, { LBQConfig.CustomESPMobs = it }).section("Targets"),
                toggle("Items", { LBQConfig.CustomESPItems }, { LBQConfig.CustomESPItems = it }).section("Targets")
            )
        ),

        // ------------------------------------------------------------ Combat
        GuiFeature(
            "Left Auto Clicker", "Clicks the left mouse button for you. $BANNABLE", Category.COMBAT,
            { LBQConfig.LeftClickerSwitch }, { LBQConfig.LeftClickerSwitch = it },
            listOf(
                KeybindSetting("Toggle Keybind", "key.lbqmod.auto_clicker_left"),
                slider("CPS", 3, 100, 1, "", { LBQConfig.LeftClickNumber }, { LBQConfig.LeftClickNumber = it })
                    .withDescription("Clicks per second. It always varies by +-2 CPS.")
            )
        ),
        GuiFeature(
            "Right Auto Clicker", "Clicks the right mouse button for you. $BANNABLE", Category.COMBAT,
            { LBQConfig.RightClickerSwitch }, { LBQConfig.RightClickerSwitch = it },
            listOf(
                KeybindSetting("Toggle Keybind", "key.lbqmod.auto_clicker_right"),
                slider("CPS", 3, 100, 1, "", { LBQConfig.RightClickNumber }, { LBQConfig.RightClickNumber = it })
                    .withDescription("Clicks per second. It always varies by +-2 CPS.")
            )
        ),

        // ------------------------------------------------------------ Slayer
        GuiFeature(
            "Vampire Helper", "Helps with the Riftstalker Bloodfiend (Vampire Slayer).", Category.SLAYER,
            { LBQConfig.vampireSlayerHelperEnabled }, { LBQConfig.vampireSlayerHelperEnabled = it },
            listOf(
                KeybindSetting("Toggle Keybind", "key.lbqmod.vampire_helper"),
                slot("Sword Slot", { LBQConfig.vampireSlayerSwordSlot }, { LBQConfig.vampireSlayerSwordSlot = it }).section("Slots"),
                slot("Healing Melon Slot", { LBQConfig.vampireSlayerHealingMelonSlot }, { LBQConfig.vampireSlayerHealingMelonSlot = it }).section("Slots"),
                slot("Holy Ice Slot", { LBQConfig.vampireSlayerHolyIceSlot }, { LBQConfig.vampireSlayerHolyIceSlot = it })
                    .withDescription("Hotbar slot (1-9) of the Holy Ice, used for Twinclaws.").section("Slots"),
                toggle("Auto Jump", { LBQConfig.vampireSlayerAutoJump }, { LBQConfig.vampireSlayerAutoJump = it }).section("Actions"),
                toggle("Auto Sneak", { LBQConfig.vampireSlayerAutoSneak }, { LBQConfig.vampireSlayerAutoSneak = it }).section("Actions"),
                toggle("Auto Click Up", { LBQConfig.vampireSlayerAutoClickUp }, { LBQConfig.vampireSlayerAutoClickUp = it }).section("Actions"),
                toggle("Auto Click Down", { LBQConfig.vampireSlayerAutoClickDown }, { LBQConfig.vampireSlayerAutoClickDown = it }).section("Actions"),
                toggle("Auto Heal", { LBQConfig.vampireSlayerAutoHeal }, { LBQConfig.vampireSlayerAutoHeal = it }).section("Actions"),
                toggle("Auto Twin Claws", { LBQConfig.vampireSlayerAutoTwinClaws }, { LBQConfig.vampireSlayerAutoTwinClaws = it }).section("Actions")
            )
        ),

        // ------------------------------------------------------------ LayBalt
        GuiFeature(
            "Click GUI", "Settings of this menu.", Category.LAYBALT,
            null, null,
            listOf(
                KeybindSetting("Open Keybind", "key.lbqmod.open_config"),
                ColorSetting("Accent Color", false, { LBQConfig.ClickGuiAccentColor }, { LBQConfig.ClickGuiAccentColor = it })
                    .withDescription("The accent color used by the whole menu."),
                DropdownSetting("Sorting", SORTING_OPTIONS, { LBQConfig.ClickGuiSorting }, { LBQConfig.ClickGuiSorting = it })
                    .withDescription("The order of the features in the panels."),
                ButtonSetting("Reset Accent Color") { LBQConfig.ClickGuiAccentColor = DEFAULT_ACCENT },
                ButtonSetting("Classic Settings") {
                    val mc = Minecraft.getInstance()
                    mc.gui.setScreen(LBQConfig.createClassicScreen(mc.gui.screen()))
                }.withDescription("Opens the previous list-style settings screen."),
                ButtonSetting("Discord") { Util.getPlatform().openUri(DISCORD_URL) }
                    .withDescription("For more help, join our Discord server.")
            )
        )
    )
}
