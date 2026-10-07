package com.laybalt.features.impl.dungeon

import com.laybalt.config.types.ToggleSetting
import com.laybalt.event.impl.ChatMessageEvent
import com.laybalt.features.Feature
import com.laybalt.utils.ChatUtils
import com.laybalt.utils.ThreadUtils
import com.laybalt.utils.dungeons.map.core.RoomType
import com.laybalt.utils.dungeons.map.utils.ScanUtils
import com.laybalt.utils.location.LocationUtils

object ArchitectDraft: Feature("Architect Draft") {
    private val sayDraft by ToggleSetting("Announce Draft", true)

    //#if CHEAT
    private val autoDraft by ToggleSetting("Auto Draft", true)
    //#endif

    private val resetPattern = Regex("^You used the Architect's First Draft to reset (.+)!$")
    private val failPattern1 = Regex("^PUZZLE FAIL! (?<player>\\w{1,16}) .+$")
    private val failPattern2 = Regex("^\\[STATUE] Oruo the Omniscient: (?<player>\\w{1,16}) chose the wrong answer! I shall never forget this moment of misrememberance\\.$")

    override fun init() {
        register<ChatMessageEvent> {
            if (! LocationUtils.inDungeon || LocationUtils.inBoss) return@register
            if (ScanUtils.currentRoom?.data?.type != RoomType.PUZZLE) return@register
            val msg = event.unformattedText

            if (sayDraft.value) resetPattern.find(msg)?.destructured?.component1()?.let { puzzleName ->
                ChatUtils.sendPartyMessage("Used Draft to Reset $puzzleName")
            }

            //#if CHEAT
            if (autoDraft.value) {
                val match = failPattern1.find(msg) ?: failPattern2.find(msg)
                val name = match?.groups?.get("player")?.value
                if (name == mc.user.name) ThreadUtils.setTimeout(1500) {
                    ChatUtils.sendCommand("/gfs ARCHITECT_FIRST_DRAFT 1")
                }
            }
            //#endif
        }
    }
}