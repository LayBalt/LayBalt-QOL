// Scope, dungeon-start refill, timer toggle and threshold ported from OdinClient (https://github.com/skies-starred/OdinClient), BSD-3-Clause, Copyright (c) 2026 Starred
package com.laybalt.features.impl.dungeon

//#if CHEAT

import com.laybalt.config.types.DropdownSetting
import com.laybalt.config.types.SliderSetting
import com.laybalt.config.types.ToggleSetting
import com.laybalt.event.EventBus
import com.laybalt.event.impl.ChatMessageEvent
import com.laybalt.event.impl.WorldChangeEvent
import com.laybalt.features.Feature
import com.laybalt.utils.*
import com.laybalt.utils.dungeons.DungeonListener
import com.laybalt.utils.dungeons.enums.DungeonClass
import com.laybalt.utils.items.ItemUtils.skyblockId
import com.laybalt.utils.location.LocationUtils
import com.laybalt.utils.location.WorldType
import gg.essential.universal.UMinecraft

object AutoGFS: Feature("Automatically refills dungeon items from your sacks using /gfs.") {
    private val scope by DropdownSetting("Scope", 0, listOf("Dungeons", "Dungeons + Kuudra", "Everywhere in SkyBlock"))
        .withDescription("Where refills are allowed: only in dungeons, in dungeons and Kuudra, or anywhere in SkyBlock.")
    private val refillOnTimer by ToggleSetting("Refill On Timer", true).withDescription("Periodically check your inventory and refill missing items.")
    private val delay by SliderSetting("Check Delay", 20.0, 5.0, 60.0, 1.0, "s").withDescription("How often to check for refills.").showIf { refillOnTimer.value }
    private val refillOnDungeonStart by ToggleSetting("Refill On Dungeon Start", true).withDescription("Refill every enabled item as soon as Mort starts the dungeon.")
    private val threshold by SliderSetting("Refill Threshold", 4, 1, 16, 1).withDescription("Only refill an item when at least this many are missing from a full stack.")

    private val refillPearl by ToggleSetting("Refill Pearl")
    private val refillTNT by ToggleSetting("Refill TNT")
    private val refillJerry by ToggleSetting("Refill Jerry")
    private val refillLeaps by ToggleSetting("Refill Leaps")
    private val refillTwilight by ToggleSetting("Refill Twilight")

    private val pyTwilight by ToggleSetting("Refill after lightning", true).section("Twilight").showIf { refillTwilight.value }
    private val coreTwilight by ToggleSetting("Refill in Core").showIf { refillTwilight.value }
    private val p5Twilight by ToggleSetting("Refill after M7 relics", true).showIf { refillTwilight.value }
    private val twilightAmount by SliderSetting("Twilight Amount", 8, 4, 32, 1).withDescription("The amount of Twilight you want the auto to pull out of sacks").showIf { (p5Twilight.value || pyTwilight.value) && refillTwilight.value }

    private val p5Message = Regex("^\\[BOSS] Wither King: I no longer wish to fight, but I know that will not stop you\\.$")
    private val pyMessage1 = Regex("^\\[BOSS] Storm: (ENERGY HEED MY CALL|THUNDER LET ME BE YOUR CATALYST)!$")
    private var pyHappened = false

    private val startRegex = Regex("^\\[NPC] Mort: Here, I found this map when I first entered the dungeon\\.$|^\\[NPC] Mort: Right-click the Orb for spells, and Left-click \\(or Drop\\) to use your Ultimate!$")

    private val sackEmptyMessage = Regex("^You have no (.+) in your Sacks!$")
    private val emptySacks = mutableSetOf<String>()
    private var refillCursor = 0

    override fun init() {
        register<WorldChangeEvent> {
            pyHappened = false
            emptySacks.clear()
        }

        ThreadUtils.loop({ delay.value * 1000 }) { if (refillOnTimer.value) refill(false) }

        register<ChatMessageEvent> {
            if (! refillOnDungeonStart.value) return@register
            if (! LocationUtils.inDungeon) return@register
            if (! startRegex.matches(event.unformattedText)) return@register
            ThreadUtils.scheduledTask(20) { refill(true) }
        }

        register<ChatMessageEvent> {
            if (! refillTwilight.value) return@register
            if (! LocationUtils.inDungeon) return@register
            if (! LocationUtils.inBoss) return@register
            if (LocationUtils.dungeonFloor != "M7") return@register
            val clazz = DungeonListener.thePlayer?.clazz ?: return@register
            val msg = event.unformattedText

            val dps = clazz.equalsOneOf(DungeonClass.Archer, DungeonClass.Berserk)
            fun fn() = gfs("twilight_arrow_poison", twilightAmount.value)

            when {
                coreTwilight.value && msg == "The Core entrance is opening!" && dps -> fn()
                p5Twilight.value && ! dps && p5Message.matches(msg) -> fn()
                pyTwilight.value && ! pyHappened && clazz == DungeonClass.Archer && pyMessage1.matches(msg) -> fn().also { pyHappened = true }
            }
        }
    }

    private val chatListener = EventBus.listener<ChatMessageEvent> {
        val item = sackEmptyMessage.matchEntire(event.unformattedText)?.groupValues?.get(1) ?: return@listener
        emptySacks.add(item.lowercase().replace(" ", "_"))
        listener.unregister()
    }

    private fun inScope() = when (scope.value) {
        0 -> LocationUtils.inDungeon
        1 -> LocationUtils.inDungeon || LocationUtils.world == WorldType.Kuudra
        else -> LocationUtils.inSkyblock
    }

    /** @param all refill every pending item at once (dungeon start) instead of one item per check. */
    private fun refill(all: Boolean) {
        if (! enabled || ! inScope()) return
        if (UMinecraft.currentScreenObj != null) return
        if (LocationUtils.inDungeon && DungeonListener.thePlayer?.isDead == true) return

        var pearlCount = 0
        var jerryCount = 0
        var tntCount = 0
        var leapCount = 0

        for (stack in player.inventory.nonEquipmentItems) when (stack.skyblockId) {
            "ENDER_PEARL" -> pearlCount += stack.count
            "INFLATABLE_JERRY" -> jerryCount += stack.count
            "SUPERBOOM_TNT" -> tntCount += stack.count
            "SPIRIT_LEAP" -> leapCount += stack.count
        }

        val pending = listOf(
            RefillEntry(pearlCount, 16, "ender_pearl", refillPearl.value),
            RefillEntry(jerryCount, 64, "inflatable_jerry", refillJerry.value),
            RefillEntry(tntCount, 64, "superboom_tnt", refillTNT.value),
            RefillEntry(leapCount, 16, "spirit_leap", refillLeaps.value),
        ).filter { it.enabled && it.current > 0 && it.max - it.current >= threshold.value.coerceAtMost(it.max - 1) && it.gfsName !in emptySacks }

        if (pending.isEmpty()) return
        if (all) {
            pending.forEach { gfs(it.gfsName, it.max - it.current) }
            return
        }
        val target = pending[refillCursor % pending.size]
        refillCursor ++
        gfs(target.gfsName, target.max - target.current)
    }

    private fun gfs(id: String, count: Int) {
        ChatUtils.sendCommand("gfs $id $count", 3000)
        ThreadUtils.setTimeout(5000) { chatListener.unregister() }
        chatListener.register()
    }

    private data class RefillEntry(val current: Int, val max: Int, val gfsName: String, val enabled: Boolean)
}
//#endif
