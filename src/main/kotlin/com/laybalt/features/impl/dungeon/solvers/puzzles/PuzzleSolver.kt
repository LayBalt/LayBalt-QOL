package com.laybalt.features.impl.dungeon.solvers.puzzles

import com.laybalt.event.impl.*
import com.laybalt.utils.render.world.RenderContext

sealed interface PuzzleSolver {
    val enabled: Boolean

    fun reset() {}
    fun onRoomExit() = reset()
    fun onStateChange(event: DungeonEvent.RoomEvent.onStateChange) {}
    fun onRoomEnter(event: DungeonEvent.RoomEvent.onEnter) {}
    fun onTick() {}
    fun onPacket(event: MainThreadPacketReceivedEvent.Pre) {}
    fun onChat(event: ChatMessageEvent) {}
    fun onRenderWorld(ctx: RenderContext) {}
    fun onInteract(event: PlayerInteractEvent.RIGHT_CLICK.BLOCK) {}
}