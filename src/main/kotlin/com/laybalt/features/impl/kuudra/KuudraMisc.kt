// Ported from Odin (https://github.com/odtheking/Odin), BSD-3-Clause, Copyright (c) 2025 odtheking
package com.laybalt.features.impl.kuudra

import com.laybalt.config.types.SliderSetting
import com.laybalt.config.types.ToggleSetting
import com.laybalt.event.impl.ChatMessageEvent
import com.laybalt.features.Feature
import com.laybalt.features.impl.general.PartyHelper
import com.laybalt.utils.ChatUtils
import com.laybalt.utils.PartyUtils
import com.laybalt.utils.ThreadUtils
import com.laybalt.utils.location.LocationUtils

object KuudraMisc: Feature("Small Kuudra helpers: mana drain callout and automatic requeue after a run.", "Kuudra Misc") {
    private val manaDrain by ToggleSetting("Mana Drain", true)
        .withDescription("When you use Extreme Focus (End Stone Sword), tells your party how much mana you drained and how many players were within 7 blocks.")
        .section("Mana Drain")

    private val autoRequeue by ToggleSetting("Auto Requeue", true)
        .withDescription("Runs /instancerequeue automatically when Elle announces the end of a Kuudra run.")
        .section("Auto Requeue")
    private val requeueDelay by SliderSetting("Requeue Delay", 20, 0, 100, 1, " ticks")
        .withDescription("How long to wait after the end of the run before requeueing (20 ticks = 1 second).").showIf { autoRequeue.value }
    private val checkParty by ToggleSetting("Check Party", true)
        .withDescription("When you are in a party, only requeues if you are the party leader and nobody asked for downtime (!dt).").showIf { autoRequeue.value }
    private val feedback by ToggleSetting("Feedback", true)
        .withDescription("Prints a chat message when the auto requeue is skipped and why.").showIf { autoRequeue.value && checkParty.value }

    private const val END_RUN_LINE = "[NPC] Elle: Good job everyone. A hard fought battle come to an end. Let's get out of here before we run into any more trouble!"
    private val endStoneRegex = Regex("^Used Extreme Focus! \\((\\d+) Mana\\)$")

    override fun init() {
        register<ChatMessageEvent> {
            if (! LocationUtils.inSkyblock) return@register
            val message = event.unformattedText

            if (manaDrain.value) endStoneRegex.find(message)?.groupValues?.get(1)?.let { mana ->
                val player = mc.player ?: return@let
                val players = mc.level?.players()?.count { it.distanceToSqr(player) < 49 && it.uuid.version() == 4 } ?: return@let
                ChatUtils.sendCommand("pc Used $mana mana ($players players nearby)")
            }

            if (autoRequeue.value && message == END_RUN_LINE) {
                if (checkParty.value && ! canRequeue()) return@register
                ThreadUtils.scheduledTask(requeueDelay.value) {
                    if (checkParty.value && ! canRequeue()) return@scheduledTask
                    ChatUtils.sendCommand("instancerequeue")
                }
            }
        }
    }

    /** Same checks as NoammAddons' dungeon AutoRequeue: solo players always requeue; in a party only the leader, and never while someone needs downtime. */
    private fun canRequeue(): Boolean {
        val reason = when {
            ! PartyUtils.isInParty -> return true
            ! PartyUtils.isLeader() -> "You are not the party leader!"
            PartyHelper.downtimeList.isNotEmpty() -> "There are players in downtime!"
            else -> return true
        }
        if (feedback.value) ChatUtils.modMessage("&bAuto Requeue &f> $reason")
        return false
    }
}
