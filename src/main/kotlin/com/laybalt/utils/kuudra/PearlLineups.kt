// Ported from Odin (https://github.com/odtheking/Odin), BSD-3-Clause, Copyright (c) 2025 odtheking
package com.laybalt.utils.kuudra

import net.minecraft.core.BlockPos
import java.awt.Color

/**
 * Preset ender pearl lineups for the Kuudra supply phase: stand on one of [startPos] and aim at one of
 * [lineups] to land the pearl on the supply's drop-off pile.
 */
data class PearlLineup(val supply: Supply, val startPos: Set<BlockPos>, val lineups: Set<BlockPos>, val color: Color)

object PearlLineups {
    private val RED = Color(0xFF5555)
    private val LIGHT_PURPLE = Color(0xFF55FF)
    private val YELLOW = Color(0xFFFF55)
    private val WHITE = Color(0xFFFFFF)
    private val AQUA = Color(0x55FFFF)
    private val GREEN = Color(0x55FF55)
    private val BLUE = Color(0x5555FF)

    /** The block of the Square lineup that throws onto each supply's pile. */
    val squareLineupFor = mapOf(
        Supply.xCannon to BlockPos(- 59, 106, - 59),
        Supply.X to BlockPos(- 58, 127, - 148),
        Supply.Shop to BlockPos(- 146, 107, - 60),
        Supply.Triangle to BlockPos(- 149, 104, - 70),
        Supply.Equals to BlockPos(- 168, 124, - 118),
        Supply.Slash to BlockPos(- 65, 109, - 162)
    )

    val lineups = listOf(
        PearlLineup(Supply.Shop, setOf(BlockPos(- 71, 79, - 135), BlockPos(- 86, 78, - 129)), setOf(BlockPos(- 146, 107, - 60), BlockPos(- 147, 111, - 69)), RED),
        PearlLineup(Supply.Triangle, setOf(BlockPos(- 68, 77, - 123)), setOf(BlockPos(- 149, 104, - 70)), LIGHT_PURPLE),
        PearlLineup(Supply.X, setOf(BlockPos(- 135, 77, - 139)), setOf(BlockPos(- 59, 115, - 71)), YELLOW),
        PearlLineup(Supply.xCannon, setOf(BlockPos(- 131, 79, - 114)), setOf(BlockPos(- 59, 106, - 59), BlockPos(- 51, 108, - 67), BlockPos(- 39, 93, - 76)), WHITE),
        PearlLineup(
            Supply.Square, setOf(BlockPos(- 141, 78, - 91)),
            setOf(
                BlockPos(- 59, 106, - 59), // cannon
                BlockPos(- 58, 127, - 148), // X
                BlockPos(- 146, 107, - 60), // shop
                BlockPos(- 149, 104, - 70), // triangle
                BlockPos(- 168, 124, - 118), // equals
                BlockPos(- 65, 109, - 162) // slash
            ),
            AQUA
        ),
        PearlLineup(Supply.Equals, setOf(BlockPos(- 66, 76, - 88)), setOf(BlockPos(- 168, 124, - 118)), GREEN),
        PearlLineup(Supply.Slash, setOf(BlockPos(- 115, 77, - 69)), setOf(BlockPos(- 65, 109, - 162)), BLUE)
    )

    /** Lineups ordered by the distance of their closest start position to [pos]. */
    fun ordered(pos: BlockPos) = lineups.sortedBy { lineup -> lineup.startPos.minOf { it.distSqr(pos) } }
}
