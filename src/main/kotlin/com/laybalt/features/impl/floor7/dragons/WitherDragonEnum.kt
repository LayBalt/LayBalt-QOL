// Ported from Odin (https://github.com/odtheking/Odin), BSD-3-Clause, Copyright (c) 2025 odtheking
package com.laybalt.features.impl.floor7.dragons

import com.laybalt.LayBaltQOL.mc
import com.laybalt.config.PersonalBest
import com.laybalt.utils.NumbersUtils.toFixed
import com.laybalt.utils.ChatUtils.modMessage
import com.laybalt.utils.MathUtils.aabb
import com.laybalt.utils.MathUtils.vec
import com.laybalt.utils.dungeons.DungeonListener
import gg.essential.universal.ChatColor
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.boss.enderdragon.EnderDragon
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.awt.Color

enum class WitherDragonEnum(
    val spawnPos: Vec3,
    val boxesDimensions: AABB,
    val colorCode: Char,
    val color: Color,
    val displayName: String,
    val xRange: ClosedRange<Double>,
    val zRange: ClosedRange<Double>,
    val skipKillTime: Long = 0,
    val bottomChin: BlockPos,
    var timeToSpawn: Int = 100,
    var state: WitherDragonState = WitherDragonState.DEAD,
    var timesSpawned: Int = 0,
    var entityId: Int? = null,
    var entity: EnderDragon? = null,
    var sprayedTime: Long? = null,
    var spawnedTime: Long = 0,
    var arrowsHit: Int = 0,
    var health: Float = 1_000_000_000f,
    var offScoreboardTicks: Int = 0
) {
    Red(vec(27, 14, 59), aabb(14.5, 13, 45.5, 39.5, 28, 70.5), 'c', ChatColor.RED.color !!, "Power Dragon", 24.0 .. 30.0, 56.0 .. 62.0, 50, BlockPos(32, 19, 59)),
    Orange(vec(85, 14, 56), aabb(72, 8, 47, 102, 28, 77), '6', ChatColor.GOLD.color !!, "Flame Dragon", 82.0 .. 88.0, 53.0 .. 59.0, 62, BlockPos(80, 19, 56)),
    Green(vec(27, 14, 94), aabb(7, 8, 80, 37, 28, 110), 'a', ChatColor.GREEN.color !!, "Apex Dragon", 23.0 .. 29.0, 91.0 .. 97.0, 52, BlockPos(32, 18, 94)),
    Blue(vec(84, 14, 94), aabb(71.5, 16, 82.5, 96.5, 26, 107.5), 'b', ChatColor.AQUA.color !!, "Ice Dragon", 82.0 .. 88.0, 91.0 .. 97.0, 47, BlockPos(79, 19, 94)),
    Purple(vec(56, 14, 125), aabb(45.5, 13, 113.5, 68.5, 23, 136.5), '5', ChatColor.DARK_PURPLE.color !!, "Soul Dragon", 53.0 .. 59.0, 122.0 .. 128.0, 38, BlockPos(56, 18, 128)),
    None(vec(0, 0, 0), aabb(0.0, 0.0, 0.0, 0.0, 0.0, 0.0), 'f', Color.WHITE, "None", 0.0 .. 0.0, 0.0 .. 0.0, 0, BlockPos(- 1, - 1, - 1));

    fun setAlive(id: Int) {
        state = WitherDragonState.ALIVE
        dragonSpawnCount ++

        timeToSpawn = 100
        timesSpawned ++
        entityId = id
        spawnedTime = DungeonListener.currentTime
        sprayedTime = null
        arrowsHit = 0
        offScoreboardTicks = 0

        if (WitherDragons.enabled && WitherDragons.sendSpawned.value)
            modMessage("&${colorCode}${name} &fdragon spawned &8(&7${timesSpawned}&8)")
    }

    fun setDead(silent: Boolean = false, accurate: Boolean = true) {
        if (state == WitherDragonState.DEAD) return

        if (state == WitherDragonState.ALIVE) WitherDragons.lastDragonDeath = this
        state = WitherDragonState.DEAD
        timeToSpawn = 100
        entityId = null
        entity = null

        if (WitherDragons.enabled && ! silent) {
            val stats = mutableListOf<String>()
            if (WitherDragons.sendTime.value) {
                val aliveSeconds = (DungeonListener.currentTime - spawnedTime) / 20.0
                val pbText = if (! accurate) "" else {
                    val key = "M7_dragon_$name"
                    val oldPB = PersonalBest.getPB(key)?.toDouble()
                    when {
                        PersonalBest.checkAndSetPB(key, aliveSeconds) -> " &d&l(PB)"
                        oldPB != null -> " &8(&7${oldPB.toFixed(2)}s&8)"
                        else -> ""
                    }
                }
                stats.add("&7Time: &6${aliveSeconds}s$pbText")
            }
            if (WitherDragons.sendArrowHit.value && this == WitherDragons.priorityDragon) stats.add("&fArrows: &6$arrowsHit")
            if (WitherDragons.sendSpray.value && sprayedTime != null) stats.add("&bSprayed: &c${sprayedTime}t")
            if (stats.isNotEmpty()) modMessage("&${colorCode}${name}: &f${stats.joinToString(" &7| ")}")
        }

        if (WitherDragons.priorityDragon == this) WitherDragons.priorityDragon = None
    }

    fun updateEntity(id: Int, hard: Boolean = false) {
        if (hard) {
            entityId = id
            state = WitherDragonState.ALIVE
        }
        else {
            entity = (mc.level?.getEntity(id) as? EnderDragon) ?: return
        }
        offScoreboardTicks = 0
    }

    companion object {
        var dragonSpawnCount = 0

        fun reset() {
            entries.forEach {
                it.timeToSpawn = 100
                it.timesSpawned = 0
                it.state = WitherDragonState.DEAD
                it.entityId = null
                it.entity = null
                it.sprayedTime = null
                it.spawnedTime = 0
                it.health = 1_000_000_000f
                it.offScoreboardTicks = 0
            }
            dragonSpawnCount = 0
        }
    }
}

