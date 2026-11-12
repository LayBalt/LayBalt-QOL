// Parts of this file are ported from Odin (https://github.com/odtheking/Odin), BSD-3-Clause, Copyright (c) 2025 odtheking
package com.laybalt.features.impl.floor7

import com.laybalt.config.types.*
import com.laybalt.event.impl.*
import com.laybalt.features.Feature
import com.laybalt.ui.utils.Resolution
import com.laybalt.utils.ChatUtils.addColor
import com.laybalt.utils.ChatUtils.unformattedText
import com.laybalt.utils.ColorUtils
import com.laybalt.utils.NumbersUtils.toFixed
import com.laybalt.utils.ThreadUtils
import com.laybalt.utils.dungeons.DungeonListener
import com.laybalt.utils.location.LocationUtils.F7Phase
import com.laybalt.utils.location.LocationUtils.dungeonFloorNumber
import com.laybalt.utils.location.LocationUtils.inBoss
import com.laybalt.utils.render.Render2D.drawCenteredString
import com.laybalt.utils.render.RenderHelper.width
import gg.essential.universal.USound
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket
import net.minecraft.sounds.SoundEvents

/**
 * NoammAddons c41b463e / c2e42664: the crystal, wither and lightning titles are one movable "F7 Titles" HUD element
 * (they used to be vanilla subtitles and a fixed 3x timer), and the terminal titles HUD element got default placement.
 */
object F7Titles: Feature(name = "F7 Titles", description = "Custom Titles for F7 boss fight.") {
    private val crystalTitles by ToggleSetting("Crystal Titles").section("Boss")
    private val witherTitles by ToggleSetting("Wither Titles")
    private val lightningTimer by ToggleSetting("Lightning Timer")
    private val terminalTitles by ToggleSetting("Terminal Titles").withDescription("Reformats terminal, device, lever, and gate subtitles during phase 3").section("Terminals")

    private val terminalTitleDuration by SliderSetting("Duration", 2.5, 0.5, 6, 0.5).withDescription("Duration of the terminal title in seconds").showIf { terminalTitles.value }
    private val terminalTitleFormat by TextInputSetting("Format", "{name} {type} &f({progress}&f)").withDescription("Replaces {name}, {class}, {type}, {action}, {progress}, {current} and {total} with the player name, dungeon class, terminal type, activated/completed, phase progress, completed count and total count. &bSupports color codes").showIf { terminalTitles.value }
    private val terminalPhaseDone by ToggleSetting("Phase Done").withDescription("Renders Phase Done instead of the completed progress").showIf { terminalTitles.value }
    private val terminalGateTitles by ToggleSetting("Gate Titles").withDescription("Also reformats gate-related subtitles").showIf { terminalTitles.value }
    private val terminalSelfOnly by ToggleSetting("Self Only").withDescription("Only replaces the titles of terminals, levers and devices you completed yourself; other players' keep Hypixel's title.").showIf { terminalTitles.value }
    private val replaceTerminal by ToggleSetting("Replace Terminal", true).withDescription("Replaces terminal completion titles.").showIf { terminalTitles.value }
    private val replaceLever by ToggleSetting("Replace Lever", true).withDescription("Replaces lever completion titles.").showIf { terminalTitles.value }
    private val replaceDevice by ToggleSetting("Replace Device", true).withDescription("Replaces device completion titles.").showIf { terminalTitles.value }
    private val terminalDisplay by DropdownSetting("Display", 0, listOf("HUD", "Vanilla Title", "Vanilla Subtitle")).withDescription("HUD: the movable \"Terminal Titles\" HUD element. Vanilla Title / Subtitle: Minecraft's own title text in the middle of the screen.").showIf { terminalTitles.value }

    private val crystalRegex = Regex("^(\\d)/(\\d) Energy Crystals are now active!$")
    private val enragedRegex = Regex("^⚠ (\\w+) is enraged! ⚠$")
    private val terminalRegex = Regex("^(.{1,16}) (activated|completed) a (terminal|device|lever)! \\((\\d+)/(\\d+)\\)$")

    private val terminalTitle = Title()
    private val title = Title()
    private var lightningTicks = 0L
    private var maxorDead = false
    private var goldorDead = false
    private var necronDead = false
    private var goldorStart = false
    private var necronStart = false

    /** Used by MixinBladeAddons so BladeAddons does not re-force the terminal titles this feature replaces. */
    @JvmStatic
    fun terminalTitlesEnabled() = terminalTitles.value

    override fun init() {
        // NoammAddons c2e42664: one HUD element for the crystal, wither and lightning titles
        hudElement(
            "F7 Titles",
            { crystalTitles.value || witherTitles.value || lightningTimer.value },
            { title.text.isNotBlank() || lightningTicks > 0L },
            centered = { true }
        ) { ctx, example ->
            val text = when {
                example -> when {
                    crystalTitles.value -> "&3Crystal&r(${formatProgress(1, 2)})"
                    witherTitles.value -> "&dMaxor Stunned!"
                    else -> "&l&c5.4"
                }

                title.text.isNotBlank() -> title.text
                else -> "&l&c${((lightningTicks - DungeonListener.currentTime) / 20.0).coerceAtLeast(0.0).toFixed(1)}"
            }

            ctx.drawCenteredString(text, 0, 0)
            return@hudElement text.width() to 9f
        } defaults {
            x = Resolution.width / 2f
            y = Resolution.height * 0.51f
            scale = 1.5f
        }

        hudElement(
            "Terminal Titles",
            { terminalTitles.value && terminalDisplay.value == 0 },
            { terminalTitle.text.isNotBlank() },
            centered = { true }
        ) { ctx, example ->
            val str = if (example) formatTerminalTitle(mc.user.name, "activated", "terminal", 6, 7) else terminalTitle.text
            ctx.drawCenteredString(str, 0, 0)
            return@hudElement str.width() to 9f
        } defaults {
            // NoammAddons c41b463e: default placement just above the crosshair
            x = Resolution.width / 2f
            y = Resolution.height * 0.45f
            scale = 1.5f
        }

        register<WorldChangeEvent> {
            maxorDead = false
            goldorDead = false
            necronDead = false
            goldorStart = false
            necronStart = false
            resetTitles()
        }

        // NoammAddons c41b463e: the lightning countdown ends on the server tick, not on a render frame
        register<TickEvent.Server> {
            if (lightningTicks <= 0L || DungeonListener.currentTime < lightningTicks) return@register
            lightningTicks = 0L
            if (! lightningTimer.value) return@register
            showTitle("&aStorm's Lightning Ended!")
        }

        register<ChatMessageEvent> {
            if (dungeonFloorNumber != 7 || ! inBoss || ! witherTitles.value) return@register
            when (event.unformattedText) {
                "[BOSS] Maxor: YOU TRICKED ME!", "[BOSS] Maxor: THAT BEAM! IT HURTS! IT HURTS!!" -> showTitle("&dMaxor Stunned!")
                "[BOSS] Storm: Oof", "[BOSS] Storm: Ouch, that hurt!" -> showTitle("&bStorm Crushed!")
                "[BOSS] Storm: I should have known that I stood no chance." -> showTitle("&bStorm Dead!")
                "[BOSS] Necron: ARGH!" -> necronStart = true
                "The Core entrance is opening!" -> goldorStart = true
            }
        }

        register<MainThreadPacketReceivedEvent.Pre> {
            if (dungeonFloorNumber != 7 || ! inBoss) return@register

            when (val packet = event.packet) {
                is ClientboundSetSubtitleTextPacket -> {
                    val text = packet.text.unformattedText
                    if (text.isBlank()) return@register

                    if (crystalTitles.value) {
                        if (text == "The Energy Laser is charging up!") {
                            event.isCanceled = true
                            return@register
                        }

                        crystalRegex.find(text)?.destructured?.let { (min, max) ->
                            val progress = formatProgress(min.toInt(), max.toInt())
                            title.show("&3Crystal&r($progress)")
                            event.isCanceled = true
                            return@register
                        }
                    }

                    // NoammAddons c41b463e: the enraged titles belong to "Wither Titles" (showTitle already plays the ping)
                    if (witherTitles.value) enragedRegex.find(text)?.destructured?.component1()?.let { boss ->
                        val color = when (boss) {
                            "Storm" -> "&b"
                            "Maxor" -> "&5"
                            else -> ""
                        }
                        showTitle("$color$text")
                        event.isCanceled = true
                    }

                    if (terminalTitles.value && F7Phase == 3 && handleTerminalTitle(text)) {
                        event.isCanceled = true
                    }
                }

                is ClientboundSetTitleTextPacket -> {
                    val text = packet.text.unformattedText.takeUnless(String::isBlank) ?: return@register
                    if (terminalTitles.value && F7Phase == 3 && handleTerminalTitle(text)) {
                        event.isCanceled = true
                        return@register
                    }

                    if (! lightningTimer.value) return@register
                    val number = text.toIntOrNull() ?: return@register
                    event.isCanceled = true

                    if (lightningTicks == 0L && (number == 4 || number == 6)) {
                        lightningTicks = DungeonListener.currentTime + (number * 1.35 * 20.0).toLong()
                        title.clear()
                    }
                }
            }
        }

        register<BossBarUpdateEvent> {
            if (! witherTitles.value) return@register
            if (dungeonFloorNumber != 7 || ! inBoss) return@register
            if (event.progress > 0f) return@register
            val name = event.name.unformattedText
            val entry = DungeonListener.bossEntryTime?.ticks ?: return@register

            if (name.contains("Maxor") && ! maxorDead && DungeonListener.currentTime - entry > 120) {
                maxorDead = true
                showTitle("&dMaxor Dead!")
            }
            else if (name.contains("Goldor") && ! goldorDead && goldorStart) {
                goldorDead = true
                showTitle("&7Goldor Dead!")
            }
            else if (name.contains("Necron") && ! necronDead && necronStart) {
                necronDead = true
                showTitle("&cNecron Dead!!")
            }
        }
    }

    override fun onDisable() {
        super.onDisable()
        resetTitles()
    }

    private fun resetTitles() {
        title.clear()
        terminalTitle.clear()
        lightningTicks = 0L
    }

    private fun handleTerminalTitle(title: String): Boolean {
        if (terminalGateTitles.value) when (title) {
            "The gate has been destroyed!" -> {
                showTerminalTitle("&cGate Destroyed!")
                return true
            }

            "The gate will open in 5 seconds!" -> {
                showTerminalTitle("&c&lGATE!")
                return true
            }
        }

        val (name, action, type, min, max) = terminalRegex.find(title)?.destructured ?: return false
        if (terminalSelfOnly.value && name != mc.player?.name?.string) return false
        val replace = when (type) {
            "terminal" -> replaceTerminal.value
            "lever" -> replaceLever.value
            "device" -> replaceDevice.value
            else -> false
        }
        if (! replace) return false

        showTerminalTitle(formatTerminalTitle(name, action, type, min.toInt(), max.toInt()))
        return true
    }

    private fun showTerminalTitle(title: String) {
        if (terminalDisplay.value != 0) {
            val component = Component.literal(title.addColor())
            mc.execute {
                mc.gui.hud.setTimes(0, (terminalTitleDuration.value.toDouble() * 20).toInt(), 5)
                if (terminalDisplay.value == 1) mc.gui.hud.setTitle(component)
                else {
                    // A subtitle is only drawn together with a title: add an empty one only when no
                    // title is active; an active title (server or another feature) is re-set so it is kept for the subtitle duration.
                    mc.gui.hud.setTitle(mc.gui.hud.title ?: Component.empty())
                    mc.gui.hud.setSubtitle(component)
                }
            }
            return
        }

        // NoammAddons 8a26dfba: keep the half seconds; c41b463e: the HUD title times out on its own
        terminalTitle.show(title, terminalTitleDuration.value.toDouble() * 20)
    }

    private fun formatTerminalTitle(name: String, action: String, type: String, min: Int, max: Int): String {
        if (terminalPhaseDone.value && min == max) return "&a&lPhase Done!"

        val formattedType = when (type) {
            "terminal" -> "&5Terminal"
            "device" -> "&bDevice"
            "lever" -> "&cLever"
            else -> ""
        }

        val clazz = DungeonListener.dungeonTeammates.find { it.name == name }?.clazz
        val classColor = clazz?.code ?: "&7"
        val formattedName = "$classColor$name&r"
        val formattedClass = clazz?.name?.let { "$classColor$it&r" } ?: "&7Unknown&r"
        val formattedProgress = "${ColorUtils.colorCodeByPercent(min, max)}$min&8/&a$max"

        return terminalTitleFormat.value
            .replace("{name}", formattedName)
            .replace("{class}", formattedClass)
            .replace("{type}", formattedType)
            .replace("{progress}", formattedProgress)
            .replace("{action}", action)
            .replace("{current}", min.toString())
            .replace("{total}", max.toString())
    }

    private fun showTitle(subtitle: String) {
        title.show(subtitle)
        USound.playSoundStatic(SoundEvents.NOTE_BLOCK_PLING, 0.25f, 1f)
    }

    private fun formatProgress(current: Int, max: Int): String {
        val minColor = if (current == max) "&b" else "&c"
        return "$minColor$current&r/&r&b$max&r"
    }

    /** NoammAddons c41b463e: a HUD title that clears itself after [show]'s ticks unless a newer one replaced it. */
    private class Title {
        var text = ""
            private set

        private var id = 0

        fun show(text: String, ticks: Number = 40) {
            this.text = text
            val cid = ++ id
            ThreadUtils.scheduledTask(ticks) { if (id == cid) this.text = "" }
        }

        fun clear() {
            text = ""
            id ++
        }
    }
}