package com.laybalt.features.impl.dev

import com.laybalt.LayBaltQOL.mc
import com.laybalt.config.types.ButtonSetting
import com.laybalt.config.types.ColorSetting
import com.laybalt.config.types.DropdownSetting
import com.laybalt.config.types.KeybindSetting
import com.laybalt.event.impl.KeyboardEvent
import com.laybalt.event.impl.MouseClickEvent
import com.laybalt.features.Feature
import com.laybalt.ui.clickgui.LayBaltScreen
import com.laybalt.ui.hud.HudEditorScreen
import com.laybalt.utils.GuiUtils
import gg.essential.universal.UMinecraft
import com.mojang.blaze3d.platform.InputConstants
import com.laybalt.ui.Palette

object ClickGui: Feature("Settings of the LayBalt GUI itself: open keybind, accent color and card order.", toggled = true) {
    private val openKeybind by KeybindSetting("Open Keybind").withDescription("Key or mouse button that opens the LayBalt GUI (the GUI also opens with /lbq or /laybalt).")
    val accentColor by ColorSetting("Accent Color", Palette.defaultAccent, false).withDescription("The accent color used by the whole LayBalt GUI (switches, borders, highlights).")
    val panelSorting by DropdownSetting("Sorting", 1, listOf("A-Z Sorting", "Width Sorting", "No Sorting")).withDescription("The order of the feature cards in each category (search results are always sorted by relevance).")

    val editGuiButton by ButtonSetting("Open HUD Editor") {
        GuiUtils.setScreen(HudEditorScreen(mc.gui.screen()))
    }.withDescription("Opens the HUD Editor Screen where you can move and resize your HUD elements.")

    val resetButton by ButtonSetting("Reset Settings") {
        accentColor.reset()
    }.withDescription("Reverts settings back to their original values.")

    override fun init() {
        register<KeyboardEvent.KeyPressed> {
            if (UMinecraft.currentScreenObj != null || event.action != InputConstants.PRESS) return@register
            if (! openKeybind.matches(event.keyEvent.key, mouse = false)) return@register

            GuiUtils.setScreen(LayBaltScreen())
            event.isCanceled = true
        }

        register<MouseClickEvent> {
            if (UMinecraft.currentScreenObj != null || event.action != InputConstants.PRESS) return@register
            if (! openKeybind.matches(event.button, mouse = true)) return@register

            GuiUtils.setScreen(LayBaltScreen())
            event.isCanceled = true
        }
    }

    override fun toggle() = Unit
}