// Ported from OdinClient (https://github.com/skies-starred/OdinClient), BSD-3-Clause, Copyright (c) 2026 Starred
package com.laybalt.features.impl.dungeon

//#if CHEAT

import com.laybalt.config.types.KeybindSetting
import com.laybalt.config.types.ToggleSetting
import com.laybalt.event.impl.PacketEvent
import com.laybalt.event.impl.TickEvent
import com.laybalt.features.Feature
import com.laybalt.utils.ChatUtils
import com.laybalt.utils.PlayerUtils
import com.laybalt.utils.items.ItemUtils.skyblockId
import com.laybalt.utils.location.LocationUtils
import gg.essential.universal.UMinecraft
import net.minecraft.core.BlockPos
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket
import net.minecraft.world.level.block.*

object GhostBlock: Feature("Turns blocks into client-side ghost blocks (air) with a held key or by right-clicking with a pickaxe. Use at your own risk.") {
    private val stonkGhostBlock by ToggleSetting("Stonk Ghost Block", true)
        .withDescription("Right-clicking a block while holding a pickaxe turns it into a ghost block instead of interacting with it.")
    private val ghostBlockKey by KeybindSetting("Ghost Block Key")
        .withDescription("Hold this key to turn every block you look at into a ghost block.")

    private val blacklist = setOf(
        Blocks.BEACON, Blocks.BREWING_STAND, Blocks.BROWN_MUSHROOM, Blocks.RED_MUSHROOM,
        Blocks.CHEST, Blocks.TRAPPED_CHEST, Blocks.ENDER_CHEST,
        Blocks.COMMAND_BLOCK, Blocks.CHAIN_COMMAND_BLOCK, Blocks.REPEATING_COMMAND_BLOCK,
        Blocks.CRAFTING_TABLE, Blocks.DAYLIGHT_DETECTOR, Blocks.DISPENSER, Blocks.DROPPER, Blocks.ENCHANTING_TABLE,
        Blocks.FURNACE, Blocks.BLAST_FURNACE, Blocks.SMOKER, Blocks.HOPPER, Blocks.LEVER, Blocks.NOTE_BLOCK,
        Blocks.REPEATER, Blocks.COMPARATOR, Blocks.BARRIER, Blocks.BEDROCK, Blocks.END_PORTAL_FRAME, Blocks.END_PORTAL,
        Blocks.MOVING_PISTON
    )

    /** Doors, beds, signs, buttons, trapdoors, heads/skulls and anvils are matched by block class (covers every wood type and colour). */
    private fun isBlacklisted(block: Block) = block in blacklist || block is DoorBlock || block is BedBlock || block is SignBlock ||
        block is ButtonBlock || block is TrapDoorBlock || block is AbstractSkullBlock || block is AnvilBlock

    override fun init() {
        register<TickEvent.Start> {
            if (! LocationUtils.inSkyblock) return@register
            if (UMinecraft.currentScreenObj != null) return@register
            if (! ghostBlockKey.isDown()) return@register
            val pos = PlayerUtils.getSelectionBlock() ?: return@register
            toAir(pos)
        }

        register<PacketEvent.Sent> {
            if (! stonkGhostBlock.value) return@register
            val packet = event.packet as? ServerboundUseItemOnPacket ?: return@register
            if (! LocationUtils.inSkyblock) return@register
            if (UMinecraft.currentScreenObj != null) return@register
            if ("PICKAXE" !in player.getItemInHand(packet.hand).skyblockId) return@register
            if (! toAir(packet.hitResult.blockPos)) return@register
            event.isCanceled = true
        }
    }

    override fun onEnable() {
        super.onEnable()
        if (mc.player != null) ChatUtils.modMessage("&cUse \"Ghost Block\" at your own risk! This feature can get you banned if used improperly!")
    }

    private fun toAir(pos: BlockPos): Boolean {
        val world = mc.level ?: return false
        val state = world.getBlockState(pos)
        if (state.isAir || isBlacklisted(state.block)) return false
        if (mc.isSameThread) world.setBlock(pos, Blocks.AIR.defaultBlockState(), 3)
        else mc.execute { world.setBlock(pos, Blocks.AIR.defaultBlockState(), 3) }
        return true
    }
}
//#endif
