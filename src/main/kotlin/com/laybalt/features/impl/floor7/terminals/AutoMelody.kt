package com.laybalt.features.impl.floor7.terminals

//#if CHEAT

/**
 * The decisions of Auto Melody (NoammAddons AutoTerminal `melodyTick`, Melody-AutoTerm settings), kept free of game
 * state so they can be tested. [AutoTerminal] feeds them [com.laybalt.features.impl.floor7.terminals.impl.MelodyTerminal]'s
 * state and sends the clicks.
 *
 * LayBalt's Melody is the 3-row one: lanes in rows 1-3 with the clay buttons at column 7 (slots 16, 25, 34), the magenta
 * column marker above and below the lanes. `current` and `correct` are lane columns 0..4, `buttonRow` is 0..2.
 */
internal object AutoMelody {
    /** Rows with a lane and a clay button. */
    const val ROWS = 3

    /** Upstream's minimum time between two Melody clicks. */
    const val COOLDOWN_MS = 250L

    const val SKIP_EDGES = 0
    const val SKIP_ALL = 1

    /** The clay button of [row] (0..2). */
    fun claySlot(row: Int) = row * 9 + 16

    /**
     * The clay slot to click now, or null: the green pane is in the marked column ([current] == [correct]), the cooldown
     * has passed, and the same button was not clicked since the last slot update ([lastClickedSlot]).
     */
    fun clickFor(current: Int?, correct: Int?, buttonRow: Int?, sinceLastClickMs: Long, lastClickedSlot: Int?): Int? {
        if (sinceLastClickMs < COOLDOWN_MS) return null
        if (current == null || correct == null || buttonRow == null) return null
        if (buttonRow !in 0 until ROWS) return null
        if (current != correct) return null
        return claySlot(buttonRow).takeUnless { it == lastClickedSlot }
    }

    /**
     * Melody Skip: after clicking row [buttonRow] at column [current], the clay buttons of the following rows are also
     * clicked, `ticks` later (1 tick per row), before their lanes start. Returns (ticks, slot) pairs.
     *
     * - Skip Mode Edges only skips from the edge columns (0 and 4), All from any column.
     * - Without Skip First Row, the first row only skips from the last column (upstream).
     * - Only rows that exist are clicked: upstream also clicks `slot + 18` from the second row, a non-button slot (43)
     *   left from the 4-row Melody.
     */
    fun skipClicks(buttonRow: Int, current: Int, mode: Int, skipFirstRow: Boolean): List<Pair<Int, Int>> {
        if (buttonRow !in 0 until ROWS - 1) return emptyList()
        if (! skipFirstRow && buttonRow == 0 && current != 4) return emptyList()
        if (mode != SKIP_ALL && current != 0 && current != 4) return emptyList()
        return (1 until ROWS - buttonRow).map { it to claySlot(buttonRow + it) }
    }
}
//#endif
