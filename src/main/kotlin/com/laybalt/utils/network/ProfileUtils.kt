package com.laybalt.utils.network

import com.laybalt.LayBaltQOL.mc
import com.laybalt.event.EventBus
import com.laybalt.event.impl.ChatMessageEvent
import com.laybalt.utils.*
import com.laybalt.utils.JsonUtils.getString
import com.laybalt.utils.dungeons.DungeonListener
import com.laybalt.utils.location.LocationUtils
import com.laybalt.utils.network.cache.*
import com.laybalt.features.impl.dungeon.SecretsCounter
import com.laybalt.utils.network.data.DungeonStats
import com.laybalt.utils.network.data.HypixelProfile
import com.laybalt.utils.network.data.MojangData
import kotlinx.coroutines.delay
import kotlinx.serialization.json.jsonObject
import java.util.*
import java.util.concurrent.*

object ProfileUtils {
    class StatsUnavailableException: Exception("no stats server set (Secrets Counter > API URL)")

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

        // only a resolver's "no such player" is kept (an hour): a timeout or a busy resolver is asked again next time
        var notFound = false
        for (api in nameToUuidApis) {
            if (System.currentTimeMillis() < (apiCooldowns[api] ?: 0L)) continue

            val result = WebUtils.getAs<String>(api + key)
            if (result.isFailure) {
                // the status line only ("HTTP <code>-..."): a timeout's message holds the URL
                val msg = result.exceptionOrNull()?.message ?: ""
                if (msg.startsWith("HTTP 429")) {
                    apiCooldowns[api] = System.currentTimeMillis() + (5 * 60 * 1000)
                    continue
                }
                if (msg.startsWith("HTTP 404") || msg.startsWith("HTTP 204")) {
                    notFound = true
                    break
                }
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

        if (! notFound) return Result.failure(Exception("could not resolve $name (name servers unreachable)"))
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

    /**
     * The stats server other players' secrets and profiles are read from: NoammAddons used its own (not part of LayBalt
     * QOL), LayBalt the one /lbq cata and Secrets Counter use (Secrets Counter > API URL, Odin's public one by default).
     */
    private fun statsServer() = SecretsCounter.apiBaseUrl.ifEmpty { null }

    /** The stats server answered 429 (too many requests): not asked again before this. */
    @Volatile private var statsServerCooldownUntil = 0L

    /** A GET to the stats server, with a minute's pause after it answered 429 (as NoammAddons paused its own API). */
    private suspend fun statsGet(url: String): Result<String> {
        val wait = statsServerCooldownUntil - System.currentTimeMillis()
        if (wait > 0) return Result.failure(Exception("stats server rate limited, retry in ${wait / 1000 + 1}s"))
        return WebUtils.getAs<String>(url).onFailure {
            // the status line only ("HTTP 429-..."): a timeout's message holds the URL, whose UUID may contain "429"
            if (it.message?.startsWith("HTTP 429") == true) statsServerCooldownUntil = System.currentTimeMillis() + 60_000L
        }.recoverCatching { throw Exception("stats server: ${(it.message ?: it::class.simpleName)?.take(60)}") }
    }

    suspend fun getSecrets(playerName: String): Result<Long> {
        val name = playerName.lowercase()
        if (name == mc.user.name.lowercase() && DungeonListener.thePlayer?.isDead == false) {
            return getSecretsCMD()
        }

        SecretCache.check(name)?.let { return it }

        // <url>/secrets/<uuid> answers the total as plain text (as Secrets Counter reads it); failures are not cached,
        // the next run asks again
        val base = statsServer() ?: return Result.failure(StatsUnavailableException())
        val uuid = getUUIDbyName(name).getOrElse { return Result.failure(it) }.uuid
        val secrets = statsGet("$base/secrets/$uuid").mapCatching { it.trim().toLongOrNull() ?: error("unexpected answer: ${it.take(40)}") }
            .getOrElse { return Result.failure(it) }
        SecretCache.addToCache(name, secrets)
        return Result.success(secrets)
    }

    suspend fun getProfile(playerName: String): Result<DungeonStats> {
        val name = playerName.lowercase()
        ProfileCache.check(name, "the lookup failed less than a minute ago")?.let { return it }

        // <url>/get/<uuid> answers the player's Hypixel SkyBlock profiles; a failure (server down, rate limit, no
        // profile, no server set) is kept a minute ([ProfileCache]), then asked again
        val base = statsServer() ?: run {
            ProfileCache.addFailedToCache(name)
            return Result.failure(StatsUnavailableException())
        }
        val stats = getUUIDbyName(name).mapCatching { mojang ->
            HypixelProfile.dungeonStats(statsGet("$base/get/${mojang.uuid}").getOrThrow(), mojang.uuid)
        }.getOrElse {
            ProfileCache.addFailedToCache(name)
            return Result.failure(it)
        }
        ProfileCache.addToCache(name, stats)
        return Result.success(stats)
    }

    // usually I don't like running commands in the background
    // but this one seems to behave exactly like /locraw.
    // meaning it does not affect the message spam cooldown
    // NoammAddons 3c810cdb: public and Result-based, so the run summary can read your own count even while you are dead
    suspend fun getSecretsCMD(): Result<Long> {
        if (! LocationUtils.inSkyblock) return Result.failure(Exception("Not in Skyblock"))
        _totalSecrets = null
        chatListener.register()
        ChatUtils.sendCommand("/secretcount")
        ThreadUtils.setTimeout(5000) { chatListener.unregister() }
        while (chatListener.isActive) delay(50)
        val secrets = _totalSecrets ?: return Result.failure(Exception("Secrets count command timed out"))
        SecretCache.addToCache(mc.user.name.lowercase(), secrets)
        return Result.success(secrets)
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
