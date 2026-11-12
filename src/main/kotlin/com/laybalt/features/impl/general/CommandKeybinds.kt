// Ported from Odin (https://github.com/odtheking/Odin), BSD-3-Clause, Copyright (c) 2025 odtheking
package com.laybalt.features.impl.general

import com.laybalt.config.types.KeybindSetting
import com.laybalt.event.impl.TickEvent
import com.laybalt.event.impl.WorldChangeEvent
import com.laybalt.features.Feature
import com.laybalt.utils.ChatUtils
import com.laybalt.utils.location.LocationUtils
import gg.essential.universal.UMinecraft

object CommandKeybinds: Feature("Keybinds that run common SkyBlock commands, such as /pets, /storage or /warp dungeon_hub.") {
    private val binds = listOf(
        bind("Pets", "pets", "Opens the pets menu (/pets)."),
        bind("Storage", "storage", "Opens the storage menu (/storage)."),
        bind("Armor Wardrobe", "armor", "Opens the armor wardrobe menu (/armor)."),
        bind("Equip Wardrobe", "equipment", "Opens the equipment wardrobe menu (/equipment)."),
        bind("Loadouts", "loadout", "Opens the loadouts menu (/loadout)."),
        bind("Stats", "stats", "Opens the SkyBlock stats menu (/stats)."),
        bind("Dungeon Hub", "warp dungeon_hub", "Warps you to the Dungeon Hub (/warp dungeon_hub)."),
        bind("Potion Bag", "potionbag", "Opens the potion bag (/potionbag)."),
    )

    private fun bind(name: String, command: String, description: String): Pair<KeybindSetting, String> {
        val setting = KeybindSetting(name).withDescription(description).apply(configSettings::add)
        if (name == "Pets") setting.section("Keybinds")
        return setting to command
    }

    override fun init() {
        register<TickEvent.Start> {
            // isPressed() is edge-triggered and stateful, so every bind is polled every tick.
            val pressed = binds.filter { it.first.isPressed() }
            if (pressed.isEmpty()) return@register
            if (UMinecraft.currentScreenObj != null) return@register
            if (! LocationUtils.inSkyblock) return@register
            pressed.forEach { ChatUtils.sendCommand(it.second) }
        }

        register<WorldChangeEvent> { binds.forEach { it.first.isPressed() } }
    }
}
