// Ported from Odin (https://github.com/odtheking/Odin), BSD-3-Clause, Copyright (c) 2025 odtheking
package com.laybalt.features.impl.floor7.dragons

import net.minecraft.world.entity.EntityTypes
import com.laybalt.LayBaltQOL.mc
import com.laybalt.utils.ChatUtils
import com.laybalt.utils.ChatUtils.unformattedText
import com.laybalt.utils.MathUtils.xzInAABB
import com.laybalt.utils.ScoreboardUtils
import com.laybalt.utils.dungeons.DungeonListener
import com.laybalt.utils.remove
import gg.essential.universal.USound
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.protocol.game.*
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.item.Items
import net.minecraft.world.phys.Vec3

object DragonCheck {
    private val healthRegex = Regex("\\d+(?:\\.\\d+)?[bBmMkK]")
    private val colorRegex = Regex("§.")

    fun isAliveOnScoreboard(dragon: WitherDragonEnum) = ScoreboardUtils.getLines().any {
        val line = it.unformattedText.remove(colorRegex) // why tf it still have colors???
        line.contains(dragon.displayName, ignoreCase = true).also {
            if (it) ChatUtils.debug("dragon", "tab line for ${dragon.name}: $line")
        } && healthRegex.find(line)?.value != "0"
    }

    fun handleSpawnPacket(particle: ClientboundLevelParticlesPacket) {
        if (particle.particle.type != ParticleTypes.FLAME) return
        if (particle.x % 1 != 0.0) return
        if (particle.z % 1 != 0.0) return
        if (particle.count != 20) return
        if (particle.y != 19.0) return
        if (particle.maxSpeed != 0f) return
        if (! particle.isOverrideLimiter) return
        if (particle.xDist != 2f) return
        if (particle.yDist != 3f) return
        if (particle.zDist != 2f) return

        val dragons = mutableListOf<WitherDragonEnum>()

        WitherDragonEnum.entries.forEach { dragon ->
            if (dragon.state == WitherDragonState.SPAWNING) {
                dragons.add(dragon)
                return@forEach
            }

            if (particle.x in dragon.xRange && particle.z in dragon.zRange) {
                dragon.state = WitherDragonState.SPAWNING
                dragons.add(dragon)
            }
        }

        if (dragons.isNotEmpty()) {
            val priority = DragonPriority.findPriority(dragons)
            WitherDragons.priorityDragon = priority
            announcePriority(priority, dragons)
        }
    }

    /**
     * Odin behaviour: once both dragons of the first split are known (or at least two dragons
     * have spawned already) show the "X is spawning!" title and the priority message, once per spawn.
     */
    private fun announcePriority(priority: WitherDragonEnum, dragons: List<WitherDragonEnum>) {
        if (! WitherDragons.enabled || priority == WitherDragonEnum.None) return
        val spawnedTotal = WitherDragonEnum.entries.sumOf { it.timesSpawned }
        if (dragons.size < 2 && spawnedTotal < 2) return

        val key = priority to priority.timesSpawned
        if (WitherDragons.lastAnnounced == key) return
        WitherDragons.lastAnnounced = key

        if (WitherDragons.dragonTitle.value) {
            ChatUtils.showTitle("&${priority.colorCode}${priority.name} is spawning!")
            USound.playSoundStatic(SoundEvents.NOTE_BLOCK_PLING.value(), 1f, 1f)
        }
        if (WitherDragons.dragonPriorityToggle.value && WitherDragons.priorityMessage.value && dragons.size > 1) ChatUtils.modMessage(
            "${dragons.joinToString("&7, ") { "&${it.colorCode}${it.name}" }} &7-> &${priority.colorCode}${priority.name} &7is your priority dragon!"
        )
    }

    fun dragonUpdate(packet: ClientboundSetEntityDataPacket) {
        val dragon = WitherDragonEnum.entries.find { it.entityId == packet.id }?.apply {
            if (entity == null || entity?.isAlive != true) updateEntity(packet.id)
        } ?: return

        dragon.health = (packet.packedItems.find { it.id == 9 }?.value as? Float) ?: return

        if (dragon.health <= 0 && dragon.state != WitherDragonState.DEAD) {
            dragon.setDead()
        }
    }

    fun dragonSpawn(packet: ClientboundAddEntityPacket) {
        if (packet.type != EntityTypes.ENDER_DRAGON) return
        val spawnVec = Vec3(packet.x, packet.y, packet.z)
        val newId = packet.id

        WitherDragonEnum.entries.find { dragon ->
            spawnVec.xzInAABB(dragon.boxesDimensions) && dragon.state == WitherDragonState.SPAWNING
        }?.setAlive(newId) ?: WitherDragonEnum.entries.find { dragon ->
            dragon.state == WitherDragonState.ALIVE && dragon.entity == null && dragon.entityId == newId
        }?.updateEntity(newId, hard = true)
    }

    fun dragonSprayed(packet: ClientboundSetEquipmentPacket) {
        if (packet.slots.none { it.second.item == Items.PACKED_ICE }) return
        val sprayedEntity = mc.level?.getEntity(packet.entity) as? ArmorStand ?: return

        WitherDragonEnum.entries.forEach { dragon ->
            val entity = dragon.entity
            if (dragon.sprayedTime != null || dragon.state != WitherDragonState.ALIVE || entity == null || sprayedEntity.distanceTo(entity) > 8) return@forEach
            dragon.sprayedTime = DungeonListener.currentTime - dragon.spawnedTime
        }
    }

    fun trackArrows(packet: ClientboundSoundPacket) {
        if (packet.sound.value() != SoundEvents.ARROW_HIT_PLAYER) return
        WitherDragons.priorityDragon.takeUnless { it == WitherDragonEnum.None }?.let {
            if (it.state == WitherDragonState.ALIVE && DungeonListener.currentTime - it.spawnedTime <= it.skipKillTime) {
                it.arrowsHit ++
            }
        }
    }
}