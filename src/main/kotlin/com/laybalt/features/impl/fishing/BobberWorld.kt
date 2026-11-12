package com.laybalt.features.impl.fishing

//#if CHEAT

import com.laybalt.LayBaltQOL.mc
import com.laybalt.features.impl.fishing.BobberFlight.Medium
import com.laybalt.features.impl.fishing.BobberFlight.Verdict
import com.laybalt.features.impl.fishing.MotorModel.View
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import net.minecraft.core.BlockPos
import net.minecraft.tags.FluidTags
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.LiquidBlock
import net.minecraft.world.level.material.FluidState

/**
 * [BobberFlight]'s world in the client's [level], each block looked up once (a decision flies a few casts over the same
 * blocks). An unloaded block counts as SOLID, so a flight into it never counts as landing in a fluid; any block with an
 * outline shape is SOLID too, since the 1.8 hook stops on plants and lily pads as well.
 */
internal class BobberWorld(private val level: Level) : BobberFlight.Blocks {
    private val media = Long2ObjectOpenHashMap<Medium>()
    private val tops = Long2DoubleOpenHashMap()
    private val pos = BlockPos.MutableBlockPos()

    override fun at(x: Int, y: Int, z: Int): Medium {
        val key = BlockPos.asLong(x, y, z)
        media.get(key)?.let { return it }
        val m = read(x, y, z)
        media.put(key, m)
        return m
    }

    /** The fluid's surface within its block, 1.8's `isAABBInMaterial` one: 1 for a source or a falling fluid. */
    override fun top(x: Int, y: Int, z: Int): Double {
        val key = BlockPos.asLong(x, y, z)
        if (tops.containsKey(key)) return tops.get(key)
        val top = level.getFluidState(pos.set(x, y, z)).amount / 8.0
        tops.put(key, top)
        return top
    }

    private fun read(x: Int, y: Int, z: Int): Medium {
        if (level.isOutsideBuildHeight(y)) return Medium.AIR
        pos.set(x, y, z)
        if (! level.isLoaded(pos)) return Medium.SOLID
        val state = level.getBlockState(pos)
        val fluid = fluidOf(state.fluidState)
        return when {
            fluid != null && state.block is LiquidBlock -> fluid
            ! state.getShape(level, pos).isEmpty -> Medium.SOLID
            else -> fluid ?: Medium.AIR
        }
    }

    private fun fluidOf(f: FluidState) = when {
        f.`is`(FluidTags.WATER) -> Medium.WATER
        f.`is`(FluidTags.LAVA) -> Medium.LAVA
        else -> null
    }

    companion object {
        /**
         * Whether a cast of [p]'s from [view] lands like one from your fishing view [home] ([BobberFlight.judge]) where
         * [p] stands; [blocks] may be shared by the decisions of one tick. UNDECIDED out of a world.
         */
        fun judge(p: Player, home: View, view: View, blocks: BobberWorld? = here()): Verdict {
            blocks ?: return Verdict.UNDECIDED
            return BobberFlight.judge(p.x, p.y, p.z, p.isShiftKeyDown, home.yaw, home.pitch, view.yaw, view.pitch, blocks)
        }

        /** The client's level as a [BobberFlight] world, null out of one. */
        fun here() = mc.level?.let { BobberWorld(it) }
    }
}

//#endif
