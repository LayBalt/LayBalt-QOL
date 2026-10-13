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
 * This is the Minecraft side: it reads the world (the board into the [BoardTracker], and the door and indicator blocks
 * on their own for the classic display), hands clicks and chunk changes on and writes the recorder's files. What to show
 * is decided by the pure [WaterRoomCore]: the 3.2.3 display as the baseline, the board-truth guidance of the
 * [WaterSupervisor] only while its inputs agree. The simulator side lives in [WaterSimBridge].
 */
class WaterRoomSession(val room: UniqueRoom, private val tables: Map<Int, ShippedTable>?) : WaterRoomCore.Hooks {
    var frame: RoomFrame? = null
        private set
    val core = WaterRoomCore(tables, this)
    val tracker get() = core.tracker
    val supervisor get() = core.supervisor
    val sim = WaterSimBridge(core.tracker, guard = { what, block -> core.advanced(what, block) }, trace = { core.trace.add(core.now, it) }) { frame }

    override val simSupport: WaterSupervisor.SimSupport get() = sim

    /** Room frame known and every chunk of the board loaded. */
    var available = false
        private set

    private var fullReadDue = true
    private var lastFullRead = BoardTracker.NEVER
    private var now = 0
    private var layoutLogged = false
    private var dumpPending = false
    private var activationsLogged = 0
    private var errorWritten = false
    private var wasNear = false

    /** File name of the layout dump of this room, once written (the recorder links runs to it). */
    var layoutFile: String? = null

    /** The trace file of this room, created with its first line. */
    val traceFile = WaterBoardRecorder.traceFileName()
    private var traceStarted = false

    private var runRecord: RunRecord? = null

    fun entered() {
        fullReadDue = true
        core.trace.add(now, "we walked into the room")
    }

    fun markSolved(reason: String) = core.markSolved(reason)

    fun tick(tick: Int) {
        now = tick
        val f = frame ?: room.rotation?.let { RoomFrame(room.centerPos, (360 - it) % 360) }?.also {
            frame = it
            core.trace.add(tick, "room at ${room.centerPos.x},${room.centerPos.z}, rotation ${room.rotation}")
        }
        if (f == null) {
            core.noFrame(tick)
            flushTrace()
            return
        }

        val wasAvailable = available
        available = WaterBoardReader.loaded(f)
        tracker.rttTicks = (ServerUtils.averagePing / 50).toInt().coerceIn(0, 20)
        if (available && (! wasAvailable || fullReadDue || tick - lastFullRead >= FULL_READ_TICKS)) core.advanced("full read") { fullRead(f) }
        // The classic display reads the door and indicator blocks itself, whatever the board-truth side makes of them.
        val gates = WaterBoardReader.readGates(f)
        val indicator = if (core.classic.variant < 0) WaterBoardReader.readVariant(f) else null
        core.recovery = PuzzleSolvers.waterRecovery.value
        core.toleranceTicks = (PuzzleSolvers.waterTolerance.value.toDouble() * 20).toInt()
        val rekeys = core.classic.rekeys
        core.tick(tick, available, gates, indicator)
        if (core.classic.rekeys != rekeys && activationsLogged < 5) {
            // What the door column holds when the doors are set (wool? pistons?), for the next field report.
            activationsLogged ++
            val columns = WaterBoardReader.doorColumns(f)
            core.trace.add(tick, "door blocks y${BoardGeometry.DOOR_Y - 1}..${BoardGeometry.DOOR_Y + 2}: $columns")
            LayBaltQOL.logger.info("[WaterBoard] doors ${WaterTrace.key(core.classic.gates)} closed (${columns}), showing ${core.classic.play ?: "nothing"}")
        }

        // Only to players at the board: the session lives for the whole dungeon, and someone clearing another room
        // has nothing to turn off.
        val near = nearRoom()
        if (near != wasNear) {
            wasNear = near
            core.trace.add(tick, if (near) "we are at the room: showing ${core.mode.name.lowercase()}" else "we left the room")
        }
        for (message in core.drainMessages()) {
            if (core.recovery && PuzzleSolvers.waterFailMessage.value && near) ChatUtils.modMessage(message)
            LayBaltQOL.logger.info("[WaterBoard] ${message.removeFormatting()}")
        }
        for (line in core.drainLog()) LayBaltQOL.logger.info("[WaterBoard] $line")
        flushTrace()
    }

    private fun fullRead(f: RoomFrame) {
        WaterBoardReader.readAll(f, tracker)
        lastFullRead = now
        fullReadDue = false
    }

    private fun flushTrace() {
        val lines = core.trace.drain()
        if (lines.isEmpty() || ! WaterBoardRecorder.enabled) return
        val header = if (traceStarted) null else WaterBoardRecorder.traceHeader(room.name, room.centerPos.x, room.centerPos.z)
        traceStarted = true
        WaterBoardRecorder.appendTrace(traceFile, header, lines)
    }

    /** The player is in the room or close to it. */
    private fun nearRoom(): Boolean {
        val player = LayBaltQOL.mc.player ?: return false
        return GuidanceGate.visible(ScanUtils.currentRoom === room, hypot(player.x - room.centerPos.x, player.z - room.centerPos.z))
    }

    /** The guidance to draw: nothing while the player is away from the room, nothing once solved. */
    fun displayed(): Guidance? = core.display.takeIf { nearRoom() && it != Guidance.Solved }

    fun onClick(pos: BlockPos, tick: Int) {
        val f = frame ?: return
        WaterLever.entries.firstOrNull { f.lever(it) == pos }?.let { core.onClick(it, tick) }
    }

    fun onBlockChange(event: BlockChangeEvent) {
        val f = frame ?: return
        if (! available) return
        core.advanced("block change") { WaterBoardReader.onChange(f, tracker, event.pos, event.newState) }
    }

    /** Chunk data replaces whole sections without single block changes: read the board again right away. */
    fun onChunkLoaded(chunkX: Int, chunkZ: Int) {
        val f = frame ?: return
        if (! f.covers(chunkX, chunkZ)) return
        fullReadDue = true
        if (WaterBoardReader.loaded(f)) {
            available = true
            tracker.available = true
            core.advanced("full read") { fullRead(f) }
        }
    }

    /**
     * A chunk of the board was dropped (the player went far away, teleported or died): nothing is read until it is back,
     * and what is read then is a fresh picture, not a live change.
     */
    fun onChunkUnloaded(chunkX: Int, chunkZ: Int) {
        val f = frame ?: return
        if (! f.covers(chunkX, chunkZ)) return
        available = false
        tracker.available = false
        core.advanced("chunk unload") { tracker.markUnwatched() }
    }

    /** `/lbq water replan`: forget the plan and plan again from the board as it is. */
    fun replan() = core.replan()

    // ================================================================================================================
    // Hooks of the core
    // ================================================================================================================

    override fun layoutCaptured(core: WaterRoomCore, table: ShippedTable?) {
        if (! layoutLogged) {
            layoutLogged = true
            LayBaltQOL.logger.info(
                "[WaterBoard] variant ${tracker.variant}, closed doors ${Door.keyOf(tracker.doors).ifEmpty { "-" }}, " +
                    "untouched state from ${tracker.startSource}, ${tracker.toggleLever.count { it >= 0 }} toggle blocks" +
                    if (tracker.doors == 0) " (the doors close when the first player walks in)" else ""
            )
        }
        // The dump waits for the first update, so it carries the doors and the water as the tracker sees them.
        if (WaterBoardRecorder.enabled) dumpPending = true
        sim.onLayoutCaptured(table)
    }

    override fun events(core: WaterRoomCore, events: List<BoardEvent>) {
        if (dumpPending) {
            dumpPending = false
            WaterBoardRecorder.dumpLayout(this, "auto")
        }
        recordRun(events)
        sim.onEvents(events)
    }

    override fun requests(core: WaterRoomCore, requests: List<WaterSupervisor.Request>, sup: WaterSupervisor) {
        if (requests.isNotEmpty()) sim.handle(requests, sup, now)
    }

    override fun afterUpdate(core: WaterRoomCore, solved: Boolean) {
        sim.tick(now, core.supervisor?.silentFailures ?: 0, solved)
    }

    override fun failed(what: String, error: Throwable) {
        // Each side reports once per room; the classic display carries on.
        LayBaltQOL.logger.error("[WaterBoard] $what failed - the classic display takes over for this room", error)
        if (! errorWritten && WaterBoardRecorder.enabled) {
            errorWritten = true
            WaterBoardRecorder.appendLine("errors.log", "${java.time.LocalDateTime.now()} ${LayBaltQOL.MOD_VERSION} $what: ${error.stackTraceToString().take(4000)}")
        }
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
        val unwatched = events.any { it is BoardEvent.Resynced }
        val over = unwatched || supervisor?.stage == WaterSupervisor.Stage.SOLVED || core.confirmedSolved ||
            (! tracker.waterOn && tracker.boardWater == 0 && now - tracker.lastWaterChange > RUN_END_DRY_TICKS) ||
            now - running.startTick > RUN_MAX_TICKS
        if (! over) return
        running.endTick = now
        running.result = when {
            unwatched -> "not watched to the end"
            tracker.doors == 0 || core.confirmedSolved -> "solved"
            else -> "doors ${Door.keyOf(tracker.doors)}"
        }
        tracker.recording = null
        runRecord = null
        WaterBoardRecorder.writeRun(this, running)
    }

    // ================================================================================================================
    // Status
    // ================================================================================================================

    /** Lines for `/lbq water status`. */
    fun status(): List<String> {
        val lines = mutableListOf<String>()
        lines += "§bWater Board §7(${if (available) "board loaded" else "board not loaded"})"
        lines += core.displayStatus()
        if (! tracker.layoutCaptured) {
            lines += "§7Layout: §funknown §7(no pattern block found) §8- §7/lbq water dump"
            return lines
        }
        lines += "§7Variant §f${tracker.variant} §7- closed doors: ${Door.names(tracker.doors)}"
        lines += "§7Levers flipped (vs untouched): §f${WaterLever.names(tracker.parity).ifEmpty { "none" }}" +
            (if (tracker.parityUncertain != 0) " §7(start of ${WaterLever.names(tracker.parityUncertain)} uncertain)" else "")
        lines += "§7Untouched state from: §f${tracker.startSource}"
        lines += "§7Water: §f${if (tracker.waterOn) "on" else "off"}§7, §f${tracker.boardWater} §7cells on the board"
        lines += "§7Latency: §f${tracker.lag(0)} §7ticks (levers), ping §f${tracker.rttTicks} §7ticks" +
            (if (tracker.resyncs > 0) "§7; read again after not being watched: §f${tracker.resyncs}x" else "")
        lines += sim.status()
        supervisor?.let { sup ->
            lines += "§7Stage: §f${sup.stage}" + (sup.play?.let { " §7- play §f${it.key}" } ?: "") +
                (if (sup.chain.size > 1) " §7(${sup.chainIndex + 1}/${sup.chain.size}: ${sup.chain.joinToString(" ") { it.key }})" else "")
            sup.lastIncident?.let { lines += "§7Last problem: §f${it.text}" }
        }
        if (WaterBoardRecorder.enabled) lines += "§7Recorder: §fon §7- this room's trace: §f$traceFile"
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
