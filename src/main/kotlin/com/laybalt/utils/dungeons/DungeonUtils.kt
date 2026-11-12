// Parts of this file are ported from Odin (https://github.com/odtheking/Odin), BSD-3-Clause, Copyright (c) 2025 odtheking
package com.laybalt.utils.dungeons

import com.laybalt.LayBaltQOL.mc
import com.laybalt.features.impl.dungeon.ScoreCalculator
import com.laybalt.init.NetworkLoop.electionData
import com.laybalt.utils.equalsOneOf
import com.laybalt.utils.network.data.ElectionData
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.SkullBlock
import net.minecraft.world.level.block.entity.SkullBlockEntity

object DungeonUtils {
    val FLOOR_NAMES = listOf("ENTRANCE", "ONE", "TWO", "THREE", "FOUR", "FIVE", "SIX", "SEVEN")
    val floorEnterRegex = Regex("""-+\s.+ entered.+The Catacombs, (?:Floor [IVX]+|Entrance)!\s-+""")
    val WITHER_ESSENCE = setOf("2865274b-3097-394e-8149-ec629c72d850", "e0f3e929-869e-3dca-9504-54c666ee6f23").toTypedArray()
    val REDSTONE_KEY = setOf("fed95410-aba1-39df-9b95-1d4f361eb66e").toTypedArray()

    @JvmStatic
    fun isSecret(pos: BlockPos): Boolean {
        val block = mc.level?.getBlockState(pos)?.block ?: Blocks.AIR

        return when {
            block is SkullBlock -> (mc.level?.getBlockEntity(pos) as? SkullBlockEntity)?.ownerProfile?.partialProfile()?.id.toString().equalsOneOf(*WITHER_ESSENCE, *REDSTONE_KEY)
            block.equalsOneOf(Blocks.CHEST, Blocks.TRAPPED_CHEST, Blocks.LEVER) -> true
            else -> false
        }
    }

    /**
     * Odin ffcea4cf: runtime Paul bonus override set by `/lbq paul <auto|on|off>`.
     * null = auto (Force Paul toggle, then the election data), true/false = forced. Not persisted.
     */
    var paulOverride: Boolean? = null

    fun setPaulOverride(mode: String): String? {
        paulOverride = when (mode.lowercase()) {
            "auto" -> null
            "on" -> true
            "off" -> false
            else -> return null
        }
        return when (paulOverride) {
            null -> "§aPaul bonus set to §fauto§a."
            true -> "§aPaul bonus forced §fon§a."
            false -> "§aPaul bonus forced §foff§a."
        }
    }

    fun isPaul(): Boolean {
        paulOverride?.let { return it }
        if (ScoreCalculator.forcePaul.value) return true
        val mayorPerks = mutableListOf<ElectionData.Perk>()
        electionData.mayor.perks.let(mayorPerks::addAll)
        electionData.minister?.perk?.let(mayorPerks::add)
        return mayorPerks.any { it.name == "EZPZ" }
    }

    val dungeonItemDrops = listOf(
        "Health Potion VIII Splash Potion", "Healing Potion 8 Splash Potion",
        "Healing Potion VIII Splash Potion", "Healing VIII Splash Potion",
        "Healing 8 Splash Potion", "Decoy", "Inflatable Jerry", "Spirit Leap",
        "Trap", "Training Weights", "Defuse Kit", "Dungeon Chest Key",
        "Treasure Talisman", "Revive Stone", "Architect's First Draft",
        "Secret Dye", "Candycomb"
    )
}