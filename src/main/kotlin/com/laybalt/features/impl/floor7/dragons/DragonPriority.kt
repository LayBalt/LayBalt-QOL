// Ported from Odin (https://github.com/odtheking/Odin), BSD-3-Clause, Copyright (c) 2025 odtheking
package com.laybalt.features.impl.floor7.dragons

import com.laybalt.utils.ChatUtils
import com.laybalt.utils.dungeons.DungeonListener
import com.laybalt.utils.dungeons.enums.Blessing
import com.laybalt.utils.dungeons.enums.DungeonClass
import com.laybalt.utils.equalsOneOf

object DragonPriority {
    /** Set once the "failed to get class" warning was shown; cleared on world change. */
    var warnedClass = false

    fun findPriority(spawningDragons: MutableList<WitherDragonEnum>): WitherDragonEnum {
        return if (! WitherDragons.dragonPriorityToggle.value) {
            spawningDragons.sortBy { listOf(WitherDragonEnum.Red, WitherDragonEnum.Orange, WitherDragonEnum.Blue, WitherDragonEnum.Purple, WitherDragonEnum.Green).indexOf(it) }
            spawningDragons[0]
        }
        else sortPriority(spawningDragons)
    }

    private fun sortPriority(spawningDragons: MutableList<WitherDragonEnum>): WitherDragonEnum {
        val totalPower = Blessing.POWER.current * (if (WitherDragons.paulBuff.value) 1.25 else 1.0) + (if (Blessing.TIME.current > 0) 2.5 else 0.0)
        val playerClass = DungeonListener.thePlayer?.clazz
        if ((playerClass == null || playerClass == DungeonClass.Empty) && ! warnedClass) ChatUtils.modMessage("&cFailed to get your dungeon class for dragon priority.").also { warnedClass = true }

        val dragonList = listOf(WitherDragonEnum.Orange, WitherDragonEnum.Green, WitherDragonEnum.Red, WitherDragonEnum.Blue, WitherDragonEnum.Purple)
        val priorityList =
            if (totalPower >= WitherDragons.normalPower.value || (spawningDragons.any { it == WitherDragonEnum.Purple } && totalPower >= WitherDragons.easyPower.value))
                if (playerClass.equalsOneOf(DungeonClass.Berserk, DungeonClass.Mage)) dragonList else dragonList.reversed()
            else listOf(WitherDragonEnum.Red, WitherDragonEnum.Orange, WitherDragonEnum.Blue, WitherDragonEnum.Purple, WitherDragonEnum.Green)

        spawningDragons.sortBy { priorityList.indexOf(it) }

        if (totalPower >= WitherDragons.easyPower.value) {
            if (WitherDragons.soloDebuff.value == 1 && playerClass == DungeonClass.Tank && (spawningDragons.any { it == WitherDragonEnum.Purple } || WitherDragons.soloDebuffOnAll.value)) spawningDragons.sortByDescending {
                priorityList.indexOf(it)
            }
            else if (playerClass == DungeonClass.Healer && (spawningDragons.any { it == WitherDragonEnum.Purple } || WitherDragons.soloDebuffOnAll.value)) spawningDragons.sortByDescending {
                priorityList.indexOf(it)
            }
        }

        ChatUtils.debug("dragon", "&7Priority: &6$totalPower &7Class: &f${playerClass?.name} &7Dragons: &a${spawningDragons.joinToString(", ") { it.name }} &7-> &c${priorityList.joinToString(", ") { it.name.first().toString() }}")

        return spawningDragons[0]
    }
}