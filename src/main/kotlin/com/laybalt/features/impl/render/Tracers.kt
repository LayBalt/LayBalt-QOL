package com.laybalt.features.impl.render

import com.laybalt.config.types.ColorSetting
import com.laybalt.config.types.MultiCheckboxSetting
import com.laybalt.config.types.SliderSetting
import com.laybalt.config.types.ToggleSetting
import com.laybalt.event.impl.RenderWorldEvent
import com.laybalt.features.Feature
import com.laybalt.utils.PartyUtils
import com.laybalt.utils.render.RenderHelper.renderX
import com.laybalt.utils.render.RenderHelper.renderY
import com.laybalt.utils.render.RenderHelper.renderZ
import com.laybalt.utils.render.world.RenderBatcher
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.Vec3
import java.awt.Color

/**
 * Lines from the centre of your screen to other players (and optionally mobs / dropped items).
 * The start point sits just in front of the camera along the view direction, so every line seems
 * to come out of the crosshair.
 */
object Tracers: Feature("Draws lines from your crosshair to other players, so you always know where they are.") {
    private val targets by MultiCheckboxSetting("Targets", mutableMapOf("Players" to true, "Mobs" to false, "Items" to false))
        .withDescription("What the lines point to")
    private val ignoreNpcs by ToggleSetting("Ignore NPCs", true)
        .withDescription("Skips player-shaped NPCs (Hypixel NPCs are fake players with a non-standard UUID)")
    private val height by SliderSetting("Height", 0f, 0f, 2f, 0.05f, " blocks")
        .withDescription("How far above the target's feet the line ends. 0 = feet, about 1 = body, about 1.6 = head")
    private val thickness by SliderSetting("Thickness", 1.5f, 0.5f, 5f, 0.25f)
        .withDescription("Line width")
    private val throughWalls by ToggleSetting("Through Walls", true)
        .withDescription("Lines are drawn on top of blocks. Turn off to let walls hide them")
    private val range by SliderSetting("Range", 128, 8, 512, 8, " blocks")
        .withDescription("Targets farther away than this get no line")

    private val color by ColorSetting("Color", Color(0xFF, 0x00, 0x00, 0x93))
        .withDescription("Colour of lines to players").section("Colors")
    private val friendColor by ColorSetting("Friends", Color(0x17, 0xDE, 0x5D, 0x93))
        .withDescription("Colour of lines to members of your party")
    private val mobColor by ColorSetting("Mob Color", Color(0xFF, 0xAA, 0x00, 0x93))
        .withDescription("Colour of lines to mobs").showIf { targets.value["Mobs"] == true }
    private val itemColor by ColorSetting("Item Color", Color(0x55, 0xBB, 0xFF, 0x93))
        .withDescription("Colour of lines to dropped items").showIf { targets.value["Items"] == true }

    override fun init() {
        register<RenderWorldEvent> {
            val level = mc.level ?: return@register
            val self = mc.player ?: return@register
            val ctx = event.ctx
            val cam = ctx.camera.pos
            val start = cam.add(Vec3.directionFromRotation(ctx.camera.xRot, ctx.camera.yRot))
            val maxSq = range.value.toDouble().let { it * it }
            val wantPlayers = targets.value["Players"] == true
            val wantMobs = targets.value["Mobs"] == true
            val wantItems = targets.value["Items"] == true
            val width = thickness.value
            val yOff = height.value.toDouble()

            ctx.matrixStack.pushPose()
            ctx.matrixStack.translate(cam.reverse())
            val pose = ctx.uMatrixStack()
            val batch = RenderBatcher.lineBatch(throughWalls.value)

            for (entity in level.entitiesForRendering()) {
                if (entity === self || ! entity.isAlive) continue
                val c = colorFor(entity, wantPlayers, wantMobs, wantItems) ?: continue
                val x = entity.renderX
                val y = entity.renderY + yOff
                val z = entity.renderZ
                val dx = x - cam.x
                val dy = y - cam.y
                val dz = z - cam.z
                if (dx * dx + dy * dy + dz * dz > maxSq) continue

                batch.line(
                    pose,
                    start.x.toFloat(), start.y.toFloat(), start.z.toFloat(),
                    x.toFloat(), y.toFloat(), z.toFloat(),
                    c.red / 255f, c.green / 255f, c.blue / 255f, c.alpha / 255f, width
                )
            }
            ctx.matrixStack.popPose()
        }
    }

    private fun colorFor(entity: Entity, players: Boolean, mobs: Boolean, items: Boolean): Color? = when {
        entity is Player -> when {
            ! players || entity.isSpectator -> null
            ignoreNpcs.value && isNpc(entity) -> null
            isFriend(entity) -> friendColor.value
            else -> color.value
        }
        entity is ItemEntity -> if (items) itemColor.value else null
        entity is ArmorStand -> null
        entity is LivingEntity -> if (mobs && ! entity.isInvisible) mobColor.value else null
        else -> null
    }

    /**
     * Hypixel's NPCs and SkyBlock fake players use version-2 UUIDs; real online-mode players have version 4
     * and offline-mode players version 3, so only version 2 counts as an NPC.
     */
    @JvmStatic
    fun isNpc(player: Player) = player.uuid.version() == 2

    @JvmStatic
    fun isFriend(player: Player): Boolean {
        val name = player.gameProfile.name
        return PartyUtils.members.any { it.equals(name, ignoreCase = true) }
    }
}
