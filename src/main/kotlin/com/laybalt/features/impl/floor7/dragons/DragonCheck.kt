// Ported from Odin (https://github.com/odtheking/Odin), BSD-3-Clause, Copyright (c) 2025 odtheking
package com.laybalt.features.impl.floor7.dragons

import net.minecraft.world.entity.EntityTypes
import com.laybalt.LayBaltQOL.mc
import com.laybalt.utils.ChatUtils
import com.laybalt.utils.ChatUtils.unformattedText
import com.laybalt.utils.MathUtils.xzInAABB
import com.laybalt.utils.ScoreboardUtils
import com.laybalt.utils.dungeons.DungeonListener
import com.laybalt.utils.dungeons.enums.DungeonClass
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

        // Odin 1cf5805/73d9134 + NoammAddons a5fd05a7: since the dungeon update the dragons of a split no longer spawn
        // together, so the order in which they start spawning decides the priority. A dragon that is already spawning
        // never restarts its timer; one we still think is alive (missed death) does start spawning again.
        val dragon = WitherDragonEnum.entries.find {
            it.state != WitherDragonState.SPAWNING && particle.x in it.xRange && particle.z in it.zRange
        } ?: return

        dragon.state = WitherDragonState.SPAWNING
        dragon.timeToSpawn = 100
        WitherDragons.spawnOrder.remove(dragon)
        WitherDragons.spawnOrder.add(dragon)

        val priority = findPriority() ?: return
        if (priority == WitherDragons.priorityDragon) return
        WitherDragons.priorityDragon = priority
        if (priority.state == WitherDragonState.SPAWNING) announcePriority(priority)
    }

    /**
     * The dragon to go for: the one that started spawning first (NoammAddons a5fd05a7, Odin 1cf5805). With Dragon
     * Priority on, the Solo Priority class takes the dragon that started spawning last instead, either only on the
     * first split or on every split (NoammAddons 4244f12e).
     */
    fun findPriority(): WitherDragonEnum? {
        val order = WitherDragons.spawnOrder
        val first = order.firstOrNull() ?: return null
        if (! WitherDragons.dragPrio.value) return first
        if (WitherDragons.firstDragonOnly.value && WitherDragonEnum.dragonSpawnCount >= 2) return first

        val playerClass = DungeonListener.thePlayer?.clazz
        if ((playerClass == null || playerClass == DungeonClass.Empty) && ! WitherDragons.warnedClass) {
            WitherDragons.warnedClass = true
            ChatUtils.modMessage("&cFailed to get your dungeon class for dragon priority.")
        }
        val soloClass = if (WitherDragons.soloPriority.value == 0) DungeonClass.Healer else DungeonClass.Tank
        return if (playerClass == soloClass) order.last() else first
    }

    /** "X is spawning!" title and, on a split with Dragon Priority on, the priority message. */
    private fun announcePriority(priority: WitherDragonEnum) {
        if (! WitherDragons.enabled) return

        if (WitherDragons.dragonTitle.value) {
            ChatUtils.showTitle("&${priority.colorCode}${priority.name} is spawning!")
            USound.playSoundStatic(SoundEvents.NOTE_BLOCK_PLING.value(), 1f, 1f)
        }
        val dragons = WitherDragons.spawnOrder
        if (WitherDragons.dragPrio.value && WitherDragons.priorityMessage.value && dragons.size > 1) ChatUtils.modMessage(
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
        val priorityDragon = WitherDragons.priorityDragon ?: return
        if (priorityDragon.state != WitherDragonState.ALIVE) return
        if (DungeonListener.currentTime - priorityDragon.spawnedTime <= priorityDragon.skipKillTime) {
            priorityDragon.arrowsHit ++
        }
    }
}