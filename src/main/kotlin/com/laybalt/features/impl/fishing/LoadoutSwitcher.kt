package com.laybalt.features.impl.fishing

//#if CHEAT

import com.laybalt.config.PogObject
import com.laybalt.event.EventBus
import com.laybalt.event.impl.ChatMessageEvent
import com.laybalt.event.impl.ContainerEvent
import com.laybalt.event.impl.ContainerFullyOpenedEvent
import com.laybalt.event.impl.TickEvent
import com.laybalt.event.impl.WorldChangeEvent
import com.laybalt.features.Shortcuts
import com.laybalt.init.types.ISelfInit
import com.laybalt.utils.ChatUtils
import com.laybalt.utils.ChatUtils.removeFormatting
import com.laybalt.utils.ChatUtils.unformattedText
import com.laybalt.utils.GuiUtils
import com.laybalt.utils.items.ItemUtils.lore
import com.laybalt.utils.items.ItemUtils.skyblockId
import gg.essential.universal.UMinecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.item.ItemStack

/**
 * Equips a SkyBlock loadout through the `/loadout` menu: opens it, turns to the right page, clicks the loadout, waits
 * for Hypixel's "You equipped <name>!" and closes the menu, with human delays from your recordings. Also remembers the
 * loadout names (shown in the rules window) and which loadout you equipped last, every time the menu is open.
 */
object LoadoutSwitcher: ISelfInit, Shortcuts {
    class LoadoutInfo(var name: String = "", var itemId: String = "")

    /** Loadout number (1-[KillRules.MAX_LOADOUT]) -> what the menu showed for it the last time it was open. */
    val known = PogObject("fishingLoadouts", linkedMapOf<Int, LoadoutInfo>())

    /** The loadout you (or the switcher) equipped last in this session, 0 = unknown; set once Hypixel confirms it. */
    var lastEquipped = 0
        private set

    /** Pages the menu had the last time it was open in this session ("(1/3) Loadouts" = 3), 0 = not seen yet. */
    var menuPages = 0
        private set

    val menuTitle = Regex("""^\((\d+)/(\d+)\) Loadouts?$""")
    val slots = listOf(14, 15, 16, 23, 24, 25, 32, 33, 34, 41, 42, 43)
    private const val NEXT_PAGE = 44
    private const val PREVIOUS_PAGE = 17
    private const val OPEN_TIMEOUT = 4000L
    private const val STEP_TIMEOUT = 3000L

    /** Hypixel's reply to a loadout click, about 200 ms after it (kept free of game state for unit tests). */
    object Confirmation {
        private val line = Regex("""^You equipped (.+)!$""")

        /** True when chat [text] confirms equipping the loadout named [name] (any loadout for an empty name). */
        fun confirms(text: String, name: String): Boolean {
            val equipped = line.find(text.trim())?.groupValues?.get(1)?.trim() ?: return false
            return name.isEmpty() || equipped.equals(name, ignoreCase = true)
        }
    }

    private const val CONFIRM_MS = 1500L
    /** A confirmation that comes later than [CONFIRM_MS] (a lag spike) still tells which loadout is on. */
    private const val LATE_CONFIRM_MS = 8000L

    /** A loadout clicked in the menu, waiting for its [Confirmation]. */
    private class Click(val index: Int, val name: String, val at: Long)

    /**
     * The loadout clicked last (by you, or by the switcher while it is [busy]); it becomes [lastEquipped] when its
     * [Confirmation] comes within [CONFIRM_MS], and is dropped then.
     */
    private var lastClick: Click? = null

    private enum class Step { IDLE, OPENING, PAGING, CLICK_WAIT, CLOSE_WAIT }

    private var step = Step.IDLE
    private var target = 0
    private var stepAt = 0L
    private var nextAt = 0L
    private var menuPage = 0
    private var menuReady = false
    private var callback: ((Boolean) -> Unit)? = null
    /**
     * Menus asked for (/loadout, page clicks) and not opened yet, until [pendingUntil]. One that opens while nobody waits
     * any more (a cancelled or timed out switch) is closed, every such reply, not just the first.
     */
    private var pendingReplies = 0
    private var pendingUntil = 0L
    private const val REPLY_MS = 10_000L

    private fun expectReply() {
        pendingReplies ++
        pendingUntil = System.currentTimeMillis() + REPLY_MS
    }

    private val replyPending get() = pendingReplies > 0 && System.currentTimeMillis() < pendingUntil

    val busy get() = step != Step.IDLE

    /**
     * When the last switch's menu closed (at your click-to-close speed), 0 before it did: the callback comes once Hypixel
     * confirmed the click, which may be after the close, and what follows a close is timed from the close.
     */
    var closedAt = 0L
        private set
    /** Ticks of the current switch so far, and the one its menu closed in (-1: not yet). */
    private var switchTicks = 0
    private var closedTick = - 1

    /**
     * How many of its ticks ago the last switch's menu closed: 0 in this very tick (a callback now came with the close,
     * the confirmation was there before it), -1 when it never closed.
     */
    fun closedTicksAgo() = if (closedTick < 0) - 1 else switchTicks - closedTick

    /**
     * Starts equipping loadout [index] (1-[KillRules.MAX_LOADOUT]); [done] gets true once Hypixel confirmed the click
     * (at once for a loadout that is already on) and the menu is closed.
     */
    fun equip(index: Int, done: (Boolean) -> Unit) {
        if (busy || index !in 1 .. KillRules.MAX_LOADOUT) return done(false)
        target = index
        callback = done
        menuReady = false
        nextAt = 0L
        closedAt = 0L
        switchTicks = 0
        closedTick = - 1
        step = Step.OPENING
        stepAt = System.currentTimeMillis()
        // a menu already on its way (from a switch given up on) is waited for instead of asked for again
        if (! inMenu() && ! replyPending) {
            expectReply()
            ChatUtils.sendCommand("loadout", 0L)
        }
    }

    /** Stops without calling back (the caller is resetting) and closes the menu if it is open. */
    fun cancel() {
        if (! busy) return
        giveUp()
        finish(false, notify = false)
    }

    /** Closes the menu if it is open (a reply still on its way is closed when it arrives, see [pendingReplies]). */
    private fun giveUp() {
        if (inMenu()) mc.player?.closeContainer()
    }

    private fun finish(success: Boolean, notify: Boolean = true) {
        step = Step.IDLE
        nextAt = 0L
        val cb = callback
        callback = null
        if (notify) cb?.invoke(success)
    }

    private fun screen() = UMinecraft.currentScreenObj as? AbstractContainerScreen<*>
    private fun inMenu() = screen()?.title?.unformattedText?.let(menuTitle::matches) == true

    /** The loadout menu is the screen open now. */
    fun menuOpen() = inMenu()

    /** A menu nobody waits for any more (a reply to a switch given up on) is closed then (0: none), your reaction to it opening. */
    private var lateCloseAt = 0L

    override fun init() {
        EventBus.register<ContainerFullyOpenedEvent> {
            val match = menuTitle.find(event.title.unformattedText) ?: return@register
            val page = match.groupValues[1].toIntOrNull() ?: return@register
            val pages = match.groupValues[2].toIntOrNull() ?: return@register
            remember(page, event.items)
            menuPage = page
            menuPages = pages
            menuReady = true
            val wasPending = replyPending
            if (pendingReplies > 0) pendingReplies --
            // the reply to a /loadout (or page click) that was given up on: nobody is waiting for this menu. It is closed
            // like you closed a menu you had no use for, a reaction after it opened, never in the tick it came
            if (! busy && wasPending) lateCloseAt = LateMenu.closeAt(System.currentTimeMillis()) { Humanize.ms(BehaviorProfile.current.menuOpenToClickMs, 260, 480) }
        }

        EventBus.register<ContainerEvent.SlotClick> {
            // the switcher's own click is recorded where it makes it
            if (busy || event.button != 0 || event.clickType != ContainerInput.PICKUP) return@register
            val match = menuTitle.find(event.screen.title.unformattedText) ?: return@register
            val i = slots.indexOf(event.slotId).takeIf { it >= 0 } ?: return@register
            val stack = event.screen.menu.slots.getOrNull(event.slotId)?.item ?: return@register
            if (! equipable(stack)) return@register
            val index = (match.groupValues[1].toIntOrNull() ?: 1).minus(1) * 12 + i + 1
            lastClick = Click(index, nameOf(stack), System.currentTimeMillis())
        }

        EventBus.register<ChatMessageEvent> {
            val click = lastClick ?: return@register
            if (System.currentTimeMillis() - click.at > LATE_CONFIRM_MS || ! Confirmation.confirms(event.unformattedText, click.name)) return@register
            lastEquipped = click.index
            lastClick = null
        }

        EventBus.register<TickEvent.Start> {
            if (busy) tick()
            lateTick()
        }

        EventBus.register<WorldChangeEvent> {
            lastEquipped = 0
            lastClick = null
            lateCloseAt = 0L
            // the old menu is gone with the world; whoever asked resets on the world change itself
            pendingReplies = 0
            if (busy) finish(false, notify = false)
        }
    }

    /**
     * The late menu nobody waits for is closed once its time came ([LateMenu.closes]): not when you closed it or another
     * screen took its place, nor once a new switch uses it ([equip] takes a menu that is open).
     */
    private fun lateTick() {
        when (LateMenu.closes(lateCloseAt, System.currentTimeMillis(), busy, inMenu())) {
            LateMenu.Step.WAIT -> Unit
            LateMenu.Step.CLOSE -> {
                lateCloseAt = 0L
                mc.player?.closeContainer()
            }
            LateMenu.Step.DROP -> lateCloseAt = 0L
        }
    }

    /** When a late loadout menu is closed: a reaction after it opened, never in its own tick. No game state, so it is unit tested. */
    internal object LateMenu {
        enum class Step { WAIT, CLOSE, DROP }

        /** A late menu that opened at [now] is closed your [reaction] to a menu opening later (yours to a click in it: never within a tick). */
        fun closeAt(now: Long, reaction: () -> Long) = TimingModel.dueAt(now, reaction())

        /**
         * At [now], with a late menu due to close at [at] (0: none): wait for it, close it, or drop it (a switch is [busy]
         * with it again, or it is no longer open: [open]).
         */
        fun closes(at: Long, now: Long, busy: Boolean, open: Boolean) = when {
            at == 0L -> Step.WAIT
            busy || ! open -> Step.DROP
            now < at -> Step.WAIT
            else -> Step.CLOSE
        }
    }

    private fun tick() {
        val now = System.currentTimeMillis()
        val profile = BehaviorProfile.current
        switchTicks ++
        when (step) {
            Step.IDLE -> Unit

            Step.OPENING, Step.PAGING -> {
                val timeout = if (step == Step.OPENING) OPEN_TIMEOUT else STEP_TIMEOUT
                // waiting for a reply asked for earlier: up to its own deadline
                if (now - stepAt > timeout && ! (replyPending && ! inMenu())) return fail("the loadout menu did not open")
                if (! inMenu() || ! menuReady) return
                val wantedPage = (target - 1) / 12 + 1
                // a "(1/2) Loadouts" menu has no loadouts 25-36
                if (wantedPage > menuPages) return fail("the menu has no page $wantedPage")
                if (menuPage != wantedPage) {
                    if (nextAt == 0L) nextAt = TimingModel.dueAt(now, Humanize.ms(profile.menuOpenToClickMs, 260, 480))
                    if (now < nextAt) return
                    nextAt = 0L
                    menuReady = false
                    step = Step.PAGING
                    stepAt = now
                    expectReply()
                    GuiUtils.clickSlot(if (wantedPage > menuPage) NEXT_PAGE else PREVIOUS_PAGE, GuiUtils.ButtonType.LEFT)
                    return
                }
                step = Step.CLICK_WAIT
                stepAt = now
                nextAt = TimingModel.dueAt(now, Humanize.ms(profile.menuOpenToClickMs, 260, 480))
            }

            Step.CLICK_WAIT -> {
                if (! inMenu()) return fail("the loadout menu was closed")
                if (now < nextAt) return
                val slot = slots[(target - 1) % 12]
                val stack = screen()?.menu?.slots?.getOrNull(slot)?.item
                if (stack == null || stack.isEmpty) return fail("loadout $target is empty")
                // An already equipped loadout has no "Left-click to equip!" line: nothing to click or to wait for.
                if (equipable(stack)) {
                    lastClick = Click(target, nameOf(stack), now)
                    GuiUtils.clickSlot(slot, GuiUtils.ButtonType.LEFT)
                }
                else {
                    lastClick = null
                    lastEquipped = target
                }
                step = Step.CLOSE_WAIT
                stepAt = now
                nextAt = TimingModel.dueAt(now, Humanize.ms(profile.menuClickToCloseMs, 180, 380))
            }

            Step.CLOSE_WAIT -> {
                // closed at your click-to-close speed, whether or not the confirmation is there yet
                if (now >= nextAt && closedAt == 0L) {
                    if (inMenu()) mc.player?.closeContainer()
                    closedAt = now
                    closedTick = switchTicks
                }
                val click = lastClick
                // CLICK_WAIT set this click (or cleared it for a loadout already on)
                if (click != null) {
                    if (now - click.at <= CONFIRM_MS) return
                    // the click may still have gone through: which loadout is on is unknown now (the click is kept, so a
                    // late confirmation still sets it)
                    lastEquipped = 0
                    return fail("no confirmation")
                }
                if (now >= nextAt) finish(true)
            }
        }
    }

    private fun fail(reason: String) {
        ChatUtils.modMessage("§cAuto Mob Killer: could not equip loadout $target ($reason).")
        giveUp()
        finish(false)
    }

    private fun equipable(stack: ItemStack) = stack.lore.any { it.removeFormatting().contains("Left-click to equip!") }

    private fun nameOf(stack: ItemStack) = stack.hoverName.unformattedText.trim()

    private fun remember(page: Int, items: Map<Int, ItemStack>) {
        var changed = false
        val map = known.get()
        slots.forEachIndexed { i, slot ->
            val stack = items[slot] ?: return@forEachIndexed
            if (stack.isEmpty) return@forEachIndexed
            val index = (page - 1) * 12 + i + 1
            val name = nameOf(stack)
            val id = stack.skyblockId
            val old = map[index]
            if (old?.name == name && old.itemId == id) return@forEachIndexed
            map[index] = LoadoutInfo(name, id)
            changed = true
        }
        if (changed) known.save()
    }
}

//#endif
