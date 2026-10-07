package com.laybalt.features.impl.render

import com.laybalt.config.types.ButtonSetting
import com.laybalt.config.types.ColorSetting
import com.laybalt.config.types.DropdownSetting
import com.laybalt.config.types.SliderSetting
import com.laybalt.config.types.ToggleSetting
import com.laybalt.event.impl.RenderWorldEvent
import com.laybalt.event.impl.TickEvent
import com.laybalt.event.impl.WorldChangeEvent
import com.laybalt.features.Feature
import com.laybalt.features.impl.render.effects.EffectColors
import com.laybalt.utils.render.RenderHelper.renderX
import com.laybalt.utils.render.RenderHelper.renderY
import com.laybalt.utils.render.RenderHelper.renderZ
import com.laybalt.utils.render.world.RenderBatcher
import java.awt.Color

/**
 * Draws the path you walked as a line on the ground (at your feet). Points are stored per world
 * and cleared on world change or when the feature is turned off.
 */
object BreadCrumbs: Feature("Draws the path you have walked as a line that follows your feet.") {
    private val throughWalls by ToggleSetting("Through Walls", true)
        .withDescription("Draws the path on top of blocks so you can see it behind walls")
    private val listLimit by SliderSetting("List Limit", 1000, 10, 99999, 10)
        .withDescription("Maximum number of path points kept. When the limit is reached the oldest points are dropped")
    private val lineWidth by SliderSetting("Line Width", 1.5f, 1f, 5f, 0.5f)
        .withDescription("Thickness of the path line")
    private val colorMode by DropdownSetting("Color Mode", 1, listOf("Custom", "Rainbow"))
        .withDescription("Custom draws the path in one colour; Rainbow colours it with an animated rainbow along its length")
    private val color by ColorSetting("Color", Color(0x37, 0xB0, 0xBA))
        .withDescription("Path colour in Custom mode").showIf { colorMode.value == 0 }
    private val clearButton by ButtonSetting("Clear Path") { points.clear() }
        .withDescription("Removes the path drawn so far")

    /** x, y, z triples. */
    private val points = ArrayDeque<DoubleArray>()

    override fun init() {
        register<WorldChangeEvent> { points.clear() }

        register<TickEvent.End> {
            val player = mc.player ?: return@register
            val x = player.x
            val y = player.boundingBox.minY
            val z = player.z
            val last = points.lastOrNull()
            if (last != null) {
                val dx = last[0] - x
                val dy = last[1] - y
                val dz = last[2] - z
                if (dx * dx + dy * dy + dz * dz < 0.05 * 0.05) return@register
            }
            points.addLast(doubleArrayOf(x, y, z))
            while (points.size > listLimit.value) points.removeFirst()
        }

        register<RenderWorldEvent> {
            if (points.isEmpty()) return@register
            val player = mc.player ?: return@register
            val ctx = event.ctx
            val cam = ctx.camera.pos

            ctx.matrixStack.pushPose()
            ctx.matrixStack.translate(cam.reverse())
            val pose = ctx.uMatrixStack()
            val batch = RenderBatcher.lineBatch(throughWalls.value)
            val width = lineWidth.value
            val custom = color.value
            val rainbow = colorMode.value == 1

            // The live end follows the interpolated player position so the line does not lag behind.
            val tail = doubleArrayOf(player.renderX, player.renderY, player.renderZ)
            val count = points.size
            var prev: DoubleArray? = null
            for (i in 0 .. count) {
                val p = if (i < count) points[i] else tail
                val from = prev
                prev = p
                if (from == null || from[0] == p[0] && from[1] == p[1] && from[2] == p[2]) continue

                val fadeIn = if (i <= 10) i / 10f else 1f
                val c = if (rainbow) EffectColors.sync(i) else custom
                batch.line(
                    pose,
                    from[0].toFloat(), from[1].toFloat(), from[2].toFloat(),
                    p[0].toFloat(), p[1].toFloat(), p[2].toFloat(),
                    c.red / 255f, c.green / 255f, c.blue / 255f, c.alpha / 255f * fadeIn,
                    width
                )
            }
            ctx.matrixStack.popPose()
        }
    }

    override fun onDisable() {
        super.onDisable()
        points.clear()
    }
}
