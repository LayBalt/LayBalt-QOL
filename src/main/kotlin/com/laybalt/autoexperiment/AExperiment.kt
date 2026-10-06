package com.laybalt.autoexperiment

import com.laybalt.GUI.LBQConfig
import com.laybalt.Main
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.ContainerScreen
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.DyeColor
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.StainedGlassBlock
import net.minecraft.world.level.block.StainedGlassPaneBlock
import java.util.TreeMap

/**
 * Auto Experiment (Chronomatron + Ultrasequencer) for the Hypixel SkyBlock experimentation table.
 *
 * How the modern Hypixel GUI behaves (6-row chest, status item in slot 49):
 *  - Chronomatron: slot 49 shows "Remember the pattern!" briefly between rounds and "Timer: Ns"
 *    during a round. During the round the whole chain is replayed (each pane gets an enchantment
 *    glint / turns into stained glass for a moment, one after another) plus one new pane, and then
 *    the player has to click the chain in order while the timer is still running. Which status text
 *    is visible while the panes light up is NOT relied upon: lit panes are recorded whenever the
 *    experiment is not over, exactly like the 1.8.9 version (which recorded the enchanted pane while
 *    the clock was shown) and the Skyblocker solver do.
 *  - Ultrasequencer: numbered panes ("1", "2", ...) are shown while slot 49 says "Remember the
 *    pattern!", then they are hidden and the player clicks them in order during "Timer: Ns".
 *
 * A Chronomatron round is only answered when the number of panes shown equals the previous chain
 * length + 1 (round 1: exactly one pane). If the solver was enabled in the middle of an experiment
 * it only watches until the next round starts, so the player can still answer by hand.
 * Everything is logged with the "[AutoExperiment]" prefix so a failing run can be diagnosed.
 */
class AExperiment(
    private val config: LBQConfig
) {
    private val mc: Minecraft get() = Minecraft.getInstance()

    /** The chest screen we last treated as "opened" (AFTER_INIT fires again on resize for the same instance). */
    private var currentScreen: Screen? = null
    /** Title of that screen; Hypixel may re-open a new window with the same title mid-experiment. */
    private var currentTitle: String? = null
    /** When the last experiment screen was removed; a same-title re-open shortly after keeps the state. */
    private var lastRemovedAt: Long = 0

    private var currentExperiment = ExperimentType.NONE
    /** Set when the solver threw; stays off until the GUI is reopened (avoids crashing the screen tick). */
    private var failed = false
    private var state = State.WATCH
    private var lastClickTime: Long = 0
    private var lastStatus = ""

    // ---- Chronomatron
    /** The pattern learned so far: slot the pane was shown in + its colour, in order. */
    private val chronomatronChain = ArrayList<ChainEntry>()
    /** How many panes lit up so far in the current round. */
    private var shownCount = 0
    /** Number of panes the current round must show (previous chain + 1); -1 = unknown, watch only. */
    private var expectedLen = -1
    /** Slot that is currently lit, -1 when nothing is lit. */
    private var litSlot = -1
    /** Set once all expected panes were shown; the answer starts when the last one goes dark. */
    private var replayDoneAt: Long = -1
    /** Next chain index to click. */
    private var nextClick = 0
    /** When the answer was finished (or the round given up); used to ignore our own click feedback. */
    private var doneAt: Long = 0
    /** Since when slot 49 shows a text that is neither round status; -1 = it shows a known one. */
    private var unknownStatusSince: Long = -1
    /** Slots still glowing from our own clicks when the new round started; ignored until they go dark. */
    private val leftoverLit = HashSet<Int>()
    /** slot -> time of our last click on it (Hypixel lights a clicked pane for a moment). */
    private val recentClicks = HashMap<Int, Long>()

    // ---- Ultrasequencer
    /** number shown on the pane -> slot index. */
    private val ultrasequencerOrder = TreeMap<Int, Int>()
    private var nextNumber = 1

    private val isEnabled: Boolean
        get() = config.AutoExperimentSwitch

    private val delay: Long
        get() = config.AutoExperimentSlider.toLong()

    fun reset() {
        currentExperiment = ExperimentType.NONE
        chronomatronChain.clear()
        ultrasequencerOrder.clear()
        expectedLen = -1
        lastStatus = ""
        resetRound()
    }

    private fun resetRound() {
        state = State.WATCH
        shownCount = 0
        litSlot = -1
        replayDoneAt = -1
        nextClick = 0
        nextNumber = 1
        leftoverLit.clear()
        recentClicks.clear()
    }

    /**
     * Replaces the Forge GuiOpenEvent / DrawScreenEvent.Post subscribers.
     *
     * AFTER_INIT fires on screen open AND on every window resize of the same screen instance.
     * Fabric recreates the per-screen events on each (re)init, so the listeners must always be
     * (re)attached, while the "opened" logic must only run once per screen instance.
     */
    fun register() {
        ScreenEvents.AFTER_INIT.register { _, screen, _, _ ->
            if (screen !is ContainerScreen) return@register

            if (screen !== currentScreen) {
                currentScreen = screen
                onGuiOpen(screen)
            }

            // Per frame (like the old DrawScreenEvent.Post): container packets are applied per frame,
            // so this never misses a lit/unlit transition that a 20 Hz tick poll could skip.
            ScreenEvents.afterExtract(screen).register { s, _, _, _, _ ->
                if (s is ContainerScreen) onGuiDraw(s)
            }
            // Fallback while no frames are rendered (e.g. minimised window).
            ScreenEvents.afterTick(screen).register { s ->
                if (s is ContainerScreen) onGuiDraw(s)
            }
            ScreenEvents.remove(screen).register { s ->
                if (s === currentScreen) {
                    currentScreen = null
                    lastRemovedAt = System.currentTimeMillis()
                }
            }
        }
    }

    fun onGuiOpen(gui: ContainerScreen) {
        failed = false // a new window is a new chance
        val title = gui.getTitle().string
        val type = detectExperiment(title)
        val reopenedQuickly = System.currentTimeMillis() - lastRemovedAt < REOPEN_GRACE_MS
        if (type != ExperimentType.NONE && type == currentExperiment && title == currentTitle && reopenedQuickly) {
            // Hypixel re-opened a new window for the same experiment (new container id): keep the
            // learned chain and the current state, the slots are laid out identically.
            Main.LOGGER.info("[AutoExperiment] same experiment re-opened, keeping state ({})", title)
            return
        }
        reset()
        currentTitle = title
        currentExperiment = type
        if (currentExperiment != ExperimentType.NONE) {
            // A freshly opened experiment always starts with round 1 (one pane).
            expectedLen = 1
            Main.LOGGER.info("[AutoExperiment] detected {} ({}) enabled={}", currentExperiment, title, isEnabled)
        }
    }

    private fun detectExperiment(title: String): ExperimentType = when {
        title.startsWith("Chronomatron") -> ExperimentType.CHRONOMATRON
        title.startsWith("Ultrasequencer") -> ExperimentType.ULTRASEQUENCER
        else -> ExperimentType.NONE
    }

    fun onGuiDraw(gui: ContainerScreen) {
        if (failed) return
        if (!isEnabled) {
            // Switched off mid-round: forget the round so a later re-enable starts clean.
            if (currentExperiment != ExperimentType.NONE) reset()
            return
        }

        // Enabled after the GUI was opened: detect lazily, but only watch this round (we may be in
        // the middle of it), the next round is answered.
        if (currentExperiment == ExperimentType.NONE) {
            val title = gui.getTitle().string
            currentExperiment = detectExperiment(title)
            currentTitle = title
            if (currentExperiment == ExperimentType.NONE) return
            expectedLen = -1
            Main.LOGGER.info("[AutoExperiment] enabled while {} is open, watching this round only", currentExperiment)
        }

        val menu = gui.getMenu()
        // Both experiments use a 6-row chest; anything else (stakes / reward menus with the same
        // title prefix) would make slot 49 and the board indices point into the player inventory.
        if (menu.rowCount != CHEST_ROWS) return

        // An exception here would crash the client ("Ticking screen"), so fail soft instead:
        // log once, forget the round and stay quiet until the GUI is reopened.
        try {
            val status = statusText(menu)
            val statusChanged = status != lastStatus
            if (statusChanged) {
                Main.LOGGER.info("[AutoExperiment] status: '{}' -> '{}' (state {}, shown {}/{}, chain {})", lastStatus, status, state, shownCount, expectedLen, chronomatronChain.size)
                lastStatus = status
            }
            when (currentExperiment) {
                ExperimentType.CHRONOMATRON -> tickChronomatron(menu, status, statusChanged)
                ExperimentType.ULTRASEQUENCER -> tickUltrasequencer(menu, status)
                else -> return
            }
        } catch (e: Exception) {
            Main.LOGGER.error("[AutoExperiment] solver failed, disabled until the GUI is reopened", e)
            reset()
            failed = true
        }
    }

    // ------------------------------------------------------------------ Chronomatron

    private fun tickChronomatron(menu: AbstractContainerMenu, status: String, statusChanged: Boolean) {
        val now = System.currentTimeMillis()

        // Neither the round status nor the between-rounds status: the experiment is over
        // (rewards, "Claim" items, ...). Forget everything, but only once the unknown text has
        // stayed for a moment, in case Hypixel flashes a short message between rounds.
        if (status.isNotEmpty() && !isRoundStatus(status)) {
            if (unknownStatusSince < 0) unknownStatusSince = now
            if (now - unknownStatusSince < UNKNOWN_STATUS_MS) return
            if (chronomatronChain.isNotEmpty() || state != State.WATCH) {
                Main.LOGGER.info("[AutoExperiment] Chronomatron over (status '{}')", status)
            }
            chronomatronChain.clear()
            expectedLen = 1
            resetRound()
            return
        }
        unknownStatusSince = -1

        // Glows left from our own clicks: forget them once they go dark.
        leftoverLit.removeAll { !isLit(menu.getSlot(it).item) }
        recentClicks.entries.removeAll { now - it.value > CLICK_GLOW_MS }

        when (state) {
            State.DONE -> {
                // Waiting for the next round: it starts with "Remember the pattern!" or, if that is
                // too short to be seen, with the first replayed pane lighting up (once our own click
                // feedback can no longer be mistaken for it).
                val lit = if (now - doneAt > CLICK_GLOW_MS) findLitSlot(menu) else -1
                if (status == REMEMBER_TEXT || lit != -1) {
                    startRound(menu, now)
                    if (lit != -1 && state == State.WATCH) onPaneLit(menu, lit, status)
                }
            }

            State.WATCH -> {
                if (statusChanged && status == REMEMBER_TEXT && shownCount > 0) {
                    // "Remember the pattern!" only appears between rounds: the round we were watching
                    // ended without us (answered by hand or timed out). Start over for the new one.
                    Main.LOGGER.info("[AutoExperiment] Chronomatron: round ended while watching ({} panes seen)", shownCount)
                    startRound(menu, now)
                    return
                }
                if (litSlot != -1) {
                    if (isLit(menu.getSlot(litSlot).item)) {
                        if (replayDoneAt >= 0 && now - replayDoneAt > LIT_TIMEOUT_MS) {
                            // The last pane stays lit: do not wait for it forever.
                            litSlot = -1
                            startAnswer(now)
                        }
                        return // still lit
                    }
                    litSlot = -1 // went dark: look for the next pane right away
                    if (replayDoneAt >= 0) {
                        // The last expected pane went dark: our turn.
                        startAnswer(now)
                        return
                    }
                }
                val lit = findLitSlot(menu)
                if (lit != -1) onPaneLit(menu, lit, status)
            }

            State.ANSWER -> {
                if (statusChanged && status == REMEMBER_TEXT) {
                    // Hypixel already moved on to the next round (we were too slow, or it accepted our
                    // clicks faster than we noticed); the chain is complete, keep it.
                    Main.LOGGER.info("[AutoExperiment] Chronomatron: next round started while answering ({} of {} clicked)", nextClick, chronomatronChain.size)
                    finishAnswer(now)
                    return
                }
                if (nextClick >= chronomatronChain.size) {
                    finishAnswer(now)
                    return
                }
                // Clicks only count while the timer runs; if the panes were shown under
                // "Remember the pattern!" the timer appears right after the last one went dark.
                if (!status.startsWith(TIMER_PREFIX)) return
                if (now - lastClickTime < delay) return

                val entry = chronomatronChain[nextClick]
                val slot = findChronomatronSlot(menu, entry) ?: return // retry next frame
                clickSlot(menu, slot)
                recentClicks[slot] = now
                lastClickTime = now
                nextClick++
                if (nextClick >= chronomatronChain.size) finishAnswer(now)
            }
        }
    }

    private fun startRound(menu: AbstractContainerMenu, now: Long) {
        // Same rule as the 1.8.9 version: stop once the chain grew past 13 - serum answered rounds.
        if (chronomatronChain.size > 13 - config.AutoExperimentSerumSlider) {
            Main.LOGGER.info("[AutoExperiment] Chronomatron chain length {} reached the limit, closing", chronomatronChain.size)
            mc.player?.closeContainer()
            return
        }
        val shownCountBeforeStart = shownCount
        state = State.WATCH
        shownCount = 0
        nextClick = 0
        litSlot = -1
        replayDoneAt = -1
        leftoverLit.clear()
        for (slot in BOARD_SLOTS) {
            if (isLit(menu.getSlot(slot).item)) leftoverLit.add(slot) // still glowing from our last click
        }
        // The whole chain is replayed plus one new pane. After a watch-only round the panes we
        // counted in it are the best estimate of the chain length; if we saw nothing at all the
        // length stays unknown and this round is only watched too.
        val previousShown = shownCountBeforeStart
        expectedLen = when {
            expectedLen >= 0 -> chronomatronChain.size + 1
            previousShown > 0 -> {
                if (chronomatronChain.size > previousShown) chronomatronChain.subList(previousShown, chronomatronChain.size).clear()
                previousShown + 1
            }
            else -> -1
        }
        Main.LOGGER.info("[AutoExperiment] Chronomatron: round started, expecting {} panes (chain {}, leftover {})", expectedLen, chronomatronChain.size, leftoverLit)
    }

    /** A pane just lit up (transition dark -> lit). */
    private fun onPaneLit(menu: AbstractContainerMenu, slot: Int, status: String) {
        litSlot = slot
        val stack = menu.getSlot(slot).item
        val entry = ChainEntry(slot, colorOf(stack))
        Main.LOGGER.info("[AutoExperiment] pane {} lit: {} foil={} name='{}' (status '{}', #{} of {})", slot, itemId(stack), stack.hasFoil(), stack.hoverName.string, status, shownCount + 1, expectedLen)

        if (expectedLen < 0) {
            // Watch-only round: remember what we see so the next round has a reference chain.
            if (shownCount < chronomatronChain.size) chronomatronChain[shownCount] = entry else chronomatronChain.add(entry)
            shownCount++
            return
        }
        if (shownCount >= expectedLen) {
            // More panes than the chain can have: our count is polluted (someone clicked by hand?).
            // Keep counting so the next round gets a fresh length estimate, but do not answer.
            Main.LOGGER.info("[AutoExperiment] Chronomatron: unexpected extra pane, watching only until the next round")
            expectedLen = -1
            replayDoneAt = -1
            if (shownCount < chronomatronChain.size) chronomatronChain[shownCount] = entry else chronomatronChain.add(entry)
            shownCount++
            return
        }
        if (shownCount < chronomatronChain.size) {
            // Replay of a pane we already know; self-heal if it does not match.
            if (chronomatronChain[shownCount] != entry) {
                chronomatronChain.subList(shownCount, chronomatronChain.size).clear()
                chronomatronChain.add(entry)
            }
        } else {
            chronomatronChain.add(entry)
        }
        shownCount++
        if (shownCount == expectedLen) {
            replayDoneAt = System.currentTimeMillis()
        }
    }

    private fun startAnswer(now: Long) {
        if (expectedLen < 0 || chronomatronChain.size != expectedLen) {
            Main.LOGGER.info("[AutoExperiment] Chronomatron: not answering (chain {}, expected {}); answer by hand", chronomatronChain.size, expectedLen)
            expectedLen = -1 // next round is sized from what we counted in this one
            doneAt = now
            state = State.DONE
            return
        }
        Main.LOGGER.info("[AutoExperiment] Chronomatron: answering a chain of {}", chronomatronChain.size)
        nextClick = 0
        lastClickTime = now - delay // first click right away
        state = State.ANSWER
    }

    private fun finishAnswer(now: Long) {
        state = State.DONE
        doneAt = now
        replayDoneAt = -1
        litSlot = -1
        if (nextClick >= chronomatronChain.size) {
            Main.LOGGER.info("[AutoExperiment] Chronomatron: chain of {} answered", chronomatronChain.size)
        }
    }

    private fun isRoundStatus(status: String): Boolean =
        status == REMEMBER_TEXT || status.startsWith(TIMER_PREFIX)

    /** Lit = enchantment glint, the criterion the 1.8.9 version and the Skyblocker solver use. */
    private fun isLit(stack: ItemStack): Boolean = !stack.isEmpty && stack.hasFoil()

    /** First slot of the board (rows 2-5) that is lit and not a glow from our own clicks, or -1. */
    private fun findLitSlot(menu: AbstractContainerMenu): Int {
        for (slot in BOARD_SLOTS) {
            if (slot in leftoverLit || slot in recentClicks) continue
            if (isLit(menu.getSlot(slot).item)) return slot
        }
        return -1
    }

    /**
     * Slot to click for a remembered chain entry: the remembered slot if it still shows that colour
     * (or is empty because our own click prediction moved its item to the cursor), else any slot
     * with that colour; null means "not visible right now, retry".
     */
    private fun findChronomatronSlot(menu: AbstractContainerMenu, entry: ChainEntry): Int? {
        val remembered = menu.getSlot(entry.slot).item
        if (remembered.isEmpty || entry.color == null || colorOf(remembered) == entry.color) return entry.slot
        for (slot in BOARD_SLOTS) {
            if (colorOf(menu.getSlot(slot).item) == entry.color) return slot
        }
        return null
    }

    /** Colour of a terracotta / stained glass (pane) item, null for anything else. */
    private fun colorOf(stack: ItemStack): DyeColor? {
        val item = stack.item as? BlockItem ?: return null
        return when (val block = item.block) {
            is StainedGlassBlock -> block.color
            is StainedGlassPaneBlock -> block.color
            else -> TERRACOTTA_COLORS[item]
        }
    }

    // ------------------------------------------------------------------ Ultrasequencer

    private fun tickUltrasequencer(menu: AbstractContainerMenu, status: String) {
        val now = System.currentTimeMillis()

        if (status.isNotEmpty() && !isRoundStatus(status)) {
            if (ultrasequencerOrder.isNotEmpty() || state != State.WATCH) {
                Main.LOGGER.info("[AutoExperiment] Ultrasequencer over (status '{}')", status)
            }
            ultrasequencerOrder.clear()
            resetRound()
            return
        }

        when (state) {
            State.DONE -> {
                if (status == REMEMBER_TEXT) {
                    ultrasequencerOrder.clear()
                    resetRound()
                }
            }

            State.WATCH -> {
                if (!status.startsWith(TIMER_PREFIX)) {
                    // The panes are named "1", "2", ...; keep scanning in case they arrive over several frames.
                    for (slot in BOARD_SLOTS) {
                        val name = menu.getSlot(slot).item.hoverName.string
                        if (name.isNotEmpty() && name.length <= 3 && name.all { it in '0'..'9' }) {
                            name.toIntOrNull()?.let {
                                if (ultrasequencerOrder.put(it, slot) == null) {
                                    Main.LOGGER.info("[AutoExperiment] pane {} shows number {} ({})", slot, it, itemId(menu.getSlot(slot).item))
                                }
                            }
                        }
                    }
                    return
                }
                val complete = ultrasequencerOrder.isNotEmpty()
                        && ultrasequencerOrder.firstKey() == 1
                        && ultrasequencerOrder.lastKey() == ultrasequencerOrder.size
                if (!complete) {
                    Main.LOGGER.info("[AutoExperiment] Ultrasequencer: incomplete sequence {} seen, not answering this round", ultrasequencerOrder.keys)
                    state = State.DONE
                    return
                }
                // Same rule as the 1.8.9 version: stop before answering a sequence longer than 12 - serum.
                if (ultrasequencerOrder.size > 12 - config.AutoExperimentSerumSlider) {
                    Main.LOGGER.info("[AutoExperiment] Ultrasequencer sequence length {} reached the limit, closing", ultrasequencerOrder.size)
                    mc.player?.closeContainer()
                    return
                }
                Main.LOGGER.info("[AutoExperiment] Ultrasequencer: answering a sequence of {}", ultrasequencerOrder.size)
                nextNumber = 1
                lastClickTime = now - delay
                state = State.ANSWER
            }

            State.ANSWER -> {
                if (!status.startsWith(TIMER_PREFIX)) {
                    state = State.DONE
                    return
                }
                val slot = ultrasequencerOrder[nextNumber]
                if (slot == null) {
                    state = State.DONE
                    return
                }
                if (now - lastClickTime < delay) return

                clickSlot(menu, slot)
                lastClickTime = now
                val next = ultrasequencerOrder.higherKey(nextNumber)
                if (next == null) {
                    Main.LOGGER.info("[AutoExperiment] Ultrasequencer: sequence of {} answered", ultrasequencerOrder.size)
                    state = State.DONE
                } else {
                    nextNumber = next
                }
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Name of the status item in slot 49 ("Remember the pattern!" / "Timer: 9s" / ...), "" when the slot is empty. */
    private fun statusText(menu: AbstractContainerMenu): String {
        val stack = menu.getSlot(STATUS_SLOT).item
        if (stack.isEmpty) return ""
        return (ChatFormatting.stripFormatting(stack.hoverName.string) ?: "").trim()
    }

    private fun itemId(stack: ItemStack): String = BuiltInRegistries.ITEM.getKey(stack.item).path

    /** A plain left click, exactly what a player does. */
    private fun clickSlot(menu: AbstractContainerMenu, slotIndex: Int) {
        val player = mc.player ?: return
        Main.LOGGER.info("[AutoExperiment] click slot {} ({})", slotIndex, itemId(menu.getSlot(slotIndex).item))
        mc.gameMode?.handleContainerInput(menu.containerId, slotIndex, 0, ContainerInput.PICKUP, player)
    }

    private data class ChainEntry(val slot: Int, val color: DyeColor?)

    private enum class ExperimentType {
        CHRONOMATRON,
        ULTRASEQUENCER,
        NONE
    }

    private enum class State {
        /** Watching the board: the pattern is being shown (Ultrasequencer: numbers visible). */
        WATCH,
        /** Our turn: click the answer. */
        ANSWER,
        /** Answer sent (or round skipped); waiting for the next round or the end of the experiment. */
        DONE
    }

    private companion object {
        const val CHEST_ROWS = 6
        const val STATUS_SLOT = 49
        const val REMEMBER_TEXT = "Remember the pattern!"
        const val TIMER_PREFIX = "Timer:"
        /** A same-title window re-opened later than this is a new experiment, not a Hypixel re-open. */
        const val REOPEN_GRACE_MS = 5000L
        /** How long Hypixel keeps a clicked pane lit (upper bound used to ignore our own click feedback). */
        const val CLICK_GLOW_MS = 1500L
        /** If the last replayed pane stays lit longer than this, answer anyway. */
        const val LIT_TIMEOUT_MS = 2000L
        /** An unknown slot-49 text must stay this long before it counts as "experiment over". */
        const val UNKNOWN_STATUS_MS = 2000L
        /** Rows 2-5 of the chest; the border row 0 and the bottom row (with the status item) are excluded. */
        val BOARD_SLOTS = 9..44

        /** Dyed terracotta item -> colour (the unlit state of the Chronomatron panes). */
        val TERRACOTTA_COLORS: Map<net.minecraft.world.item.Item, DyeColor> =
            DyeColor.values().associateBy { Items.DYED_TERRACOTTA.pick(it) }
    }
}
