// Item ids, sack names and default amounts from Odin's MainCommand (https://github.com/odtheking/Odin), BSD-3-Clause, Copyright (c) 2025 odtheking
package com.laybalt.commands.impl

import com.laybalt.LayBaltQOL.mc
import com.laybalt.commands.CommandBuilder
import com.laybalt.commands.LbqHelp
import com.laybalt.commands.LbqHelp.Category
import com.laybalt.init.types.ICommandProvider
import com.laybalt.utils.ChatUtils
import com.laybalt.utils.items.ItemUtils.skyblockId
import com.mojang.brigadier.arguments.IntegerArgumentType

/** `/lbq ep|ij|sl|sb|dd|tap|twap [amount]`: tops the item up to [amount] (default: one stack) with /gfs. */
object SackCommands: ICommandProvider {
    private class SackItem(val command: String, val itemId: String, val sackName: String, val defaultAmount: Int) {
        val displayName get() = sackName.replace('_', ' ')
    }

    private val items = listOf(
        SackItem("ep", "ENDER_PEARL", "ender_pearl", 16),
        SackItem("ij", "INFLATABLE_JERRY", "inflatable_jerry", 64),
        SackItem("sl", "SPIRIT_LEAP", "spirit_leap", 16),
        SackItem("sb", "SUPERBOOM_TNT", "superboom_tnt", 64),
        SackItem("dd", "DUNGEON_DECOY", "dungeon_decoy", 64),
        SackItem("tap", "TOXIC_ARROW_POISON", "toxic_arrow_poison", 64),
        SackItem("twap", "TWILIGHT_ARROW_POISON", "twilight_arrow_poison", 64),
    )

    override fun CommandBuilder.command() {
        setName(*LbqHelp.ROOTS.toTypedArray())

        for (item in items) literal(item.command) {
            category(Category.SACKS)
            helpUsage("${item.command} [amount]")
            description("Refills ${item.displayName} from your sacks up to [amount] (default ${item.defaultAmount})")
            runs { refill(item, item.defaultAmount) }
            argument("amount", IntegerArgumentType.integer(1, 2240)) {
                suggests { listOf(item.defaultAmount.toString()) }
                runs { refill(item, IntegerArgumentType.getInteger(it, "amount")) }
            }
        }
    }

    /** Counts every stack of the item (NoammAddons) and asks /gfs for the rest; says so when nothing is missing (Odin). */
    private fun refill(item: SackItem, amount: Int) {
        val inventory = mc.player?.inventory ?: return
        val current = inventory.sumOf { stack -> if (stack.skyblockId == item.itemId) stack.count else 0 }
        val missing = amount - current
        if (missing > 0) ChatUtils.sendCommand("gfs ${item.sackName} $missing")
        else ChatUtils.modMessage("§cYou already have §f$current §c${item.displayName}.")
    }
}
