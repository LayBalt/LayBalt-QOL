package com.laybalt.features.impl.dungeon.solvers.puzzles

import com.laybalt.features.impl.dungeon.solvers.puzzles.water.WaterLever
import kotlin.math.roundToLong

/**
 * Pure state machine behind the Water Board solver: follows a precomputed solution, notices when the
 * player deviates from it and walks them through the recovery. It never touches Minecraft; the solver
 * feeds it lever clicks, server ticks and (while draining) the number of water blocks on the board.
 *
 * Solution format (as in `waterSolutions.json`): for every lever the times in seconds, relative to the
 * first pull of the water lever, at which it has to be flipped. `0.0` entries of the block levers are
 * flipped *before* the water is turned on. Every lever is a toggle, so the board state is fully described
 * by the parity of each lever's flips since the solver started ([flipped]).
 *
 * Phases:
 * - [Phase.WAITING]: water not started yet. Wrong flips can simply be undone ([preStartFixes]).
 * - [Phase.RUNNING]: the timeline runs; a click that is not the lever's next scheduled click within the
 *   tolerance (wrong lever, extra click, too early) or a scheduled click that is more than the tolerance
 *   late fails the run.
 * - [Phase.WATER_ON_FAILED]: the run failed while the water is on, so it has to be turned off.
 * - [Phase.DRAINING]: water is off, waiting until no water is left on the board.
 * - [Phase.RESTORE]: flip the levers in [restoreFixes] back to the solution's starting state, then pull the
 *   water lever; the timeline restarts from zero.
 * - [Phase.SOLVED]: the puzzle completed (reported by the solver), no more guidance.
 */
class WaterBoardTracker(solution: Map<WaterLever, List<Double>>, var toleranceTicks: Long = 20) {

    enum class Phase { WAITING, RUNNING, WATER_ON_FAILED, DRAINING, RESTORE, SOLVED }

    enum class Cause(val text: String) {
        WRONG_LEVER("flipped a lever the solution never uses"),
        EXTRA_CLICK("flipped a lever more often than the solution says"),
        TOO_EARLY("flipped a lever too early"),
        MISSED_CLICK("missed a lever flip"),
        WRONG_START_STATE("started the water with a wrong lever flipped")
    }

    /** [offsetTicks]: how early (TOO_EARLY) or late (MISSED_CLICK) the flip was, 0 otherwise. */
    data class Failure(val cause: Cause, val lever: WaterLever, val tick: Long, val offsetTicks: Long = 0)

    /** Scheduled flips per lever, in ticks after the water start, sorted. */
    val schedule: Map<WaterLever, List<Long>> = WaterLever.entries.associateWith { lever ->
        solution[lever].orEmpty().map { (it * 20).roundToLong() }.sorted()
    }

    /** Number of flips that belong before the water start (the `0.0` entries) per block lever. */
    private val preStart = WaterLever.entries.associateWith { lever ->
        if (lever == WaterLever.WATER) 0 else schedule.getValue(lever).count { it == 0L }
    }

    private val flips = IntArray(WaterLever.entries.size)
    private val consumed = IntArray(WaterLever.entries.size)

    var phase = Phase.WAITING
        private set
    var waterOn = false
        private set
    /** Tick of the (re)started water pull, -1 while not running. */
    var startTick = - 1L
        private set
    var lastFailure: Failure? = null
        private set
    var restarts = 0
        private set

    private var drainStartTick = - 1L
    private var drainStartCount = - 1
    private var lastWaterCount = - 1
    private var lastDrainProgressTick = - 1L
    private var dryTicks = 0
    private var drainEtaTicks: Long? = null

    /** True when the lever has an odd number of flips since the solver started (it is "toggled"). */
    fun flipped(lever: WaterLever) = flips[lever.ordinal] % 2 == 1

    /** How many scheduled flips of the lever are done; the remaining ones are `schedule.drop(consumed)`. */
    fun consumed(lever: WaterLever) = consumed[lever.ordinal]

    /** Toggle state every block lever must have when the water is pulled (the solution's starting state). */
    fun startState(lever: WaterLever) = preStart.getValue(lever) % 2 == 1

    /** Before the water start: levers flipped although the solution never flips them before the water. */
    fun preStartFixes(): List<WaterLever> =
        if (phase != Phase.WAITING) emptyList()
        else blockLevers.filter { preStart.getValue(it) == 0 && flipped(it) }

    /** In [Phase.RESTORE]: levers whose state differs from the starting state (each needs one flip). */
    fun restoreFixes(): List<WaterLever> =
        if (phase != Phase.RESTORE) emptyList()
        else blockLevers.filter { flipped(it) != startState(it) }

    /** Whether the normal solution display (countdowns, CLICK labels) applies. */
    val followsSolution get() = phase == Phase.WAITING || phase == Phase.RUNNING

    /** Estimated ticks until the board is dry, if a drain rate could be measured. */
    val drainEta get() = drainEtaTicks

    /** Registers a click on [lever] at [now]. Returns the failure it caused, if any. */
    fun onClick(lever: WaterLever, now: Long): Failure? {
        val i = lever.ordinal
        when (phase) {
            Phase.WAITING -> {
                flips[i] ++
                if (lever == WaterLever.WATER) {
                    waterOn = true
                    return startRun(now)
                }
                syncPreStart(lever)
            }

            Phase.RUNNING -> {
                val times = schedule.getValue(lever)
                val c = consumed[i]
                val t = now - startTick
                flips[i] ++
                if (lever == WaterLever.WATER) waterOn = ! waterOn
                if (c < times.size && times[c] - t <= toleranceTicks) {
                    consumed[i] ++
                    return null
                }
                return when {
                    times.isEmpty() -> fail(Cause.WRONG_LEVER, lever, now)
                    c >= times.size -> fail(Cause.EXTRA_CLICK, lever, now)
                    else -> fail(Cause.TOO_EARLY, lever, now, times[c] - t)
                }
            }

            Phase.WATER_ON_FAILED -> {
                flips[i] ++
                if (lever == WaterLever.WATER) {
                    waterOn = false
                    beginDrain(now)
                }
            }

            Phase.DRAINING -> {
                flips[i] ++
                if (lever == WaterLever.WATER) {
                    waterOn = true
                    phase = Phase.WATER_ON_FAILED
                }
            }

            Phase.RESTORE -> {
                flips[i] ++
                if (lever == WaterLever.WATER) {
                    waterOn = true
                    restarts ++
                    return startRun(now)
                }
            }

            Phase.SOLVED -> flips[i] ++
        }
        return null
    }

    /**
     * Advances the timeline to [now]. [waterBlocks] is the number of water blocks on the board, or null
     * when unknown (only needed while [Phase.DRAINING]). Returns a failure caused by a missed flip, if any.
     */
    fun onTick(now: Long, waterBlocks: Int? = null): Failure? {
        when (phase) {
            Phase.RUNNING -> {
                val t = now - startTick
                for (lever in WaterLever.entries) {
                    val times = schedule.getValue(lever)
                    val c = consumed[lever.ordinal]
                    if (c < times.size && t - times[c] > toleranceTicks) {
                        return fail(Cause.MISSED_CLICK, lever, now, t - times[c])
                    }
                }
            }

            Phase.DRAINING -> tickDrain(now, waterBlocks)
            else -> {}
        }
        return null
    }

    /** The solver saw the puzzle complete (all gates open). */
    fun markSolved() {
        phase = Phase.SOLVED
    }

    private fun syncPreStart(lever: WaterLever) {
        val z = preStart.getValue(lever)
        if (z == 0) return
        // With one pre-start flip, the lever is either done (toggled) or still pending.
        consumed[lever.ordinal] = if (flipped(lever) == startState(lever)) z else z - 1
    }

    private fun startRun(now: Long): Failure? {
        startTick = now
        phase = Phase.RUNNING
        consumed.fill(0)
        consumed[WaterLever.WATER.ordinal] = 1
        var wrong: WaterLever? = null
        for (lever in blockLevers) {
            val z = preStart.getValue(lever)
            consumed[lever.ordinal] = when {
                flipped(lever) == startState(lever) -> z
                // Still has its pre-start flip pending: allowed right after the water within the tolerance.
                z > 0 -> z - 1
                else -> { if (wrong == null) wrong = lever; 0 }
            }
        }
        return wrong?.let { fail(Cause.WRONG_START_STATE, it, now) }
    }

    private fun fail(cause: Cause, lever: WaterLever, now: Long, offset: Long = 0): Failure {
        val failure = Failure(cause, lever, now, offset)
        lastFailure = failure
        startTick = - 1
        if (waterOn) phase = Phase.WATER_ON_FAILED
        else beginDrain(now)
        return failure
    }

    private fun beginDrain(now: Long) {
        phase = Phase.DRAINING
        drainStartTick = now
        drainStartCount = - 1
        lastWaterCount = - 1
        lastDrainProgressTick = now
        dryTicks = 0
        drainEtaTicks = null
    }

    private fun tickDrain(now: Long, waterBlocks: Int?) {
        if (waterBlocks == null) {
            // Board not readable: fall back to a fixed wait.
            if (now - drainStartTick >= UNKNOWN_DRAIN_TICKS) phase = Phase.RESTORE
            return
        }

        if (waterBlocks == 0) {
            drainEtaTicks = 0
            if (++ dryTicks >= DRY_TICKS) phase = Phase.RESTORE
            return
        }
        dryTicks = 0

        if (drainStartCount < 0) drainStartCount = waterBlocks
        if (lastWaterCount < 0 || waterBlocks < lastWaterCount) lastDrainProgressTick = now
        lastWaterCount = waterBlocks

        val elapsed = now - drainStartTick
        val drained = drainStartCount - waterBlocks
        drainEtaTicks = if (elapsed >= 10 && drained > 0) waterBlocks * elapsed / drained else null

        // Water that stopped receding (e.g. a source block elsewhere) must not block the recovery forever.
        if (now - lastDrainProgressTick >= STALLED_DRAIN_TICKS) phase = Phase.RESTORE
    }

    companion object {
        /** Ticks the board has to stay dry before the restore step starts. */
        const val DRY_TICKS = 10
        const val UNKNOWN_DRAIN_TICKS = 200L
        const val STALLED_DRAIN_TICKS = 300L

        private val blockLevers = WaterLever.entries.filter { it != WaterLever.WATER }
    }
}
