// Contains code ported from Odin (https://github.com/odtheking/Odin), BSD-3-Clause, Copyright (c) 2025 odtheking
package com.laybalt.features.impl.floor7

import com.laybalt.config.types.DropdownSetting
import com.laybalt.config.types.ToggleSetting
import com.laybalt.event.impl.*
import com.laybalt.features.Feature
import com.laybalt.utils.ColorUtils
import com.laybalt.utils.NumbersUtils.toFixed
import com.laybalt.utils.location.LocationUtils
import com.laybalt.utils.render.Render2D.drawCenteredString
import com.laybalt.utils.render.RenderHelper.width

/**
 * Timings for the dungeon update (NoammAddons 64bb7927, Odin 833e053): Maxor now starts 83 ticks after his first line,
 * Goldor 64 ticks after Storm's death (Odin) and the Storm PY window is 62 ticks (NoammAddons). The Storm/Necron start
 * timers and the 0s death / secret tick timers were removed upstream.
 * The upstreams disagree on PY: NoammAddons 1cec5e74 counts 62 ticks, Odin 833e053 counts 55 from the same
 * "ENERGY HEED MY CALL" / "THUNDER LET ME BE YOUR CATALYST" line. 62 is kept until a run confirms one of them.
 */
object TickTimers: Feature("Shows various types of server tick timers for F7 boss fight.") {
    private const val MAXOR_START_TICKS = 83
    private const val GOLDOR_START_TICKS = 64
    private const val PY_TICKS = 62
    private const val FIRE_FREEZE_TICKS = 160
    private const val FIRE_FREEZE_WINDOW_TICKS = 100

    private val showPrefix by ToggleSetting("Prefix", true).section("Settings")
    private val showSuffix by ToggleSetting("Suffix", true)
    private val format by DropdownSetting("Format", 0, listOf("Seconds", "Ticks"))

    private val p1 by ToggleSetting("Maxor Start").section("F7")
        .withDescription("Counts down to Maxor becoming active after his first line.")
    private val p3 by ToggleSetting("Goldor Start")
        .withDescription("Counts down to Goldor's phase starting after Storm dies.")

    private val goldorDeathTickTimer by ToggleSetting("Goldor Death Ticks")
    private val padTimer by ToggleSetting("Storm Pad Timer")
    private val pyTimer by ToggleSetting("Storm PY Timer")

    // Odin 3bf75747: Fire Freeze timing after the minister change, 160 ticks from the Professor's line:
    // a 60-tick countdown, then a 100-tick "Use Fire Freeze" window.
    private val fireFreezeTimer by ToggleSetting("Fire Freeze Timer").section("F3/M3")
        .withDescription("Counts down to the moment to use Fire Freeze Staff on the Professor's guardians in F3/M3.")
    private val professorFireFreezeRegex = Regex("^\\[BOSS] The Professor: Oh\\? You found my Guardians' one weakness\\?$")

    private var startTickTime = - 1
    private var startTickMax = MAXOR_START_TICKS
    private var goldorTickTime = - 1
    private var padTickTime = - 1
    private var pyTickTime = - 1
    private var stormActive = false
    private var pyTriggered = false
    private var fireFreezeTime = - 1

    override fun init() {
        hudElement(
            "Tick Timers",
            shouldDraw = { LocationUtils.inDungeon },
            centered = { true }
        ) { ctx, example ->
            val textToRender = if (example) "§aStart: 83"
            else when {
                startTickTime != - 1 -> formatTimer(startTickTime, startTickMax, "§aStart:")
                goldorTickTime != - 1 -> formatTimer(goldorTickTime, 60, "§7Goldor:")
                pyTickTime != - 1 -> formatTimer(pyTickTime, PY_TICKS, "§5PY:")
                padTickTime != - 1 -> formatTimer(padTickTime, 20, "§bPad:")
                fireFreezeTime - FIRE_FREEZE_WINDOW_TICKS > 0 -> formatTimer(fireFreezeTime - FIRE_FREEZE_WINDOW_TICKS, 60, "§4Fire Freeze:")
                fireFreezeTime >= 0 -> "§4Use Fire Freeze"
                else -> return@hudElement 0f to 0f
            }

            ctx.drawCenteredString(textToRender, 0f, 0f)
            return@hudElement textToRender.width() to 9f
        }

        register<WorldChangeEvent> { reset() }

        register<ChatMessageEvent> {
            if (fireFreezeTimer.value && event.unformattedText.matches(professorFireFreezeRegex)) fireFreezeTime = FIRE_FREEZE_TICKS

            when (event.unformattedText) {
                "[BOSS] Maxor: WELL! WELL! WELL! LOOK WHO'S HERE!" -> if (p1.value) startTimer(MAXOR_START_TICKS)

                "[BOSS] Storm: ENERGY HEED MY CALL!", "[BOSS] Storm: THUNDER LET ME BE YOUR CATALYST!" -> {
                    if (pyTimer.value && ! pyTriggered) {
                        pyTriggered = true
                        pyTickTime = PY_TICKS
                    }
                }

                "[BOSS] Storm: I should have known that I stood no chance." -> {
                    if (p3.value) startTimer(GOLDOR_START_TICKS)
                    if (pyTriggered) {
                        pyTriggered = false
                        pyTickTime = - 1
                    }
                    if (stormActive) {
                        stormActive = false
                        padTickTime = - 1
                    }
                }

                "[BOSS] Goldor: Who dares trespass into my domain?" -> {
                    if (goldorDeathTickTimer.value) goldorTickTime = 60
                }

                "The Core entrance is opening!" -> goldorTickTime = - 1

                "[BOSS] Storm: Pathetic Maxor, just like expected." -> {
                    if (padTimer.value) {
                        padTickTime = 20
                        stormActive = true
                    }
                }
            }
        }

        register<TickEvent.Server> {
            if (startTickTime != - 1) startTickTime --

            if (stormActive && padTickTime != - 1) {
                padTickTime --
                if (padTickTime <= 0) padTickTime = 20
            }

            if (pyTimer.value && pyTickTime >= 0) pyTickTime --

            if (fireFreezeTime >= 0) fireFreezeTime --

            if (goldorTickTime >= 0) {
                goldorTickTime --
                if (goldorTickTime == 0) goldorTickTime = 60
            }
        }
    }

    private fun startTimer(ticks: Int) {
        startTickTime = ticks
        startTickMax = ticks
    }

    private fun reset() {
        padTickTime = - 1
        goldorTickTime = - 1
        startTickTime = - 1
        stormActive = false
        pyTickTime = - 1
        pyTriggered = false
        fireFreezeTime = - 1
    }

    private fun formatTimer(time: Int, max: Int, prefixText: String): String {
        // NoammAddons c2c30c3e
        val color = ColorUtils.colorCodeByPercent(time, max)

        val timeDisplay = if (format.value == 1) time.toString()
        else (time / 20f).toFixed(2)

        val prefix = if (showPrefix.value) "$prefixText " else ""
        val suffix = if (showSuffix.value) if (format.value == 1) "t" else "s" else ""

        return "$prefix$color$timeDisplay$suffix"
    }
}
