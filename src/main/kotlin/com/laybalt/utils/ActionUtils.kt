package com.laybalt.utils

import com.laybalt.LayBaltQOL.scope
import com.laybalt.event.EventBus
import com.laybalt.event.impl.*
import com.laybalt.init.types.ISelfInit
import com.laybalt.utils.ThreadUtils.scheduledTask
import kotlinx.coroutines.*
import java.lang.Runnable
import java.util.concurrent.*
import kotlin.coroutines.resume

object ActionUtils: ISelfInit {
    private data class Action(val priority: Int, val blockInput: Boolean, val block: suspend () -> Unit): Comparable<Action> {
        override fun compareTo(other: Action) = other.priority.compareTo(this.priority)
    }

    private val actionQueue = PriorityBlockingQueue<Action>()
    @Volatile private var isBlocked = false
    private var processingJob: Job? = null
    private var running = false
    private val lock = Any()

    /**
     * @param priority The priority of the action (higher values executed first).
     * @param block The suspendable action to execute.
     */
    fun queue(priority: Int = 0, blockInput: Boolean = false, block: suspend () -> Unit) = synchronized(lock) {
        actionQueue.add(Action(priority, blockInput, block))
        if (running) return@synchronized
        processingJob = scope.launch { run() }
    }

    private suspend fun run() {
        running = true
        while (actionQueue.isNotEmpty()) {
            val action = synchronized(lock) { actionQueue.poll() } ?: break
            if (action.blockInput) ThreadUtils.setTimeout(5000) { isBlocked = false }
            isBlocked = action.blockInput
            catch { action.block() }
            isBlocked = false
        }
        running = false
    }

    fun reset() = catch {
        synchronized(lock) {
            actionQueue.clear()
            processingJob?.cancel()
            processingJob = null
            running = false
            isBlocked = false
        }
    }

    suspend fun waitTicks(ticks: Int = 0, cb: Runnable = {}) = suspendCancellableCoroutine {
        scheduledTask(ticks) {
            cb.run()
            it.resume(Unit)
        }
    }

    override fun init() {
        EventBus.register<WorldChangeEvent> { reset() }
        EventBus.register<MouseClickEvent> { if (isBlocked) event.cancel() }
        EventBus.register<KeyboardEvent.KeyPressed> { if (isBlocked) event.cancel() }
        EventBus.register<KeyboardEvent.CharTyped> { if (isBlocked) event.cancel() }
    }
}