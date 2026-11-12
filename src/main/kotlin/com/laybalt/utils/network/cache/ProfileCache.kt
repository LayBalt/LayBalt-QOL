package com.laybalt.utils.network.cache

import com.laybalt.utils.network.abstracts.CacheResult
import com.laybalt.utils.network.abstracts.CachedEntry
import com.laybalt.utils.network.abstracts.NetworkCache
import com.laybalt.utils.network.data.DungeonStats
import com.google.common.cache.CacheBuilder
import java.util.concurrent.*

object ProfileCache: NetworkCache<String, DungeonStats> {
    private val storage = CacheBuilder.newBuilder().expireAfterAccess(1, TimeUnit.HOURS).build<String, CachedEntry<DungeonStats>>()

    override fun addToCache(key: String, value: DungeonStats) = storage.put(key.lowercase(), CachedEntry(value))
    override fun addFailedToCache(key: String) = storage.put(key.lowercase(), CachedEntry(null))

    override fun get(key: String): CacheResult<DungeonStats> {
        val entry = storage.getIfPresent(key.lowercase()) ?: return CacheResult.NotFound
        if (entry.value != null) return CacheResult.Success(entry.value)
        // a failed lookup (server down, rate limited) is answered from here a minute (the Party Finder tooltip asks every
        // frame), then asked again
        if (System.currentTimeMillis() - entry.timestamp < FAILED_MS) return CacheResult.Failed
        storage.invalidate(key.lowercase())
        return CacheResult.NotFound
    }

    private const val FAILED_MS = 60_000L
}