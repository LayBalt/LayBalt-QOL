package com.laybalt.utils

import com.laybalt.LayBaltQOL
import com.laybalt.event.EventBus
import com.laybalt.event.priority.EventPriority
import com.laybalt.event.impl.MainThreadPacketReceivedEvent
import com.laybalt.init.types.ISelfInit
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.*
import net.minecraft.world.scores.DisplaySlot
import net.minecraft.world.scores.PlayerTeam

object ScoreboardUtils: ISelfInit {
    private var cachedLines: List<Component> = emptyList()
    private var listDirty = true

    private val updatePackets = setOf(
        ClientboundSetScorePacket::class, ClientboundSetObjectivePacket::class,
        ClientboundSetDisplayObjectivePacket::class, ClientboundResetScorePacket::class,
        ClientboundSetPlayerTeamPacket::class
    )

    override fun init() {
        EventBus.register<MainThreadPacketReceivedEvent.Post>(EventPriority.HIGHEST) {
            if (updatePackets.none { it.java.isInstance(event.packet) }) return@register
            listDirty = true
        }
    }

    fun getLines(): List<Component> {
        if (listDirty) {
            cachedLines = fetchScoreboard()
            listDirty = false
        }
        return cachedLines
    }

    private fun fetchScoreboard(): List<Component> {
        val scoreboard = LayBaltQOL.mc.level?.scoreboard ?: return emptyList()
        val objective = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR) ?: return emptyList()

        val lines = mutableListOf(objective.displayName)
        // like vanilla: hidden scores skipped, the team looked up by the owner (not by its display name)
        scoreboard.listPlayerScores(objective).filterNot { it.isHidden }.sortedByDescending { it.value }.take(15).forEach { score ->
            lines.add(PlayerTeam.formatNameForTeam(scoreboard.getPlayersTeam(score.owner), score.ownerName()).copy())
        }

        return lines
    }
}