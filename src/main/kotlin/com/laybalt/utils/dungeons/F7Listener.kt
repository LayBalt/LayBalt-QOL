package com.laybalt.utils.dungeons

import com.laybalt.event.EventBus
import com.laybalt.event.impl.BossBarUpdateEvent
import com.laybalt.event.impl.ChatMessageEvent
import com.laybalt.event.impl.DungeonEvent
import com.laybalt.event.impl.WorldChangeEvent
import com.laybalt.init.types.ISelfInit
import com.laybalt.utils.ChatUtils.unformattedText
import com.laybalt.utils.location.LocationUtils.dungeonFloorNumber
import com.laybalt.utils.location.LocationUtils.inBoss

/**
 * F7/M7 boss milestones that no longer have a reliable dialogue line since the dungeon update:
 * Necron's death (his boss bar empties after "[BOSS] Necron: ARGH!") and the start of the Wither King phase
 * (his boss bar appears). From NoammAddons (ba028bc1).
 */
object F7Listener: ISelfInit {
    private var necronDead = false
    private var necronStart = false
    private var witherKingStart = false

    override fun init() {
        EventBus.register<ChatMessageEvent> {
            if (event.unformattedText == "[BOSS] Necron: ARGH!") necronStart = true
        }

        EventBus.register<BossBarUpdateEvent> {
            if (dungeonFloorNumber != 7 || ! inBoss) return@register
            val name = event.name.unformattedText

            // NoammAddons only checks this on an empty bar; its first version (605cac19) fired on any Wither King bar,
            // which is kept here so the phase start is not missed if the bar appears with progress.
            if (name.contains("wither king", ignoreCase = true) && ! witherKingStart) {
                witherKingStart = true
                EventBus.post(DungeonEvent.WitherKingStartEvent)
            }

            if (event.progress > 0f) return@register
            if (name.contains("Necron") && ! necronDead && necronStart) {
                necronDead = true
                EventBus.post(DungeonEvent.NecronDeathEvent)
            }
        }

        EventBus.register<WorldChangeEvent> {
            necronDead = false
            necronStart = false
            witherKingStart = false
        }
    }
}
