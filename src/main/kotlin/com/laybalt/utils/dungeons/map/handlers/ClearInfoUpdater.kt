package com.laybalt.utils.dungeons.map.handlers

import com.laybalt.LayBaltQOL
import com.laybalt.event.EventBus
import com.laybalt.event.impl.DungeonEvent
import com.laybalt.features.impl.dungeon.map.DungeonMap
import com.laybalt.features.impl.dungeon.map.MapConfig
import com.laybalt.init.types.ISelfInit
import com.laybalt.utils.ChatUtils
import com.laybalt.utils.ChatUtils.addColor
import com.laybalt.utils.dungeons.DungeonListener
import com.laybalt.utils.dungeons.DungeonPlayer
import com.laybalt.utils.dungeons.map.core.*
import com.laybalt.utils.equalsOneOf
import com.laybalt.utils.network.ProfileUtils
import kotlinx.coroutines.*
import net.minecraft.network.chat.*

object ClearInfoUpdater: ISelfInit {
    private val componentSeparator = createComponent(" &f|&r ")

    override fun init() {
        EventBus.register<DungeonEvent.RunStatedEvent> { initStartSecrets() }
        EventBus.register<DungeonEvent.PlayerDeathEvent> { updateDeaths(event.name, event.reason) }
        EventBus.register<DungeonEvent.RoomEvent.onStateChange> { checkSplits(event.room.data, event.oldState, event.newState, event.roomPlayers) }
        EventBus.register<DungeonEvent.RunEndedEvent> { sendClearInfoMessage() }
    }

    fun checkSplits(room: RoomData, oldState: RoomState, newState: RoomState, players: List<DungeonPlayer>) {
        if (! DungeonMap.enabled) return
        if (! MapConfig.printPlayersClearInfo.value) return
        if (players.isEmpty()) return
        if (room.type.equalsOneOf(RoomType.FAIRY, RoomType.ENTRANCE)) return
        if (! oldState.equalsOneOf(RoomState.UNDISCOVERED, RoomState.DISCOVERED, RoomState.UNOPENED)) return
        if (! newState.equalsOneOf(RoomState.CLEARED, RoomState.GREEN)) return

        if (players.size == 1) DungeonListener.dungeonTeammates.find { it.name == players[0].name }?.clearedRooms?.first?.add(room.name)
        else players.forEach { p -> DungeonListener.dungeonTeammates.find { it.name == p.name }?.clearedRooms?.second?.add(room.name) }
    }

    fun updateDeaths(player: String, reason: String) {
        if (! DungeonMap.enabled) return
        if (! MapConfig.printPlayersClearInfo.value) return
        DungeonListener.dungeonTeammates.find { it.name == player }?.deaths?.add(reason)
    }

    fun initStartSecrets() = LayBaltQOL.scope.launch(Dispatchers.IO) {
        if (! DungeonMap.enabled) return@launch
        if (! MapConfig.printPlayersClearInfo.value) return@launch
        coroutineScope {
            val jobs = DungeonListener.dungeonTeammates.map { teammate ->
                async {
                    // Only your own count can be read (/secretcount); other players' lookups have no backend.
                    // An unknown count stays 0 and the run summary leaves the secrets part out for that player.
                    ProfileUtils.getSecrets(teammate.name).onSuccess {
                        teammate.secretsBeforeRun = it
                    }.onFailure {
                        teammate.secretsBeforeRun = 0
                        if (it !is ProfileUtils.StatsUnavailableException) {
                            LayBaltQOL.logger.debug("Could not read secrets of {}: {}", teammate.name, it.message)
                        }
                    }
                }
            }

            jobs.awaitAll()
        }
    }

    fun sendClearInfoMessage() = LayBaltQOL.scope.launch(Dispatchers.IO) {
        if (! DungeonMap.enabled) return@launch
        if (! MapConfig.printPlayersClearInfo.value) return@launch
        val msgList = DungeonListener.dungeonTeammates.map { teammate ->
            async {
                val before = teammate.secretsBeforeRun
                val foundSecrets = if (before > 0L) ProfileUtils.getSecrets(teammate.name).getOrNull()?.let { it - before } else null
                val playerFormatted = "${teammate.clazz.code}${teammate.name}"

                val baseComp = LayBaltQOL.PREFIX.copy().append(Component.literal(" $playerFormatted&f:&r ".addColor()))
                val (solo, stacked) = teammate.clearedRooms
                val deaths = teammate.deaths

                val comps = listOfNotNull(
                    getRoomsClearedComponent(solo, stacked),
                    foundSecrets?.let { createComponent("&b$it Secrets&r") },
                    if (deaths.isEmpty()) null else createComponent("&c${deaths.size} ${if (deaths.size > 1) "Deaths" else "Death"}", deaths.joinToString("\n")),
                )

                comps.forEachIndexed { i, c ->
                    baseComp.append(c)
                    if (i != comps.lastIndex) {
                        baseComp.append(componentSeparator)
                    }
                }

                baseComp
            }
        }

        msgList.awaitAll().forEach(ChatUtils::chat)
    }

    private fun getRoomsClearedComponent(solo: Collection<String>, stacked: Collection<String>): MutableComponent {
        return if (solo.size + stacked.size == 0) createComponent("&e0 Rooms&r")
        else {
            val roomRange = if (stacked.isEmpty()) "${solo.size}" else "${solo.size}-${solo.size + stacked.size}"
            val tooltip = buildString {
                append(solo.joinToString("\n") { "$it &b(Solo)&r" })
                if (solo.isNotEmpty() && stacked.isNotEmpty()) append("\n")
                append(stacked.joinToString("\n") { "$it &d(stack)&r" })
            }

            createComponent("&e$roomRange Rooms&r", tooltip)
        }
    }

    private fun createComponent(text: String, hoverText: String? = null): MutableComponent {
        val comp = Component.literal(text.addColor())

        if (hoverText != null) {
            return comp.withStyle { s ->
                s.withHoverEvent(HoverEvent.ShowText(Component.literal(hoverText.addColor())))
            }
        }
        return comp
    }
}