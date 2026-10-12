// Ported from Odin (https://github.com/odtheking/Odin), BSD-3-Clause, Copyright (c) 2025 odtheking
package com.laybalt.features.impl.dungeon

import com.laybalt.LayBaltQOL
import com.laybalt.config.types.TextInputSetting
import com.laybalt.config.types.ToggleSetting
import com.laybalt.event.impl.ChatMessageEvent
import com.laybalt.event.impl.WorldChangeEvent
import com.laybalt.features.Feature
import com.laybalt.utils.ChatUtils
import com.laybalt.utils.ThreadUtils
import com.laybalt.utils.dungeons.DungeonListener
import com.laybalt.utils.dungeons.DungeonPlayer
import com.laybalt.utils.network.ProfileUtils
import com.laybalt.utils.network.WebUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

object SecretsCounter: Feature("Counts the secrets each player found and prints them at the end of a dungeon run.") {
    private val includeSelf by ToggleSetting("Include Yourself")
        .withDescription("Also counts your own secrets (read silently with /secretcount)")
    private val apiUrl by TextInputSetting("API URL", "https://api.odtheking.com/hypixel/")
        .withDescription("Server that returns a player's total secrets at <url>secrets/<uuid> (Odin's public API). Teammates' Minecraft UUIDs are sent to it; leave empty to count only yourself")

    private val dungeonStartRegex = Regex("^\\[NPC] Mort: Good luck\\.$")
    private val dungeonEndRegex = Regex("^\\s*(?:Master Mode )?(?:The )?Catacombs - (?:Entrance|Floor .{1,3})$")

    /** The configured stats server without a trailing slash, or "" when none is set (also used by /lbq cata). */
    val apiBaseUrl: String get() = apiUrl.value.trim().removeSuffix("/")

    private val baseline = ConcurrentHashMap<String, Long>()
    private var snapshotDone = false

    override fun init() {
        // The baseline is kept across runs (Odin f48d2cb): the end-of-run total is the next run's start value,
        // so teammates already known are not requested from the API again.
        register<WorldChangeEvent> {
            snapshotDone = false
        }

        register<ChatMessageEvent>(receiveCancelled = true) {
            val message = event.unformattedText
            if (dungeonEndRegex.containsMatchIn(message)) ThreadUtils.scheduledTask(30) { fetchAndDisplay() }

            if (snapshotDone || ! dungeonStartRegex.containsMatchIn(message)) return@register
            val players = players().ifEmpty { return@register }
            snapshotDone = true
            LayBaltQOL.scope.launch(Dispatchers.IO) {
                for (player in players) {
                    if (baseline.containsKey(player.name)) continue
                    pullSecrets(player.name).onSuccess { baseline[player.name] = it }
                }
            }
        }
    }

    private fun players(): List<DungeonPlayer> =
        if (includeSelf.value) DungeonListener.dungeonTeammates.toList() else DungeonListener.dungeonTeammatesNoSelf.toList()

    private fun fetchAndDisplay() {
        val players = players().ifEmpty { return }
        baseline.keys.retainAll(players.map { it.name }.toSet())
        LayBaltQOL.scope.launch(Dispatchers.IO) {
            val delta = HashMap<String, Long?>()
            for (player in players) {
                val before = baseline[player.name]
                pullSecrets(player.name).onSuccess { after ->
                    delta[player.name] = before?.let { (after - it).coerceAtLeast(0L) }
                    baseline[player.name] = after
                }
            }

            players.sortedWith(compareBy<DungeonPlayer> { it.clazz.ordinal }.thenByDescending { delta[it.name] ?: - 1L }).forEach { player ->
                ChatUtils.modMessage("${player.clazz.code}${player.name} §7-> §f${delta[player.name]?.toString() ?: "N/A"} Secrets")
            }
        }
    }

    private suspend fun pullSecrets(name: String): Result<Long> {
        if (name.equals(LayBaltQOL.mc.user.name, true)) return ProfileUtils.getSecrets(name)
        val base = apiUrl.value.trim().ifEmpty { return Result.failure(IllegalStateException("no API URL set")) }
        val uuid = ProfileUtils.getUUIDbyName(name).getOrElse { return Result.failure(it) }.uuid
        return WebUtils.getAs<String>("${base.removeSuffix("/")}/secrets/$uuid").mapCatching {
            it.trim().toLongOrNull() ?: error("unexpected answer: $it")
        }
    }
}
