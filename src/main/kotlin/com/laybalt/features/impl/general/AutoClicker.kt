// Ported from OdinClient (https://github.com/skies-starred/OdinClient), BSD-3-Clause, Copyright (c) 2026 Starred
package com.laybalt.features.impl.general

//#if CHEAT

import com.laybalt.commands.CommandBuilder
import com.laybalt.config.ConfigManager
import com.laybalt.config.types.KeybindSetting
import com.laybalt.config.types.ListSetting
import com.laybalt.config.types.SliderSetting
import com.laybalt.config.types.ToggleSetting
import com.laybalt.event.impl.TickEvent
import com.laybalt.features.Feature
import com.laybalt.init.types.ICommandProvider
import com.laybalt.mixin.IKeyMapping
import com.laybalt.utils.ChatUtils
import com.laybalt.utils.PlayerUtils
import com.laybalt.utils.items.ItemUtils.itemUUID
import com.laybalt.utils.items.ItemUtils.skyblockId
import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.KeyMapping
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import org.lwjgl.glfw.GLFW

/**
 * Left / right auto clicker that clicks while a hold key is held, with optional per-button item whitelists
 * (`/lbqac add|remove left|right`, `/lbqac clear left|right|all`, `/lbqac list`).
 *
 * The old LayBalt left/right clickers (1 ms timer threads) are gone; NA's "Term AC" stays the dedicated Terminator clicker.
 */
object AutoClicker: Feature("Auto clicker for left click, right click or both, while a hold key is held."), ICommandProvider {
    private val whiteListOnly by ToggleSetting("Whitelist Only", false)
        .withDescription("Only clicks while holding a whitelisted item. Add the held item with /lbqac add left|right.")
    private val allowBreaking by ToggleSetting("Allow Breaking Blocks", false)
        .withDescription("While the left click key is held and you look at a block, holds attack down to mine it instead of spam clicking.")
    private val blockBreaker by ToggleSetting("Block Dungeon Breaker", true)
        .withDescription("Never clicks while holding the Dungeon Breaker.")
    private val terminatorOnly by ToggleSetting("Terminator Only", false)
        .withDescription("Only left clicks while holding the Terminator and right click. Skipped while Term AC is on, which already does this.")

    private val cps by SliderSetting("Clicks Per Second", 5.0, 3.0, 15.0, 0.5)
        .withDescription("Terminator clicks per second (about +-30 ms random jitter per click).")
        .showIf { terminatorOnly.value }

    private val enableLeftClick by ToggleSetting("Enable Left Click", true)
        .withDescription("Left clicks while the Left Click key is held.")
        .showIf { ! terminatorOnly.value }
    private val enableRightClick by ToggleSetting("Enable Right Click", true)
        .withDescription("Right clicks while the Right Click key is held.")
        .showIf { ! terminatorOnly.value }
    private val leftCps by SliderSetting("Left Clicks Per Second", 5.0, 3.0, 15.0, 0.5)
        .withDescription("Left clicks per second (about +-30 ms random jitter per click).")
        .showIf { ! terminatorOnly.value && enableLeftClick.value }
    private val rightCps by SliderSetting("Right Clicks Per Second", 5.0, 3.0, 15.0, 0.5)
        .withDescription("Right clicks per second (about +-30 ms random jitter per click).")
        .showIf { ! terminatorOnly.value && enableRightClick.value }
    private val leftClickKey by KeybindSetting("Left Click")
        .withDescription("Hold this key (or mouse button) to auto left click.")
        .showIf { ! terminatorOnly.value && enableLeftClick.value }
    private val rightClickKey by KeybindSetting("Right Click")
        .withDescription("Hold this key (or mouse button) to auto right click.")
        .showIf { ! terminatorOnly.value && enableRightClick.value }

    // Not shown in the click GUI (lists have no widget); edited with /lbqac and saved with the config.
    private val leftWhitelist by ListSetting<String>("Left Whitelist")
    private val rightWhitelist by ListSetting<String>("Right Whitelist")

    private var nextLeftClick = 0.0
    private var nextRightClick = 0.0
    private var holdingAttack = false

    override fun init() {
        register<TickEvent.Start> {
            val holdWanted = tickClicker()
            if (holdingAttack && ! holdWanted) releaseAttack()
        }
    }

    override fun onDisable() {
        super.onDisable()
        if (holdingAttack) releaseAttack()
    }

    /** @return true while the "hold attack to break" mode wants the attack key held down. */
    private fun tickClicker(): Boolean {
        if (mc.gui.screen() != null) return false
        val player = mc.player ?: return false
        if (player.isUsingItem) return false
        val now = System.currentTimeMillis()

        if (terminatorOnly.value) {
            if (TermAutoClicker.enabled) return false
            if (mc.gameMode?.isDestroying == true) return false
            if (player.mainHandItem.skyblockId != "TERMINATOR" || ! mc.options.keyUse.isDown) return false
            if (now < nextRightClick) return false
            nextRightClick = now + nextDelay(cps.value)
            PlayerUtils.leftClick()
            return false
        }

        if (blockBreaker.value && player.mainHandItem.skyblockId == "DUNGEONBREAKER") return false

        val held = heldKey()
        val leftAllowed = ! whiteListOnly.value || (held != null && held in leftWhitelist.value)
        val rightAllowed = ! whiteListOnly.value || (held != null && held in rightWhitelist.value)
        if (! leftAllowed && ! rightAllowed) return false

        val left = leftAllowed && enableLeftClick.value && leftClickKey.isDown()
        val right = rightAllowed && enableRightClick.value && rightClickKey.isDown()

        if (left && allowBreaking.value && lookingAtBlock()) {
            KeyMapping.set((mc.options.keyAttack as IKeyMapping).key, true)
            holdingAttack = true
            return true
        }

        if (mc.gameMode?.isDestroying == true) return false

        if (left && now >= nextLeftClick) {
            nextLeftClick = now + nextDelay(leftCps.value)
            PlayerUtils.leftClick()
        }

        if (right && now >= nextRightClick) {
            nextRightClick = now + nextDelay(rightCps.value)
            PlayerUtils.rightClick()
        }
        return false
    }

    private fun nextDelay(cps: Double) = 1000.0 / cps + (Math.random() - 0.5) * 60.0

    private fun lookingAtBlock(): Boolean {
        val level = mc.level ?: return false
        val hit = mc.hitResult as? BlockHitResult ?: return false
        if (hit.type != HitResult.Type.BLOCK) return false
        return ! level.getBlockState(hit.blockPos).isAir
    }

    /** Releases the attack key held by the breaking mode, unless the player is physically holding it. */
    private fun releaseAttack() {
        holdingAttack = false
        val key = (mc.options.keyAttack as IKeyMapping).key
        if (key.type == InputConstants.Type.MOUSE) {
            if (GLFW.glfwGetMouseButton(mc.window.handle(), key.value) == GLFW.GLFW_PRESS) return
        }
        else if (InputConstants.isKeyDown(mc.window, key.value)) return
        KeyMapping.set(key, false)
    }

    /** Whitelist key of the held item: its SkyBlock UUID, else its SkyBlock id, else its name. */
    private fun heldKey(): String? {
        val stack = mc.player?.mainHandItem ?: return null
        if (stack.isEmpty) return null
        return stack.itemUUID.ifEmpty { null } ?: stack.skyblockId.ifEmpty { null } ?: stack.hoverName.string
    }

    override fun CommandBuilder.command() {
        setName("lbqac", "lbqautoclicker")
        description("Manages the Auto Clicker item whitelists")
        runs { listWhitelists() }

        literal("add") {
            description("Whitelists the held item for the left or right auto clicker")
            literal("left") { runs { add(leftWhitelist.value, "left") } }
            literal("right") { runs { add(rightWhitelist.value, "right") } }
        }

        literal("remove") {
            description("Removes the held item from the left or right whitelist")
            literal("left") { runs { remove(leftWhitelist.value, "left") } }
            literal("right") { runs { remove(rightWhitelist.value, "right") } }
        }

        literal("clear") {
            description("Clears the left, right or both whitelists")
            literal("left") { runs { clear(leftWhitelist.value, "Left whitelist cleared.") } }
            literal("right") { runs { clear(rightWhitelist.value, "Right whitelist cleared.") } }
            literal("all") {
                runs {
                    leftWhitelist.value.clear()
                    clear(rightWhitelist.value, "All whitelists cleared.")
                }
            }
        }

        literal("list") {
            description("Shows both whitelists")
            runs { listWhitelists() }
        }
    }

    private fun add(list: MutableList<String>, side: String) {
        val item = heldKey() ?: return ChatUtils.modMessage("Hold an item to whitelist.")
        if (item in list) return ChatUtils.modMessage("\"$item\" is already in the $side whitelist.")
        list.add(item)
        ConfigManager.save()
        ChatUtils.modMessage("Added \"$item\" to the $side whitelist.")
    }

    private fun remove(list: MutableList<String>, side: String) {
        val item = heldKey() ?: return ChatUtils.modMessage("Hold an item to remove from the whitelist.")
        if (item !in list) return ChatUtils.modMessage("\"$item\" isn't in the $side whitelist.")
        list.remove(item)
        ConfigManager.save()
        ChatUtils.modMessage("Removed \"$item\" from the $side whitelist.")
    }

    private fun clear(list: MutableList<String>, message: String) {
        list.clear()
        ConfigManager.save()
        ChatUtils.modMessage(message)
    }

    private fun listWhitelists() {
        ChatUtils.modMessage("Auto Clicker whitelist:")
        ChatUtils.modMessage("Left: ${leftWhitelist.value.joinToString(", ").ifEmpty { "empty" }}")
        ChatUtils.modMessage("Right: ${rightWhitelist.value.joinToString(", ").ifEmpty { "empty" }}")
    }
}

//#endif
