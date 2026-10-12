package experiments

import com.laybalt.features.impl.general.experiments.ExperimentBoard
import com.laybalt.features.impl.general.experiments.ExperimentGame
import com.laybalt.features.impl.general.experiments.ExperimentHost
import com.laybalt.features.impl.general.experiments.ExperimentSolver
import com.laybalt.features.impl.general.experiments.ItemKind
import com.laybalt.features.impl.general.experiments.Experiments.CHRONOMATRON_BOARD
import com.laybalt.features.impl.general.experiments.Experiments.REMEMBER_TEXT
import com.laybalt.features.impl.general.experiments.Experiments.TIMER_PREFIX
import com.laybalt.features.impl.general.experiments.Experiments.isRoundStatus

/**
 * The Chronomatron solver shipped in LayBaltQOL 3.2.2 (commit 9ed669e), ported unchanged onto [ExperimentBoard] and kept as
 * a reference: it tracks single lit slots, so a note made of several slots that go dark one packet at a time is counted
 * twice. The tests use it to prove that the simulator reproduces the bug report (failure on the 3rd click / in round 3).
 *
 * Original description: every round the server replays the whole chain (each pane lights up with a glint for a
 * moment) plus one new pane, then the timer runs and the chain has to be clicked in order. The solver counts the lit
 * panes against the expected length (previous chain + 1), self-heals a mismatching replay and only answers a
 * consistent chain.
 */
class LegacyChronomatronSolver(host: ExperimentHost, watchFirstRound: Boolean): ExperimentSolver(host) {
    private data class ChainEntry(val slot: Int, val color: Int?)

    private enum class State { WATCH, ANSWER, DONE }

    private val chain = ArrayList<ChainEntry>()
    /** Panes the current round must show (chain + 1); -1 = unknown, watch this round only. */
    private var expectedLen = if (watchFirstRound) - 1 else 1
    private var shownCount = 0
    private var litSlot = - 1
    private var replayDoneAt = - 1L
    private var nextIndex = 0
    private var state = State.WATCH
    private var doneAt = 0L
    private var unknownStatusSince = - 1L
    private var timerRunning = false
    private val leftoverLit = HashSet<Int>()
    private val recentClicks = HashMap<Int, Long>()

    override val blocksInput get() = expectedLen >= 0

    override fun update(board: ExperimentBoard, status: String, statusChanged: Boolean, now: Long) {
        timerRunning = status.startsWith(TIMER_PREFIX)

        if (status.isNotEmpty() && ! isRoundStatus(status)) {
            if (unknownStatusSince < 0) unknownStatusSince = now
            if (now - unknownStatusSince < UNKNOWN_STATUS_MS) return
            if (chain.isNotEmpty() || state != State.WATCH) debug("Chronomatron over (status '$status')")
            chain.clear()
            expectedLen = 1
            resetRound()
            return
        }
        unknownStatusSince = - 1

        leftoverLit.removeAll { ! isLit(board, it) }
        recentClicks.entries.removeAll { now - it.value > CLICK_GLOW_MS }

        when (state) {
            State.DONE -> {
                val lit = if (now - doneAt > CLICK_GLOW_MS) findLitSlot(board) else - 1
                if (status == REMEMBER_TEXT || lit != - 1) {
                    if (! startRound(board)) return
                    if (lit != - 1 && state == State.WATCH) onPaneLit(board, lit, now)
                }
            }

            State.WATCH -> {
                if (statusChanged && status == REMEMBER_TEXT && shownCount > 0) {
                    debug("Chronomatron: round ended while watching ($shownCount panes seen)")
                    startRound(board)
                    return
                }
                if (litSlot != - 1) {
                    if (isLit(board, litSlot)) {
                        if (replayDoneAt >= 0 && now - replayDoneAt > LIT_TIMEOUT_MS) {
                            litSlot = - 1
                            startAnswer(now)
                        }
                        return
                    }
                    litSlot = - 1
                    if (replayDoneAt >= 0) {
                        startAnswer(now)
                        return
                    }
                }
                val lit = findLitSlot(board)
                if (lit != - 1) onPaneLit(board, lit, now)
            }

            State.ANSWER -> {
                if ((statusChanged && status == REMEMBER_TEXT) || nextIndex >= chain.size) finishAnswer(now)
            }
        }
    }

    override fun nextClick(board: ExperimentBoard, now: Long): Int? {
        if (state != State.ANSWER || ! timerRunning) return null // clicks only count while the timer runs
        if (nextIndex >= chain.size) {
            finishAnswer(now)
            return null
        }
        val slot = findSlot(board, chain[nextIndex]) ?: return null // not visible right now, retry next tick
        recentClicks[slot] = now
        nextIndex ++
        if (nextIndex >= chain.size) finishAnswer(now)
        return slot
    }

    private fun resetRound() {
        state = State.WATCH
        shownCount = 0
        litSlot = - 1
        replayDoneAt = - 1
        nextIndex = 0
        leftoverLit.clear()
        recentClicks.clear()
    }

    /** @return false when the stop rule fired (the solver was dropped). */
    private fun startRound(board: ExperimentBoard): Boolean {
        if (expectedLen >= 0 && host.reachedTarget(ExperimentGame.CHRONOMATRON, chain.size)) {
            stop(chain.size)
            return false
        }
        val previousShown = shownCount
        state = State.WATCH
        shownCount = 0
        nextIndex = 0
        litSlot = - 1
        replayDoneAt = - 1
        leftoverLit.clear()
        for (slot in CHRONOMATRON_BOARD) if (isLit(board, slot)) leftoverLit.add(slot)
        expectedLen = when {
            expectedLen >= 0 -> chain.size + 1
            previousShown > 0 -> {
                if (chain.size > previousShown) chain.subList(previousShown, chain.size).clear()
                previousShown + 1
            }
            else -> - 1
        }
        debug("Chronomatron: round started, expecting $expectedLen panes (chain ${chain.size}, leftover $leftoverLit)")
        return true
    }

    private fun onPaneLit(board: ExperimentBoard, slot: Int, now: Long) {
        litSlot = slot
        val entry = ChainEntry(slot, colorOf(board, slot))
        debug("Chronomatron: pane $slot lit (${entry.color}), #${shownCount + 1} of $expectedLen")

        if (expectedLen < 0 || shownCount >= expectedLen) {
            // Watch-only round, or more panes than possible (manual click?): remember, but do not answer this round.
            if (expectedLen >= 0) debug("Chronomatron: unexpected extra pane, watching only until the next round")
            expectedLen = - 1
            replayDoneAt = - 1
            if (shownCount < chain.size) chain[shownCount] = entry else chain.add(entry)
            shownCount ++
            return
        }

        if (shownCount < chain.size) {
            if (chain[shownCount] != entry) {
                chain.subList(shownCount, chain.size).clear()
                chain.add(entry)
            }
        }
        else chain.add(entry)
        shownCount ++
        if (shownCount == expectedLen) replayDoneAt = now
    }

    private fun startAnswer(now: Long) {
        if (expectedLen < 0 || chain.size != expectedLen) {
            debug("Chronomatron: not answering (chain ${chain.size}, expected $expectedLen); answer by hand")
            expectedLen = - 1
            doneAt = now
            state = State.DONE
            return
        }
        debug("Chronomatron: answering a chain of ${chain.size}")
        nextIndex = 0
        clickNow = true // first click right away
        state = State.ANSWER
    }

    private fun finishAnswer(now: Long) {
        if (state == State.ANSWER) debug("Chronomatron: answer finished ($nextIndex of ${chain.size} clicked)")
        state = State.DONE
        doneAt = now
        replayDoneAt = - 1
        litSlot = - 1
    }

    private fun isLit(board: ExperimentBoard, slot: Int) = board.kind(slot) != ItemKind.EMPTY && board.isLit(slot)

    private fun colorOf(board: ExperimentBoard, slot: Int) = board.color(slot).takeIf { it >= 0 }

    private fun findLitSlot(board: ExperimentBoard): Int {
        for (slot in CHRONOMATRON_BOARD) {
            if (slot in leftoverLit || slot in recentClicks) continue
            if (isLit(board, slot)) return slot
        }
        return - 1
    }

    /** The remembered slot if it still shows the colour (or is empty: our click prediction), else any slot with it. */
    private fun findSlot(board: ExperimentBoard, entry: ChainEntry): Int? {
        if (board.kind(entry.slot) == ItemKind.EMPTY || entry.color == null || colorOf(board, entry.slot) == entry.color) return entry.slot
        return CHRONOMATRON_BOARD.firstOrNull { colorOf(board, it) == entry.color }
    }

    private companion object {
        const val UNKNOWN_STATUS_MS = 2000L
        const val CLICK_GLOW_MS = 1500L
        const val LIT_TIMEOUT_MS = 2000L
    }
}
