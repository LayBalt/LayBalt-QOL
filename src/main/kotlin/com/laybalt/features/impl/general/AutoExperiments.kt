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
import com.laybalt.features.impl.general.experiments.ChronomatronSolver
import com.laybalt.features.impl.general.experiments.ExperimentBoard
import com.laybalt.features.impl.general.experiments.ExperimentDriver
import com.laybalt.features.impl.general.experiments.ExperimentGame
import com.laybalt.features.impl.general.experiments.ExperimentHost
import com.laybalt.features.impl.general.experiments.Experiments.STATUS_SLOT
import com.laybalt.features.impl.general.experiments.ItemKind
import com.laybalt.features.impl.general.experiments.Stake
import com.laybalt.features.impl.general.experiments.SuperpairsSolver
import com.laybalt.features.impl.general.experiments.UltrasequencerSolver
import com.laybalt.utils.ChatUtils
import com.laybalt.utils.ChatUtils.removeFormatting
import com.laybalt.utils.items.ItemUtils.lore
import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.sounds.SoundEvents
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
import kotlin.random.Random

/**
 * Solver for the Experimentation Table add-on games (Chronomatron, Ultrasequencer and, experimentally, Superpairs).
 *
 * Replaces the old LayBalt `AExperiment` ("Auto Enchanting") and OdinClient's `AutoExperiments`. The game logic lives in
 * the Minecraft-free solvers of [com.laybalt.features.impl.general.experiments], which are replayed against a simulation
 * of Hypixel's behaviour in the unit tests; this object adapts the open menu to them, feeds them every container packet
 * (main thread) and every client tick, and sends the clicks with OdinClient's click delay + random variance, its
 * (0, CLONE) click and its manual-click blocking.
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

    private val stopAtMaxClicks by ToggleSetting("Stop At Max Clicks", true)
        .withDescription("Reads from the stake you pick how many rounds give the most Superpairs clicks (its \"Chain of N\" / \"Series of N\" line, Metaphysical Serums already counted) and stops once they are answered, the way SkyHanni's alert knows it. Only the Metaphysical stake pays the third click. Off, or when the stake could not be read: Serums Consumed and the round targets under Advanced.")
        .showIf { ! getMaxXp.value }
    private val ultrasequencerEarly by ToggleSetting("Ultrasequencer Early Stop", false)
        .withDescription("SkyHanni's rule: closes Ultrasequencer as soon as the Series of N shows, one round early (about 6 s saved), because Hypixel paid the full Series for one round less. Not confirmed for the current game: check once that the Experiment Over rewards still list every Superpairs click before relying on it.")
        .showIf { stopAtMaxClicks.value && ! getMaxXp.value }
    private val serumCount by SliderSetting("Serums Consumed", 0, 0, 3, 1)
        .withDescription("How many Metaphysical Serums you consumed. Each serum lowers the number of rounds needed for the full reward. Only used when Stop At Max Clicks is off or could not read the stake.")
        .showIf { ! getMaxXp.value }
    private val getMaxXp by ToggleSetting("Get Max XP", false)
        .withDescription("Keeps solving for maximum enchanting XP: Chronomatron up to 15 rounds, Ultrasequencer up to 20.")
    private val autoClose by ToggleSetting("Auto Close", true)
        .withDescription("Closes the experiment once the round target is reached. Off = stops clicking and leaves the rest to you.")
    private val targetAlert by ToggleSetting("Target Alert", true)
        .withDescription("Plays a sound and says in chat when the target is reached (with Stop At Max Clicks: all Superpairs clicks of the add-on are yours).")
    private val blockClicks by ToggleSetting("Block Manual Clicks", true)
        .withDescription("Ignores your own mouse clicks inside the experiment while the solver is answering, so you cannot break the pattern by accident.")
    private val toggleKey by KeybindSetting("Toggle Keybind")
        .withDescription("Turns Auto Enchanting on or off. Works in game and inside the experiment menus. Unbound by default.")

    private val advanced by ToggleSetting("Advanced", false)
        .withDescription("Shows the round targets and the debug log.")
    private val chronomatronRounds by SliderSetting("Chronomatron Rounds", 11, 5, 20, 1)
        .withDescription("Without Stop At Max Clicks (or when the stake could not be read): Chronomatron stops once more than (this - serums) rounds were answered. 11 matches OdinClient, the old LayBalt solver used 13.")
        .showIf { advanced.value }
    private val ultrasequencerRounds by SliderSetting("Ultrasequencer Rounds", 9, 5, 20, 1)
        .withDescription("Without Stop At Max Clicks (or when the stake could not be read): Ultrasequencer stops before answering a sequence longer than (this - serums). 9 matches OdinClient, the old LayBalt solver used 12.")
        .showIf { advanced.value }
    private val debugLog by ToggleSetting("Debug Log", false)
        .withDescription("Writes every solver step to the game log (latest.log) with the [AutoExperiment] prefix.")
        .showIf { advanced.value }

    private var driver: ExperimentDriver? = null
    private var currentScreen: AbstractContainerScreen<*>? = null
    private var failed = false

    /** The stake last picked per game in its "➜ Stakes" menu ([Stake]); a game without one uses the configured targets. */
    private val stakes = mutableMapOf<ExperimentGame, Stake>()

    private val host = object: ExperimentHost {
        override fun debug(msg: String) = AutoExperiments.debug(msg)
        override fun reachedTarget(game: ExperimentGame, size: Int) = AutoExperiments.reachedTarget(game, size)
        override fun onTargetReached(size: Int) = AutoExperiments.onTargetReached(size)
        override fun onRoundSkipped(game: ExperimentGame) = AutoExperiments.onRoundSkipped(game)
    }

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

        // The stake you click in "Chronomatron ➜ Stakes" / "Ultrasequencer ➜ Stakes": its lore says how many rounds give
        // the most Superpairs clicks for the game it starts (SkyHanni's SuperpairsClicksAlert reads the same lines).
        // Registered globally, so a stake picked while the feature is off still counts once it is toggled on in the game.
        EventBus.register<ContainerEvent.SlotClick> {
            val title = event.screen.title.string.removeFormatting()
            if (! title.endsWith("Stakes")) return@register
            val game = ExperimentGame.entries.firstOrNull { title.startsWith(it.prefix.removeSuffix(" (")) } ?: return@register
            val stack = event.screen.menu.slots.getOrNull(event.slotId)?.item?.takeUnless { it.isEmpty } ?: return@register
            when (val stake = Stake.of(stack.lore.map { it.removeFormatting() })) {
                null -> return@register
                is Stake.Rewards -> stakes[game] = stake
                Stake.Practice -> stakes.remove(game)
            }
            debug("stake for $game: ${stakes[game] ?: "practice (configured targets)"}")
        }

        // Every experiment window starts a fresh solver: the logs of the old solver show no window re-opened mid-game, and
        // a quickly re-opened new game must never inherit the chain of the last one.
        register<ContainerEvent.Close> { if (event.screen === currentScreen) resetAll() }

        // A stake counts for the one game it started: forgotten when that game's window closes (globally, as it is recorded).
        EventBus.register<ContainerEvent.Close> {
            ExperimentGame.of(event.screen.title.string)?.takeIf { isExperimentMenu(event.screen.menu) }?.let(stakes::remove)
        }

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
        return screen is AbstractContainerScreen<*> && ExperimentGame.of(screen.title.string) != null
    }

    private fun resetAll() {
        driver = null
        currentScreen = null
        failed = false
    }

    /**
     * @param fromEvent the window was just opened (ContainerEvent.Open), so a game in it starts with round 1; otherwise the
     * feature was enabled (or the window found) while a game may already be running, and the solver joins mid-game.
     */
    private fun onScreenOpened(screen: AbstractContainerScreen<*>, fromEvent: Boolean) {
        // ContainerEvent.Open also fires on window resize for the same screen instance.
        if (screen === currentScreen) return

        val now = System.currentTimeMillis()
        val title = screen.title.string
        val type = ExperimentGame.of(title)?.takeIf { isExperimentMenu(screen.menu) }
        currentScreen = screen
        failed = false

        val solver = when (type) {
            ExperimentGame.CHRONOMATRON -> if (chronomatron.value) ChronomatronSolver(host, attachedAtStart = fromEvent) else null
            ExperimentGame.ULTRASEQUENCER -> if (ultrasequencer.value) UltrasequencerSolver(host) else null
            ExperimentGame.SUPERPAIRS -> if (superpairs.value) SuperpairsSolver(host) else null
            null -> null
        }
        driver = solver?.let { ExperimentDriver(it, host, ::delay, now, clickDelay.value.toLong()) }
        if (type != null) debug("detected $type ($title), solver ${if (solver != null) "on" else "off"}, from ${if (fromEvent) "open" else "tick"}")
    }

    /** All three games are 6-row chests; the stake/reward menus with the same title prefix are smaller. */
    private fun isExperimentMenu(menu: AbstractContainerMenu) = (menu as? ChestMenu)?.rowCount == 6

    private fun tick() {
        val screen = mc.gui.screen() as? AbstractContainerScreen<*> ?: return
        // Feature enabled (or a screen opened) without the Open event reaching us: detect lazily and join the running game.
        if (screen !== currentScreen) onScreenOpened(screen, fromEvent = false)
        if (failed) return
        val driver = driver ?: return
        val slot = driver.tick(MenuBoard(screen.menu), System.currentTimeMillis()) ?: return
        click(screen.menu, slot)
    }

    private fun update(screen: AbstractContainerScreen<*>, now: Long) {
        if (failed || screen !== currentScreen) return
        driver?.update(MenuBoard(screen.menu), now)
    }

    /** An exception inside a screen tick would crash the client: log once, reset and stay idle until the GUI is reopened. */
    private inline fun safely(block: () -> Unit) {
        try {
            block()
        }
        catch (e: Exception) {
            LayBaltQOL.logger.error("[AutoExperiment] solver failed, idle until the experiment is reopened", e)
            driver = null
            failed = true
        }
    }

    private fun shouldBlockInput(screen: AbstractContainerScreen<*>): Boolean {
        if (! blockClicks.value || screen !== currentScreen || failed) return false
        val solver = driver?.solver ?: return false
        return ! solver.stopped && solver.blocksInput
    }

    /** The rounds the picked stake gives the most Superpairs clicks for, when Stop At Max Clicks uses it. */
    private fun stakeRounds(game: ExperimentGame) = (stakes[game] as? Stake.Rewards)?.rounds?.takeIf { stopAtMaxClicks.value && ! getMaxXp.value }

    /** Stop rule: true when [size] answered rounds / sequence length is past the target. */
    private fun reachedTarget(game: ExperimentGame, size: Int): Boolean {
        if (game == ExperimentGame.SUPERPAIRS) return false
        val target = when {
            getMaxXp.value -> if (game == ExperimentGame.CHRONOMATRON) 15 else 20
            else -> stakeRounds(game)?.let { Stake.target(game, it, ultrasequencerEarly.value) }
                ?: if (game == ExperimentGame.CHRONOMATRON) chronomatronRounds.value - serumCount.value else ultrasequencerRounds.value - serumCount.value
        }
        return size > target
    }

    private fun onTargetReached(size: Int) {
        val game = currentScreen?.title?.string?.let(ExperimentGame::of)
        debug("target reached ($size), ${if (autoClose.value) "closing" else "stopping"}")
        driver = null
        if (targetAlert.value) {
            val name = game?.name?.lowercase()?.replaceFirstChar { it.uppercase() } ?: "Experiment"
            val rounds = game?.let(::stakeRounds)
            val what = if (rounds != null) "§aall Superpairs clicks§7 (${if (game == ExperimentGame.CHRONOMATRON) "chain" else "series"} of $rounds)" else "§atarget reached§7"
            ChatUtils.modMessage("§7AutoExperiment: $name $what${if (autoClose.value) ", closed." else ", finish it yourself."}")
            mc.player?.playSound(SoundEvents.NOTE_BLOCK_PLING.value(), 1f, 2f)
        }
        else if (! autoClose.value) ChatUtils.modMessage("§7AutoExperiment: §atarget reached§7, finish the experiment yourself.")
        if (autoClose.value) mc.player?.closeContainer()
    }

    /** The solver lost track and leaves this round to the player (input is not blocked); called once per round. */
    private fun onRoundSkipped(game: ExperimentGame) {
        val name = game.name.lowercase().replaceFirstChar { it.uppercase() }
        ChatUtils.modMessage("§7AutoExperiment: §clost track of the $name pattern§7, finish this round yourself. It takes over again next round if it can.")
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

    private fun debug(msg: String) {
        if (debugLog.value) LayBaltQOL.logger.info("[AutoExperiment] $msg")
    }

    // ------------------------------------------------------------------ menu adapter

    private val TERRACOTTA_COLORS: Map<Item, DyeColor> = DyeColor.entries.associateBy { Items.DYED_TERRACOTTA.pick(it) }

    /** The live menu as an [ExperimentBoard]; created per update, reads the slots on demand. */
    private class MenuBoard(private val menu: AbstractContainerMenu): ExperimentBoard {
        override val status: String = name(STATUS_SLOT)

        private fun stack(slot: Int): ItemStack = menu.slots.getOrNull(slot)?.item ?: ItemStack.EMPTY

        override fun kind(slot: Int): ItemKind {
            val stack = stack(slot)
            if (stack.isEmpty) return ItemKind.EMPTY
            val item = stack.item as? BlockItem ?: return ItemKind.OTHER
            return when (item.block) {
                is StainedGlassBlock -> ItemKind.GLASS
                is StainedGlassPaneBlock -> ItemKind.PANE
                else -> if (item in TERRACOTTA_COLORS) ItemKind.TERRACOTTA else ItemKind.OTHER
            }
        }

        override fun color(slot: Int): Int {
            val item = stack(slot).item as? BlockItem ?: return - 1
            return when (val block = item.block) {
                is StainedGlassBlock -> block.color.id
                is StainedGlassPaneBlock -> block.color.id
                else -> TERRACOTTA_COLORS[item]?.id ?: - 1
            }
        }

        override fun isLit(slot: Int): Boolean = stack(slot).let { ! it.isEmpty && it.hasFoil() }

        override fun name(slot: Int): String = stack(slot).let { if (it.isEmpty) "" else it.hoverName.string.removeFormatting().trim() }

        override fun count(slot: Int): Int = stack(slot).count

        override fun cardKey(slot: Int): Any? = stack(slot).takeUnless { it.isEmpty }?.let { CardKey(it.copy()) }
    }

    /** Superpairs card identity: equal when [ItemStack.matches] (item, count and components). */
    private class CardKey(val stack: ItemStack) {
        override fun equals(other: Any?) = other is CardKey && ItemStack.matches(stack, other.stack)
        override fun hashCode() = ItemStack.hashItemAndComponents(stack) * 31 + stack.count
    }
}

//#endif
