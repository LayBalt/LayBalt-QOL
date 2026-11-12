package com.laybalt.features.impl.fishing

//#if CHEAT

import com.laybalt.LayBaltQOL
import com.laybalt.config.types.KeybindSetting
import com.laybalt.config.types.SliderSetting
import com.laybalt.config.types.ToggleSetting
import com.laybalt.event.EventBus
import com.laybalt.event.impl.ChatMessageEvent
import com.laybalt.event.impl.KeyboardEvent
import com.laybalt.event.impl.MouseClickEvent
import com.laybalt.event.impl.PlayerInteractEvent
import com.laybalt.event.impl.TickEvent
import com.laybalt.event.impl.WorldChangeEvent
import com.laybalt.event.priority.EventPriority
import com.laybalt.features.Feature
import com.laybalt.features.impl.fishing.HumanAim.Owner
import com.laybalt.features.impl.fishing.MotorModel.View
import com.laybalt.features.impl.fishing.TimingModel.Action
import com.laybalt.features.impl.fishing.TimingModel.StreakState
import com.laybalt.ui.notification.NotificationManager
import com.laybalt.utils.ChatUtils
import com.laybalt.utils.ChatUtils.unformattedText
import com.laybalt.utils.PlayerUtils
import com.laybalt.utils.items.ItemUtils.skyblockId
import com.mojang.blaze3d.platform.InputConstants
import gg.essential.universal.UMinecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.BlockPos
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.projectile.FishingHook
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.EntityHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.random.Random

/**
 * Re-implementation of the old LayBalt "Auto Fishing" as a tick-driven state machine.
 *
 * Fixes of the legacy version: only reacts to the bite marker of the player's own bobber, never casts twice per bite,
 * pauses (instead of turning off) while a menu is open, never touches the mouse sensitivity (Lock Mouse cancels
 * look input through [com.laybalt.mixin.MixinMouseHandlerAutoFishing]), never right-clicks without a rod in hand, never
 * starts by itself after a restart and turns itself off on death, warp and world change.
 *
 * With your recordings its waits are drawn like yours ([TimingModel]): bite reactions, recasts (slow ones in streaks)
 * and the cast after a fight, which follows the rod and the view coming back. While it waits for a bite your hand rests
 * on the mouse through the one view driver, [HumanAim.shared] (idle moves, [Owner.IDLE]); a bobber that came to rest out
 * of water and lava is reeled in and cast again from your fishing view ([HumanAim.home]), and a cast from a view whose
 * bobber would land elsewhere ([BobberFlight]) turns towards that view first. Look Around looks around and back the way
 * your recordings show ([GlancePolicy]); no cast goes out while such a look keeps the view off the water. No rod click
 * goes out while it would interact with something first (an entity that is not one of your creatures under the
 * crosshair, a door, a lever, a chest): it waits for the way and goes a reaction after it came clear ([RodWay]).
 */
object AutoFishing: Feature("Automatically casts and reels in your fishing rod.") {
    private val reelDelay by SliderSetting("Reel Delay", 250, 150, 600, 25, "ms")
        .withDescription("Delay between the bite (the \"!!!\" above your bobber) and the reel-in.")
    private val castDelay by SliderSetting("Cast Delay", 250, 150, 600, 25, "ms")
        .withDescription("Delay between the reel-in and the next cast.")
    private val delayVariance by SliderSetting("Delay Variance", 50, 0, 300, 10, "ms")
        .withDescription("Random extra delay (0 up to this value) added to both the reel and the cast delay.")
    private val recastTimeout by SliderSetting("Recast Timeout", 60, 15, 180, 5, "s")
        .withDescription("Reels in and casts again when nothing has bitten for this long (stuck or badly placed bobber).")
    private val onlyOwnBobber by ToggleSetting("Only My Bobber", true)
        .withDescription("Only reacts to the bite marker above your own bobber. Off = any \"!!!\" within 15 blocks, which also reacts to other players' bites.")
    private val stopOnGui by ToggleSetting("Pause In Menus", true)
        .withDescription("Pauses while any screen (chat, inventory, menus) is open and resumes when it closes. Off = keeps fishing with chat and other screens open, never with a container (a chest, the SkyBlock menu, your inventory), where nobody can use a rod.")
    private val shakeHead by ToggleSetting("Shake Head", true)
        .withDescription("Moves your view a little now and then while you wait for a bite, like a hand resting on the mouse: a nudge of a few mouse steps every few seconds, and about every 40 seconds, when the view has drifted, a small re-aim back to where you fish. Never while a menu is open or the Fishing Recorder records.")
    private val lookAround by ToggleSetting("Look Around", true)
        .withDescription("Now and then looks around and back the way your recordings show you do. Nothing happens until your recordings have taught it. Never while a fight needs your aim, in menus or before a cast off the water.")
    private val lockMouse by ToggleSetting("Lock Mouse", false)
        .withDescription("Ignores mouse look input while Auto Fishing is on, so you cannot accidentally turn away from the water. Your sensitivity setting is not changed.")
    private val recordedTimings by ToggleSetting("Use Recorded Timings", true)
        .withDescription("Once the Fishing Recorder has enough of your own bites and recasts, the reel and cast delays are your recorded ones instead of the sliders above.")
    private val toggleKey by KeybindSetting("Toggle Key")
        .withDescription("Turns Auto Fishing on or off. Works in game only (not while a screen is open). Unbound by default.")

    /** Set by other features to hold Auto Fishing; it resumes by casting again. */
    @JvmField var paused = false

    /** When Auto Fishing last used the rod: its clicks are automatic, and its casts and reels ([AutoMobKiller.rodUseIsMods]). */
    @JvmField var lastRodUseAt = 0L

    /** When Auto Fishing last cast, or clicked again for a reel the server may have done already (a reel click is no cast): a press of yours before that bobber showed up reels it in ([AutoMobKiller.UseOwner]). */
    var lastCastClickAt = 0L
        private set

    /** When [SeaCreatureTracker] last reported the catch message of one of your sea creatures. */
    @JvmField var lastCatchAt = 0L

    /** When Auto Fishing last recast right after reeling in a catch (that bobber often kills the fresh sea creature). */
    var lastCatchRecastAt = 0L
        private set

    /** When the recast after a catch is due (wall clock ms, from its ticks), 0 when none is pending (one held back still is). */
    val recastDueAt: Long get() = if (enabled && phase == Phase.CASTING && (recast.pending || recastOwed)) recastDue else 0L

    internal enum class Phase { IDLE, WAITING_BITE, REELING, CASTING }

    private var phase = Phase.IDLE
    private var nextActionAt = 0L
    private var castAt = 0L
    /** How long this cast waits for a bite before it is reeled in and cast again (Recast Timeout, drawn per cast). */
    private var biteTimeoutMs = Double.POSITIVE_INFINITY
    private var reeledAt = 0L
    private var reelRetries = 0
    /** Entity id of the bobber reeled in last: while the client still shows it, it is not the new cast. */
    private var reeledHookId = - 1
    private val recast = RecastTimer()
    private var recastDue = 0L
    /** When a fishing rod came into your main hand (the cast after a weapon fight follows it like yours did). */
    private var rodInHandAt = 0L
    private var hadRod = false
    /** The player tick of Auto Fishing's last rod click: Auto Mob Killer never clicks or swaps in the same tick. */
    private val rodUseTick = TickMark()
    /**
     * A reel, cast or recast that came due while a move of the view held it (a look around off the water, a turn home) or
     * a screen paused Auto Fishing: it goes out a wait of yours after that move came to rest or the screen closed ([HeldUse]).
     */
    private val moveHeld = HeldUse()
    private val screenHeld = HeldUse()
    /** The recast after a catch came due while held: it goes out once [moveHeld] and [screenHeld] let it, its ticks no longer count. */
    private var recastOwed = false
    /** A screen paused Auto Fishing at the last tick; the first tick after it closed. */
    private var screenOpen = false
    private var screenClosedAt = Long.MIN_VALUE / 2
    /**
     * A screen of yours (any that keeps Auto Fishing off your view, not the killer's loadout menu) was open at the last
     * tick; once one closed, Auto Fishing's own moves of the view (an idle re-aim, a turn home for its cast, a look around)
     * wait your reaction to the close, as the killer's do ([AutoMobKiller.ScreenGate.Kind.MOVE]).
     */
    private var viewScreenOpen = false
    private val screenGate = AutoMobKiller.ScreenGate { TimingModel.draw(it.action, emptyList(), null, Random.Default) }
    /** Your hand's switch from the killer's last left click to the rod's right click ([CrossGap]); the tick a rod click due waited for it. */
    private val crossGap = CrossGap()
    private var handWaitTick = Int.MIN_VALUE / 2
    /**
     * A rod click due waits while it would interact with something first ([RodWay]: an entity that is no creature of yours
     * under the crosshair, a door or a lever), and goes a reaction after the way came clear ([AutoMobKiller.ClearWay]).
     */
    private val rodWay = AutoMobKiller.ClearWay()
    /** The tick of Auto Fishing's last rod click through the use key: the game's handling of it is checked once more ([guardRod]). */
    private val rodClickTick = TickMark()
    /** The rod that click was meant for: its hotbar slot, SkyBlock id and item ([guardRod]). */
    private var clickSlot = - 1
    private var clickId = ""
    private var clickItem: Item? = null
    /** That click's reel, cast or recast moves Auto Fishing on only once the game let it go out ([RodClick]). */
    private val rodClick = RodClick()
    /** A rod click held for the way this long is told in chat, and so is its end ([WayNotice]). */
    private val wayNotice = WayNotice()
    /** The cast due waits for another player's creature or a player on its bobber's flight, within bounds ([CastWay]). */
    private val castWay = CastWay()
    /** When the bite being reeled showed; a bite whose reel was held past your latest reel is lost and not taken again ([staleBite]). */
    private var biteAt = 0L
    private var staleBite = false

    /** Your slow recasts after a catch and your slow casts after taking the rod come in streaks. */
    private val catchRecastStreak = StreakState()
    private val rodToCastStreak = StreakState()

    /** Something holds Auto Fishing (a fight, another feature), since [heldSince]. */
    private var held = false
    private var heldSince = 0L
    /** The hold just ended: the next cast follows the rod and the view coming back ([CastClock]). */
    private var castAfterHold = false
    /** The last client tick the view moved by more than [MOVE_DEG], and the view at the tick before. */
    private var viewMovedAt = 0L
    private var lastView: View? = null

    private val aim get() = HumanAim.shared
    /** When the next idle re-aim is due, -1 before it is drawn. */
    private var reaimAt = - 1L
    /** An idle move or a look around that may not go on comes to rest once your hand reacted ([MoveHalt]). */
    private val idleHalt = MoveHalt(Owner.IDLE)
    private val glanceHalt = MoveHalt(Owner.GLANCE)
    /** Auto Fishing's own move still turning when it was turned off ([OffHalt], driven while it is off). */
    private val offHalt = OffHalt()
    /** The stop under way ends Auto Fishing's own moves at once: a world change or your death. */
    private var stopAtOnce = false

    /** Auto Fishing's own cast is out: once its bobber came to rest it must be in water or lava. */
    private var checkLanding = false
    private var hookPos: Vec3? = null
    /** Casts in a row whose bobber came to rest out of water and lava; past [MAX_MISSED_CASTS] they are left alone. */
    private var missedCasts = 0
    /** The reeled bobber missed the water: the view turns back home before the next cast, from then on ([turnHomeAfterMiss])... */
    private var reaimHome = false
    private var reaimHomeAt = 0L
    /** ...and that cast (or a turn home for the recast) waits for the move: it is held from the move's start and goes out a wait of yours after its rest ([HeldUse]). */
    private var castAfterReaim = false
    /** The idle move in flight (if any) turns home for Auto Fishing's own cast or recast: a fight phase that leaves Auto Fishing to fish with no target fought does not stop it ([CastAim]). */
    private var castReaim = false
    /** The next timed cast had its bobber flight checked ([CastCheck]): after a turn home it goes out unchecked. */
    private var castChecked = false
    /** The pending recast after a catch had its bobber flight checked ([CastCheck.recastTurnsHome]), once per recast. */
    private var recastChecked = false
    /** A cast or recast is due and waits for a look around to come back ([CastCheck.glanceHolds]). */
    private var glanceReturnDue = false
    /** A cast went out from where a look around stayed away: the look comes back once your hand reacted to it ([glanceReactAt]). */
    private var glanceBackAfterCast = false
    /** No look around comes back before this: your hand's reaction to the bite or the cast that brought the cast nearer ([GlancePolicy.Inputs.returnNotBefore]). */
    private var glanceReactAt = Long.MIN_VALUE
    /** How long after its cast or reel a reeled bobber still out is reeled again ([Action.REEL_RETRY], drawn per reel and per retry). */
    private var reelRetryMs = 0L
    /** Your reaction to this cast's bobber lying out of water and lava ([Action.LANDING_REEL], drawn per cast), and since when it lies still there (-1: it does not). */
    private var landingReactMs = 0L
    private var lyingSince = - 1L

    private const val CAST_GRACE = 1500L
    private const val MAX_REEL_RETRIES = 3
    private const val BITE_IGNORE_AFTER_CAST = 300L
    /** The rod came back into your hand this recently: the cast follows it after your rod-to-cast time. */
    private const val ROD_BACK_MS = 3000L
    /** A bobber in water or lava (or hooked) this long after the cast has landed... */
    private const val LANDING_CHECK_MS = 2000L
    /** ...and one still flying this long after it is left alone. */
    private const val LANDING_GIVE_UP_MS = 5000L
    /** A bobber that moved less than this (blocks) in a tick has come to rest. */
    private const val HOOK_REST = 0.05
    private const val MAX_MISSED_CASTS = 2
    /** A view change above this in one tick (15 deg/s, where your recorded movements start) is a movement, not a rest. */
    private const val MOVE_DEG = 0.75
    /** A learned stay rate needs this many slow waits followed by another (as [TimingModel] learns its own). */
    private const val STREAK_PAIRS = 8
    /** The view turns back home after a missed cast when it is this far from it. */
    private const val MISS_REAIM_DEG = 1.0
    /** No look around within this long after your own mouse turned the view. */
    private const val GLANCE_MOUSE_MS = 2000L
    /** After a fight, the time until the next cast counts as after the kill this long. */
    private const val AFTER_KILL_MS = 3000L

    private val deathMessages = listOf(
        "fell into the void", "was thrown into the void by", "was slain by", "burned to death", "fell to death",
        "fell to their death with help from", "suffocated.", "drowned.", "was pricked to death by a cactus.", "died.", "were killed by"
    )

    /** Read by [com.laybalt.mixin.MixinMouseHandlerAutoFishing]: true while mouse look input must be ignored. */
    @JvmStatic
    fun shouldLockMouse() = enabled && lockMouse.value && mc.player != null && ! FishingRecorder.holdsAutoFishing() && ! AutoMobKiller.userFights()

    /** Turns Auto Fishing off with [reason] in chat (Auto Mob Killer's "Stop" rule). */
    fun stop(reason: String) = disable(reason)

    /** Auto Fishing used the rod in this tick: a click or slot swap of Auto Mob Killer now would change what it does. */
    fun usedRodThisTick() = mc.player?.tickCount?.let(rodUseTick::at) == true

    /**
     * A rod click of Auto Fishing's is due and waits only for your hand to come off the other button ([CrossGap]), in this
     * tick or the last: Auto Mob Killer clicks no more meanwhile, so it is not put off again and again. Never by a tick
     * of the player before a world change (the new one counts from 0 again).
     */
    fun waitsForHand(): Boolean {
        val tick = mc.player?.tickCount ?: return false
        return enabled && tick - handWaitTick in 0 .. 1
    }

    /**
     * [stack] is a rod you fish with: a vanilla fishing rod that is no rod-type weapon (Flaming Flay, Soul Whip), no
     * grappling hook and no item your recordings show you fighting with ([WeaponPlanner.isFishingRod]). A rod your
     * recordings never saw still fishes.
     */
    fun isFishingRod(stack: ItemStack) = stack.item == Items.FISHING_ROD && WeaponPlanner.isFishingRod(BehaviorProfile.current, stack.skyblockId, true)

    override fun init() {
        // The toggle key must work while the feature is off, so it is registered globally (not feature-scoped).
        EventBus.register<KeyboardEvent.KeyPressed> {
            if (UMinecraft.currentScreenObj != null || event.action != InputConstants.PRESS) return@register
            if (! toggleKey.matches(event.keyEvent.key, mouse = false)) return@register
            toggle()
            event.isCanceled = true
        }

        EventBus.register<MouseClickEvent> {
            if (UMinecraft.currentScreenObj != null || event.action != InputConstants.PRESS) return@register
            if (! toggleKey.matches(event.button, mouse = true)) return@register
            toggle()
            event.isCanceled = true
        }

        // the one view driver samples its moves every frame and tick; Shake Head turns its idle rest nudges on
        HumanAim.install()
        HumanAim.idleRest = { idleRestAllowed() }
        // a move of Auto Fishing's own still turning when it was turned off comes to rest a moment later: registered
        // globally, its own listeners are off by then
        EventBus.register<TickEvent.Start> { if (! enabled) offTick() }

        register<TickEvent.Start> { onTick() }

        // The game picks the crosshair target again right before it handles the rod click, after the hotbar keys and the
        // offhand swap of that tick: one that would interact with something first (an entity that is no creature of yours,
        // a door, a lever) or use another item than the rod it was meant for is cancelled whole, nothing is sent, and the
        // next one waits for the way ([rodWay]). EventBus dispatches by exact class, so each event gets the guard; the
        // click's state moves on last, only when nothing cancelled it ([RodClick]).
        register<PlayerInteractEvent.RIGHT_CLICK.ENTITY>(EventPriority.HIGHEST, receiveCancelled = true) { guardRod(event, RodWay.blocks(true, ownCreature(event.entity), false)) }
        register<PlayerInteractEvent.RIGHT_CLICK.BLOCK>(EventPriority.HIGHEST, receiveCancelled = true) { guardRod(event, usableBlock(event.pos)) }
        register<PlayerInteractEvent.RIGHT_CLICK.AIR>(EventPriority.HIGHEST, receiveCancelled = true) { guardRod(event, false) }
        register<PlayerInteractEvent.RIGHT_CLICK.ENTITY>(EventPriority.LOWEST, receiveCancelled = true) { settleRod(event) }
        register<PlayerInteractEvent.RIGHT_CLICK.BLOCK>(EventPriority.LOWEST, receiveCancelled = true) { settleRod(event) }
        register<PlayerInteractEvent.RIGHT_CLICK.AIR>(EventPriority.LOWEST, receiveCancelled = true) { settleRod(event) }

        register<ChatMessageEvent> {
            val text = event.unformattedText
            if (text.trim().startsWith("Sending to server")) return@register disable("Warped")
            if (isOwnDeathMessage(text)) disable("Killed: ${text.trim()}", atOnce = true)
        }

        register<WorldChangeEvent> { disable("World changed", atOnce = true) }

        SeaCreatureTracker.onCatchMessage += { _, _ -> lastCatchAt = System.currentTimeMillis() }

        // a cast of yours (from your own press of the use key, never the mod's whatever the ping) is a fresh spot to check
        // landings from
        SeaCreatureTracker.onCast += { if (AutoMobKiller.rodUseIsYours()) missedCasts = 0 }
    }

    override fun toggle() {
        super.toggle()
        if (mc.player != null && LayBaltQOL.isLoaded) announce()
    }

    override fun onEnable() {
        // Never resume from a saved "enabled" (config load happens before any world exists) - fixes the old auto-start.
        if (mc.player == null || mc.level == null) {
            enabled = false
            if (LayBaltQOL.isLoaded) NotificationManager.push(LayBaltQOL.MOD_NAME, "Join a world before enabling $name.")
            return
        }
        // a move still turning since the last stop is this run's own again: an idle one goes on, a look's runs out
        offHalt.clear()
        resetState()
        super.onEnable()
    }

    override fun onDisable() {
        super.onDisable()
        resetState()
        // your hand goes on a moment after Auto Fishing stopped (your key, a rare catch, a warp) and brings its move in
        // flight to rest then ([offTick]); at once at a world change, your death, or where nothing may turn the view
        val p = mc.player
        val atOnce = stopAtOnce || p == null || frozen(p)
        offHalt.arm(aim, System.currentTimeMillis(), atOnce) { handReaction() }
    }

    private fun announce() = ChatUtils.modMessage("§7AutoFishing is now ${if (enabled) "§aon" else "§coff"}")

    /** Turns Auto Fishing off with [reason]; [atOnce]: its moves end with it (a world change, your death), not after your hand's reaction. */
    private fun disable(reason: String, atOnce: Boolean = false) {
        if (! enabled) return
        stopAtOnce = atOnce
        try {
            super.toggle()
        }
        finally {
            stopAtOnce = false
        }
        ChatUtils.modMessage("§7AutoFishing is now §coff §8[§c$reason§8]")
    }

    /** While Auto Fishing is off: its move still turning since it stopped comes to rest at your hand's reaction ([OffHalt]). */
    private fun offTick() {
        if (! offHalt.pending) return
        val p = mc.player
        offHalt.tick(aim, System.currentTimeMillis(), p == null || frozen(p))
    }

    /** Nothing may turn the view: a screen, the Fishing Recorder (its lines would be learned as your hand), your death. */
    private fun frozen(p: LocalPlayer) = UMinecraft.currentScreenObj != null || FishingRecorder.enabled || FishingRecorder.holdsAutoFishing() || p.isDeadOrDying

    /**
     * Your hand's reaction to something that ends a move of Auto Fishing's own (a fight or a hold taking over, the killer's
     * /loadout, Auto Fishing stopping): your reaction to a new creature, as the killer's moves take it then.
     */
    private fun handReaction() = TimingModel.draw(Action.SPAWN_REACTION, BehaviorProfile.current.spawnReactionMs, null, Random.Default)

    private fun resetState() {
        phase = Phase.IDLE
        nextActionAt = 0L
        castAt = 0L
        reeledAt = 0L
        reelRetries = 0
        reeledHookId = - 1
        recast.cancel()
        // a rod already in hand did not just come back
        hadRod = mc.player?.mainHandItem?.let(::isFishingRod) == true
        held = false
        castAfterHold = false
        lastView = null
        checkLanding = false
        hookPos = null
        missedCasts = 0
        reaimHome = false
        castAfterReaim = false
        castReaim = false
        castChecked = false
        recastChecked = false
        catchRecastStreak.reset()
        rodToCastStreak.reset()
        // a move of its own in flight is not frozen here: the stop decides how it ends ([onDisable])
        idleHalt.clear()
        glanceHalt.clear()
        reaimAt = - 1L
        glanceReturnDue = false
        glanceBackAfterCast = false
        glanceReactAt = Long.MIN_VALUE
        lyingSince = - 1L
        clearHeld()
        screenOpen = false
        viewScreenOpen = false
        rodWay.clear()
        rodClick.clear()
        wayNotice.clear()
        castWay.clear()
        staleBite = false
        // the player after a world change counts its ticks from 0 again: no tick of the old one is this one's
        rodUseTick.clear()
        rodClickTick.clear()
        handWaitTick = Int.MIN_VALUE / 2
        HumanAim.glances.drop()
    }

    /** Nothing held back is owed any more: the reel, cast or recast it was went out or is no longer due. */
    private fun clearHeld() {
        moveHeld.clear()
        screenHeld.clear()
        recastOwed = false
    }

    private fun onTick() {
        val p = mc.player ?: return
        val now = System.currentTimeMillis()
        // a click the game never handled in its tick never went out: nothing of it is done
        rodClick.expire(p.tickCount)
        watchView(p, now)
        watchScreenClose(now)
        glanceReturnDue = false
        // the look that stayed away came back, was dropped or another move took the view
        val glances = HumanAim.glances
        if (glances.state != GlancePolicy.State.REST || ! glances.out) glanceBackAfterCast = false
        fish(p, now)
        idleTick(p, now)
        glanceTick(p, now)
    }

    /**
     * Your looks around and back may move the view now (looks/SPEC §3.4.4): Look Around is on and your recordings taught
     * them, nothing holds Auto Fishing but a fight, the Fishing Recorder is off (its lines would be learned as your hand),
     * you are alive, no screen or loadout switch is open, your mouse has been still for 2 s and no fight is yours.
     */
    fun glancesAllowed(p: LocalPlayer, now: Long) = enabled && lookAround.value && BehaviorProfile.current.glances.learned && ! FishingRecorder.enabled &&
        ! FishingRecorder.holdsAutoFishing() && ! paused && ! p.isDeadOrDying && UMinecraft.currentScreenObj == null && ! LoadoutSwitcher.busy &&
        ! AutoMobKiller.userMouseWithin(now, GLANCE_MOUSE_MS) && ! AutoMobKiller.userFights()

    /**
     * A cast or recast waits for a look around to come back, or went out from where one stayed away: the look comes back
     * now, or once your hand reacted to that cast ([glanceReturnNotBefore]).
     */
    fun glanceReturnDue() = enabled && (glanceReturnDue || glanceBackAfterCast)

    /** No look around comes back before this: your hand's reaction to a bite or a cast that brought the cast nearer. */
    fun glanceReturnNotBefore() = if (enabled) glanceReactAt else Long.MIN_VALUE

    /** How long the view is free for a look around as far as the rod goes ([RodClock.glanceWindowMs]), whoever feeds the looks. */
    fun glanceWindowMs(now: Long) = if (! enabled) Double.POSITIVE_INFINITY else RodClock.glanceWindowMs(phase, now, nextActionAt, recastDue.takeIf { recast.pending }, heldUntil(now))

    /** When a reel, cast or recast held back goes out ([HeldUse]): its drawn time, [now] while it is still held; null with none owed. */
    private fun heldUntil(now: Long): Long? {
        if (! moveHeld.owed && ! screenHeld.owed) return null
        return maxOf(moveHeld.goesAt ?: now, screenHeld.goesAt ?: now)
    }

    /** Auto Fishing reels in or is about to cast ([RodClock.busy]): a hold now (a loadout menu) would lose that bite or recast. */
    fun rodBusy() = enabled && RodClock.busy(phase)

    /**
     * Once a tick: a look around that may not go on ends and is forgotten (its move stops dead where nothing may turn the
     * view or your hand took it, else it comes to rest once your hand reacted: [MoveHalt]); between the killer's fights
     * Auto Fishing feeds them ([HumanAim.glance], in [LookPhase]'s phase): waiting for a bite (with creatures of yours
     * nobody fights too), and until the cast after a reel or a fight. The reel needs no view, the recast after it
     * does: a bite brings a look back once your hand reacted to it ([glanceReturnNotBefore]), a look comes back in time
     * for the cast that follows (the window), and a cast still due before it is back waits ([glanceReturnDue]).
     */
    private fun glanceTick(p: LocalPlayer, now: Long) {
        val glances = HumanAim.glances
        if (! glancesAllowed(p, now)) {
            // your mouse or your fight took the view: yours from now on, as for the killer's moves
            val atOnce = frozen(p) || AutoMobKiller.userMouseWithin(now, GLANCE_MOUSE_MS) || AutoMobKiller.userFights()
            glanceHalt.tick(aim, now, CastAim.halt(false, aim.moving(Owner.GLANCE), aim.stopping, atOnce)) { handReaction() }
            // the episode is over once its move is (still turning until your hand reacted, the policy waits unfed)
            if (glances.out && (! aim.moving(Owner.GLANCE) || aim.stopping)) glances.drop()
            return
        }
        glanceHalt.clear()
        if (AutoMobKiller.feedsGlances() || HumanAim.glanceTick == p.tickCount) return
        // none starts before your reaction to a screen of yours closing, as in the killer's fights
        if (! moveAfterScreen(now)) return
        HumanAim.glanceTick = p.tickCount
        val phase = LookPhase.of(phase, now - AutoMobKiller.fightEndedAt <= AFTER_KILL_MS)
        val profile = BehaviorProfile.current
        aim.glance(glances, now, phase, profile.motor, profile.glances, glanceWindowMs(now), AutoMobKiller.sinceOwnDeathMs(now), AutoMobKiller.deadSpotView(p, now),
            glanceReturnDue(), returnNotBefore = glanceReturnNotBefore())
    }

    /** Whether the view moved since the last tick: the cast after a fight waits for it to settle. */
    private fun watchView(p: LocalPlayer, now: Long) {
        val view = viewOf(p)
        lastView?.let { if (MotorModel.angle(it.yaw, it.pitch, view.yaw, view.pitch) > MOVE_DEG) viewMovedAt = now }
        lastView = view
    }

    private fun fish(p: LocalPlayer, now: Long) {
        // a rod-type weapon in hand (the Flay) is no rod: Auto Fishing never casts it, and taking the rod back from it
        // times the next cast like a rod back from any weapon
        val rod = isFishingRod(p.mainHandItem)
        if (rod && ! hadRod) rodInHandAt = now
        hadRod = rod

        val holding = paused || AutoMobKiller.holdsAutoFishing() || FishingRecorder.holdsAutoFishing() || p.isDeadOrDying
        if (holding != held) {
            held = holding
            if (holding) heldSince = now else castAfterHold = true
        }
        if (holding) {
            phase = Phase.IDLE
            recast.cancel()
            clearHeld()
            return
        }
        // the killer clicked this tick: the rod waits for the next one (a recast's ticks no longer count: it goes by its time)
        if (AutoMobKiller.clickedThisTick()) {
            recast.held()
            return
        }

        val screen = UMinecraft.currentScreenObj
        if (screen != null && ScreenPause.pauses(stopOnGui.value, screen is AbstractContainerScreen<*>)) {
            // what comes due behind the screen goes out once your hand reacted to its close, never in the tick after it
            // (a reel without a bite as well: the timeout's, a retry's, a bobber lying out of the water)
            val due = dueNow(p, now)
            if (due) oweRecast() else recast.held()
            screenHeld.hold(now, due)
            screenOpen = true
            return
        }
        if (screenOpen) {
            screenOpen = false
            screenClosedAt = now
        }

        if (! rod) {
            phase = Phase.IDLE
            clearHeld()
            return
        }

        when (phase) {
            Phase.IDLE -> {
                val hook = p.fishing
                if (hook != null && hook.id != reeledHookId) waitForBite(now) else startCasting(now)
            }

            Phase.WAITING_BITE -> {
                val hook = p.fishing
                if (hook == null) {
                    // The hook can take a moment to appear client-side after a cast; after that it is really gone.
                    if (now - castAt > CAST_GRACE) startCasting(now)
                    return
                }
                // still the reeled bobber: the new cast is on its way, unless that reel never got through. The click again may
                // cast (the server reeled already), so its flight is checked like a cast's, turning nothing
                if (hook.id == reeledHookId) {
                    if (now - castAt > reelRetryMs && screenFree(now) && castClear(p, now, turn = false) && rodHand(p, now, castAt)) retryReel(now)
                    return
                }
                if (missedWater(p, hook, now)) return

                // a bite lost to something in the way is not taken again while its marker still shows
                val bite = now - castAt > BITE_IGNORE_AFTER_CAST && hasBite(p, hook)
                if (! bite) staleBite = false
                if (bite && ! staleBite) {
                    phase = Phase.REELING
                    biteAt = now
                    nextActionAt = TimingModel.dueAt(now, reelWait())
                    // the reel with no bite a screen held (the timeout's, a retry's, a lying bobber's) is no longer due: this
                    // reel is the bite's own, your reaction to it, not the wait after the close drawn for that one
                    screenHeld.clear()
                    // a look around keeping the view away comes back once your hand reacted to the bite, not in its tick
                    if (HumanAim.glances.out) glanceReactAt = now + glanceReaction()
                }
                // a timeout that ran out behind a screen of yours waits your reaction to its close like a cast
                else if (now - castAt > biteTimeoutMs && screenFree(now) && rodHand(p, now, castAt)) reel(now, caught = false)
            }

            Phase.REELING -> {
                if (p.fishing == null) startCasting(now)
                // a reel something in the way held past your latest reel of a bite: the bite is lost, like your missed ones,
                // and the bobber waits for the next (or its timeout)
                else if (RodWay.biteLost(now, biteAt, rodWay.held)) loseBite()
                // a bite whose reel came due behind a screen is reeled once your hand reacted to its close
                else if (screenHeld.go(now, now >= nextActionAt, screenClosedAt, 0L) { screenWait() } && rodHand(p, now, biteAt)) {
                    // the hand stops turning with the reel click, your reaction to the bite
                    if (! aim.stopping) aim.stop(Owner.IDLE)
                    reel(now, caught = true)
                }
            }

            Phase.CASTING -> {
                val hook = p.fishing
                // a new bobber (cast by hand meanwhile): it is out
                if (hook != null && hook.id != reeledHookId) return waitForBite(now)
                // after a missed cast, your reaction to the reel, then once the view may turn and nothing else turns it (put
                // off meanwhile, not dropped)
                turnHomeAfterMiss(p, now)
                val recasting = recast.pending || recastOwed
                // the cast or recast is due (the reeled bobber's retry is a reel: it goes as it did)
                val due = dueNow(p, now) && (recasting || hook == null)
                // the turn home after a missed cast is still to come: your reaction to that reel runs, or the view may turn
                // but your reaction to a screen's close does
                val turnOwed = reaimHome && (now < reaimHomeAt || castAimAllowed(p) && ! moveAfterScreen(now))
                val moveHold = CastCheck.moveHold(aim.moving(Owner.IDLE) && aim.turning, turnOwed, due, castAfterReaim)
                when {
                    // never a cast while an idle move still turns the view: one due meanwhile, or the one that move turns
                    // home for, goes out a wait of yours after its last moving tick ([HeldUse]); nor before the turn home
                    // after a missed cast started, owing nothing then: with no turn after all it goes on its own clock
                    moveHold != null -> held(now, moveHold)
                    // nor from a look around off the water: it comes back first, a recast's ticks wait for it, and one due
                    // meanwhile goes out a wait of yours after the way back came to rest
                    (recasting || due) && glanceHolds(p) -> {
                        glanceReturnDue = true
                        held(now, due)
                    }
                    // a move's slow tail to rest after its last moving tick turns the view less than a still hand's nudge a
                    // tick (your casts came in such tails too: 12 of your 178 after a move), so the cast does not wait for
                    // it, as long as the bobber from the view now and from where it comes to rest lands like one from your
                    // fishing view; else it waits for the rest
                    (recasting || due) && CastCheck.tailHolds(aim.moving && ! aim.turning) { tailLands(p) } -> held(now, due)
                    // After a catch the recast comes a fixed number of ticks after the reel click, like a held use button,
                    // even while the client still shows the reeled bobber: the server reeled it first, so this click casts.
                    recasting -> {
                        // a view left off the water (a look given up without its return, a fight's end) turns home first,
                        // and the recast waits for that move (from this tick on); the check waits while it cannot turn the
                        // view (a screen, your mouse), it is only used up once it could
                        if (! recastChecked) {
                            val turn = castTurnsHome(p, now, recast = true)
                            // a turn home after a screen of yours closed waits your reaction to the close, like any move of
                            // the view; the recast waits with it, by its time, and the check is not used up
                            if (CastCheck.waitsForScreen(turn, moveAfterScreen(now))) {
                                recast.held()
                                return
                            }
                            if (turn != null) recastChecked = true
                            if (turn == true && turnHomeForCast(p)) {
                                castAfterReaim = true
                                return
                            }
                        }
                        // its ticks, or once a hold stopped them its time: never the ticks left over after a hold
                        if (! recastOwed && recast.tick(now, recastDue)) recastOwed = true
                        if (recastOwed && free(now, afterCatch = true) && castClear(p, now) && rodHand(p, now, reeledAt)) cast(now, afterCatch = true)
                    }
                    // Server lag: the bobber is still out a while after reeling - reel again, then give up and wait for a bite.
                    // That click casts when the server reeled already: its flight is checked like a cast's, turning nothing
                    hook != null -> if (now - reeledAt > reelRetryMs && screenFree(now) && castClear(p, now, turn = false) && rodHand(p, now, reeledAt)) retryReel(now)
                    else -> if (due) {
                        // a bobber from this view would land elsewhere: the view turns home first, like after a missed cast,
                        // and the cast goes out a wait of yours after that turn came to rest. A check that cannot turn the
                        // view (a screen, your mouse, the killer's target) lets the cast go, as before.
                        val turn = if (castChecked) null else castTurnsHome(p, now)
                        // and after a screen of yours closed that turn waits your reaction to the close, the cast with it
                        if (CastCheck.waitsForScreen(turn, moveAfterScreen(now))) return
                        if (turn != null) castChecked = true
                        if (turn == true && turnHomeForCast(p)) castAfterReaim = true
                        else if (free(now, afterCatch = false) && castClear(p, now) && rodHand(p, now, reeledAt)) cast(now, afterCatch = false)
                    }
                }
            }
        }
    }

    /**
     * The reel, cast or recast of this phase is due by its own clock: the reel once your reaction to the bite passed, the
     * recast after a catch once its ticks would have (or held back already), the timed cast once its time came.
     */
    private fun dueNow(p: LocalPlayer, now: Long) = RodClock.due(
        phase, now, p.fishing?.id, reeledHookId, nextActionAt, recast.pending, recastOwed, recastDue, reeledAt, castAt, reelRetryMs, biteTimeoutMs,
        LandingCheck.due(lyingSince, now, landingReactMs),
    )

    /** The recast after a catch came due while held: it is owed from now on, its ticks no longer count. */
    private fun oweRecast() {
        if (phase != Phase.CASTING || ! recast.pending) return
        recast.cancel()
        recastOwed = true
    }

    /**
     * A move of the view holds the cast or recast at [now]; [due]: it is due (or the move turns home for it). A recast not
     * due yet stops counting its ticks: it goes by its time once nothing holds it ([RecastTimer.held]).
     */
    private fun held(now: Long, due: Boolean) {
        if (due) oweRecast() else recast.held()
        moveHeld.hold(now, due)
    }

    /**
     * A rod click with no bite to reel (a retry, the timeout, a bobber lying out of the water) due at [now] may go as far as
     * a screen of yours goes: one that came due behind it, or less than your shortest gap after its close, goes your wait
     * after the close ([Action.SCREEN_CLOSE_TO_CAST]: your rod uses after a close with no bite waiting).
     */
    private fun screenFree(now: Long) = screenHeld.go(now, true, screenClosedAt, ViewPolicy.MIN_GAP_MS) { screenWait() }

    /**
     * Nothing holds the cast or recast due at [now] any more: true once your hand reacted to what held it, the move's
     * rest ([HeldUse]; your own came 101-2153 ms after it, never sooner) and the screen's close.
     */
    private fun free(now: Long, afterCatch: Boolean) =
        moveHeld.go(now, true, restSeenAt(now), ViewPolicy.MIN_GAP_MS, restId()) { restWait(afterCatch) } &&
            screenHeld.go(now, true, screenClosedAt, ViewPolicy.MIN_GAP_MS) { screenWait() }

    /**
     * When the view's last move came to rest as your recordings time it: the end of its last moving tick
     * ([HumanAim.turnedAt]; your waits after a move ran from there, not from the hand's slow tail to its rest after it);
     * long ago while a move still turns the view, or before the first.
     */
    private fun restSeenAt(now: Long): Long = if (aim.turning) Long.MIN_VALUE / 2 else aim.turnedAt(now)

    /** Which rest [restSeenAt] is ([HumanAim.turns]); none while a move still turns the view. */
    private fun restId(): Long = if (aim.turning) Long.MIN_VALUE / 2 else aim.turns

    /**
     * Whether a cast from the view coming to rest after a move's last moving tick ([CastCheck.tailHolds]) lands like one from
     * your fishing view, and one from where it comes to rest too ([BobberWorld.judge]); null without a fishing view.
     */
    private fun tailLands(p: LocalPlayer): Boolean? {
        val home = aim.home ?: return null
        val world = BobberWorld.here()
        if (BobberWorld.judge(p, home, viewOf(p), world).same != true) return false
        return aim.restingAt?.let { BobberWorld.judge(p, home, it, world).same == true } ?: true
    }

    /** Every tick: a screen of yours that kept Auto Fishing off your view closed ([viewScreenOpen], [screenGate]). */
    private fun watchScreenClose(now: Long) {
        if (UMinecraft.currentScreenObj != null) {
            // one you opened while the killer's loadout switch was under way is yours too, as is one still open after it
            if (AutoMobKiller.FightRules.screenYours(LoadoutSwitcher.busy, LoadoutSwitcher.menuOpen())) viewScreenOpen = true
            return
        }
        if (! viewScreenOpen) return
        viewScreenOpen = false
        screenGate.closed(now)
    }

    /**
     * Auto Fishing's own move of the view may start at [now] as far as screens go: none of yours closed yet, or your
     * reaction to the last close passed (yours: 4 of 14 moves in the close's own tick, the rest p50 245 ms after it).
     */
    private fun moveAfterScreen(now: Long) = screenGate.ready(AutoMobKiller.ScreenGate.Kind.MOVE, now)

    /**
     * Your hand is off the killer's button for the rod's right click due at [now], whose own clock started at [from] (the
     * bite, the reel, the cast): after a left click of the killer's that came since, one of your switches to a right click
     * already due ([CrossGap], [Action.CROSS_L_TO_R]); one that came before is inside that clock. While it waits, the killer
     * clicks no more ([waitsForHand]).
     */
    private fun rodHand(p: LocalPlayer, now: Long, from: Long): Boolean {
        // never a rod click that would interact with something first (vanilla sends that before the cast, and no use goes
        // without it): it waits for the way, a reaction after it came clear
        val blocked = wayBlocked()
        // a wait of more than a few seconds for the way (a player standing right in front of you) is told in chat, and
        // so is its end
        when (wayNotice.update(now, blocked)) {
            WayNotice.Tell.PAUSED -> ChatUtils.modMessage("§7AutoFishing waits: ${inTheWay()} is right under your crosshair, a click would hit it.")
            WayNotice.Tell.RESUMED -> ChatUtils.modMessage("§7AutoFishing goes on: the way is clear.")
            null -> Unit
        }
        if (! rodWay.ready(now, blocked) { TimingModel.draw(Action.PATH_CLEAR, emptyList(), null, Random.Default) }) return false
        if (now >= crossGap.notBefore(AutoMobKiller.lastLeftClickAt, from, now) { TimingModel.draw(Action.CROSS_L_TO_R, emptyList(), null, Random.Default) }) return true
        handWaitTick = p.tickCount
        return false
    }

    /**
     * A rod click now would interact with something first ([RodWay.blocks]): under the crosshair (the last pick) an entity
     * that is no creature of yours, or a block a right click opens or toggles.
     */
    private fun wayBlocked(): Boolean = when (val hit = mc.hitResult) {
        is EntityHitResult -> RodWay.blocks(true, ownCreature(hit.entity), false)
        is BlockHitResult -> hit.type == HitResult.Type.BLOCK && usableBlock(hit.blockPos)
        else -> false
    }

    /**
     * Your creatures as the rod's checks see them, alive or dying: the root vehicles of their mobs and nametags, so a
     * mount or rider of one rides with it ([CastPath.ridesWith]). The tracker knows them with the killer off too
     * ([SeaCreatureTracker.ClaimRules.runs]): the one just caught at the bobber, older ones walking at you.
     */
    private fun ownRoots(): Set<Entity> {
        val roots = HashSet<Entity>()
        for (c in SeaCreatureTracker.creatures.values) if (c.own) {
            roots += c.stand.rootVehicle
            c.mob?.let { roots += it.rootVehicle }
        }
        return roots
    }

    /** [e] is one of your creatures, its nametag, or rides with one: a click on it interacts with nothing of anyone else's. */
    private fun ownCreature(e: Entity, roots: Set<Entity> = ownRoots()) = CastPath.ridesWith(e.rootVehicle, roots)

    private fun usableBlock(pos: BlockPos) = mc.level?.let { AutoMobKiller.BlockUses.usable(it.getBlockState(pos)) } == true

    /** What the last pick found that a rod click must not touch, for the chat: the entity's name, else the block's. */
    private fun inTheWay(): String = when (val hit = mc.hitResult) {
        is EntityHitResult -> hit.entity.name.string.ifBlank { "something" }
        is BlockHitResult -> mc.level?.getBlockState(hit.blockPos)?.block?.name?.string?.let { "the $it" } ?: "a block"
        else -> "something"
    }

    /**
     * The game handles a click of the use key in the tick of Auto Fishing's rod click with its fresh pick, after that
     * tick's hotbar keys and offhand swap: when that would interact with something first ([blocked]) or use another item
     * than the fishing rod the click was meant for (a hotbar key of yours just before it: a Hyperion, an Aspect of the Void),
     * it is cancelled whole (nothing is sent) and the next rod click waits a reaction after the way is clear ([rodWay]).
     */
    private fun guardRod(event: PlayerInteractEvent, blocked: Boolean) {
        val p = mc.player ?: return
        if (! rodClickTick.at(p.tickCount)) return
        val stack = p.mainHandItem
        if (! blocked && RodWay.sameRod(p.inventory.selectedSlot, stack.skyblockId, isFishingRod(stack) && stack.item === clickItem, clickSlot, clickId)) return
        event.cancel()
        rodWay.block()
    }

    /**
     * Last of all listeners, the game's handling of the use key in the tick of Auto Fishing's rod click: what that click
     * moves on is done only when it went out, nothing of it when anything cancelled it ([RodClick]).
     */
    private fun settleRod(event: PlayerInteractEvent) {
        val p = mc.player ?: return
        rodClick.settled(p.tickCount, ! event.isCanceled)
    }

    /**
     * The bite's reel was held past your latest reel of a bite ([RodWay.biteLost]): back to waiting, that bite's marker taken
     * for none. The reel held for the way is gone with it: the next rod click is no reaction to that way.
     */
    private fun loseBite() {
        phase = Phase.WAITING_BITE
        staleBite = true
        rodWay.clear()
        // the next rod click due counts its wait for the way from when it finds it blocked, not from this one's
        wayNotice.lapse()
        clearHeld()
    }

    /**
     * Reels in; after a bite ([caught]) the recast is timed in ticks from this click, otherwise by the cast delay; [then]
     * goes with it. A bobber hooked in an entity that is not yours (another player's creature: your own bobber hooked one
     * now and then) is reeled on these same times, like yours were: nothing pulls it sooner or later than any other.
     * All of it is done only once the click went out ([useRod]).
     */
    private fun reel(now: Long, caught: Boolean, then: () -> Unit = {}) {
        val hookId = mc.player?.fishing?.id ?: - 1
        useRod {
            reeledHookId = hookId
            phase = Phase.CASTING
            reeledAt = now
            reelRetryMs = reelRetryWait()
            reelRetries = 0
            checkLanding = false
            recastChecked = false
            castWay.clear()
            clearHeld()
            if (caught) {
                val wait = catchRecastWait()
                recast.start(wait)
                recastDue = now + RecastTimer.ticks(wait) * TimingModel.TICK_MS
            }
            else {
                recast.cancel()
                nextActionAt = TimingModel.dueAt(now, castWait())
            }
            then()
        }
    }

    /** The reeled bobber is still out a while after reeling (server lag): reel again, then give up and wait for a bite on it. */
    private fun retryReel(now: Long) {
        if (reelRetries >= MAX_REEL_RETRIES) {
            reeledHookId = - 1
            return waitForBite(now)
        }
        useRod {
            reelRetries ++
            // the server may have reeled that bobber already (the client still shows it): then this click casts, and a
            // press of yours before its bobber shows up reels it in, as for a cast ([AutoMobKiller.UseOwner])
            lastCastClickAt = lastRodUseAt
            phase = Phase.CASTING
            nextActionAt = TimingModel.dueAt(now, castWait())
            reeledAt = now
            reelRetryMs = reelRetryWait()
            recast.cancel()
            // the next rod click (a cast, or a retry again) has its flight checked anew
            castWay.clear()
            clearHeld()
        }
    }

    /**
     * No bobber out: cast after the cast delay. After a hold (a fight) the cast follows the rod and the view coming back
     * ([CastClock]); after the rod came back into your hand otherwise, your rod-to-cast time.
     */
    private fun startCasting(now: Long) {
        phase = Phase.CASTING
        reeledAt = now
        reelRetryMs = reelRetryWait()
        recast.cancel()
        // a new cast gets its own check: one whose turn home a hold cut short (a move of the killer's) is checked again,
        // and its own flight
        castChecked = false
        castAfterReaim = false
        castWay.clear()
        clearHeld()
        nextActionAt = when {
            castAfterHold -> CastClock.afterHold(now, heldSince, rodInHandAt, viewMovedAt, { rodToCastWait() }, { settleWait() }, { castWait() })
            now - rodInHandAt < ROD_BACK_MS -> maxOf(now, TimingModel.dueAt(rodInHandAt, rodToCastWait()))
            else -> TimingModel.dueAt(now, castWait())
        }
        castAfterHold = false
    }

    /** Casts (the recast after a catch: [afterCatch]); all of it is done only once the click went out ([useRod]). */
    private fun cast(now: Long, afterCatch: Boolean) {
        useRod {
            lastCastClickAt = lastRodUseAt
            // a look that stayed away where the cast guard let this cast go comes back after it, once your hand reacted to
            // the click (this tick's click carries the view it went out from): nothing else would bring the view home, nor
            // let looks and re-aims start again
            if (HumanAim.glances.state == GlancePolicy.State.REST && HumanAim.glances.out) {
                glanceBackAfterCast = true
                glanceReactAt = now + glanceReaction()
            }
            if (afterCatch) lastCatchRecastAt = now
            waitForBite(now)
            checkLanding = true
            landingReactMs = TimingModel.draw(Action.LANDING_REEL, emptyList(), null, Random.Default)
        }
    }

    private fun waitForBite(now: Long) {
        phase = Phase.WAITING_BITE
        castAt = now
        // a new bobber: no marker of a lost bite is around it, no click is held for a way any more, and the next cast's
        // flight is checked anew
        staleBite = false
        rodWay.clear()
        wayNotice.lapse()
        castWay.clear()
        reelRetryMs = reelRetryWait()
        biteTimeoutMs = BiteTimeout.draw(recastTimeout.value.toDouble(), Random.Default)
        recast.cancel()
        castAfterHold = false
        castAfterReaim = false
        castChecked = false
        // a turn home owed after a missed cast that never could start, or still turning, is no use to this bobber
        reaimHome = false
        castReaim = false
        checkLanding = false
        hookPos = null
        lyingSince = - 1L
        clearHeld()
    }

    /**
     * One right click with the rod. Only ever called while the main hand holds a fishing rod. True when it clicked. What
     * the click moves on ([commit]: the phase, the reeled bobber, the recast's clock, [lastRodUseAt]) is done once it went
     * out: through the use key, when the game handled it in this tick with nothing cancelling it ([guardRod], [settleRod]);
     * a cancelled one leaves everything as it was, so the reel or cast is still due (a cancelled bite's reel stays a reel
     * of that bite, lost past your latest reel, never a late recast).
     */
    private fun useRod(commit: () -> Unit = {}): Boolean {
        val p = mc.player ?: return false
        val stack = p.mainHandItem
        if (! isFishingRod(stack)) return false
        val at = System.currentTimeMillis()
        // the killer never clicks or swaps in this tick, whatever becomes of the click
        rodUseTick.mark(p.tickCount)
        val done = {
            lastRodUseAt = at
            commit()
        }

        if (UMinecraft.currentScreenObj == null) {
            rodClickTick.mark(p.tickCount)
            clickSlot = p.inventory.selectedSlot
            clickId = stack.skyblockId
            clickItem = stack.item
            rodClick.sent(p.tickCount, done)
            PlayerUtils.rightClick()
        }
        else {
            // Simulated key clicks are only processed without a screen; use the item directly instead.
            val gameMode = mc.gameMode ?: return false
            if (gameMode.useItem(p, InteractionHand.MAIN_HAND).consumesAction()) p.swing(InteractionHand.MAIN_HAND)
            done()
        }
        return true
    }

    /**
     * Auto Fishing's bobber came to rest out of water and lava and lay still there for your reaction ([landingReactMs],
     * [LandingCheck]): it is reeled in and the view turns back to your fishing view before the next cast (DESIGN-HUMAN
     * §2.9). One in water or lava or hooked [LANDING_CHECK_MS] after its cast has landed, one still flying
     * [LANDING_GIVE_UP_MS] after it is left alone. At most [MAX_MISSED_CASTS] in a row, so a spot where that never helps is
     * not cast at forever. True when it reeled.
     */
    private fun missedWater(p: LocalPlayer, hook: FishingHook, now: Long): Boolean {
        if (! checkLanding) return false
        val pos = hook.position()
        val last = hookPos
        hookPos = pos
        if (last == null) return false
        val wet = hook.hookedIn != null || inFluid(hook)
        if (wet && now - castAt >= LANDING_CHECK_MS) {
            checkLanding = false
            missedCasts = 0
            return false
        }
        val still = last.distanceTo(pos) <= HOOK_REST
        if (! still && now - castAt > LANDING_GIVE_UP_MS) checkLanding = false
        lyingSince = LandingCheck.lyingSince(lyingSince, now, still && ! wet)
        // a bobber found lying there behind a screen of yours is reeled your reaction to the close later
        if (! LandingCheck.due(lyingSince, now, landingReactMs) || ! screenFree(now) || ! rodHand(p, now, lyingSince)) return false
        if (missedCasts + 1 > MAX_MISSED_CASTS) {
            checkLanding = false
            missedCasts ++
            return false
        }
        // a reel cancelled as the game handled it leaves the bobber lying there, checked and reeled again
        reel(now, caught = false) {
            checkLanding = false
            missedCasts ++
            reaimHome = true
            reaimHomeAt = TimingModel.dueAt(now, TimingModel.draw(Action.REEL_TO_TURN, emptyList(), null, Random.Default))
            // your turn home came in the reel's own tick now and then
            turnHomeAfterMiss(p, now)
        }
        return true
    }

    /**
     * After a missed cast's reel the view turns home your reaction to that reel later ([reaimHomeAt], [Action.REEL_TO_TURN]),
     * once it may turn, your reaction to a screen's close passed and nothing else turns it (put off meanwhile, not
     * dropped); the cast waits for that turn's rest. A view already home ([CastCheck.missTurnNeeded]) turns not at all:
     * nothing waits for it, the cast goes on its own clock.
     */
    private fun turnHomeAfterMiss(p: LocalPlayer, now: Long) {
        if (! reaimHome) return
        if (! CastCheck.missTurnNeeded(homeDistance(p))) {
            reaimHome = false
            return
        }
        if (now < reaimHomeAt || ! castAimAllowed(p) || aim.moving || ! moveAfterScreen(now)) return
        reaimHome = false
        castAfterReaim = turnHomeForCast(p)
    }

    /** How far the view is from your fishing view the way a turn home measures it ([reaim]); null without one. */
    private fun homeDistance(p: LocalPlayer): Double? {
        val home = aim.home ?: return null
        val view = viewOf(p)
        val goal = HumanAim.homeGoal(view.yaw, home)
        return MotorModel.angle(view.yaw, view.pitch, goal.yaw, goal.pitch)
    }

    /** The bobber floats in (or right above) water or lava; true where the chunk is not loaded (nothing to tell there). */
    private fun inFluid(hook: FishingHook): Boolean {
        val level = mc.level ?: return true
        val pos = hook.blockPosition()
        if (! level.isLoaded(pos)) return true
        return ! level.getFluidState(pos).isEmpty || ! level.getFluidState(pos.below()).isEmpty
    }

    private fun hasBite(p: LocalPlayer, hook: FishingHook): Boolean {
        val level = mc.level ?: return false
        return if (onlyOwnBobber.value) {
            level.getEntitiesOfClass(ArmorStand::class.java, hook.boundingBox.inflate(4.0)) {
                it.isInvisible && it.isCustomNameVisible && it.customName?.unformattedText?.trim() == "!!!"
            }.isNotEmpty()
        }
        else {
            level.getEntitiesOfClass(ArmorStand::class.java, p.boundingBox.inflate(15.0)) {
                it.customName?.unformattedText?.contains("!!!") == true
            }.isNotEmpty()
        }
    }

    private fun isOwnDeathMessage(raw: String): Boolean {
        val text = raw.trim()
        if (deathMessages.none { text.contains(it) }) return false
        if (text.startsWith("☠")) return true
        // Plain "You ..." system lines only; skip rank-prefixed / channel chat ("[MVP+] x: ...", "Party > x: ...").
        return text.contains("You") && ! text.startsWith("[") && ! text.contains(": ")
    }

    /**
     * Nothing keeps Auto Fishing's hands off your view ([CastAim]): no screen (whatever Pause In Menus says), nothing holds
     * it, you are alive, and the Fishing Recorder is off (its rot lines would be learned as your hand).
     */
    private fun viewFree(p: LocalPlayer) = UMinecraft.currentScreenObj == null && ! FishingRecorder.enabled && ! paused && ! p.isDeadOrDying &&
        ! AutoMobKiller.holdsAutoFishing() && ! FishingRecorder.holdsAutoFishing()

    /** Auto Fishing may move your view by itself (Shake Head, idle re-aims): no fight of the killer's either ([CastAim.motion]). */
    private fun motionAllowed(p: LocalPlayer) = CastAim.motion(viewFree(p), aim.movingInFight(), AutoMobKiller.busy())

    /** Auto Fishing may turn your view home for the cast or recast it sends anyway: no move of the killer's fight in flight, no target of it fought ([CastAim.allowed]). */
    private fun castAimAllowed(p: LocalPlayer) = CastAim.allowed(viewFree(p), aim.movingInFight(), AutoMobKiller.aimsAtTarget())

    /** Shake Head's rest nudges are on: you fish with nothing else going on ([HumanAim] asks every tick without a move). */
    private fun idleRestAllowed(): Boolean {
        val p = mc.player ?: return false
        return enabled && shakeHead.value && isFishingRod(p.mainHandItem) && motionAllowed(p)
    }

    /**
     * Once per tick: an idle move ends when Auto Fishing may not move the view, unless it turns home for Auto Fishing's
     * own cast and may still ([CastAim.goesOn]): frozen for a screen, the Recorder or your death, brought to rest for a
     * fight or a hold once your hand reacted to it ([CastAim.halt], [MoveHalt]); and now and then, while you wait for a
     * bite, a view that drifted from home is re-aimed ([IdleReaim]).
     */
    private fun idleTick(p: LocalPlayer, now: Long) {
        val motion = motionAllowed(p)
        val halt = CastAim.halt(CastAim.goesOn(motion, castReaim, castAimAllowed(p)), aim.moving(Owner.IDLE), aim.stopping, frozen(p))
        // a turn home for a cast cut short (a screen, a hold): that cast is checked again before it goes out
        if (idleHalt.tick(aim, now, halt) { handReaction() } && castReaim) {
            castChecked = false
            recastChecked = false
            castAfterReaim = false
        }
        if (! motion) return
        // a look around's dwell is no drift
        if (! shakeHead.value || phase != Phase.WAITING_BITE || aim.moving || HumanAim.glances.out) return
        val distance = aim.home?.let { HumanAim.distance(p.yRot, p.xRot, it.yaw.toFloat(), it.pitch.toFloat()).toDouble() }
        val (next, go) = IdleReaim.schedule(now, reaimAt, castAt, distance, AutoMobKiller.userTurnedRecently(now), Random.Default, moveAfterScreen(now))
        reaimAt = next
        if (go) reaim(p, IdleReaim.MIN_DEG, forCast = false)
    }

    /** The turn home before Auto Fishing's own cast or recast ([reaim]): false when none started. */
    private fun turnHomeForCast(p: LocalPlayer) = reaim(p, MISS_REAIM_DEG, forCast = true)

    /**
     * An idle move from the view back towards [HumanAim.home] when it is [minDeg] or more away, ending where a cast still
     * lands like one from home ([BobberFlight]); [forCast]: it is for Auto Fishing's own cast ([castReaim]). False when
     * none started.
     */
    private fun reaim(p: LocalPlayer, minDeg: Double, forCast: Boolean): Boolean {
        val home = aim.home ?: return false
        if (aim.moving) return false
        val view = viewOf(p)
        val world = BobberWorld.here()
        val goal = IdleReaim.goal(view, HumanAim.homeGoal(view.yaw, home), minDeg, Random.Default) { BobberWorld.judge(p, home, it, world).same } ?: return false
        aim.params = HumanAim.paramsOf(BehaviorProfile.current.motor)
        if (! aim.start(goal.yaw, goal.pitch, Owner.IDLE)) return false
        castReaim = forCast
        return true
    }

    /** A look around keeps the view off the water: no cast from it ([CastCheck.glanceHolds]). */
    private fun glanceHolds(p: LocalPlayer): Boolean {
        val home = aim.home
        val distance = home?.let { HumanAim.distance(p.yRot, p.xRot, it.yaw.toFloat(), it.pitch.toFloat()).toDouble() }
        return CastCheck.glanceHolds(HumanAim.glances.out, aim.moving(Owner.GLANCE) && aim.turning, distance) { home?.let { BobberWorld.judge(p, it, viewOf(p)).same } }
    }

    /**
     * The timed cast now, or the [recast] after a catch, would land elsewhere than one from your fishing view and turns
     * home first ([CastCheck]); null while the check cannot turn the view, so it is not used up.
     */
    private fun castTurnsHome(p: LocalPlayer, now: Long, recast: Boolean = false): Boolean? {
        val home = aim.home
        val distance = home?.let { HumanAim.distance(p.yRot, p.xRot, it.yaw.toFloat(), it.pitch.toFloat()).toDouble() }
        val allowed = castAimAllowed(p)
        val turned = AutoMobKiller.userTurnedRecently(now)
        val lands = { home?.let { BobberWorld.judge(p, it, viewOf(p)).same } }
        return if (recast) CastCheck.recastTurnsHome(distance, allowed, turned, lands) else CastCheck.turnsHome(distance, allowed, turned, lands)
    }

    /**
     * The cast or recast due may go as far as its bobber's flight goes ([CastPath]): nothing that is not yours (another
     * player's sea creature, a player, any other mob) meets it first. One that would: the view turns a small re-aim of
     * yours to a spot of home's water whose flight is clear (once per cast, where Auto Fishing may turn the view), the cast
     * waiting for that move like any; else it waits for the way a while ([CastWay]) and goes once clear, a reaction later,
     * or when that wait ran out, as yours went into them now and then. Never longer. The re-aim starts a reaction after
     * the flight was found blocked where the view may turn ([CastWay.reaimDue]), never in that tick. [turn] false: a rod
     * click that may cast or reel (a retry under server lag) only waits for the way, it turns nothing.
     */
    private fun castClear(p: LocalPlayer, now: Long, turn: Boolean = true): Boolean {
        val world = BobberWorld.here() ?: return true
        val blocked = castHitsOther(p, viewOf(p), world)
        val wait = { CastWay.waitMs(Random.Default) }
        val react = { TimingModel.draw(Action.PATH_CLEAR, emptyList(), null, Random.Default) }
        // the re-aim is tried only where the view may turn now: a cast that may not keeps it for when it may
        if (blocked && turn && ! castWay.reaimTried && castAimAllowed(p) && ! aim.moving) {
            // your reaction to what is on the flight (and after a screen of yours closed, to the close, like any move of the
            // view): the cast waits with it, the way's own wait running meanwhile
            if (! castWay.reaimDue(now, react) || ! moveAfterScreen(now)) {
                castWay.ready(now, true, wait, react)
                return false
            }
            val started = reaimClear(p, world)
            castWay.reaimTaken(started)
            if (started) {
                castAfterReaim = true
                return false
            }
        }
        return castWay.ready(now, blocked, wait, react)
    }

    /**
     * A cast from [view] would hook something that is not yours first ([CastPath.hitsOther]): its mean flight to the water
     * ([BobberFlight], up to its first tick in a fluid) against every living entity that is no armor stand around it, your
     * creatures (and what rides with them, [ownRoots]) taking the bobber where every bobber of your spread there meets
     * them, anything else never to be hooked, met with your spread at its distance along the flight (none at the hook's
     * spawn: a player beside or behind you is no obstacle).
     */
    private fun castHitsOther(p: LocalPlayer, view: View, world: BobberWorld): Boolean {
        val level = mc.level ?: return false
        val steps = ArrayList<CastPath.Step>()
        val landing = BobberFlight.fly(p.x, p.y, p.z, view.yaw, view.pitch, p.isShiftKeyDown, world) { tick, x0, y0, z0, x1, y1, z1 ->
            steps += CastPath.Step(tick, Vec3(x0, y0, z0), Vec3(x1, y1, z1))
        }
        val flight = CastPath.flight(steps, landing.contactTick)
        if (flight.isEmpty()) return false
        var near = AABB(flight[0].from, flight[0].to)
        for (s in flight) near = near.minmax(AABB(s.from, s.to))
        val around = level.getEntities(p, near.inflate(CastPath.HOOK_HIT + CastPath.SPREAD + 1.0)) { it.isAlive && ! it.isSpectator && it is LivingEntity && it !is ArmorStand }
        return CastPath.hitsOther(flight, around, { it.boundingBox }, { it.rootVehicle }, ownRoots())
    }

    /**
     * A small re-aim of yours from the view ([CastPath.reaim]) to where a cast lands like one from your fishing view
     * ([BobberWorld.judge]) with nothing that is not yours on its flight; false when none was found or the view may not
     * turn now.
     */
    private fun reaimClear(p: LocalPlayer, world: BobberWorld): Boolean {
        val home = aim.home ?: return false
        if (aim.moving) return false
        val goal = CastPath.reaim(viewOf(p), Random.Default) { v -> BobberWorld.judge(p, home, v, world).same == true && ! castHitsOther(p, v, world) } ?: return false
        aim.params = HumanAim.paramsOf(BehaviorProfile.current.motor)
        if (! aim.start(goal.yaw, goal.pitch, Owner.IDLE)) return false
        castReaim = true
        return true
    }

    private fun viewOf(p: LocalPlayer) = View(p.yRot.toDouble(), p.xRot.toDouble())

    private fun randomized(base: Int) = base.toLong() + if (delayVariance.value > 0) Random.nextLong(0, delayVariance.value + 1L) else 0L

    /** Your recorded [samples] when Use Recorded Timings is on and there are enough of them. */
    private fun recorded(samples: List<Int>) = samples.takeIf { recordedTimings.value && it.size >= TimingModel.MIN_SAMPLES }

    /** [state] with the stay rate your recordings showed for [family] when they had enough slow waits to tell; else TimingModel finds it. */
    private fun streak(state: StreakState, family: String) = state.also {
        it.stay = BehaviorProfile.current.timing.streaks[family]?.takeIf { s -> s.slowPairs >= STREAK_PAIRS }?.stay
    }

    /** Bite -> reel: one of your recorded reaction times when Use Recorded Timings has enough of them, else the sliders. */
    private fun reelWait() = recorded(BehaviorProfile.current.biteReactionMs)?.let { TimingModel.draw(Action.BITE, it, null, Random.Default) } ?: randomized(reelDelay.value)

    private fun castWait() = recorded(BehaviorProfile.current.castDelayMs)?.let { TimingModel.draw(Action.CAST, it, null, Random.Default) } ?: randomized(castDelay.value)

    /** Catch reel -> recast: your recorded recasts after a catch (a held use button recasts after 200 ms), slow ones in streaks; else the cast delay. */
    private fun catchRecastWait() =
        recorded(BehaviorProfile.current.catchRecastMs)?.let { TimingModel.draw(Action.CATCH_RECAST, it, streak(catchRecastStreak, "catchRecastMs"), Random.Default) } ?: castWait()

    /** Rod back in hand -> cast: your recorded ones, slow ones in streaks, when Use Recorded Timings has enough of them; else the cast delay. */
    private fun rodToCastWait() =
        recorded(BehaviorProfile.current.rodToCastMs)?.let { TimingModel.draw(Action.ROD_TO_CAST, it, streak(rodToCastStreak, "rodToCastMs"), Random.Default) } ?: castWait()

    /** A bite or a cast brought the cast nearer while a look kept the view away -> the look starts back. */
    private fun glanceReaction() = TimingModel.draw(Action.GLANCE_REACTION, emptyList(), null, Random.Default)

    /** A reel (or a retry) whose bobber is still out -> reeling again. */
    private fun reelRetryWait() = TimingModel.draw(Action.REEL_RETRY, emptyList(), null, Random.Default)

    /** A move held the cast or recast after a catch ([afterCatch]) -> it goes out: your waits after such a move's rest. */
    private fun restWait(afterCatch: Boolean) = TimingModel.draw(if (afterCatch) Action.REST_TO_RECAST else Action.REST_TO_CAST, emptyList(), null, Random.Default)

    /**
     * A screen held the reel or a cast -> it goes out: your waits after a screen of yours closed, for a reel of a bite and
     * for a cast; a reel with no bite (a retry, the timeout, a bobber lying out of the water) as a cast, your rod use after a
     * close with no bite waiting.
     */
    private fun screenWait() =
        TimingModel.draw(if (phase == Phase.REELING) Action.SCREEN_CLOSE_TO_REEL else Action.SCREEN_CLOSE_TO_CAST, emptyList(), null, Random.Default)

    /** The view settled after a turn -> cast: your recorded time from a turn back to the next cast, else a default like it. */
    private fun settleWait(): Long {
        val d = BehaviorProfile.current.policy.backCastLag
        if (! recordedTimings.value || d.n < TimingModel.MIN_SAMPLES || d.p50 <= 0.0 || d.lnSigma <= 0.0) return TimingModel.draw(Action.SETTLE_TO_CAST, emptyList(), null, Random.Default)
        return TimingModel.lognormal(d.p50, d.lnSigma, Random.Default, Action.SETTLE_TO_CAST.capMs).toLong()
    }

    /**
     * When the cast after a hold (a fight) goes out (DESIGN-HUMAN §3.6): at the later of the rod back in your hand + your
     * rod-to-cast time and the view settled + your settle time, each only when it happened during the hold; with neither,
     * after the plain cast delay. Due on the nearest tick. Pure, so it can be unit tested.
     */
    internal object CastClock {
        fun afterHold(nowMs: Long, heldSince: Long, rodBackAt: Long, viewMovedAt: Long, rodToCast: () -> Long, settle: () -> Long, castWait: () -> Long): Long {
            val rod = if (rodBackAt >= heldSince) TimingModel.dueAt(rodBackAt, rodToCast()) else null
            val view = if (viewMovedAt >= heldSince) settled(nowMs, viewMovedAt, settle) else null
            if (rod == null && view == null) return TimingModel.dueAt(nowMs, castWait())
            return maxOf(rod ?: Long.MIN_VALUE, view ?: Long.MIN_VALUE)
        }

        /**
         * Your settle time from the view's last moving tick ([movedAt]: your recorded times run from there), drawn again
         * until it falls after the hold's end at [nowMs] (you had not cast by then, the hold kept the cast back), never
         * piled on that tick; null when none of [AutoMobKiller.FightRules.REDRAW_TRIES] does: the view had settled long
         * before, nothing of it is waited for.
         */
        fun settled(nowMs: Long, movedAt: Long, settle: () -> Long): Long? {
            repeat(AutoMobKiller.FightRules.REDRAW_TRIES) {
                val at = TimingModel.dueAt(movedAt, settle())
                if (at > nowMs) return at
            }
            return null
        }
    }

    /**
     * How long a cast waits for a bite before it is reeled in and cast again: Recast Timeout drawn per cast, at most
     * [CAP] times it and never piled there, so a bobber stuck on land or at a dead spot is not reeled at one fixed period
     * (your own reels without a bite came anywhere between 1 and 11 s). Pure, so it can be unit tested.
     */
    internal object BiteTimeout {
        private const val SIGMA = 0.2
        const val CAP = 1.5

        /** The timeout in ms for a Recast Timeout of [seconds]. */
        fun draw(seconds: Double, rng: Random) = TimingModel.lognormal(seconds * 1000.0, SIGMA, rng, CAP * seconds * 1000.0)
    }

    /**
     * When a bobber of Auto Fishing's lying out of water and lava is reeled in: once it has lain still there for your
     * reaction to it, drawn per cast ([Action.LANDING_REEL]); a move of it starts that over. Pure, so it can be unit tested.
     */
    internal object LandingCheck {
        /** Since when the bobber lies still out of water and lava ([lying] in this tick at [now]), -1 when it does not. */
        fun lyingSince(since: Long, now: Long, lying: Boolean) = if (! lying) - 1L else if (since < 0) now else since

        /** It has lain there [since] then for your reaction [reactMs]: reel it in. */
        fun due(since: Long, now: Long, reactMs: Long) = since >= 0 && now - since >= reactMs
    }

    /**
     * What the rod needs next, for whatever else wants the view or a menu meanwhile: the looks around (Auto Fishing's
     * between fights, the killer's in a fight that does not hold Auto Fishing) and the killer's loadout menu. Pure, so it
     * can be unit tested.
     */
    internal object RodClock {
        /**
         * The rod use of [phase] is due by its own clock at [now]: the reel once your reaction to the bite passed
         * ([nextActionAt]), the recast after a catch once its ticks would have ([recastDue]) or held back already, the timed
         * cast once its time came; and the reels with no bite: the reeled bobber's retry ([reelRetryMs] after its reel or
         * cast), the timeout ([biteTimeoutMs] after the cast) and a bobber lying out of water and lava ([landingDue]).
         * [hook]: the bobber out (null: none), [reeledHook] the one reeled last.
         */
        fun due(
            phase: Phase, now: Long, hook: Int?, reeledHook: Int, nextActionAt: Long, recastPending: Boolean, recastOwed: Boolean, recastDue: Long,
            reeledAt: Long, castAt: Long, reelRetryMs: Long, biteTimeoutMs: Double, landingDue: Boolean,
        ): Boolean = when (phase) {
            Phase.REELING -> hook != null && now >= nextActionAt
            Phase.CASTING -> when {
                recastPending || recastOwed -> recastOwed || now >= recastDue
                hook != null -> now - reeledAt > reelRetryMs
                else -> now >= nextActionAt
            }
            Phase.WAITING_BITE -> hook != null && if (hook == reeledHook) now - castAt > reelRetryMs else now - castAt > biteTimeoutMs || landingDue
            Phase.IDLE -> false
        }

        /**
         * How long the view is free for a look around in [phase] at [now]: reeling in, until the tick after the reel at
         * [nextActionAt] (the recast follows it within ticks: a bite brings a look back once your hand reacted to it, none
         * starts before the reel);
         * casting, until the cast is due ([recastDue] for the recast after a catch, else [nextActionAt]); otherwise for as
         * long as it likes. A reel or cast held back ([held]: when it goes out, [HeldUse]) leaves the view free only until
         * then.
         */
        fun glanceWindowMs(phase: Phase, now: Long, nextActionAt: Long, recastDue: Long?, held: Long? = null): Double = when {
            held != null && (phase == Phase.REELING || phase == Phase.CASTING) -> held - now.toDouble()
            phase == Phase.REELING -> nextActionAt - now.toDouble() + TimingModel.TICK_MS
            phase == Phase.CASTING -> (recastDue ?: nextActionAt) - now.toDouble()
            else -> Double.POSITIVE_INFINITY
        }

        /** Reeling in after a bite, or a cast or recast to come: a hold now (a loadout menu) would lose that bite or recast. */
        fun busy(phase: Phase) = phase == Phase.REELING || phase == Phase.CASTING
    }

    /**
     * What Auto Fishing's rod click would touch first. Vanilla interacts with an entity under the crosshair (and uses a
     * block's own use) before it casts or reels, and no use goes without that: a click with another player's creature, a
     * player, an armor stand or any entity but your creatures under the crosshair, or a door, a lever, a chest there, waits
     * instead ([AutoMobKiller.ClearWay]); a reel waiting past your latest reel of a bite loses the bite. Pure, so it can be
     * unit tested.
     */
    internal object RodWay {
        /** Your reels of a bite came 0-2749 ms after it, 99.5 % of them within 948 ms (1578): a reel held longer is no reel of yours. */
        const val REEL_LATEST_MS = 950L

        /** The click interacts with something first: an [entity] under the crosshair that is not one of your creatures ([own]), or a [usableBlock]. */
        fun blocks(entity: Boolean, own: Boolean, usableBlock: Boolean) = entity && ! own || usableBlock

        /** The reel of the bite at [biteAt], [held] for the way at [now], would come later than yours ever did: the bite is lost. */
        fun biteLost(now: Long, biteAt: Long, held: Boolean) = held && now - biteAt > REEL_LATEST_MS

        /**
         * The item the game is about to use ([slot], [id], [rod]: a fishing rod you fish with, the very item the click was
         * meant for) is the rod Auto Fishing's click was meant for ([clickSlot], [clickId]); a hotbar key or the offhand
         * swap handled before the click in its tick puts something else there.
         */
        fun sameRod(slot: Int, id: String, rod: Boolean, clickSlot: Int, clickId: String) = rod && slot == clickSlot && id == clickId
    }

    /**
     * Auto Fishing's rod click through the use key, from the tick it was [sent] to the game's handling of it later in that
     * tick: what it moves on (its commit) is done when it went out ([settled] with nothing cancelling it), dropped when it
     * was cancelled or never handled in its tick ([expire]). No game state, so it is unit tested.
     */
    internal class RodClick {
        private var tick = Int.MIN_VALUE
        private var commit: (() -> Unit)? = null

        /** A click is on its way, its commit not done yet. */
        val pending get() = commit != null

        fun sent(tick: Int, commit: () -> Unit) {
            this.tick = tick
            this.commit = commit
        }

        /** The game handled a use of the item in [tick]: the click [out] (nothing cancelled it) does its commit, once. */
        fun settled(tick: Int, out: Boolean) {
            if (tick != this.tick) return
            val c = commit ?: return
            commit = null
            if (out) c()
        }

        /** At the start of [tick]: a click of an earlier tick the game never handled never went out. */
        fun expire(tick: Int) {
            if (tick != this.tick) commit = null
        }

        fun clear() {
            commit = null
        }
    }

    /**
     * A player tick something of Auto Fishing's happened in (its rod click, the use key's click it checks), forgotten with
     * its state: after a world change the new player counts its ticks from 0 again, and no tick of the old one is one of
     * the new one's. No game state, so it is unit tested.
     */
    internal class TickMark {
        private var tick = NONE

        fun mark(tick: Int) {
            this.tick = tick
        }

        /** It happened in [tick]. */
        fun at(tick: Int) = this.tick != NONE && this.tick == tick

        fun clear() {
            tick = NONE
        }

        private companion object {
            const val NONE = Int.MIN_VALUE
        }
    }

    /**
     * Auto Fishing waiting for the way says so: a rod click held for something under the crosshair (a player standing in
     * front of you) [AFTER_MS] or longer is told once, and its end once the way was seen clear again. No game state, so it
     * is unit tested.
     */
    internal class WayNotice(private val afterMs: Long = AFTER_MS) {
        enum class Tell { PAUSED, RESUMED }

        private var since = NONE
        private var told = false

        /** A rod click due at [now] found the way [blocked] or clear: what to tell, if anything. */
        fun update(now: Long, blocked: Boolean): Tell? {
            if (! blocked) {
                since = NONE
                if (! told) return null
                told = false
                return Tell.RESUMED
            }
            if (since == NONE) since = now
            if (told || now - since < afterMs) return null
            told = true
            return Tell.PAUSED
        }

        /**
         * The click held for the way is no longer due (its bite was lost, a new bobber is out): the next one due counts
         * its wait from the first tick it finds the way blocked, never from this one's. One told already stays told until
         * the way is seen clear, so its end is still told and the pause is not told twice.
         */
        fun lapse() {
            since = NONE
        }

        fun clear() {
            since = NONE
            told = false
        }

        companion object {
            /** A few seconds: no reel of a bite of yours came this late, a cast waits that long only for something in the way. */
            const val AFTER_MS = 3000L
            private const val NONE = Long.MIN_VALUE
        }
    }

    /**
     * A cast or recast due whose bobber's flight meets another player's creature or a player first ([CastPath]): it waits
     * for that to move, a [waitMs] drawn at the first such tick, and goes a reaction after the flight was seen clear
     * ([AutoMobKiller.ClearWay]) or once that wait ran out with it still there (your casts went into one now and then,
     * none waited for it). Once per cast it may try a re-aim, a reaction after its flight was found blocked where the
     * view may turn ([reaimDue], [reaimTaken]); the flight that re-aim turned to is a new one, waited for anew. No game
     * state, so it is unit tested.
     */
    internal class CastWay {
        private val way = AutoMobKiller.ClearWay()
        private var until = NONE
        private var anyway = false
        private var reaimed = false
        private var reaimAt = NONE
        /** Blocks of this cast's flight that went before the re-aim's reaction to them ran ([ready]). */
        private var gone = 0
        /** The flight was blocked at the last check (null: none yet for this flight). */
        private var lastBlocked: Boolean? = null
        /** Once the way's wait ran out: the cast goes your reaction after the flight last changed. */
        private var changeDue = NONE

        /** This cast's re-aim was tried already ([reaimTaken]), or given up for something walking in and out of its flight. */
        val reaimTried get() = reaimed

        /**
         * The re-aim of this cast may be tried at [now]: a [react]ion after the first tick it was asked for since its
         * flight was last seen clear (found blocked where the view may turn), drawn then, never in that tick; false once
         * it was tried or given up.
         */
        fun reaimDue(now: Long, react: () -> Long): Boolean {
            if (reaimed) return false
            if (reaimAt == NONE) reaimAt = TimingModel.dueAt(now, react())
            return now >= reaimAt
        }

        /**
         * The re-aim was tried: none again for this cast. One that [started] turns the view to a clear flight: the cast
         * follows that move's rest, no reaction to the way coming clear on top (the hand turned away from it itself), and
         * that flight is a new one: something on it is waited for anew, the old flight's wait (run out or not) no
         * longer counts for it.
         */
        fun reaimTaken(started: Boolean) {
            reaimed = true
            if (! started) return
            way.clear()
            until = NONE
            anyway = false
            lastBlocked = null
            changeDue = NONE
        }

        /**
         * The cast may go at [now] with its flight [blocked] or not ([wait]: how long it waits for it to move; [react]: the
         * reaction to it moving away). A flight seen clear drops the re-aim's reaction drawn for it: a block after that is
         * a new one, reacted to anew. Something walking in and out of the flight faster than that reaction, [MAX_GONE]
         * times, has the re-aim given up: the cast waits for its way alone then, within its bounds, and no re-aim ever
         * starts on a reaction to a block that was gone. Once the way's wait ran out the cast goes into the block, but
         * never in the tick something walks into the flight or out of it: your reaction to that change first, and
         * whatever keeps walking in and out, never past the way's longest wait ([WAIT_MAX_MS]) after that wait ran out.
         */
        fun ready(now: Long, blocked: Boolean, wait: () -> Long, react: () -> Long): Boolean {
            if (! blocked && reaimAt != NONE && ! reaimed) {
                reaimAt = NONE
                if (++ gone >= MAX_GONE) reaimed = true
            }
            val changed = lastBlocked != null && lastBlocked != blocked
            lastBlocked = blocked
            if (blocked && until == NONE) until = TimingModel.dueAt(now, wait())
            if (anyway || blocked && now >= until) {
                anyway = true
                if (changed) changeDue = TimingModel.dueAt(now, react())
                return now >= changeDue || now >= until + WAIT_MAX_MS.toLong()
            }
            return way.ready(now, blocked, react)
        }

        /** A new cast: its flight is checked anew. */
        fun clear() {
            way.clear()
            until = NONE
            anyway = false
            reaimed = false
            reaimAt = NONE
            lastBlocked = null
            changeDue = NONE
            gone = 0
        }

        companion object {
            private const val NONE = Long.MIN_VALUE
            /**
             * A re-aim's reaction is drawn anew this often per cast at most (no data: none of your casts near another
             * player's creature waited or re-aimed; a conservative few, each under [Action.PATH_CLEAR]'s 1.5 s cap).
             */
            const val MAX_GONE = 3
            /**
             * The wait for something on the flight to move: none of your casts with another player's creature within 0.8 m
             * of the line (11 of 1775) waited (reel to cast p50 149 ms, as all of yours), so a conservative lognormal
             * around a short look, never past [WAIT_MAX_MS].
             */
            const val WAIT_P50_MS = 600.0
            const val WAIT_SIGMA = 0.5
            const val WAIT_MAX_MS = 1500.0

            fun waitMs(rng: Random) = TimingModel.lognormal(WAIT_P50_MS, WAIT_SIGMA, rng, WAIT_MAX_MS).roundToLong()
        }
    }

    /**
     * A cast's bobber against the entities around its flight: 1.8's hook meets the first box along each tick's move
     * ([BobberFlight.Track]), widened by [HOOK_HIT], and is hooked in it. Pure, so it is unit tested.
     */
    internal object CastPath {
        /** 1.8's hook meets an entity's box widened by this... */
        const val HOOK_HIT = 0.3
        /**
         * ...and your bobbers spread sideways of the mean flight's line the farther they flew: where they came to rest lay
         * this much sideways per metre along it at the most, 19 times in 20 (459 casts of yours with their reel's spot 2 m
         * and more along it: side / along p50 0.009, p90 0.042, p95 0.070), the launch noise and the spot's drift
         * together. At the hook's spawn there is none: a box beside or behind you is met only where the hook meets it...
         */
        const val SPREAD_PER_M = 0.07
        /**
         * ...and never more than this, however far: 19 times in 20 your bobbers came to rest within it of the line (p50
         * 0.07, p90 0.44, p95 0.74, p99 0.86 m; 0.73 at 7-11 m, under 7 m p90 0.20).
         */
        const val SPREAD = 0.74
        /** The flight is weighed in pieces this long at most, each with the spread at its far end, the widest on it. */
        const val PIECE = 0.25
        /**
         * Your small re-aims between casts from the same spot (0.3-10 degrees: n 145, p25 1.38, p50 2.57, p75 4.59; ln fit
         * p50 2.41, sd 0.84), drawn under the largest...
         */
        const val REAIM_P50 = 2.41
        const val REAIM_SIGMA = 0.84
        const val REAIM_MIN = 0.3
        const val REAIM_MAX = 10.0
        /** ...this many times for a spot whose flight is clear. */
        const val REAIM_TRIES = 16

        /** The hook's move in [tick], [from] to [to]. */
        class Step(val tick: Int, val from: Vec3, val to: Vec3)

        /** The flight's moves up to its first tick in a fluid ([contactTick], -1: none), where it floats from then on. */
        fun flight(steps: List<Step>, contactTick: Int) = if (contactTick < 0) steps else steps.filter { it.tick <= contactTick }

        /** Your bobbers' sideways spread [flown] metres along the flight from the hook's spawn ([SPREAD_PER_M], at most [SPREAD]). */
        fun spread(flown: Double) = min(SPREAD_PER_M * flown.coerceAtLeast(0.0), SPREAD)

        /**
         * How much wider than its box an entity is met [flown] metres along the flight: anything that is not yours with
         * the [spread] there too (a bobber that far out may pass that much beside the mean line), a creature of yours
         * ([mine]) only within its box shrunk by it, where every bobber of that spread still meets it (negative: shrunk).
         */
        fun margin(mine: Boolean, flown: Double) = if (mine) HOOK_HIT - spread(flown) else HOOK_HIT + spread(flown)

        /** [root] (an entity's root vehicle) is one of [mine]: the root vehicles of your creatures' mobs and nametags. */
        fun <R> ridesWith(root: R, mine: Set<R>) = root in mine

        /**
         * A cast's flight ([steps] in order) among [entities] (their [box]es, each yours when its [root] vehicle rides with
         * one of your creatures, [mine]) is hooked in something that is not yours ([blocked]).
         */
        fun <E, R> hitsOther(steps: List<Step>, entities: List<E>, box: (E) -> AABB, root: (E) -> R, mine: Set<R>) =
            blocked(steps, entities.map { box(it) to ridesWith(root(it), mine) })

        /**
         * The flight ([steps] in order) is hooked in something that is not yours: of [boxes] (each with whether it is a
         * creature of yours) the first one met decides, widened by their [margin] at that distance from the hook's spawn,
         * in [PIECE]s with the spread at each one's far end. Your creature takes the bobber only where every bobber of
         * that spread meets it (nothing behind it is met then); one the flight passes within its spread of hides nothing
         * behind it, and one shrunk to nothing by it (far out, a small one) never does.
         */
        fun blocked(steps: List<Step>, boxes: List<Pair<AABB, Boolean>>): Boolean {
            var flown = 0.0
            for (s in steps) {
                val len = s.from.distanceTo(s.to)
                val pieces = maxOf(1, ceil(len / PIECE).toInt())
                for (k in 0 until pieces) {
                    val from = s.from.lerp(s.to, k.toDouble() / pieces)
                    val to = s.from.lerp(s.to, (k + 1.0) / pieces)
                    val far = flown + len * (k + 1) / pieces
                    var best = Double.POSITIVE_INFINITY
                    var own = true
                    for ((box, mine) in boxes) {
                        val b = widened(box, margin(mine, far)) ?: continue
                        val d = if (b.contains(from)) 0.0 else b.clip(from, to).map { it.distanceTo(from) }.orElse(null) ?: continue
                        if (d < best) {
                            best = d
                            own = mine
                        }
                    }
                    if (best.isFinite()) return ! own
                }
                flown += len
            }
            return false
        }

        /** [box] widened by [m] on every side, shrunk where [m] is negative; null when that leaves nothing (AABB would swap its sides back). */
        fun widened(box: AABB, m: Double): AABB? {
            if (m < 0 && (box.xsize <= - 2 * m || box.ysize <= - 2 * m || box.zsize <= - 2 * m)) return null
            return box.inflate(m)
        }

        /**
         * A small re-aim of yours from [view] that [ok] takes (it casts into home's water, its flight clear): a size drawn
         * from yours (an angle of the view, a yaw step wider by the pitch's narrowing) in a direction drawn all round,
         * [REAIM_TRIES] draws at most; null when none was taken.
         */
        fun reaim(view: View, rng: Random, ok: (View) -> Boolean): View? {
            repeat(REAIM_TRIES) {
                val amp = TimingModel.lognormal(REAIM_P50, REAIM_SIGMA, rng, REAIM_MAX)
                if (amp < REAIM_MIN) return@repeat
                val a = rng.nextDouble() * 2 * PI
                val pitch = view.pitch + amp * sin(a)
                if (abs(pitch) > 90.0) return@repeat
                val narrow = cos(Math.toRadians((view.pitch + pitch) / 2))
                if (narrow < MIN_NARROW) return@repeat
                val v = View(view.yaw + amp * cos(a) / narrow, pitch)
                if (ok(v)) return v
            }
            return null
        }

        /** Right above or below you a yaw step turns the view next to nothing: no re-aim widens it past this. */
        private const val MIN_NARROW = 0.1
    }

    /** Which of your look phases Auto Fishing's own looks around take. Pure, so it can be unit tested. */
    internal object LookPhase {
        /**
         * The phase of your looks around ([GlancePolicy.Phase]) Auto Fishing's own looks take in its [phase] (null: none
         * may start): waiting for a bite or reeling one, your waiting looks, also with creatures of yours alive that
         * nobody fights (the killer off, or one it leaves alone): with yours alive, the rod in hand, the line out and no
         * attack of yours within 2 s you looked around as often as waiting with none alive (18.4 in-place min, 11 looks,
         * 0.60 a minute, against 0.53), not as seldom as in a fight the bobber or an area weapon decides (0.20); until the
         * cast after a fight ([afterKill]: it ended within 3 s) your looks after a kill. Your own fights at the water take
         * the waiting ones too: your looks in aimed fights from your fishing view come as often (0.48-0.51 a minute against
         * 0.53), and only waiting looks keep to it (none from a view you turned onto a creature, none chained from one that
         * stayed away).
         */
        fun of(phase: Phase, afterKill: Boolean): GlancePolicy.Phase? = when (phase) {
            Phase.WAITING_BITE, Phase.REELING -> GlancePolicy.Phase.WAITING
            Phase.CASTING -> if (afterKill) GlancePolicy.Phase.AFTER_KILL else GlancePolicy.Phase.WAITING
            Phase.IDLE -> null
        }
    }

    /**
     * Whether a screen stops Auto Fishing: any with Pause In Menus on ([pauseInMenus]), and a container screen
     * ([container]: a chest, the SkyBlock menu, your inventory) always, since no player can use a rod while one is open
     * and the server knows it is. Pure, so it can be unit tested.
     */
    internal object ScreenPause {
        fun pauses(pauseInMenus: Boolean, container: Boolean) = pauseInMenus || container
    }

    /**
     * Shake Head's idle re-aims (DESIGN-HUMAN §4.2): about every 40 s a view that drifted [MIN_DEG] to [MAX_DEG] from your
     * fishing view turns back towards it, to a goal a little short of it (the motor gets there in its own strokes, so it
     * never lands on it exactly). Pure, so it can be unit tested.
     */
    internal object IdleReaim {
        /** A view this far from home is re-aimed... */
        const val MIN_DEG = 2.0
        /** ...up to this far: a view farther away is one you turned to, not a drift. */
        const val MAX_DEG = HumanAim.IDLE_ANCHOR_DEG
        private const val SHORT_P50 = 1.0
        private const val SHORT_SIGMA = 0.6
        private const val SHORT_MAX = 3.0

        /** Time to the next re-aim: a lognormal, p50 40 s. */
        fun interval(rng: Random) = TimingModel.draw(Action.IDLE_REAIM, emptyList(), null, rng)

        /**
         * The periodic re-aim at [now] while waiting for a bite, due at [dueAt] (-1: none drawn yet): when the next one is
         * due, and whether one starts now. One that fell due by the cast at [castAt] (while Auto Fishing was held, reeling
         * or casting) is drawn afresh instead of going out with the cast. One due while your mouse turned the view
         * ([userTurned]), or with the view more than [MAX_DEG] from home ([distance], null without a home), is skipped. One
         * due behind a screen of yours, or before your reaction to its close passed ([moveReady]), waits for that reaction,
         * never the first tick after the close by itself.
         */
        fun schedule(now: Long, dueAt: Long, castAt: Long, distance: Double?, userTurned: Boolean, rng: Random, moveReady: Boolean = true): Pair<Long, Boolean> {
            if (dueAt < 0 || dueAt <= castAt) return now + interval(rng) to false
            if (now < dueAt || ! moveReady) return dueAt to false
            return now + interval(rng) to (distance != null && distance <= MAX_DEG && ! userTurned)
        }

        /**
         * The goal of a re-aim from [view] towards [home]: short of it by a lognormal p50 1° drawn under 3° and half the
         * way when that cast [lands] like home's (a goal; null: cannot tell), else by the same lognormal drawn under the
         * largest undershoot that still does ([BobberFlight.undershoot]): never on home itself. Null when the view is
         * less than [minDeg] from home.
         */
        fun goal(view: View, home: View, minDeg: Double, rng: Random, lands: (View) -> Boolean? = { null }): View? {
            val dist = MotorModel.angle(view.yaw, view.pitch, home.yaw, home.pitch)
            if (dist < minDeg) return null
            val cap = min(SHORT_MAX, dist / 2)
            val drawn = TimingModel.lognormal(SHORT_P50, SHORT_SIGMA, rng, cap)
            val short = BobberFlight.undershoot(drawn, { lands(MotorModel.towards(view.yaw, view.pitch, home.yaw, home.pitch, it)) }) {
                TimingModel.lognormal(SHORT_P50, SHORT_SIGMA, rng, min(cap, it))
            } ?: drawn
            return MotorModel.towards(view.yaw, view.pitch, home.yaw, home.pitch, short)
        }
    }

    /**
     * The check before a cast (the recast after a catch keeps its ticks, which wait for the turn): a cast from a view that
     * drifted or was turned more than [PRE_CAST_DEG] off your fishing view, whose bobber would not land in home's water or
     * lava ([BobberFlight]), turns home first: on Hypixel nearly every bobber hooks the server's armor stand, which the
     * check after a landing takes for a landing. Pure, so it can be unit tested.
     */
    internal object CastCheck {
        const val PRE_CAST_DEG = 2.0

        /**
         * Whether the view turns home before this cast: it is [distance] degrees from your fishing view (null without one)
         * and the cast from it [lands] elsewhere (false; null cannot tell and casts, as without the check). Null when the
         * check cannot act now: the view may not be turned ([allowed]: a screen, the Recorder, the killer's target) or your mouse is turning it
         * ([userTurned]); the cast is then not held for it, and the check is not used up.
         */
        fun turnsHome(distance: Double?, allowed: Boolean, userTurned: Boolean, lands: () -> Boolean?): Boolean? =
            if (! allowed || userTurned) null else distance != null && distance > PRE_CAST_DEG && lands() == false

        /**
         * Whether the recast after a catch turns home first, checked once per recast as soon as it can act (null as for
         * [turnsHome]): as [turnsHome], but when the flight cannot tell only a view within
         * [AutoMobKiller.FightRules.FLUID_FALLBACK_DEG] casts. The view is home or near it after the reel, unless something
         * left it away (a look around given up without its return, a fight's end), and the look's cast guard no longer
         * holds that cast. With the Fishing Recorder on nothing is turned at all ([CastAim]): it never acts then.
         */
        fun recastTurnsHome(distance: Double?, allowed: Boolean, userTurned: Boolean, lands: () -> Boolean?): Boolean? =
            if (! allowed || userTurned) null else distance != null && distance > PRE_CAST_DEG && ! AutoMobKiller.FightRules.landsInFluid(lands(), distance)

        /**
         * A look around holds the cast: its move is in flight ([moving]), or it keeps the view away ([out]) [distance]
         * degrees from your fishing view (null without one: nothing to hold it for) where the cast does not land in its
         * water or lava ([lands], within [AutoMobKiller.FightRules.FLUID_FALLBACK_DEG] when the flight cannot tell).
         */
        fun glanceHolds(out: Boolean, moving: Boolean, distance: Double?, lands: () -> Boolean?) =
            moving || out && distance != null && ! AutoMobKiller.FightRules.landsInFluid(lands(), distance)

        /**
         * A move's slow tail to rest ([settling]: in flight, no longer turning the view the way the learner sees a move,
         * [HumanAim.turning]) holds the cast unless the bobber from the view now and from where it comes to rest lands like
         * one from your fishing view ([lands]: null without one, which holds it too). A cast never waits for a tail that
         * changes nothing, and never goes from one that would throw it off home's water.
         */
        fun tailHolds(settling: Boolean, lands: () -> Boolean?) = settling && lands() != true

        /**
         * A turn home the check asked for ([turn]: [turnsHome], [recastTurnsHome]) waits at a tick when your reaction to a
         * screen of yours closing has not passed ([moveReady]): Auto Fishing's own moves start no sooner than yours did
         * after a close; the cast or recast waits with it and the check is not used up.
         */
        fun waitsForScreen(turn: Boolean?, moveReady: Boolean) = turn == true && ! moveReady

        /**
         * How a move of Auto Fishing's own holds the cast or recast at a tick ([HeldUse]), null when none does: an idle move
         * turning the view ([idleMoving]) holds it owed when it is [due] by its own clock or the move turns home for it
         * ([forCast]), so it goes a wait of yours after that move's rest; the turn home after a missed cast that has not
         * started yet ([turnOwed]: your reaction to that reel, or to a screen's close, still runs) holds it owing nothing,
         * so with no turn after all it goes on its own clock, never a wait after a rest that never came.
         */
        fun moveHold(idleMoving: Boolean, turnOwed: Boolean, due: Boolean, forCast: Boolean): Boolean? = when {
            idleMoving -> due || forCast
            turnOwed -> false
            else -> null
        }

        /**
         * The view turns home after a missed cast at all: it is [distance] degrees from your fishing view (null without
         * one), at least the least turn home ([MISS_REAIM_DEG]); nearer, nothing waits for a turn that never starts.
         */
        fun missTurnNeeded(distance: Double?) = distance != null && distance >= MISS_REAIM_DEG
    }

    /**
     * When Auto Fishing may turn your view. [free]: no screen, nothing holds Auto Fishing, you are alive and the Fishing
     * Recorder is off (with it on Auto Fishing never turns the view). Its own idle motion (Shake Head, re-aims) also needs no
     * fight of the killer's going on ([killerBusy]). A turn home for the cast or recast it sends anyway only needs no move
     * of the killer's fight in flight ([killerMoving]) and no target of the killer's fought ([killerAims]): a fight phase
     * that leaves Auto Fishing to fish before or after its targets (REACT waiting for this very recast, the end of a fight
     * only a look around kept open) lets that cast go out, so it must not go out from a view off the water; a move of the
     * killer that starts replaces the turn and holds Auto Fishing. While a rod fight's target is fought the view is its
     * plan's: a turn home would pull the crosshair off the creature and the plan would turn back to it, so the cast goes
     * out from that view unchecked, as it did before the check. Pure, so it can be unit tested.
     */
    internal object CastAim {
        /** Auto Fishing may turn the view home for its own cast or recast. */
        fun allowed(free: Boolean, killerMoving: Boolean, killerAims: Boolean) = free && ! killerMoving && ! killerAims

        /** Auto Fishing may move the view by itself. */
        fun motion(free: Boolean, killerMoving: Boolean, killerBusy: Boolean) = free && ! killerMoving && ! killerBusy

        /** An idle move in flight goes on: Auto Fishing may move the view ([motion]), or the move turns home for its cast ([forCast]) and still may ([castAim]). */
        fun goesOn(motion: Boolean, forCast: Boolean, castAim: Boolean) = motion || forCast && castAim

        /** How an idle move that may not go on ends. */
        enum class Halt {
            NONE,
            /** Brought to rest, decelerating: a fight or a hold took over, your hand stops turning. */
            STOP,
            /** Frozen at once: a screen, the Recorder or your death, where nothing may turn the view. */
            ABORT,
        }

        /**
         * What happens to the idle move in flight ([moving], already braking when [stopping]) that may not go on
         * ([goesOn]): [Halt.ABORT] when the view is [frozen], else [Halt.STOP] once (a move braking is left to come to
         * rest).
         */
        fun halt(goesOn: Boolean, moving: Boolean, stopping: Boolean, frozen: Boolean) = when {
            goesOn || ! moving -> Halt.NONE
            frozen -> Halt.ABORT
            stopping -> Halt.NONE
            else -> Halt.STOP
        }
    }

    /**
     * Ends a move of Auto Fishing's own ([owner]: an idle move or a look around) that may not go on, as [CastAim.halt]
     * decides: at once ([CastAim.Halt.ABORT]), or brought to rest ([CastAim.Halt.STOP]) once your hand reacted to what
     * ended it, a wait drawn in the first tick that held (a hold that lets go meanwhile draws anew next time); until then
     * the move goes on. Yours went on like that: past a creature coming up (the 20 moves of yours turning at a spawn ran
     * on 50-454 ms after it, none stopped in its tick), and through your /loadout (you turned the view from 6 ms before to
     * 208 ms after sending it, until its menu opened 147-488 ms later). No game state, so the call path is unit tested
     * on a [HumanAim].
     */
    internal class MoveHalt(private val owner: Owner) {
        private val reaction = AutoMobKiller.Reaction()

        /** This tick at [now] on [aim]: the move ends as [halt] says, the reaction [draw]n when it is first to stop; true when it ended or started braking now. */
        fun tick(aim: HumanAim, now: Long, halt: CastAim.Halt, draw: () -> Long): Boolean {
            if (halt != CastAim.Halt.STOP) reaction.clear()
            return when (halt) {
                CastAim.Halt.NONE -> false
                CastAim.Halt.ABORT -> true.also { aim.abort(owner) }
                CastAim.Halt.STOP -> reaction.ready(now, true, draw).also { due ->
                    if (due) {
                        reaction.clear()
                        aim.stop(owner)
                    }
                }
            }
        }

        fun clear() = reaction.clear()
    }

    /**
     * A move of Auto Fishing's own (an idle move, a look around) still turning when Auto Fishing was turned off (your key,
     * a rare catch, a warp): your hand goes on until its reaction to that, then brings it to rest, as the killer's moves
     * do then; at once where nothing may turn the view (a screen, the Fishing Recorder, your death) or the stop came with
     * a world change. Ticked while Auto Fishing is off; once the move ended, braked, or another one took the view there
     * is nothing left to do. No game state, so the call path is unit tested on a [HumanAim].
     */
    internal class OffHalt {
        private var owner: Owner? = null
        private var started = - 1
        private var dueAt = Long.MAX_VALUE

        /** A move waits for your hand's reaction. */
        val pending get() = owner != null

        /** Auto Fishing stopped at [now]: its move in flight on [aim] ends at once ([atOnce]) or after your hand's [reaction]. */
        fun arm(aim: HumanAim, now: Long, atOnce: Boolean, reaction: () -> Long) {
            clear()
            val o = OWNERS.firstOrNull { aim.moving(it) } ?: return
            if (atOnce) return aim.abort(o)
            // braking already: it comes to rest by itself
            if (aim.stopping) return
            owner = o
            started = aim.started
            dueAt = TimingModel.dueAt(now, reaction())
        }

        /** One tick at [now] while Auto Fishing is off: the move stops dead once the view is [frozen], else comes to rest at its time. */
        fun tick(aim: HumanAim, now: Long, frozen: Boolean) {
            val o = owner ?: return
            if (! aim.moving(o) || aim.started != started || aim.stopping) return clear()
            if (frozen) aim.abort(o)
            else if (now >= dueAt) aim.stop(o)
            else return
            clear()
        }

        fun clear() {
            owner = null
            started = - 1
            dueAt = Long.MAX_VALUE
        }

        private companion object {
            val OWNERS = listOf(Owner.IDLE, Owner.GLANCE)
        }
    }
}

/**
 * A reel, cast or recast of Auto Fishing's that came due while something held it back (a move of the view it waits for,
 * a screen that paused it) goes out a wait of yours after what held it ended (that move's rest, the screen's close),
 * never in the tick it ended: your casts due while your view moved came 101-2153 ms after its rest, none sooner. One due
 * by its own clock a moment after such an end (sooner than your shortest gap after it) waits the same. Nothing held it:
 * it goes when due. No game state, so it is unit tested.
 */
internal class HeldUse {
    /** The first tick it was due while held ([NONE]: nothing owed). */
    private var since = NONE
    /** When it goes, drawn at the first tick nothing held it ([NONE]: not yet), and the end that wait runs from. */
    private var at = NONE
    private var from = Long.MIN_VALUE
    /** The end last seen ([go]'s endId) and when: one end is one moment, however its time reads later. */
    private var seenId = Long.MIN_VALUE
    private var seenEnd = Long.MIN_VALUE

    /** It came due while held and waits for its time. */
    val owed get() = since != NONE

    /** When it goes once drawn; null before that or with nothing owed. */
    val goesAt: Long? get() = at.takeIf { it != NONE }

    /** Something holds it back at [now]; [due]: it is due by its own clock. A time drawn before is dropped: it is held again. */
    fun hold(now: Long, due: Boolean) {
        if (due && since == NONE) since = now
        at = NONE
    }

    /**
     * Nothing holds it back at [now], and what held it last ended at [endedAt], the first tick that showed that end (your
     * recordings time a rest from the tick that shows it, a move's last moving tick): whether it goes now. Due by its own
     * clock ([due]) and owed nothing it goes, unless that end is less than [floorMs] ago; owed (or due that soon after the
     * end) it goes a [wait] after that end, or after [now] when nothing of its own ended since it was held (a look given up
     * where it stood), drawn once, and never sooner than [floorMs] after it (the tick its time rounds to included); another
     * end meanwhile (a move that held nothing came to rest) times it anew from that one, so it never goes right after a
     * rest either. [endId]: which end that is (a move's rest, [HumanAim.rests]; by default its time).
     */
    fun go(now: Long, due: Boolean, endedAt: Long, floorMs: Long, endId: Long = endedAt, wait: () -> Long): Boolean {
        if (endId != seenId) {
            seenId = endId
            seenEnd = endedAt
        }
        val end = seenEnd
        if (since == NONE) {
            if (! due) return false
            if (now - end >= floorMs) return true
            since = now
        }
        if (at == NONE || end > from && now - end < floorMs) {
            from = if (end >= since || now - end < floorMs) end else now
            at = maxOf(TimingModel.dueAt(from, wait()), from + floorMs)
        }
        if (now < at) return false
        clear()
        return true
    }

    fun clear() {
        since = NONE
        at = NONE
        from = Long.MIN_VALUE
    }

    companion object {
        const val NONE = Long.MAX_VALUE
    }
}

/**
 * Your hand switching buttons ([TimingModel.Action.CROSS_L_TO_R], [TimingModel.Action.CROSS_R_TO_L]): Auto Fishing's rod
 * click and the killer's click otherwise put each other off by exactly one tick. A click whose own clock started after the
 * other button's last click is timed by that clock alone: yours were measured from there with the switch inside (your
 * first hits from the recast or the spawn, your reels from the bite, your recasts from the reel), so a switch on top
 * would count it twice. One whose clock started before it was due on that clock with the hand on the other button: it
 * goes one of your switches to a click already due after that click, drawn once per such click to fall after its own due
 * time (never the later of two waits). No game state, so it is unit tested.
 */
internal class CrossGap {
    /** The other button's click the switch was drawn for, and when this one may go after it. */
    private var other = Long.MIN_VALUE
    private var at = Long.MIN_VALUE

    /**
     * This button may click from then on ([Long.MIN_VALUE]: whenever its own clock says): the other one's last click went
     * out at [otherAt] (0 or less: none yet), this click's own clock started at [from] and has it due at [dueAt]. A switch
     * is [draw]n once per other click, again until it falls after [dueAt] ([REDRAW_TRIES] tries: none does, your switch
     * would have come by then, no wait).
     */
    fun notBefore(otherAt: Long, from: Long, dueAt: Long, draw: () -> Long): Long {
        if (otherAt <= 0L || otherAt <= from) return Long.MIN_VALUE
        if (otherAt != other) {
            other = otherAt
            at = Long.MIN_VALUE
            for (k in 1 .. REDRAW_TRIES) {
                val a = TimingModel.dueAt(otherAt, draw())
                if (a > dueAt) {
                    at = a
                    break
                }
            }
        }
        return at
    }

    companion object {
        const val REDRAW_TRIES = 32
    }
}

/**
 * Times the recast after a catch in client ticks from the reel click, the way a held use button repeats (vanilla
 * uses the item again on the 4th tick, 200 ms). It holds no game state, so it can be unit tested.
 */
internal class RecastTimer {
    private var ticksLeft = 0
    /** A hold stopped the count before the recast was due: from then on it is due at its time by the wall clock. */
    private var byClock = false

    /** A recast is waiting for its tick. */
    val pending get() = ticksLeft > 0

    /** Starts counting on the tick of the reel click: [delayMs] rounded to whole ticks, at least one. */
    fun start(delayMs: Long) {
        ticksLeft = ticks(delayMs)
        byClock = false
    }

    fun cancel() {
        ticksLeft = 0
        byClock = false
    }

    /**
     * Something held the recast (a move of the view) before it was due: its ticks no longer count, or they would resume
     * where the hold left them and send it a fixed number of ticks after the hold's end; it is due at its time instead
     * ([tick]'s dueAt), and one due that soon after a rest waits your wait after that rest ([HeldUse]).
     */
    fun held() {
        if (ticksLeft > 0) byClock = true
    }

    /** Counts one tick at [now]; true on the tick the recast is due: its own tick, or after a hold the first tick at [dueAt] or later. */
    fun tick(now: Long = Long.MIN_VALUE, dueAt: Long = Long.MAX_VALUE): Boolean {
        if (ticksLeft <= 0) return false
        if (! byClock) return -- ticksLeft == 0
        if (now < dueAt) return false
        ticksLeft = 0
        return true
    }

    companion object {
        private const val MS_PER_TICK = 50L

        fun ticks(delayMs: Long) = ((delayMs + MS_PER_TICK / 2) / MS_PER_TICK).toInt().coerceAtLeast(1)
    }
}

//#endif
