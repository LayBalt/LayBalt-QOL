package com.laybalt.features.impl.general.experiments

//#if CHEAT

import com.laybalt.features.impl.general.experiments.Experiments.DYE_BLACK
import com.laybalt.features.impl.general.experiments.Experiments.DYE_CYAN
import com.laybalt.features.impl.general.experiments.Experiments.STATUS_SLOT
import kotlin.random.Random

/**
 * Remembers every revealed card and clicks known pairs; otherwise explores hidden cards it has not seen yet.
 * Hidden cards are cyan stained glass; any other item on a slot that was once hidden is a revealed card.
 */
class SuperpairsSolver(host: ExperimentHost, private val random: Random = Random.Default): ExperimentSolver(host) {
    private enum class Phase { FIRST, AWAIT_FIRST, SECOND, AWAIT_SECOND, SETTLE }

    private val board = HashSet<Int>()
    private val known = HashMap<Int, Any>()
    private var phase = Phase.FIRST
    private var firstSlot = - 1
    private var secondSlot = - 1
    private var phaseSince = 0L
    private var failures = 0
    private var finished = false

    override val blocksInput get() = ! finished

    override fun update(board: ExperimentBoard, status: String, statusChanged: Boolean, now: Long) {
        if (finished) return
        for (slot in 0 until 54) {
            if (slot == STATUS_SLOT) continue
            if (isHidden(board, slot)) this.board.add(slot)
            else if (slot in this.board && isCard(board, slot) && known[slot] == null) {
                known[slot] = board.cardKey(slot) ?: continue
                debug("Superpairs: slot $slot is ${board.name(slot)}")
            }
        }

        when (phase) {
            Phase.AWAIT_FIRST -> when {
                ! isHidden(board, firstSlot) -> {
                    failures = 0
                    setPhase(Phase.SECOND, now)
                }
                now - phaseSince > REVEAL_TIMEOUT_MS -> fail(now)
            }

            Phase.AWAIT_SECOND -> when {
                ! isHidden(board, secondSlot) -> {
                    failures = 0
                    setPhase(Phase.SETTLE, now)
                }
                now - phaseSince > REVEAL_TIMEOUT_MS -> fail(now)
            }

            Phase.SETTLE -> {
                // A wrong pair flips back after a moment, a matched pair stays revealed.
                val bothHidden = isHidden(board, firstSlot) && isHidden(board, secondSlot)
                if (bothHidden || now - phaseSince > SETTLE_MS) setPhase(Phase.FIRST, now)
            }

            else -> Unit
        }

        if (failures >= MAX_FAILURES) {
            debug("Superpairs: clicks are not revealing cards any more, stopping")
            finished = true
        }
        else if (this.board.isNotEmpty() && this.board.none { isHidden(board, it) } && phase == Phase.FIRST) {
            debug("Superpairs: no hidden card left")
            finished = true
        }
    }

    override fun nextClick(board: ExperimentBoard, now: Long): Int? {
        if (finished) return null
        val hidden = this.board.filter { isHidden(board, it) }
        if (hidden.isEmpty()) return null

        return when (phase) {
            Phase.FIRST -> {
                val pair = hidden.firstOrNull { a -> known[a]?.let { item -> hidden.any { b -> b != a && known[b] == item } } == true }
                val slot = pair ?: hidden.filter { it !in known }.randomOrNull(random) ?: hidden.random(random)
                firstSlot = slot
                setPhase(Phase.AWAIT_FIRST, now)
                slot
            }

            Phase.SECOND -> {
                val item = known[firstSlot]
                val partner = item?.let { hidden.firstOrNull { b -> b != firstSlot && known[b] == item } }
                val slot = partner ?: hidden.filter { it != firstSlot && it !in known }.randomOrNull(random)
                    ?: hidden.filter { it != firstSlot }.randomOrNull(random) ?: return null
                secondSlot = slot
                setPhase(Phase.AWAIT_SECOND, now)
                slot
            }

            else -> null
        }
    }

    private fun setPhase(phase: Phase, now: Long) {
        this.phase = phase
        phaseSince = now
    }

    private fun fail(now: Long) {
        failures ++
        setPhase(Phase.FIRST, now)
    }

    private fun isHidden(board: ExperimentBoard, slot: Int) = board.kind(slot) == ItemKind.GLASS && board.color(slot) == DYE_CYAN

    private fun isCard(board: ExperimentBoard, slot: Int) = when (board.kind(slot)) {
        ItemKind.EMPTY -> false
        ItemKind.PANE -> board.color(slot) != DYE_BLACK
        else -> ! isHidden(board, slot)
    }

    private companion object {
        const val REVEAL_TIMEOUT_MS = 2000L
        const val SETTLE_MS = 1500L
        const val MAX_FAILURES = 3
    }
}

//#endif
