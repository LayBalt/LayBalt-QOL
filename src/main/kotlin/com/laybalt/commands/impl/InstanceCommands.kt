// Floors/KuudraTier instance names ported from Odin (https://github.com/odtheking/Odin), BSD-3-Clause, Copyright (c) 2025 odtheking
package com.laybalt.commands.impl

import com.laybalt.commands.CommandBuilder
import com.laybalt.commands.LbqHelp
import com.laybalt.commands.LbqHelp.Category
import com.laybalt.init.types.ICommandProvider
import com.laybalt.utils.ChatUtils

/** `/lbq f1..f7`, `/lbq m1..m7` and `/lbq t1..t5`: join a Catacombs floor or a Kuudra tier (Odin's `/od <floor|tier>`). */
object InstanceCommands: ICommandProvider {
    private val floorWords = listOf("one", "two", "three", "four", "five", "six", "seven")
    private val kuudraTiers = listOf("normal", "hot", "burning", "fiery", "infernal")

    override fun CommandBuilder.command() {
        setName(*LbqHelp.ROOTS.toTypedArray())

        floorWords.forEachIndexed { i, word ->
            literal("f${i + 1}") {
                category(Category.DUNGEONS)
                if (i == 0) {
                    helpUsage("f1..f7 | m1..m7")
                    description("Joins a Catacombs floor (f) or a Master Mode floor (m)")
                }
                runs { join("catacombs_floor_$word") }
            }
            literal("m${i + 1}") {
                category(Category.DUNGEONS)
                runs { join("master_catacombs_floor_$word") }
            }
        }

        kuudraTiers.forEachIndexed { i, tier ->
            literal("t${i + 1}") {
                category(Category.DUNGEONS)
                if (i == 0) {
                    helpUsage("t1..t5")
                    description("Joins a Kuudra tier (Basic, Hot, Burning, Fiery, Infernal)")
                }
                runs { join("kuudra_$tier") }
            }
        }
    }

    private fun join(instance: String) = ChatUtils.sendCommand("joininstance $instance")
}
