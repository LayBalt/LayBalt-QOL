package com.laybalt.features.impl.rift

//#if CHEAT

import com.laybalt.LayBaltQOL
import com.laybalt.config.types.KeybindSetting
import com.laybalt.config.types.SliderSetting
import com.laybalt.config.types.ToggleSetting
import com.laybalt.event.EventBus
import com.laybalt.event.impl.KeyboardEvent
import com.laybalt.event.impl.MainThreadPacketReceivedEvent
import com.laybalt.event.impl.MouseClickEvent
import com.laybalt.event.impl.RenderWorldEvent
import com.laybalt.event.impl.TickEvent
import com.laybalt.event.impl.WorldChangeEvent
import com.laybalt.features.Feature
import com.laybalt.utils.ChatUtils
import com.laybalt.utils.ChatUtils.removeFormatting
import com.laybalt.utils.ChatUtils.unformattedText
import com.laybalt.utils.PlayerUtils
import com.laybalt.utils.ScoreboardUtils
import com.laybalt.utils.items.ItemUtils.skyblockId
import com.laybalt.utils.location.LocationUtils
import com.laybalt.utils.location.WorldType
import com.mojang.blaze3d.platform.InputConstants
import gg.essential.universal.UMinecraft
import net.minecraft.client.player.LocalPlayer
import net.minecraft.network.protocol.game.ClientboundClearTitlesPacket
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.phys.AABB

/**
 * Re-implementation of the old LayBalt "Vampire Slayer Helper" (VHelper + TitleChecker). Work in progress: the logic is
 * complete but untested against the current Hypixel texts, so the feature stays greyed out until it is reworked.
 *
 * Fixes of the legacy version: every setting is used (hotbar slots, per-action toggles, auto heal, auto Twinclaws);
 * Impel is read from the title/subtitle packets the moment it arrives (no 10-tick artificial delay, no HUD polling, no
 * stale subtitle firing when the fight gate opens); every action runs as its own tick-driven sequence on the client
 * thread (no shared tick counter, a new Impel cancels the running one and restores its keys/pitch); the pitch turns
 * smoothly instead of snapping; jumping and sneaking press the vanilla key mappings (toggle-sneak aware); the sidebar is
 * read from the cached [ScoreboardUtils] once per server tick; everything is aborted and released on world change,
 * disable and death.
 */
object VampireHelper: Feature("Helps with the Riftstalker Bloodfiend: reacts to Impel, heals with melon, uses Holy Ice on Twinclaws.") {
    override val wip = true

    private val swordSlot by SliderSetting("Sword Slot", 1, 1, 9, 1).section("Slots")
        .withDescription("Hotbar slot of your weapon. The helper switches back to it after healing or using Holy Ice.")
    private val melonSlot by SliderSetting("Healing Melon Slot", 2, 1, 9, 1)
        .withDescription("Hotbar slot of the Healing Melon, used by Auto Heal.")
    private val iceSlot by SliderSetting("Holy Ice Slot", 3, 1, 9, 1)
        .withDescription("Hotbar slot of the Holy Ice, used for Twinclaws.")

    private val autoJump by ToggleSetting("Auto Jump").section("Impel")
        .withDescription("Jumps when the boss casts \"Impel: JUMP\".")
    private val autoSneak by ToggleSetting("Auto Sneak")
        .withDescription("Sneaks briefly when the boss casts \"Impel: SNEAK\" (does nothing if you are already sneaking).")
    private val autoClickUp by ToggleSetting("Auto Click Up")
        .withDescription("Looks up, left clicks and looks back when the boss casts \"Impel: CLICK UP\".")
    private val autoClickDown by ToggleSetting("Auto Click Down")
        .withDescription("Looks down, left clicks and looks back when the boss casts \"Impel: CLICK DOWN\".")
    private val impelDelay by SliderSetting("Impel Reaction Delay", 2, 0, 10, 1, " ticks")
        .withDescription("Ticks to wait after the Impel title appears before reacting. 0 = react on the next tick.")
    private val rotationTime by SliderSetting("Rotation Time", 150, 0, 400, 10, "ms")
        .withDescription("How long turning up/down and back takes for the click actions. 0 = instant.")

    private val autoHeal by ToggleSetting("Auto Heal").section("Boss")
        .withDescription("Uses the Healing Melon when your health drops below the threshold during the boss fight (at most every 2 seconds).")
    private val healBelow by SliderSetting("Heal Below", 40, 10, 90, 5, "%")
        .withDescription("Health percentage below which Auto Heal uses the melon.")
        .showIf { autoHeal.value }
    private val autoTwinclaws by ToggleSetting("Auto Twinclaws")
        .withDescription("Uses the Holy Ice when the boss starts its Twinclaws attack.")
    private val twinclawsDelay by SliderSetting("Twinclaws Delay", 5, 0, 20, 1, " ticks")
        .withDescription("Ticks to wait after Twinclaws appears before using the Holy Ice.")
        .showIf { autoTwinclaws.value }

    private val toggleKey by KeybindSetting("Toggle Key")
        .withDescription("Turns Vampire Helper on or off. Works in game only (not while a screen is open). Unbound by default.")

    private const val HEAL_COOLDOWN = 2000L
    private const val SAME_IMPEL_WINDOW = 1000L
    private const val CLICK_HOLD_TICKS = 6
    private const val SNEAK_HOLD_TICKS = 6
    private const val JUMP_HOLD_TICKS = 2
    private const val TWINCLAWS_RADIUS = 2.5
    private const val BOSS_SEARCH_RADIUS = 20.0

    private val countdownSuffix = Regex("\\s+\\d+(?:\\.\\d+)?s$")

    private enum class Kind(val priority: Int) { IMPEL(2), TWINCLAWS(1), HEAL(0) }

    /** A tick-driven action: every step returns true when it is done; finished steps fall through in the same tick. */
    private class Sequence(val kind: Kind) {
        val steps = ArrayDeque<() -> Boolean>()
        val cleanups = mutableListOf<() -> Unit>()

        fun waitTicks(ticks: Int) {
            var left = ticks
            steps.add { if (left > 0) { left --; false } else true }
        }

        fun run(block: () -> Unit) = steps.add { block(); true }
        fun until(condition: () -> Boolean) = steps.add(condition)
        fun onAbort(block: () -> Unit) = cleanups.add(block)

        /** @return true when the sequence is finished. */
        fun tick(): Boolean {
            while (steps.isNotEmpty()) {
                if (! steps.first().invoke()) return false
                steps.removeFirst()
            }
            return true
        }
    }

    private var current: Sequence? = null
    private val pending = ArrayList<Sequence>()

    private var inBossFight = false
    private var lastImpel = ""
    private var lastImpelAt = 0L
    private var lastHealAt = 0L
    private var twinclawsActive = false

    // Smooth pitch rotation, advanced every frame and every tick.
    private var rotFrom = 0f
    private var rotTo = 0f
    private var rotStart = 0L
    private var rotDuration = 0L
    private var rotating = false

    override fun init() {
        registerToggleKey()

        register<TickEvent.Server> {
            inBossFight = LocationUtils.world == WorldType.Rift && ScoreboardUtils.getLines().any { it.unformattedText.contains("Slay the boss!") }
        }

        register<MainThreadPacketReceivedEvent.Pre> {
            val text = when (val packet = event.packet) {
                is ClientboundSetSubtitleTextPacket -> packet.text.unformattedText
                is ClientboundSetTitleTextPacket -> packet.text.unformattedText
                is ClientboundClearTitlesPacket -> return@register run { lastImpel = "" }
                else -> return@register
            }
            onTitle(text)
        }

        register<TickEvent.Start> { onTick() }

        register<RenderWorldEvent> { updateRotation() }

        register<WorldChangeEvent> { abortAll() }
    }

    override fun toggle() {
        super.toggle()
        if (! wip && mc.player != null && LayBaltQOL.isLoaded) ChatUtils.modMessage("§7VampireHelper is now ${if (enabled) "§aon" else "§coff"}")
    }

    override fun onDisable() {
        super.onDisable()
        abortAll()
    }

    /**
     * The toggle key has to work while the feature is off, so it is a global listener - but never while the feature is
     * WIP (the WIP guard only blocks feature-scoped listeners). Once `wip` is removed it works like Auto Fishing's key.
     */
    private fun registerToggleKey() {
        if (wip) return

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
    }

    // ---------------------------------------------------------------- Impel

    private fun onTitle(raw: String) {
        val text = raw.removeFormatting().trim().replace(countdownSuffix, "").trim()
        if (! text.startsWith("Impel:")) return

        // The title is re-sent while its countdown runs; react once per appearance.
        val now = System.currentTimeMillis()
        val repeated = text == lastImpel && now - lastImpelAt < SAME_IMPEL_WINDOW
        lastImpel = text
        lastImpelAt = now
        if (repeated || ! inBossFight) return

        val sequence = when (text) {
            "Impel: CLICK UP" -> if (autoClickUp.value) clickSequence(- 88f) else null
            "Impel: CLICK DOWN" -> if (autoClickDown.value) clickSequence(88f) else null
            "Impel: JUMP" -> if (autoJump.value) jumpSequence() else null
            "Impel: SNEAK" -> if (autoSneak.value) sneakSequence() else null
            else -> {
                LayBaltQOL.logger.info("[VampireHelper] unknown Impel text: \"$text\"")
                null
            }
        } ?: return

        start(sequence)
    }

    private fun clickSequence(targetPitch: Float) = Sequence(Kind.IMPEL).apply {
        var startPitch = 0f
        var turned = false
        waitTicks(impelDelay.value)
        run {
            startPitch = mc.player?.xRot ?: 0f
            turned = true
            startRotation(targetPitch)
        }
        onAbort {
            // Look back at once when the action is cut short (new Impel, fight over, disable).
            stopRotation()
            if (turned) mc.player?.let { PlayerUtils.rotate(it.yRot, startPitch) }
        }
        until { ! rotating }
        waitTicks(1)
        run { PlayerUtils.leftClick() }
        waitTicks(CLICK_HOLD_TICKS)
        run { startRotation(startPitch) }
        until { ! rotating }
    }

    private fun jumpSequence() = Sequence(Kind.IMPEL).apply {
        var pressed = false
        waitTicks(impelDelay.value)
        run {
            if (! mc.options.keyJump.isDown) {
                mc.options.keyJump.isDown = true
                pressed = true
            }
        }
        onAbort { if (pressed) mc.options.keyJump.isDown = false }
        waitTicks(JUMP_HOLD_TICKS)
        run { if (pressed) mc.options.keyJump.isDown = false }
    }

    private fun sneakSequence() = Sequence(Kind.IMPEL).apply {
        var pressed = false
        waitTicks(impelDelay.value)
        run {
            if (! mc.options.keyShift.isDown) {
                setSneak(true)
                pressed = true
            }
        }
        onAbort { if (pressed) setSneak(false) }
        waitTicks(SNEAK_HOLD_TICKS)
        run { if (pressed) setSneak(false) }
    }

    /** Toggle-sneak aware: a [net.minecraft.client.ToggleKeyMapping] in toggle mode flips on every `setDown(true)`. */
    private fun setSneak(down: Boolean) {
        val key = mc.options.keyShift
        if (key.isDown == down) return
        if (mc.options.toggleCrouch().get()) key.isDown = true
        else PlayerUtils.toggleSneak(down)
    }

    // ---------------------------------------------------------------- Heal / Twinclaws

    private fun onTick() {
        val player = mc.player ?: return abortAll()
        if (player.isDeadOrDying || player.health <= 0f) return abortAll()
        if (! inBossFight) {
            twinclawsActive = false
            if (current != null || pending.isNotEmpty()) abortAll()
            return
        }

        updateRotation()

        if (autoHeal.value) checkHeal(player)
        if (autoTwinclaws.value) checkTwinclaws(player) else twinclawsActive = false

        runSequences()
    }

    private fun checkHeal(player: LocalPlayer) {
        val now = System.currentTimeMillis()
        if (now - lastHealAt < HEAL_COOLDOWN || isQueued(Kind.HEAL)) return
        if (player.maxHealth <= 0f || player.health / player.maxHealth * 100f >= healBelow.value) return
        lastHealAt = now

        val slot = melonSlot.value - 1
        if (! isMelon(player.inventory.getItem(slot))) return ChatUtils.modMessage("§cVampireHelper: no Healing Melon in hotbar slot ${melonSlot.value}")
        start(useItemSequence(Kind.HEAL, slot, 0))
    }

    private fun checkTwinclaws(player: LocalPlayer) {
        val boss = findBossStand(player) ?: return run { twinclawsActive = false }
        val box = AABB(boss.x - TWINCLAWS_RADIUS, boss.y - TWINCLAWS_RADIUS, boss.z - TWINCLAWS_RADIUS, boss.x + TWINCLAWS_RADIUS, boss.y + TWINCLAWS_RADIUS, boss.z + TWINCLAWS_RADIUS)
        val claws = level.getEntitiesOfClass(ArmorStand::class.java, box) { it.customName?.unformattedText?.contains("TWINCLAWS") == true }.isNotEmpty()

        // Fire once per Twinclaws attack (rising edge), not every tick while the indicator is shown.
        if (claws && ! twinclawsActive && ! isQueued(Kind.TWINCLAWS)) {
            val slot = iceSlot.value - 1
            if (isHolyIce(player.inventory.getItem(slot))) start(useItemSequence(Kind.TWINCLAWS, slot, twinclawsDelay.value))
            else ChatUtils.modMessage("§cVampireHelper: no Holy Ice in hotbar slot ${iceSlot.value}")
        }
        twinclawsActive = claws
    }

    /** The boss nametag closest to the player's own "Spawned by: <name>" tag, else the closest Bloodfiend nametag. */
    private fun findBossStand(player: LocalPlayer): ArmorStand? {
        val stands = level.getEntitiesOfClass(ArmorStand::class.java, player.boundingBox.inflate(BOSS_SEARCH_RADIUS)) { it.hasCustomName() }
        val bosses = stands.filter { it.customName?.unformattedText?.contains("Bloodfiend") == true }
        if (bosses.isEmpty()) return null

        val ownTag = "Spawned by: ${player.gameProfile.name}"
        val spawnedBy = stands.firstOrNull { it.customName?.unformattedText?.contains(ownTag) == true }
        if (spawnedBy != null) bosses.minByOrNull { it.distanceToSqr(spawnedBy) }?.takeIf { it.distanceToSqr(spawnedBy) < 9.0 }?.let { return it }
        return bosses.minByOrNull { it.distanceToSqr(player) }
    }

    /** Switch to [slot], right click, switch back to the sword slot. */
    private fun useItemSequence(kind: Kind, slot: Int, delay: Int) = Sequence(kind).apply {
        var swapped = false
        waitTicks(delay)
        run {
            selectSlot(slot)
            swapped = true
        }
        onAbort { if (swapped) selectSlot(swordSlot.value - 1) }
        waitTicks(1)
        run { PlayerUtils.rightClick() }
        waitTicks(2)
        run { selectSlot(swordSlot.value - 1) }
    }

    private fun selectSlot(slot: Int) {
        val player = mc.player ?: return
        if (! Inventory.isHotbarSlot(slot)) return
        player.inventory.selectedSlot = slot
    }

    private fun isMelon(stack: ItemStack) = ! stack.isEmpty &&
        (stack.skyblockId.contains("MELON") || stack.item == Items.MELON_SLICE || stack.item == Items.GLISTERING_MELON_SLICE)

    private fun isHolyIce(stack: ItemStack) = ! stack.isEmpty &&
        (stack.skyblockId.contains("HOLY_ICE") || stack.hoverName.unformattedText.contains("Holy Ice", true))

    // ---------------------------------------------------------------- sequencing

    private fun isQueued(kind: Kind) = current?.kind == kind || pending.any { it.kind == kind }

    /** Higher priority preempts the running sequence (which is aborted cleanly); same kind replaces the pending one. */
    private fun start(sequence: Sequence) {
        pending.removeAll { it.kind == sequence.kind }
        val running = current
        if (running != null && (sequence.kind.priority > running.kind.priority || sequence.kind == running.kind)) {
            abort(running)
            current = null
        }
        pending.add(sequence)
        pending.sortByDescending { it.kind.priority }
    }

    private fun runSequences() {
        repeat(2) {
            val sequence = current ?: pending.removeFirstOrNull()?.also { current = it } ?: return
            if (! sequence.tick()) return
            current = null
        }
    }

    private fun abort(sequence: Sequence) = sequence.cleanups.forEach { runCatching(it) }

    private fun abortAll() {
        current?.let(::abort)
        resetState()
    }

    private fun resetState() {
        current = null
        pending.clear()
        rotating = false
        twinclawsActive = false
        lastImpel = ""
    }

    // ---------------------------------------------------------------- rotation

    private fun startRotation(targetPitch: Float) {
        val player = mc.player ?: return
        rotFrom = player.xRot
        rotTo = targetPitch.coerceIn(- 90f, 90f)
        rotStart = System.currentTimeMillis()
        rotDuration = rotationTime.value.toLong()
        rotating = true
        updateRotation()
    }

    private fun stopRotation() {
        rotating = false
    }

    private fun updateRotation() {
        if (! rotating) return
        val player = mc.player ?: return stopRotation()
        val progress = if (rotDuration <= 0) 1f else ((System.currentTimeMillis() - rotStart).toFloat() / rotDuration).coerceIn(0f, 1f)
        val eased = if (progress < 0.5f) 4f * progress * progress * progress else 1f - (- 2f * progress + 2f).let { it * it * it } / 2f
        PlayerUtils.rotate(player.yRot, rotFrom + (rotTo - rotFrom) * eased)
        if (progress >= 1f) rotating = false
    }
}

//#endif
