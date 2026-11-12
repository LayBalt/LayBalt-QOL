package com.laybalt.features.impl.dungeon.solvers.puzzles.water

/**
 * A compact, human-readable trace of one Water Board room: what the board did (activation, doors, lever flips, the
 * water at the entry hole and at the outlets, unwatched stretches) and what the solver made of it (stages, display mode,
 * incidents). The recorder writes it to `config/LayBaltQOL/waterboard/` so a bug can be found from the player's files.
 *
 * Bounded: after [MAX_LINES] lines one truncation note is written and the rest is dropped. Pure.
 */
class WaterTrace(private val maxLines: Int = MAX_LINES) {
    private val pending = ArrayList<String>()
    private var written = 0
    private var truncated = false

    /** Adds a line stamped with the server tick. */
    fun add(tick: Int, text: String) {
        if (truncated) return
        if (written >= maxLines) {
            truncated = true
            pending += "t=$tick trace truncated after $maxLines lines"
            return
        }
        written ++
        pending += "t=$tick $text"
    }

    /** Lines added since the last call. */
    fun drain(): List<String> {
        if (pending.isEmpty()) return emptyList()
        val out = pending.toList()
        pending.clear()
        return out
    }

    companion object {
        const val MAX_LINES = 4000

        fun describe(e: BoardEvent): String = when (e) {
            is BoardEvent.LeverFlipped -> "flip ${e.lever.displayName} by ${e.source} (click ${e.clickTick}, seen ${e.tick})"
            is BoardEvent.WaterSwitched ->
                "water ${if (e.on) "on" else "off"} by ${e.source} (click ${e.clickTick}, seen ${e.tick})" + if (e.correction) " - correction: the board did not follow the lever" else ""

            is BoardEvent.DoorToggled -> "door ${e.door.displayName} ${if (e.closed) "closed" else "opened"} (since ${e.tick})"
            is BoardEvent.Resynced -> "read again after ${e.gap} ticks unwatched: doors ${key(e.doorsBefore)} -> ${key(e.doorsAfter)}, " +
                "levers moved ${WaterLever.names(e.levers).ifEmpty { "none" }}, water ${onOff(e.waterBefore)} -> ${onOff(e.waterAfter)}"
        }

        fun key(doors: Int) = Door.keyOf(doors).ifEmpty { "-" }

        private fun onOff(on: Boolean) = if (on) "on" else "off"
    }
}
