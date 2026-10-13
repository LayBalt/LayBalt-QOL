package com.laybalt.features.impl.dungeon.solvers.puzzles

import com.laybalt.features.impl.dungeon.solvers.puzzles.water.BoardCodes
import com.laybalt.features.impl.dungeon.solvers.puzzles.water.BoardGeometry
import com.laybalt.features.impl.dungeon.solvers.puzzles.water.BoardTracker
import com.laybalt.features.impl.dungeon.solvers.puzzles.water.Door
import com.laybalt.features.impl.dungeon.solvers.puzzles.water.GateRead
import com.laybalt.features.impl.dungeon.solvers.puzzles.water.WaterLever
import com.laybalt.utils.WorldUtils
import com.laybalt.utils.dungeons.map.utils.ScanUtils
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.tags.BlockTags
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.LeverBlock
import net.minecraft.world.level.block.LiquidBlock
import net.minecraft.world.level.block.state.BlockState
import kotlin.math.abs

/**
 * The Water Board room in the world: maps room-relative coordinates (centre of the room, `ScanUtils.getRealCoord`) to
 * world positions and back. [rot] is the rotation passed to `getRealCoord` (360 - room rotation).
 */
class RoomFrame(val center: BlockPos, val rot: Int) {
    fun world(x: Int, y: Int, z: Int): BlockPos = ScanUtils.getRealCoord(BlockPos(x, y, z), center, rot)

    fun lever(lever: WaterLever): BlockPos = BoardGeometry.LEVER_POS[lever.ordinal].let { world(it[0], it[1], it[2]) }

    fun door(door: Door): BlockPos = world(0, BoardGeometry.DOOR_Y, door.z)

    /** Room-relative position of [pos], or null when it is outside the room's box (cheap reject first). */
    fun relative(pos: BlockPos): BlockPos? {
        if (pos.y !in Y_MIN .. Y_MAX) return null
        if (abs(pos.x - center.x) > HALF || abs(pos.z - center.z) > HALF) return null
        return ScanUtils.getRelativeCoord(pos, center, rot)
    }

    /**
     * Chunk columns (x, z) of every block the reader looks at: both board planes (and the planes around them), the doors
     * and the levers. Rooms start 8 blocks into a chunk, so the board alone can span three chunk columns per axis.
     */
    val chunks: List<Pair<Int, Int>> by lazy {
        val points = mutableListOf<BlockPos>()
        for (x in BoardGeometry.MIN_X .. BoardGeometry.MAX_X) for (z in BoardGeometry.FRONT_Z .. BoardGeometry.BACK_Z) {
            points += world(x, BoardGeometry.MIN_Y, z)
        }
        Door.entries.forEach { points += door(it) }
        WaterLever.entries.forEach { points += lever(it) }
        points.map { (it.x shr 4) to (it.z shr 4) }.distinct()
    }

    /** Whether the chunk column ([chunkX], [chunkZ]) holds part of the board. */
    fun covers(chunkX: Int, chunkZ: Int) = chunks.any { it.first == chunkX && it.second == chunkZ }

    companion object {
        private const val HALF = 16
        private const val Y_MIN = BoardGeometry.MIN_Y - 2
        private const val Y_MAX = BoardGeometry.MAX_Y + 2
    }
}

/**
 * Reads the Water Board from the client world into a [BoardTracker] (client thread only): a full read of both board
 * planes, the doors and the levers, and single block changes as they arrive.
 */
object WaterBoardReader {
    private val TOGGLES = mapOf(
        Blocks.QUARTZ_BLOCK to WaterLever.QUARTZ,
        Blocks.GOLD_BLOCK to WaterLever.GOLD,
        Blocks.COAL_BLOCK to WaterLever.COAL,
        Blocks.DIAMOND_BLOCK to WaterLever.DIAMOND,
        Blocks.EMERALD_BLOCK to WaterLever.EMERALD,
        Blocks.TERRACOTTA to WaterLever.CLAY,
    )
    private val PISTONS = setOf(Blocks.PISTON, Blocks.STICKY_PISTON, Blocks.PISTON_HEAD, Blocks.MOVING_PISTON)

    /** Material code of a block, by the 1.8 rules that decide whether water can flow into it. */
    fun code(state: BlockState): Byte {
        if (state.isAir) return BoardCodes.AIR
        val block = state.block
        if (block == Blocks.WATER) return BoardCodes.WATER
        TOGGLES[block]?.let { return BoardCodes.toggle(it.ordinal) }
        if (block in PISTONS) return BoardCodes.PISTON
        return if (state.blocksMotion()) BoardCodes.WALL else BoardCodes.OPEN
    }

    fun waterLevel(state: BlockState): Int = if (state.block == Blocks.WATER) state.getValue(LiquidBlock.LEVEL) else - 1

    /** What a door block says about its door. */
    enum class DoorBlock { OPEN, CLOSED, MOVING }

    /**
     * Hypixel's doors are wool blocks moved by sticky pistons. Wool (or any other solid block, Odin's rule) is a closed
     * door, air an open one; a piston, its head or a block being moved is a door in transit, which keeps its last state.
     */
    fun doorBlock(state: BlockState): DoorBlock = when {
        state.isAir -> DoorBlock.OPEN
        state.block in PISTONS -> DoorBlock.MOVING
        state.`is`(BlockTags.WOOL) || state.blocksMotion() -> DoorBlock.CLOSED
        else -> DoorBlock.OPEN
    }

    /** All chunks of the room are loaded (an unloaded chunk reads as air: "dry board, all doors open"). */
    fun loaded(frame: RoomFrame) = frame.chunks.all { (cx, cz) -> WorldUtils.isChunkLoaded(cx shl 4, cz shl 4) }

    /**
     * Reads everything; the tracker turns differences to its last picture into events. A position whose chunk is not
     * loaded is skipped (its last read stays): it would read as air, "dry board, every door open".
     */
    fun readAll(frame: RoomFrame, tracker: BoardTracker) {
        for (cell in 0 until BoardGeometry.N) {
            val x = BoardGeometry.x(cell)
            val y = BoardGeometry.y(cell)
            WorldUtils.getStateIfLoaded(frame.world(x, y, BoardGeometry.BOARD_Z))?.let { front ->
                tracker.setFront(cell, code(front))
                tracker.setWater(cell, waterLevel(front))
            }
            WorldUtils.getStateIfLoaded(frame.world(x, y, BoardGeometry.HIDDEN_Z))?.let { tracker.setBack(cell, code(it)) }
        }
        for (door in Door.entries) WorldUtils.getStateIfLoaded(frame.door(door))?.let { setDoor(tracker, door, it) }
        for (lever in WaterLever.entries) {
            val state = WorldUtils.getStateIfLoaded(frame.lever(lever)) ?: continue
            if (state.block is LeverBlock) tracker.setPowered(lever, state.getValue(LeverBlock.POWERED))
        }
    }

    private fun setDoor(tracker: BoardTracker, door: Door, state: BlockState) {
        when (doorBlock(state)) {
            DoorBlock.OPEN -> tracker.setDoor(door, false)
            DoorBlock.CLOSED -> tracker.setDoor(door, true)
            DoorBlock.MOVING -> {}
        }
    }

    /**
     * The door blocks read on their own, for the classic display: null when a door's chunk is not loaded (nothing can
     * be concluded then), otherwise the closed doors and the doors a piston is moving right now.
     */
    fun readGates(frame: RoomFrame): GateRead? {
        var closed = 0
        var moving = 0
        for (door in Door.entries) {
            val state = WorldUtils.getStateIfLoaded(frame.door(door)) ?: return null
            when (doorBlock(state)) {
                DoorBlock.CLOSED -> closed = closed or door.bit
                DoorBlock.MOVING -> moving = moving or door.bit
                DoorBlock.OPEN -> {}
            }
        }
        return GateRead(closed, moving)
    }

    /**
     * The layout variant from Odin's indicator blocks, in either plane (so a flipped lever does not hide it): null when
     * a chunk is not loaded, -1 when no indicator matches.
     */
    fun readVariant(frame: RoomFrame): Int? {
        for ((i, indicator) in BoardGeometry.INDICATORS.withIndex()) {
            val (x, y, lever) = indicator
            for (z in intArrayOf(BoardGeometry.HIDDEN_Z, BoardGeometry.BOARD_Z)) {
                val state = WorldUtils.getStateIfLoaded(frame.world(x, y, z)) ?: return null
                if (TOGGLES[state.block] == lever) return i
            }
        }
        return - 1
    }

    /** The blocks of every door's column (y 55..58), for the trace: "Purple white_wool/wool/air/air, ...". */
    fun doorColumns(frame: RoomFrame): String = Door.entries.joinToString(", ") { door ->
        door.displayName + " " + (BoardGeometry.DOOR_Y - 1 .. BoardGeometry.DOOR_Y + 2).joinToString("/") { y ->
            WorldUtils.getStateIfLoaded(frame.world(0, y, door.z))?.let { BuiltInRegistries.BLOCK.getKey(it.block).path } ?: "unloaded"
        }
    }

    /** Routes one block change to the tracker. Returns false when the position is not part of the board. */
    fun onChange(frame: RoomFrame, tracker: BoardTracker, pos: BlockPos, state: BlockState): Boolean {
        val rel = frame.relative(pos) ?: return false
        val x = rel.x
        val y = rel.y
        val z = rel.z
        if (BoardGeometry.inGrid(x, y) && (z == BoardGeometry.BOARD_Z || z == BoardGeometry.HIDDEN_Z)) {
            val cell = BoardGeometry.cell(x, y)
            if (z == BoardGeometry.BOARD_Z) {
                tracker.setFront(cell, code(state))
                tracker.setWater(cell, waterLevel(state))
            }
            else tracker.setBack(cell, code(state))
            return true
        }
        if (x == 0 && y == BoardGeometry.DOOR_Y) Door.entries.firstOrNull { it.z == z }?.let {
            setDoor(tracker, it, state)
            return true
        }
        for (lever in WaterLever.entries) {
            val p = BoardGeometry.LEVER_POS[lever.ordinal]
            if (p[0] == x && p[1] == y && p[2] == z) {
                if (state.block is LeverBlock) tracker.setPowered(lever, state.getValue(LeverBlock.POWERED))
                return true
            }
        }
        return false
    }

    /**
     * Open cells of the playable board whose neighbour in front (z 10) or behind (z 12) lets water through. The simulator
     * models the board as a flat plane; with such leaks it would be wrong, so it is not used on that board.
     */
    fun zLeaks(frame: RoomFrame, tracker: BoardTracker): Int {
        var leaks = 0
        for (y in BoardGeometry.TOGGLE_MIN_Y + 1 .. BoardGeometry.TOGGLE_MAX_Y) for (x in BoardGeometry.PLAY_MIN_X .. BoardGeometry.PLAY_MAX_X) {
            val cell = BoardGeometry.cell(x, y)
            if (! BoardCodes.passable(tracker.front[cell])) continue
            if (tracker.toggleLever[cell] >= 0) continue
            // An unloaded chunk is no leak (it would read as air): only blocks really read count.
            val front = WorldUtils.getStateIfLoaded(frame.world(x, y, BoardGeometry.FRONT_Z))?.let(::code) ?: continue
            if (BoardCodes.passable(front) || BoardCodes.passable(tracker.back[cell])) leaks ++
        }
        return leaks
    }

    /** One plane of the room (x -12..12, y 84..56) as text rows for dumps; see the recorder legend. */
    fun planeRows(frame: RoomFrame, z: Int): List<String> = (BoardGeometry.MAX_Y downTo BoardGeometry.MIN_Y).map { y ->
        buildString {
            for (x in - 12 .. 12) append(dumpChar(WorldUtils.getStateAt(frame.world(x, y, z))))
        }
    }

    private fun dumpChar(state: BlockState): Char {
        val block = state.block
        return when {
            state.isAir -> '.'
            block == Blocks.WATER -> '~'
            block == Blocks.STONE_BRICKS -> 'B'
            block == Blocks.POLISHED_ANDESITE -> 'p'
            block == Blocks.ANDESITE -> 'a'
            block in PISTONS -> 'P'
            TOGGLES.containsKey(block) -> "QGKDET"[TOGGLES.getValue(block).ordinal]
            state.blocksMotion() -> '#'
            else -> '?'
        }
    }

    /** Registry name of the block at [pos] (for dumps). */
    fun blockName(pos: BlockPos): String = BuiltInRegistries.BLOCK.getKey(WorldUtils.getStateAt(pos).block).toString()

    /** Powered state of the lever at [pos], null when there is no lever. */
    fun leverPowered(pos: BlockPos): Boolean? {
        val state = WorldUtils.getStateAt(pos)
        return if (state.block is LeverBlock) state.getValue(LeverBlock.POWERED) else null
    }
}
