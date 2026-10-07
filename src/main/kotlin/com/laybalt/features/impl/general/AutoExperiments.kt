// Ported from OdinClient (https://github.com/skies-starred/OdinClient), BSD-3-Clause, Copyright (c) 2026 Starred
package com.laybalt.features.impl.general

//#if CHEAT

import com.laybalt.LayBaltQOL
import com.laybalt.config.types.DropdownSetting
import com.laybalt.config.types.KeybindSetting
import com.laybalt.config.types.SliderSetting
import com.laybalt.config.types.ToggleSetting
import com.laybalt.event.EventBus
import com.laybalt.event.impl.ContainerEvent
import com.laybalt.event.impl.KeyboardEvent
import com.laybalt.event.impl.MainThreadPacketReceivedEvent
import com.laybalt.event.impl.MouseClickEvent
import com.laybalt.event.impl.PacketEvent
import com.laybalt.event.impl.TickEvent
import com.laybalt.event.impl.WorldChangeEvent
import com.laybalt.features.Feature
import com.laybalt.utils.ChatUtils
import com.laybalt.utils.ChatUtils.removeFormatting
import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.ChestMenu
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.DyeColor
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.StainedGlassBlock
import net.minecraft.world.level.block.StainedGlassPaneBlock
import java.util.TreeMap
import kotlin.random.Random

/**
 * Solver for the Experimentation Table add-on games (Chronomatron, Ultrasequencer and, experimentally, Superpairs).
 *
 * Replaces the old LayBalt `AExperiment` ("Auto Enchanting") and OdinClient's `AutoExperiments`:
 * - phases are read from the NAME of the status item in slot 49 ("Remember the pattern!" / "Timer: Ns"),
 * - Chronomatron uses the self-healing, pane-counting model of the old LayBalt solver,
 * - board changes are processed on every container packet (main thread) and clicks are sent on client ticks only,
 *   with OdinClient's click delay + random variance, OdinClient's (0, CLONE) click and its manual-click blocking.
 */
object AutoExperiments: Feature(
    "Solves Chronomatron and Ultrasequencer (and, experimentally, Superpairs) at the Experimentation Table.",
    name = "Auto Enchanting"
) {
    private val chronomatron by ToggleSetting("Solve Chronomatron", true)
        .withDescription("Automatically clicks the Chronomatron pattern.")
    private val ultrasequencer by ToggleSetting("Solve Ultrasequencer", true)
        .withDescription("Automatically clicks the Ultrasequencer numbers in order.")
    private val superpairs by ToggleSetting("Solve Superpairs", false)
        .withDescription("§6Experimental:§r remembers revealed Superpairs cards and clicks matching pairs. Untested against Hypixel's timing - keep an eye on it.")

    private val clickDelay by SliderSetting("Click Delay", 200, 100, 1000, 10, "ms")
        .withDescription("Time between two automatic clicks.")
    private val delayVariance by SliderSetting("Delay Variance", 50, 0, 1000, 10, "ms")
        .withDescription("Random extra time (0 up to this value) added to every click delay.")
    private val clickType by DropdownSetting("Click Type", 0, listOf("Middle", "Left"))
        .withDescription("Middle = middle click (the item never lands on your cursor). Left = plain left click, like the old LayBalt solver.")

    private val serumCount by SliderSetting("Serums Consumed", 0, 0, 3, 1)
        .withDescription("How many Metaphysical Serums you consumed. Each serum lowers the number of rounds needed for the full reward.")
        .showIf { ! getMaxXp.value }
    private val getMaxXp by ToggleSetting("Get Max XP", false)
        .withDescription("Keeps solving for maximum enchanting XP: Chronomatron up to 15 rounds, Ultrasequencer up to 20.")
    private val autoClose by ToggleSetting("Auto Close", true)
        .withDescription("Closes the experiment once the round target is reached. Off = stops clicking and leaves the rest to you.")
    private val blockClicks by ToggleSetting("Block Manual Clicks", true)
        .withDescription("Ignores your own mouse clicks inside the experiment while the solver is answering, so you cannot break the pattern by accident.")
    private val toggleKey by KeybindSetting("Toggle Keybind")
        .withDescription("Turns Auto Enchanting on or off. Works in game and inside the experiment menus. Unbound by default.")

    private val advanced by ToggleSetting("Advanced", false)
        .withDescription("Shows the round targets and the debug log.")
    private val chronomatronRounds by SliderSetting("Chronomatron Rounds", 11, 5, 20, 1)
        .withDescription("Chronomatron stops once more than (this - serums) rounds were answered. 11 matches OdinClient, the old LayBalt solver used 13.")
        .showIf { advanced.value }
    private val ultrasequencerRounds by SliderSetting("Ultrasequencer Rounds", 9, 5, 20, 1)
        .withDescription("Ultrasequencer stops before answering a sequence longer than (this - serums). 9 matches OdinClient, the old LayBalt solver used 12.")
        .showIf { advanced.value }
    private val debugLog by ToggleSetting("Debug Log", false)
        .withDescription("Writes every solver step to the game log (latest.log) with the [AutoExperiment] prefix.")
        .showIf { advanced.value }

    private const val STATUS_SLOT = 49
    private const val REMEMBER_TEXT = "Remember the pattern!"
    private const val TIMER_PREFIX = "Timer:"
    private const val REOPEN_GRACE_MS = 5000L
    private const val UNKNOWN_STATUS_MS = 2000L
    private const val CLICK_GLOW_MS = 1500L
    private const val LIT_TIMEOUT_MS = 2000L

    private val CHRONOMATRON_BOARD = 10 .. 43
    private val ULTRASEQUENCER_BOARD = 9 .. 44
    private val TERRACOTTA_COLORS: Map<Item, DyeColor> = DyeColor.entries.associateBy { Items.DYED_TERRACOTTA.pick(it) }

    private enum class Game(val prefix: String) {
        CHRONOMATRON("Chronomatron ("),
        ULTRASEQUENCER("Ultrasequencer ("),
        SUPERPAIRS("Superpairs (");

        companion object {
            fun of(title: String) = entries.firstOrNull { title.startsWith(it.prefix) }
        }
    }

    private var game: Game? = null
    private var solver: Solver? = null
    private var currentScreen: AbstractContainerScreen<*>? = null
    private var currentTitle: String? = null
    private var lastClosedAt = 0L
    private var failed = false
    private var lastStatus = ""
    private var nextClickAt = 0L

    override fun init() {
        // The toggle key must also turn the feature ON, so it is registered globally (not feature-scoped).
        EventBus.register<KeyboardEvent.KeyPressed> {
            if (event.action != InputConstants.PRESS || ! canToggleHere()) return@register
            if (! toggleKey.matches(event.keyEvent.key, mouse = false)) return@register
            toggle()
            event.isCanceled = true
        }

        EventBus.register<MouseClickEvent> {
            if (event.action != InputConstants.PRESS || ! canToggleHere()) return@register
            if (! toggleKey.matches(event.button, mouse = true)) return@register
            toggle()
            event.isCanceled = true
        }

        register<ContainerEvent.Open> { safely { onScreenOpened(event.screen, fromEvent = true) } }

        register<ContainerEvent.Close> {
            if (event.screen !== currentScreen) return@register
            currentScreen = null
            lastClosedAt = System.currentTimeMillis()
        }

        // A close sent by the client (Escape, Auto Close) ends the experiment: never carry its state over. A window the
        // server re-opens between rounds sends no close packet, so the reopen grace below still applies to it.
        register<PacketEvent.Sent> { if (event.packet is ServerboundContainerClosePacket) mc.execute { resetAll() } }

        register<WorldChangeEvent> { resetAll() }

        register<MainThreadPacketReceivedEvent.Post> {
            val containerId = when (val packet = event.packet) {
                is ClientboundContainerSetSlotPacket -> packet.containerId
                is ClientboundContainerSetContentPacket -> packet.containerId
                else -> return@register
            }
            val screen = currentScreen ?: return@register
            if (screen.menu.containerId != containerId) return@register
            safely { update(screen, System.currentTimeMillis()) }
        }

        register<TickEvent.Start> { safely { tick() } }

        register<ContainerEvent.MouseClick> {
            if (shouldBlockInput(event.screen)) event.isCanceled = true
        }

        // Press AND release (OdinClient cancels both), so a half-sent manual click cannot desync the board.
        register<MouseClickEvent> {
            val screen = mc.gui.screen() as? AbstractContainerScreen<*> ?: return@register
            if (toggleKey.matches(event.button, mouse = true)) return@register
            if (shouldBlockInput(screen)) event.isCanceled = true
        }
    }

    override fun toggle() {
        super.toggle()
        if (mc.player != null && LayBaltQOL.isLoaded) ChatUtils.modMessage("§7AutoExperiment is now ${if (enabled) "§aon" else "§coff"}")
    }

    override fun onDisable() {
        super.onDisable()
        resetAll()
    }

    // ------------------------------------------------------------------ lifecycle

    private fun canToggleHere(): Boolean {
        val screen = mc.gui.screen() ?: return true
        return screen is AbstractContainerScreen<*> && Game.of(screen.title.string) != null
    }

    private fun resetAll() {
        game = null
        solver = null
        currentScreen = null
        currentTitle = null
        failed = false
        lastStatus = ""
    }

    private fun onScreenOpened(screen: AbstractContainerScreen<*>, fromEvent: Boolean) {
        // ContainerEvent.Open also fires on window resize for the same screen instance.
        if (screen === currentScreen) return

        val now = System.currentTimeMillis()
        val title = screen.title.string
        val type = Game.of(title)?.takeIf { isExperimentMenu(screen.menu) }
        val reopened = type != null && type == game && title == currentTitle && solver != null && now - lastClosedAt < REOPEN_GRACE_MS

        currentScreen = screen
        failed = false

        if (reopened) {
            // Hypixel re-sends a new window (new container id) for the same experiment between rounds: keep the state.
            debug("same experiment re-opened, keeping state ($title)")
            return
        }

        game = type
        currentTitle = title
        lastStatus = ""
        solver = when (type) {
            Game.CHRONOMATRON -> if (chronomatron.value) ChronomatronSolver(watchFirstRound = ! fromEvent) else null
            Game.ULTRASEQUENCER -> if (ultrasequencer.value) UltrasequencerSolver() else null
            Game.SUPERPAIRS -> if (superpairs.value) SuperpairsSolver() else null
            null -> null
        }
        nextClickAt = now + clickDelay.value
        if (type != null) debug("detected $type ($title), solver ${if (solver != null) "on" else "off"}, from ${if (fromEvent) "open" else "tick"}")
    }

    /** All three games are 6-row chests; the stake/reward menus with the same title prefix are smaller. */
    private fun isExperimentMenu(menu: AbstractContainerMenu) = (menu as? ChestMenu)?.rowCount == 6

    private fun tick() {
        val screen = mc.gui.screen() as? AbstractContainerScreen<*> ?: return
        // Feature enabled (or a screen opened) without the Open event reaching us: detect lazily, watch-only first round.
        if (screen !== currentScreen) onScreenOpened(screen, fromEvent = false)
        if (failed) return

        val now = System.currentTimeMillis()
        update(screen, now)

        val solver = solver ?: return
        if (now < nextClickAt) return
        val slot = solver.nextClick(screen.menu, now) ?: return
        click(screen.menu, slot)
        nextClickAt = now + delay()
    }

    private fun update(screen: AbstractContainerScreen<*>, now: Long) {
        if (failed || screen !== currentScreen) return
        val solver = solver ?: return
        val status = statusText(screen.menu)
        val statusChanged = status != lastStatus
        if (statusChanged) {
            debug("status '$lastStatus' -> '$status'")
            lastStatus = status
        }
        solver.update(screen.menu, status, statusChanged, now)
    }

    /** An exception inside a screen tick would crash the client: log once, reset and stay idle until the GUI is reopened. */
    private inline fun safely(block: () -> Unit) {
        try {
            block()
        }
        catch (e: Exception) {
            LayBaltQOL.logger.error("[AutoExperiment] solver failed, idle until the experiment is reopened", e)
            solver = null
            failed = true
        }
    }

    private fun shouldBlockInput(screen: AbstractContainerScreen<*>): Boolean {
        if (! blockClicks.value || screen !== currentScreen || failed) return false
        return solver?.blocksInput == true
    }

    /** Stop rule (§8): true when [size] answered rounds / sequence length is past the target. */
    private fun reachedTarget(game: Game, size: Int): Boolean {
        val target = when (game) {
            Game.CHRONOMATRON -> if (getMaxXp.value) 15 else chronomatronRounds.value - serumCount.value
            Game.ULTRASEQUENCER -> if (getMaxXp.value) 20 else ultrasequencerRounds.value - serumCount.value
            Game.SUPERPAIRS -> return false
        }
        return size > target
    }

    private fun onTargetReached(size: Int) {
        debug("target reached ($size), ${if (autoClose.value) "closing" else "stopping"}")
        solver = null
        if (autoClose.value) mc.player?.closeContainer()
        else ChatUtils.modMessage("§7AutoExperiment: §atarget reached§7, finish the experiment yourself.")
    }

    // ------------------------------------------------------------------ click engine

    private fun delay(): Long = clickDelay.value.toLong() + if (delayVariance.value > 0) Random.nextLong(0, delayVariance.value + 1L) else 0L

    private fun click(menu: AbstractContainerMenu, slot: Int) {
        val player = mc.player ?: return
        val gameMode = mc.gameMode ?: return
        if (slot !in 0 until 54) return
        debug("click slot $slot (${menu.slots[slot].item.hoverName.string})")
        // OdinClient's proven combination is button 0 + CLONE; Left = plain PICKUP like the old LayBalt solver.
        val input = if (clickType.value == 0) ContainerInput.CLONE else ContainerInput.PICKUP
        gameMode.handleContainerInput(menu.containerId, slot, 0, input, player)
    }

    // ------------------------------------------------------------------ helpers

    private fun statusText(menu: AbstractContainerMenu): String {
        val stack = menu.slots.getOrNull(STATUS_SLOT)?.item ?: return ""
        if (stack.isEmpty) return ""
        return stack.hoverName.string.removeFormatting().trim()
    }

    private fun isRoundStatus(status: String) = status == REMEMBER_TEXT || status.startsWith(TIMER_PREFIX)

    private fun isLit(stack: ItemStack) = ! stack.isEmpty && stack.hasFoil()

    private fun colorOf(stack: ItemStack): DyeColor? {
        val item = stack.item as? BlockItem ?: return null
        return when (val block = item.block) {
            is StainedGlassBlock -> block.color
            is StainedGlassPaneBlock -> block.color
            else -> TERRACOTTA_COLORS[item]
        }
    }

    private fun debug(msg: String) {
        if (debugLog.value) LayBaltQOL.logger.info("[AutoExperiment] $msg")
    }

    private abstract class Solver {
        /** True while the solver answers by itself; manual clicks are then blocked (if enabled). */
        abstract val blocksInput: Boolean

        /** Reads the board. Runs on every container packet and every tick; never clicks. */
        abstract fun update(menu: AbstractContainerMenu, status: String, statusChanged: Boolean, now: Long)

        /** The slot to click now (the solver advances as if it was clicked), or null. Called at most once per tick. */
        abstract fun nextClick(menu: AbstractContainerMenu, now: Long): Int?
    }

    // ------------------------------------------------------------------ Chronomatron

    private data class ChainEntry(val slot: Int, val color: DyeColor?)

    private enum class State { WATCH, ANSWER, DONE }

    /**
     * Every round the server replays the whole chain (each pane lights up with a glint for a moment) plus one new
     * pane, then the timer runs and the chain has to be clicked in order. The solver counts the lit panes against the
     * expected length (previous chain + 1), self-heals a mismatching replay and only answers a consistent chain.
     */
    private class ChronomatronSolver(watchFirstRound: Boolean): Solver() {
        private val chain = ArrayList<ChainEntry>()
        /** Panes the current round must show (chain + 1); -1 = unknown, watch this round only. */
        private var expectedLen = if (watchFirstRound) - 1 else 1
        private var shownCount = 0
        private var litSlot = - 1
        private var replayDoneAt = - 1L
        private var nextIndex = 0
        private var state = State.WATCH
        private var doneAt = 0L
        private var unknownStatusSince = - 1L
        private var timerRunning = false
        private val leftoverLit = HashSet<Int>()
        private val recentClicks = HashMap<Int, Long>()

        override val blocksInput get() = expectedLen >= 0

        override fun update(menu: AbstractContainerMenu, status: String, statusChanged: Boolean, now: Long) {
            timerRunning = status.startsWith(TIMER_PREFIX)

            if (status.isNotEmpty() && ! isRoundStatus(status)) {
                if (unknownStatusSince < 0) unknownStatusSince = now
                if (now - unknownStatusSince < UNKNOWN_STATUS_MS) return
                if (chain.isNotEmpty() || state != State.WATCH) debug("Chronomatron over (status '$status')")
                chain.clear()
                expectedLen = 1
                resetRound()
                return
            }
            unknownStatusSince = - 1

            leftoverLit.removeAll { ! isLit(menu.slots[it].item) }
            recentClicks.entries.removeAll { now - it.value > CLICK_GLOW_MS }

            when (state) {
                State.DONE -> {
                    val lit = if (now - doneAt > CLICK_GLOW_MS) findLitSlot(menu) else - 1
                    if (status == REMEMBER_TEXT || lit != - 1) {
                        if (! startRound(menu)) return
                        if (lit != - 1 && state == State.WATCH) onPaneLit(menu, lit, now)
                    }
                }

                State.WATCH -> {
                    if (statusChanged && status == REMEMBER_TEXT && shownCount > 0) {
                        debug("Chronomatron: round ended while watching ($shownCount panes seen)")
                        startRound(menu)
                        return
                    }
                    if (litSlot != - 1) {
                        if (isLit(menu.slots[litSlot].item)) {
                            if (replayDoneAt >= 0 && now - replayDoneAt > LIT_TIMEOUT_MS) {
                                litSlot = - 1
                                startAnswer(now)
                            }
                            return
                        }
                        litSlot = - 1
                        if (replayDoneAt >= 0) {
                            startAnswer(now)
                            return
                        }
                    }
                    val lit = findLitSlot(menu)
                    if (lit != - 1) onPaneLit(menu, lit, now)
                }

                State.ANSWER -> {
                    if ((statusChanged && status == REMEMBER_TEXT) || nextIndex >= chain.size) finishAnswer(now)
                }
            }
        }

        override fun nextClick(menu: AbstractContainerMenu, now: Long): Int? {
            if (state != State.ANSWER || ! timerRunning) return null // clicks only count while the timer runs
            if (nextIndex >= chain.size) {
                finishAnswer(now)
                return null
            }
            val slot = findSlot(menu, chain[nextIndex]) ?: return null // not visible right now, retry next tick
            recentClicks[slot] = now
            nextIndex ++
            if (nextIndex >= chain.size) finishAnswer(now)
            return slot
        }

        private fun resetRound() {
            state = State.WATCH
            shownCount = 0
            litSlot = - 1
            replayDoneAt = - 1
            nextIndex = 0
            leftoverLit.clear()
            recentClicks.clear()
        }

        /** @return false when the stop rule fired (the solver was dropped). */
        private fun startRound(menu: AbstractContainerMenu): Boolean {
            if (expectedLen >= 0 && reachedTarget(Game.CHRONOMATRON, chain.size)) {
                onTargetReached(chain.size)
                return false
            }
            val previousShown = shownCount
            state = State.WATCH
            shownCount = 0
            nextIndex = 0
            litSlot = - 1
            replayDoneAt = - 1
            leftoverLit.clear()
            for (slot in CHRONOMATRON_BOARD) if (isLit(menu.slots[slot].item)) leftoverLit.add(slot)
            expectedLen = when {
                expectedLen >= 0 -> chain.size + 1
                previousShown > 0 -> {
                    if (chain.size > previousShown) chain.subList(previousShown, chain.size).clear()
                    previousShown + 1
                }
                else -> - 1
            }
            debug("Chronomatron: round started, expecting $expectedLen panes (chain ${chain.size}, leftover $leftoverLit)")
            return true
        }

        private fun onPaneLit(menu: AbstractContainerMenu, slot: Int, now: Long) {
            litSlot = slot
            val entry = ChainEntry(slot, colorOf(menu.slots[slot].item))
            debug("Chronomatron: pane $slot lit (${entry.color}), #${shownCount + 1} of $expectedLen")

            if (expectedLen < 0 || shownCount >= expectedLen) {
                // Watch-only round, or more panes than possible (manual click?): remember, but do not answer this round.
                if (expectedLen >= 0) debug("Chronomatron: unexpected extra pane, watching only until the next round")
                expectedLen = - 1
                replayDoneAt = - 1
                if (shownCount < chain.size) chain[shownCount] = entry else chain.add(entry)
                shownCount ++
                return
            }

            if (shownCount < chain.size) {
                if (chain[shownCount] != entry) {
                    chain.subList(shownCount, chain.size).clear()
                    chain.add(entry)
                }
            }
            else chain.add(entry)
            shownCount ++
            if (shownCount == expectedLen) replayDoneAt = now
        }

        private fun startAnswer(now: Long) {
            if (expectedLen < 0 || chain.size != expectedLen) {
                debug("Chronomatron: not answering (chain ${chain.size}, expected $expectedLen); answer by hand")
                expectedLen = - 1
                doneAt = now
                state = State.DONE
                return
            }
            debug("Chronomatron: answering a chain of ${chain.size}")
            nextIndex = 0
            nextClickAt = now // first click right away
            state = State.ANSWER
        }

        private fun finishAnswer(now: Long) {
            if (state == State.ANSWER) debug("Chronomatron: answer finished ($nextIndex of ${chain.size} clicked)")
            state = State.DONE
            doneAt = now
            replayDoneAt = - 1
            litSlot = - 1
        }

        private fun findLitSlot(menu: AbstractContainerMenu): Int {
            for (slot in CHRONOMATRON_BOARD) {
                if (slot in leftoverLit || slot in recentClicks) continue
                if (isLit(menu.slots[slot].item)) return slot
            }
            return - 1
        }

        /** The remembered slot if it still shows the colour (or is empty: our click prediction), else any slot with it. */
        private fun findSlot(menu: AbstractContainerMenu, entry: ChainEntry): Int? {
            val remembered = menu.slots[entry.slot].item
            if (remembered.isEmpty || entry.color == null || colorOf(remembered) == entry.color) return entry.slot
            return CHRONOMATRON_BOARD.firstOrNull { colorOf(menu.slots[it].item) == entry.color }
        }
    }

    // ------------------------------------------------------------------ Ultrasequencer

    /** The numbered panes are visible while "Remember the pattern!" is shown and must be clicked in order once the timer runs. */
    private class UltrasequencerSolver: Solver() {
        private val order = TreeMap<Int, Int>() // number -> slot
        private var nextNumber = 1
        private var state = State.WATCH
        private var skipped = false

        override val blocksInput get() = ! skipped

        override fun update(menu: AbstractContainerMenu, status: String, statusChanged: Boolean, now: Long) {
            if (status.isNotEmpty() && ! isRoundStatus(status)) {
                if (order.isNotEmpty() || state != State.WATCH) debug("Ultrasequencer over (status '$status')")
                order.clear()
                resetRound()
                return
            }

            when (state) {
                State.DONE -> if (status == REMEMBER_TEXT) {
                    order.clear()
                    resetRound()
                    scan(menu)
                }

                State.WATCH -> {
                    if (! status.startsWith(TIMER_PREFIX)) {
                        scan(menu) // the numbers may arrive over several packets
                        return
                    }
                    val complete = order.isNotEmpty() && order.firstKey() == 1 && order.lastKey() == order.size
                    if (! complete) {
                        debug("Ultrasequencer: incomplete sequence ${order.keys}, not answering this round")
                        skipped = true
                        state = State.DONE
                        return
                    }
                    if (reachedTarget(Game.ULTRASEQUENCER, order.size)) return onTargetReached(order.size)
                    debug("Ultrasequencer: answering a sequence of ${order.size}")
                    nextNumber = order.firstKey()
                    nextClickAt = now
                    state = State.ANSWER
                }

                State.ANSWER -> if (! status.startsWith(TIMER_PREFIX)) state = State.DONE
            }
        }

        override fun nextClick(menu: AbstractContainerMenu, now: Long): Int? {
            if (state != State.ANSWER) return null
            val slot = order[nextNumber] ?: run {
                state = State.DONE
                return null
            }
            val next = order.higherKey(nextNumber)
            if (next == null) {
                debug("Ultrasequencer: sequence of ${order.size} answered")
                state = State.DONE
            }
            else nextNumber = next
            return slot
        }

        private fun resetRound() {
            state = State.WATCH
            nextNumber = 1
            skipped = false
        }

        private fun scan(menu: AbstractContainerMenu) {
            for (slot in ULTRASEQUENCER_BOARD) {
                val stack = menu.slots[slot].item
                if (stack.isEmpty) continue
                val name = stack.hoverName.string.removeFormatting().trim()
                if (name.length !in 1 .. 3 || ! name.all(Char::isDigit)) continue
                val number = name.toInt()
                if (order.put(number, slot) == null) debug("Ultrasequencer: pane $slot shows $number (count ${stack.count})")
            }
        }
    }

    // ------------------------------------------------------------------ Superpairs (experimental)

    /**
     * Remembers every revealed card and clicks known pairs; otherwise explores hidden cards it has not seen yet.
     * Hidden cards are cyan stained glass; any other item on a slot that was once hidden is a revealed card.
     */
    private class SuperpairsSolver: Solver() {
        private enum class Phase { FIRST, AWAIT_FIRST, SECOND, AWAIT_SECOND, SETTLE }

        private val board = HashSet<Int>()
        private val known = HashMap<Int, ItemStack>()
        private var phase = Phase.FIRST
        private var firstSlot = - 1
        private var secondSlot = - 1
        private var phaseSince = 0L
        private var failures = 0
        private var finished = false

        override val blocksInput get() = ! finished

        override fun update(menu: AbstractContainerMenu, status: String, statusChanged: Boolean, now: Long) {
            if (finished) return
            for (slot in 0 until 54) {
                if (slot == STATUS_SLOT) continue
                val stack = menu.slots[slot].item
                if (isHidden(stack)) board.add(slot)
                else if (slot in board && isCard(stack) && known[slot] == null) {
                    known[slot] = stack.copy()
                    debug("Superpairs: slot $slot is ${stack.hoverName.string}")
                }
            }

            when (phase) {
                Phase.AWAIT_FIRST -> when {
                    ! isHidden(menu.slots[firstSlot].item) -> {
                        failures = 0
                        setPhase(Phase.SECOND, now)
                    }
                    now - phaseSince > REVEAL_TIMEOUT_MS -> fail(now)
                }

                Phase.AWAIT_SECOND -> when {
                    ! isHidden(menu.slots[secondSlot].item) -> {
                        failures = 0
                        setPhase(Phase.SETTLE, now)
                    }
                    now - phaseSince > REVEAL_TIMEOUT_MS -> fail(now)
                }

                Phase.SETTLE -> {
                    // A wrong pair flips back after a moment, a matched pair stays revealed.
                    val bothHidden = isHidden(menu.slots[firstSlot].item) && isHidden(menu.slots[secondSlot].item)
                    if (bothHidden || now - phaseSince > SETTLE_MS) setPhase(Phase.FIRST, now)
                }

                else -> Unit
            }

            if (failures >= MAX_FAILURES) {
                debug("Superpairs: clicks are not revealing cards any more, stopping")
                finished = true
            }
            else if (board.isNotEmpty() && board.none { isHidden(menu.slots[it].item) } && phase == Phase.FIRST) {
                debug("Superpairs: no hidden card left")
                finished = true
            }
        }

        override fun nextClick(menu: AbstractContainerMenu, now: Long): Int? {
            if (finished) return null
            val hidden = board.filter { isHidden(menu.slots[it].item) }
            if (hidden.isEmpty()) return null

            return when (phase) {
                Phase.FIRST -> {
                    val pair = hidden.firstOrNull { a -> known[a]?.let { item -> hidden.any { b -> b != a && known[b]?.let { ItemStack.matches(it, item) } == true } } == true }
                    val slot = pair ?: hidden.filter { it !in known }.randomOrNull() ?: hidden.random()
                    firstSlot = slot
                    setPhase(Phase.AWAIT_FIRST, now)
                    slot
                }

                Phase.SECOND -> {
                    val item = known[firstSlot]
                    val partner = item?.let { hidden.firstOrNull { b -> b != firstSlot && known[b]?.let { ItemStack.matches(it, item) } == true } }
                    val slot = partner ?: hidden.filter { it != firstSlot && it !in known }.randomOrNull()
                        ?: hidden.filter { it != firstSlot }.randomOrNull() ?: return null
                    secondSlot = slot
                    setPhase(Phase.AWAIT_SECOND, now)
                    slot
                }

                else -> null
            }
        }

        private fun setPhase(phase: Phase, now: Long) {
            this.phase = phase
            phaseSince = now
        }

        private fun fail(now: Long) {
            failures ++
            setPhase(Phase.FIRST, now)
        }

        private fun isHidden(stack: ItemStack) = stack.item == Items.STAINED_GLASS.cyan()

        private fun isCard(stack: ItemStack) = ! stack.isEmpty && stack.item != Items.STAINED_GLASS_PANE.black() && ! isHidden(stack)

        private companion object {
            const val REVEAL_TIMEOUT_MS = 2000L
            const val SETTLE_MS = 1500L
            const val MAX_FAILURES = 3
        }
    }
}

//#endif
