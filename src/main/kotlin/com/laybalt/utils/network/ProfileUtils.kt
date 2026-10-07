package com.laybalt.utils.network

import com.laybalt.LayBaltQOL.mc
import com.laybalt.event.EventBus
import com.laybalt.event.impl.ChatMessageEvent
import com.laybalt.utils.*
import com.laybalt.utils.JsonUtils.getString
import com.laybalt.utils.dungeons.DungeonListener
import com.laybalt.utils.location.LocationUtils
import com.laybalt.utils.network.cache.*
import com.laybalt.utils.network.data.DungeonStats
import com.laybalt.utils.network.data.MojangData
import kotlinx.coroutines.delay
import kotlinx.serialization.json.jsonObject
import java.util.*
import java.util.concurrent.*

object ProfileUtils {
    class StatsUnavailableException: Exception("Player stats lookup is not available in LayBalt QOL")

    private val apiCooldowns = ConcurrentHashMap<String, Long>()

    private val nameToUuidApis = listOf(
        "https://mowojang.matdoes.dev/",
        "https://api.minecraftservices.com/minecraft/profile/lookup/name/",
        "https://api.mojang.com/users/profiles/minecraft/"
    )

    private val uuidToNameApis = listOf(
        "https://mowojang.matdoes.dev/",
        "https://sessionserver.mojang.com/session/minecraft/profile/",
        "https://mc-api.io/name/",
    )

    suspend fun getUUIDbyName(name: String): Result<MojangData> {
        val key = name.lowercase()
        MojangCache.check(key)?.let { return it }

        for (api in nameToUuidApis) {
            if (System.currentTimeMillis() < (apiCooldowns[api] ?: 0L)) continue

            val result = WebUtils.getAs<String>(api + key)
            if (result.isFailure) {
                val msg = result.exceptionOrNull()?.message ?: ""
                if (msg.contains("429")) {
                    apiCooldowns[api] = System.currentTimeMillis() + (5 * 60 * 1000)
                    continue
                }
                if (msg.containsOneOf("404", "204")) break
                continue
            }

            val response = catch { JsonUtils.json.parseToJsonElement(result.getOrThrow()).jsonObject } ?: continue
            val fetchedName = response.getString("name").takeUnless { it.isNullOrBlank() } ?: continue
            val uuid = response.getString("id").takeUnless { it.isNullOrBlank() } ?: continue

            val cleanUuid = uuid.replace("-", "")
            val data = MojangData(fetchedName, cleanUuid)
            MojangCache.addToCache(data)
            return Result.success(data)
        }

        return Result.failure<MojangData>(Exception("$name not found")).also { MojangCache.addFailedToCache(key) }
    }

    suspend fun getNameByUUID(uuid: UUID): Result<MojangData> {
        val key = uuid.toString().replace("-", "")
        MojangCache.check(key)?.let { return it }

        for (api in uuidToNameApis) {
            if (System.currentTimeMillis() < (apiCooldowns[api] ?: 0L)) continue

            val result = WebUtils.getAs<String>(api + key)
            if (result.isFailure) {
                val msg = result.exceptionOrNull()?.message ?: ""
                if (msg.contains("429")) {
                    apiCooldowns[api] = System.currentTimeMillis() + (5 * 60 * 1000)
                    continue
                }
                if (msg.containsOneOf("404", "204")) break
                continue
            }

            val response = catch { JsonUtils.json.parseToJsonElement(result.getOrThrow()).jsonObject } ?: continue
            val fetchedUuid = response.getString("id").takeUnless { it.isNullOrBlank() } ?: continue
            val fetchedName = response.getString("name").takeUnless { it.isNullOrBlank() } ?: continue

            val cleanUuid = fetchedUuid.replace("-", "")
            val data = MojangData(fetchedName, cleanUuid)
            MojangCache.addToCache(data)
            return Result.success(data)
        }

        return Result.failure<MojangData>(Exception("$key not found")).also { MojangCache.addFailedToCache(key) }
    }

    suspend fun getSecrets(playerName: String): Result<Long> {
        val name = playerName.lowercase()
        if (name == mc.user.name.lowercase() && DungeonListener.thePlayer?.isDead == false) {
            return runCatching { getSecretsCMD() }
        }

        SecretCache.check(name)?.let { return it }

        // LayBalt QOL has no stats backend (NoammAddons used its own api server), so other players
        // secrets cannot be looked up. The failure is cached to avoid repeated lookups.
        SecretCache.addFailedToCache(name)
        return Result.failure(StatsUnavailableException())
    }

    suspend fun getProfile(playerName: String): Result<DungeonStats> {
        val name = playerName.lowercase()
        ProfileCache.check(name)?.let { return it }

        // LayBalt QOL has no stats backend (NoammAddons used its own api server).
        ProfileCache.addFailedToCache(name)
        return Result.failure(StatsUnavailableException())
    }

    // usually I don't like running commands in the background
    // but this one seems to behave exactly like /locraw.
    // meaning it does not affect the message spam cooldown
    private suspend fun getSecretsCMD(): Long {
        if (! LocationUtils.inSkyblock) error("Not in Skyblock")
        _totalSecrets = null
        chatListener.register()
        ChatUtils.sendCommand("/secretcount")
        ThreadUtils.setTimeout(5000) { chatListener.unregister() }
        while (chatListener.isActive) delay(50)
        return _totalSecrets ?: error("No secrets found")
    }

    private val regex = Regex("^\\w+: \\d+$")
    private var _totalSecrets: Long? = null
    private val chatListener = EventBus.listener<ChatMessageEvent> {
        if (event.unformattedText == "Secret Counts:") return@listener event.cancel()
        if (! event.unformattedText.matches(regex)) return@listener
        event.isCanceled = true

        if (event.unformattedText.substringBefore(":") != mc.user.name) return@listener
        _totalSecrets = event.unformattedText.substringAfter(": ").toLongOrNull()
        ThreadUtils.scheduledTaskServer(5) { listener.unregister() }
    }
}
