package com.laybalt.utils

import com.laybalt.LayBaltQOL.mc
import com.laybalt.event.EventBus
import com.laybalt.event.impl.MainThreadPacketReceivedEvent
import com.laybalt.event.priority.EventPriority
import com.laybalt.init.types.ISelfInit
import com.google.common.collect.ComparisonChain
import net.minecraft.client.multiplayer.PlayerInfo
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket
import net.minecraft.world.level.GameType

object TabListUtils: ISelfInit {
    private var cachedLines: List<Pair<Component, PlayerInfo>> = emptyList()
    private var listDirty = true

    override fun init() {
        EventBus.register<MainThreadPacketReceivedEvent.Post>(EventPriority.HIGHEST) {
            if (event.packet is ClientboundPlayerInfoUpdatePacket) {
                listDirty = true
            }
        }
    }

    fun getTabList(): List<Pair<Component, PlayerInfo>> {
        if (listDirty) {
            cachedLines = fetchTabList()
            listDirty = false
        }
        return cachedLines
    }

    private fun fetchTabList(): List<Pair<Component, PlayerInfo>> {
        val player = mc.player ?: return emptyList()
        val onlinePlayers = player.connection.onlinePlayers
        val sortedPlayers = onlinePlayers.sortedWith(PlayerComparator)
        val result = mutableListOf<Pair<Component, PlayerInfo>>()
        for (info in sortedPlayers) result.add(

            mc.gui.hud.tabList.getNameForDisplay(info) to info)
        return if (result.size > 80) result.subList(0, 80) else result
    }

    private object PlayerComparator: Comparator<PlayerInfo> {
        override fun compare(o1: PlayerInfo, o2: PlayerInfo): Int {
            return ComparisonChain.start()
                .compareTrueFirst(o1.gameMode != GameType.SPECTATOR, o2.gameMode != GameType.SPECTATOR)
                .compare(o1.team?.name.orEmpty(), o2.team?.name.orEmpty())
                .compare(o1.profile.name, o2.profile.name)
                .result()
        }
    }
}