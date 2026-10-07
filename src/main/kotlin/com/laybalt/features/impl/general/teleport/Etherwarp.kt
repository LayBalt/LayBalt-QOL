package com.laybalt.features.impl.general.teleport

// Singleplayer Etherwarp, Use Server Position and the "hide in menus" check are ported from Odin
// (https://github.com/odtheking/Odin), BSD-3-Clause, Copyright (c) 2025 odtheking

import com.laybalt.config.types.*
import com.laybalt.event.impl.*
import com.laybalt.features.Feature
import com.laybalt.mixin.ILocalPlayer
import com.laybalt.utils.*
import com.laybalt.utils.ColorUtils.withAlpha
import com.laybalt.utils.PlayerUtils.serverPitch
import com.laybalt.utils.PlayerUtils.serverYaw
import com.laybalt.utils.Utils.send
import com.laybalt.utils.items.EtherwarpHelper
import com.laybalt.utils.items.ItemUtils.skyblockId
import com.laybalt.utils.items.TeleportUtils
import com.laybalt.utils.render.world.Render3D.renderBlock
import com.laybalt.utils.render.world.Render3D.renderBox
import gg.essential.universal.UMinecraft
import gg.essential.universal.USound
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.minecraft.network.protocol.game.*
import net.minecraft.sounds.SoundEvents
import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.world.phys.Vec3
import java.awt.Color

object Etherwarp: Feature("Etherwarp overlay and sound.") {
    private val overlay by ToggleSetting("Etherwarp Overlay").withDescription("Highlights the block an Etherwarp would teleport you to while you sneak with an Etherwarp item").section("Overlay")
    private val mode by DropdownSetting("Mode", 0, listOf("Outline", "Fill", "Filled Outline")).withDescription("How the target block is drawn: only its outline, only a filled box, or both").showIf { overlay.value }
    private val phase by ToggleSetting("Phase").withDescription("Draws the highlight through walls").showIf { overlay.value }
    private val lineWidth by SliderSetting("Line Width", 1.0, 1.0, 10.0, 0.1).withDescription("Thickness of the outline").showIf { overlay.value && mode.value != 1 }
    private val showFail by ToggleSetting("Show Fail", true).withDescription("Shows the fail position of the etherwarp").showIf { overlay.value }
    private val fullBlock by ToggleSetting("Full Block").withDescription("Draws the overlay as a full block").showIf { overlay.value }
    private val useServerPosition by ToggleSetting("Use Server Position").withDescription("Predicts the Etherwarp from the last position the server knows about instead of your client position. Matches the server exactly while you are moving").showIf { overlay.value }

    private val fillColor by ColorSetting("Fill Color", Utils.favoriteColor.withAlpha(50)).withDescription("Fill color of a valid Etherwarp target").showIf { overlay.value && mode.value != 0 }.section("Colors")
    private val outlineColor by ColorSetting("Outline Color", Utils.favoriteColor, false).withDescription("Outline color of a valid Etherwarp target").showIf { overlay.value && mode.value != 1 }

    private val invalidFillColor by ColorSetting("Invalid Fill Color ", Color.RED.withAlpha(50)).withDescription("Fill color of a target you cannot Etherwarp to").showIf { overlay.value && mode.value != 0 && showFail.value }
    private val invalidOutlineColor by ColorSetting("Invalid Outline Color ", Color.RED, false).withDescription("Outline color of a target you cannot Etherwarp to").showIf { overlay.value && mode.value != 1 && showFail.value }

    private val etherwarpSound by ToggleSetting("Etherwarp Sound").withDescription("Replaces the Etherwarp teleport sound with the sound chosen below").section("Sound")
    private val zeroPingSound by ToggleSetting("Zero-Ping Sound").withDescription("Plays the Etherwarp sound client-side instead of waiting for the server to send the sound packet").showIf { etherwarpSound.value }
    private val playSound = createSoundSettings("Sound", SoundEvents.EXPERIENCE_ORB_PICKUP) { etherwarpSound.value }

    private val singleplayer by ToggleSetting("Singleplayer Etherwarp", true).withDescription("In singleplayer worlds, right-clicking with an Etherwarp item teleports you to the predicted block, so you can practice Etherwarps offline").section("Practice")

    //#if CHEAT
    private val leftClick by ToggleSetting("Left-Click Etherwarp").withDescription("Left-clicking with an Etherwarp item performs an Etherwarp (a right-click is sent for you)").section("Left Click")
    private val swingHandToggle by ToggleSetting("Swing Hand", true).withDescription("Swings your hand when the left-click Etherwarp fires").showIf { leftClick.value }
    private val autoSneak by ToggleSetting("Auto Sneak", false).withDescription("Sneaks for you during the left-click Etherwarp when you are not sneaking already").showIf { leftClick.value }
    private val autoSneakDelay by SliderSetting("Auto Sneak Delay", 50, 50, 150, 1).withDescription("Total time in milliseconds the auto sneak is held around the click").showIf { leftClick.value && autoSneak.value }
    //#endif

    private const val ETHERWARP_PITCH = 0.53968257f

    private fun startPosition(): Vec3 {
        if (! useServerPosition.value) return player.position()
        val local = player as ILocalPlayer
        return Vec3(local.serverX, local.serverY, local.serverZ)
    }

    override fun init() {
        register<RenderWorldEvent> {
            if (! overlay.value) return@register
            if (UMinecraft.currentScreenObj != null) return@register
            val heldItem = player.mainHandItem.takeUnless { it.isEmpty } ?: return@register
            if (! mc.options.keyShift.isDown && heldItem.skyblockId != "ETHERWARP_CONDUIT") return@register
            val distance = EtherwarpHelper.getEtherwarpDistance(heldItem) ?: return@register
            val (valid, pos) = EtherwarpHelper.getEtherPos(startPosition(), player.lookAngle, distance)
            if (! valid && ! showFail.value) return@register
            pos ?: return@register

            if (fullBlock.value) event.ctx.renderBox(pos.x + 0.5,
                pos.y, pos.z + 0.5, 1.0001, 1.0001, if (valid) outlineColor.value else invalidOutlineColor.value,
                if (valid) fillColor.value else invalidFillColor.value,
                mode.value.equalsOneOf(0, 2),
                mode.value.equalsOneOf(1, 2),
                phase.value,
                lineWidth.value
            )
            else event.ctx.renderBlock(pos, if (valid) outlineColor.value else invalidOutlineColor.value,
                if (valid) fillColor.value else invalidFillColor.value,
                mode.value.equalsOneOf(0, 2),
                mode.value.equalsOneOf(1, 2),
                phase = phase.value,
                lineWidth.value.toFloat()
            )
        }

        register<MainThreadPacketReceivedEvent.Pre> {
            if (! etherwarpSound.value) return@register
            val packet = event.packet as? ClientboundSoundPacket ?: return@register
            if (packet.sound.value() != SoundEvents.ENDER_DRAGON_HURT) return@register
            if (packet.pitch != ETHERWARP_PITCH) return@register
            event.isCanceled = true

            if (! zeroPingSound.value) playSound.invoke()
        }

        register<PacketEvent.Sent> {
            if (! etherwarpSound.value || ! zeroPingSound.value) return@register
            val packet = event.packet as? ServerboundUseItemOnPacket ?: return@register
            if (! mc.options.keyShift.isDown) return@register
            if (WorldUtils.getBlockAt(packet.hitResult.blockPos) !in TeleportUtils.TILLABLE_BLOCKS) return@register
            val dist = EtherwarpHelper.getEtherwarpDistance(player.mainHandItem) ?: return@register
            val (succeeded, pos) = EtherwarpHelper.getEtherPos(player.position(), player.lookAngle, dist)
            if (! succeeded || pos == null) return@register
            if (TeleportUtils.canTeleport(player.serverYaw, player.serverPitch)) playSound.invoke()
        }

        register<PacketEvent.Sent> {
            if (! etherwarpSound.value || ! zeroPingSound.value) return@register
            val packet = event.packet as? ServerboundUseItemPacket ?: return@register
            if (! mc.options.keyShift.isDown) return@register
            val dist = EtherwarpHelper.getEtherwarpDistance(player.mainHandItem) ?: return@register
            val (succeeded, pos) = EtherwarpHelper.getEtherPos(player.position(), player.lookAngle, dist)
            if (! succeeded || pos == null) return@register
            if (TeleportUtils.canTeleport(packet.yRot, packet.xRot)) playSound.invoke()
        }

        register<MouseClickEvent> {
            if (! singleplayer.value) return@register
            if (mc.singleplayerServer == null) return@register
            if (event.button != InputConstants.MOUSE_BUTTON_RIGHT || event.action != InputConstants.PRESS) return@register
            if (UMinecraft.currentScreenObj != null) return@register
            val heldItem = player.mainHandItem.takeUnless { it.isEmpty } ?: return@register
            if (! mc.options.keyShift.isDown && heldItem.skyblockId != "ETHERWARP_CONDUIT") return@register
            val distance = EtherwarpHelper.getEtherwarpDistance(heldItem) ?: return@register
            val (succeeded, pos) = EtherwarpHelper.getEtherPos(startPosition(), player.lookAngle, distance)
            if (! succeeded || pos == null) return@register

            val x = pos.x + 0.5
            val y = pos.y + 1.05
            val z = pos.z + 0.5
            ServerboundMovePlayerPacket.PosRot(x, y, z, player.yRot, player.xRot, false, false).send()
            player.setPos(x, y, z)
            player.setDeltaMovement(0.0, 0.0, 0.0)

            if (etherwarpSound.value) playSound.invoke()
            else USound.playSoundStatic(SoundEvents.ENDER_DRAGON_HURT, 1f, ETHERWARP_PITCH)
        }

        //#if CHEAT
        register<MouseClickEvent> {
            if (! leftClick.value) return@register
            if (event.button != InputConstants.MOUSE_BUTTON_LEFT) return@register
            if (event.action != InputConstants.PRESS) return@register
            if (UMinecraft.currentScreenObj != null) return@register
            if (! mc.options.keyShift.isDown && ! autoSneak.value) return@register
            if (EtherwarpHelper.getEtherwarpDistance(player.mainHandItem) == null) return@register

            event.isCanceled = true

            if (! player.isCrouching && autoSneak.value) scope.launch {
                val wait = autoSneakDelay.value.toLong() / 2
                PlayerUtils.toggleSneak(true)
                delay(wait)

                PlayerUtils.rightClick()
                if (swingHandToggle.value) PlayerUtils.swingArm()

                delay(wait)
                PlayerUtils.toggleSneak(false)
            }
            else {
                PlayerUtils.rightClick()
                if (swingHandToggle.value) PlayerUtils.swingArm()
            }
        }
        //#endif
    }
}
