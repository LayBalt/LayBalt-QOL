package com.laybalt.utils.dungeons.map.core

/**
 * [ordinal] matters here, should be in the order of what can happen to a room.
 */
enum class RoomState {
    GREEN,
    CLEARED,
    DISCOVERED,
    FAILED,
    UNOPENED,
    UNDISCOVERED
}