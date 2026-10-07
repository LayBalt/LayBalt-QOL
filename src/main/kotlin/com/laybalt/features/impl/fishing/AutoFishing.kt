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
import com.laybalt.event.impl.RenderWorldEvent
import com.laybalt.event.impl.TickEvent
import com.laybalt.event.impl.WorldChangeEvent
import com.laybalt.features.Feature
import com.laybalt.ui.notification.NotificationManager
import com.laybalt.utils.ChatUtils
import com.laybalt.utils.ChatUtils.unformattedText
import com.laybalt.utils.PlayerUtils
import com.laybalt.utils.MathUtils
import com.mojang.blaze3d.platform.InputConstants
import gg.essential.universal.UMinecraft
import net.minecraft.client.player.LocalPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.projectile.FishingHook
import net.minecraft.world.item.Items
import kotlin.random.Random

/**
 * Re-implementation of the old LayBalt "Auto Fishing" as a tick-driven state machine.
 *
 * Fixes of the legacy version: only reacts to the bite marker of the player's own bobber, never casts twice per bite,
 * pauses (instead of turning off) while a menu is open, never touches the mouse sensitivity (Lock Mouse cancels
 * look input through [com.laybalt.mixin.MixinMouseHandlerAutoFishing]), never right-clicks without a rod in hand, never
 * starts by itself after a restart and turns itself off on death, warp and world change.
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
        .withDescription("Pauses while any screen (chat, inventory, menus) is open and resumes when it closes. Off = keeps fishing with menus open.")
    private val shakeHead by ToggleSetting("Shake Head", true)
        .withDescription("Slightly moves your head every 20-30 seconds.")
    private val lockMouse by ToggleSetting("Lock Mouse", false)
        .withDescription("Ignores mouse look input while Auto Fishing is on, so you cannot accidentally turn away from the water. Your sensitivity setting is not changed.")
    private val toggleKey by KeybindSetting("Toggle Key")
        .withDescription("Turns Auto Fishing on or off. Works in game only (not while a screen is open). Unbound by default.")

    /** Set by other features (e.g. Auto Mob Killer) to hold Auto Fishing; it resumes by casting again. */
    @JvmField var paused = false

    private enum class Phase { IDLE, WAITING_BITE, REELING, CASTING }

    private var phase = Phase.IDLE
    private var nextActionAt = 0L
    private var castAt = 0L
    private var reeledAt = 0L
    private var reelRetries = 0
    private var nextShakeAt = 0L

    private var shakeStartedAt = - 1L
    private var shakeYaw = 0f
    private var shakePitch = 0f
    private var shakeAppliedYaw = 0f
    private var shakeAppliedPitch = 0f

    private const val SHAKE_TIME = 500L
    private const val CAST_GRACE = 1500L
    private const val REEL_RETRY_AFTER = 2000L
    private const val MAX_REEL_RETRIES = 3
    private const val BITE_IGNORE_AFTER_CAST = 300L

    private val deathMessages = listOf(
        "fell into the void", "was thrown into the void by", "was slain by", "burned to death", "fell to death",
        "fell to their death with help from", "suffocated.", "drowned.", "was pricked to death by a cactus.", "died.", "were killed by"
    )

    /** Read by [com.laybalt.mixin.MixinMouseHandlerAutoFishing]: true while mouse look input must be ignored. */
    @JvmStatic
    fun shouldLockMouse() = enabled && lockMouse.value && mc.player != null

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

        register<TickEvent.Start> { onTick() }

        register<RenderWorldEvent> { updateShake() }

        register<ChatMessageEvent> {
            val text = event.unformattedText
            if (text.trim().startsWith("Sending to server")) return@register disable("Warped")
            if (isOwnDeathMessage(text)) disable("Killed: ${text.trim()}")
        }

        register<WorldChangeEvent> { disable("World changed") }
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
        resetState()
        super.onEnable()
    }

    override fun onDisable() {
        super.onDisable()
        resetState()
    }

    private fun announce() = ChatUtils.modMessage("§7AutoFishing is now ${if (enabled) "§aon" else "§coff"}")

    private fun disable(reason: String) {
        if (! enabled) return
        super.toggle()
        ChatUtils.modMessage("§7AutoFishing is now §coff §8[§c$reason§8]")
    }

    private fun resetState() {
        phase = Phase.IDLE
        nextActionAt = 0L
        castAt = 0L
        reeledAt = 0L
        reelRetries = 0
        shakeStartedAt = - 1L
        nextShakeAt = System.currentTimeMillis() + randomShakeInterval()
    }

    private fun onTick() {
        val p = mc.player ?: return
        val now = System.currentTimeMillis()

        if (paused || p.isDeadOrDying) {
            phase = Phase.IDLE
            return
        }

        val screenOpen = UMinecraft.currentScreenObj != null
        if (screenOpen && stopOnGui.value) return

        if (p.mainHandItem.item != Items.FISHING_ROD) {
            phase = Phase.IDLE
            return
        }

        when (phase) {
            Phase.IDLE -> {
                if (p.fishing != null) {
                    phase = Phase.WAITING_BITE
                    castAt = now
                }
                else {
                    phase = Phase.CASTING
                    nextActionAt = now + castDelay.value
                    reeledAt = now
                }
            }

            Phase.WAITING_BITE -> {
                val hook = p.fishing
                if (hook == null) {
                    // The hook can take a moment to appear client-side after a cast; after that it is really gone.
                    if (now - castAt > CAST_GRACE) {
                        phase = Phase.CASTING
                        nextActionAt = now + randomized(castDelay.value)
                        reeledAt = now
                    }
                    return
                }

                if (now - castAt > BITE_IGNORE_AFTER_CAST && hasBite(p, hook)) {
                    phase = Phase.REELING
                    nextActionAt = now + randomized(reelDelay.value)
                }
                else if (now - castAt > recastTimeout.value * 1000L) reel(now)
            }

            Phase.REELING -> {
                if (p.fishing == null) {
                    phase = Phase.CASTING
                    nextActionAt = now + randomized(castDelay.value)
                    reeledAt = now
                }
                else if (now >= nextActionAt) reel(now)
            }

            Phase.CASTING -> {
                if (p.fishing != null) {
                    // Server lag: the bobber is still out a while after reeling - reel again, then give up and wait for a bite.
                    if (now - reeledAt <= REEL_RETRY_AFTER) return
                    if (reelRetries < MAX_REEL_RETRIES) {
                        reelRetries ++
                        reeledAt = now
                        useRod()
                    }
                    else {
                        phase = Phase.WAITING_BITE
                        castAt = now
                    }
                    return
                }

                if (now >= nextActionAt) {
                    useRod()
                    phase = Phase.WAITING_BITE
                    castAt = now
                }
            }
        }

        if (shakeHead.value && now >= nextShakeAt && phase != Phase.REELING && shakeStartedAt < 0 && ! screenOpen) startShake(p, now)
    }

    private fun reel(now: Long) {
        useRod()
        phase = Phase.CASTING
        nextActionAt = now + randomized(castDelay.value)
        reeledAt = now
        reelRetries = 0
    }

    /** One right click with the rod. Only ever called while the main hand holds a fishing rod. */
    private fun useRod() {
        val p = mc.player ?: return
        if (p.mainHandItem.item != Items.FISHING_ROD) return

        if (UMinecraft.currentScreenObj == null) PlayerUtils.rightClick()
        else {
            // Simulated key clicks are only processed without a screen; use the item directly instead.
            val gameMode = mc.gameMode ?: return
            if (gameMode.useItem(p, InteractionHand.MAIN_HAND).consumesAction()) p.swing(InteractionHand.MAIN_HAND)
        }
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

    private fun startShake(p: LocalPlayer, now: Long) {
        shakeYaw = Random.nextDouble(- 1.5, 1.5).toFloat()
        shakePitch = Random.nextDouble(- 1.5, 1.5).toFloat()
        if (p.xRot + shakePitch !in - 90f .. 90f) shakePitch = - shakePitch
        shakeAppliedYaw = 0f
        shakeAppliedPitch = 0f
        shakeStartedAt = now
        nextShakeAt = now + randomShakeInterval()
    }

    /** Runs every frame on the render (client) thread; adds the remaining part of the shake on top of the current rotation. */
    private fun updateShake() {
        if (shakeStartedAt < 0) return
        val p = mc.player ?: return run { shakeStartedAt = - 1L }

        val progress = ((System.currentTimeMillis() - shakeStartedAt).toDouble() / SHAKE_TIME).coerceIn(0.0, 1.0)
        val eased = if (progress < 0.5) 2 * progress * progress else 1 - (- 2 * progress + 2).let { it * it } / 2
        val wantYaw = (shakeYaw * eased).toFloat()
        val wantPitch = (shakePitch * eased).toFloat()

        val beforeYaw = p.yRot
        val beforePitch = p.xRot
        PlayerUtils.rotate(beforeYaw + (wantYaw - shakeAppliedYaw), MathUtils.normalizePitch(beforePitch + (wantPitch - shakeAppliedPitch)))
        shakeAppliedYaw += p.yRot - beforeYaw
        shakeAppliedPitch += p.xRot - beforePitch

        if (progress >= 1.0) shakeStartedAt = - 1L
    }

    private fun randomized(base: Int) = base.toLong() + if (delayVariance.value > 0) Random.nextLong(0, delayVariance.value + 1L) else 0L

    private fun randomShakeInterval() = Random.nextLong(20_000, 30_001)
}

//#endif
