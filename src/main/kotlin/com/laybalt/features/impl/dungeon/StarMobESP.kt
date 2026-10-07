// Ported from Odin (https://github.com/odtheking/Odin), BSD-3-Clause, Copyright (c) 2025 odtheking
// (nametag tick scan, starred regex, box rendering, non-starred name hiding and teammate class glow come from Odin's Highlight module;
// the per-miniboss colours, floor 4 guard and box mode follow Skyblocker's behaviour, re-implemented without its code)
package com.laybalt.features.impl.dungeon

import com.laybalt.config.types.ColorSetting
import com.laybalt.config.types.DropdownSetting
import com.laybalt.config.types.SliderSetting
import com.laybalt.config.types.ToggleSetting
import com.laybalt.event.impl.*
import com.laybalt.features.Feature
import com.laybalt.utils.ChatUtils.formattedText
import com.laybalt.utils.ChatUtils.removeFormatting
import com.laybalt.utils.ColorUtils.withAlpha
import com.laybalt.utils.dungeons.DungeonListener
import com.laybalt.utils.equalsOneOf
import com.laybalt.utils.location.LocationUtils
import com.laybalt.utils.location.LocationUtils.inBoss
import com.laybalt.utils.render.RenderHelper.renderBoundingBox
import com.laybalt.utils.render.world.Render3D.renderBoxBounds
import net.minecraft.client.player.AbstractClientPlayer
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.ExperienceOrb
import net.minecraft.world.entity.ambient.Bat
import net.minecraft.world.entity.boss.wither.WitherBoss
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.monster.EnderMan
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.projectile.arrow.AbstractArrow
import java.awt.Color

object StarMobESP: Feature(
    "Highlights all starred mobs in a dungeon.",
    //#if LEGIT
    //$name = "Star Mob Highlight",
    //#endif
    jsonName = "Star Mob ESP"
) {
    private val espBats by ToggleSetting("Highlight Bats", true).withDescription("Highlights Bats in Dungeons.")
    private val espFels by ToggleSetting("Highlight Fels", false).withDescription("Highlights Fels, even when they are invisible.")

    private val starMobColor by ColorSetting("Star Mob Color", Color.YELLOW, false).section("General Colors").withDescription("Default color for all Starred mobs.")
    private val batColor by ColorSetting("Bat Color", Color.GREEN, false).withDescription("The color used for highlighted bats.").showIf { espBats.value }
    private val felColor by ColorSetting("Fel Color", Color.PINK, false).withDescription("The color used for fels.").showIf { espFels.value }

    private val minibossColors by ToggleSetting("Miniboss Colors", false).section("Minibosses")
        .withDescription("Gives each miniboss (Lost Adventurer, Shadow Assassin, Diamond Guy, King Midas) its own color instead of the Star Mob Color.")
    private val lostAdventurerColor by ColorSetting("Lost Adventurer Color", Color(0xFEE15C), false)
        .withDescription("Highlight color of the Lost Adventurer miniboss.").showIf { minibossColors.value }
    private val shadowAssassinColor by ColorSetting("Shadow Assassin Color", Color(0x5B2CB2), false)
        .withDescription("Highlight color of Shadow Assassins.").showIf { minibossColors.value }
    private val diamondGuyColor by ColorSetting("Diamond Guy Color", Color(0x57C2F7), false)
        .withDescription("Highlight color of the Diamond Guy (Angry Archaeologist) miniboss.").showIf { minibossColors.value }
    private val kingMidasColor by ColorSetting("King Midas Color", Color(0xFFAA00), false)
        .withDescription("Highlight color of King Midas.").showIf { minibossColors.value }

    private val highlightMode by DropdownSetting("Highlight Mode", 0, listOf("Glow", "Box", "Glow + Box")).section("Rendering")
        .withDescription("Glow outlines the mob with the vanilla glow effect. Box draws a box around the mob's hitbox instead of, or in addition to, the glow.")
    private val boxStyle by DropdownSetting("Box Style", 0, listOf("Outline", "Filled", "Filled Outline"))
        .withDescription("How the hitbox boxes are drawn.").showIf { highlightMode.value != 0 }
    private val boxPhase by ToggleSetting("Boxes Through Walls", true)
        .withDescription("Draws the hitbox boxes through walls.").showIf { highlightMode.value != 0 }
    private val boxLineWidth by SliderSetting("Box Line Width", 2f, 1f, 5f, 0.1f)
        .withDescription("Line width of the box outline.").showIf { highlightMode.value != 0 && boxStyle.value != 1 }
    private val boxFillOpacity by SliderSetting("Box Fill Opacity", 25, 0, 100, 1, "%")
        .withDescription("Opacity of the box fill.").showIf { highlightMode.value != 0 && boxStyle.value != 0 }

    private val tickScan by ToggleSetting("Nametag Scan", true).section("Detection")
        .withDescription("Also scans dungeon mob nametags every tick and highlights the mob under every starred nametag. Catches mobs that were missed when their nametag first appeared.")
    private val hideNonStarredNames by ToggleSetting("Hide Non-Starred Names", false)
        .withDescription("Hides the floating nametags of dungeon mobs that are not starred, so only the important ones stay visible.")
    private val teammateClassGlow by ToggleSetting("Teammate Class Glow", false)
        .withDescription("Makes your dungeon teammates glow in their class color (also in the boss room).")

    private val starMobs = HashSet<Int>()
    private val checked = HashSet<Int>()

    private val dungeonMobSpawns = listOf(
        "Lurker", "Dreadlord", "Souleater", "Zombie", "Skeleton", "Skeletor", "Sniper", "Super Archer", "Spider",
        "Fels", "Withermancer", "Lost Adventurer", "Angry Archaeologist", "Frozen Adventurer"
    )
    private val starredRegex = Regex("^.*✯ .*\\d{1,3}(?:,\\d{3})*(?:\\.\\d+)?.?❤$")
    private val minibossNames = setOf("Shadow Assassin", "Lost Adventurer", "Diamond Guy", "King Midas")

    private val glowEnabled get() = highlightMode.value != 1
    private val boxEnabled get() = highlightMode.value != 0

    override fun init() {
        register<MainThreadPacketReceivedEvent.Post> {
            if (! LocationUtils.inDungeon || inBoss) return@register
            if (event.packet !is ClientboundSetEntityDataPacket) return@register
            val entity = level.getEntity(event.packet.id) ?: return@register
            if (entity is ArmorStand) {
                val name = entity.customName?.formattedText ?: return@register
                if (name.endsWith("§c❤") && name.contains("✯")) {
                    checkStarMob(entity, name)
                }
            }
            else if (entity is Player) {
                val name = mc.connection?.getPlayerInfo(entity.uuid)?.profile?.name ?: return@register
                if (name.equalsOneOf("Shadow Assassin", "Lost Adventurer", "Diamond Guy", "King Midas")) {
                    starMobs.add(entity.id)
                }
            }
        }

        register<TickEvent.End> {
            if (! tickScan.value || ! LocationUtils.inDungeon || inBoss) return@register
            for (e in level.entitiesForRendering()) {
                if (e !is ArmorStand || ! e.isAlive) continue
                val name = e.name.string
                if (dungeonMobSpawns.none { it in name } || ! starredRegex.matches(name)) continue
                level.getEntities(e, e.boundingBox.move(0.0, - 1.0, 0.0)) { isValidScanTarget(it) }
                    .firstOrNull()?.let { starMobs.add(it.id) }
            }
        }

        register<CheckEntityRenderEvent> {
            if (! hideNonStarredNames.value || ! LocationUtils.inDungeon || inBoss) return@register
            val stand = event.entity as? ArmorStand ?: return@register
            if (! stand.isInvisible) return@register
            val name = stand.customName?.string ?: return@register
            if (dungeonMobSpawns.none { it in name }) return@register
            if (name.contains("✯")) return@register
            event.isCanceled = true
        }

        register<EntityUnloadEvent> {
            if (! LocationUtils.inDungeon || inBoss) return@register
            starMobs.remove(event.entity.id)
            checked.remove(event.entity.id)
        }

        register<WorldChangeEvent> {
            starMobs.clear()
            checked.clear()
        }

        register<CheckEntityGlowEvent> {
            if (! LocationUtils.inDungeon) return@register

            if (teammateClassGlow.value) getTeammateColor(event.entity)?.let {
                event.color = it
                return@register
            }

            if (inBoss || ! glowEnabled) return@register
            getColor(event.entity)?.let { event.color = it }
        }

        register<RenderWorldEvent> {
            if (! boxEnabled || ! LocationUtils.inDungeon || inBoss) return@register
            val outline = boxStyle.value != 1
            val fill = boxStyle.value != 0
            val fillAlpha = (boxFillOpacity.value * 2.55).toInt()

            for (entity in level.entitiesForRendering()) {
                if (! entity.isAlive) continue
                val color = getColor(entity) ?: continue
                event.ctx.renderBoxBounds(
                    entity.renderBoundingBox, color, color.withAlpha(fillAlpha),
                    outline, fill, boxPhase.value, boxLineWidth.value
                )
            }
        }
    }

    private fun getColor(entity: Entity): Color? {
        if (entity.id in starMobs) {
            if (entity is Player) {
                val name = entity.name.string
                if (name in minibossNames) {
                    if (minibossColors.value) return minibossColor(name)
                }
            }
            return starMobColor.value
        }
        if (entity is Bat) return if (espBats.value && ! entity.isInvisible && ! entity.isPassenger) batColor.value else null
        if (entity is EnderMan) return if (espFels.value && entity.customName?.string == "Dinnerbone") felColor.value else null
        return null
    }

    private fun minibossColor(name: String) = when (name) {
        "Lost Adventurer" -> lostAdventurerColor.value
        "Shadow Assassin" -> shadowAssassinColor.value
        "Diamond Guy" -> diamondGuyColor.value
        "King Midas" -> kingMidasColor.value
        else -> starMobColor.value
    }

    private fun getTeammateColor(entity: Entity): Color? {
        if (entity !is AbstractClientPlayer || entity.uuid.version() != 4) return null
        val name = entity.name.string
        return DungeonListener.dungeonTeammates.find { it.entity?.id == entity.id || it.name == name }?.clazz?.color
    }

    private fun isValidScanTarget(entity: Entity): Boolean = when (entity) {
        is ArmorStand, is WitherBoss, is ExperienceOrb, is AbstractArrow -> false
        is Player -> entity.uuid.version() == 2 && entity != player
        else -> ! entity.isInvisible
    }

    private fun checkStarMob(armorStand: Entity, name: String) {
        if (! checked.add(armorStand.id)) return
        val name = name.removeFormatting().uppercase()
        // withermancers are always -3 to real entity the -1 and -2 are the wither skulls that they shoot
        val offset = if (name.contains("WITHERMANCER")) 3 else 1
        val id = armorStand.id - offset

        val mob = armorStand.level().getEntity(id)
        if (mob !is ArmorStand && id !in starMobs && mob != null) {
            starMobs.add(id)
            return
        }

        val possibleEntities = armorStand.level().getEntities(
            armorStand, armorStand.boundingBox.move(0.0, - 1.0, 0.0)
        ) { it !is ArmorStand && it !is ExperienceOrb }

        possibleEntities.find {
            ! starMobs.contains(it.id) && when (it) {
                is Player -> ! it.isInvisible && it.uuid.version() == 2 && it != player
                is WitherBoss -> false
                is AbstractArrow -> false
                else -> true
            }
        }?.let {
            starMobs.add(it.id)
        }
    }
}
