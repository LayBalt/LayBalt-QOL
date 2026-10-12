// Secret Hitboxes / Close Chest options ported from OdinClient (https://github.com/skies-starred/OdinClient), BSD-3-Clause, Copyright (c) 2026 Starred
// Secret Clicked options ported from Odin (https://github.com/odtheking/Odin), BSD-3-Clause, Copyright (c) 2025 odtheking
package com.laybalt.features.impl.dungeon

import com.laybalt.config.types.*
import com.laybalt.event.impl.*
import com.laybalt.features.Feature
import com.laybalt.init.ModCompatibility
import com.laybalt.utils.*
import com.laybalt.utils.ChatUtils.unformattedText
import com.laybalt.utils.ColorUtils.withAlpha
import com.laybalt.utils.Utils.send
import com.laybalt.utils.dungeons.DungeonUtils
import com.laybalt.utils.dungeons.enums.SecretType
import com.laybalt.utils.location.LocationUtils
import com.laybalt.utils.render.Render2D.drawString
import com.laybalt.utils.render.RenderHelper.width
import com.laybalt.utils.render.world.Render3D.renderBoxBounds
import net.minecraft.client.gui.screens.inventory.ContainerScreen
import net.minecraft.client.input.KeyEvent
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket
import net.minecraft.network.protocol.game.ClientboundSoundPacket
import net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.inventory.MenuType
import net.minecraft.world.level.block.*
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.AttachFace
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape
import java.awt.Color
import java.util.concurrent.CopyOnWriteArrayList

object Secrets: Feature() {
    private val hudDisplay by ToggleSetting("Secret HUD", true).withDescription("Displays the current room's secrets on screen.").section("HUD")
    private val hudPrefix by TextInputSetting("HUD Prefix", "Secrets:").withDescription("The text before the secret count of the room")

    //#if CHEAT
    private val closeChest by ToggleSetting("Close Chest").section("Auto").withDescription("Automatically closes the secret chest for you.")
    private val closeChestMode by DropdownSetting("Close Chest Mode", 0, listOf("Auto", "Any Key"))
        .withDescription("Auto: secret chests are closed before they even open. Any Key: the chest opens normally and closes as soon as you press any key (except the inventory key) or click.")
        .showIf { closeChest.value }

    private val hitboxesOnlyInDungeon by ToggleSetting("Only In Dungeons", true).section("Secret Hitboxes")
        .withDescription("Only change secret block hitboxes while you are inside a dungeon.")
    private val lever by ToggleSetting("Lever").withDescription("Expand block Lever hitbox.")
    private val leverStyle by DropdownSetting("Lever Hitbox Style", 0, listOf("Scaled", "1.8 Style", "Full Block"))
        .withDescription("Scaled: lever box grown by the size slider (1.0 = whole block). 1.8 Style: the Minecraft 1.8 lever box, only slightly bigger than vanilla. Full Block: the whole block.")
        .showIf { lever.value }
    private val leverSize by SliderSetting("Lever Hitbox Size", 1.0, 8.0 / 16.0, 1.0, 0.01).withDescription("Size of the scaled lever hitbox (1.0 = a full block face).").showIf { lever.value && leverStyle.value == 0 }
    @JvmStatic val button by ToggleSetting("Button").withDescription("Expand button hitbox.")
    private val buttonStyle by DropdownSetting("Button Hitbox Style", 0, listOf("Scaled", "Flat", "Full Block"))
        .withDescription("Scaled: button box grown by the size slider. Flat: a thin slab covering the whole face the button is on. Full Block: the whole block.")
        .showIf { button.value }
    private val buttonSize by SliderSetting("Button Hitbox Size", 1.0, 6.0 / 16.0, 1.0, 0.01).withDescription("Size of the scaled button hitbox (1.0 = a full block face).").showIf { button.value && buttonStyle.value == 0 }
    @JvmStatic val skull by ToggleSetting("Skulls").withDescription("Full block Skull hitbox.")
    @JvmStatic val mushroom by ToggleSetting("Mushroom").withDescription("Full block Mushroom hitbox.")
    @JvmStatic val chests by ToggleSetting("Chests").withDescription("Full block Chest hitbox for aiming and the block outline (collision stays vanilla).")
    private val onlyTrappedChests by ToggleSetting("Only Trapped Chests").withDescription("Only expand the hitbox of trapped chests.").showIf { chests.value }
    //#endif

    private val secretClicked by ToggleSetting("Highlight Clicked Secret").withDescription("Highlights the block of a secret when you interact with it.").section("Secret Clicked")
    private val displayTime by SliderSetting("Highlight Time", 2.0, 0.5, 120.0, 0.5, "s").withDescription("How long (in seconds) the highlight box remains visible.").showIf { secretClicked.value }
    private val secretClickedColor by ColorSetting("Highlight Color", Utils.favoriteColor.withAlpha(50)).withDescription("The color of the secret highlight box.").showIf { secretClicked.value }
    private val lockedColor by ColorSetting("Locked Color", Color(255, 85, 85, 204)).withDescription("The color of the highlight box when the clicked chest is locked (\"That chest is locked!\").").showIf { secretClicked.value }
    private val mode by DropdownSetting("Render Mode", 2, listOf("Fill", "Outline", "Filled Outline")).withDescription("Choose how the box is rendered.").showIf { secretClicked.value }
    private val phase by ToggleSetting("See Through Walls").withDescription("If enabled, the highlight will be visible through other blocks.").showIf { secretClicked.value }
    private val itemBoxes by ToggleSetting("Item Boxes", true).withDescription("Also highlight the position of picked up secret items.").showIf { secretClicked.value }
    private val boxInBoss by ToggleSetting("Highlight In Boss").withDescription("Also highlight secrets clicked inside the boss room.").showIf { secretClicked.value }

    private val secretSound by ToggleSetting("Secret Sound").withDescription("Plays a sound effect when a secret is clicked/found.").section("Secret Sound")
    private val soundInBoss by ToggleSetting("Sound In Boss").withDescription("Also play the sound for secrets clicked inside the boss room.").showIf { secretSound.value }
    private val playSound = createSoundSettings("Sound", SoundEvents.EXPERIENCE_ORB_PICKUP) { secretSound.value }

    private data class ClickedSecret(val pos: BlockPos, val aabb: AABB, val time: Long, var locked: Boolean = false)

    private val clicked = CopyOnWriteArrayList<ClickedSecret>()
    private var lastPlayed = System.currentTimeMillis()

    override fun init() {
        hudElement("Secret Hud", { hudDisplay.value }, { LocationUtils.inDungeon && ! LocationUtils.inBoss }) { ctx, example ->
            val line = if (example) "${if (hudPrefix.value.isBlank()) "" else "&7${hudPrefix.value} "}&c3&7/&a7"
            else {
                val max = ActionBarParser.maxSecrets ?: return@hudElement 0f to 0f
                val current = ActionBarParser.secrets ?: return@hudElement 0f to 0f
                val prefix = if (hudPrefix.value.isBlank()) "" else "&7${hudPrefix.value} "

                "$prefix${ColorUtils.colorCodeByPercent(current, max)}$current&7/&a$max"
            }

            ctx.drawString(line, 0, 0)
            return@hudElement line.width() to 9f
        }

        register<RenderWorldEvent> {
            if (clicked.isEmpty()) return@register
            val now = System.currentTimeMillis()
            clicked.removeIf { it.time + (displayTime.value * 1000) < now }
            if (! LocationUtils.inDungeon || (LocationUtils.inBoss && ! boxInBoss.value)) return@register

            val outline = mode.value.equalsOneOf(1, 2)
            val fill = mode.value.equalsOneOf(0, 2)

            for (secret in clicked) {
                val color = if (secret.locked) lockedColor.value else secretClickedColor.value
                event.ctx.renderBoxBounds(secret.aabb, color, color, outline, fill, phase.value)
            }
        }

        register<DungeonEvent.SecretEvent> { onSecret(event.type, event.pos) }

        // The shared dispatcher only posts bat / item secret events outside the boss room; mirror them here for the boss options.
        register<MainThreadPacketReceivedEvent.Pre> {
            if (! LocationUtils.inDungeon || ! LocationUtils.inBoss) return@register
            if (! (secretClicked.value && boxInBoss.value) && ! (secretSound.value && soundInBoss.value)) return@register
            when (val packet = event.packet) {
                is ClientboundSoundPacket -> {
                    if (packet.sound.value() != SoundEvents.BAT_DEATH) return@register
                    onSecret(SecretType.BAT, BlockPos(packet.x.toInt(), packet.y.toInt(), packet.z.toInt()))
                }

                is ClientboundTakeItemEntityPacket -> {
                    val entity = level.getEntity(packet.itemId) as? ItemEntity ?: return@register
                    if (entity.item.hoverName.unformattedText !in DungeonUtils.dungeonItemDrops) return@register
                    if (player.distanceToSqr(entity) > 36) return@register
                    onSecret(SecretType.ITEM, entity.blockPosition())
                }
            }
        }

        register<ChatMessageEvent> {
            if (! LocationUtils.inDungeon) return@register
            if (event.unformattedText != "That chest is locked!") return@register
            clicked.lastOrNull()?.locked = true
        }

        register<WorldChangeEvent> { clicked.clear() }

        //#if CHEAT
        register<MainThreadPacketReceivedEvent.Pre> {
            if (! closeChest.value || closeChestMode.value != 0) return@register
            if (! LocationUtils.inDungeon) return@register
            if (LocationUtils.inBoss) return@register
            val packet = event.packet as? ClientboundOpenScreenPacket ?: return@register
            if (! packet.type.equalsOneOf(MenuType.GENERIC_9x3, MenuType.GENERIC_9x6)) return@register
            if (! packet.title.unformattedText.equalsOneOf("Chest", "Large Chest")) return@register
            ServerboundContainerClosePacket(packet.containerId).send()
            event.isCanceled = true
        }

        register<ContainerEvent.Keyboard> {
            if (! isAnyKeyChest(event.screen)) return@register
            if (mc.options.keyInventory.matches(KeyEvent(event.key, event.scancode, event.modifiers))) return@register
            player.closeContainer()
            event.isCanceled = true
        }

        register<ContainerEvent.MouseClick> {
            if (! isAnyKeyChest(event.screen)) return@register
            player.closeContainer()
            event.isCanceled = true
        }

        register<GameStartEvent> { if (lever.value || button.value) ModCompatibility.disableBlockstateCulling() }
        //#endif
    }

    private fun onSecret(type: SecretType, pos: BlockPos) {
        val inBoss = LocationUtils.inBoss
        val seen = clicked.any { it.pos == pos }

        if (secretSound.value && (! inBoss || soundInBoss.value)) run {
            if (type == SecretType.ITEM && System.currentTimeMillis() - lastPlayed < 2000) return@run
            if (type == SecretType.CHEST) lastPlayed = System.currentTimeMillis()
            if (seen) return@run
            playSound()
        }

        if (! secretClicked.value || seen) return
        if (inBoss && ! boxInBoss.value) return
        if (type == SecretType.ITEM && ! itemBoxes.value) return
        clicked.add(ClickedSecret(pos, blockBounds(pos), System.currentTimeMillis()))
    }

    private fun blockBounds(pos: BlockPos): AABB {
        val state = WorldUtils.getStateAt(pos)
        val shape = if (state.isAir) Shapes.empty() else state.getShape(level, pos)
        return if (shape.isEmpty) AABB(pos) else shape.bounds().move(pos)
    }

    //#if CHEAT
    private fun isAnyKeyChest(screen: Any?): Boolean {
        if (! enabled || ! closeChest.value || closeChestMode.value != 1) return false
        if (! LocationUtils.inDungeon || LocationUtils.inBoss) return false
        val chest = screen as? ContainerScreen ?: return false
        return chest.title.unformattedText.equalsOneOf("Chest", "Large Chest")
    }

    override fun onEnable() {
        super.onEnable()
        if (lever.value || button.value) ModCompatibility.disableBlockstateCulling()
    }

    private fun hitboxesActive() = enabled && (! hitboxesOnlyInDungeon.value || LocationUtils.inDungeon)

    @JvmStatic
    fun shouldModifyButton() = hitboxesActive() && button.value

    @JvmStatic
    fun shouldModifyMushroom() = hitboxesActive() && mushroom.value

    @JvmStatic
    fun shouldModifySkull() = hitboxesActive() && skull.value

    @JvmStatic
    fun shouldExpandChest(state: BlockState): Boolean {
        if (! hitboxesActive() || ! chests.value) return false
        return ! onlyTrappedChests.value || state.block is TrappedChestBlock
    }

    @JvmStatic
    fun getButtonShape(state: BlockState): VoxelShape {
        val face = state.getValue(FaceAttachedHorizontalDirectionalBlock.FACE)
        val facing = state.getValue(FaceAttachedHorizontalDirectionalBlock.FACING)
        val powered = state.getValue(ButtonBlock.POWERED)
        return when (buttonStyle.value) {
            1 -> flatButtonShape(face, facing, powered)
            2 -> Shapes.block()
            else -> {
                val base = Block.boxZ(buttonSize.value * 16.0, 16.0 - if (powered) 1 else 2, 16.0)
                Shapes.rotateAttachFace(base)[face]?.get(facing) !!
            }
        }
    }

    @JvmStatic
    fun getLeverShape(state: BlockState): VoxelShape {
        val face = state.getValue(FaceAttachedHorizontalDirectionalBlock.FACE)
        val facing = state.getValue(FaceAttachedHorizontalDirectionalBlock.FACING)
        return when (leverStyle.value) {
            1 -> oldLeverShape(face, facing)
            2 -> Shapes.block()
            else -> {
                val base = Block.boxZ(leverSize.value * 16.0, leverSize.value * 16.0, 16.0 - leverSize.value * 16.0, 16.0)
                Shapes.rotateAttachFace(base)[face]?.get(facing) !!
            }
        }
    }

    private val LEVER_FLOOR_SHAPE = Block.box(4.0, 0.0, 4.0, 12.0, 10.0, 12.0)
    private val LEVER_NORTH_SHAPE = Block.box(5.0, 3.0, 10.0, 11.0, 13.0, 16.0)
    private val LEVER_SOUTH_SHAPE = Block.box(5.0, 3.0, 0.0, 11.0, 13.0, 6.0)
    private val LEVER_EAST_SHAPE = Block.box(0.0, 3.0, 5.0, 6.0, 13.0, 11.0)
    private val LEVER_WEST_SHAPE = Block.box(10.0, 3.0, 5.0, 16.0, 13.0, 11.0)

    private fun oldLeverShape(face: AttachFace, facing: Direction): VoxelShape {
        if (face == AttachFace.FLOOR || face == AttachFace.CEILING) return LEVER_FLOOR_SHAPE
        return when (facing) {
            Direction.EAST -> LEVER_EAST_SHAPE
            Direction.WEST -> LEVER_WEST_SHAPE
            Direction.SOUTH -> LEVER_SOUTH_SHAPE
            Direction.NORTH -> LEVER_NORTH_SHAPE
            else -> LEVER_FLOOR_SHAPE
        }
    }

    private fun flatButtonShape(face: AttachFace, facing: Direction, powered: Boolean): VoxelShape {
        val t = (if (powered) 1 else 2) / 16.0
        return when (face) {
            AttachFace.CEILING -> Shapes.box(0.0, 1.0 - t, 0.0, 1.0, 1.0, 1.0)
            AttachFace.FLOOR -> Shapes.box(0.0, 0.0, 0.0, 1.0, t, 1.0)
            else -> when (facing) {
                Direction.EAST -> Shapes.box(0.0, 0.0, 0.0, t, 1.0, 1.0)
                Direction.WEST -> Shapes.box(1.0 - t, 0.0, 0.0, 1.0, 1.0, 1.0)
                Direction.SOUTH -> Shapes.box(0.0, 0.0, 0.0, 1.0, 1.0, t)
                Direction.NORTH -> Shapes.box(0.0, 0.0, 1.0 - t, 1.0, 1.0, 1.0)
                Direction.UP -> Shapes.box(0.0, 0.0, 0.0, 1.0, t, 1.0)
                Direction.DOWN -> Shapes.box(0.0, 1.0 - t, 0.0, 1.0, 1.0, 1.0)
            }
        }
    }

    private val blackListedLevers = listOf(
        BlockPos(61, 136, 142), BlockPos(60, 136, 142),
        BlockPos(59, 136, 142), BlockPos(62, 135, 142),
        BlockPos(61, 135, 142), BlockPos(59, 135, 142),
        BlockPos(58, 135, 142), BlockPos(62, 134, 142),
        BlockPos(61, 134, 142), BlockPos(59, 134, 142),
        BlockPos(58, 134, 142), BlockPos(61, 133, 142),
        BlockPos(60, 133, 142), BlockPos(59, 133, 142)
    )

    @JvmStatic
    fun isValidLever(pos: BlockPos): Boolean {
        if (! hitboxesActive()) return false
        if (! lever.value) return false
        if (pos in blackListedLevers && LocationUtils.dungeonFloorNumber == 7) return false
        return true
    }
    //#endif
}
