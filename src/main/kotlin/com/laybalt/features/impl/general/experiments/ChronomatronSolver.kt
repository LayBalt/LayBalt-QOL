package com.laybalt.features.impl.general.experiments

//#if CHEAT

import com.laybalt.features.impl.general.experiments.Experiments.CHRONOMATRON_BOARD
import com.laybalt.features.impl.general.experiments.Experiments.REMEMBER_TEXT
import com.laybalt.features.impl.general.experiments.Experiments.TIMER_PREFIX

/**
 * Chronomatron as Hypixel runs it (reconstructed from real debug logs of the old LayBalt solver and of 3.2.2, and from
 * the OdinClient / Skyblocker / NoFrills / SkyHanni implementations):
 * - Slot 49 says "Remember the pattern!" while the chain so far is replayed and "Timer: Ns" while it has to be clicked;
 *   N = chain length + 2 when the timer appears.
 * - A note is a block of 1-3 slots of one colour (High-Supreme: a column of 3, Transcendent/Metaphysical: 2 rows of
 *   2-slot blocks): stained glass while dark, dyed terracotta with a glint while lit. Every slot comes in its own
 *   packet, so between two packets a note can be half lit or half dark.
 * - The replay shows one note about every 0.9 s. In the tick the NEW note lights up, the status turns into the timer.
 * - Every click lights the clicked note for ~0.5 s; the last correct click starts the next round at once, a wrong click
 *   or the timer running out ends the experiment.
 *
 * So the solver works on notes (colours), never on single slots: a note is on while ANY of its slots is lit and off only
 * once ALL are dark (no phantom note from a half-updated block, the same note twice in a row shows as on-off-on). Like
 * OdinClient, the chain is the previous chain - exactly what the server just accepted - plus the note lit when the
 * timer starts. The replay is recorded as well: it cross-checks that chain and rebuilds it when the solver joined
 * mid-experiment, and is trusted on its own only when its length matches the timer.
 *
 * @param attachedAtStart the solver was created together with the window (round 1 starts with an empty chain);
 * otherwise it joined mid-experiment and watches until it can reconstruct the chain.
 */
class ChronomatronSolver(host: ExperimentHost, private val attachedAtStart: Boolean): ExperimentSolver(host) {
    private class Note(val color: Int, val slot: Int) {
        override fun toString() = "${colorName(color)}@$slot"
    }

    private enum class Phase { UNKNOWN, REMEMBER, TIMER, OVER }

    private enum class State {
        /** "Remember the pattern!": the chain is replayed. */
        WATCH,
        /** The timer started; waiting for the new note to light up. */
        AWAIT_NEW_NOTE,
        /** Clicking [answer]. */
        ANSWER,
        /** Answered, or not answering this round: waiting for the next round. */
        DONE
    }

    /** The chain as the server knows it after the last answered round. */
    private var chain: List<Note> = emptyList()
    /** [chain] is exactly the server's chain (an empty chain at the start of a fresh experiment). */
    private var chainTrusted = false

    private var phase = Phase.UNKNOWN
    private var state = State.DONE
    /** The next "Remember the pattern!" is round 1 of a game seen from its start. */
    private var freshStart = attachedAtStart
    /** The current round's "Remember the pattern!" was seen from its start. */
    private var roundFromStart = false
    private var answeredRound = false
    private var unknownStatusSince = - 1L

    /** Colours lit right now -> since when. */
    private val litSince = HashMap<Int, Long>()
    private var lastOn: Note? = null
    private var lastOnAt = 0L
    /** Notes that lit up during this round's "Remember the pattern!". */
    private val replay = ArrayList<Note>()
    private var rememberAt = 0L

    private var timerAt = 0L
    /** Chain length announced by the timer (its seconds - 2), -1 when unreadable. */
    private var timerLength = - 1
    /**
     * A note already lit for a while when the timer started: the last replayed note, and also the new note if no other
     * note lights up (the same note replayed and added without a dark gap).
     */
    private var heldNote: Note? = null

    private var newNote: Note? = null
    private var newNoteAt = 0L
    private var answer: List<Note> = emptyList()
    private var nextIndex = 0
    private var armed = false

    override val blocksInput get() = (state == State.WATCH && chainTrusted) || state == State.AWAIT_NEW_NOTE || state == State.ANSWER || (state == State.DONE && answeredRound)

    override fun update(board: ExperimentBoard, status: String, statusChanged: Boolean, now: Long) {
        val seen = phaseOf(status)
        if (seen == Phase.OVER) {
            if (unknownStatusSince < 0) unknownStatusSince = now
            if (now - unknownStatusSince >= UNKNOWN_STATUS_MS && phase != Phase.OVER) experimentOver(status)
            return
        }
        unknownStatusSince = - 1

        trackNotes(board, now)

        if (seen != Phase.UNKNOWN && seen != phase) {
            val previous = phase
            phase = seen
            when {
                seen == Phase.REMEMBER && freshStart -> newGame(now)
                previous == Phase.UNKNOWN -> joined(seen, now)
                seen == Phase.REMEMBER -> startRound(now)
                else -> startTimer(status, now)
            }
            if (stopped) return
        }

        when (state) {
            State.AWAIT_NEW_NOTE -> {
                val held = heldNote
                if (held != null && now - timerAt >= HELD_NOTE_MS) acceptNewNote(held, now, "it stayed lit from the replay")
                else if (now - timerAt >= NEW_NOTE_TIMEOUT_MS) {
                    debug("Chronomatron: no new note ${NEW_NOTE_TIMEOUT_MS}ms after the timer started; answer by hand")
                    skipRound()
                }
            }

            // The first click waits until the new note went dark again (as the old LayBalt solver did; OdinClient's
            // first click effectively lands at the same moment), at most FIRST_CLICK_WAIT_MS.
            State.ANSWER -> if (! armed && (newNote?.color !in litSince || now - newNoteAt >= FIRST_CLICK_WAIT_MS)) {
                armed = true
                clickNow = true
            }

            else -> Unit
        }
    }

    override fun nextClick(board: ExperimentBoard, now: Long): Int? {
        if (state != State.ANSWER || ! armed || phase != Phase.TIMER) return null // clicks only count while the timer runs
        val note = answer[nextIndex ++]
        if (nextIndex >= answer.size) {
            debug("Chronomatron: chain of ${answer.size} clicked")
            state = State.DONE
            answeredRound = true
        }
        return note.slot
    }

    // ------------------------------------------------------------------ notes

    /** Note level edges: a colour turns on when one of its slots lights up and off once none is lit any more. */
    private fun trackNotes(board: ExperimentBoard, now: Long) {
        val lit = HashMap<Int, Int>() // colour -> first lit slot
        for (slot in CHRONOMATRON_BOARD) {
            if (board.kind(slot) == ItemKind.EMPTY || ! board.isLit(slot)) continue
            val color = board.color(slot)
            if (color >= 0) lit.putIfAbsent(color, slot)
        }
        litSince.keys.retainAll(lit.keys)
        for ((color, slot) in lit) {
            if (color in litSince) continue
            litSince[color] = now
            onNoteOn(Note(color, slot), now)
        }
    }

    private fun onNoteOn(note: Note, now: Long) {
        lastOn = note
        lastOnAt = now
        when {
            state == State.AWAIT_NEW_NOTE -> acceptNewNote(note, now, "it lit up after the timer started")
            phase == Phase.REMEMBER && state == State.WATCH -> {
                // The glow of the click that finished the last round can arrive just after the status changed.
                if (now - rememberAt < GLOW_GRACE_MS) return debug("Chronomatron: $note lit right after the round started, a click glow")
                replay += note
                debug("Chronomatron: replay #${replay.size}: $note")
            }
            // A glow from a click (ours or the player's), or the board of a round we do not answer.
            else -> Unit
        }
    }

    // ------------------------------------------------------------------ rounds

    /** Round 1 of a game seen from its first status: the chain is empty. */
    private fun newGame(now: Long) {
        freshStart = false
        chain = emptyList()
        chainTrusted = true
        answeredRound = false
        beginRound(fromStart = true, now)
    }

    /** The solver joined while a game was running (enabled mid-game, or the window opened mid-round): watch. */
    private fun joined(seen: Phase, now: Long) {
        freshStart = false
        chainTrusted = false
        answeredRound = false
        debug("Chronomatron: joined during ${seen.name.lowercase()}, watching until the chain is known")
        if (seen == Phase.REMEMBER) beginRound(fromStart = false, now) else state = State.DONE
    }

    /** "Remember the pattern!" again: the previous round is over. */
    private fun startRound(now: Long) {
        if (state != State.DONE) {
            // The server moved on although our answer was not complete: our chain is not the server's.
            debug("Chronomatron: round ended before our answer was complete ($state, $nextIndex of ${answer.size})")
            chainTrusted = false
        }
        else if (! answeredRound) chainTrusted = false

        if (answeredRound && host.reachedTarget(ExperimentGame.CHRONOMATRON, chain.size)) {
            stop(chain.size)
            return
        }
        answeredRound = false
        beginRound(fromStart = true, now)
    }

    private fun beginRound(fromStart: Boolean, now: Long) {
        state = State.WATCH
        roundFromStart = fromStart
        rememberAt = now
        replay.clear()
        newNote = null
        heldNote = null
        answer = emptyList()
        nextIndex = 0
        armed = false
        debug("Chronomatron: round ${if (chainTrusted) chain.size + 1 else "?"} starts (chain ${if (chainTrusted) chain else "unknown"})")
    }

    private fun startTimer(status: String, now: Long) {
        timerAt = now
        timerLength = status.removePrefix(TIMER_PREFIX).trim().removeSuffix("s").toIntOrNull()?.minus(2) ?: - 1
        if (state != State.WATCH) {
            if (state != State.DONE) debug("Chronomatron: timer started while $state")
            return
        }

        // The new note's slots come before slot 49 in the same tick, so it is usually lit already.
        val candidate = lastOn
        if (candidate != null && candidate.color in litSince) {
            if (now - lastOnAt <= NEW_NOTE_WINDOW_MS) {
                // It was taken for a replayed note because its packets arrived before the status.
                if (replay.lastOrNull() === candidate) replay.removeAt(replay.lastIndex)
                return acceptNewNote(candidate, now, "it lit up together with the timer")
            }
            heldNote = candidate
        }
        state = State.AWAIT_NEW_NOTE
    }

    private fun acceptNewNote(note: Note, now: Long, why: String) {
        newNote = note
        newNoteAt = now
        heldNote = null

        val known = if (chainTrusted) chain + note else null
        val replayed = replay + note
        val replayValid = if (timerLength > 0) replayed.size == timerLength else roundFromStart
        debug("Chronomatron: new note $note ($why); timer length $timerLength, replay $replay")

        val pick = when {
            known != null && sameColors(known, replayed) -> known
            known != null && replayValid && timerLength > 0 && known.size != timerLength -> {
                debug("Chronomatron: the replay $replayed matches the timer, the known chain $known does not: using the replay")
                replayed
            }
            known != null -> {
                debug("Chronomatron: the replay $replayed differs from the known chain $known, keeping the known chain")
                known
            }
            replayValid -> {
                debug("Chronomatron: rebuilt the chain from the replay")
                replayed
            }
            else -> null
        }

        if (pick == null) {
            debug("Chronomatron: chain unknown (replay ${replayed.size}, timer $timerLength); answer this round by hand")
            skipRound()
            return
        }
        chain = pick
        chainTrusted = true
        answer = pick
        nextIndex = 0
        armed = false
        state = State.ANSWER
        debug("Chronomatron: answering $pick")
    }

    private fun skipRound() {
        chainTrusted = false
        answeredRound = false
        state = State.DONE
    }

    private fun experimentOver(status: String) {
        debug("Chronomatron over (status '$status')")
        phase = Phase.OVER
        // A new game in the same window would start from round 1 again.
        freshStart = true
        chain = emptyList()
        chainTrusted = false
        answeredRound = false
        state = State.DONE
    }

    private fun phaseOf(status: String) = when {
        status.isEmpty() -> Phase.UNKNOWN
        status == REMEMBER_TEXT -> Phase.REMEMBER
        status.startsWith(TIMER_PREFIX) -> Phase.TIMER
        else -> Phase.OVER
    }

    private fun sameColors(a: List<Note>, b: List<Note>) = a.size == b.size && a.indices.all { a[it].color == b[it].color }

    private companion object {
        /** An unknown status must stay this long before it counts as "experiment over". */
        const val UNKNOWN_STATUS_MS = 2000L
        /** Notes lighting up this soon after "Remember the pattern!" are click glows (the replay starts ~0.8 s later). */
        const val GLOW_GRACE_MS = 300L
        /** A note that lit up at most this long before the timer is the new note (same server tick). */
        const val NEW_NOTE_WINDOW_MS = 300L
        /** How long to wait for another note before the held note is taken as the new note. */
        const val HELD_NOTE_MS = 500L
        /** No note at all this long after the timer started: give the round to the player. */
        const val NEW_NOTE_TIMEOUT_MS = 1500L
        /** Longest wait for the new note to go dark before the first click. */
        const val FIRST_CLICK_WAIT_MS = 1000L

        val COLOR_NAMES = listOf(
            "white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
            "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"
        )

        fun colorName(color: Int) = COLOR_NAMES.getOrNull(color) ?: "color$color"
    }
}

//#endif
