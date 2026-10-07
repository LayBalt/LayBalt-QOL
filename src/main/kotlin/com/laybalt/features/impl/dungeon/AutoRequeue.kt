package com.laybalt.features.impl.dungeon

import com.laybalt.config.types.DropdownSetting
import com.laybalt.config.types.SliderSetting
import com.laybalt.config.types.ToggleSetting
import com.laybalt.event.impl.DungeonEvent
import com.laybalt.features.Feature
import com.laybalt.features.impl.general.PartyHelper
import com.laybalt.utils.ChatUtils
import com.laybalt.utils.PartyUtils
import com.laybalt.utils.ThreadUtils
import com.laybalt.utils.dungeons.DungeonUtils
import com.laybalt.utils.location.LocationUtils

object AutoRequeue: Feature() {
    private val requeueCmd by DropdownSetting("Requeue Command", 0, listOf("/joininstance", "/instancerequeue"))
    private val checkParty by ToggleSetting("Check Party", true).withDescription("Should the auto check the party state before running the command.")
    private val delay by SliderSetting("Delay", 5L, 1L, 10L, 1L).withDescription("Delay in Seconds.")
    private val feedback by ToggleSetting("Feedback", true).withDescription("Print feedback messages from auto in chat.")

    private const val prefix = "&bAutoRequeue &f>"
    private val masterMode get() = if (LocationUtils.isMasterMode) "MASTER_" else ""
    private val floor get() = DungeonUtils.FLOOR_NAMES[LocationUtils.dungeonFloorNumber ?: 0]

    private fun feedBackMessage(msg: String) {
        if (! feedback.value) return
        ThreadUtils.setTimeout(1000) { ChatUtils.modMessage("$prefix $msg") }
    }

    override fun init() {
        register<DungeonEvent.RunEndedEvent> {
            if (checkParty.value) {
                if (! PartyUtils.isInParty) return@register feedBackMessage("Not in a party!")
                if (PartyUtils.members.size != 5) return@register feedBackMessage("Not enough players in party!")
                if (PartyHelper.downtimeList.isNotEmpty()) return@register feedBackMessage("There are players in downtime!")
            }

            ThreadUtils.setTimeout(delay.value * 1000) {
                if (checkParty.value && ! PartyUtils.isLeader()) return@setTimeout feedBackMessage("You are not the party leader!")
                if (requeueCmd.value == 0) ChatUtils.sendMessage("/joininstance ${masterMode}CATACOMBS_FLOOR_${floor}")
                else ChatUtils.sendMessage("/instancerequeue")
            }
        }
    }
}