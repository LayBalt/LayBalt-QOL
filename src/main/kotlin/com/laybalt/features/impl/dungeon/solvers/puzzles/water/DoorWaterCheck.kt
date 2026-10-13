package com.laybalt.features.impl.dungeon.solvers.puzzles.water

/**
 * The whole recovery rests on doors toggling only when water reaches their outlet (and keeping their state while the
 * board drains). This checks every door toggle against the water in its outlet's column (x, y 58..61, the row above the
 * outlet down to the basin) from [WINDOW] ticks before the toggle until [DELAY] ticks after it, and reports the ones
 * without any: recordings then show if Hypixel does otherwise. A diagnostic only; nothing acts on it. Pure.
 *
 * Not checked: door changes before any water came into the board (Hypixel closing the doors when the first player walks
 * in), and door changes found by a re-read after the board was not watched (they are no [BoardEvent.DoorToggled]).
 */
class DoorWaterCheck(private val tracker: BoardTracker) {
    private var waterSeen = false
    private val pending = ArrayList<BoardEvent.DoorToggled>()

    /** Door toggles reported without water so far. */
    var withoutWater = 0
        private set

    fun onEvents(events: List<BoardEvent>) {
        for (e in events) when (e) {
            is BoardEvent.WaterSwitched -> if (e.on && ! e.correction) waterSeen = true
            is BoardEvent.DoorToggled -> if (waterSeen) pending += e
            is BoardEvent.Resynced -> {
                pending.clear()
                if (e.waterAfter) waterSeen = true
            }

            is BoardEvent.LeverFlipped -> {}
        }
    }

    /** Checks the toggles that are [DELAY] ticks old; returns a line for each one without water. */
    fun tick(now: Int, outletX: (Door) -> Int): List<String> {
        if (tracker.boardWater > 0) waterSeen = true
        if (pending.isEmpty()) return emptyList()
        val out = ArrayList<String>()
        pending.removeAll { e ->
            if (now - e.tick < DELAY) return@removeAll false
            val x = outletX(e.door)
            if (! hadWater(x, e.tick)) {
                withoutWater ++
                out += "the ${e.door.displayName} door ${if (e.closed) "closed" else "opened"} at ${e.tick} without water in its outlet column (x=$x)"
            }
            true
        }
        return out
    }

    private fun hadWater(x: Int, tick: Int): Boolean = (BoardGeometry.OUTLET_Y - 2 .. BoardGeometry.OUTLET_Y + 1).any { y ->
        val cell = BoardGeometry.cell(x, y)
        tracker.water[cell] >= 0 || tracker.cellChangeTick[cell] >= tick - WINDOW
    }

    companion object {
        /** Water in the column this long before the toggle counts as the water that moved the door. */
        const val WINDOW = 40

        /** The check waits this long after the toggle (the server may react to water still on its way down). */
        const val DELAY = 10

        /** Outlet x per door digit (Purple .. Red): the old Skyblocker mapping, used until the gate fitted one. */
        val DEFAULT_OUTLET_X = intArrayOf(9, 5, 0, - 5, - 9)
    }
}
