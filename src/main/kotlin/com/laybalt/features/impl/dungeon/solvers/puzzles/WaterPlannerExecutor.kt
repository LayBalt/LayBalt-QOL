package com.laybalt.features.impl.dungeon.solvers.puzzles

import com.laybalt.LayBaltQOL
import com.laybalt.features.impl.dungeon.solvers.puzzles.water.CancelToken
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * The Water Board's planner thread: calibration, plans and checks of the run run here, never on the client thread.
 *
 * Every job kind has its own generation counter, and only a newer job of the same kind makes an older one stale (a check
 * never cancels a plan in progress). Generations only ever grow, and every job also carries the id of the room session
 * it was made for, so a result from the previous dungeon can never be taken for one of the current one. A check is
 * skipped while another one is still waiting. Results are handed back on the client thread, and only if still current.
 */
object WaterPlannerExecutor {
    enum class Kind { CALIBRATION, PLAN, CHECK }

    private val thread = Executors.newSingleThreadExecutor { r ->
        Thread(r, "LayBalt-WaterPlanner").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY - 1
        }
    }
    private val generations = Kind.entries.associateWith { AtomicLong() }
    private val checkQueued = AtomicBoolean(false)
    private val sessionIds = AtomicLong()

    /** The session the results are for; results of any other session are dropped. */
    @Volatile var currentSession = 0L
        private set

    private val failureLogged = AtomicBoolean(false)

    /** A new id for a room session (also makes everything still running for the old one stale). */
    fun newSession(): Long = sessionIds.incrementAndGet().also { currentSession = it }

    /**
     * Runs [job] on the planner thread and hands its result to [onResult] on the client thread, unless a newer job of
     * the same [kind] or another session came in meanwhile. Returns false when the job was skipped.
     */
    fun <T> submit(kind: Kind, session: Long, job: (CancelToken) -> T, onResult: (T) -> Unit): Boolean {
        if (kind == Kind.CHECK && ! checkQueued.compareAndSet(false, true)) return false
        val counter = generations.getValue(kind)
        val gen = counter.incrementAndGet()
        val token = CancelToken { counter.get() != gen || currentSession != session }
        thread.execute {
            if (kind == Kind.CHECK) checkQueued.set(false)
            try {
                token.check()
                val result = job(token)
                token.check()
                LayBaltQOL.mc.execute {
                    if (counter.get() == gen && currentSession == session) onResult(result)
                }
            }
            catch (_: CancellationException) {
            }
            catch (t: Throwable) {
                if (failureLogged.compareAndSet(false, true)) LayBaltQOL.logger.error("[WaterBoard] planner job $kind failed", t)
            }
        }
        return true
    }

    /** Makes every job of [kind] that is still waiting or running stale. */
    fun cancel(kind: Kind) {
        generations.getValue(kind).incrementAndGet()
    }
}
