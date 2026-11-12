// Ported from Odin (https://github.com/odtheking/Odin), BSD-3-Clause, Copyright (c) 2025 odtheking
package com.laybalt.features.impl.dungeon.solvers.puzzles

import com.laybalt.LayBaltQOL.mc
import com.laybalt.event.impl.DungeonEvent
import com.laybalt.features.impl.dungeon.solvers.PuzzleSolvers
import com.laybalt.utils.ChatUtils
import com.laybalt.utils.ChatUtils.unformattedText
import com.laybalt.utils.dungeons.map.utils.ScanUtils
import com.laybalt.utils.location.LocationUtils
import com.laybalt.utils.render.RenderHelper.renderBoundingBox
import com.laybalt.utils.render.RenderHelper.renderVec
import com.laybalt.utils.render.world.Render3D.renderBoxBounds
import com.laybalt.utils.render.world.Render3D.renderLine
import com.laybalt.utils.render.world.RenderContext
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.monster.Blaze
import java.awt.Color
import java.util.concurrent.*

/**
 * Higher Or Lower (Blaze) puzzle solver.
 *
 * Odin 1acdc46d: the blaze name tags (armor stands) are picked up the moment their name arrives (entity data packet)
 * anywhere in the dungeon clear, instead of scanning the world every few ticks. They are sorted by their max HP:
 * descending in "Lower Blaze", ascending in "Higher Blaze". The blazes are boxed in kill order
 * (first / second / third / other colour) and lines connect the next ones. The puzzle is complete when the death
 * event of the last tracked blaze arrives (its name tag is the entity right after it).
 *
 * Odin 9ce2c78: the tags are keyed by their max HP (every blaze of the puzzle has its own), so a tag sent again (its
 * data again, or a new entity for the same blaze) replaces the old one instead of adding a second box, and no alive
 * filter drops a tag whose data came first.
 */
object BlazeSolver: PuzzleSolver {
    override val enabled get() = PuzzleSolvers.blaze.value

    private val blazeHealthRegex = Regex("^\\[Lv\\d+].*Blaze [\\d,]+/([\\d,]+)❤$")
    /** Max HP to its name tag, ascending (packets and rendering both run on the client thread; a concurrent map all the same). */
    private val blazes = ConcurrentSkipListMap<Int, ArmorStand>()

    private val inClear get() = LocationUtils.inDungeon && ! LocationUtils.inBoss
    private val currentRoomName get() = ScanUtils.currentRoom?.name

    val inBlazeRoom get() = currentRoomName.let { it == "Lower Blaze" || it == "Higher Blaze" }

    /** Odin b412ad1c: a tracked blaze name tag, hidden together with the blazes. */
    fun isBlazeTag(entity: Entity) = entity is ArmorStand && inBlazeRoom && blazes.values.any { it.id == entity.id }

    override fun onRoomEnter(event: DungeonEvent.RoomEvent.onEnter) {
        val name = event.room.name
        if (name != "Lower Blaze" && name != "Higher Blaze") return
        // Tags sent before the dungeon was detected (or after a manual reset) are picked up here once.
        mc.level?.entitiesForRendering()?.forEach { if (it is ArmorStand) track(it) }
    }

    // The tags are tracked for the whole dungeon clear (they are only sent once), so leaving the room keeps them.
    override fun onRoomExit() {}

    /** Called after a packet was applied, so an entity data packet has already set the armor stand's name. */
    fun onPacketPost(packet: Packet<*>) {
        when (packet) {
            is ClientboundSetEntityDataPacket -> onEntityData(packet)
            is ClientboundEntityEventPacket -> onEntityEvent(packet)
        }
    }

    private fun onEntityData(packet: ClientboundSetEntityDataPacket) {
        if (! inClear) return
        val level = mc.level ?: return
        val entity = level.getEntity(packet.id()) as? ArmorStand ?: return
        track(entity)
    }

    private fun track(entity: ArmorStand) {
        val name = entity.customName?.unformattedText?.takeIf { it.endsWith('❤') } ?: return
        val maxHealth = blazeHealthRegex.find(name)?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull() ?: return
        if (blazes[maxHealth]?.id == entity.id) return
        blazes.values.removeIf { it.id == entity.id }
        blazes[maxHealth] = entity
    }

    private fun onEntityEvent(packet: ClientboundEntityEventPacket) {
        if (packet.eventId != 3.toByte()) return
        val level = mc.level ?: return
        val blaze = packet.getEntity(level) as? Blaze ?: return
        val removed = blazes.values.removeIf { it.id == blaze.id + 1 }
        if (! removed || blazes.isNotEmpty() || ! inBlazeRoom) return
        PuzzleSolvers.onPuzzleComplete(currentRoomName ?: return)
        if (PuzzleSolvers.blazeSendComplete.value) ChatUtils.sendPartyMessage(PuzzleSolvers.blazeCompleteMessage.value)
    }

    fun onEntityRemove(entity: Entity) {
        if (entity !is ArmorStand) return
        blazes.values.removeIf { it.id == entity.id }
    }

    override fun onRenderWorld(ctx: RenderContext) {
        if (blazes.isEmpty() || ! inBlazeRoom) return

        val style = PuzzleSolvers.blazeStyle.value
        val outline = style == 1 || style == 2
        val fill = style == 0 || style == 2
        val phase = PuzzleSolvers.blazePhase.value
        val lineAmount = PuzzleSolvers.blazeLineAmount.value
        val lineWidth = PuzzleSolvers.blazeLineWidth.value

        // kill order: highest HP first in "Lower Blaze", lowest first in "Higher Blaze"
        val ordered = if (currentRoomName == "Lower Blaze") blazes.descendingMap().values else blazes.values
        var previous: ArmorStand? = null
        ordered.forEachIndexed { index, stand ->
            val color = getColor(index)
            val aabb = stand.renderBoundingBox.inflate(0.5, 1.0, 0.5).move(0.0, - 1.0, 0.0)

            ctx.renderBoxBounds(aabb, color, color, outline, fill, phase)

            if (PuzzleSolvers.blazeLineNext.value && index <= lineAmount) previous?.let {
                ctx.renderLine(it.renderVec, aabb.center, color, lineWidth, phase)
            }
            previous = stand
        }
    }

    private fun getColor(index: Int): Color = when (index) {
        0 -> PuzzleSolvers.blazeFirstColor.value
        1 -> PuzzleSolvers.blazeSecondColor.value
        2 -> PuzzleSolvers.blazeThirdColor.value
        else -> PuzzleSolvers.blazeOtherColor.value
    }

    override fun reset() {
        blazes.clear()
    }
}
