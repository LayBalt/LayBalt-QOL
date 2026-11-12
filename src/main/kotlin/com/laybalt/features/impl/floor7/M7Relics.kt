// Ported from Odin (https://github.com/odtheking/Odin), BSD-3-Clause, Copyright (c) 2025 odtheking
package com.laybalt.features.impl.floor7

import com.laybalt.config.PersonalBest
import com.laybalt.config.types.SliderSetting
import com.laybalt.config.types.ToggleSetting
import com.laybalt.event.impl.*
import com.laybalt.features.Feature
import com.laybalt.utils.ChatUtils
import com.laybalt.utils.ChatUtils.removeFormatting
import com.laybalt.utils.ChatUtils.unformattedText
import com.laybalt.utils.WorldUtils
import com.laybalt.utils.render.world.Render3D.renderBoxBounds
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.AABB
import com.laybalt.utils.MathUtils.center
import com.laybalt.utils.MathUtils.toPos
import com.laybalt.utils.MathUtils.vec
import com.laybalt.utils.NumbersUtils.toFixed
import com.laybalt.utils.PlayerUtils
import com.laybalt.utils.dungeons.DungeonListener
import com.laybalt.utils.dungeons.enums.WitherRelic
import com.laybalt.utils.location.LocationUtils
import com.laybalt.utils.render.Render2D.drawCenteredString
import com.laybalt.utils.render.RenderHelper.width
import com.laybalt.utils.render.world.Render3D.renderBlock
import com.laybalt.utils.render.world.Render3D.renderTracer
import kotlinx.coroutines.launch
import net.minecraft.core.BlockPos
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket
import net.minecraft.network.protocol.game.ClientboundSetPlayerInventoryPacket
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.phys.EntityHitResult
import net.minecraft.world.phys.Vec3
import java.awt.Color

object M7Relics: Feature(name = "M7 Relics", description = "A bunch of M7 Relics features") {
    private val relicBox by ToggleSetting("Box Relics").withDescription("Draws a box on where the relics are spawning and the cauldron you need to place.")
    private val relicSpawnTimer by ToggleSetting("Spawn Timer").withDescription("Shows on screen when the relics will spawn (61 server ticks after Necron dies).")
    private val relicTimer by ToggleSetting("Place Timer").withDescription("When all 5 relics are placed, sends in chat how long after the start of P5 each one was placed.")
    private val instantPlaceTime by ToggleSetting("Instant Place Time", true)
        .withDescription("Prints your own relic place time (from the start of P5, now the Wither King's boss bar; PBs saved before the dungeon update counted from Necron's last line) with a personal-best comparison as soon as you click the cauldron, without waiting for all 5 relics.")
    private val pickupPlaceTime by ToggleSetting("Pickup To Place Time", true).showIf { instantPlaceTime.value }
        .withDescription("Adds how long it took from the relic landing in your 9th hotbar slot to placing it (Odin's relic time), with its own personal best.")
    private val relicBeacon by ToggleSetting("Relic Beacon", false)
        .withDescription("Draws a beacon-style beam above the cauldron of the relic you are holding.")

    //#if CHEAT
    private val relicLook by ToggleSetting("Relic Look").withDescription("Automatically rotate to the relic cauldron after you pick it up.")
    private val relicLookTime by SliderSetting("Relic Look Time", 150L, 10, 300, 1).showIf { relicLook.value }.withDescription("How fast should the auto rotate (in milliseconds)")
    private val blockWrongRelic by ToggleSetting("Block Wrong Relic").withDescription("Prevents you from placing your relic at the wrong cauldron.")
    private val relicTriggerbot by ToggleSetting("Relic Pickup Triggerbot").withDescription("Automatically picks up the relic when you're aiming at it.").jsonName("Relic Triggerbot")
    private val relicPlaceTriggerbot by ToggleSetting("Relic Place Triggerbot").withDescription("Automatically selects hotbar slot 9 and places your relic when you're aiming at its cauldron or the anvil below it.")
    private val anvil by ToggleSetting("Full Block Anvil").withDescription("Makes the anvil under the relic cauldrons a full block hitbox during P5, so it is easier to aim at.")
    private var lastRelicClick = 0L
    //#endif

    private val relicPickUpRegex = Regex("^(\\w{3,16}) picked the Corrupted (\\w{3,6}) Relic!$")
    private val relicTimes = mutableListOf<RelicEntry>()
    /** Server tick at which the Wither King phase started (his boss bar appeared); 0 until then. */
    private var p5StartTime = 0L
    /** Server tick at which your relic landed in hotbar slot 9 (Odin 1cf5805); -1 until then. */
    private var relicPickupTick = - 1L
    /** Server tick at which the relics spawn (NoammAddons 6a656831: 61 ticks after Necron's death sound); 0 if none. */
    private var spawnTimerTicks = 0L

    /** Relics you placed yourself this run -> whether that place time was a new PB (from Instant Place Time). */
    private val ownPlaceResults = mutableMapOf<WitherRelic, Boolean>()

    private data class RelicEntry(
        val relic: WitherRelic,
        val player: String,
        val pickupTimeMs: Long,
        var placeTimeSeconds: Double = 0.0,
        var isPlaced: Boolean = false,
        var isPB: Boolean = false
    )

    override fun init() {
        register<WorldChangeEvent> {
            relicTimes.clear()
            p5StartTime = 0L
            relicPickupTick = - 1L
            spawnTimerTicks = 0L
            ownPlaceResults.clear()
            //#if CHEAT
            lastRelicClick = 0L
            //#endif
        }

        // NoammAddons 605cac19/ba028bc1: since the dungeon update Necron's "All this, for nothing..." is no longer used as
        // the start of P5 (upstream dropped it), so P5 starts when the Wither King's boss bar appears.
        register<DungeonEvent.WitherKingStartEvent> {
            if (LocationUtils.dungeonFloor != "M7") return@register
            p5StartTime = DungeonListener.currentTime
            ownPlaceResults.clear()
        }

        // NoammAddons 6a656831 / bd22e1cb (M7 only, F7 has no relics). Upstream also starts P5 here; LayBaltQOL keeps
        // the Wither King boss bar above as the start of P5, which the place time PBs count from.
        register<DungeonEvent.NecronDeathEvent> {
            if (LocationUtils.dungeonFloor != "M7") return@register
            if (relicSpawnTimer.value) spawnTimerTicks = DungeonListener.currentTime + 61
        }

        hudElement(
            "Relic Spawn Timer",
            enabled = { relicSpawnTimer.value },
            shouldDraw = { spawnTimerTicks - DungeonListener.currentTime > 0 },
            centered = { true }
        ) { ctx, example ->
            val ticksLeft = if (example) 25 else spawnTimerTicks - DungeonListener.currentTime
            val displayTime = (ticksLeft / 20.0).toFixed(2)
            ctx.drawCenteredString(displayTime, 0, 0, DungeonListener.thePlayer?.clazz?.color ?: Color.WHITE)
            return@hudElement displayTime.width() to 9f
        }

        register<ChatMessageEvent> {
            if (! relicTimer.value) return@register
            if (! LocationUtils.inDungeon || ! LocationUtils.inBoss || LocationUtils.dungeonFloor != "M7") return@register

            relicPickUpRegex.find(event.unformattedText)?.destructured?.let { (player, relicType) ->
                val relic = WitherRelic.fromName("Corrupted $relicType Relic") ?: return@register
                relicTimes.add(RelicEntry(relic, player, System.currentTimeMillis()))
            }
        }

        register<RenderWorldEvent> {
            if (! relicBox.value) return@register
            if (LocationUtils.F7Phase != 5) return@register
            WitherRelic.fromName(player.inventory.getItem(8).hoverName.string)?.let {
                event.ctx.renderBlock(it.cauldronPos.toPos(), it.color, phase = true)
                event.ctx.renderTracer(it.cauldronPos.add(0.5, 0.5, 0.5), it.color)
            }
        }

        register<RenderWorldEvent> {
            if (! relicBeacon.value || LocationUtils.F7Phase != 5) return@register
            val relic = heldRelic() ?: return@register
            val base = relic.cauldronPos
            val beamColor = Color(relic.color.red, relic.color.green, relic.color.blue, 90)
            event.ctx.renderBoxBounds(
                AABB(base.x + 0.3, base.y + 1.0, base.z + 0.3, base.x + 0.7, base.y + 120.0, base.z + 0.7),
                beamColor, outline = false, fill = true, phase = true
            )
            event.ctx.renderBoxBounds(
                AABB(base.x, base.y, base.z, base.x + 1.0, base.y + 1.0, base.z + 1.0),
                Color(relic.color.red, relic.color.green, relic.color.blue, 255), beamColor, outline = true, fill = true, phase = true
            )
        }

        // Odin 1cf5805: the relic time counts server ticks from the relic landing in hotbar slot 9 (inventory menu slot 44)
        // to placing it. Checked after every packet that can fill that slot, like Odin's SetSlotEvent.
        register<MainThreadPacketReceivedEvent.Post> {
            if (relicPickupTick != - 1L || LocationUtils.F7Phase != 5) return@register
            val packet = event.packet
            if (packet !is ClientboundContainerSetSlotPacket && packet !is ClientboundContainerSetContentPacket && packet !is ClientboundSetPlayerInventoryPacket) return@register
            if (heldRelic() != null) relicPickupTick = DungeonListener.currentTime
        }

        // Odin "Relic Time": announce your own place time immediately when you click the cauldron.
        register<PlayerInteractEvent.RIGHT_CLICK.BLOCK> {
            if (! instantPlaceTime.value || p5StartTime == 0L || LocationUtils.F7Phase != 5) return@register
            val relic = event.item?.hoverName?.string?.removeFormatting()?.let(WitherRelic::fromName) ?: return@register
            if (relic in ownPlaceResults) return@register
            if (WorldUtils.getBlockAt(event.pos) !in placeBlocks) return@register
            val dx = event.pos.x - relic.cauldronPos.x
            val dz = event.pos.z - relic.cauldronPos.z
            if (dx * dx + dz * dz > 2.5) return@register

            val key = "M7_relic_${relic.name}"
            val seconds = ((DungeonListener.currentTime - p5StartTime) / 20.0).toFixed(2).toDouble()
            val oldPB = PersonalBest.getPB(key)?.toDouble()
            val isPB = PersonalBest.checkAndSetPB(key, seconds, lowerIsBetter = true)
            ownPlaceResults[relic] = isPB

            val pbText = when {
                isPB && oldPB != null -> " §7(§d§lNew PB§r§7) Old PB was §8${oldPB.toFixed(2)}s"
                isPB -> " §7(§d§lNew PB§r§7)"
                oldPB != null -> " §8(§7${oldPB.toFixed(2)}s§8)"
                else -> ""
            }
            ChatUtils.modMessage("${relic.coloredName} relic §7placed in §6${seconds}s§7!$pbText${pickupPlaceText(relic)}")
        }

        register<TickEvent.Start> {
            if (! relicTimer.value || p5StartTime == 0L || LocationUtils.F7Phase != 5 || relicTimes.isEmpty()) return@register
            val activeRelics = relicTimes.filter { ! it.isPlaced }.takeUnless { it.isEmpty() } ?: return@register

            val relicStands = level.entitiesForRendering().filterIsInstance<ArmorStand>().filter {
                it.getItemBySlot(EquipmentSlot.HEAD).hoverName.string.contains("Relic")
            }

            for (entity in relicStands) for (entry in activeRelics) {
                if (! isEntityAtCauldron(entity.position(), entry.relic)) continue
                val currentTime = (DungeonListener.currentTime - p5StartTime) / 20.0
                entry.placeTimeSeconds = currentTime.toFixed(2).toDouble()
                entry.isPlaced = true

                if (entry.player != mc.user.name) continue
                entry.isPB = ownPlaceResults[entry.relic] ?: PersonalBest.checkAndSetPB(
                    key = "M7_relic_${entry.relic.name}",
                    value = entry.placeTimeSeconds,
                    lowerIsBetter = true
                )
            }

            if (relicTimes.size == 5 && relicTimes.all { it.isPlaced }) {
                relicTimes.sortedBy { it.placeTimeSeconds }.forEach { entry ->
                    val pbSuffix = if (entry.isPB) " §d§l(PB)" else ""
                    ChatUtils.modMessage("${entry.relic.coloredName} §aRelic placed in §e${entry.placeTimeSeconds}s§a.$pbSuffix")
                }
                relicTimes.clear()
            }
        }

        //#if CHEAT
        register<TickEvent.Start> {
            if (! relicTriggerbot.value || LocationUtils.F7Phase != 5) return@register
            if (mc.gui.screen() != null) return@register
            val now = System.currentTimeMillis()
            if (now - lastRelicClick < 200) return@register
            if (player.inventory.getItem(8).hoverName.string.contains("Relic")) return@register

            val hit = mc.hitResult as? EntityHitResult ?: return@register
            val armorStand = hit.entity as? ArmorStand ?: return@register
            if (! armorStand.getItemBySlot(EquipmentSlot.HEAD).hoverName.string.contains("Relic")) return@register
            if (armorStand.position().distanceTo(player.position()) >= 3) return@register
            if (WitherRelic.entries.any { isEntityAtCauldron(armorStand.position(), it) }) return@register

            PlayerUtils.rightClick()
            lastRelicClick = now
        }

        // NoammAddons 9f8fc6ac (with 5f298ef6, 37b98124): the vanilla use click still fires PlayerInteractEvent, so
        // Instant Place Time and Block Wrong Relic see it like a manual click.
        register<TickEvent.Start> {
            if (! relicPlaceTriggerbot.value || LocationUtils.F7Phase != 5) return@register
            if (mc.gui.screen() != null || DungeonListener.thePlayer?.isDead == true) return@register
            val now = System.currentTimeMillis()
            if (now - lastRelicClick < 200) return@register
            val relic = WitherRelic.fromName(player.inventory.getItem(8).hoverName.string) ?: return@register
            val looking = PlayerUtils.getSelectionBlock() ?: return@register
            val cauldron = relic.cauldronPos.toPos()
            if (looking != cauldron && looking != cauldron.below()) return@register

            PlayerUtils.swapToSlot(8)
            PlayerUtils.rightClick()
            lastRelicClick = now
        }

        fun onInteract(event: PlayerInteractEvent, pos: BlockPos) {
            if (! blockWrongRelic.value || LocationUtils.F7Phase != 5) return
            val item = event.item?.hoverName?.string ?: return
            val relic = WitherRelic.fromName(item) ?: return
            if (pos.x == relic.cauldronPos.x.toInt() && pos.z == relic.cauldronPos.z.toInt()) return
            event.cancel()
        }

        register<PlayerInteractEvent.LEFT_CLICK.BLOCK> { onInteract(event, event.pos) }
        register<PlayerInteractEvent.RIGHT_CLICK.BLOCK> { onInteract(event, event.pos) }

        register<MainThreadPacketReceivedEvent.Post> {
            if (! relicLook.value || LocationUtils.F7Phase != 5) return@register
            if (event.packet !is ClientboundContainerSetSlotPacket) return@register
            val item = PlayerUtils.getHotbarSlot(8)?.hoverName ?: return@register
            val relic = WitherRelic.fromName(item.unformattedText) ?: return@register
            if (relic == WitherRelic.RED || relic == WitherRelic.ORANGE) scope.launch {
                PlayerUtils.rotateSmoothly(relic.cauldronPos.center(), relicLookTime.value)
            }
        }
        //#endif
    }

    /**
     * " | pickup → place 0.85s (PB)" for Odin's relic time (Odin 1cf5805), under its own PB key: it is measured from a
     * different moment than LayBaltQOL's place time, which counts from the start of P5.
     */
    private fun pickupPlaceText(relic: WitherRelic): String {
        if (! pickupPlaceTime.value || relicPickupTick < 0) return ""
        val key = "M7_relic_pickup_${relic.name}"
        val seconds = ((DungeonListener.currentTime - relicPickupTick) / 20.0).toFixed(2).toDouble()
        val oldPB = PersonalBest.getPB(key)?.toDouble()
        val pbText = when {
            PersonalBest.checkAndSetPB(key, seconds, lowerIsBetter = true) -> " §d§l(PB)"
            oldPB != null -> " §8(§7${oldPB.toFixed(2)}s§8)"
            else -> ""
        }
        return " §8| §7pickup → place §6${seconds}s$pbText"
    }

    //#if CHEAT
    /** NoammAddons a096a6ee: the full-block anvil outline (MixinAnvilBlock), only during P5. */
    @JvmStatic
    fun isFullBlockAnvil(): Boolean = enabled && anvil.value && LocationUtils.F7Phase == 5
    //#endif

    private val placeBlocks = setOf(Blocks.CAULDRON, Blocks.ANVIL)

    private fun heldRelic(): WitherRelic? = WitherRelic.fromName(player.inventory.getItem(8).hoverName.string.removeFormatting())

    private fun isEntityAtCauldron(pos: Vec3, relic: WitherRelic): Boolean {
        val relicVec2 = vec(relic.coords.first, 0, relic.coords.second)
        val entityVec2 = vec(pos.x, 0, pos.z)
        return entityVec2.distanceTo(relicVec2) < 1.5
    }
}