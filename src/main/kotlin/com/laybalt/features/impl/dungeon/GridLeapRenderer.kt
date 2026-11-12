package com.laybalt.features.impl.dungeon

import com.laybalt.ui.utils.Resolution
import com.laybalt.utils.ColorUtils.withAlpha
import com.laybalt.utils.dungeons.DungeonPlayer
import com.laybalt.utils.render.Render2D.drawBorder
import com.laybalt.utils.render.Render2D.drawFloatingRect
import com.laybalt.utils.render.Render2D.drawPlayerHead
import com.laybalt.utils.render.Render2D.drawString
import gg.essential.universal.UGraphics
import gg.essential.universal.UMouse
import gg.essential.universal.UResolution
import net.minecraft.client.gui.GuiGraphicsExtractor

/** The default 2x2 leap menu; the hovered box is the screen quadrant under the mouse. */
object GridLeapRenderer: LeapMenuRenderer() {
    private const val BOX_WIDTH = 128f * 1.3f
    private const val BOX_HEIGHT = 80f * 0.8f
    private const val PADDING = 40f
    private const val HEAD_SIZE = 50

    override fun render(ctx: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        Resolution.push(ctx)
        val userScale = (LeapMenu.scale.value.toFloat() / 100f) * 2.0f
        val screenWidth = Resolution.width / userScale
        val screenHeight = Resolution.height / userScale

        val pose = ctx.pose()
        pose.pushMatrix()
        pose.scale(userScale)

        val gridW = (BOX_WIDTH * 2) + PADDING
        val gridH = (BOX_HEIGHT * 2) + PADDING
        val startX = (screenWidth - gridW) / 2f
        val startY = (screenHeight - gridH) / 2f

        val offsets = listOf(
            startX to startY,
            (startX + BOX_WIDTH + PADDING) to startY,
            startX to (startY + BOX_HEIGHT + PADDING),
            (startX + BOX_WIDTH + PADDING) to (startY + BOX_HEIGHT + PADDING)
        )

        val hoveredIndex = getHoveredIndex()

        LeapMenu.players.forEachIndexed { i, entry ->
            if (entry == null) return@forEachIndexed
            val (x, y) = offsets[i]
            drawPlayerBox(ctx, x, y, entry.player, i == hoveredIndex)
        }

        pose.popMatrix()
        Resolution.pop(ctx)
    }

    private fun drawPlayerBox(ctx: GuiGraphicsExtractor, x: Float, y: Float, player: DungeonPlayer, isHovered: Boolean) {
        val bgColor = leapBoxColor(player, isHovered)

        ctx.drawFloatingRect(x, y, BOX_WIDTH, BOX_HEIGHT, bgColor.withAlpha(190))

        val headX = (x + 10).toInt()
        val headY = (y + (BOX_HEIGHT / 2) - HEAD_SIZE / 2).toInt()

        ctx.drawPlayerHead(headX, headY, HEAD_SIZE, player.skin)
        ctx.drawBorder(headX, headY, HEAD_SIZE, HEAD_SIZE, player.clazz.color)

        val textX = (x + 10 + HEAD_SIZE + 5).toInt()
        val textY = (y + BOX_HEIGHT / 2 - UGraphics.getFontHeight()).toInt()

        ctx.drawString(player.name, textX, textY + 2, player.clazz.color)

        val status = if (player.isDead) "§cDEAD" else player.clazz.name
        ctx.drawString(status, textX, textY + 12, player.clazz.color)
    }

    override fun getHoveredIndex(): Int? {
        val cx = UResolution.windowWidth / 2
        val cy = UResolution.windowHeight / 2
        val mx = UMouse.Raw.x
        val my = UMouse.Raw.y

        return when {
            mx < cx && my < cy -> 0
            mx > cx && my < cy -> 1
            mx < cx && my > cy -> 2
            mx > cx && my > cy -> 3
            else -> null
        }
    }
}
