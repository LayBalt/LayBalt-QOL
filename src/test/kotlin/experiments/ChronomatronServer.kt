package experiments

import com.laybalt.features.impl.general.experiments.ItemKind
import kotlin.random.Random

/**
 * Board layouts seen in real Hypixel logs (old LayBalt 2.0.0 debug logs, Sept/Oct 2026, and the 3.2.2 trace of the
 * bug report). A note is a block of slots that light up together.
 */
class ChronomatronLayout(val tier: String, val notes: List<Note>) {
    data class Note(val color: Int, val colorName: String, val slots: List<Int>)

    private val noteBySlot = HashMap<Int, Int>().apply { notes.forEachIndexed { i, note -> note.slots.forEach { put(it, i) } } }

    fun noteOf(slot: Int) = noteBySlot[slot] ?: - 1

    override fun toString() = tier

    companion object {
        private fun column(color: Int, name: String, top: Int, height: Int) = Note(color, name, List(height) { top + 9 * it })

        /** One row of notes, each a column of three slots (rows 1-3): green = 16/25/34, red = 10/19/28 in the logs. */
        val SUPREME = ChronomatronLayout(
            "Supreme", listOf(
                column(14, "Red", 10, 3), column(1, "Orange", 11, 3), column(5, "Lime", 12, 3), column(4, "Yellow", 13, 3),
                column(3, "Aqua", 14, 3), column(11, "Blue", 15, 3), column(13, "Green", 16, 3)
            )
        )

        /** Two rows of notes, each two slots tall: red 11/20, blue 12/21, lime 13/22, yellow 14/23, aqua 30/39, pink 31/40, green 32/41, cyan 33/42. */
        val TRANSCENDENT = ChronomatronLayout(
            "Transcendent", listOf(
                column(14, "Red", 11, 2), column(11, "Blue", 12, 2), column(5, "Lime", 13, 2), column(4, "Yellow", 14, 2),
                column(3, "Aqua", 30, 2), column(6, "Pink", 31, 2), column(13, "Green", 32, 2), column(9, "Cyan", 33, 2)
            )
        )

        /** Two rows of notes, two slots tall (bug report: aqua 15/24, purple 33/42, cyan 31/40; yellow 14 on Oct 5). */
        val METAPHYSICAL = ChronomatronLayout(
            "Metaphysical", listOf(
                column(1, "Orange", 10, 2), column(14, "Red", 11, 2), column(11, "Blue", 12, 2), column(5, "Lime", 13, 2),
                column(4, "Yellow", 14, 2), column(3, "Aqua", 15, 2),
                column(13, "Green", 29, 2), column(2, "Magenta", 30, 2), column(9, "Cyan", 31, 2), column(6, "Pink", 32, 2),
                column(10, "Purple", 33, 2)
            )
        )

        val ALL = listOf(SUPREME, TRANSCENDENT, METAPHYSICAL)
    }
}

/** Server-side timings in ticks (20 per second), randomised per round inside these ranges. */
data class ChronomatronTiming(
    /** "Remember the pattern!" -> first shown note (the new note itself in round 1); ~0.8 s in the logs. */
    val firstShow: IntRange = 14 .. 18,
    /** Start of one shown note -> start of the next (the logs show ~1 note per second). */
    val period: IntRange = 16 .. 20,
    /** How long a shown note stays lit. */
    val lit: IntRange = 8 .. 13,
    /** How long a clicked note stays lit (the logs show every clicked note still lit when the next round starts). */
    val glow: IntRange = 6 .. 14,
    /**
     * Ticks between the new note lighting up and slot 49 turning into the timer: 0 in the logs; more models a server that
     * sends the status late, so the client sees the new note as one more replayed note (maybe dark again already). The
     * server's own timer still starts with the new note.
     */
    val timerStatusDelay: IntRange = 0 .. 0
)

/**
 * Chronomatron as Hypixel runs it (see the behaviour model in the AutoExperiments fix): "Remember the pattern!" while the
 * chain so far is replayed note by note; in the tick the NEW note lights up the status turns into "Timer: (k+2)s"; the
 * player clicks the whole chain; every click lights the clicked note; the last correct click starts the next round right
 * away; a wrong click or the timer running out ends the experiment.
 */
class ChronomatronServer(
    private val layout: ChronomatronLayout,
    private val rng: Random,
    private val timing: ChronomatronTiming = ChronomatronTiming(),
    /** Notes (indices into the layout) to use first; random afterwards. */
    private val forced: List<Int> = emptyList()
): SimServer() {
    private enum class Phase { REMEMBER, TIMER }

    val sequence = ArrayList<Int>()
    private var phase = Phase.REMEMBER
    private var showAt = IntArray(0)
    private var progress = 0
    private var timerEnd = 0
    private var timerShownAt = 0
    private val litUntil = IntArray(layout.notes.size)
    private val glowUntil = IntArray(layout.notes.size)

    private fun pick(range: IntRange) = rng.nextInt(range.first, range.last + 1)

    private fun startRound(tick: Int) {
        sequence += forced.getOrNull(sequence.size) ?: rng.nextInt(layout.notes.size)
        phase = Phase.REMEMBER
        progress = 0
        var at = tick + pick(timing.firstShow)
        showAt = IntArray(sequence.size) { i -> at.also { at += pick(timing.period) } }
    }

    override fun onTick(tick: Int) {
        if (tick == 0) {
            for (slot in 0 until 54) board[slot] = SimItem.BORDER
            startRound(0)
        }

        when (phase) {
            Phase.REMEMBER -> for (i in showAt.indices) if (showAt[i] == tick) {
                litUntil[sequence[i]] = tick + pick(timing.lit)
                if (i == sequence.lastIndex) {
                    phase = Phase.TIMER
                    timerEnd = tick + (sequence.size + 2) * 20
                    // No extra random draw without a delay, so the other tests keep their games.
                    timerShownAt = tick + if (timing.timerStatusDelay.last > 0) pick(timing.timerStatusDelay) else 0
                }
            }

            Phase.TIMER -> if (tick >= timerEnd) {
                failure = "timer ran out in round ${sequence.size} after $progress clicks"
                return
            }
        }

        for ((i, note) in layout.notes.withIndex()) {
            val lit = litUntil[i] > tick || glowUntil[i] > tick
            val item = SimItem(if (lit) ItemKind.TERRACOTTA else ItemKind.GLASS, note.color, lit, note.colorName)
            for (slot in note.slots) board[slot] = item
        }
        board[49] = SimItem.status(
            if (phase == Phase.TIMER && tick >= timerShownAt) "Timer:${(timerEnd - tick + 19) / 20}s" else "Remember the pattern!"
        )
    }

    override fun expectedSlot(index: Int) = if (phase == Phase.TIMER) sequence.getOrNull(index)?.let { layout.notes[it].slots.first() } else null

    override fun onClick(tick: Int, slot: Int) {
        if (phase != Phase.TIMER) {
            ignoredClicks ++
            return
        }
        val note = layout.noteOf(slot)
        val expected = sequence[progress]
        if (note != expected) {
            failure = "wrong click in round ${sequence.size}, click ${progress + 1}: slot $slot " +
                "(${layout.notes.getOrNull(note)?.colorName ?: "no note"}) instead of ${layout.notes[expected].colorName}"
            return
        }
        glowUntil[note] = tick + pick(timing.glow)
        if (++ progress == sequence.size) {
            roundsCompleted = sequence.size
            startRound(tick)
        }
    }
}
