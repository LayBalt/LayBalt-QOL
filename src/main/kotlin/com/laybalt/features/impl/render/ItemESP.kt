package com.laybalt.features.impl.render

import com.laybalt.config.types.ColorSetting
import com.laybalt.config.types.DropdownSetting
import com.laybalt.config.types.SliderSetting
import com.laybalt.config.types.ToggleSetting
import com.laybalt.event.impl.RenderOverlayEvent
import com.laybalt.event.impl.RenderWorldEvent
import com.laybalt.features.Feature
import com.laybalt.features.impl.render.effects.EffectColors
import com.laybalt.utils.render.Render2D.drawBorder
import com.laybalt.utils.render.Render2D.drawHorizontalGradient
import com.laybalt.utils.render.Render2D.drawRect
import com.laybalt.utils.render.Render2D.drawVerticalGradient
import com.laybalt.utils.render.RenderHelper.renderX
import com.laybalt.utils.render.RenderHelper.renderY
import com.laybalt.utils.render.RenderHelper.renderZ
import com.laybalt.utils.render.WorldToScreen
import com.laybalt.utils.render.world.RenderBatcher
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.item.ItemEntity
import java.awt.Color
import kotlin.math.cos
import kotlin.math.sin

/**
 * Marks dropped items: a name label above each item plus either a screen-space rectangle around it
 * (drawn on the HUD from a world -> screen projection) or a coloured ring on the ground under it.
 */
object ItemESP: Feature("Shows the name of every dropped item and marks it with a box on screen or a ring on the ground.") {
    private const val RECT = 0
    private const val CIRCLE = 1

    private val mode by DropdownSetting("Mode", RECT, listOf("Rect", "Circle", "None"))
        .withDescription("Rect: a rainbow-edged frame around the item on screen. Circle: a ring on the ground under the item. None: only the name label")
    private val range by SliderSetting("Range", 64, 8, 256, 8, " blocks")
        .withDescription("Items farther away than this are ignored")
    private val textColor by ColorSetting("Text Color", Color.WHITE, false)
        .withDescription("Colour of the name label. Names that already have their own colour (e.g. SkyBlock rarities) keep it")
    private val shadow by ToggleSetting("Shadow", true)
        .withDescription("Draws a dark box behind the name label so it stays readable")
    private val shadowColor by ColorSetting("Shadow Color", Color(0, 0, 0, 120))
        .withDescription("Colour of the box behind the name label").showIf { shadow.value }

    private val radius by SliderSetting("Radius", 1f, 0.1f, 5f, 0.1f)
        .withDescription("Ring radius in blocks").section("Circle").showIf { mode.value == CIRCLE }
    private val circlePoints by SliderSetting("Circle Points", 12, 3, 32, 1)
        .withDescription("Number of segments of the ring. Low values give a polygon (3 = triangle)").showIf { mode.value == CIRCLE }
    private val useHudColor by ToggleSetting("Use HUD Color", true)
        .withDescription("Colours the ring with the animated rainbow instead of a fixed colour").showIf { mode.value == CIRCLE }
    private val colorOffset by SliderSetting("Color Offset", 2, 1, 50, 1)
        .withDescription("Hue step between ring segments; higher values squeeze more of the rainbow into one ring")
        .showIf { mode.value == CIRCLE && useHudColor.value }
    private val circleColor by ColorSetting("Circle Color", Color.WHITE)
        .withDescription("Fixed ring colour").showIf { mode.value == CIRCLE && ! useHudColor.value }
    private val throughWalls by ToggleSetting("Through Walls", false)
        .withDescription("Draws the ring on top of blocks").showIf { mode.value == CIRCLE }

    override fun init() {
        register<RenderWorldEvent> {
            if (mode.value != CIRCLE) return@register
            val level = mc.level ?: return@register
            val ctx = event.ctx
            val cam = ctx.camera.pos
            val maxSq = range.value.toDouble().let { it * it }
            val points = circlePoints.value
            val r = radius.value.toDouble()

            ctx.matrixStack.pushPose()
            ctx.matrixStack.translate(cam.reverse())
            val pose = ctx.uMatrixStack()
            val batch = RenderBatcher.lineBatch(throughWalls.value)

            for (entity in level.entitiesForRendering()) {
                if (entity !is ItemEntity || ! entity.isAlive) continue
                val x = entity.renderX
                val y = entity.renderY + 0.01
                val z = entity.renderZ
                val dx = x - cam.x
                val dy = y - cam.y
                val dz = z - cam.z
                if (dx * dx + dy * dy + dz * dz > maxSq) continue

                for (i in 0 until points) {
                    val a0 = i * Math.PI * 2 / points
                    val a1 = (i + 1) * Math.PI * 2 / points
                    val c = if (useHudColor.value) EffectColors.sync(i * colorOffset.value) else circleColor.value
                    batch.line(
                        pose,
                        (x + cos(a0) * r).toFloat(), y.toFloat(), (z + sin(a0) * r).toFloat(),
                        (x + cos(a1) * r).toFloat(), y.toFloat(), (z + sin(a1) * r).toFloat(),
                        c.red / 255f, c.green / 255f, c.blue / 255f, c.alpha / 255f, 2f
                    )
                }
            }
            ctx.matrixStack.popPose()
        }

        register<RenderOverlayEvent> {
            val level = mc.level ?: return@register
            val gui = event.context
            val proj = WorldToScreen.begin(gui) ?: return@register
            val camera = mc.gameRenderer.mainCamera().position()
            val maxSq = range.value.toDouble().let { it * it }

            for (entity in level.entitiesForRendering()) {
                if (entity !is ItemEntity || ! entity.isAlive) continue
                if (entity.distanceToSqr(camera) > maxSq) continue

                val bb = entity.boundingBox
                val ox = entity.renderX - entity.x
                val oy = entity.renderY - entity.y
                val oz = entity.renderZ - entity.z
                val rect = proj.projectBox(
                    bb.minX + ox - 0.05, bb.minY + oy, bb.minZ + oz - 0.05,
                    bb.maxX + ox + 0.05, bb.maxY + oy + 0.15, bb.maxZ + oz + 0.05
                ) ?: continue
                val (x0, y0, x1, y1) = rect
                if (x1 < 0 || y1 < 0 || x0 > proj.width || y0 > proj.height) continue

                if (mode.value == RECT) drawFrame(gui, x0, y0, x1 - x0, y1 - y0)
                drawLabel(gui, entity, (x0 + x1) / 2f, y0 - 10f)
            }
        }
    }

    private fun drawFrame(gui: net.minecraft.client.gui.GuiGraphicsExtractor, x: Float, y: Float, w: Float, h: Float) {
        // Outer black frame, then a thin gradient frame whose corners run through the rainbow.
        gui.drawBorder(x - 1f, y - 1f, w + 2f, h + 2f, Color.BLACK, 1f)
        val tl = EffectColors.sync(270)
        val bl = EffectColors.sync(0)
        val br = EffectColors.sync(180)
        val tr = EffectColors.sync(90)
        val t = 0.5f
        gui.drawVerticalGradient(x, y, t, h, tl, bl)
        gui.drawVerticalGradient(x + w - t, y, t, h, tr, br)
        gui.drawHorizontalGradient(x, y, w, t, tl, tr)
        gui.drawHorizontalGradient(x, y + h - t, w, t, bl, br)
    }

    private fun drawLabel(gui: net.minecraft.client.gui.GuiGraphicsExtractor, entity: ItemEntity, cx: Float, y: Float) {
        val stack = entity.item
        val text = Component.empty().append(stack.hoverName).withStyle(ChatFormatting.BOLD)
        if (stack.count > 1) text.append(Component.literal(" x${stack.count}").withStyle(ChatFormatting.GRAY))

        val scale = 0.75f
        val width = mc.font.width(text) * scale
        val height = 9f * scale
        val left = cx - width / 2f

        if (shadow.value) gui.drawRect(left - 2f, y - 1.5f, width + 4f, height + 2f, shadowColor.value)

        gui.pose().pushMatrix()
        gui.pose().translate(left, y)
        gui.pose().scale(scale, scale)
        gui.text(mc.font, text, 0, 0, textColor.value.rgb or (0xFF shl 24), false)
        gui.pose().popMatrix()
    }
}
