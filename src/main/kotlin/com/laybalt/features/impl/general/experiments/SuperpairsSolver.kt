package com.laybalt.features.impl.general.experiments

//#if CHEAT

import com.laybalt.features.impl.general.experiments.Experiments.DYE_BLACK
import com.laybalt.features.impl.general.experiments.Experiments.DYE_CYAN
import com.laybalt.features.impl.general.experiments.Experiments.STATUS_SLOT
import kotlin.random.Random

/**
 * Remembers every revealed card and clicks known pairs; otherwise explores hidden cards it has not seen yet.
 *
 * The board (Skyblocker, SkyHanni, NoFrills): hidden cards are cyan stained glass named "Click any button!" - or "Click a
 * second button!" while one card of a pair attempt is open (also "?" / "Next button is instantly rewarded!"); a revealed
 * card shows its reward; a matched pair stays revealed, a wrong pair flips back after a moment; single powerup cards
 * need no partner; slot 4 says "Remaining Clicks: N".
 *
 * A card only counts as revealed once the server shows the reward: with Left clicks the clicked slot is predicted empty
 * until the server's resync, which must not be taken for a reveal. The card names tell whether the server waits for a
 * second card, which keeps the solver in step around powerups.
 */
class SuperpairsSolver(host: ExperimentHost, private val random: Random = Random.Default): ExperimentSolver(host) {
    private enum class Phase { FIRST, AWAIT_FIRST, SECOND, AWAIT_SECOND, SETTLE }

    /** Slots that ever showed a hidden card. */
    private val cards = HashSet<Int>()
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
            if (slot == STATUS_SLOT || slot == CLICKS_SLOT) continue
            if (isHidden(board, slot)) cards.add(slot)
            else if (slot in cards && isCard(board, slot) && slot !in known) {
                known[slot] = board.cardKey(slot) ?: continue
                debug("Superpairs: slot $slot is ${board.name(slot)}")
            }
        }

        if (remainingClicks(board) == 0) {
            debug("Superpairs: no clicks left")
            finished = true
            return
        }

        when (phase) {
            Phase.AWAIT_FIRST -> when {
                isCard(board, firstSlot) -> {
                    failures = 0
                    setPhase(Phase.SECOND, now)
                }
                now - phaseSince > REVEAL_TIMEOUT_MS -> fail(now)
            }

            Phase.AWAIT_SECOND -> when {
                isCard(board, secondSlot) -> {
                    failures = 0
                    setPhase(Phase.SETTLE, now)
                }
                now - phaseSince > REVEAL_TIMEOUT_MS -> fail(now)
            }

            Phase.SETTLE -> {
                val matched = known[firstSlot] != null && known[firstSlot] == known[secondSlot]
                val flippedBack = isHidden(board, firstSlot) && isHidden(board, secondSlot)
                val settled = now - phaseSince >= NAMES_SETTLE_MS
                when {
                    matched || flippedBack -> setPhase(Phase.FIRST, now)
                    // The second card was a single (powerup): the server still waits for a partner of the first one.
                    settled && waitsForSecond(board) == true -> {
                        debug("Superpairs: slot $secondSlot was a single card, the server still waits for a second card")
                        setPhase(Phase.SECOND, now)
                    }
                    now - phaseSince > SETTLE_TIMEOUT_MS -> setPhase(Phase.FIRST, now)
                }
            }

            else -> Unit
        }

        if (failures >= MAX_FAILURES) {
            debug("Superpairs: clicks are not revealing cards any more, stopping")
            finished = true
        }
        else if (cards.isNotEmpty() && cards.none { isHidden(board, it) } && phase == Phase.FIRST) {
            debug("Superpairs: no hidden card left")
            finished = true
        }
    }

    override fun nextClick(board: ExperimentBoard, now: Long): Int? {
        if (finished) return null
        val hidden = cards.filter { isHidden(board, it) }
        if (hidden.isEmpty()) return null

        if (phase == Phase.SECOND) {
            if (now - phaseSince < NAMES_SETTLE_MS) return null // let the whole board update of the reveal arrive
            if (waitsForSecond(board) == false) {
                // The first card was a single (powerup): the server is not waiting for a partner.
                debug("Superpairs: slot $firstSlot was a single card")
                setPhase(Phase.FIRST, now)
            }
        }

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

    private fun isHidden(board: ExperimentBoard, slot: Int) =
        (board.kind(slot) == ItemKind.GLASS && board.color(slot) == DYE_CYAN) || HIDDEN_NAMES.matches(board.name(slot))

    private fun isCard(board: ExperimentBoard, slot: Int) = when (board.kind(slot)) {
        ItemKind.EMPTY -> false
        ItemKind.PANE -> board.color(slot) != DYE_BLACK
        else -> ! isHidden(board, slot)
    }

    /** true / false when the hidden cards say whether the server waits for a second card, null when they do not tell. */
    private fun waitsForSecond(board: ExperimentBoard): Boolean? {
        var any = false
        for (slot in cards) {
            when (board.name(slot)) {
                SECOND_TEXT -> return true
                ANY_TEXT -> any = true
            }
        }
        return if (any) false else null
    }

    private fun remainingClicks(board: ExperimentBoard) = CLICKS.matchEntire(board.name(CLICKS_SLOT))?.groupValues?.get(1)?.toIntOrNull()

    private companion object {
        const val CLICKS_SLOT = 4
        const val ANY_TEXT = "Click any button!"
        const val SECOND_TEXT = "Click a second button!"
        val HIDDEN_NAMES = Regex("\\?|Click a(?: seco)?n[dy] button!?|Next button is instantly rewarded!?")
        val CLICKS = Regex("Remaining Clicks: (\\d+)")

        const val REVEAL_TIMEOUT_MS = 2000L
        /** A wrong pair is given this long to flip back. */
        const val SETTLE_TIMEOUT_MS = 4000L
        /** The board update of a reveal arrives slot by slot; card names are read only after it settled. */
        const val NAMES_SETTLE_MS = 250L
        const val MAX_FAILURES = 3
    }
}

//#endif
