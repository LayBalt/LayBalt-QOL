package com.laybalt.utils.render

import com.laybalt.event.EventBus
import com.laybalt.event.impl.EntityUnloadEvent
import com.laybalt.event.impl.WorldChangeEvent
import com.laybalt.init.types.ISelfInit
import net.minecraft.client.player.LocalPlayer
import net.minecraft.util.Util
import net.minecraft.world.entity.Entity

object LegitEntityVisibility: ISelfInit {
    private const val MAX_DISTANCE = 128.0 * 128.0
    private const val MAX_ENTRIES = 1024
    private const val TTL_MILLIS = 100L

    private data class Entry(var visible: Boolean, var lastCheck: Long)

    private val cache = HashMap<Int, Entry>(256)

    override fun init() {
        EventBus.register<WorldChangeEvent> { cache.clear() }
        EventBus.register<EntityUnloadEvent> { cache.remove(event.entity.id) }
    }

    @JvmStatic
    @Suppress("unused")
    fun isVisible(player: LocalPlayer, entity: Entity): Boolean {
        if (entity === player) return true
        if (entity.isRemoved || ! entity.isAlive) {
            cache.remove(entity.id)
            return false
        }
        if (player.distanceToSqr(entity) > MAX_DISTANCE) {
            cache.remove(entity.id)
            return false
        }

        val now = Util.getMillis()
        val cached = cache[entity.id]
        if (cached != null && now - cached.lastCheck < TTL_MILLIS) return cached.visible

        val visible = ! entity.isInvisibleTo(player) && player.hasLineOfSight(entity)
        if (cached != null) {
            cached.visible = visible
            cached.lastCheck = now
        }
        else {
            if (cache.size >= MAX_ENTRIES) cache.clear()
            cache[entity.id] = Entry(visible, now)
        }

        return visible
    }
}