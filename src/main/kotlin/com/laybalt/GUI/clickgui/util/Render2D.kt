package com.laybalt.GUI.clickgui.util

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.resources.Identifier
import java.awt.Color
import kotlin.math.atan2
import kotlin.math.sqrt

/** Float-precision 2D drawing helpers on top of the 26.x render-state API. */
object Render2D {
    private val font get() = Minecraft.getInstance().font

    val fontHeight get() = font.lineHeight

    fun String.width() = font.width(this)

    /** Greedy word wrap; explicit `\n` breaks are kept. */
    fun String.wrap(maxWidth: Int): List<String> = split('\n').flatMap { paragraph ->
        val lines = mutableListOf<String>()
        var current = ""
        for (word in paragraph.split(' ')) {
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (candidate.width() <= maxWidth || current.isEmpty()) current = candidate
            else {
                lines.add(current)
                current = word
            }
        }
        lines.add(current)
        lines
    }

    fun GuiGraphicsExtractor.drawRect(x: Number, y: Number, width: Number, height: Number, color: Color = Color.WHITE) {
        pose().pushMatrix()
        pose().translate(x.toFloat(), y.toFloat())
        pose().scale(width.toFloat(), height.toFloat())
        fill(0, 0, 1, 1, color.rgb)
        pose().popMatrix()
    }

    fun GuiGraphicsExtractor.drawBorder(x: Number, y: Number, width: Number, height: Number, color: Color = Color.WHITE, thickness: Float = 1f) {
        val fx = x.toFloat()
        val fy = y.toFloat()
        val fw = width.toFloat()
        val fh = height.toFloat()
        drawRect(fx, fy, fw, thickness, color)
        drawRect(fx, fy + fh - thickness, fw, thickness, color)
        drawRect(fx, fy + thickness, thickness, fh - thickness * 2, color)
        drawRect(fx + fw - thickness, fy + thickness, thickness, fh - thickness * 2, color)
    }

    fun GuiGraphicsExtractor.drawLine(x1: Number, y1: Number, x2: Number, y2: Number, color: Color, thickness: Float = 1f) {
        val fx1 = x1.toFloat()
        val fy1 = y1.toFloat()
        val dx = x2.toFloat() - fx1
        val dy = y2.toFloat() - fy1

        pose().pushMatrix()
        pose().translate(fx1, fy1)
        pose().rotate(atan2(dy, dx))
        pose().translate(0f, -thickness / 2f)
        pose().scale(sqrt(dx * dx + dy * dy), thickness)
        fill(0, 0, 1, 1, color.rgb)
        pose().popMatrix()
    }

    fun GuiGraphicsExtractor.drawString(str: String, x: Number, y: Number, color: Color = Color.WHITE, scale: Float = 1f, shadow: Boolean = true) {
        pose().pushMatrix()
        pose().translate(x.toFloat(), y.toFloat())
        if (scale != 1f) pose().scale(scale, scale)
        text(font, str, 0, 0, color.rgb, shadow)
        pose().popMatrix()
    }

    fun GuiGraphicsExtractor.drawCenteredString(str: String, x: Number, y: Number, color: Color = Color.WHITE, scale: Float = 1f, shadow: Boolean = true) {
        drawString(str, x.toFloat() - (str.width() * scale / 2f), y, color, scale, shadow)
    }

    fun GuiGraphicsExtractor.scissor(x: Number, y: Number, width: Number, height: Number) {
        enableScissor(x.toInt(), y.toInt(), x.toInt() + width.toInt(), y.toInt() + height.toInt())
    }

    fun GuiGraphicsExtractor.drawTexture(texture: Identifier, x: Number, y: Number, width: Number, height: Number, color: Color = Color.WHITE) {
        blit(RenderPipelines.GUI_TEXTURED, texture, x.toInt(), y.toInt(), 0f, 0f, width.toInt(), height.toInt(), width.toInt(), height.toInt(), color.rgb)
    }

    /** Gradient from [top] to [bottom]. */
    fun GuiGraphicsExtractor.drawVerticalGradient(x: Number, y: Number, width: Number, height: Number, top: Color, bottom: Color) {
        pose().pushMatrix()
        pose().translate(x.toFloat(), y.toFloat())
        pose().scale(width.toFloat(), height.toFloat())
        fillGradient(0, 0, 1, 1, top.rgb, bottom.rgb)
        pose().popMatrix()
    }

    /** Gradient from [left] to [right]: a vertical gradient rotated by -90 degrees. */
    fun GuiGraphicsExtractor.drawHorizontalGradient(x: Number, y: Number, width: Number, height: Number, left: Color, right: Color) {
        pose().pushMatrix()
        pose().translate(x.toFloat(), y.toFloat() + height.toFloat())
        pose().rotate((-Math.PI / 2).toFloat())
        pose().scale(height.toFloat(), width.toFloat())
        fillGradient(0, 0, 1, 1, left.rgb, right.rgb)
        pose().popMatrix()
    }

    fun Color.withAlpha(alpha: Int) = Color(red, green, blue, alpha.coerceIn(0, 255))

    fun Color.lerp(to: Color, t: Float): Color {
        val f = t.coerceIn(0f, 1f)
        return Color(
            (red + (to.red - red) * f).toInt(),
            (green + (to.green - green) * f).toInt(),
            (blue + (to.blue - blue) * f).toInt(),
            (alpha + (to.alpha - alpha) * f).toInt()
        )
    }
}
