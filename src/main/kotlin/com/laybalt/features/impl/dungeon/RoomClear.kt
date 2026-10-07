// Ported from Odin (https://github.com/odtheking/Odin), BSD-3-Clause, Copyright (c) 2025 odtheking
package com.laybalt.features.impl.dungeon

import com.laybalt.config.types.DropdownSetting
import com.laybalt.config.types.ToggleSetting
import com.laybalt.event.impl.DungeonEvent
import com.laybalt.features.Feature
import com.laybalt.utils.ChatUtils
import com.laybalt.utils.dungeons.map.core.RoomState
import com.laybalt.utils.dungeons.map.core.RoomType
import com.laybalt.utils.dungeons.map.utils.ScanUtils
import com.laybalt.utils.equalsOneOf
import gg.essential.universal.USound
import net.minecraft.sounds.SoundEvents

object RoomClear: Feature("Notifies you when the dungeon room you are in is cleared.") {
    private val mode by DropdownSetting("Mode", 0, listOf("Both", "Green", "White"))
        .withDescription("Notify when the room's checkmark turns green (all secrets), white (cleared), or both")
    private val playSound by ToggleSetting("Play Sound", true)
        .withDescription("Plays a ding together with the title")

    override fun init() {
        register<DungeonEvent.RoomEvent.onStateChange> {
            val room = event.room
            if (ScanUtils.currentRoom != room) return@register
            val state = event.newState
            if (! state.equalsOneOf(RoomState.GREEN, RoomState.CLEARED) || state == event.oldState) return@register
            if (room.data.type.equalsOneOf(RoomType.FAIRY, RoomType.ENTRANCE)) return@register

            val green = state == RoomState.GREEN
            if (mode.value == 1 && ! green) return@register
            if (mode.value == 2 && green) return@register

            ChatUtils.showTitle(if (green) "§aRoom Complete!" else "Room Cleared!")
            if (playSound.value) USound.playSoundStatic(SoundEvents.NOTE_BLOCK_PLING, 1f, 1f)
        }
    }
}
