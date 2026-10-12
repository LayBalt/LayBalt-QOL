package com.laybalt.features.impl.dungeon.solvers.puzzles

import com.laybalt.event.impl.DungeonEvent
import com.laybalt.event.impl.PlayerInteractEvent
import com.laybalt.features.impl.dungeon.solvers.PuzzleSolvers
import com.laybalt.features.impl.dungeon.solvers.PuzzleSolvers.currentClickColor
import com.laybalt.features.impl.dungeon.solvers.PuzzleSolvers.nextColor
import com.laybalt.features.impl.dungeon.solvers.puzzles.WaterBoardTracker.Phase
import com.laybalt.features.impl.dungeon.solvers.puzzles.water.WaterLever
import com.laybalt.init.DataDownloader
import com.laybalt.utils.ChatUtils
import com.laybalt.utils.MathUtils.center
import com.laybalt.utils.MathUtils.toPos
import com.laybalt.utils.MathUtils.toVec
import com.laybalt.utils.NumbersUtils.toFixed
import com.laybalt.utils.ThreadUtils
import com.laybalt.utils.WorldUtils
import com.laybalt.utils.dungeons.map.utils.ScanUtils
import com.laybalt.utils.render.world.Render3D.renderBox
import com.laybalt.utils.render.world.Render3D.renderLine
import com.laybalt.utils.render.world.Render3D.renderString
import com.laybalt.utils.render.world.Render3D.renderTracer
import com.laybalt.utils.render.world.RenderContext
import net.minecraft.core.BlockPos
import net.minecraft.tags.BlockTags
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.ChestBlock
import net.minecraft.world.phys.Vec3
import java.awt.Color

object WaterBoardSolver: PuzzleSolver {
    override val enabled get() = PuzzleSolvers.water.value

    private val waterSolutions by lazy {
        DataDownloader.loadJson<Map<String, Map<String, Map<String, List<Double>>>>>("waterSolutions.json")
    }

    private var solution = HashMap<LEVER, List<Double>>()
    private var patternId = - 1

    /** Follows the solution, detects failed runs and drives the recovery guidance (pure, unit tested). */
    @Volatile private var tracker: WaterBoardTracker? = null

    /** Server ticks counted by this solver (TickEvent.Server): the time base of [tracker] and the countdowns. */
    @Volatile private var serverTicks = 0L

    /** Plain click counting of the original solver, used while recovery guidance is disabled. */
    private val legacyClicks = IntArray(LEVER.entries.size)
    private var legacyWaterTick = - 1L

    /** The gates that were closed when the puzzle was solved; all of them open means the puzzle is done. */
    private var closedGates = emptyList<Gate>()

    private var center: BlockPos? = null
    private var rotation: Int? = null

    private val recovery get() = PuzzleSolvers.waterRecovery.value
    private val recoveryPhases = setOf(Phase.WATER_ON_FAILED, Phase.DRAINING, Phase.RESTORE)

    /** Short status line for the "Water Board Recovery" HUD element, null when there is nothing to fix. */
    val hudText: String?
        get() {
            if (! enabled || ! recovery || patternId == - 1) return null
            val tracker = tracker ?: return null
            return when (tracker.phase) {
                Phase.WAITING -> tracker.preStartFixes().takeIf { it.isNotEmpty() }?.let {
                    "§dFlip back ${names(it)} §7before pulling the water"
                }

                Phase.WATER_ON_FAILED -> "§cWater Board failed §7- §fturn the water §cOFF"
                Phase.DRAINING -> "§eDraining…" + (tracker.drainEta?.let { " §7~§f${(it / 20.0).toFixed(1)}s" } ?: "")
                Phase.RESTORE -> tracker.restoreFixes().takeIf { it.isNotEmpty() }
                    ?.let { "§dFlip ${names(it)} §7to reset the board" }
                    ?: "§aPull the water lever to restart"

                Phase.RUNNING, Phase.SOLVED -> null
            }
        }

    override fun onRoomEnter(event: DungeonEvent.RoomEvent.onEnter) {
        if (event.room.name != "Water Board") return
        if (patternId != - 1) return

        center = event.room.centerPos
        rotation = 360 - (event.room.rotation ?: return)

        ThreadUtils.loop(500, { patternId != - 1 }) { solve() }
    }

    override fun onTick() {
        serverTicks ++
        val tracker = tracker ?: return
        tracker.toleranceTicks = (PuzzleSolvers.waterTolerance.value.toDouble() * 20).toLong()
        val waterBlocks = if (tracker.phase == Phase.DRAINING) countBoardWater() else null
        tracker.onTick(serverTicks, waterBlocks)?.let(::announce)

        if (tracker.phase in recoveryPhases && closedGates.isNotEmpty() && closedGates.none { it.isGateClosed() }) {
            tracker.markSolved()
        }
    }

    override fun onRenderWorld(ctx: RenderContext) {
        if (patternId == - 1 || solution.isEmpty()) return
        val tracker = tracker ?: return

        if (! recovery) {
            renderSolution(ctx, { legacyClicks[it.ordinal] }, legacyWaterTick, tracers = true)
            return
        }

        if (tracker.followsSolution) {
            renderSolution(
                ctx,
                { tracker.consumed(it.lever) },
                if (tracker.phase == Phase.RUNNING) tracker.startTick else - 1L,
                tracers = tracker.preStartFixes().isEmpty()
            )
        }
        renderRecovery(ctx, tracker)
    }

    /** The solver's normal display: tracer to the next lever and a countdown above every lever. */
    private fun renderSolution(ctx: RenderContext, done: (LEVER) -> Int, waterTick: Long, tracers: Boolean) {
        val solution = solution.toMap()

        val clicks = solution
            .flatMap { (lever, times) -> times.drop(done(lever)).map { lever to it } }
            .sortedBy { (lever, time) -> time + if (lever == LEVER.WATER) 0.01 else 0.0 }

        val nextClick = clicks.firstOrNull()?.first ?: return

        if (tracers) {
            ctx.renderTracer(nextClick.getPos().center(), currentClickColor.value, 2.5f)

            if (clicks.size > 1) {
                val secondClick = clicks[1].first
                if (nextClick != secondClick) ctx.renderLine(
                    nextClick.getPos().center(),
                    secondClick.getPos().center(),
                    nextColor.value,
                    1.5f
                )
            }
        }

        solution.forEach { (mech, times) ->
            val skip = done(mech)
            times.drop(skip).forEachIndexed { index, timeSeconds ->
                val timeInTicks = (timeSeconds * 20).toInt()

                val displayText = when (waterTick) {
                    - 1L if timeInTicks == 0 -> "§a§lCLICK"
                    - 1L -> {
                        val color = if (timeSeconds < 2) "§c" else if (timeSeconds < 6) "§e" else "§a"
                        "$color${timeSeconds}s"
                    }

                    else -> {
                        val remainingTicks = waterTick + timeInTicks - serverTicks
                        if (remainingTicks > 0) {
                            val remainingSec = remainingTicks / 20.0
                            val color = if (remainingSec < 2) "§c" else if (remainingSec < 6) "§e" else "§a"
                            "$color${remainingSec.toFixed(1)}s"
                        }
                        else "§a§lCLICK"
                    }
                }

                val renderPos = mech.getPos().add(0.5, (index + skip) * 0.5 + 1.5, 0.5)
                ctx.renderString(displayText, renderPos, scale = 1.35f, phase = true)
            }
        }
    }

    /** Recovery markers in the fix colour: what to flip, turn off or pull to get the run back on track. */
    private fun renderRecovery(ctx: RenderContext, tracker: WaterBoardTracker) {
        val fixColor = PuzzleSolvers.waterFixColor.value
        when (tracker.phase) {
            Phase.WAITING -> tracker.preStartFixes().forEachIndexed { i, lever ->
                markLever(ctx, LEVER.of(lever), "§lFLIP BACK", fixColor, tracer = i == 0)
            }

            Phase.WATER_ON_FAILED -> markLever(ctx, LEVER.WATER, "§lTURN WATER OFF", fixColor, tracer = true)
            Phase.DRAINING -> {
                val eta = tracker.drainEta?.let { " ~${(it / 20.0).toFixed(1)}s" } ?: ""
                markLever(ctx, LEVER.WATER, "§lDRAINING…$eta", fixColor, tracer = false, box = false)
            }

            Phase.RESTORE -> {
                val fixes = tracker.restoreFixes()
                if (fixes.isEmpty()) markLever(ctx, LEVER.WATER, "§lPULL TO RESTART", fixColor, tracer = true)
                else fixes.forEachIndexed { i, lever -> markLever(ctx, LEVER.of(lever), "§lFLIP", fixColor, tracer = i == 0) }
            }

            Phase.RUNNING, Phase.SOLVED -> {}
        }
    }

    private fun markLever(ctx: RenderContext, lever: LEVER, label: String, color: Color, tracer: Boolean, box: Boolean = true) {
        val pos = lever.getPos()
        val center = pos.center()
        if (box) ctx.renderBox(center.x, pos.y, center.z, 1.02, 1.02, color, outline = true, fill = false, phase = true)
        ctx.renderString(label, pos.add(0.5, 1.1, 0.5), color, scale = 1.35f, phase = true)
        if (tracer) ctx.renderTracer(center, color, 2.5f)
    }

    override fun onInteract(event: PlayerInteractEvent.RIGHT_CLICK.BLOCK) {
        if (patternId == - 1) return
        if (solution.isEmpty()) return
        val block = WorldUtils.getBlockAt(event.pos)

        LEVER.entries.find { it.getPos().toPos() == event.pos }?.let { mech ->
            if (mech == LEVER.WATER && legacyWaterTick == - 1L) legacyWaterTick = serverTicks
            legacyClicks[mech.ordinal] ++
            tracker?.onClick(mech.lever, serverTicks)?.let(::announce)
        }

        if (block is ChestBlock) {
            if (Gate.entries.any { it.isGateClosed() }) return
            reset()
        }
    }

    private fun announce(failure: WaterBoardTracker.Failure) {
        if (! recovery || ! PuzzleSolvers.waterFailMessage.value) return
        val offset = if (failure.offsetTicks > 0) {
            val direction = if (failure.cause == WaterBoardTracker.Cause.TOO_EARLY) "early" else "late"
            " §7(${(failure.offsetTicks / 20.0).toFixed(1)}s $direction)"
        }
        else ""
        val steps = if (tracker?.phase == Phase.WATER_ON_FAILED) "turn the water §cOFF§7, wait for the board to drain"
        else "wait for the board to drain"
        ChatUtils.modMessage(
            "§cWater Board failed: §fyou ${failure.cause.text}: §d${failure.lever.displayName}$offset§7. " +
                "To fix it: $steps, flip the §dmarked §7levers and pull the water lever again."
        )
    }

    private fun solve() {
        val center = center ?: return
        val rot = rotation ?: return

        val gates = Gate.entries.joinToString("") {
            if (it.isGateClosed(center, rot)) it.ordinal.toString() else ""
        }

        if (gates.length != 3) return

        patternId = when {
            checkBlock(BlockPos(- 1, 77, 12), Blocks.TERRACOTTA) -> 0
            checkBlock(BlockPos(1, 78, 12), Blocks.EMERALD_BLOCK) -> 1
            checkBlock(BlockPos(- 1, 78, 12), Blocks.DIAMOND_BLOCK) -> 2
            checkBlock(BlockPos(- 1, 78, 12), Blocks.QUARTZ_BLOCK) -> 3
            else -> throw Error("§cWater Solver: Unknown Pattern.")
        }

        val newSolution = HashMap<LEVER, List<Double>>()
        waterSolutions["$patternId"]?.get(gates)?.forEach { (key, times) ->
            LEVER.fromKey(key)?.let {
                newSolution[it] = times
            }
        }
        closedGates = Gate.entries.filter { it.ordinal.toString() in gates }
        tracker = WaterBoardTracker(newSolution.mapKeys { it.key.lever })
        solution = newSolution
    }

    override fun reset() {
        legacyClicks.fill(0)
        legacyWaterTick = - 1
        patternId = - 1
        solution = HashMap()
        tracker = null
        closedGates = emptyList()
        center = null
        rotation = null
    }

    private fun names(levers: List<WaterLever>) = levers.joinToString("§7, §d") { it.displayName }

    /** Water blocks on the board (the wall the water runs down), or null when the room is unknown. */
    private fun countBoardWater(): Int? {
        val center = center ?: return null
        val rot = rotation ?: return null
        var count = 0
        for (x in BOARD_MIN_X .. BOARD_MAX_X) for (y in BOARD_MIN_Y .. BOARD_MAX_Y) {
            val pos = ScanUtils.getRealCoord(BlockPos(x, y, BOARD_Z), center, rot)
            if (WorldUtils.getStateAt(pos).`is`(Blocks.WATER)) count ++
        }
        return count
    }

    private fun checkBlock(rel: BlockPos, expected: Block): Boolean {
        val center = center ?: return false
        val rot = rotation ?: return false
        val realPos = ScanUtils.getRealCoord(rel, center, rot)
        return WorldUtils.getBlockAt(realPos) == expected
    }

    // Board bounds relative to the room centre (Odin's room-corner coordinates minus 15 on x and z).
    private const val BOARD_MIN_X = - 9
    private const val BOARD_MAX_X = 9
    private const val BOARD_MIN_Y = 58
    private const val BOARD_MAX_Y = 81
    private const val BOARD_Z = 11

    private enum class Gate(val offset: BlockPos) {
        PURPLE(BlockPos(0, 56, 4)),
        ORANGE(BlockPos(0, 56, 3)),
        BLUE(BlockPos(0, 56, 2)),
        GREEN(BlockPos(0, 56, 1)),
        RED(BlockPos(0, 56, 0));

        fun isGateClosed(center: BlockPos, rotation: Int): Boolean {
            val pos = ScanUtils.getRealCoord(offset, center, rotation)
            val block = WorldUtils.getBlockAt(pos)
            return block.defaultBlockState().`is`(BlockTags.WOOL)
        }

        fun isGateClosed(): Boolean = isGateClosed(center ?: return false, rotation ?: return false)
    }

    private enum class LEVER(val offset: BlockPos, val lever: WaterLever) {
        QUARTZ(BlockPos(5, 61, 5), WaterLever.QUARTZ),
        GOLD(BlockPos(5, 61, 0), WaterLever.GOLD),
        COAL(BlockPos(5, 61, - 5), WaterLever.COAL),
        DIAMOND(BlockPos(- 5, 61, 5), WaterLever.DIAMOND),
        EMERALD(BlockPos(- 5, 61, 0), WaterLever.EMERALD),
        CLAY(BlockPos(- 5, 61, - 5), WaterLever.CLAY),
        WATER(BlockPos(0, 60, - 10), WaterLever.WATER);

        fun getPos(): Vec3 {
            val center = center ?: return Vec3.ZERO
            val rot = rotation ?: return Vec3.ZERO
            return ScanUtils.getRealCoord(offset, center, rot).toVec()
        }

        companion object {
            fun fromKey(str: String) = WaterLever.fromKey(str)?.let(::of)
            fun of(lever: WaterLever) = entries.first { it.lever == lever }
        }
    }
}
