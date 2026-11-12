package com.laybalt.features.impl.general.experiments

//#if CHEAT

import com.laybalt.features.impl.general.experiments.Experiments.REMEMBER_TEXT
import com.laybalt.features.impl.general.experiments.Experiments.TIMER_PREFIX
import com.laybalt.features.impl.general.experiments.Experiments.ULTRASEQUENCER_BOARD
import com.laybalt.features.impl.general.experiments.Experiments.isRoundStatus
import java.util.TreeMap

/** The numbered panes are visible while "Remember the pattern!" is shown and must be clicked in order once the timer runs. */
class UltrasequencerSolver(host: ExperimentHost): ExperimentSolver(host) {
    private enum class State { WATCH, ANSWER, DONE }

    private val order = TreeMap<Int, Int>() // number -> slot
    private var nextNumber = 1
    private var state = State.WATCH
    private var skipped = false

    override val blocksInput get() = ! skipped

    override fun update(board: ExperimentBoard, status: String, statusChanged: Boolean, now: Long) {
        if (status.isNotEmpty() && ! isRoundStatus(status)) {
            if (order.isNotEmpty() || state != State.WATCH) debug("Ultrasequencer over (status '$status')")
            order.clear()
            resetRound()
            return
        }

        when (state) {
            State.DONE -> if (status == REMEMBER_TEXT) {
                order.clear()
                resetRound()
                scan(board)
                stopIfPastTarget()
            }

            State.WATCH -> {
                if (! status.startsWith(TIMER_PREFIX)) {
                    scan(board) // the numbers may arrive over several packets
                    stopIfPastTarget()
                    return
                }
                val complete = order.isNotEmpty() && order.firstKey() == 1 && order.lastKey() == order.size
                if (! complete) {
                    debug("Ultrasequencer: incomplete sequence ${order.keys}, not answering this round")
                    skipped = true
                    state = State.DONE
                    return
                }
                if (host.reachedTarget(ExperimentGame.ULTRASEQUENCER, order.size)) return stop(order.size)
                debug("Ultrasequencer: answering a sequence of ${order.size}")
                nextNumber = order.firstKey()
                clickNow = true
                state = State.ANSWER
            }

            State.ANSWER -> if (! status.startsWith(TIMER_PREFIX)) state = State.DONE
        }
    }

    override fun nextClick(board: ExperimentBoard, now: Long): Int? {
        if (state != State.ANSWER) return null
        val slot = order[nextNumber] ?: run {
            state = State.DONE
            return null
        }
        val next = order.higherKey(nextNumber)
        if (next == null) {
            debug("Ultrasequencer: sequence of ${order.size} answered")
            state = State.DONE
        }
        else nextNumber = next
        return slot
    }

    /**
     * A number past the target shows while the pattern is still being shown: the sequence is longer than the target, so
     * the game stops now instead of after the memorize phase (SkyHanni alerts at the same moment: the round counter is
     * the longest number shown).
     */
    private fun stopIfPastTarget() {
        if (order.isEmpty() || stopped) return
        val longest = order.lastKey()
        if (host.reachedTarget(ExperimentGame.ULTRASEQUENCER, longest)) stop(longest)
    }

    private fun resetRound() {
        state = State.WATCH
        nextNumber = 1
        skipped = false
    }

    private fun scan(board: ExperimentBoard) {
        for (slot in ULTRASEQUENCER_BOARD) {
            if (board.kind(slot) == ItemKind.EMPTY) continue
            val name = board.name(slot)
            if (name.length !in 1 .. 3 || ! name.all(Char::isDigit)) continue
            val number = name.toInt()
            if (order.put(number, slot) == null) debug("Ultrasequencer: pane $slot shows $number (count ${board.count(slot)})")
        }
    }
}

//#endif
