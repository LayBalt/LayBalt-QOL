// Ported from Odin (https://github.com/odtheking/Odin), BSD-3-Clause, Copyright (c) 2025 odtheking
package com.laybalt.utils.kuudra

import com.laybalt.event.EventBus
import com.laybalt.event.impl.ChatMessageEvent
import com.laybalt.event.impl.MainThreadPacketReceivedEvent
import com.laybalt.event.impl.TickEvent
import com.laybalt.event.impl.WorldChangeEvent
import com.laybalt.features.Shortcuts
import com.laybalt.init.types.ISelfInit
import com.laybalt.utils.ChatUtils.removeFormatting
import com.laybalt.utils.NumbersUtils.toFixed
import com.laybalt.utils.ThreadUtils
import com.laybalt.utils.location.LocationUtils
import com.laybalt.utils.location.WorldType
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.monster.Giant
import net.minecraft.world.entity.monster.cubemob.MagmaCube
import kotlin.jvm.optionals.getOrNull

/**
 * Shared Kuudra state used by the features in `features.impl.kuudra`.
 *
 * - [phase]: 0 = not started, 1 = supplies, 2 = build, 3 = fight (ballista ready), 4 = final phase.
 * - [kuudraTier]: 1..5 from the scoreboard line `Kuudra's Hollow (T5)`, 0 when unknown.
 * - [giantZombies]: the giants that carry supply crates (main-hand item ending in "Head").
 * - [kuudraEntity]: the Kuudra magma cube (size 30, 100k max health).
 * - [buildingPiles]: the `PROGRESS: x%` armor stands of unfinished ballista piles.
 * - [missing]: the supply a party member reported missing ("No Shop!" etc. in party chat).
 * - [freshers]: players that used Fresh Tools in the last 10 seconds (name -> time in ms, null once expired).
 */
object KuudraUtils: ISelfInit, Shortcuts {
    inline val inKuudra get() = LocationUtils.world == WorldType.Kuudra

    val freshers: MutableMap<String, Long?> = mutableMapOf()
    val giantZombies = arrayListOf<Giant>()
    var kuudraEntity: MagmaCube? = null
        private set
    var phase = 0
        private set

    val buildingPiles = arrayListOf<ArmorStand>()
    var playersBuildingAmount = 0
        private set
    var buildDonePercentage = 0
        private set

    var kuudraTier = 0
        private set

    /** The supply a party member called out as missing in party chat, [Supply.None] otherwise. */
    var missing = Supply.None
        private set

    /** System time of the "I will go and fish up Kuudra!" line (start of the supply phase), 0 if not seen yet. */
    var runStartTime = 0L
        private set

    private const val SUPPLY_PHASE_LINE = "[NPC] Elle: Okay adventurers, I will go and fish up Kuudra!"
    private const val BUILD_PHASE_LINE = "[NPC] Elle: OMG! Great work collecting my supplies!"
    private const val FIGHT_PHASE_LINE = "[NPC] Elle: Phew! The Ballista is finally ready! It should be strong enough to tank Kuudra's blows now!"
    private const val FINAL_PHASE_LINE = "[NPC] Elle: POW! SURELY THAT'S IT! I don't think he has any more in him!"

    private val ownFreshRegex = Regex("^Your Fresh Tools Perk bonus doubles your building speed for the next 10 seconds!$")
    private val partyFreshRegex = Regex("^Party > (\\[[^]]*?])? ?(\\w{1,16}): FRESH$")
    private val missingRegex = Regex("^Party > (\\[[^]]*?])? ?(\\w{1,16}): No ?(Triangle|X|Equals|Slash|xCannon|Square|Shop)!$")
    private val buildRegex = Regex("Building Progress (\\d+)% \\((\\d+) Players Helping\\)")
    private val tierRegex = Regex("Kuudra's Hollow \\(T(\\d)\\)$")
    private val progressRegex = Regex("PROGRESS: (\\d+)%")

    private var ticks = 0

    override fun init() {
        EventBus.register<TickEvent.End> {
            if (! inKuudra || ++ ticks % 10 != 0) return@register
            val entities = mc.level?.entitiesForRendering() ?: return@register

            giantZombies.clear()
            buildingPiles.clear()

            for (entity in entities) when (entity) {
                is Giant -> if (entity.mainHandItem.hoverName.string.removeFormatting().endsWith("Head")) giantZombies.add(entity)

                is MagmaCube -> if (entity.size == 30 && entity.getAttributeBaseValue(Attributes.MAX_HEALTH) == 100000.0) kuudraEntity = entity

                is ArmorStand -> {
                    val name = entity.name.string.removeFormatting()
                    if (name.matches(progressRegex)) buildingPiles.add(entity)

                    if (phase == 2) buildRegex.find(name)?.let {
                        buildDonePercentage = it.groupValues[1].toIntOrNull() ?: 0
                        playersBuildingAmount = it.groupValues[2].toIntOrNull() ?: 0
                    }

                    if (phase != 1 || name != "✓ SUPPLIES RECEIVED ✓") continue
                    val x = entity.x.toInt()
                    val z = entity.z.toInt()
                    when {
                        x == - 98 && z == - 112 -> Supply.Shop.isActive = false
                        x == - 98 && z == - 99 -> Supply.Equals.isActive = false
                        x == - 110 && z == - 106 -> Supply.xCannon.isActive = false
                        x == - 106 && z == - 112 -> Supply.X.isActive = false
                        x == - 94 && z == - 106 -> Supply.Triangle.isActive = false
                        x == - 106 && z == - 99 -> Supply.Slash.isActive = false
                    }
                }
            }

            if (kuudraEntity?.isRemoved == true) kuudraEntity = null
        }

        EventBus.register<ChatMessageEvent> {
            if (! inKuudra) return@register
            val message = event.unformattedText

            when (message) {
                SUPPLY_PHASE_LINE -> {
                    phase = 1
                    runStartTime = System.currentTimeMillis()
                }

                BUILD_PHASE_LINE -> phase = 2
                FIGHT_PHASE_LINE -> phase = 3
                FINAL_PHASE_LINE -> phase = 4
            }

            partyFreshRegex.find(message)?.groupValues?.get(2)?.let(::addFresher)
            if (ownFreshRegex.matches(message)) addFresher(mc.player?.name?.string ?: "self")

            missingRegex.find(message)?.groupValues?.lastOrNull()?.let { name ->
                Supply.entries.find { it.name == name }?.let { missing = it }
            }
        }

        EventBus.register<MainThreadPacketReceivedEvent.Post> {
            if (! inKuudra) return@register
            val packet = event.packet as? ClientboundSetPlayerTeamPacket ?: return@register
            val team = packet.parameters.getOrNull() ?: return@register
            val text = (team.playerPrefix.string + team.playerSuffix.string).removeFormatting()
            tierRegex.find(text)?.groupValues?.get(1)?.toIntOrNull()?.let { kuudraTier = it }
        }

        EventBus.register<WorldChangeEvent> { reset() }
    }

    private fun addFresher(name: String) {
        val time = System.currentTimeMillis()
        freshers[name] = time
        ThreadUtils.scheduledTaskServer(200) {
            mc.execute { if (freshers[name] == time) freshers[name] = null }
        }
    }

    private fun reset() {
        Supply.entries.forEach { it.isActive = true }
        playersBuildingAmount = 0
        buildDonePercentage = 0
        buildingPiles.clear()
        giantZombies.clear()
        kuudraEntity = null
        freshers.clear()
        kuudraTier = 0
        phase = 0
        missing = Supply.None
        runStartTime = 0L
    }

    /** Odin-style duration format: `1h 2m 3.45s`. */
    fun formatTime(millis: Long, decimals: Int = 2): String {
        if (millis <= 0L) return "0s"
        var remaining = millis
        val hours = remaining / 3_600_000
        remaining -= hours * 3_600_000
        val minutes = remaining / 60_000
        remaining -= minutes * 60_000
        return buildString {
            if (hours > 0) append("${hours}h ")
            if (minutes > 0) append("${minutes}m ")
            append((remaining / 1000.0).toFixed(decimals)).append('s')
        }
    }
}
