package com.laybalt.features.impl.dungeon.solvers.puzzles

import com.laybalt.LayBaltQOL
import com.laybalt.event.impl.BlockChangeEvent
import com.laybalt.event.impl.DungeonEvent
import com.laybalt.event.impl.PlayerInteractEvent
import com.laybalt.features.impl.dungeon.solvers.PuzzleSolvers
import com.laybalt.features.impl.dungeon.solvers.puzzles.water.*
import com.laybalt.init.DataDownloader
import com.laybalt.utils.MathUtils.add
import com.laybalt.utils.MathUtils.center
import com.laybalt.utils.MathUtils.toVec
import com.laybalt.utils.dungeons.map.core.RoomState
import com.laybalt.utils.dungeons.map.core.UniqueRoom
import com.laybalt.utils.dungeons.map.utils.ScanUtils
import com.laybalt.utils.render.world.Render3D.renderBox
import com.laybalt.utils.render.world.Render3D.renderLine
import com.laybalt.utils.render.world.Render3D.renderString
import com.laybalt.utils.render.world.Render3D.renderTracer
import com.laybalt.utils.render.world.RenderContext
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket

/**
 * Water Board solver. It reads the real board every server tick - the doors, the water and both planes of the lever
 * blocks - instead of counting clicks, so a run that goes wrong is noticed from what the board does, and the guidance
 * always starts from the board as it is: the shipped timed solution on an untouched board, otherwise "turn the water
 * off, let the board drain, flip the marked levers, pull the water" with the plays that open the doors that are still
 * closed ([WaterSupervisor]).
 *
 * The [WaterRoomSession] lives for the whole dungeon: leaving the room keeps it, coming back continues from the board.
 */
object WaterBoardSolver: PuzzleSolver {
    override val enabled get() = PuzzleSolvers.water.value

    const val ROOM_NAME = "Water Board"

    /** Every shipped play by variant (Odin's solutions, BSD-3); null when the bundled data failed to load. */
    private val tables: Map<Int, ShippedTable>? by lazy {
        runCatching {
            ShippedTable.parseAll(DataDownloader.loadJson<Map<String, Map<String, Map<String, List<Double>>>>>("waterSolutions.json"))
        }.onFailure { LayBaltQOL.logger.error("[WaterBoard] could not load waterSolutions.json", it) }.getOrNull()
    }

    /** Server ticks counted by this solver (TickEvent.Server): the time base of everything here. */
    var serverTicks = 0
        private set

    var session: WaterRoomSession? = null
        private set

    /** HUD line of the current guidance, null when there is nothing to show (or the player is elsewhere). */
    val hudText: String?
        get() {
            if (! enabled) return null
            return session?.displayed()?.hudLine
        }

    // ================================================================================================================
    // Events
    // ================================================================================================================

    override fun onRoomEnter(event: DungeonEvent.RoomEvent.onEnter) {
        if (event.room.name == ROOM_NAME) ensureSession(event.room).entered()
    }

    override fun onRotationFound(event: DungeonEvent.RoomEvent.RotationFound) {
        if (event.room.name == ROOM_NAME) ensureSession(event.room)
    }

    /** Leaving the room keeps the session: the board does not reset when nobody is looking. */
    override fun onRoomExit() {}

    override fun onStateChange(event: DungeonEvent.RoomEvent.onStateChange) {
        if (event.room.name == ROOM_NAME && event.newState == RoomState.GREEN) session?.markSolved()
    }

    override fun onTick() {
        serverTicks ++
        guarded("tick") { session?.tick(serverTicks) }
    }

    override fun onInteract(event: PlayerInteractEvent.RIGHT_CLICK.BLOCK) {
        guarded("click") { session?.onClick(event.pos, serverTicks) }
    }

    fun onBlockChange(event: BlockChangeEvent) {
        guarded("block change") { session?.onBlockChange(event) }
    }

    /** Chunk data replaces whole sections without single block changes: the session reads the board again at once. */
    fun onPacketPost(packet: Packet<*>) {
        val s = session ?: return
        if (packet is ClientboundLevelChunkWithLightPacket) guarded("chunk") { s.onChunkLoaded(packet.x, packet.z) }
    }

    private var errorLogged = false

    /**
     * A bug in the Water Board must not take the other puzzle solvers down with it (they share one tick listener): it is
     * logged once and the solver carries on with the next tick.
     */
    private inline fun guarded(what: String, block: () -> Unit) {
        try {
            block()
        }
        catch (e: Exception) {
            if (! errorLogged) {
                errorLogged = true
                LayBaltQOL.logger.error("[WaterBoard] $what failed", e)
            }
        }
    }

    override fun reset() {
        session = null
    }

    /** "Reset Water" / `/lbq water reset`: forget everything about the room and read the board again. */
    fun resetSession() {
        val room = session?.room ?: ScanUtils.currentRoom?.takeIf { it.name == ROOM_NAME }
        session = null
        room?.let(::ensureSession)
    }

    private fun ensureSession(room: UniqueRoom): WaterRoomSession {
        session?.let { if (it.room === room) return it }
        return WaterRoomSession(room, tables).also { session = it }
    }

    // ================================================================================================================
    // Rendering
    // ================================================================================================================

    override fun onRenderWorld(ctx: RenderContext) = guarded("render") { render(ctx) }

    private fun render(ctx: RenderContext) {
        val s = session ?: return
        val frame = s.frame ?: return
        val g = s.displayed() as? Guidance.Act ?: return
        val fixColor = PuzzleSolvers.waterFixColor.value
        fun pos(lever: WaterLever) = frame.lever(lever).toVec()

        for (label in g.labels) {
            val p = pos(label.lever)
            when (label.style) {
                LabelStyle.SOLUTION -> ctx.renderString(label.text, p.add(0.5, label.stack * 0.5 + 1.5, 0.5), scale = 1.35f, phase = true)
                LabelStyle.FIX -> {
                    val c = p.center()
                    ctx.renderBox(c.x, p.y, c.z, 1.02, 1.02, fixColor, outline = true, fill = false, phase = true)
                    ctx.renderString(label.text, p.add(0.5, 1.1, 0.5), fixColor, scale = 1.35f, phase = true)
                }

                LabelStyle.FIX_NO_BOX -> ctx.renderString(label.text, p.add(0.5, 1.1, 0.5), fixColor, scale = 1.35f, phase = true)
            }
        }
        g.tracer?.let { lever ->
            val fix = g.labels.any { it.lever == lever && it.style == LabelStyle.FIX }
            ctx.renderTracer(pos(lever).center(), if (fix) fixColor else PuzzleSolvers.currentClickColor.value, 2.5f)
        }
        g.line?.let { (a, b) -> ctx.renderLine(pos(a).center(), pos(b).center(), PuzzleSolvers.nextColor.value, 1.5f) }
    }
}
