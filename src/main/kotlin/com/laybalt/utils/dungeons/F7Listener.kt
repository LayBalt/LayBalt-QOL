package com.laybalt.utils.dungeons

import com.laybalt.event.EventBus
import com.laybalt.event.impl.BossBarUpdateEvent
import com.laybalt.event.impl.ChatMessageEvent
import com.laybalt.event.impl.DungeonEvent
import com.laybalt.event.impl.MainThreadPacketReceivedEvent
import com.laybalt.event.impl.WorldChangeEvent
import com.laybalt.init.types.ISelfInit
import com.laybalt.utils.ChatUtils.unformattedText
import com.laybalt.utils.location.LocationUtils.dungeonFloorNumber
import com.laybalt.utils.location.LocationUtils.inBoss
import net.minecraft.network.protocol.game.ClientboundSoundPacket
import net.minecraft.sounds.SoundEvents

/**
 * F7/M7 boss milestones that no longer have a reliable dialogue line since the dungeon update:
 * Necron's death and the start of the Wither King phase (his boss bar appears). From NoammAddons (ba028bc1).
 */
object F7Listener: ISelfInit {
    private var necronDead = false
    private var necronStart = false
    private var witherKingStart = false

    /** The F7/M7 core entrance has opened this run ("The Core entrance is opening!", NoammAddons 81339d4f). */
    var coreOpen = false
        private set

    override fun init() {
        EventBus.register<ChatMessageEvent> {
            when (event.unformattedText) {
                "[BOSS] Necron: ARGH!" -> necronStart = true
                "The Core entrance is opening!" -> if (dungeonFloorNumber == 7 && inBoss) coreOpen = true
            }
        }

        // NoammAddons 6a656831: Necron dies on the server with a WITHER_HURT sound at volume 15 and pitch 1 after his
        // "ARGH!" line; this replaced the empty boss bar, which comes later. Devonian users get the packet through
        // MixinDevonianEventBus (NoammAddons b9b41f83); receiveCancelled so a feature hiding the sound cannot hide the death.
        EventBus.register<MainThreadPacketReceivedEvent.Pre>(receiveCancelled = true) {
            if (! necronStart || necronDead) return@register
            val packet = event.packet as? ClientboundSoundPacket ?: return@register
            if (packet.sound.value() != SoundEvents.WITHER_HURT) return@register
            if (packet.volume != 15f || packet.pitch != 1f) return@register

            necronDead = true
            EventBus.post(DungeonEvent.NecronDeathEvent)
        }

        EventBus.register<BossBarUpdateEvent> {
            if (dungeonFloorNumber != 7 || ! inBoss) return@register
            val name = event.name.unformattedText

            // NoammAddons only checks this on an empty bar; its first version (605cac19) fired on any Wither King bar,
            // which is kept here so the phase start is not missed if the bar appears with progress.
            // NoammAddons 6a656831 dropped this event and starts P5 on Necron's death; LayBaltQOL keeps the boss bar
            // as the start of P5 so relic place times stay comparable with the saved PBs.
            if (name.contains("wither king", ignoreCase = true) && ! witherKingStart) {
                witherKingStart = true
                EventBus.post(DungeonEvent.WitherKingStartEvent)
            }

            // Fallback if the death sound was missed: Necron's boss bar empties. necronDead makes the event fire once.
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
            coreOpen = false
        }
    }
}
