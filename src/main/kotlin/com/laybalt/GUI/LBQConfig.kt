package com.laybalt.GUI

import com.laybalt.GUI.clickgui.ClickGuiScreen
import dev.isxander.yacl3.api.ConfigCategory
import dev.isxander.yacl3.api.Option
import dev.isxander.yacl3.api.OptionDescription
import dev.isxander.yacl3.api.OptionEventListener
import dev.isxander.yacl3.api.OptionGroup
import dev.isxander.yacl3.api.YetAnotherConfigLib
import dev.isxander.yacl3.api.controller.BooleanControllerBuilder
import dev.isxander.yacl3.api.controller.ColorControllerBuilder
import dev.isxander.yacl3.api.controller.CyclingListControllerBuilder
import dev.isxander.yacl3.api.controller.IntegerSliderControllerBuilder
import dev.isxander.yacl3.config.v2.api.ConfigClassHandler
import dev.isxander.yacl3.config.v2.api.serializer.GsonConfigSerializerBuilder
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import java.awt.Color

/**
 * Mod configuration.
 *
 * The 1.8.9 version used Essential Vigilance; this version stores the values in
 * `config/LBQConfig.json` through YACL and builds the config screen with the YACL API.
 * The property names are kept identical to the old Vigilance config so the feature code can keep
 * using `LBQConfig.FishingSwitch` (Kotlin) / `LBQConfig.INSTANCE.getFishingSwitch()` (Java).
 */
object LBQConfig {
    private const val MOD_ID = "lbqmod"

    @JvmField
    val HANDLER: ConfigClassHandler<LBQConfigData> = ConfigClassHandler.createBuilder(LBQConfigData::class.java)
        .id(Identifier.fromNamespaceAndPath(MOD_ID, "config"))
        .serializer { handler ->
            GsonConfigSerializerBuilder.create(handler)
                .setPath(FabricLoader.getInstance().configDir.resolve("LBQConfig.json"))
                .build()
        }
        .build()

    /** Names shown for the "Choose a path for AutoFishing" selector; [largeSelector] is an index into it. */
    @JvmField
    val AUTO_PATH_OPTIONS: List<String> = listOf(
        "Hub - Wizard Tower",
        "Hub - The Barn",
        "The Park - Pond",
        "Spider's Den - Pond",
        "Mushroom Desert - Pond",
        "Custom - 1",
        "Custom - 2",
        "Custom - 3"
    )

    private val data: LBQConfigData get() = HANDLER.instance()
    private val defaults: LBQConfigData get() = HANDLER.defaults()

    /** Loads the config from disk (creates it with defaults when missing). Call once at startup. */
    fun load() {
        HANDLER.load()
    }

    /** Writes the current values to disk. */
    fun save() {
        HANDLER.save()
    }

    // ------------------------------------------------------------------ Fishing
    var FishingSwitch: Boolean
        get() = data.fishingSwitch
        set(value) { data.fishingSwitch = value }
    var FishingReelDelayNumber: Int
        get() = data.fishingReelDelayNumber
        set(value) { data.fishingReelDelayNumber = value }
    var FishingCastDelayNumber: Int
        get() = data.fishingCastDelayNumber
        set(value) { data.fishingCastDelayNumber = value }
    var FishingStopMoveHead: Boolean
        get() = data.fishingStopMoveHead
        set(value) { data.fishingStopMoveHead = value }
    var FishingShakeHead: Boolean
        get() = data.fishingShakeHead
        set(value) { data.fishingShakeHead = value }
    var FishingMobKillerSwitch: Boolean
        get() = data.fishingMobKillerSwitch
        set(value) { data.fishingMobKillerSwitch = value }
    var FishingAttackDelay: Int
        get() = data.fishingAttackDelay
        set(value) { data.fishingAttackDelay = value }
    var FishingMobDetectionRadius: Int
        get() = data.fishingMobDetectionRadius
        set(value) { data.fishingMobDetectionRadius = value }
    var FishingSwordSlot: Int
        get() = data.fishingSwordSlot
        set(value) { data.fishingSwordSlot = value }
    var FishingRodSlot: Int
        get() = data.fishingRodSlot
        set(value) { data.fishingRodSlot = value }
    var AutoPathSwitch: Boolean
        get() = data.autoPathSwitch
        set(value) { data.autoPathSwitch = value }
    var largeSelector: Int
        get() = data.largeSelector
        set(value) { data.largeSelector = value }

    // ------------------------------------------------------------------ QOL
    var MelodySwitch: Boolean
        get() = data.melodySwitch
        set(value) { data.melodySwitch = value }
    var MelodyDelay: Int
        get() = data.melodyDelay
        set(value) { data.melodyDelay = value }
    var DanseSolverSwitch: Boolean
        get() = data.danseSolverSwitch
        set(value) { data.danseSolverSwitch = value }
    var AutoExperimentSwitch: Boolean
        get() = data.autoExperimentSwitch
        set(value) { data.autoExperimentSwitch = value }
    var AutoExperimentSlider: Int
        get() = data.autoExperimentSlider
        set(value) { data.autoExperimentSlider = value }
    var AutoExperimentSerumSlider: Int
        get() = data.autoExperimentSerumSlider
        set(value) { data.autoExperimentSerumSlider = value }
    var CustomESP: Boolean
        get() = data.customESP
        set(value) { data.customESP = value }
    var CustomESPColor: Color
        get() = Color(data.customESPColor, true)
        set(value) { data.customESPColor = value.rgb }
    var CustomESPLineWidth: Int
        get() = data.customESPLineWidth
        set(value) { data.customESPLineWidth = value }
    var CustomESPThroughWalls: Boolean
        get() = data.customESPThroughWalls
        set(value) { data.customESPThroughWalls = value }
    var CustomESPPlayers: Boolean
        get() = data.customESPPlayers
        set(value) { data.customESPPlayers = value }
    var CustomESPMobs: Boolean
        get() = data.customESPMobs
        set(value) { data.customESPMobs = value }
    var CustomESPItems: Boolean
        get() = data.customESPItems
        set(value) { data.customESPItems = value }

    // ------------------------------------------------------------------ Combat
    var LeftClickerSwitch: Boolean
        get() = data.leftClickerSwitch
        set(value) { data.leftClickerSwitch = value }
    var LeftClickNumber: Int
        get() = data.leftClickNumber
        set(value) { data.leftClickNumber = value }
    var RightClickerSwitch: Boolean
        get() = data.rightClickerSwitch
        set(value) { data.rightClickerSwitch = value }
    var RightClickNumber: Int
        get() = data.rightClickNumber
        set(value) { data.rightClickNumber = value }

    // ------------------------------------------------------------------ Vampire Slayer
    var vampireSlayerHelperEnabled: Boolean
        get() = data.vampireSlayerHelperEnabled
        set(value) { data.vampireSlayerHelperEnabled = value }
    var vampireSlayerSwordSlot: Int
        get() = data.vampireSlayerSwordSlot
        set(value) { data.vampireSlayerSwordSlot = value }
    var vampireSlayerHealingMelonSlot: Int
        get() = data.vampireSlayerHealingMelonSlot
        set(value) { data.vampireSlayerHealingMelonSlot = value }
    var vampireSlayerHolyIceSlot: Int
        get() = data.vampireSlayerHolyIceSlot
        set(value) { data.vampireSlayerHolyIceSlot = value }
    var vampireSlayerAutoJump: Boolean
        get() = data.vampireSlayerAutoJump
        set(value) { data.vampireSlayerAutoJump = value }
    var vampireSlayerAutoSneak: Boolean
        get() = data.vampireSlayerAutoSneak
        set(value) { data.vampireSlayerAutoSneak = value }
    var vampireSlayerAutoClickUp: Boolean
        get() = data.vampireSlayerAutoClickUp
        set(value) { data.vampireSlayerAutoClickUp = value }
    var vampireSlayerAutoClickDown: Boolean
        get() = data.vampireSlayerAutoClickDown
        set(value) { data.vampireSlayerAutoClickDown = value }
    var vampireSlayerAutoHeal: Boolean
        get() = data.vampireSlayerAutoHeal
        set(value) { data.vampireSlayerAutoHeal = value }
    var vampireSlayerAutoTwinClaws: Boolean
        get() = data.vampireSlayerAutoTwinClaws
        set(value) { data.vampireSlayerAutoTwinClaws = value }

    // ------------------------------------------------------------------ Click GUI
    var ClickGuiAccentColor: Color
        get() = Color(data.clickGuiAccentColor)
        set(value) { data.clickGuiAccentColor = value.rgb }
    var ClickGuiSorting: Int
        get() = data.clickGuiSorting
        set(value) { data.clickGuiSorting = value }

    // ------------------------------------------------------------------ Screen

    /** Builds the config screen. `parent` is the screen to return to when closed (may be null). */
    @JvmStatic
    fun createScreen(parent: Screen?): Screen = ClickGuiScreen(parent)

    /** The previous YACL list-style screen, still reachable from the click GUI settings. */
    @JvmStatic
    fun createClassicScreen(parent: Screen?): Screen = buildYacl().generateScreen(parent)

    private fun text(s: String): Component = Component.literal(s)

    private fun description(vararg lines: String): OptionDescription =
        if (lines.isEmpty()) OptionDescription.EMPTY
        else OptionDescription.of(*lines.map { Component.literal(it) }.toTypedArray())

    private fun switch(
        name: String,
        description: String?,
        default: Boolean,
        getter: () -> Boolean,
        setter: (Boolean) -> Unit
    ): Option<Boolean> =
        Option.createBuilder<Boolean>()
            .name(text(name))
            .description(if (description == null) OptionDescription.EMPTY else description(description))
            .binding(default, { getter() }, { setter(it) })
            .controller { opt -> BooleanControllerBuilder.create(opt).coloured(true).onOffFormatter() }
            .build()

    private fun slider(
        name: String,
        description: String?,
        default: Int,
        min: Int,
        max: Int,
        step: Int,
        getter: () -> Int,
        setter: (Int) -> Unit
    ): Option<Int> =
        Option.createBuilder<Int>()
            .name(text(name))
            .description(if (description == null) OptionDescription.EMPTY else description(description))
            .binding(default, { getter() }, { setter(it) })
            .controller { opt -> IntegerSliderControllerBuilder.create(opt).range(min, max).step(step) }
            .build()

    private fun selector(
        name: String,
        description: String?,
        default: Int,
        options: List<String>,
        getter: () -> Int,
        setter: (Int) -> Unit
    ): Option<Int> =
        Option.createBuilder<Int>()
            .name(text(name))
            .description(if (description == null) OptionDescription.EMPTY else description(description))
            .binding(default, { getter() }, { setter(it) })
            .controller { opt ->
                CyclingListControllerBuilder.create(opt)
                    .values(options.indices.toList())
                    .formatValue { index -> text(options.getOrNull(index) ?: "Unknown") }
            }
            .build()

    private fun color(
        name: String,
        description: String?,
        default: Color,
        getter: () -> Color,
        setter: (Color) -> Unit
    ): Option<Color> =
        Option.createBuilder<Color>()
            .name(text(name))
            .description(if (description == null) OptionDescription.EMPTY else description(description))
            .binding(default, { getter() }, { setter(it) })
            .controller { opt -> ColorControllerBuilder.create(opt).allowAlpha(true) }
            .build()

    /** Equivalent of Vigilance `addDependency`: the children are only editable while `parent` is on. */
    private fun dependOn(parent: Option<Boolean>, parentValue: Boolean, vararg children: Option<*>) {
        children.forEach { it.setAvailable(parentValue) }
        parent.addEventListener { option, event ->
            if (event == OptionEventListener.Event.INITIAL || event == OptionEventListener.Event.STATE_CHANGE) {
                val value = option.pendingValue()
                children.forEach { it.setAvailable(value) }
            }
        }
    }

    private fun group(name: String, description: OptionDescription, vararg options: Option<*>): OptionGroup =
        OptionGroup.createBuilder()
            .name(text(name))
            .description(description)
            .options(options.toList())
            .build()

    private fun group(name: String, vararg options: Option<*>): OptionGroup =
        group(name, OptionDescription.EMPTY, *options)

    private const val DISCORD_LINE_1 = "For more help, go to our discord server."
    private const val DISCORD_LINE_2 = "[!] discord.gg/laybalt [!]"

    private fun buildYacl(): YetAnotherConfigLib {
        val d = data
        val def = defaults

        // ---------------------------------------------------------------- Fishing
        val fishingSwitch = switch("Enable Auto Fishing", null, def.fishingSwitch, { d.fishingSwitch }, { d.fishingSwitch = it })
        val fishingReelDelay = slider("Fishing Reel Delay", "Delay when reeling in the fish (in ms).", def.fishingReelDelayNumber, 150, 600, 25, { d.fishingReelDelayNumber }, { d.fishingReelDelayNumber = it })
        val fishingCastDelay = slider("Fishing Cast Delay", "Delay between each cast (in ms).", def.fishingCastDelayNumber, 150, 600, 25, { d.fishingCastDelayNumber }, { d.fishingCastDelayNumber = it })
        val fishingStopMoveHead = switch("Stop moving head?", "Sets mouse sensitivity to 0 while fishing", def.fishingStopMoveHead, { d.fishingStopMoveHead }, { d.fishingStopMoveHead = it })
        val fishingShakeHead = switch("Shake head?", "Shakes head every 20-30 seconds when fishing.", def.fishingShakeHead, { d.fishingShakeHead }, { d.fishingShakeHead = it })

        val autoPathSwitch = switch("Enable Auto Path", "Works only while Auto Fishing is enabled.", def.autoPathSwitch, { d.autoPathSwitch }, { d.autoPathSwitch = it })
        val autoPathSelector = selector("Choose a path for AutoFishing", "Select where you want to fish. The script will automatically go by the path you choose.", def.largeSelector, AUTO_PATH_OPTIONS, { d.largeSelector }, { d.largeSelector = it })
        dependOn(autoPathSwitch, d.autoPathSwitch, autoPathSelector)

        val mobKillerSwitch = switch("Enable Auto Mob Killer", "Works only while Auto Fishing is enabled.", def.fishingMobKillerSwitch, { d.fishingMobKillerSwitch }, { d.fishingMobKillerSwitch = it })
        val mobKillerAttackDelay = slider("Fishing Attack Delay", "Delay between attacks during combat (in ms)", def.fishingAttackDelay, 250, 1000, 50, { d.fishingAttackDelay }, { d.fishingAttackDelay = it })
        val mobKillerRadius = slider("Fishing Mob Detection Radius", "Radius for detecting hostile mobs while fishing", def.fishingMobDetectionRadius, 1, 6, 1, { d.fishingMobDetectionRadius }, { d.fishingMobDetectionRadius = it })
        val mobKillerSwordSlot = slider("Fishing Sword Slot", "Inventory slot number for the sword (1-9)", def.fishingSwordSlot, 1, 9, 1, { d.fishingSwordSlot }, { d.fishingSwordSlot = it })
        val mobKillerRodSlot = slider("Fishing Rod Slot", "Inventory slot number for the fishing rod (1-9)", def.fishingRodSlot, 1, 9, 1, { d.fishingRodSlot }, { d.fishingRodSlot = it })
        dependOn(mobKillerSwitch, d.fishingMobKillerSwitch, mobKillerAttackDelay, mobKillerRadius, mobKillerSwordSlot, mobKillerRodSlot)

        val fishingCategory = ConfigCategory.createBuilder()
            .name(text("Fishing"))
            .tooltip(text("Settings for the fishing module."), text(DISCORD_LINE_1), text(DISCORD_LINE_2))
            .group(group("Auto Fishing", fishingSwitch, fishingReelDelay, fishingCastDelay, fishingStopMoveHead, fishingShakeHead))
            .group(group("Auto Path", autoPathSwitch, autoPathSelector))
            .group(group("Auto Mob Killer", mobKillerSwitch, mobKillerAttackDelay, mobKillerRadius, mobKillerSwordSlot, mobKillerRodSlot))
            .build()

        // ---------------------------------------------------------------- QOL
        val melodySwitch = switch("Enable Auto Melody", null, def.melodySwitch, { d.melodySwitch }, { d.melodySwitch = it })
        val melodyDelay = slider("Melody Delay", "Delay between each click.", def.melodyDelay, 50, 300, 1, { d.melodyDelay }, { d.melodyDelay = it })

        val danceSolverSwitch = switch("Enable Dance Room Solver", "Soon...", def.danseSolverSwitch, { d.danseSolverSwitch }, { d.danseSolverSwitch = it })

        val experimentSwitch = switch("Enable Auto Enchanting", null, def.autoExperimentSwitch, { d.autoExperimentSwitch }, { d.autoExperimentSwitch = it })
        val experimentDelay = slider("Click delay Auto Enchanting", null, def.autoExperimentSlider, 100, 400, 1, { d.autoExperimentSlider }, { d.autoExperimentSlider = it })
        val experimentSerum = slider("Eated serum(s)", "How much serum you eated? (Reduces total clicks in experiments.)", def.autoExperimentSerumSlider, 0, 3, 1, { d.autoExperimentSerumSlider }, { d.autoExperimentSerumSlider = it })

        val espSwitch = switch("Enable Custom ESP", null, def.customESP, { d.customESP }, { d.customESP = it })
        val espColor = color("ESP Color", "Color of the ESP outline", Color(def.customESPColor, true), { Color(d.customESPColor, true) }, { d.customESPColor = it.rgb })
        val espLineWidth = slider("ESP Line Width", "Width of the ESP outline", def.customESPLineWidth, 1, 5, 1, { d.customESPLineWidth }, { d.customESPLineWidth = it })
        val espThroughWalls = switch("ESP Through Walls", "Show ESP through walls", def.customESPThroughWalls, { d.customESPThroughWalls }, { d.customESPThroughWalls = it })
        val espPlayers = switch("ESP for Players", "Show ESP for players", def.customESPPlayers, { d.customESPPlayers }, { d.customESPPlayers = it })
        val espMobs = switch("ESP for Mobs", "Show ESP for mobs", def.customESPMobs, { d.customESPMobs }, { d.customESPMobs = it })
        val espItems = switch("ESP for Items", "Show ESP for items", def.customESPItems, { d.customESPItems }, { d.customESPItems = it })

        val qolCategory = ConfigCategory.createBuilder()
            .name(text("QOL"))
            .tooltip(text("Quality of Life settings."), text(DISCORD_LINE_1), text(DISCORD_LINE_2))
            .group(group("Auto Melody", melodySwitch, melodyDelay))
            .group(group("Dance Room Solver", danceSolverSwitch))
            .group(group("Auto Enchanting", experimentSwitch, experimentDelay, experimentSerum))
            .group(
                group(
                    "ESP",
                    description(
                        "Settings for the ESP module.",
                        "When it's on you should MIDDLE CLICK on a mob to add/remove it to ESP.",
                        DISCORD_LINE_1,
                        DISCORD_LINE_2
                    ),
                    espSwitch, espColor, espLineWidth, espThroughWalls, espPlayers, espMobs, espItems
                )
            )
            .build()

        // ---------------------------------------------------------------- Combat
        val leftClickerSwitch = switch("Enable Auto Clicker (Left)", null, def.leftClickerSwitch, { d.leftClickerSwitch }, { d.leftClickerSwitch = it })
        val leftClickerCps = slider("Auto Clicker CPS (Left)", "It will have +- 2 CPS every time anyway!", def.leftClickNumber, 3, 100, 1, { d.leftClickNumber }, { d.leftClickNumber = it })
        val rightClickerSwitch = switch("Enable Auto Clicker (Right)", null, def.rightClickerSwitch, { d.rightClickerSwitch }, { d.rightClickerSwitch = it })
        val rightClickerCps = slider("Auto Clicker CPS (Right)", "It will have +- 2 CPS every time anyway!", def.rightClickNumber, 3, 100, 1, { d.rightClickNumber }, { d.rightClickNumber = it })

        val combatCategory = ConfigCategory.createBuilder()
            .name(text("Combat"))
            .tooltip(text("Settings for the combat module."), text(DISCORD_LINE_1), text(DISCORD_LINE_2))
            .group(group("Auto Clicker Left", description("Settings for the left auto clicker. <!> MIGHT BE BANNABLE! <!>"), leftClickerSwitch, leftClickerCps))
            .group(group("Auto Clicker Right", description("Settings for the right auto clicker. <!> MIGHT BE BANNABLE! <!>"), rightClickerSwitch, rightClickerCps))
            .build()

        // ---------------------------------------------------------------- Vampire Slayer
        val vampEnabled = switch("Enable Vampire Slayer Helper", null, def.vampireSlayerHelperEnabled, { d.vampireSlayerHelperEnabled }, { d.vampireSlayerHelperEnabled = it })
        val vampSwordSlot = slider("Sword Slot", "Inventory slot number for the sword.", def.vampireSlayerSwordSlot, 1, 9, 1, { d.vampireSlayerSwordSlot }, { d.vampireSlayerSwordSlot = it })
        val vampMelonSlot = slider("Healing Melon Slot", "Inventory slot number for the Healing Melon.", def.vampireSlayerHealingMelonSlot, 1, 9, 1, { d.vampireSlayerHealingMelonSlot }, { d.vampireSlayerHealingMelonSlot = it })
        val vampIceSlot = slider("Holy Ice Slot", "Inventory slot number for the Holy Ice. (TWINCLAWS)", def.vampireSlayerHolyIceSlot, 1, 9, 1, { d.vampireSlayerHolyIceSlot }, { d.vampireSlayerHolyIceSlot = it })
        val vampAutoJump = switch("Auto Jump", null, def.vampireSlayerAutoJump, { d.vampireSlayerAutoJump }, { d.vampireSlayerAutoJump = it })
        val vampAutoSneak = switch("Auto Sneak", null, def.vampireSlayerAutoSneak, { d.vampireSlayerAutoSneak }, { d.vampireSlayerAutoSneak = it })
        val vampAutoClickUp = switch("Auto Click Up", null, def.vampireSlayerAutoClickUp, { d.vampireSlayerAutoClickUp }, { d.vampireSlayerAutoClickUp = it })
        val vampAutoClickDown = switch("Auto Click Down", null, def.vampireSlayerAutoClickDown, { d.vampireSlayerAutoClickDown }, { d.vampireSlayerAutoClickDown = it })
        val vampAutoHeal = switch("Auto Heal", null, def.vampireSlayerAutoHeal, { d.vampireSlayerAutoHeal }, { d.vampireSlayerAutoHeal = it })
        val vampAutoTwinClaws = switch("Auto Twin Claws", null, def.vampireSlayerAutoTwinClaws, { d.vampireSlayerAutoTwinClaws }, { d.vampireSlayerAutoTwinClaws = it })

        val vampireCategory = ConfigCategory.createBuilder()
            .name(text("Vampire Slayer"))
            .tooltip(text("Settings for the Vampire Slayer module."), text(DISCORD_LINE_1), text(DISCORD_LINE_2))
            .group(group("General", vampEnabled))
            .group(group("Slots", vampSwordSlot, vampMelonSlot, vampIceSlot))
            .group(group("Actions", vampAutoJump, vampAutoSneak, vampAutoClickUp, vampAutoClickDown, vampAutoHeal, vampAutoTwinClaws))
            .build()

        return YetAnotherConfigLib.createBuilder()
            .title(text("LayBalt QOL"))
            .category(fishingCategory)
            .category(qolCategory)
            .category(combatCategory)
            .category(vampireCategory)
            .save { save() }
            .build()
    }
}
