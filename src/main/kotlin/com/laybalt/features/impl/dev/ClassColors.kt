package com.laybalt.features.impl.dev

import com.laybalt.config.ConfigHolder
import com.laybalt.config.types.ButtonSetting
import com.laybalt.config.types.ColorCodeSetting
import com.laybalt.features.Feature
import com.laybalt.features.annotations.AlwaysActive
import gg.essential.universal.ChatColor

@AlwaysActive
object ClassColors: Feature("Allows setting custom color for every dungeon class", toggled = true) {
    val archCode by ColorCodeSetting("Archer Code", ChatColor.DARK_RED).section("Colors")
    val bersCode by ColorCodeSetting("Berserk Code", ChatColor.GOLD)
    val healCode by ColorCodeSetting("Healer Code", ChatColor.DARK_PURPLE)
    val mageCode by ColorCodeSetting("Mage Code", ChatColor.DARK_AQUA)
    val tankCode by ColorCodeSetting("Tank Code", ChatColor.DARK_GREEN)
    val emptyCode = ColorCodeSetting("Empty Code", ChatColor.BLACK)

    private val reset by ButtonSetting("Reset Colors") {
        configSettings.forEach(ConfigHolder<*>::reset)
    }

    override fun toggle() = Unit
}