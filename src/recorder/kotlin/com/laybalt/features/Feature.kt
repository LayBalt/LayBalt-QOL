package com.laybalt.features

import com.laybalt.event.Event
import com.laybalt.event.EventBus
import com.laybalt.event.EventContext
import com.laybalt.event.EventListener
import com.laybalt.event.priority.EventPriority
import com.laybalt.utils.spaceCaps

/**
 * The recorder-only jar's feature base: the part of LayBalt QOL's `Feature` the Fishing Recorder uses (no config,
 * HUD or click GUI). Listeners registered with [register] only receive events while the feature is enabled.
 */
open class Feature(val description: String? = null, name: String? = null, toggled: Boolean = false): Shortcuts {
    val name = name ?: this::class.simpleName.toString().spaceCaps()
    @JvmField var enabled = toggled
    val listeners = mutableSetOf<EventListener<*>>()

    fun initialize() {
        init()
        if (enabled) onEnable() else onDisable()
    }

    open fun init() = Unit

    open fun onEnable() {
        listeners.forEach(EventListener<*>::register)
    }

    open fun onDisable() {
        listeners.forEach(EventListener<*>::unregister)
    }

    open fun toggle() {
        enabled = ! enabled
        if (enabled) onEnable() else onDisable()
    }

    protected inline fun <reified T: Event> register(
        priority: EventPriority = EventPriority.NORMAL,
        receiveCancelled: Boolean = false,
        noinline block: EventContext<T>.() -> Unit
    ): EventListener<T> {
        val listener = EventBus.listener<T>(priority, receiveCancelled, block)
        listeners.add(listener)
        return listener
    }
}
