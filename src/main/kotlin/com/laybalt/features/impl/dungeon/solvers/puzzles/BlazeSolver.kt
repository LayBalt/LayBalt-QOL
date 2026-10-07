// Ported from Odin (https://github.com/odtheking/Odin), BSD-3-Clause, Copyright (c) 2025 odtheking
package com.laybalt.features.impl.dungeon.solvers.puzzles

import com.laybalt.LayBaltQOL.mc
import com.laybalt.event.impl.DungeonEvent
import com.laybalt.features.impl.dungeon.solvers.PuzzleSolvers
import com.laybalt.utils.ChatUtils
import com.laybalt.utils.ChatUtils.unformattedText
import com.laybalt.utils.render.RenderHelper.renderBoundingBox
import com.laybalt.utils.render.RenderHelper.renderVec
import com.laybalt.utils.render.world.Render3D.renderBoxBounds
import com.laybalt.utils.render.world.Render3D.renderLine
import com.laybalt.utils.render.world.RenderContext
import net.minecraft.world.entity.decoration.ArmorStand
import java.awt.Color
import java.util.concurrent.*

/**
 * Higher Or Lower (Blaze) puzzle solver.
 *
 * Every few client ticks the blaze name tags (armor stands) are scanned and sorted by their max HP:
 * descending in "Lower Blaze", ascending in "Higher Blaze". The blazes are boxed in kill order
 * (first / second / third / other colour) and lines connect the next ones.
 */
object BlazeSolver: PuzzleSolver {
    override val enabled get() = PuzzleSolvers.blaze.value

    private val blazeHealthRegex = Regex("^\\[Lv\\d+].*Blaze [\\d,]+/([\\d,]+)❤$")
    private val blazes = CopyOnWriteArrayList<ArmorStand>()

    private var inBlaze = false
    private var roomName = ""
    private var lastBlazeCount = 10
    private var tickCounter = 0

    override fun onRoomEnter(event: DungeonEvent.RoomEvent.onEnter) {
        if (event.room.name != "Lower Blaze" && event.room.name != "Higher Blaze") return
        inBlaze = true
        roomName = event.room.name
        lastBlazeCount = 10
        tickCounter = 0
        scan()
    }


    override fun onClientTick() {
        if (! inBlaze) return
        if (++ tickCounter % 5 != 0) return
        scan()
    }

    private fun scan() {
        val level = mc.level ?: return
        val hpMap = HashMap<ArmorStand, Int>()

        for (entity in level.entitiesForRendering()) {
            if (entity !is ArmorStand || ! entity.isAlive) continue
            val name = entity.customName?.unformattedText ?: continue
            val hp = blazeHealthRegex.find(name)?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull() ?: continue
            hpMap[entity] = hp
        }

        val sorted = if (roomName == "Lower Blaze") hpMap.keys.sortedByDescending { hpMap[it] }
        else hpMap.keys.sortedBy { hpMap[it] }

        blazes.clear()
        blazes.addAll(sorted)
        checkComplete()
    }

    /** Fires once when the last blaze of the room dies. */
    private fun checkComplete() {
        if (blazes.isEmpty() && lastBlazeCount == 1) {
            lastBlazeCount = 0
            PuzzleSolvers.onPuzzleComplete(roomName)
            if (PuzzleSolvers.blazeSendComplete.value) ChatUtils.sendPartyMessage(PuzzleSolvers.blazeCompleteMessage.value)
            return
        }
        if (blazes.isNotEmpty()) lastBlazeCount = blazes.size
    }

    override fun onRenderWorld(ctx: RenderContext) {
        if (! inBlaze || blazes.isEmpty()) return
        val level = mc.level ?: return
        blazes.removeIf { it.isRemoved || level.getEntity(it.id) == null }
        checkComplete()
        if (blazes.isEmpty()) return

        val style = PuzzleSolvers.blazeStyle.value
        val outline = style == 1 || style == 2
        val fill = style == 0 || style == 2
        val phase = PuzzleSolvers.blazePhase.value
        val lineAmount = PuzzleSolvers.blazeLineAmount.value
        val lineWidth = PuzzleSolvers.blazeLineWidth.value

        blazes.forEachIndexed { index, entity ->
            val color = getColor(index)
            val aabb = entity.renderBoundingBox.inflate(0.5, 1.0, 0.5).move(0.0, - 1.0, 0.0)

            ctx.renderBoxBounds(aabb, color, color, outline, fill, phase)

            if (PuzzleSolvers.blazeLineNext.value && index > 0 && index <= lineAmount) {
                val previous = blazes.getOrNull(index - 1) ?: return@forEachIndexed
                ctx.renderLine(previous.renderVec, aabb.center, color, lineWidth, phase)
            }
        }
    }

    private fun getColor(index: Int): Color = when (index) {
        0 -> PuzzleSolvers.blazeFirstColor.value
        1 -> PuzzleSolvers.blazeSecondColor.value
        2 -> PuzzleSolvers.blazeThirdColor.value
        else -> PuzzleSolvers.blazeOtherColor.value
    }

    override fun reset() {
        inBlaze = false
        roomName = ""
        lastBlazeCount = 10
        tickCounter = 0
        blazes.clear()
    }
}
