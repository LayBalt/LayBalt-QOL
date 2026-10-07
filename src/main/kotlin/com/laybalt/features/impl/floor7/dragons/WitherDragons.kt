// Ported from Odin (https://github.com/odtheking/Odin), BSD-3-Clause, Copyright (c) 2025 odtheking
package com.laybalt.features.impl.floor7.dragons

import com.laybalt.config.types.ColorSetting
import com.laybalt.config.types.DropdownSetting
import com.laybalt.config.types.SliderSetting
import com.laybalt.config.types.ToggleSetting
import com.laybalt.event.impl.*
import com.laybalt.features.Feature
import com.laybalt.utils.ChatUtils
import com.laybalt.utils.ColorUtils.withAlpha
import com.laybalt.utils.MathUtils.add
import com.laybalt.utils.MathUtils.vec
import com.laybalt.utils.NumbersUtils.toFixed
import com.laybalt.utils.location.LocationUtils
import com.laybalt.utils.ChatUtils.unformattedText
import com.laybalt.utils.render.Render2D.drawCenteredString
import com.laybalt.utils.render.Render2D.drawString
import com.laybalt.utils.render.RenderHelper.width
import com.laybalt.utils.render.world.Render3D.renderBillboardedCircle
import com.laybalt.utils.render.world.Render3D.renderBoxBounds
import com.laybalt.utils.render.world.Render3D.renderString
import com.laybalt.utils.render.world.Render3D.renderTracer
import com.laybalt.utils.render.RenderHelper.renderVec
import gg.essential.universal.UResolution
import net.minecraft.network.protocol.game.*
import net.minecraft.world.entity.boss.enderdragon.EnderDragon
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3
import java.awt.Color
import java.util.concurrent.*
import kotlin.math.sqrt

object WitherDragons: Feature("M7 dragons timers, boxes, priority, health, and alerts") {
    private val dragonTimer by ToggleSetting("Dragon Timer ", true).section("Dragon Timer")
        .withDescription("Shows a countdown above each spawning dragon's statue.")
    private val dragonTimerStyle by DropdownSetting("Timer Style", 0, listOf("Milliseconds", "Seconds", "Ticks")).showIf { dragonTimer.value }
        .withDescription("Unit used for every dragon timer.")
    private val showSymbol by ToggleSetting("Timer Symbol", true).showIf { dragonTimer.value }
        .withDescription("Appends the unit symbol (ms, s or t) to the timer.")
    private val colorTimer by ToggleSetting("Color Timer By Time", false).showIf { dragonTimer.value }
        .withDescription("Colours the timer green, then yellow under 3 seconds and red under 1 second.")
    private val centeredTimer by ToggleSetting("Centered Timer", true).showIf { dragonTimer.value }
        .withDescription("Shows the big timer of your priority dragon in the middle of the screen.")
    private val timerHud by ToggleSetting("Dragon Timer HUD", false).showIf { dragonTimer.value }
        .withDescription("Shows the priority dragon's timer as a movable HUD element (move it in the HUD editor).")

    private val dragonArrowStack by ToggleSetting("Arrow Stack Indicator", true).section("Arrow Stack").withDescription("Shows the optimal arrow stack aim position")
    private val indicatorColor by ColorSetting("Indicator Color", Color.CYAN)
    private val indicatorSize by SliderSetting("Indicator Size", 2.0f, 0.1f, 5.0f, 0.1f)
    private val indicatorThickness by SliderSetting("Indicator Thickness", 3.0f, 1.0f, 10.0f, 0.5f)

    private val dragonBoxes by ToggleSetting("Dragon Skip Box ", true).section("Dragon Visuals")
    private val dragonHealth by ToggleSetting("Dragon Health", true)
    private val highlightDragons by ToggleSetting("Highlight Dragons")
    private val dragonTracers by ToggleSetting("Dragon Tracer", false)
    private val tracerThickness by SliderSetting("Tracer Width", 2f, 1f, 5f, 0.1f).showIf { dragonTracers.value }

    val sendTime by ToggleSetting("Send Dragon Time Alive", true).section("Dragon Alerts")
        .withDescription("When a dragon dies, prints how long it was alive and compares it with your personal best for that dragon.")
    val sendSpray by ToggleSetting("Send Ice Sprayed", true)
        .withDescription("Adds how many ticks after spawning the dragon was ice sprayed to the death message.")
    val sendArrowHit by ToggleSetting("Send Arrows Hit", true)
        .withDescription("Adds how many of your arrows hit your priority dragon during its skip window to the death message.")
    val sendSpawned by ToggleSetting("Send Dragon Spawned", true)
        .withDescription("Prints a message when a dragon spawns, with how many times that dragon has spawned.")
    private val sendNotification by ToggleSetting("Send Dragon Confirmation", true)
        .withDescription("When the Wither King confirms a dragon kill, prints which dragon counted.")
    val dragonTitle by ToggleSetting("Dragon Title", true)
        .withDescription("Shows a title with your priority dragon when it starts spawning.")

    val dragonPriorityToggle by ToggleSetting("Dragon Priority", false).section("Dragon Priority")
        .withDescription("Picks your priority dragon from your power, class and split settings instead of the fixed R-O-B-P-G order.")
    val normalPower by SliderSetting("Normal Power", 0f, 0f, 32f, 0.5f).showIf { dragonPriorityToggle.value }
        .withDescription("Power (plus 2.5 with a Time blessing) needed to split.")
    val easyPower by SliderSetting("Easy Power", 0f, 0f, 32f, 0.5f).showIf { dragonPriorityToggle.value }
        .withDescription("Power needed to split when Purple spawns together with another dragon.")
    val soloDebuff by DropdownSetting("Purple Solo Debuff", 0, listOf("Tank", "Healer")).showIf { dragonPriorityToggle.value }
        .withDescription("The class that solo debuffs Purple; the other class helps Berserk/Mage.")
    val soloDebuffOnAll by ToggleSetting("Solo Debuff on All Splits", true).showIf { dragonPriorityToggle.value }
        .withDescription("Uses the solo debuff rule for every split, not only splits with Purple.")
    val paulBuff by ToggleSetting("Paul Buff", false).showIf { dragonPriorityToggle.value }
        .withDescription("Multiplies the run's power by 1.25 (Paul's Benediction perk) before comparing it with the thresholds.")
    val priorityMessage by ToggleSetting("Priority Message", true).showIf { dragonPriorityToggle.value }
        .withDescription("On a split, prints the spawning dragons and which one is your priority.")

    var priorityDragon = WitherDragonEnum.None

    private const val scoreboardGraceTicks = 40 // how long the dragon needs to be off scoreboard for it to count as dead

    private val smoothedVelocities = ConcurrentHashMap<Int, Vec3>()
    private val fixedStackPositions = mapOf(
        WitherDragonEnum.Green to vec(27f, WitherDragonEnum.Green.spawnPos.y, 90f),
        WitherDragonEnum.Red to vec(28f, WitherDragonEnum.Red.spawnPos.y, 58f),
        WitherDragonEnum.Blue to vec(84f, WitherDragonEnum.Blue.spawnPos.y, 97f)
    )

    private val witherKingRegex = Regex("^\\[BOSS] Wither King: (Oh, this one hurts!|I have more of those\\.|My soul is disposable\\.)$")

    /** Dragon whose death was seen last; consumed by the Wither King confirmation line. */
    var lastDragonDeath: WitherDragonEnum? = null

    /** (dragon, timesSpawned) of the last title/priority announcement, so each spawn is announced once. */
    var lastAnnounced: Pair<WitherDragonEnum, Int>? = null

    override fun init() {
        hudElement(
            "Dragon Timer",
            enabled = { dragonTimer.value && timerHud.value },
            shouldDraw = { LocationUtils.F7Phase == 5 && priorityDragon != WitherDragonEnum.None && priorityDragon.state == WitherDragonState.SPAWNING && priorityDragon.timeToSpawn > 0 }
        ) { ctx, example ->
            val text = if (example) "&5P: ${getDragonTimer(90)}"
            else "&${priorityDragon.colorCode}${priorityDragon.name.first()}: ${getDragonTimer(priorityDragon.timeToSpawn)}"
            ctx.drawString(text, 0, 0)
            return@hudElement text.width() to 9f
        }

        register<WorldChangeEvent> {
            priorityDragon = WitherDragonEnum.None
            WitherDragonEnum.reset()
            smoothedVelocities.clear()
            lastDragonDeath = null
            lastAnnounced = null
            DragonPriority.warnedClass = false
        }

        register<ChatMessageEvent> {
            if (LocationUtils.F7Phase != 5 || ! witherKingRegex.matches(event.unformattedText)) return@register
            val dragon = lastDragonDeath ?: WitherDragonEnum.entries.find { it != WitherDragonEnum.None && it.state != WitherDragonState.DEAD }
            dragon?.let {
                if (sendNotification.value) ChatUtils.modMessage("&${it.colorCode}${it.name} &fdragon counts.")
                if (it.state != WitherDragonState.DEAD) it.setDead(silent = true)
            }
            lastDragonDeath = null
        }

        register<MainThreadPacketReceivedEvent.Pre> {
            if (LocationUtils.F7Phase != 5) return@register
            when (val packet = event.packet) {
                is ClientboundLevelParticlesPacket -> DragonCheck.handleSpawnPacket(packet)
                is ClientboundSetEquipmentPacket -> DragonCheck.dragonSprayed(packet)
                is ClientboundAddEntityPacket -> DragonCheck.dragonSpawn(packet)
                is ClientboundSetEntityDataPacket -> DragonCheck.dragonUpdate(packet)
                is ClientboundSoundPacket -> DragonCheck.trackArrows(packet)
            }
        }

        register<EntityUnloadEvent> {
            if (LocationUtils.F7Phase != 5) return@register
            if (event.entity !is EnderDragon) return@register
            val dragon = WitherDragonEnum.entries.find { it.entityId == event.entity.id } ?: return@register
            if (dragon.state != WitherDragonState.ALIVE) return@register
            ChatUtils.debug("dragon", "${dragon.displayName} unloaded with ${formatHealth(dragon.health)} health")

            dragon.entity = null
            dragon.offScoreboardTicks = 0
        }

        register<BlockChangeEvent> {
            if (LocationUtils.F7Phase != 5) return@register
            if (event.newBlock == Blocks.AIR && event.oldBlock == Blocks.COBBLESTONE_SLAB) {
                WitherDragonEnum.entries.find {
                    it.bottomChin.x == event.pos.x && it.bottomChin.z == event.pos.z
                }?.setDead(true)
            }
        }

        register<TickEvent.Server> {
            WitherDragonEnum.entries.forEach { dragon ->
                if (dragon.state == WitherDragonState.SPAWNING) {
                    dragon.timeToSpawn --
                    if (dragon.timeToSpawn <= - 20) dragon.setDead(true)
                }

                if (dragon.state == WitherDragonState.ALIVE && dragon.entity == null) {
                    if (DragonCheck.isAliveOnScoreboard(dragon)) dragon.offScoreboardTicks = 0
                    else if (dragon.offScoreboardTicks < scoreboardGraceTicks) dragon.offScoreboardTicks ++
                    else dragon.setDead(accurate = false).also { ChatUtils.debug("dragon", "${dragon.displayName} set to dead by scoreboard") }
                }
            }
        }

        register<RenderWorldEvent> {
            if (LocationUtils.F7Phase != 5) return@register

            WitherDragonEnum.entries.forEach { dragon ->
                if (dragonHealth.value && dragon.state == WitherDragonState.ALIVE) dragon.entity?.let {
                    event.ctx.renderString(formatHealth(dragon.health), it.renderVec.add(y = - 1), scale = 6f, phase = true)
                }

                if (dragonTimer.value && dragon.state == WitherDragonState.SPAWNING && dragon.timeToSpawn > 0) event.ctx.renderString(
                    "&${dragon.colorCode}${dragon.name}: ${getDragonTimer(dragon.timeToSpawn)}",
                    dragon.spawnPos, scale = 6f
                )

                if (dragonBoxes.value && dragon.state != WitherDragonState.DEAD) event.ctx.renderBoxBounds(
                    dragon.boxesDimensions, dragon.color.withAlpha(0.5f), fill = false, lineWidth = 2.0
                )

                if (dragonArrowStack.value && dragon.state == WitherDragonState.SPAWNING) {
                    val targetPos = (fixedStackPositions[dragon] ?: dragon.spawnPos).add(0.5, 3.5, 0.5)
                    val leadPos = calculateLead(targetPos) ?: return@forEach

                    val distance = player.eyePosition.distanceTo(targetPos)
                    val scaledSize = indicatorSize.value * sqrt(distance / 50.0).coerceAtLeast(0.5)

                    event.ctx.renderBillboardedCircle(leadPos, scaledSize, indicatorColor.value, indicatorThickness.value, phase = true)
                }
            }

            if (dragonTracers.value && priorityDragon != WitherDragonEnum.None && priorityDragon.state == WitherDragonState.SPAWNING) {
                event.ctx.renderTracer(priorityDragon.spawnPos.add(0.5, 3.5, 0.5), priorityDragon.color, tracerThickness.value)
            }
        }

        register<RenderOverlayEvent> {
            if (! dragonTimer.value || ! centeredTimer.value) return@register
            priorityDragon.takeIf { it != WitherDragonEnum.None }?.let { dragon ->
                if (dragon.state != WitherDragonState.SPAWNING || dragon.timeToSpawn <= 0) return@register
                event.context.drawCenteredString(
                    "&${dragon.colorCode}${getDragonTimer(dragon.timeToSpawn)}",
                    UResolution.scaledWidth / 2f,
                    UResolution.scaledHeight * 0.4f,
                    scale = 3f,
                )
            }
        }

        register<CheckEntityGlowEvent> {
            if (! highlightDragons.value) return@register
            if (LocationUtils.F7Phase != 5) return@register

            WitherDragonEnum.entries.forEach { dragon ->
                if (dragon.state != WitherDragonState.ALIVE) return@forEach
                if (event.entity.id != dragon.entityId) return@forEach
                event.color = dragon.color
                return@register
            }
        }
    }


    private fun getDragonTimer(spawnTime: Int): String = (if (! colorTimer.value) "" else when {
        spawnTime <= 20 -> "&c"
        spawnTime <= 60 -> "&e"
        else -> "&a"
    }) + when (dragonTimerStyle.value) {
        0 -> "${(spawnTime * 50)}${if (showSymbol.value) "ms" else ""}"
        1 -> "${(spawnTime / 20f).toFixed(1)}${if (showSymbol.value) "s" else ""}"
        else -> "${spawnTime}${if (showSymbol.value) "t" else ""}"
    }

    private fun formatHealth(health: Float): String {
        val color = when {
            health >= 750_000_000 -> "&a"
            health >= 500_000_000 -> "&e"
            health >= 250_000_000 -> "&6"
            else -> "&c"
        }

        val str = when {
            health >= 1_000_000_000 -> {
                val b = health / 1_000_000_000
                "${if (b > 1) b.toFixed(1) else b.toInt()}b"
            }

            health >= 1_000_000 -> "${(health / 1_000_000).toInt()}m"
            health >= 1_000 -> "${(health / 1_000).toInt()}k"
            else -> "${health.toInt()}"
        }

        return color + str
    }

    private fun calculateLead(targetPos: Vec3): Vec3? {
        val distToTargetSq = targetPos.distanceToSqr(player.eyePosition)

        var currentArrowDist = 0.0
        var currentSpeed = 3.0
        var currentYVel = 0.0
        var drop = 0.0

        repeat(160) {
            currentArrowDist += currentSpeed
            currentSpeed *= 0.99

            drop += currentYVel
            currentYVel -= 0.05
            currentYVel *= 0.99

            if ((currentArrowDist * currentArrowDist) >= distToTargetSq) {
                return targetPos.subtract(0.0, drop, 0.0)
            }
        }

        return null
    }
}