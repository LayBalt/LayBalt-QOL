// Restored from NoammAddons (com.github.noamm9.features.impl.dungeon.Abilities), merged with OdinClient's AutoAbilities
// Ported from OdinClient (https://github.com/skies-starred/OdinClient), BSD-3-Clause, Copyright (c) 2026 Starred
package com.laybalt.features.impl.dungeon

import com.laybalt.config.types.*
import com.laybalt.event.impl.ChatMessageEvent
import com.laybalt.event.impl.KeyboardEvent
import com.laybalt.event.impl.MouseClickEvent
import com.laybalt.features.Feature
import com.laybalt.utils.*
import com.laybalt.utils.dungeons.DungeonListener
import com.laybalt.utils.dungeons.enums.DungeonClass
import com.laybalt.utils.location.LocationUtils
import com.mojang.blaze3d.platform.InputConstants
import gg.essential.universal.UMinecraft
import org.lwjgl.glfw.GLFW

object Abilities: Feature(
    //#if CHEAT
    "Allows you to use dungeon class abilities with keybinds and automatically trigger ultimates when needed.",
    //#else
    //$"Allows you to use dungeon class abilities with keybinds.",
    //#endif
    name = "Auto Abilities",
    jsonName = "Abilities"
) {

    private val ultKeybind by KeybindSetting("Ultimate Keybind").withDescription("Uses your dungeon class ultimate (drops one item from the class item slot).").section("Keybinds")
    private val abilityKeybind by KeybindSetting("Ability Keybind").withDescription("Uses your dungeon class ability (drops the whole class item stack).")
    private val requireStart by ToggleSetting("Only After Run Start", true)
        .withDescription("Only allow the keybinds once the run has started. Turn off to also use them in the starting room before Mort opens the door.")

    //#if CHEAT
    private val autoUlt by ToggleSetting("Auto Use Ultimate").withDescription("Automatically uses your ultimate on the boss messages where it is needed (Maxor enrage, Goldor's factory, Sadan's giants, Livid).").section("Auto Ultimate")
    private val autoUltDelay by SliderSetting(name = "Auto delay", 0, 0, 100, 1, suffix = "ticks")
        .withDescription("Server ticks to wait after the boss message before using the ultimate (Maxor, Goldor, Livid).").showIf { autoUlt.value }
    private val sadanDelay by SliderSetting("Sadan Delay", 25, 0, 100, 1, suffix = "ticks")
        .withDescription("Server ticks to wait after Sadan's \"My giants! Unleashed!\" before using the ultimate, so it lands once the giants are active.").showIf { autoUlt.value }
    private val anyClass by ToggleSetting("Any Class")
        .withDescription("Use the ultimate on every boss message regardless of your class, instead of only for the classes whose ultimate helps there.").showIf { autoUlt.value }

    private class UltMessage(val msgs: List<String>, val classes: List<DungeonClass>, val floor: Int, val sadan: Boolean = false)

    private val ultMessages = listOf(
        UltMessage(
            msgs = listOf("⚠ Maxor is enraged! ⚠"),
            classes = listOf(DungeonClass.Healer, DungeonClass.Tank),
            floor = 7
        ),
        UltMessage(
            // Hypixel's message has been seen with both three dots and a unicode ellipsis
            msgs = listOf("[BOSS] Goldor: You have done it, you destroyed the factory...", "[BOSS] Goldor: You have done it, you destroyed the factory…"),
            classes = listOf(DungeonClass.Healer, DungeonClass.Tank),
            floor = 7
        ),
        UltMessage(
            msgs = listOf("[BOSS] Sadan: My giants! Unleashed!"),
            classes = listOf(DungeonClass.Healer, DungeonClass.Tank, DungeonClass.Archer, DungeonClass.Berserk, DungeonClass.Mage),
            floor = 6,
            sadan = true
        ),
        UltMessage(
            msgs = listOf("[BOSS] Livid: I respect you for making it to here, but I'll be your undoing."),
            classes = listOf(DungeonClass.Healer, DungeonClass.Tank),
            floor = 5
        )
    )
    //#endif

    private fun canUseKeybinds() = LocationUtils.inDungeon && (DungeonListener.dungeonStarted || ! requireStart.value) && UMinecraft.currentScreenObj == null

    override fun init() {
        register<KeyboardEvent.KeyPressed> {
            if (event.action != InputConstants.PRESS) return@register
            if (! canUseKeybinds()) return@register

            if (! ultKeybind.isMouse && ultKeybind.isPressed()) {
                PlayerUtils.useDungeonClassAbility(true)
                event.isCanceled = true
                return@register
            }

            if (! abilityKeybind.isMouse && abilityKeybind.isPressed()) {
                PlayerUtils.useDungeonClassAbility(false)
                event.isCanceled = true
                return@register
            }
        }

        register<MouseClickEvent> {
            if (event.action != GLFW.GLFW_PRESS) return@register
            if (! canUseKeybinds()) return@register

            if (ultKeybind.matches(event.button, true)) {
                PlayerUtils.useDungeonClassAbility(true)
                event.isCanceled = true
                return@register
            }

            if (abilityKeybind.matches(event.button, true)) {
                PlayerUtils.useDungeonClassAbility(false)
                event.isCanceled = true
            }
        }

        //#if CHEAT
        register<ChatMessageEvent> {
            if (! autoUlt.value || ! LocationUtils.inBoss) return@register

            val msg = event.unformattedText
            val matchingMessage = ultMessages.find {
                msg in it.msgs && it.floor == LocationUtils.dungeonFloorNumber
            } ?: return@register

            if (! anyClass.value && DungeonListener.thePlayer?.clazz !in matchingMessage.classes) return@register

            val delay = if (matchingMessage.sadan) sadanDelay.value else autoUltDelay.value
            ThreadUtils.scheduledTaskServer(delay) {
                PlayerUtils.useDungeonClassAbility(true)
                ChatUtils.modMessage("Used Ultimate!")
            }
        }
        //#endif
    }
}
