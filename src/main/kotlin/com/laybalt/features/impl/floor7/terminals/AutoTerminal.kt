package com.laybalt.features.impl.floor7.terminals

import com.mojang.blaze3d.platform.InputConstants
//#if CHEAT

import com.laybalt.config.ConfigManager
import com.laybalt.config.types.*
import com.laybalt.event.impl.*
import com.laybalt.event.priority.EventPriority
import com.laybalt.features.Feature
import com.laybalt.features.impl.floor7.terminals.impl.*
import com.laybalt.ui.utils.Resolution
import com.laybalt.utils.*
import com.laybalt.utils.render.Render2D.drawCenteredString
import com.laybalt.utils.render.RenderHelper.width
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper
import kotlin.math.roundToInt

object AutoTerminal: Feature("Automatically clicks terminals for you.") {
    // NoammAddons 5dff2334: one range slider instead of Random Delay + Click Delay + Min/Max Random Delay. Two different
    // ends are the old gaussian random delay, both ends equal the old fixed delay; old configs carry over (LegacyDelays).
    private val clickDelay by RangeSliderSetting("Click Delay", 120 to 150, 50, 500, 5, "ms").withDescription("Delay between clicks. With two different ends each delay is a random time between them (normally distributed); with both ends equal it is fixed.").section("Settings")
    private val offtick by ToggleSetting("Off Tick Clicks").withDescription("Makes the auto check every frame if it can click or not instead of every server tick.")
    private val clickOrder by DropdownSetting("Click Order", 2, listOf("None", "Random", "Human", "Skizo")).withDescription("Human: Logic pathing. Skizo: Chaotic/Furthest.")
    private val invwalk by ToggleSetting("Fake InvWalk").withDescription("Draws the Term name and progress on screen rather then the solution")

    // NoammAddons Auto Melody (its "Melody-AutoTerm" section), see melodyTick / AutoMelody.
    private val autoMelody by ToggleSetting("Melody", true).withDescription("Clicks the Melody button when the green pane reaches the marked column.").section("Melody")
    private val melodyFcDelay by ToggleSetting("First Click Delay", true).withDescription("Waits for the Terminal Solver's First Click Protection (Melody adds 150ms and 3 lag protection ticks) before clicking.").showIf { autoMelody.value }
    private val melodySkip by ToggleSetting("Melody Skip").withDescription("After a click, also clicks the buttons of the following rows (one tick apart) before their lanes start.").showIf { autoMelody.value }
    private val melodySkipMode by DropdownSetting("Skip Mode", 0, listOf("Edges", "All")).withDescription("Edges: only skip after a click in the first or last column. All: skip after every click.").showIf { autoMelody.value && melodySkip.value }
    private val melodySkipFirstRow by ToggleSetting("Skip First Row").withDescription("Also skip from the first row in any column; without it the first row only skips from its last column.").showIf { autoMelody.value && melodySkip.value }

    private val autoNumbers by ToggleSetting("Numbers", true).section("Terminals")
    private val autoColors by ToggleSetting("Colors", true)
    private val autoRubix by ToggleSetting("Rubix", true)
    private val autoRedGreen by ToggleSetting("Red-Green", true)
    private val autoStartWith by ToggleSetting("Start-With", true)

    private var lastClickTime = 0L
    private var lastClickTick = - 1L
    /** Server ticks counted here instead of DungeonListener.currentTime, which only advances inside a dungeon. */
    private var serverTicks = 0L
    private var lastClickedSlot: Int? = null
    /** The wait before the next click, rolled from Click Delay once per click (a roll per check would lean to the short end). */
    private var nextDelay = 0

    override fun init() {
        ConfigManager.migrateSettings(this, LegacyDelays::migrate)

        hudElement(
            "AutoTerminal - FakeInvWalk",
            centered = { true },
            enabled = { invwalk.value },
            shouldDraw = { TerminalListener.currentHandler?.enabled() == true }
        ) { ctx, e ->
            val handler = if (e) NumberTerminal else TerminalListener.currentHandler !!
            val maxClicks = if (e) NumberTerminal.gridSize.run { first * second } else handler.maxClicks()
            val completed = if (e) 4 else handler.completedClicks()

            val title = "§3In Terminal (${handler.displayName})"
            val progress = "§b[${completed.coerceIn(0, maxClicks)}/$maxClicks]${handler.progressSuffix()}"

            ctx.drawCenteredString(title, 0, 0)
            // NoammAddons 2950c5e9: the progress line goes below the title, inside the 20px element.
            if (maxClicks != null && maxClicks > 0) ctx.drawCenteredString(progress, 0, 10f)

            maxOf(title.width(), progress.width()).toFloat() to 20f
        } defaults {
            x = Resolution.width / 2
            y = Resolution.height / 2 - Resolution.height / 10
            scale = 3f
        }

        // NoammAddons e2e9e2c1: Off Tick Clicks checks every frame, but still clicks at most once per server tick.
        register<RenderWorldEvent> {
            if (! offtick.value) return@register
            if (TerminalListener.simScreen != null) return@register
            if (lastClickTick == serverTicks) return@register
            if (autoTick()) lastClickTick = serverTicks
        }

        // Server ticks come from ping packets, so the simulator (singleplayer or a server without them) uses client ticks (NoammAddons cff93729).
        register<TickEvent.Server> {
            serverTicks ++
            if (TerminalListener.simScreen != null) return@register
            if (! offtick.value) autoTick()
            melodyTick()
        }

        register<TickEvent.End> {
            if (TerminalListener.simScreen == null) return@register
            autoTick()
            melodyTick()
        }

        // NoammAddons Auto Melody: a Melody slot update (the lane moved or a row advanced) allows clicking the same button again.
        register<TerminalEvent.SlotUpdate> {
            if (! autoMelody.value) return@register
            if (event.handler !is MelodyTerminal) return@register
            lastClickedSlot = null
        }

        register<ContainerEvent.MouseClick>(EventPriority.HIGH) {
            val handler = TerminalListener.currentHandler ?: return@register
            if (handler.enabled()) event.isCanceled = true
        }

        register<ContainerEvent.Keyboard>(EventPriority.HIGH) {
            if (event.key.equalsOneOf(KeyMappingHelper.getBoundKeyOf(mc.options.keyInventory).value, InputConstants.KEY_ESCAPE)) return@register
            val handler = TerminalListener.currentHandler ?: return@register
            if (handler.enabled()) event.isCanceled = true
        }

        register<ScreenEvent.PreRender>(EventPriority.HIGH) {
            if (! invwalk.value) return@register
            val handler = TerminalListener.currentHandler ?: return@register
            if (handler.enabled()) event.isCanceled = true
        }

        // A broken terminal is re-synced from the server, so the last clicked slot may need clicking again.
        register<TerminalEvent.Break> { lastClickedSlot = null }

        register<TerminalEvent.Close> {
            lastClickedSlot = null
            lastClickTime = 0L
            lastClickTick = - 1L
            nextDelay = 0
        }
    }

    /**
     * NoammAddons Auto Melody (melodyTick): clicks the current row's clay button when the green pane is in the marked
     * column, at most every 250ms; with Melody Skip also the buttons of the following rows, 1 and 2 ticks later. Clicks go
     * through [TerminalClick.send], which feeds the terminal simulator when one is open (like the other auto clicks).
     */
    private fun melodyTick() {
        if (! autoMelody.value) return
        val handler = TerminalListener.currentHandler as? MelodyTerminal ?: return
        if (melodyFcDelay.value && TerminalListener.checkFcDelay()) return

        val now = System.currentTimeMillis()
        val current = handler.current
        val buttonRow = handler.buttonRow
        // LayBalt's simulator posts slot updates like Hypixel, so the "same button" check applies there too.
        val slot = AutoMelody.clickFor(current, handler.correct, buttonRow, now - lastClickTime, lastClickedSlot) ?: return
        // the state first, as autoClick does: the last click can finish (and close) a simulated Melody inside send(),
        // whose Close resets this state, which must not be written again afterwards for the next terminal
        lastClickTime = now
        lastClickedSlot = slot
        val windowId = TerminalListener.lastWindowId
        val sim = TerminalListener.simScreen
        TerminalClick(slot).send(record = false)

        if (! melodySkip.value || current == null || buttonRow == null) return
        val skips = AutoMelody.skipClicks(buttonRow, current, melodySkipMode.value, melodySkipFirstRow.value)
        if (skips.isEmpty()) return

        // Only while this very Melody (window, or simulator screen) is still open.
        val stillOpen = {
            enabled && autoMelody.value && TerminalListener.inTerm && TerminalListener.currentHandler is MelodyTerminal &&
                TerminalListener.lastWindowId == windowId && TerminalListener.simScreen === sim && (sim == null || mc.gui.screen() === sim)
        }
        for ((ticks, skipSlot) in skips) ThreadUtils.scheduledTask(ticks) {
            if (stillOpen()) TerminalClick(skipSlot).send(record = false)
        }
    }

    /** Clicks the next solution slot when the delay allows it; returns true when a click went out. */
    private fun autoTick(): Boolean {
        val handler = TerminalListener.currentHandler ?: return false
        if (handler is MelodyTerminal) return false
        if (! handler.enabled()) return false
        if (handler.solution.isEmpty()) return false
        if (TerminalListener.checkFcDelay()) return false
        if (System.currentTimeMillis() - lastClickTime < nextDelay) return false
        lastClickTime = System.currentTimeMillis()
        // NoammAddons 5dff2334: the next wait comes from the Click Delay range.
        nextDelay = clickDelay.random().coerceAtLeast(0)

        handler.autoClick()
        return true
    }

    private fun Terminal.autoClick() {
        val rawClick = if (this == NumberTerminal) solution.first()
        else when (clickOrder.value) {
            1 -> solution.random()
            2 -> HumanClickOrder.getBestClick(this)
            3 -> HumanClickOrder.getWorstClick(this)
            else -> solution.first()
        }

        if (lastClickedSlot == rawClick.slotId && this !is RubixTerminal) return

        val finalClick = if (this is RubixTerminal) getClickForSlot(rawClick.slotId) !! else rawClick

        lastClickedSlot = finalClick.slotId
        predict(finalClick)
        finalClick.send()
    }

    private fun Terminal.enabled() = when (this) {
        is NumberTerminal -> autoNumbers.value
        is ColorsTerminal -> autoColors.value
        is MelodyTerminal -> autoMelody.value
        is RubixTerminal -> autoRubix.value
        is RedGreenTerminal -> autoRedGreen.value
        is StartWithTerminal -> autoStartWith.value
    }

    /**
     * NoammAddons 5dff2334 replaced Random Delay (`randomDelay`), Click Delay (`autoDelay`) and Min/Max Random Delay
     * (`minRandomDelay`/`maxRandomDelay`) with the Click Delay range (saved as `Click Delay`). A config saved before that
     * gets the range its old values meant: min..max with Random Delay on (the old default), the fixed delay at both ends
     * with it off; missing old keys count as their old defaults (on, 150, 120, 150). Runs on every load and import; the old
     * keys are removed, so it does nothing once the config was saved again.
     */
    internal object LegacyDelays {
        const val KEY = "Click Delay"
        private val oldKeys = listOf("randomDelay", "autoDelay", "minRandomDelay", "maxRandomDelay")

        fun migrate(settings: JsonObject) {
            if (oldKeys.none(settings::has)) return
            val old = oldKeys.associateWith { settings.remove(it) }
            if (settings.has(KEY)) return

            fun number(key: String, default: Int) = runCatching { old[key]?.asDouble?.roundToInt() }.getOrNull() ?: default
            val random = runCatching { old["randomDelay"]?.asBoolean }.getOrNull() ?: true
            val (start, end) =
                if (random) number("minRandomDelay", 120).let { a -> number("maxRandomDelay", 150).let { b -> minOf(a, b) to maxOf(a, b) } }
                else number("autoDelay", 150).let { it to it }
            settings.add(KEY, JsonArray().apply {
                add(start)
                add(end)
            })
        }
    }
}
//#endif
