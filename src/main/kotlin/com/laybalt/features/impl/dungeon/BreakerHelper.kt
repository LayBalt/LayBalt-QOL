// Charges HUD, fatigue / boss gates and extra blacklist entries ported from OdinClient (https://github.com/skies-starred/OdinClient), BSD-3-Clause, Copyright (c) 2026 Starred
package com.laybalt.features.impl.dungeon

//#if CHEAT

import com.laybalt.config.types.ToggleSetting
import com.laybalt.event.impl.MainThreadPacketReceivedEvent
import com.laybalt.event.impl.PlayerInteractEvent
import com.laybalt.event.impl.TickEvent
import com.laybalt.event.impl.WorldChangeEvent
import com.laybalt.features.Feature
import com.laybalt.utils.ChatUtils.removeFormatting
import com.laybalt.utils.WorldUtils
import com.laybalt.utils.dungeons.DungeonUtils.REDSTONE_KEY
import com.laybalt.utils.dungeons.map.core.RoomType
import com.laybalt.utils.dungeons.map.utils.ScanUtils
import com.laybalt.utils.equalsOneOf
import com.laybalt.utils.items.ItemUtils.lore
import com.laybalt.utils.items.ItemUtils.skyblockId
import com.laybalt.utils.location.LocationUtils
import com.laybalt.utils.render.Render2D.drawString
import com.laybalt.utils.render.RenderHelper.width
import net.minecraft.core.BlockPos
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket
import net.minecraft.sounds.SoundSource
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.SkullBlock
import net.minecraft.world.level.block.entity.SkullBlockEntity

object BreakerHelper: Feature("Utilities for Dungeon Breaker") {
    private val preventBreakingSecrets by ToggleSetting("Prevent Secret Mine").withDescription("Prevents you from breaking secret blocks like chests & levers.")
    private val zeroPing by ToggleSetting("Zero Ping").withDescription("Removes the blocks you mine instantly instead of waiting for the server to remove them.")
    private val onlyWhenFatigue by ToggleSetting("Only With Mining Fatigue", true)
        .withDescription("Zero Ping only removes blocks while you have Mining Fatigue (as the Dungeon Breaker insta-mines) and while the breaker still has charges.")
        .showIf { zeroPing.value }
    private val chargesHud by ToggleSetting("Charges HUD", true).withDescription("Shows the charges left in your Dungeon Breaker while in a dungeon. Move it in the HUD editor.")

    private val chargesRegex = Regex("Charges: (\\d+)/(\\d+)⸕")
    private var charges = 0
    private var maxCharges = 0
    private var scanTicks = 0

    private val blacklist = setOf(
        Blocks.BARRIER, Blocks.BEDROCK, Blocks.COMMAND_BLOCK, Blocks.CHAIN_COMMAND_BLOCK, Blocks.REPEATING_COMMAND_BLOCK,
        Blocks.TNT, Blocks.CHEST, Blocks.PLAYER_HEAD, Blocks.PLAYER_WALL_HEAD, Blocks.TRAPPED_CHEST, Blocks.END_PORTAL_FRAME,
        Blocks.END_PORTAL, Blocks.STICKY_PISTON, Blocks.PISTON_HEAD, Blocks.PISTON, Blocks.MOVING_PISTON, Blocks.LEVER,
        Blocks.STONE_BUTTON, Blocks.SKELETON_SKULL, Blocks.SKELETON_WALL_SKULL, Blocks.WITHER_SKELETON_SKULL,
        Blocks.WITHER_SKELETON_WALL_SKULL
    )

    override fun init() {
        hudElement("Breaker Charges", { chargesHud.value }, { LocationUtils.inDungeon && maxCharges > 0 }) { ctx, example ->
            val text = if (example) "§e17§7/§e20§c⸕" else "§e$charges§7/§e$maxCharges§c⸕"
            ctx.drawString(text, 0, 0)
            text.width() to 9f
        }

        register<PlayerInteractEvent.LEFT_CLICK.BLOCK> {
            if (! preventBreakingSecrets.value) return@register
            if (! LocationUtils.inDungeon) return@register
            if (LocationUtils.inBoss) return@register
            if (event.item?.skyblockId != "DUNGEONBREAKER") return@register
            val block = WorldUtils.getBlockAt(event.pos)
            if (block !in blacklist) return@register
            if (block is SkullBlock && (level.getBlockEntity(event.pos) as? SkullBlockEntity)?.ownerProfile?.partialProfile()?.id.toString().equalsOneOf(*REDSTONE_KEY)) return@register
            event.isCanceled = true
        }

        register<MainThreadPacketReceivedEvent.Post> {
            if (! LocationUtils.inDungeon) return@register
            val packet = event.packet as? ClientboundContainerSetSlotPacket ?: return@register
            parseCharges(packet.item)
        }

        // Inventory contents arrive in bulk when joining a dungeon, so also rescan the inventory once a second.
        register<TickEvent.Start> {
            if (! LocationUtils.inDungeon) return@register
            if (++ scanTicks < 20) return@register
            scanTicks = 0
            for (stack in player.inventory.nonEquipmentItems) if (parseCharges(stack)) break
        }

        register<WorldChangeEvent> {
            charges = 0
            maxCharges = 0
        }
    }

    private fun parseCharges(stack: ItemStack): Boolean {
        if (stack.skyblockId != "DUNGEONBREAKER") return false
        val match = stack.lore.firstNotNullOfOrNull { chargesRegex.find(it.removeFormatting()) } ?: return false
        charges = match.groupValues[1].toIntOrNull() ?: 0
        maxCharges = match.groupValues[2].toIntOrNull() ?: 0
        return true
    }

    @JvmStatic
    fun onHitBlock(pos: BlockPos) {
        if (! enabled) return
        if (! zeroPing.value) return
        if (! LocationUtils.inDungeon) return
        if (player.mainHandItem.skyblockId != "DUNGEONBREAKER") return
        if (LocationUtils.inBoss && LocationUtils.dungeonFloorNumber != 7) return
        if (ScanUtils.currentRoom?.data?.type.equalsOneOf(RoomType.PUZZLE, RoomType.FAIRY)) return
        if (onlyWhenFatigue.value) {
            if (maxCharges > 0 && charges == 0) return
            if (! player.hasEffect(MobEffects.MINING_FATIGUE)) return
        }
        val state = WorldUtils.getStateAt(pos).takeUnless { it.block in blacklist || it.block == Blocks.OBSIDIAN } ?: return

        level.removeBlock(pos, false)
        level.playLocalSound(
            pos.x + 0.5,
            pos.y + 0.5,
            pos.z + 0.5,
            state.soundType.breakSound,
            SoundSource.BLOCKS,
            (state.soundType.volume + 1.0f) / 2.0f,
            state.soundType.pitch * 0.8f,
            false
        )
    }
}
//#endif
