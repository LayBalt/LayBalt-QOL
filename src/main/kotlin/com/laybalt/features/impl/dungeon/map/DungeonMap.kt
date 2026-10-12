package com.laybalt.features.impl.dungeon.map

import com.laybalt.event.impl.*
import com.laybalt.features.Feature
import com.laybalt.utils.WorldUtils
import com.laybalt.utils.dungeons.enums.SecretType
import com.laybalt.utils.dungeons.map.handlers.*
import com.laybalt.utils.location.LocationUtils
import com.laybalt.utils.render.world.Render3D.renderBlock
import net.minecraft.world.level.block.Blocks

object DungeonMap: Feature() {
    override fun init() {
        configSettings.addAll(MapConfig.configSettings)
        hudElements.add(MapRenderer)

        register<RenderWorldEvent> {
            if (! enabled || ! LocationUtils.inDungeon || LocationUtils.inBoss) return@register

            //#if CHEAT
            val mimicRoom = DungeonScanner.mimicRoom
            if (MapConfig.mimicEsp.value && ! ScoreCalculation.mimicKilled && mimicRoom != null) {
                for (chestPos in mimicRoom.trappedChestPositions) {
                    if (! WorldUtils.getStateAt(chestPos).`is`(Blocks.TRAPPED_CHEST)) continue
                    if (mimicRoom.secretCoords[SecretType.CHEST]?.any { it == chestPos } != true) continue
                    event.ctx.renderBlock(chestPos, MapConfig.mimicEspColor.value, phase = true)
                }
            }
            //#endif
        }
    }
}