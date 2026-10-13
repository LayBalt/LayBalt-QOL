package com.laybalt.features.impl.dungeon.solvers.puzzles.water

import java.util.Locale

/**
 * One read of the five door blocks: [closed] bits (wool or another solid block), [moving] bits (a piston is moving
 * that door right now: its last state is kept). A read whose chunk is not loaded is no read at all (null), never "open".
 */
data class GateRead(val closed: Int, val moving: Int = 0)

/**
 * The 3.2.3 Water Board display, kept as the baseline that never depends on the board-truth pipeline ([BoardTracker],
 * [WaterSupervisor], the simulator): once doors read closed, the shipped timed solution for the variant and the doors
 * closed right now, with the countdown and CLICK labels above the levers and a tracer to the next one. Our own clicks
 * move it on and the first click on the water lever starts the countdowns, exactly like 3.2.3.
 *
 * Doors come from its own reads of the door blocks ([GateRead]): unknown reads and doors caught mid-move keep the last
 * state, and a change counts once it held for [GATE_STABLE_TICKS]. The play follows the doors closed right now whenever
 * no run is going (before the water is pulled, and again once a run is over); during a run it stays, since the doors
 * changing is what the run is for. Every door that was closed reading open again is solved (the 3.2.3 rule). Pure.
 */
class ClassicDisplay(private val tables: Map<Int, ShippedTable>?) {
    /** Layout variant from the indicator blocks (-1 until known). */
    var variant = - 1
        private set

    /** Closed doors as last read stable (-1 until the first stable read). */
    var gates = - 1
        private set

    /** Doors have been read closed at least once. */
    var activated = false
        private set

    /** Every door that was closed is open: done (until a door closes again). */
    var solved = false
        private set

    /** The doors the shown play is for. */
    var key = 0
        private set

    /** The plays shown, one after the other (one for three closed doors). */
    var chain: List<ShippedPlay> = emptyList()
        private set
    var chainIndex = 0
        private set
    val play: ShippedPlay? get() = chain.getOrNull(chainIndex)

    /** Click-frame tick of our first click on the water lever for this play (-1: not pulled yet). */
    var waterTick = - 1
        private set

    /** How often the display started over (new doors, or a run that is over). */
    var rekeys = 0
        private set

    private val clicks = IntArray(WaterLever.entries.size)
    private var pendingRaw = - 1
    private var pendingSince = 0
    private val events = ArrayList<String>()

    /** What the display did since the last call (activation, new key, run over, solved), for the trace. */
    fun drainEvents(): List<String> {
        if (events.isEmpty()) return emptyList()
        val out = events.toList()
        events.clear()
        return out
    }

    /** The variant read from the indicator blocks (null or -1: not known from this read). */
    fun onVariant(read: Int?, now: Int) {
        if (read == null || read < 0 || variant >= 0) return
        variant = read
        if (activated) rekey(now, "layout $read read")
    }

    /** This tick's read of the door blocks (null: not loaded, nothing is concluded from it). */
    fun onGates(read: GateRead?, now: Int) {
        if (read == null) return
        val previous = if (pendingRaw >= 0) pendingRaw else 0
        val raw = (read.closed and read.moving.inv() or (previous and read.moving)) and Door.ALL
        if (raw != pendingRaw) {
            pendingRaw = raw
            pendingSince = now
        }
        if (now - pendingSince >= GATE_STABLE_TICKS && raw != gates) setGates(raw, now)
    }

    /** Advances to [now]: a run that is over hands the display back to the doors closed now. */
    fun tick(now: Int) {
        if (activated && ! solved && runOver(now)) rekey(now, "run over")
    }

    /** One of our clicks on [lever] (3.2.3: every click counts, the first one on the water lever starts the timers). */
    fun onClick(lever: WaterLever, now: Int) {
        if (! activated || solved || play == null) return
        if (lever == WaterLever.WATER && waterTick < 0) waterTick = now
        clicks[lever.ordinal] ++
    }

    private fun setGates(closed: Int, now: Int) {
        gates = closed
        if (closed == 0) {
            if (activated && ! solved) {
                solved = true
                events += "classic: every door open - solved"
            }
            return
        }
        if (solved) {
            solved = false
            events += "classic: ${Door.keyOf(closed)} closed again"
        }
        if (! activated) {
            activated = true
            rekey(now, "doors ${Door.keyOf(closed)} closed")
            return
        }
        if (closed != key && (waterTick < 0 || runOver(now))) rekey(now, "doors ${Door.keyOf(closed)} closed now")
    }

    private fun runOver(now: Int): Boolean {
        if (waterTick < 0) return false
        return now - waterTick > (play?.lastClickTick ?: 0) + RUN_OVER_TICKS
    }

    private fun rekey(now: Int, why: String) {
        key = if (gates > 0) gates else key
        clicks.fill(0)
        waterTick = - 1
        chainIndex = 0
        val table = tables?.get(variant)
        chain = when {
            table == null || key == 0 -> emptyList()
            Door.count(key) == 3 -> listOfNotNull(table.play(key))
            // Not the three doors of a fresh room (a run already toggled some): the shipped plays that add up to them.
            else -> DoorAlgebra.best(key, table, 0, boardReady = true)
        }
        rekeys ++
        events += "classic: $why at $now - " + (play?.let { "play v$variant/${Door.keyOf(key)}" + if (chain.size > 1) " (${chain.size} plays)" else "" }
            ?: "no play (layout $variant)")
    }

    /**
     * What 3.2.3 showed: for every lever its remaining times (static seconds before the water, countdowns after it,
     * CLICK when due), the tracer to the next lever and a line to the one after. Null before any door closed.
     */
    fun guidance(now: Int): Guidance? {
        if (! activated) return null
        if (solved) return Guidance.Solved
        val play = play ?: return Guidance.Status(
            if (variant < 0) "§7Water Board: reading the layout…" else "§cWater Board: §fno solution for ${Door.names(key)} §7(layout $variant)"
        )
        val labels = ArrayList<LeverLabel>()
        val pending = ArrayList<Pair<WaterLever, Double>>()
        for (lever in WaterLever.entries) {
            val skip = clicks[lever.ordinal]
            play.times[lever].orEmpty().drop(skip).forEachIndexed { i, seconds ->
                pending += lever to seconds
                labels += LeverLabel(lever, text(seconds, now), LabelStyle.SOLUTION, i + skip)
            }
        }
        if (labels.isEmpty()) return Guidance.Status("§bWater Board: §fwaiting for the water to reach ${Door.names(play.doors)}")
        pending.sortBy { (lever, seconds) -> seconds + if (lever == WaterLever.WATER) 0.01 else 0.0 }
        val tracer = pending.first().first
        val second = pending.getOrNull(1)?.first
        val line = if (second != null && second != tracer) tracer to second else null
        val headline = if (chain.size > 1) "§dRun ${chainIndex + 1}/${chain.size} §7(${Door.names(play.doors)}§7) §f- follow the timers" else null
        return Guidance.Act(labels, tracer, line, headline)
    }

    private fun text(seconds: Double, now: Int): String {
        val ticks = ShippedPlay.ticks(seconds)
        if (waterTick < 0) return if (ticks == 0) "§a§lCLICK" else color(seconds) + "${seconds}s"
        val left = waterTick + ticks - now
        if (left <= 0) return "§a§lCLICK"
        val s = left / 20.0
        return color(s) + String.format(Locale.ROOT, "%.1f", s) + "s"
    }

    private fun color(seconds: Double) = when {
        seconds < 2 -> "§c"
        seconds < 6 -> "§e"
        else -> "§a"
    }

    companion object {
        /** A door read must hold this long before it counts (a piston moves a door in 2-3 ticks). */
        const val GATE_STABLE_TICKS = 5

        /** A run is over this long after its last planned click: the doors have long toggled by then. */
        const val RUN_OVER_TICKS = 200
    }
}
