package com.laybalt.features.impl.general.experiments

//#if CHEAT

/** What a solver needs from the feature: settings-driven stop rule, closing and logging. */
interface ExperimentHost {
    fun debug(msg: String)

    /** Stop rule: true when [size] answered rounds (Chronomatron) / sequence length (Ultrasequencer) is past the target. */
    fun reachedTarget(game: ExperimentGame, size: Int): Boolean

    /** The stop rule fired: close the experiment (Auto Close) or tell the player to finish it. */
    fun onTargetReached(size: Int)

    /** The solver lost track of [game] and leaves the current round to the player: tell them. At most once per round. */
    fun onRoundSkipped(game: ExperimentGame)
}

abstract class ExperimentSolver(protected val host: ExperimentHost) {
    /** True while the solver answers by itself; manual clicks are then blocked (if enabled). */
    abstract val blocksInput: Boolean

    /** Set when an answer starts so the first click goes out right away; cleared by the [ExperimentDriver]. */
    var clickNow = false

    /** Set once the stop rule fired: the solver is done for this experiment. */
    var stopped = false
        private set

    /** Reads the board. Runs on every container packet and every tick; never clicks. */
    abstract fun update(board: ExperimentBoard, status: String, statusChanged: Boolean, now: Long)

    /** The slot to click now (the solver advances as if it was clicked), or null. Called at most once per tick. */
    abstract fun nextClick(board: ExperimentBoard, now: Long): Int?

    protected fun stop(size: Int) {
        stopped = true
        host.onTargetReached(size)
    }

    protected fun debug(msg: String) = host.debug(msg)
}

/**
 * Feeds one solver: board updates on every container packet ([update]) and on every client tick ([tick]), and paces the
 * clicks with the click delay (+ random variance). At most one click per tick. Pure, so the tests drive exactly the code
 * path the feature uses.
 */
class ExperimentDriver(
    val solver: ExperimentSolver,
    private val host: ExperimentHost,
    private val delay: () -> Long,
    now: Long,
    firstDelay: Long
) {
    private var lastStatus = ""
    private var nextClickAt = now + firstDelay

    fun update(board: ExperimentBoard, now: Long) {
        if (solver.stopped) return
        val status = board.status
        val statusChanged = status != lastStatus
        if (statusChanged) {
            host.debug("status '$lastStatus' -> '$status'")
            lastStatus = status
        }
        solver.update(board, status, statusChanged, now)
    }

    /** One client tick: reads the board, then returns the slot to click now, or null. */
    fun tick(board: ExperimentBoard, now: Long): Int? {
        update(board, now)
        if (solver.stopped) return null
        if (solver.clickNow) {
            solver.clickNow = false
            nextClickAt = now
        }
        if (now < nextClickAt) return null
        val slot = solver.nextClick(board, now) ?: return null
        nextClickAt = now + delay()
        return slot
    }
}

//#endif
