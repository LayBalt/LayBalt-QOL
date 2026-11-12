// Blood Assist boxes/timings, Move HUD and Watcher Bar ported from Odin (https://github.com/odtheking/Odin), BSD-3-Clause, Copyright (c) 2025 odtheking
package com.laybalt.features.impl.dungeon

import com.laybalt.config.types.*
import com.laybalt.event.impl.*
import com.laybalt.features.Feature
import com.laybalt.utils.*
import com.laybalt.utils.ChatUtils.unformattedText
import com.laybalt.utils.MathUtils.add
import com.laybalt.utils.NumbersUtils.toFixed
import com.laybalt.utils.dungeons.DungeonListener
import com.laybalt.utils.dungeons.enums.DungeonClass
import com.laybalt.utils.items.ItemUtils
import com.laybalt.utils.location.LocationUtils
import com.laybalt.utils.render.Render2D.drawString
import com.laybalt.utils.render.RenderHelper.renderVec
import com.laybalt.utils.render.RenderHelper.width
import com.laybalt.utils.render.world.Render3D.renderBoxBounds
import com.laybalt.utils.render.world.Render3D.renderLine
import com.laybalt.utils.render.world.Render3D.renderString
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.*
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.monster.zombie.Zombie
import net.minecraft.world.item.Items
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.awt.Color
import java.util.concurrent.*
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToInt

object BloodCamp: Feature("Features for Blood Room.") {
    private val bloodCamp by ToggleSetting("Blood Camp Helper", true).section("Blood Camp Helper")
        .withDescription("Predicts where each blood mob will stop and draws a box, a line and a timer until it spawns.")
    private val decimalPlaces by SliderSetting("Decimal Places", 1, 0, 2, 1).showIf { bloodCamp.value }.withDescription("The number of decimal places shown on blood mob timers")
    private val drawTime by ToggleSetting("Draw Timer", true)
        .withDescription("Shows the time left until the blood mob spawns above its spawn box.")
        .showIf { bloodCamp.value }
    private val colorByTime by ToggleSetting("Color Timer By Time", true)
        .withDescription("Colours the timer by the time left: green above 1.5s, gold above 0.5s, red until spawn, aqua once it should have spawned.")
        .showIf { bloodCamp.value && drawTime.value }
    private val timerColor by ColorSetting("Timer Color", Color.WHITE)
        .withDescription("Colour of the timer text when Color Timer By Time is off.")
        .showIf { bloodCamp.value && drawTime.value && ! colorByTime.value }
    private val boxColor by ColorSetting("Box Color", Color(255, 0, 255))
        .withDescription("Colour of the spawn box (where the mob will stop and spawn).")
        .showIf { bloodCamp.value }
    private val finalColor by ColorSetting("Final Color", Color(0, 170, 170))
        .withDescription("Colour of the spawn box once the mob has (almost) reached it, i.e. the position box and the spawn box have merged. Set alpha to 0 to hide it.")
        .showIf { bloodCamp.value }
    private val positionBox by ToggleSetting("Position Box", true)
        .withDescription("Draws a second box at the mob's current position, shifted forward by your ping, so you can see how far it still has to travel.")
        .showIf { bloodCamp.value }
    private val positionColor by ColorSetting("Position Color", Color(85, 255, 85))
        .withDescription("Colour of the position box.")
        .showIf { bloodCamp.value && positionBox.value }
    private val boxSize by SliderSetting("Box Size", 1.0, 0.1, 1.0, 0.1)
        .withDescription("Size of the boxes. Smaller boxes may look less accurate.")
        .showIf { bloodCamp.value }
    private val drawLine by ToggleSetting("Draw Line", true)
        .withDescription("Draws a line from the mob to its predicted spawn position.")
        .showIf { bloodCamp.value }
    private val lineColor by ColorSetting("Line Color", Color.CYAN).showIf { bloodCamp.value && drawLine.value }
        .withDescription("Colour of the line from the mob to its spawn position.")

    private val offset by SliderSetting("Time Offset", 40, - 100, 100, 1, "ms").section("Blood Camp Advanced")
        .withDescription("Millisecond offset used to decide when the position box has reached the spawn box (Final Color).")
        .showIf { bloodCamp.value }
    private val spawnTick by SliderSetting("Spawn Tick", 38, 35, 41, 1)
        .withDescription("The server tick (after the mob starts moving) at which the mob is assumed to spawn.")
        .showIf { bloodCamp.value }
    private val interpolation by ToggleSetting("Interpolation", true)
        .withDescription("Smoothly moves the boxes between server updates. Less jitter at the cost of some accuracy.")
        .showIf { bloodCamp.value }
    private val pingOffset by ToggleSetting("Ping Offset", true)
        .withDescription("Offsets the position box by your average ping.")
        .showIf { bloodCamp.value && positionBox.value }
    private val manualOffset by SliderSetting("Mob Box Offset", 0f, 0f, 300f, 1f, "ms")
        .withDescription("Manual millisecond offset for the position box when Ping Offset is off.")
        .showIf { bloodCamp.value && positionBox.value && ! pingOffset.value }

    private val killTitle by ToggleSetting("Kill Title").section("Alerts").withDescription("Displays a Title when the blood mobs are ready to be killed. &bNOTE: Not always accurate, you will get better move times by learning when to kill the mobs yourself.")
    private val moveHud by ToggleSetting("Move Timer HUD", true)
        .withDescription("Shows a HUD countdown until the Watcher moves (and the blood mobs can be killed). Move it with the HUD editor.")
    // NoammAddons 8b01571e
    private val mageOnly by ToggleSetting("Mage Only")
        .withDescription("Only shows the Move Timer HUD and the Kill Title (with its chat line) when you are playing Mage.")
        .showIf { killTitle.value || moveHud.value }

    private val watcherBar by ToggleSetting("Watcher Bar", true).section("Watcher Bar")
        .withDescription("Appends the Watcher's remaining health (current/max blood mobs) to its boss bar.")

    //#if CHEAT
    // NoammAddons f1f73680 / 8c3dfc23
    private val bloodTb by ToggleSetting("Blood Mobs Triggerbot").section("Triggerbot")
        .withDescription("Automatically clicks when a blood mob's timer reaches 0 while you are looking at its box.")
        .showIf { bloodCamp.value }
    private val rotate by ToggleSetting("Snap to center")
        .withDescription("Flicks to the center of the blood camp box before clicking. Other players won't see the flick.")
        .showIf { bloodCamp.value && bloodTb.value }
    private val clickType by DropdownSetting("Click Type", 0, listOf("Left Click", "Right Click"))
        .withDescription("Which click the triggerbot sends.")
        .showIf { bloodCamp.value && bloodTb.value }
    private val pingCompensation by ToggleSetting("Ping Compensation")
        .withDescription("Clicks earlier based on your ping so the click reaches the server when the timer ends.")
        .showIf { bloodCamp.value && bloodTb.value }
    //#endif

    private val bloodMobs = ConcurrentHashMap<ArmorStand, BloodEntity>()
    private var watcherEntity: Zombie? = null
    private var firstSpawns = true
    private var moveEndTime: Long? = null
    // NoammAddons 8b01571e: the move alerts can be limited to the Mage, who usually camps blood
    private val shouldShowAlerts get() = ! mageOnly.value || DungeonListener.thePlayer?.clazz == DungeonClass.Mage

    // NoammAddons ad8f81bc: server ticks from the blood room opening until the Watcher moves,
    // indexed by floor (0 = Entrance .. 7 = F7/M7).
    private val moveTimes = intArrayOf(480, 600, 500, 500, 500, 470, 470, 420)
    // Odin's flat 410 ticks (20.5s), used only while the floor is unknown.
    private const val FALLBACK_MOVE_TICKS = 410

    private data class BloodEntity(
        var startVec: Vec3,
        val started: Long,
        var firstSpawn: Boolean = true,
        val deltaHistory: ArrayDeque<Vec3> = ArrayDeque(),
        var lastPosition: Vec3 = startVec,
        var currentVector: Vec3 = startVec,
        var endVector: Vec3? = null,
        var lastEndVector: Vec3? = null,
        var endUpdated: Long = 0L,
        var speedVector: Vec3 = Vec3.ZERO,
        var tbClicked: Boolean = false,
    )

    override fun init() {
        hudElement(
            "Watcher Move Timer",
            enabled = { moveHud.value },
            shouldDraw = { LocationUtils.inDungeon && ! LocationUtils.inBoss && moveTicksLeft() > 0 && shouldShowAlerts }
        ) { ctx, example ->
            val seconds = if (example) 0.5f else moveTicksLeft() / 20f
            val text = "§cMove Timer: ${seconds.toFixed(2)}s"
            ctx.drawString(text, 0, 0)
            return@hudElement text.width() to 9f
        }

        register<ChatMessageEvent> {
            if (LocationUtils.inBoss) return@register
            if (event.unformattedText != "[BOSS] The Watcher: Let's see how you can handle this.") return@register
            firstSpawns = false
        }

        // Since the dungeon update the Watcher moves a fixed, floor-dependent time after the blood room opens.
        // NoammAddons ad8f81bc times it per floor (moveTimes, e.g. F1 600 ticks = 30s, F7 420 = 21s);
        // Odin still uses a flat 410 ticks (20.5s), kept here only as the fallback for an unknown floor.
        register<DungeonEvent.BloodOpenEvent> {
            if (LocationUtils.inBoss) return@register
            val moveTicks = moveTimes.getOrNull(LocationUtils.dungeonFloorNumber ?: - 1) ?: FALLBACK_MOVE_TICKS
            moveEndTime = DungeonListener.currentTime + moveTicks
            if (! killTitle.value) return@register
            if (shouldShowAlerts) ChatUtils.modMessage("Watcher will move in ${(moveTicks / 20f).toFixed(2)}s.")
            ThreadUtils.scheduledTaskServer(moveTicks) {
                if (shouldShowAlerts) ChatUtils.showTitle("&c&lKill Mobs")
            }
        }

        register<MainThreadPacketReceivedEvent.Pre> {
            if (watcherEntity != null || LocationUtils.inBoss) return@register
            val packet = event.packet as? ClientboundSetEquipmentPacket ?: return@register
            val itemStack = packet.slots.find { it.first == EquipmentSlot.HEAD }?.second ?: return@register
            if (itemStack.isEmpty || ! itemStack.`is`(Items.PLAYER_HEAD)) return@register
            if (ItemUtils.getSkullTexture(itemStack) !in watcherSkulls) return@register
            watcherEntity = level.getEntity(packet.entity) as? Zombie
        }

        register<MainThreadPacketReceivedEvent.Pre> {
            if (watcherEntity == null) return@register
            val packet = event.packet as? ClientboundRemoveEntitiesPacket ?: return@register
            if (packet.entityIds.none { it == watcherEntity?.id }) return@register
            watcherEntity = null
        }

        register<MainThreadPacketReceivedEvent.Pre> {
            if (! bloodCamp.value) return@register
            if (LocationUtils.inBoss) return@register
            val packet = event.packet as? ClientboundMoveEntityPacket ?: return@register
            if (packet.xa == 0.toShort() && packet.za == 0.toShort()) return@register
            val entity = packet.getEntity(level) as? ArmorStand ?: return@register
            if (watcherEntity?.let { it.distanceToSqr(entity) <= 400 } != true) return@register
            val item = entity.getItemBySlot(EquipmentSlot.HEAD).takeIf { it.`is`(Items.PLAYER_HEAD) } ?: return@register
            if (ItemUtils.getSkullTexture(item) !in mobSkulls) return@register

            val packetVec = entity.positionCodec.decode(packet.xa.toLong(), packet.ya.toLong(), packet.za.toLong())

            val data = bloodMobs.getOrPut(entity) {
                BloodEntity(packetVec, DungeonListener.currentTime, firstSpawns)
            }

            val delta = packetVec.subtract(data.lastPosition)
            data.lastPosition = packetVec

            if (delta.lengthSqr() > 0) data.deltaHistory.addLast(delta)

            val spawnDistance = if (data.firstSpawn) 16.1 else 11.9
            val totalDelta = data.deltaHistory.fold(Vec3.ZERO) { acc, d -> acc.add(d) }
            val direction = if (totalDelta.lengthSqr() > 0) totalDelta.normalize() else Vec3.ZERO
            val endpoint = data.startVec.add(direction.scale(spawnDistance))

            val timeTookMs = (DungeonListener.currentTime - data.started) * 50.0
            data.speedVector = if (timeTookMs > 0) packetVec.subtract(data.startVec).scale(1.0 / timeTookMs) else Vec3.ZERO
            data.currentVector = packetVec
            data.lastEndVector = data.endVector
            data.endVector = endpoint
            data.endUpdated = System.currentTimeMillis()
        }

        //#if CHEAT
        // NoammAddons f1f73680 / 8c3dfc23: click a blood mob's spawn box when its timer runs out.
        register<TickEvent.Server> {
            if (! bloodCamp.value || ! bloodTb.value) return@register
            if (! LocationUtils.inDungeon || LocationUtils.inBoss) return@register
            if (mc.gui.screen() != null) return@register
            if (bloodMobs.isEmpty()) return@register
            val clickTicks = if (pingCompensation.value) ServerUtils.averagePing / 50.0 else 1.0
            val eye = player.eyePosition

            val (data, box) = bloodMobs.values.firstNotNullOfOrNull {
                if (it.tbClicked) return@firstNotNullOfOrNull null
                val endVector = it.endVector ?: return@firstNotNullOfOrNull null
                if (ticksLeft(it) > clickTicks) return@firstNotNullOfOrNull null
                val box = boxAt(endVector, 1.0)
                val look = player.lookAngle.scale(eye.distanceTo(box.center) + 2)
                if (box.clip(eye, eye.add(look)).isPresent) it to box else null
            } ?: return@register

            if (rotate.value) rotateTo(box.center)
            ThreadUtils.scheduledTaskServer(1) { if (clickType.value == 0) PlayerUtils.leftClick() else PlayerUtils.rightClick() }
            data.tbClicked = true
        }
        //#endif

        register<EntityUnloadEvent> {
            if (! bloodCamp.value) return@register
            bloodMobs.remove(event.entity)
        }

        register<WorldChangeEvent> {
            bloodMobs.clear()
            watcherEntity = null
            firstSpawns = true
            moveEndTime = null
        }

        register<BossBarRenderEvent> {
            if (! watcherBar.value || ! LocationUtils.inDungeon || LocationUtils.inBoss) return@register
            if (event.name.unformattedText != "The Watcher") return@register
            val progress = event.bossEvent.progress
            if (progress < 0.05f) return@register
            val max = 8 + (LocationUtils.dungeonFloorNumber ?: 0)
            event.name = event.name.copy().append(Component.literal(" ${(max * progress).roundToInt()}/$max"))
        }

        register<RenderWorldEvent> {
            if (! bloodCamp.value) return@register
            if (LocationUtils.inBoss) return@register
            val size = boxSize.value
            val now = System.currentTimeMillis()

            bloodMobs.forEach { (entity, data) ->
                if (! entity.isAlive) return@forEach
                val endVector = data.endVector ?: return@forEach

                val endPoint = data.lastEndVector?.takeIf { interpolation.value }?.let { last ->
                    lerp(last, endVector, min(now - data.endUpdated, 100L) / 100.0)
                } ?: endVector

                val timeTookMs = (DungeonListener.currentTime - data.started) * 50
                val timeMs = (if (data.firstSpawn) 2000 else 0) + spawnTick.value * 50 - timeTookMs + offset.value
                val mobOffset = if (pingOffset.value) ServerUtils.averagePing.toDouble() else manualOffset.value.toDouble()
                val merged = mobOffset >= timeMs

                val endAABB = boxAt(endPoint, size)
                if (positionBox.value && ! merged) {
                    val basePos = if (interpolation.value) entity.renderVec else entity.position()
                    val pingPoint = basePos.add(data.speedVector.scale(mobOffset))
                    drawBox(event, boxAt(pingPoint, size), positionColor.value)
                    drawBox(event, endAABB, boxColor.value)
                }
                else drawBox(event, endAABB, if (merged) finalColor.value else boxColor.value)

                if (drawLine.value) event.ctx.renderLine(entity.renderVec.add(y = 2), endPoint.add(y = 2), lineColor.value, phase = true)

                if (drawTime.value) {
                    val seconds = (timeMs - offset.value) / 1000.0
                    val timerText = if (decimalPlaces.value > 0) seconds.toFixed(decimalPlaces.value) else ceil(seconds).toInt().toString()
                    val text = if (colorByTime.value) "${timeColorCode(seconds)}${timerText}s" else timerText
                    val color = if (colorByTime.value) Color.WHITE else timerColor.value
                    event.ctx.renderString(text, endPoint.add(y = 2), color = color, scale = 2f, phase = true)
                }
            }
        }
    }

    //#if CHEAT
    private fun ticksLeft(data: BloodEntity) = (if (data.firstSpawn) 40 else 0) + spawnTick.value - (DungeonListener.currentTime - data.started)

    private fun rotateTo(pos: Vec3) {
        val rot = MathUtils.calcYawPitch(pos)
        PlayerUtils.rotate(rot.yaw, rot.pitch)
    }
    //#endif

    private fun moveTicksLeft() = moveEndTime?.let { it - DungeonListener.currentTime } ?: 0L

    private fun boxAt(pos: Vec3, size: Double) = AABB(
        pos.x - size / 2.0, pos.y + 1.5, pos.z - size / 2.0,
        pos.x + size / 2.0, pos.y + 1.5 + size, pos.z + size / 2.0
    )

    private fun drawBox(event: RenderWorldEvent, aabb: AABB, color: Color) {
        if (color.alpha == 0) return
        event.ctx.renderBoxBounds(aabb, color, fill = false, phase = true)
    }

    private fun lerp(from: Vec3, to: Vec3, t: Double) = Vec3(
        from.x + (to.x - from.x) * t,
        from.y + (to.y - from.y) * t,
        from.z + (to.z - from.z) * t
    )

    private fun timeColorCode(seconds: Double) = when {
        seconds > 1.5 -> "§a"
        seconds in 0.5 .. 1.5 -> "§6"
        seconds in 0.0 .. 0.5 -> "§c"
        else -> "§b"
    }

    private val watcherSkulls = setOf(
        "ewogICJ0aW1lc3RhbXAiIDogMTY5NzMwOTQxNzI1NiwKICAicHJvZmlsZUlkIiA6ICJjYjYxY2U5ODc4ZWI0NDljODA5MzliNWYxNTkwMzE1MiIsCiAgInByb2ZpbGVOYW1lIiA6ICJWb2lkZWRUcmFzaDUxODUiLAogICJzaWduYXR1cmVSZXF1aXJlZCIgOiB0cnVlLAogICJ0ZXh0dXJlcyIgOiB7CiAgICAiU0tJTiIgOiB7CiAgICAgICJ1cmwiIDogImh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNTY2MmI2ZmI0YjhiNTg2ZGM0Y2RmODAzYjA0NDRkOWI0MWQyNDVjZGY2NjhkYWIzOGZhNmMwNjRhZmU4ZTQ2MSIsCiAgICAgICJtZXRhZGF0YSIgOiB7CiAgICAgICAgIm1vZGVsIiA6ICJzbGltIgogICAgICB9CiAgICB9CiAgfQp9",
        "ewogICJ0aW1lc3RhbXAiIDogMTcxOTYwNjM1MjMyMiwKICAicHJvZmlsZUlkIiA6ICI3MmY5MTdjNWQyNDU0OTk0YjlmYzQ1YjVhM2YyMjIzMCIsCiAgInByb2ZpbGVOYW1lIiA6ICJUaGF0X0d1eV9Jc19NZSIsCiAgInNpZ25hdHVyZVJlcXVpcmVkIiA6IHRydWUsCiAgInRleHR1cmVzIiA6IHsKICAgICJTS0lOIiA6IHsKICAgICAgInVybCIgOiAiaHR0cDovL3RleHR1cmVzLm1pbmVjcmFmdC5uZXQvdGV4dHVyZS8yNzM5ZDdmNGU2NmE3ZGIyZWE2Y2Q0MTRlNGM0YmE0MWRmN2E5MjQ1NWM5ZmM0MmNhYWIwMTQ2NjVjMzY3YWQ1IiwKICAgICAgIm1ldGFkYXRhIiA6IHsKICAgICAgICAibW9kZWwiIDogInNsaW0iCiAgICAgIH0KICAgIH0KICB9Cn0=",
        "ewogICJ0aW1lc3RhbXAiIDogMTcxOTYwNjI5MjgzNiwKICAicHJvZmlsZUlkIiA6ICIzZDIxZTYyMTk2NzQ0Y2QwYjM3NjNkNTU3MWNlNGJlZSIsCiAgInByb2ZpbGVOYW1lIiA6ICJTcl83MUJsYWNrYmlyZCIsCiAgInNpZ25hdHVyZVJlcXVpcmVkIiA6IHRydWUsCiAgInRleHR1cmVzIiA6IHsKICAgICJTS0lOIiA6IHsKICAgICAgInVybCIgOiAiaHR0cDovL3RleHR1cmVzLm1pbmVjcmFmdC5uZXQvdGV4dHVyZS9iZjZlMWU3ZWQzNjU4NmMyZDk4MDU3MDAyYmMxYWRjOTgxZTI4ODlmN2JkN2I1YjM4NTJiYzU1Y2M3ODAyMjA0IiwKICAgICAgIm1ldGFkYXRhIiA6IHsKICAgICAgICAibW9kZWwiIDogInNsaW0iCiAgICAgIH0KICAgIH0KICB9Cn0=",
        "ewogICJ0aW1lc3RhbXAiIDogMTY5NzIzODQ0NjgxMiwKICAicHJvZmlsZUlkIiA6ICJmMjc0YzRkNjI1MDQ0ZTQxOGVmYmYwNmM3NWIyMDIxMyIsCiAgInByb2ZpbGVOYW1lIiA6ICJIeXBpZ3NlbCIsCiAgInNpZ25hdHVyZVJlcXVpcmVkIiA6IHRydWUsCiAgInRleHR1cmVzIiA6IHsKICAgICJTS0lOIiA6IHsKICAgICAgInVybCIgOiAiaHR0cDovL3RleHR1cmVzLm1pbmVjcmFmdC5uZXQvdGV4dHVyZS80Y2VjNDAwMDhlMWMzMWMxOTg0ZjRkNjUwYWJiMzQxMGYyMDM3MTE5ZmQ2MjRhZmM5NTM1NjNiNzM1MTVhMDc3IiwKICAgICAgIm1ldGFkYXRhIiA6IHsKICAgICAgICAibW9kZWwiIDogInNsaW0iCiAgICAgIH0KICAgIH0KICB9Cn0=",
        "ewogICJ0aW1lc3RhbXAiIDogMTcxOTYwNjAwOTg2NywKICAicHJvZmlsZUlkIiA6ICJiMGQ0YjI4YmMxZDc0ODg5YWYwZTg2NjFjZWU5NmFhYiIsCiAgInByb2ZpbGVOYW1lIiA6ICJNaW5lU2tpbl9vcmciLAogICJzaWduYXR1cmVSZXF1aXJlZCIgOiB0cnVlLAogICJ0ZXh0dXJlcyIgOiB7CiAgICAiU0tJTiIgOiB7CiAgICAgICJ1cmwiIDogImh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvYjM3ZGQxOGI1OTgzYTc2N2U1NTZkYzY0NDI0YWY0YjlhYmRiNzVkNGM5ZThiMDk3ODE4YWZiYzQzMWJmMGUwOSIsCiAgICAgICJtZXRhZGF0YSIgOiB7CiAgICAgICAgIm1vZGVsIiA6ICJzbGltIgogICAgICB9CiAgICB9CiAgfQp9",
        "ewogICJ0aW1lc3RhbXAiIDogMTcxOTYwNTkyNDIwNSwKICAicHJvZmlsZUlkIiA6ICIzZDIxZTYyMTk2NzQ0Y2QwYjM3NjNkNTU3MWNlNGJlZSIsCiAgInByb2ZpbGVOYW1lIiA6ICJTcl83MUJsYWNrYmlyZCIsCiAgInNpZ25hdHVyZVJlcXVpcmVkIiA6IHRydWUsCiAgInRleHR1cmVzIiA6IHsKICAgICJTS0lOIiA6IHsKICAgICAgInVybCIgOiAiaHR0cDovL3RleHR1cmVzLm1pbmVjcmFmdC5uZXQvdGV4dHVyZS9mNWYwZDc4ZmUzOGQxZDdmNzVmMDhjZGNmMmExODU1ZDZkYTAzMzdlMTE0YTNjNjNlM2JmM2M2MThiYzczMmIwIiwKICAgICAgIm1ldGFkYXRhIiA6IHsKICAgICAgICAibW9kZWwiIDogInNsaW0iCiAgICAgIH0KICAgIH0KICB9Cn0=",
        "ewogICJ0aW1lc3RhbXAiIDogMTU4OTU1MDkyNjM2MSwKICAicHJvZmlsZUlkIiA6ICI0ZDcwNDg2ZjUwOTI0ZDMzODZiYmZjOWMxMmJhYjRhZSIsCiAgInByb2ZpbGVOYW1lIiA6ICJzaXJGYWJpb3pzY2hlIiwKICAic2lnbmF0dXJlUmVxdWlyZWQiIDogdHJ1ZSwKICAidGV4dHVyZXMiIDogewogICAgIlNLSU4iIDogewogICAgICAidXJsIiA6ICJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlLzUxOTY3ZGI1ZTMxOTk5MTYyNTIwMjE5MDNjZjRlOTk1MmVmN2NlYzIyMGZhYWNhMWJhNzliYWZlNTkzOGJkODAiCiAgICB9CiAgfQp9",
        "ewogICJ0aW1lc3RhbXAiIDogMTcxOTYwNjIxMjc1NSwKICAicHJvZmlsZUlkIiA6ICI2NGRiNmMwNTliOTk0OTM2YTY0M2QwODEwODE0ZmJkMyIsCiAgInByb2ZpbGVOYW1lIiA6ICJUaGVTaWx2ZXJEcmVhbXMiLAogICJzaWduYXR1cmVSZXF1aXJlZCIgOiB0cnVlLAogICJ0ZXh0dXJlcyIgOiB7CiAgICAiU0tJTiIgOiB7CiAgICAgICJ1cmwiIDogImh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvOWZkNjFlODA1NWY2ZWU5N2FiNWI2MTk2YThkN2VjOTgwNzhhYzM3ZTAwMzc2MTU3YjZiNTIwZWFhYTJmOTNhZiIsCiAgICAgICJtZXRhZGF0YSIgOiB7CiAgICAgICAgIm1vZGVsIiA6ICJzbGltIgogICAgICB9CiAgICB9CiAgfQp9",
        "ewogICJ0aW1lc3RhbXAiIDogMTcxOTYwNjIzOTU4NiwKICAicHJvZmlsZUlkIiA6ICJhYWZmMDUwYTExOTk0NzM1YjEyNDVlNDk0MGFlZjY4NCIsCiAgInByb2ZpbGVOYW1lIiA6ICJMYXN0SW1tb3J0YWwiLAogICJzaWduYXR1cmVSZXF1aXJlZCIgOiB0cnVlLAogICJ0ZXh0dXJlcyIgOiB7CiAgICAiU0tJTiIgOiB7CiAgICAgICJ1cmwiIDogImh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZTVjMWRjNDdhMDRjZTU3MDAxYThiNzI2ZjAxOGNkZWY0MGI3ZWE5ZDdiZDZkODM1Y2E0OTVhMGVmMTY5Zjg5MyIsCiAgICAgICJtZXRhZGF0YSIgOiB7CiAgICAgICAgIm1vZGVsIiA6ICJzbGltIgogICAgICB9CiAgICB9CiAgfQp9"
    )

    private val mobSkulls = setOf(
        "eyJ0aW1lc3RhbXAiOjE1ODYwNDEwNjQwNTAsInByb2ZpbGVJZCI6ImRhNDk4YWM0ZTkzNzRlNWNiNjEyN2IzODA4NTU3OTgzIiwicHJvZmlsZU5hbWUiOiJOaXRyb2hvbGljXzIiLCJzaWduYXR1cmVSZXF1aXJlZCI6dHJ1ZSwidGV4dHVyZXMiOnsiU0tJTiI6eyJ1cmwiOiJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlLzVhNzk4NjBhY2E3OTk0MDdjMGZhYTEwYjFiYmNmNDI5OThmYWQ0ZWJjZjMxZDdhMjE0MTgwODI2YjRhYzk0ZTEifX19",
        "eyJ0aW1lc3RhbXAiOjE1ODYwNDExODY2MzYsInByb2ZpbGVJZCI6ImRhNDk4YWM0ZTkzNzRlNWNiNjEyN2IzODA4NTU3OTgzIiwicHJvZmlsZU5hbWUiOiJOaXRyb2hvbGljXzIiLCJzaWduYXR1cmVSZXF1aXJlZCI6dHJ1ZSwidGV4dHVyZXMiOnsiU0tJTiI6eyJ1cmwiOiJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlLzQ3NzQ4NzExOTBjODc4YzlhMmM0NDk2YzFlMTAyNTdjNmM0ZWExMzgwN2Q3MmMxNWQ3YWM2YWIzYTdhOWE4ZGMifX19",
        "eyJ0aW1lc3RhbXAiOjE1ODYwNDAyMDM1NzMsInByb2ZpbGVJZCI6ImRhNDk4YWM0ZTkzNzRlNWNiNjEyN2IzODA4NTU3OTgzIiwicHJvZmlsZU5hbWUiOiJOaXRyb2hvbGljXzIiLCJzaWduYXR1cmVSZXF1aXJlZCI6dHJ1ZSwidGV4dHVyZXMiOnsiU0tJTiI6eyJ1cmwiOiJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlL2Y0NjI0YTlhOGM2OWNhMjA0NTA0YWJiMDQzZDQ3NDU2Y2Q5YjA5NzQ5YTM2MzU3NDYyMzAzZjI3NmEyMjlkNCJ9fX0=",
        "eyJ0aW1lc3RhbXAiOjE1ODYwNDExNDUyMjIsInByb2ZpbGVJZCI6ImRhNDk4YWM0ZTkzNzRlNWNiNjEyN2IzODA4NTU3OTgzIiwicHJvZmlsZU5hbWUiOiJOaXRyb2hvbGljXzIiLCJzaWduYXR1cmVSZXF1aXJlZCI6dHJ1ZSwidGV4dHVyZXMiOnsiU0tJTiI6eyJ1cmwiOiJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlL2M5MTllNWI4ZDU2ZjA2MmEyMWQyMjRkZTE0YWY3NzFlMmY1NWQwOWI1OWU3YjA5OWQwOWRhYTU3NTQwYjc5Y2YiLCJtZXRhZGF0YSI6eyJtb2RlbCI6InNsaW0ifX19fQ==",
        "eyJ0aW1lc3RhbXAiOjE1ODYwNDA1MzgzODIsInByb2ZpbGVJZCI6ImRhNDk4YWM0ZTkzNzRlNWNiNjEyN2IzODA4NTU3OTgzIiwicHJvZmlsZU5hbWUiOiJOaXRyb2hvbGljXzIiLCJzaWduYXR1cmVSZXF1aXJlZCI6dHJ1ZSwidGV4dHVyZXMiOnsiU0tJTiI6eyJ1cmwiOiJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlL2E4OWY2MzAzYWY4NTg3NzYxMDkxMmRjMDRiOGIxZTg5NzI0NzUyZjBhN2VlYTA1YWI2NTQ3ZTIyODE3OWMwNmYiLCJtZXRhZGF0YSI6eyJtb2RlbCI6InNsaW0ifX19fQ==",
        "eyJ0aW1lc3RhbXAiOjE1ODYwNDA5ODk1NTgsInByb2ZpbGVJZCI6ImRhNDk4YWM0ZTkzNzRlNWNiNjEyN2IzODA4NTU3OTgzIiwicHJvZmlsZU5hbWUiOiJOaXRyb2hvbGljXzIiLCJzaWduYXR1cmVSZXF1aXJlZCI6dHJ1ZSwidGV4dHVyZXMiOnsiU0tJTiI6eyJ1cmwiOiJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlLzY3MjM3ZWRkYWViZGJiZGFhY2ZhOTEyODg1NTYwY2NkYzY1ZGE5M2I0YzNkNTEzNTMyODY4ZWMyM2JiNWI0NDgifX19",
        "eyJ0aW1lc3RhbXAiOjE1ODYwNDA0OTUwMjgsInByb2ZpbGVJZCI6ImRhNDk4YWM0ZTkzNzRlNWNiNjEyN2IzODA4NTU3OTgzIiwicHJvZmlsZU5hbWUiOiJOaXRyb2hvbGljXzIiLCJzaWduYXR1cmVSZXF1aXJlZCI6dHJ1ZSwidGV4dHVyZXMiOnsiU0tJTiI6eyJ1cmwiOiJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlL2ZmMTg0YzE5ZTcyNTYyM2QzMjgyOGEwYTRlNzQxZTg2ZjEzNWFjNjNkYmM4MjhmZjNjODQ2ODMzOGYzNjgzYiJ9fX0=",
        "eyJ0aW1lc3RhbXAiOjE1ODYwNDEwMzA3NjUsInByb2ZpbGVJZCI6ImRhNDk4YWM0ZTkzNzRlNWNiNjEyN2IzODA4NTU3OTgzIiwicHJvZmlsZU5hbWUiOiJOaXRyb2hvbGljXzIiLCJzaWduYXR1cmVSZXF1aXJlZCI6dHJ1ZSwidGV4dHVyZXMiOnsiU0tJTiI6eyJ1cmwiOiJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlLzVjY2NkNTNmNTE5MWMyOWE5ZGM4ZjAxNzBmYmRjNGU1OWU2NjQ3NmFhZTMzZGUyN2I0NjhmMWRlMWI3Y2YzYjIifX19",
        "eyJ0aW1lc3RhbXAiOjE1ODYwNDA5MTc4NzYsInByb2ZpbGVJZCI6ImRhNDk4YWM0ZTkzNzRlNWNiNjEyN2IzODA4NTU3OTgzIiwicHJvZmlsZU5hbWUiOiJOaXRyb2hvbGljXzIiLCJzaWduYXR1cmVSZXF1aXJlZCI6dHJ1ZSwidGV4dHVyZXMiOnsiU0tJTiI6eyJ1cmwiOiJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlL2I1YmE3NmUwMmNhYjcyZmE3ZDhhYzU0Y2VlYzg0OTk3NmFiMGIwMGEwMTA2OGQ2OGMyNjY3NjZiZjcwYzM5OTcifX19",
        "eyJ0aW1lc3RhbXAiOjE1ODYwNDA3Njk2MTQsInByb2ZpbGVJZCI6ImRhNDk4YWM0ZTkzNzRlNWNiNjEyN2IzODA4NTU3OTgzIiwicHJvZmlsZU5hbWUiOiJOaXRyb2hvbGljXzIiLCJzaWduYXR1cmVSZXF1aXJlZCI6dHJ1ZSwidGV4dHVyZXMiOnsiU0tJTiI6eyJ1cmwiOiJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlL2FhMjNjOGNkZTI5NDNjODQyNDlkZTgzNTFiYzM1NDBiZTVmOGFmYWFiYThiMmNiMDMyZmM1YWNhZDc4YTI2OWIifX19",
        "eyJ0aW1lc3RhbXAiOjE1ODYwNDA4MTg4MDMsInByb2ZpbGVJZCI6ImRhNDk4YWM0ZTkzNzRlNWNiNjEyN2IzODA4NTU3OTgzIiwicHJvZmlsZU5hbWUiOiJOaXRyb2hvbGljXzIiLCJzaWduYXR1cmVSZXF1aXJlZCI6dHJ1ZSwidGV4dHVyZXMiOnsiU0tJTiI6eyJ1cmwiOiJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlLzkxNzFmMzViOGY1MDgxNDJiZDhjNjU0MTdkMGYzMjQxNTNhYjkxNDc3MzllZTRkMTBkZWE3MzNjYzgwZWFhMjAifX19",
        "eyJ0aW1lc3RhbXAiOjE1ODYwNDA5NTY0MjIsInByb2ZpbGVJZCI6ImRhNDk4YWM0ZTkzNzRlNWNiNjEyN2IzODA4NTU3OTgzIiwicHJvZmlsZU5hbWUiOiJOaXRyb2hvbGljXzIiLCJzaWduYXR1cmVSZXF1aXJlZCI6dHJ1ZSwidGV4dHVyZXMiOnsiU0tJTiI6eyJ1cmwiOiJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlLzdkMTJiMmFkZTQxM2E2Y2Q3Y2NhM2M5NWU5NjFiYTlmMGFlNzE2NWZhNDFmYzdiNWQ1ZjA5NGEwMTI0MGM2MDkifX19",
        "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvOTZjM2UzMWNmYzY2NzMzMjc1YzQyZmNmYjVkOWE0NDM0MmQ2NDNiNTVjZDE0YzljNzdkMjczYTIzNTIifX19",
        "ewogICJ0aW1lc3RhbXAiIDogMTU4OTkyMzE2OTIxMSwKICAicHJvZmlsZUlkIiA6ICJhMmY4MzQ1OTVjODk0YTI3YWRkMzA0OTcxNmNhOTEwYyIsCiAgInByb2ZpbGVOYW1lIiA6ICJiUHVuY2giLAogICJzaWduYXR1cmVSZXF1aXJlZCIgOiB0cnVlLAogICJ0ZXh0dXJlcyIgOiB7CiAgICAiU0tJTiIgOiB7CiAgICAgICJ1cmwiIDogImh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvODQyMWJhNWI4ZTM1NzNlZjk3YmViNWI0MGUxNWQxNWIyMGYzMDYzMWM0YzUzMzBjM2RlZGEzMDQ3ZGYwZTkyIgogICAgfQogIH0KfQ==",
        "ewogICJ0aW1lc3RhbXAiIDogMTU4OTkyMzExMjUwMCwKICAicHJvZmlsZUlkIiA6ICJhMmY4MzQ1OTVjODk0YTI3YWRkMzA0OTcxNmNhOTEwYyIsCiAgInByb2ZpbGVOYW1lIiA6ICJiUHVuY2giLAogICJzaWduYXR1cmVSZXF1aXJlZCIgOiB0cnVlLAogICJ0ZXh0dXJlcyIgOiB7CiAgICAiU0tJTiIgOiB7CiAgICAgICJ1cmwiIDogImh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvYWQyMjc3MmY3NjkwNDVmZGM1YmU4MTlhZDY4YjAxYTk3YWMwNGM2MDg4NmQyY2E3YWZlZTM5YjI4MmY3YTM4MyIKICAgIH0KICB9Cn0=",
        "ewogICJ0aW1lc3RhbXAiIDogMTU4OTkyMzM4Njc5NCwKICAicHJvZmlsZUlkIiA6ICJhMmY4MzQ1OTVjODk0YTI3YWRkMzA0OTcxNmNhOTEwYyIsCiAgInByb2ZpbGVOYW1lIiA6ICJiUHVuY2giLAogICJzaWduYXR1cmVSZXF1aXJlZCIgOiB0cnVlLAogICJ0ZXh0dXJlcyIgOiB7CiAgICAiU0tJTiIgOiB7CiAgICAgICJ1cmwiIDogImh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvYWQ2N2Y5N2Q3ZjgyMTcyOWJlYjM0YTgyYzNmMTM1OTJiNDA0MzlmZTUyNDhlNzI1NzZmZGU3YWExODBiZjc3IgogICAgfQogIH0KfQ==",
        "ewogICJ0aW1lc3RhbXAiIDogMTU4OTkyMzIxNTkwNSwKICAicHJvZmlsZUlkIiA6ICJhMmY4MzQ1OTVjODk0YTI3YWRkMzA0OTcxNmNhOTEwYyIsCiAgInByb2ZpbGVOYW1lIiA6ICJiUHVuY2giLAogICJzaWduYXR1cmVSZXF1aXJlZCIgOiB0cnVlLAogICJ0ZXh0dXJlcyIgOiB7CiAgICAiU0tJTiIgOiB7CiAgICAgICJ1cmwiIDogImh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZmIzOTczYTc1MmIyNGEyZjNhYmIwMDM0MjdmNmRiZTZjYTNhNjFkYjBhMWJjZjM1MWM2ZWFiMjdlYzI3ZTUwIgogICAgfQogIH0KfQ==",
        "eyJ0aW1lc3RhbXAiOjE1NzQ0MTkzMTAxNjQsInByb2ZpbGVJZCI6Ijc1MTQ0NDgxOTFlNjQ1NDY4Yzk3MzlhNmUzOTU3YmViIiwicHJvZmlsZU5hbWUiOiJUaGFua3NNb2phbmciLCJzaWduYXR1cmVSZXF1aXJlZCI6dHJ1ZSwidGV4dHVyZXMiOnsiU0tJTiI6eyJ1cmwiOiJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlLzEyNzE2ZWNiZjViOGRhMDBiMDVmMzE2ZWM2YWY2MWU4YmQwMjgwNWIyMWViOGU0NDAxNTE0NjhkYzY1NjU0OWMifX19",
        "ewogICJ0aW1lc3RhbXAiIDogMTU4OTkyMzAyODAxNSwKICAicHJvZmlsZUlkIiA6ICJhMmY4MzQ1OTVjODk0YTI3YWRkMzA0OTcxNmNhOTEwYyIsCiAgInByb2ZpbGVOYW1lIiA6ICJiUHVuY2giLAogICJzaWduYXR1cmVSZXF1aXJlZCIgOiB0cnVlLAogICJ0ZXh0dXJlcyIgOiB7CiAgICAiU0tJTiIgOiB7CiAgICAgICJ1cmwiIDogImh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvMzI2MDMyNTE3MWE3YmE4NDYwODMwYzBlZWE1MTVjNzU3YTY2NWU1YjE2YTE0MjA3YmExYTMxODI3NTJiZWU4NyIKICAgIH0KICB9Cn0=",
        "ewogICJ0aW1lc3RhbXAiIDogMTU5NTQyODIyMDAyMCwKICAicHJvZmlsZUlkIiA6ICJkYTQ5OGFjNGU5Mzc0ZTVjYjYxMjdiMzgwODU1Nzk4MyIsCiAgInByb2ZpbGVOYW1lIiA6ICJOaXRyb2hvbGljXzIiLAogICJzaWduYXR1cmVSZXF1aXJlZCIgOiB0cnVlLAogICJ0ZXh0dXJlcyIgOiB7CiAgICAiU0tJTiIgOiB7CiAgICAgICJ1cmwiIDogImh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNjJkOGZkM2FhNTYxN2IxZGFjMGFhZTljODFmNmRkNzBhZDkzYTU5OTQyZjQ2MGQyN2U0ZDU1YTVjYjg5MThlOCIKICAgIH0KICB9Cn0=",
        "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNTZmYzg1NGJiODRjZjRiNzY5NzI5Nzk3M2UwMmI3OWJjMTA2OTg0NjBiNTFhNjM5YzYwZTVlNDE3NzM0ZTExIn19fQ==",
        "ewogICJ0aW1lc3RhbXAiIDogMTU4OTc5MzA2ODgzOSwKICAicHJvZmlsZUlkIiA6ICIyYzEwNjRmY2Q5MTc0MjgyODRlM2JmN2ZhYTdlM2UxYSIsCiAgInByb2ZpbGVOYW1lIiA6ICJOYWVtZSIsCiAgInNpZ25hdHVyZVJlcXVpcmVkIiA6IHRydWUsCiAgInRleHR1cmVzIiA6IHsKICAgICJTS0lOIiA6IHsKICAgICAgInVybCIgOiAiaHR0cDovL3RleHR1cmVzLm1pbmVjcmFmdC5uZXQvdGV4dHVyZS83ZGU3YmJiZGYyMmJmZTE3OTgwZDRlMjA2ODdlMzg2ZjExZDU5ZWUxZGI2ZjhiNDc2MjM5MWI3OWE1YWM1MzJkIgogICAgfQogIH0KfQ==",
        "ewogICJ0aW1lc3RhbXAiIDogMTU5ODk3NzI1OTM1NywKICAicHJvZmlsZUlkIiA6ICJlNzkzYjJjYTdhMmY0MTI2YTA5ODA5MmQ3Yzk5NDE3YiIsCiAgInByb2ZpbGVOYW1lIiA6ICJUaGVfSG9zdGVyX01hbiIsCiAgInNpZ25hdHVyZVJlcXVpcmVkIiA6IHRydWUsCiAgInRleHR1cmVzIiA6IHsKICAgICJTS0lOIiA6IHsKICAgICAgInVybCIgOiAiaHR0cDovL3RleHR1cmVzLm1pbmVjcmFmdC5uZXQvdGV4dHVyZS9jMTAwN2M1YjcxMTRhYmVjNzM0MjA2ZDRmYzYxM2RhNGYzYTBlOTlmNzFmZjk0OWNlZGFkYzk5MDc5MTM1YTBiIgogICAgfQogIH0KfQ=="
    )
}