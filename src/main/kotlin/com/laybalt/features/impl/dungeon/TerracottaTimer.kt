package com.laybalt.features.impl.dungeon

import com.laybalt.event.impl.BlockChangeEvent
import com.laybalt.event.impl.ChatMessageEvent
import com.laybalt.event.impl.RenderWorldEvent
import com.laybalt.event.impl.WorldChangeEvent
import com.laybalt.features.Feature
import com.laybalt.utils.NumbersUtils.toFixed
import com.laybalt.utils.ThreadUtils
import com.laybalt.utils.dungeons.DungeonListener
import com.laybalt.utils.location.LocationUtils
import com.laybalt.utils.render.world.Render3D.renderString
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.FlowerPotBlock
import net.minecraft.world.phys.Vec3
import java.util.concurrent.*

object TerracottaTimer: Feature("Displays a timer until Terracottas respawn in F6/M6") {
    private var terracottaSpawns = CopyOnWriteArrayList<Pair<BlockPos, Long>>()

    override fun init() {
        register<WorldChangeEvent> { terracottaSpawns.clear() }

        register<BlockChangeEvent> {
            if (LocationUtils.dungeonFloorNumber != 6 || ! LocationUtils.inBoss) return@register
            if (event.newBlock !is FlowerPotBlock) return@register
            if (terracottaSpawns.any { it.first == event.pos }) return@register
            val time = if (LocationUtils.isMasterMode) 240 else 300
            val terracotta = Pair(event.pos, DungeonListener.currentTime + time)
            ThreadUtils.scheduledTaskServer(time) { terracottaSpawns.remove(terracotta) }
            terracottaSpawns.add(terracotta)
        }

        register<ChatMessageEvent> {
            if (LocationUtils.dungeonFloorNumber != 6 || ! LocationUtils.inBoss) return@register
            if (event.unformattedText == "[BOSS] Sadan: ENOUGH!") ThreadUtils.scheduledTaskServer(10) {
                terracottaSpawns.clear()
            }
        }

        register<RenderWorldEvent> {
            terracottaSpawns.ifEmpty { return@register }.forEach { (pos, time) ->
                val timeLeft = (time - DungeonListener.currentTime) / 20.0
                event.ctx.renderString(timeLeft.toFixed(1), Vec3(pos).add(0.5, 0.5, 0.5), scale = 1.35, phase = true)
            }
        }
    }
}