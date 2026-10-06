package com.laybalt.GUI.clickgui

import com.laybalt.GUI.clickgui.util.Animation
import com.laybalt.GUI.clickgui.util.MouseHelper
import com.laybalt.GUI.clickgui.util.Render2D.drawBorder
import com.laybalt.GUI.clickgui.util.Render2D.drawCenteredString
import com.laybalt.GUI.clickgui.util.Render2D.drawRect
import com.laybalt.GUI.clickgui.util.Render2D.withAlpha
import com.laybalt.GUI.clickgui.util.ResizeCorner
import com.laybalt.GUI.clickgui.util.Resolution
import com.laybalt.GUI.clickgui.widget.SectionWidget
import com.laybalt.GUI.clickgui.widget.Widget
import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.gui.GuiGraphicsExtractor
import java.awt.Color

/** Floating, draggable, resizable and scrollable window with one feature's settings. */
class FeatureConfigWindow(val feature: GuiFeature, startX: Float, startY: Float, startWidth: Float, startHeight: Float) {
    enum class ClickResult { CONSUMED, CLOSE }

    private companion object {
        const val TITLE_BAR_HEIGHT = 24f
        const val PADDING = 10f
        const val BOTTOM_PADDING = 8f
        const val RESIZE_MARGIN = 3f
        const val CLOSE_SIZE = 14f
        const val SPACING = 5f
        const val MIN_WIDTH = 180f
        const val MIN_HEIGHT = 140f
    }

    var x = startX
        private set
    var y = startY
        private set
    private var width = startWidth
    private var height = startHeight

    private val widgets: List<Widget<*>> = buildList {
        var lastSection: String? = null
        val all = feature.settings.map { Widget.fromSetting(it) }

        for (widget in all) {
            val section = widget.config.section
            if (section != null && section != lastSection) {
                val members = all.dropWhile { it !== widget }.takeWhile { it.config.section == section }
                add(SectionWidget(section, withSeparator = isNotEmpty()).also { header ->
                    header.config.visibility = { members.any { it.config.visibility() } }
                })
                lastSection = section
            }
            add(widget)
        }
    }

    private var visibleWidgets = emptyList<Widget<*>>()
    private val scrollAnim = Animation(200)
    private var scrollTarget = 0f
    private var maxScroll = 0f

    private var contentLeft = 0f
    private var contentTop = 0f
    private var contentRight = 0f
    private var contentBottom = 0f

    private var dragging = false
    private var dragOffsetX = 0f
    private var dragOffsetY = 0f

    private var scrollbarDragging = false
    private var scrollbarDragStartY = 0f
    private var scrollbarDragStartScroll = 0f
    private var scrollbarThumbY = 0f
    private var scrollbarThumbH = 0f
    private var scrollbarViewportH = 0f

    private var resizeCorner = ResizeCorner.NONE
    private var startMouseX = 0f
    private var startMouseY = 0f
    private var startBoundsX = startX
    private var startBoundsY = startY
    private var startBoundsW = startWidth
    private var startBoundsH = startHeight

    init {
        updateContentBounds()
    }

    private fun updateContentBounds() {
        contentLeft = x + PADDING
        contentTop = y + TITLE_BAR_HEIGHT + 8f
        contentRight = x + width - PADDING
        contentBottom = y + height - BOTTOM_PADDING
    }

    fun render(ctx: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, focused: Boolean) {
        val mx = mouseX.toFloat()
        val my = mouseY.toFloat()
        updateInteraction(mx, my)
        clampToScreen()
        updateContentBounds()

        ctx.drawRect(x - 2f, y - 2f, width + 4f, height + 4f, Color(0, 0, 0, 70))
        ctx.drawRect(x, y, width, height, Color(16, 16, 16, 235))
        ctx.drawRect(x, y, width, TITLE_BAR_HEIGHT, Color(24, 24, 24, 245))
        ctx.drawRect(x, y, width, 2f, Style.accentColor.withAlpha(if (focused) 255 else 180))
        ctx.drawBorder(x, y, width, height, Color(255, 255, 255, if (focused) 40 else 24))

        ctx.drawCenteredString("§l${feature.name}", x + width / 2f, y + 8f)
        drawCloseButton(ctx, isInsideCloseButton(mx, my))

        visibleWidgets = widgets.filter { it.config.visibility() }

        val viewportWidth = (contentRight - contentLeft).coerceAtLeast(120f)
        val viewportHeight = (contentBottom - contentTop).coerceAtLeast(40f)
        val totalContentHeight = if (visibleWidgets.isEmpty()) 0f
        else visibleWidgets.sumOf { it.height }.toFloat() + (visibleWidgets.size - 1) * SPACING

        maxScroll = (totalContentHeight - viewportHeight).coerceAtLeast(0f)
        scrollTarget = scrollTarget.coerceIn(0f, maxScroll)
        scrollAnim.update(scrollTarget)
        if (scrollAnim.value > maxScroll) scrollAnim.set(maxScroll)

        val scrollbarReserve = if (maxScroll > 0f) 6f else 0f
        val settingWidth = (viewportWidth - scrollbarReserve).toInt().coerceAtLeast(120)

        ctx.enableScissor(contentLeft.toInt(), contentTop.toInt(), contentRight.toInt(), contentBottom.toInt())
        var currentY = contentTop - scrollAnim.value
        visibleWidgets.forEach { widget ->
            widget.x = contentLeft.toInt()
            widget.y = currentY.toInt()
            widget.width = settingWidth
            widget.draw(ctx, mouseX, mouseY)

            if (widget.isHovered(mouseX, mouseY) && isInsideContent(mx, my)) TooltipManager.hover(widget.config.description, mouseX, mouseY)
            currentY += widget.height + SPACING
        }
        ctx.disableScissor()

        if (visibleWidgets.isEmpty()) {
            ctx.drawCenteredString("§7No settings", x + width / 2f, contentTop + 6f)
        }

        if (maxScroll > 0f && totalContentHeight > 0f) {
            val barX = contentRight - 2f
            val thumbHeight = ((viewportHeight / totalContentHeight) * viewportHeight).coerceAtLeast(18f)
            val thumbY = contentTop + (scrollAnim.value / maxScroll) * (viewportHeight - thumbHeight).coerceAtLeast(0f)

            scrollbarThumbY = thumbY
            scrollbarThumbH = thumbHeight
            scrollbarViewportH = viewportHeight

            ctx.drawRect(barX, contentTop, 2f, viewportHeight, Color(255, 255, 255, 15))
            ctx.drawRect(barX, thumbY, 2f, thumbHeight, Style.accentColor.withAlpha(if (focused) 180 else 140))
        }
    }

    fun mouseClicked(mouseX: Int, mouseY: Int, button: Int): ClickResult {
        val mx = mouseX.toFloat()
        val my = mouseY.toFloat()

        if (button == InputConstants.MOUSE_BUTTON_LEFT) {
            if (isInsideCloseButton(mx, my)) return ClickResult.CLOSE

            val corner = MouseHelper.getResizeCorner(mx, my, x, y, width, height, RESIZE_MARGIN)
            if (corner != ResizeCorner.NONE) {
                beginResize(corner, mx, my)
                return ClickResult.CONSUMED
            }

            if (my <= y + TITLE_BAR_HEIGHT) {
                dragging = true
                dragOffsetX = mx - x
                dragOffsetY = my - y
                return ClickResult.CONSUMED
            }

            if (maxScroll > 0f && mx >= contentRight - 5f && mx <= contentRight + 6f && my >= contentTop && my <= contentBottom) {
                if (my >= scrollbarThumbY && my <= scrollbarThumbY + scrollbarThumbH) {
                    scrollbarDragging = true
                    scrollbarDragStartY = my
                    scrollbarDragStartScroll = scrollTarget
                }
                else {
                    val travel = (scrollbarViewportH - scrollbarThumbH).coerceAtLeast(0f)
                    if (travel > 0f) scrollTarget = ((my - contentTop - scrollbarThumbH / 2f) / travel).coerceIn(0f, 1f) * maxScroll
                }
                return ClickResult.CONSUMED
            }
        }

        if (isInsideContent(mx, my)) {
            var handled = false
            visibleWidgets.forEach { if (!handled && it.mouseClicked(mouseX.toDouble(), mouseY.toDouble(), button)) handled = true }
            // Clicking elsewhere drops text focus (slider typing, hex field, keybind listening).
            if (handled) visibleWidgets.forEach { if (!it.isHovered(mouseX, mouseY)) it.clearFocus() }
        }
        return ClickResult.CONSUMED
    }

    fun clearFocus() = widgets.forEach { it.clearFocus() }

    fun mouseReleased(button: Int) {
        if (button == InputConstants.MOUSE_BUTTON_LEFT) {
            dragging = false
            resizeCorner = ResizeCorner.NONE
            scrollbarDragging = false
        }
        visibleWidgets.forEach { it.mouseReleased(button) }
    }

    fun mouseScrolled(mouseX: Int, mouseY: Int, delta: Double) {
        if (visibleWidgets.any { it.mouseScrolled(mouseX, mouseY, delta) }) return
        if (isInsideContent(mouseX.toFloat(), mouseY.toFloat())) {
            scrollTarget = (scrollTarget - (delta * 30).toFloat()).coerceIn(0f, maxScroll)
        }
    }

    fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int) = visibleWidgets.any { it.keyPressed(keyCode, scanCode, modifiers) }

    fun charTyped(codePoint: Char) = visibleWidgets.any { it.charTyped(codePoint) }

    fun contains(mouseX: Float, mouseY: Float) = mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height

    fun cursorAt(mouseX: Float, mouseY: Float) = when {
        resizeCorner != ResizeCorner.NONE -> resizeCorner.cursor
        contains(mouseX, mouseY) -> MouseHelper.getResizeCorner(mouseX, mouseY, x, y, width, height, RESIZE_MARGIN).cursor
        else -> null
    }

    fun clampToScreen() {
        width = width.coerceIn(MIN_WIDTH, Resolution.width.coerceAtLeast(MIN_WIDTH))
        height = height.coerceIn(MIN_HEIGHT, Resolution.height.coerceAtLeast(MIN_HEIGHT))
        x = x.coerceIn(0f, (Resolution.width - width).coerceAtLeast(0f))
        y = y.coerceIn(0f, (Resolution.height - height).coerceAtLeast(0f))
    }

    private fun updateInteraction(mouseX: Float, mouseY: Float) {
        if (scrollbarDragging) {
            val travel = (scrollbarViewportH - scrollbarThumbH).coerceAtLeast(0f)
            if (travel > 0f) {
                val newScroll = (scrollbarDragStartScroll + ((mouseY - scrollbarDragStartY) / travel) * maxScroll).coerceIn(0f, maxScroll)
                scrollTarget = newScroll
                scrollAnim.set(newScroll)
            }
        }

        if (dragging) {
            x = mouseX - dragOffsetX
            y = mouseY - dragOffsetY
            return
        }
        if (resizeCorner == ResizeCorner.NONE) return

        val dx = mouseX - startMouseX
        val dy = mouseY - startMouseY
        var newX = startBoundsX
        var newY = startBoundsY
        var newW = startBoundsW
        var newH = startBoundsH

        when (resizeCorner) {
            ResizeCorner.LEFT, ResizeCorner.TOP_LEFT, ResizeCorner.BOTTOM_LEFT -> {
                newX = (startBoundsX + dx).coerceIn(0f, startBoundsX + startBoundsW - MIN_WIDTH)
                newW = startBoundsW + (startBoundsX - newX)
            }
            ResizeCorner.RIGHT, ResizeCorner.TOP_RIGHT, ResizeCorner.BOTTOM_RIGHT ->
                newW = (startBoundsW + dx).coerceIn(MIN_WIDTH, (Resolution.width - startBoundsX).coerceAtLeast(MIN_WIDTH))
            else -> {}
        }
        when (resizeCorner) {
            ResizeCorner.TOP, ResizeCorner.TOP_LEFT, ResizeCorner.TOP_RIGHT -> {
                newY = (startBoundsY + dy).coerceIn(0f, startBoundsY + startBoundsH - MIN_HEIGHT)
                newH = startBoundsH + (startBoundsY - newY)
            }
            ResizeCorner.BOTTOM, ResizeCorner.BOTTOM_LEFT, ResizeCorner.BOTTOM_RIGHT ->
                newH = (startBoundsH + dy).coerceIn(MIN_HEIGHT, (Resolution.height - startBoundsY).coerceAtLeast(MIN_HEIGHT))
            else -> {}
        }

        x = newX
        y = newY
        width = newW
        height = newH
    }

    private fun beginResize(corner: ResizeCorner, mouseX: Float, mouseY: Float) {
        dragging = false
        resizeCorner = corner
        startMouseX = mouseX
        startMouseY = mouseY
        startBoundsX = x
        startBoundsY = y
        startBoundsW = width
        startBoundsH = height
    }

    private fun isInsideContent(mouseX: Float, mouseY: Float) = mouseX in contentLeft..contentRight && mouseY in contentTop..contentBottom

    private fun closeButtonX() = x + width - PADDING - CLOSE_SIZE
    private fun closeButtonY() = y + (TITLE_BAR_HEIGHT - CLOSE_SIZE) / 2f

    private fun isInsideCloseButton(mouseX: Float, mouseY: Float) =
        mouseX in closeButtonX()..(closeButtonX() + CLOSE_SIZE) && mouseY in closeButtonY()..(closeButtonY() + CLOSE_SIZE)

    private fun drawCloseButton(ctx: GuiGraphicsExtractor, hovered: Boolean) {
        val cx = closeButtonX()
        val cy = closeButtonY()
        ctx.drawRect(cx, cy, CLOSE_SIZE, CLOSE_SIZE, if (hovered) Color(180, 60, 60, 220) else Color(255, 255, 255, 18))
        ctx.drawCenteredString("×", cx + CLOSE_SIZE / 2f, cy + 3f)
    }
}
