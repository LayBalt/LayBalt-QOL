package com.laybalt.utils.dungeons.map.core

import java.awt.Color

class Unknown(override val x: Int, override val z: Int): Tile {
    override var state: RoomState = RoomState.UNDISCOVERED
    override fun getColor() = Color(0, 0, 0, 0)
}