// Ported from Odin (https://github.com/odtheking/Odin), BSD-3-Clause, Copyright (c) 2025 odtheking
package com.laybalt.features.impl.floor7.devices

import com.laybalt.config.types.ButtonSetting
import com.laybalt.config.types.ColorSetting
import com.laybalt.config.types.ToggleSetting
import com.laybalt.event.impl.*
import com.laybalt.features.Feature
import com.laybalt.utils.ChatUtils
import com.laybalt.utils.ChatUtils.unformattedText
import com.laybalt.utils.location.LocationUtils
import com.laybalt.utils.render.world.Render3D.renderBoxBounds
import gg.essential.universal.USound
import net.minecraft.core.BlockPos
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.item.DyeColor
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.awt.Color
import kotlin.math.abs

object ArrowsDevice: Feature("Shows a solution for the Sharp Shooter (arrows) device in Floor 7 phase 3.", "Arrows Device") {
    private val markedPositionColor by ColorSetting("Marked Position", Color(85, 255, 255, 127))
        .withDescription("Colour of the targets that have already been shot (blue terracotta).")
    private val targetPositionColor by ColorSetting("Target Position", Color(255, 85, 255, 127))
        .withDescription("Colour of the current target (emerald block) you need to shoot.")
    private val depthCheck by ToggleSetting("Depth Check", true)
        .withDescription("When on, the boxes are hidden behind walls. Turn it off to see them through walls.")
    private val alertOnDeviceComplete by ToggleSetting("Device Complete Alert", true)
        .withDescription("Sends a chat message, shows a title and plays a sound when you complete the device.")

    private val showAimPositions by ToggleSetting("Show Aim Positions", false).section("Aim Positions")
        .withDescription("Shows up to three spots to aim at so one arrow can hit two targets at once. The first spot always covers the current target.")
    private val firstAimPositionColor by ColorSetting("First Aim Position", Color(85, 255, 85, 127))
        .withDescription("Colour of the first aim position (it covers the current target).").showIf { showAimPositions.value }
    private val secondAimPositionColor by ColorSetting("Second Aim Position", Color(255, 170, 0, 127))
        .withDescription("Colour of the second aim position.").showIf { showAimPositions.value }
    private val thirdAimPositionColor by ColorSetting("Third Aim Position", Color(255, 85, 85, 127))
        .withDescription("Colour of the third aim position.").showIf { showAimPositions.value }

    private val resetButton by ButtonSetting("Reset") { reset() }
        .withDescription("Clears all tracked targets and aim positions.")

    private val deviceCompleteRegex = Regex("^(.{1,16}) completed a device! \\((\\d)/(\\d)\\)$")
    private val roomBoundingBox = AABB(20.0, 100.0, 30.0, 89.0, 151.0, 51.0)

    private val devicePositions = listOf(
        BlockPos(68, 130, 50), BlockPos(66, 130, 50), BlockPos(64, 130, 50),
        BlockPos(68, 128, 50), BlockPos(66, 128, 50), BlockPos(64, 128, 50),
        BlockPos(68, 126, 50), BlockPos(66, 126, 50), BlockPos(64, 126, 50)
    )

    private val markedPositions = mutableSetOf<BlockPos>()
    private var targetPosition: BlockPos? = null
    private var isDeviceComplete = false

    private data class AimPosition(val position: Vec3, val coveredBlocks: Set<BlockPos>, val distance: Double)

    private var optimalAimPositions: List<AimPosition> = emptyList()

    private val adjacentPairs by lazy {
        devicePositions.flatMapIndexed { i, block1 ->
            devicePositions.drop(i + 1).mapNotNull { block2 ->
                if (abs(block1.x - block2.x) == 2 && block1.y == block2.y && block1.z == block2.z) block1 to block2 else null
            }
        }
    }

    private val blueTerracotta get() = Blocks.DYED_TERRACOTTA.pick(DyeColor.BLUE)

    private val isPlayerInRoom: Boolean
        get() = mc.player?.let { roomBoundingBox.contains(it.position()) } == true

    override fun init() {
        register<BlockChangeEvent> {
            if (LocationUtils.F7Phase != 3 || event.pos !in devicePositions) return@register

            if (event.oldBlock == Blocks.EMERALD_BLOCK && event.newBlock == blueTerracotta) {
                markedPositions.add(event.pos.immutable())
                if (targetPosition == event.pos) targetPosition = null
                if (showAimPositions.value) optimalAimPositions = calculateOptimalAimPositions(event.pos)
            }
            else if (event.oldBlock == blueTerracotta && event.newBlock == Blocks.EMERALD_BLOCK) {
                markedPositions.remove(event.pos)
                targetPosition = event.pos.immutable()
                if (showAimPositions.value) optimalAimPositions = calculateOptimalAimPositions(event.pos)
            }
        }

        register<RenderWorldEvent> {
            if (LocationUtils.F7Phase != 3) return@register
            val phase = ! depthCheck.value

            markedPositions.forEach { position ->
                event.ctx.renderBoxBounds(AABB(position), markedPositionColor.value, outline = false, fill = true, phase = phase)
            }

            targetPosition?.let { position ->
                event.ctx.renderBoxBounds(AABB(position), targetPositionColor.value, outline = false, fill = true, phase = phase)
            }

            if (showAimPositions.value) {
                val colors = listOf(firstAimPositionColor.value, secondAimPositionColor.value, thirdAimPositionColor.value)
                optimalAimPositions.take(3).forEachIndexed { index, aimPos ->
                    val corner = aimPos.position.add(- 0.5, - 0.5, - 0.1)
                    event.ctx.renderBoxBounds(
                        AABB(corner.x, corner.y, corner.z, corner.x + 1.0, corner.y + 1.0, corner.z + 1.0),
                        colors[index], outline = false, fill = true, phase = false
                    )
                }
            }
        }

        register<WorldChangeEvent> {
            reset()
            isDeviceComplete = false
        }

        register<ChatMessageEvent> {
            if (LocationUtils.F7Phase != 3 || ! isPlayerInRoom || isDeviceComplete) return@register
            if (deviceCompleteRegex.find(event.unformattedText)?.groupValues?.get(1) == mc.player?.name?.string) onComplete("Chat")
        }

        register<MainThreadPacketReceivedEvent.Post> {
            val packet = event.packet as? ClientboundSetEntityDataPacket ?: return@register
            if (LocationUtils.F7Phase != 3 || ! isPlayerInRoom || isDeviceComplete) return@register
            if (mc.level?.getEntity(packet.id)?.name?.string == "Active") onComplete("Entity")
        }
    }

    private fun reset() {
        markedPositions.clear()
        targetPosition = null
        optimalAimPositions = emptyList()
    }

    private fun onComplete(method: String) {
        isDeviceComplete = true

        if (alertOnDeviceComplete.value) {
            ChatUtils.modMessage("§aSharp shooter device complete §7($method)")
            ChatUtils.showTitle("§aDevice Complete")
            USound.playSoundStatic(SoundEvents.NOTE_BLOCK_PLING.value(), 1f, 1f)
        }
        reset()
    }

    private fun calculateOptimalAimPositions(target: BlockPos): List<AimPosition> {
        val unmarkedBlocks = devicePositions.filterNot { it in markedPositions }

        fun createAimPosition(block1: BlockPos, block2: BlockPos): AimPosition? =
            listOf(block1, block2).filter { it in unmarkedBlocks }.takeIf { it.isNotEmpty() }?.let {
                AimPosition(block1.midpoint(block2), it.toSet(), 0.0)
            }

        val greenAim = adjacentPairs
            .filter { (block1, block2) -> target == block1 || target == block2 }
            .mapNotNull { (block1, block2) -> createAimPosition(block1, block2) }
            .maxByOrNull { it.coveredBlocks.size } ?: return emptyList()

        val remainingAimPositions = adjacentPairs
            .filterNot { (block1, block2) -> target == block1 || target == block2 }
            .mapNotNull { (block1, block2) -> createAimPosition(block1, block2) }

        return findBestCombination(greenAim, remainingAimPositions)
    }

    private fun findBestCombination(greenAim: AimPosition, aimPositions: List<AimPosition>): List<AimPosition> {
        val result = mutableListOf(greenAim)
        val covered = greenAim.coveredBlocks.toMutableSet()

        repeat(2) {
            val best = aimPositions.filterNot { it in result }
                .maxWithOrNull(compareBy(
                    { it.coveredBlocks.count { block -> block !in covered } }, // new unmarked blocks
                    { it.coveredBlocks.size }, // total blocks covered
                    { - (result.last().position.distanceTo(it.position)) } // closer to the previous aim
                )) ?: return@repeat

            result.add(best)
            covered.addAll(best.coveredBlocks)
        }

        return result.mapIndexed { index, aimPos ->
            AimPosition(aimPos.position, aimPos.coveredBlocks, if (index == 0) 0.0 else aimPos.position.distanceTo(result[index - 1].position))
        }
    }

    private fun BlockPos.midpoint(other: BlockPos): Vec3 =
        Vec3((x + other.x) / 2.0 + 0.5, y.toDouble() + 0.5, z.toDouble() + 0.5)
}
