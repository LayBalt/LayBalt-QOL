package com.laybalt.features.impl.dungeon.solvers.puzzles

import com.laybalt.LayBaltQOL
import com.laybalt.event.impl.BlockChangeEvent
import com.laybalt.features.impl.dungeon.solvers.PuzzleSolvers
import com.laybalt.features.impl.dungeon.solvers.puzzles.water.*
import com.laybalt.utils.ChatUtils
import com.laybalt.utils.ChatUtils.removeFormatting
import com.laybalt.utils.ServerUtils
import com.laybalt.utils.dungeons.map.core.UniqueRoom
import com.laybalt.utils.dungeons.map.utils.ScanUtils
import net.minecraft.core.BlockPos
import kotlin.math.hypot

/**
 * Everything about the Water Board room of one dungeon (client thread only). It lives until the world changes or the
 * boss starts: leaving the room keeps it, so walking away and coming back continues from the board as it is.
 *
 * Every server tick it reads the board ([WaterBoardReader] into the [BoardTracker]), hands the board's events to the
 * [WaterSupervisor] and caches the resulting [Guidance], which the solver draws while the player is in or near the room.
 * The simulator side (calibration, plans, checks, shadowing) lives in [WaterSimBridge].
 */
class WaterRoomSession(val room: UniqueRoom, private val tables: Map<Int, ShippedTable>?) : WaterSupervisor.RunListener {
    var frame: RoomFrame? = null
        private set
    val tracker = BoardTracker()
    var supervisor: WaterSupervisor? = null
        private set
    val sim = WaterSimBridge(tracker) { frame }

    /** Room frame known and every chunk of the board loaded. */
    var available = false
        private set

    private var fullReadDue = true
    private var lastFullRead = BoardTracker.NEVER
    private var now = 0
    private var cached: Guidance = Guidance.Status("§7Water Board: reading the board…")
    private var solvedByMap = false
    private var layoutLogged = false

    /** File name of the layout dump of this room, once written (the recorder links runs to it). */
    var layoutFile: String? = null

    private var runRecord: RunRecord? = null

    fun entered() {
        fullReadDue = true
    }

    fun markSolved() {
        solvedByMap = true
        cached = Guidance.Solved
    }

    fun tick(tick: Int) {
        now = tick
        val f = frame ?: room.rotation?.let { RoomFrame(room.centerPos, 360 - it) }?.also { frame = it }
        if (f == null) {
            cached = Guidance.Status("§7Water Board: waiting for the room scan…")
            return
        }

        val wasAvailable = available
        available = WaterBoardReader.loaded(f)
        tracker.available = available
        tracker.rttTicks = (ServerUtils.averagePing / 50).toInt().coerceIn(0, 20)
        if (available && (! wasAvailable || fullReadDue || tick - lastFullRead >= FULL_READ_TICKS)) fullRead(f)
        if (available && ! tracker.layoutCaptured && tracker.captureLayout()) onLayoutCaptured()

        tracker.update(tick)
        val events = tracker.drainEvents()
        tracker.drainNotes().forEach { LayBaltQOL.logger.info("[WaterBoard] $it") }
        recordRun(events)
        sim.onEvents(events)

        supervisor?.let { sup ->
            sup.recovery = PuzzleSolvers.waterRecovery.value
            sup.toleranceTicks = (PuzzleSolvers.waterTolerance.value.toDouble() * 20).toInt()
            sup.update(tracker, events)
            sim.handle(sup.drainRequests(), sup, tick)
            for (message in sup.drainMessages()) {
                if (sup.recovery && PuzzleSolvers.waterFailMessage.value) ChatUtils.modMessage(message)
                LayBaltQOL.logger.info("[WaterBoard] ${message.removeFormatting()}")
            }
        }
        if (available && tracker.layoutCaptured) sim.tick(tick, supervisor?.silentFailures ?: 0)
        cached = compute()
    }

    private fun fullRead(f: RoomFrame) {
        WaterBoardReader.readAll(f, tracker)
        lastFullRead = now
        fullReadDue = false
    }

    private fun onLayoutCaptured() {
        val table = tables?.get(tracker.variant)
        if (! layoutLogged) {
            layoutLogged = true
            LayBaltQOL.logger.info(
                "[WaterBoard] variant ${tracker.variant}, closed doors ${Door.keyOf(tracker.doors).ifEmpty { "-" }}, " +
                    "untouched state from ${tracker.startSource}, ${tracker.toggleLever.count { it >= 0 }} toggle blocks"
            )
        }
        if (WaterBoardRecorder.enabled) WaterBoardRecorder.dumpLayout(this, "auto")
        sim.onLayoutCaptured(table)
        if (table == null) return
        supervisor = WaterSupervisor(table).also {
            it.listener = this
            it.sim = sim
        }
    }

    private fun compute(): Guidance {
        if (solvedByMap) return Guidance.Solved
        if (tables == null) return Guidance.Status("§cWater Board: §fthe solution data failed to load")
        if (! available) return Guidance.Status("§7Water Board: walk closer to the board")
        if (! tracker.layoutCaptured) return Guidance.Status("§7Water Board: unknown layout §8- §7use §f/lbq water dump")
        val sup = supervisor ?: return Guidance.Status("§cWater Board: §fno solutions for layout ${tracker.variant}")
        return sup.guidance(tracker)
    }

    /** The guidance to draw: nothing while the player is away from the room, nothing once solved. */
    fun displayed(): Guidance? {
        val player = LayBaltQOL.mc.player ?: return null
        val inRoom = ScanUtils.currentRoom === room
        val distance = hypot(player.x - room.centerPos.x, player.z - room.centerPos.z)
        return GuidanceGate.forDisplay(cached, inRoom, distance)?.takeUnless { it == Guidance.Solved }
    }

    fun onClick(pos: BlockPos, tick: Int) {
        val f = frame ?: return
        WaterLever.entries.firstOrNull { f.lever(it) == pos }?.let { tracker.ownClick(it, tick) }
    }

    fun onBlockChange(event: BlockChangeEvent) {
        val f = frame ?: return
        if (! available) return
        WaterBoardReader.onChange(f, tracker, event.pos, event.newState)
    }

    /** Chunk data replaces whole sections without single block changes: read the board again right away. */
    fun onChunkLoaded(chunkX: Int, chunkZ: Int) {
        val f = frame ?: return
        if (! f.covers(chunkX, chunkZ)) return
        fullReadDue = true
        if (WaterBoardReader.loaded(f)) {
            available = true
            tracker.available = true
            fullRead(f)
        }
    }

    /** `/lbq water replan`: forget the plan and plan again from the board as it is. */
    fun replan() {
        supervisor?.let { sup ->
            sup.replan(tracker)
            sim.handle(sup.drainRequests(), sup, now)
        }
        cached = compute()
    }

    // ================================================================================================================
    // Recorder
    // ================================================================================================================

    private fun recordRun(events: List<BoardEvent>) {
        val record = runRecord
        for (e in events) {
            if (e !is BoardEvent.WaterSwitched) continue
            if (e.on && record == null && WaterBoardRecorder.enabled && tracker.layoutCaptured) {
                runRecord = RunRecord(e.tick, tracker.variant, Door.keyOf(tracker.doors), tracker.mask, tracker.water.copyOf()).also {
                    tracker.recording = it
                    if (layoutFile == null) WaterBoardRecorder.dumpLayout(this, "run")
                }
            }
        }
        val running = runRecord ?: return
        val over = supervisor?.stage == WaterSupervisor.Stage.SOLVED || solvedByMap ||
            (! tracker.waterOn && tracker.boardWater == 0 && now - tracker.lastWaterChange > RUN_END_DRY_TICKS) ||
            now - running.startTick > RUN_MAX_TICKS
        if (! over) return
        running.endTick = now
        running.result = if (tracker.doors == 0 || solvedByMap) "solved" else "doors ${Door.keyOf(tracker.doors)}"
        tracker.recording = null
        runRecord = null
        WaterBoardRecorder.writeRun(this, running)
    }

    override fun runStarted(play: ShippedPlay, tick: Int) {
        LayBaltQOL.logger.info("[WaterBoard] run $play started at $tick")
    }

    override fun runEnded(reason: String, tick: Int) {
        LayBaltQOL.logger.info("[WaterBoard] run ended at $tick: $reason")
    }

    // ================================================================================================================
    // Status
    // ================================================================================================================

    /** Lines for `/lbq water status`. */
    fun status(): List<String> {
        val lines = mutableListOf<String>()
        lines += "§bWater Board §7(${if (available) "board loaded" else "board not loaded"})"
        if (! tracker.layoutCaptured) {
            lines += "§7Layout: §funknown §7(no pattern block found) §8- §7/lbq water dump"
            return lines
        }
        lines += "§7Variant §f${tracker.variant} §7- closed doors: ${Door.names(tracker.doors)}"
        lines += "§7Levers flipped (vs untouched): §f${WaterLever.names(tracker.parity).ifEmpty { "none" }}" +
            (if (tracker.parityUncertain != 0) " §7(start of ${WaterLever.names(tracker.parityUncertain)} uncertain)" else "")
        lines += "§7Untouched state from: §f${tracker.startSource}"
        lines += "§7Water: §f${if (tracker.waterOn) "on" else "off"}§7, §f${tracker.boardWater} §7cells on the board"
        lines += "§7Latency: §f${tracker.lag(0)} §7ticks (levers), ping §f${tracker.rttTicks} §7ticks"
        lines += sim.status()
        supervisor?.let { sup ->
            lines += "§7Stage: §f${sup.stage}" + (sup.play?.let { " §7- play §f${it.key}" } ?: "") +
                (if (sup.chain.size > 1) " §7(${sup.chainIndex + 1}/${sup.chain.size}: ${sup.chain.joinToString(" ") { it.key }})" else "")
            sup.lastIncident?.let { lines += "§7Last problem: §f${it.text}" }
        }
        return lines
    }

    companion object {
        /** Safety net: missed packets and chunk resends are caught by a full read this often. */
        const val FULL_READ_TICKS = 20

        /** A recorded run ends once the water is off and the board stayed dry this long. */
        const val RUN_END_DRY_TICKS = 40

        /** Recorded runs are cut off after this long (two minutes). */
        const val RUN_MAX_TICKS = 2400
    }
}
