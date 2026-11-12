package com.laybalt.features.impl.dungeon.solvers

import com.laybalt.config.types.ColorSetting
import com.laybalt.config.types.ToggleSetting
import com.laybalt.event.impl.*
import com.laybalt.features.Feature
import com.laybalt.utils.*
import com.laybalt.utils.ChatUtils.unformattedText
import com.laybalt.utils.MathUtils.add
import com.laybalt.utils.NumbersUtils.toFixed
import com.laybalt.utils.Utils.favoriteColor
import com.laybalt.utils.dungeons.DungeonListener.dungeonTeammates
import com.laybalt.utils.location.LocationUtils
import com.laybalt.utils.render.Render2D.drawCenteredString
import com.laybalt.utils.render.RenderHelper.renderVec
import com.laybalt.utils.render.RenderHelper.width
import com.laybalt.utils.render.world.Render3D.renderString
import com.laybalt.utils.render.world.Render3D.renderTracer
import gg.essential.universal.USound
import net.minecraft.client.player.AbstractClientPlayer
import net.minecraft.core.BlockPos
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.level.block.Blocks
import java.util.UUID

object LividSolver: Feature() {
    private val showHp by ToggleSetting("Show HP", true)
    private val tracer by ToggleSetting("Tracer", true)
    private val hideWrong by ToggleSetting("Hide Wrong")
    private val invulnerabilityTimer by ToggleSetting("Invulnerability Timer")
    private val iceSprayTitle by ToggleSetting("Ice Spray Title", true).showIf { invulnerabilityTimer.value }
    private val iceSpraySound by ToggleSetting("Ice Spray Sound", true).showIf { invulnerabilityTimer.value }
    private val highlightColor by ColorSetting("Highlight Color", favoriteColor, false).section("Colors")
    private val tracerColor by ColorSetting("Tracer Color", favoriteColor, false).showIf { tracer.value }

    private val lividMap = mapOf(
        Blocks.WOOL.green() to "Frog Livid",
        Blocks.WOOL.purple() to "Purple Livid",
        Blocks.WOOL.gray() to "Doctor Livid",
        Blocks.WOOL.blue() to "Scream Livid",
        Blocks.WOOL.lime() to "Smile Livid",
        Blocks.WOOL.red() to "Hockey Livid",
        Blocks.WOOL.magenta() to "Crossed Livid",
        Blocks.WOOL.yellow() to "Arcade Livid",
        Blocks.WOOL.white() to "Vendetta Livid"
    )

    private val ceilingWoolBlock = BlockPos(5, 108, 40)
    // NoammAddons 4df108eb: tracked by UUID, numeric entity id 0 threw an IllegalStateException
    private var lividId: UUID? = null

    private const val ticks = 340
    private var timer = - 1

    override fun init() {
        hudElement(
            "Livid Invulnerability Timer",
            enabled = { invulnerabilityTimer.value },
            shouldDraw = { timer > 0 },
            centered = { true }
        ) { ctx, example ->
            val displayTicks = if (example) ticks / 2 else timer
            val color = ColorUtils.colorCodeByPercent(ticks - displayTicks, ticks, true)
            val text = "&5Livid Invulnerability: $color${(displayTicks / 20.0).toFixed(1)}"
            ctx.drawCenteredString(text, 0, 0)
            text.width() to 9f
        }

        register<CheckEntityGlowEvent> {
            if (lividId == event.entity.uuid) {
                event.color = highlightColor.value
            }
        }

        register<CheckEntityRenderEvent> {
            if (! hideWrong.value) return@register
            if (LocationUtils.dungeonFloorNumber != 5) return@register
            if (! LocationUtils.inBoss) return@register
            if (lividId == event.entity.uuid) return@register
            if (lividId.livid?.isSleeping == true) return@register
            if (dungeonTeammates.any { it.entity?.uuid == event.entity.uuid }) return@register
            if (event.entity is ArmorStand && ! event.entity.name.unformattedText.contains("Livid")) return@register
            event.isCanceled = true
        }

        register<RenderWorldEvent> {
            val livid = lividId.livid ?: return@register
            if (livid.isDeadOrDying) return@register
            if (tracer.value) event.ctx.renderTracer(livid.renderVec.add(y = 0.9), tracerColor.value)
            if (showHp.value) event.ctx.renderString(
                ColorUtils.colorCodeByPercent(livid.health, livid.maxHealth) + NumbersUtils.format(livid.health),
                livid.renderVec.add(y = 3.0), scale = 1.5f
            )
        }

        register<TickEvent.Start> {
            if (! LocationUtils.inBoss || LocationUtils.dungeonFloorNumber != 5) return@register
            val targetLivid = lividMap[WorldUtils.getBlockAt(ceilingWoolBlock)] ?: return@register
            val currentLivid = lividId.livid
            if (currentLivid?.gameProfile?.name == targetLivid && currentLivid.isRemoved) return@register
            lividId = level.entitiesForRendering().asSequence().filterIsInstance<AbstractClientPlayer>().find {
                it.gameProfile.name == targetLivid
            }?.uuid
        }

        register<ChatMessageEvent> {
            if (! invulnerabilityTimer.value) return@register
            if (LocationUtils.dungeonFloorNumber != 5) return@register
            if (event.unformattedText != "[BOSS] Livid: Welcome, you've arrived right on time. I am Livid, the Master of Shadows.") return@register
            timer = ticks
        }

        register<TickEvent.Server> {
            if (timer > 0) timer --
            if (timer == 0) {
                if (iceSprayTitle.value) ChatUtils.showTitle("&bIce Spray Livid!")
                if (iceSpraySound.value) USound.playSoundStatic(SoundEvents.NOTE_BLOCK_PLING, 0.25f, 1f)
                timer = - 1
            }
        }

        register<WorldChangeEvent> {
            lividId = null
            timer = - 1
        }
    }

    private val UUID?.livid get() = this?.let { level.getEntity(it) as? AbstractClientPlayer }
}
