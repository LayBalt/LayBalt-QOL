// Ported from Odin (https://github.com/odtheking/Odin), BSD-3-Clause, Copyright (c) 2025 odtheking
package com.laybalt.features.impl.dungeon

import com.laybalt.config.types.SliderSetting
import com.laybalt.config.types.ToggleSetting
import com.laybalt.event.impl.ChatMessageEvent
import com.laybalt.event.impl.WorldChangeEvent
import com.laybalt.features.Feature
import com.laybalt.features.impl.visual.WarpCooldown
import com.laybalt.utils.ChatUtils
import com.laybalt.utils.NumbersUtils.toFixed
import com.laybalt.utils.ThreadUtils
import com.laybalt.utils.render.Render2D.drawString
import com.laybalt.utils.render.RenderHelper.width

object DungeonQueue: Feature("Requeues dungeons automatically, shows the warp cooldown and announces kicks.") {
    private val warpHud by ToggleSetting("Warp Cooldown HUD", true)
        .withDescription("Shows the 30 second cooldown after someone enters a dungeon (move it with the HUD editor). Hidden while the Warp Cooldown feature is on.")
    private val announceKick by ToggleSetting("Announce Kick")
        .withDescription("Sends \"I was kicked!\" in party chat when you are kicked while joining a dungeon instance")
    private val autoRequeue by ToggleSetting("Auto Requeue")
        .withDescription("Runs /instancerequeue after the end-of-run stats (\"> EXTRA STATS <\") of a dungeon. Does nothing while the Auto Requeue feature is on.")
    private val requeueDelay by SliderSetting("Requeue Delay", 2, 0, 30, 1, "s").showIf { autoRequeue.value }
        .withDescription("Seconds to wait before requeuing")
    private val disablePartyLeave by ToggleSetting("Disable on leave/kick", true).showIf { autoRequeue.value }
        .withDescription("Skips the next requeue when someone leaves the party, is kicked or the party is disbanded")

    private val enterRegex = Regex("^-*\\n\\[[^]]+] (\\w+) entered (?:MM )?\\w+ Catacombs, Floor (\\w+)!\\n-*$")
    private val timeoutExceptionRegex = Regex("^Exception Connecting:ReadTimeoutException : null$")
    private val kickedInstanceRegex = Regex("^You are no longer allowed to access this instance!$")
    private val kickedJoiningRegex = Regex("^You were kicked while joining that server!$")
    private val extraStatsRegex = Regex(" {29}> EXTRA STATS <")

    private val partyLeaveRegexes = listOf(
        Regex("^(?:\\[[^]]*?] ?)?\\w{1,16} has left the party\\.$"),
        Regex("^(?:\\[[^]]*?] ?)?\\w{1,16} has been removed from the party\\.$"),
        Regex("^Kicked (?:\\[[^]]*?] ?)?\\w{1,16} because they were offline\\.$"),
        Regex("^(?:\\[[^]]*?] ?)?\\w{1,16} was removed from your party because they disconnected\\.$"),
        Regex("^The party was transferred to (?:\\[[^]]*?] ?)?\\w{1,16} because (?:\\[[^]]*?] ?)?\\w{1,16} left$"),
        Regex("^(?:\\[[^]]*?] ?)?\\w{1,16} has disbanded the party!$"),
        Regex("^You have been kicked from the party by (?:\\[[^]]*?] ?)?\\w{1,16}$"),
        Regex("^The party was disbanded because all invites expired and the party was empty\\.$"),
        Regex("^The party was disbanded because the party leader disconnected\\.$"),
        Regex("^You left the party\\.$"),
    )

    private var warpTimer = 0L
    private var disableRequeue = false

    override fun init() {
        hudElement("DungeonQueueWarp", enabled = { warpHud.value && ! WarpCooldown.enabled }) { ctx, example ->
            val remaining = warpTimer - System.currentTimeMillis()
            if (remaining <= 0 && ! example) return@hudElement 0 to 0
            val text = "§eWarp: §a${if (example) "30" else (remaining / 1000.0).toFixed(1)}s"
            ctx.drawString(text, 0, 0)
            text.width() to 9
        }

        // Extra Stats hides the "> EXTRA STATS <" line, so cancelled messages must still be seen here.
        register<ChatMessageEvent>(receiveCancelled = true) {
            val message = event.unformattedText
            when {
                announceKick.value && (message.matches(kickedJoiningRegex) || message.matches(kickedInstanceRegex) || message.matches(timeoutExceptionRegex)) ->
                    ChatUtils.sendCommand("pc I was kicked!")

                message.matches(enterRegex) -> warpTimer = System.currentTimeMillis() + 30_000L

                autoRequeue.value && ! AutoRequeue.enabled && message.matches(extraStatsRegex) -> {
                    if (disableRequeue.also { disableRequeue = false }) return@register
                    ThreadUtils.scheduledTask(requeueDelay.value * 20) {
                        if (! disableRequeue) ChatUtils.sendCommand("instancerequeue")
                    }
                }

                autoRequeue.value && disablePartyLeave.value && partyLeaveRegexes.any { it.matches(message) } -> disableRequeue = true
            }
        }

        register<WorldChangeEvent> { disableRequeue = false }
    }
}
