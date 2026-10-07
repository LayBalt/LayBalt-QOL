package com.laybalt.utils

import com.laybalt.LayBaltQOL
import com.laybalt.init.ModCompatibility
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.status.ChunkStatus
import net.minecraft.world.phys.Vec3

object WorldUtils {
    fun getStateAt(pos: BlockPos) = getLoadedChunk(pos.x shr 4, pos.z shr 4)?.getBlockState(pos) ?: Blocks.AIR.defaultBlockState()
    fun getStateAt(x: Int, y: Int, z: Int) = this.getStateAt(BlockPos(x, y, z))
    fun getBlockAt(pos: BlockPos) = getStateAt(pos).block
    fun getBlockAt(vec3: Vec3) = getBlockAt(BlockPos(vec3.x.toInt(), vec3.y.toInt(), vec3.z.toInt()))
    fun getBlockAt(x: Number, y: Number, z: Number) = getBlockAt(BlockPos(x.toInt(), y.toInt(), z.toInt()))

    fun setBlockAt(pos: BlockPos, state: BlockState) = LayBaltQOL.mc.level?.setBlock(pos, state, 19)

    fun isChunkLoaded(x: Number, z: Number) = getLoadedChunk(x.toInt() shr 4, z.toInt() shr 4) != null

    fun getBlockEntityList(): List<BlockPos> {
        val player = LayBaltQOL.mc.player ?: return emptyList()
        val renderDistance = LayBaltQOL.mc.options.renderDistance().get()
        val pX = player.chunkPosition().x
        val pZ = player.chunkPosition().z

        return buildList {
            for (x in (pX - renderDistance) .. (pX + renderDistance)) {
                for (z in (pZ - renderDistance) .. (pZ + renderDistance)) {
                    val chunk = getLoadedChunk(x, z) ?: continue
                    addAll(chunk.blockEntitiesPos)
                }
            }
        }
    }

    private fun getLoadedChunk(chunkX: Int, chunkZ: Int) = LayBaltQOL.mc.level
        ?.getChunk(chunkX, chunkZ, ChunkStatus.FULL, false)
        ?.takeUnless { it.javaClass.name == ModCompatibility.bobby_chunk && ! ModCompatibility.bobbyManagesWorlds }
}