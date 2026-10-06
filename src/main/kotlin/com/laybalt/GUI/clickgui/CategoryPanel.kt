package com.laybalt.GUI.clickgui

import com.laybalt.GUI.LBQConfig
import com.laybalt.GUI.clickgui.util.Animation
import com.laybalt.GUI.clickgui.util.Render2D.drawCenteredString
import com.laybalt.GUI.clickgui.util.Render2D.drawRect
import com.laybalt.GUI.clickgui.util.Render2D.scissor
import com.laybalt.GUI.clickgui.util.Render2D.width
import com.laybalt.GUI.clickgui.util.Render2D.withAlpha
import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.gui.GuiGraphicsExtractor
import java.awt.Color

/**
 * Draggable column of features. Left click a feature to toggle it, right click to open its settings,
 * right click the header to collapse the panel.
 */
class CategoryPanel(val category: Category, var x: Int, var y: Int, private val screen: ClickGuiScreen) {
    private companion object {
        const val WIDTH = 110
        const val HEADER_HEIGHT = 22
        const val BUTTON_HEIGHT = 16
        const val MAX_DISPLAY_HEIGHT = 350

        val headerBg = Color(20, 20, 20, 230)
        val bodyBg = Color(15, 15, 15, 180)
        val hoverColor = Color(255, 255, 255, 30)
    }

    private val features = GuiFeatures.byCategory(category)
    private val openAnim = Animation(150, 1f)
    private var collapsed = false
    private var scrollOffset = 0f

    var dragging = false
        private set
    private var dragX = 0
    private var dragY = 0

    private fun visibleFeatures(): List<GuiFeature> {
        val matches = SuggestionManager.getSuggestions(screen.searchQuery, features)
        if (screen.searchQuery.isNotBlank()) return matches
        return when (LBQConfig.ClickGuiSorting) {
            0 -> matches.sortedBy { it.name }
            1 -> matches.sortedByDescending { it.name.width() }
            else -> matches
        }
    }

    private fun contentHeight(count: Int) = (count * BUTTON_HEIGHT).coerceAtMost(MAX_DISPLAY_HEIGHT)

    /** Collapsing only applies while not searching, so search results are always visible. */
    private fun isOpen() = !collapsed || screen.searchQuery.isNotBlank()

    fun render(ctx: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        if (dragging) {
            x = mouseX - dragX
            y = mouseY - dragY
        }

        val shown = visibleFeatures()
        if (shown.isEmpty() && screen.searchQuery.isNotBlank()) return

        openAnim.update(if (isOpen()) 1f else 0f)

        ctx.drawRect(x, y, WIDTH, HEADER_HEIGHT, headerBg)
        ctx.drawRect(x, y, WIDTH, 2, Style.accentColor)
        ctx.drawCenteredString("§l${category.displayName}", x + WIDTH / 2f, y + 7f)

        if (openAnim.value <= 0.01f) return

        val totalHeight = shown.size * BUTTON_HEIGHT
        val visibleHeight = contentHeight(shown.size)
        val maxScroll = (totalHeight - visibleHeight).coerceAtLeast(0)
        scrollOffset = scrollOffset.coerceIn(0f, maxScroll.toFloat())

        val top = y + HEADER_HEIGHT
        var currentY = top - scrollOffset.toInt()

        ctx.scissor(x, top, WIDTH, (visibleHeight * openAnim.value).toInt())
        shown.forEach { feature ->
            if (currentY + BUTTON_HEIGHT > top && currentY < top + visibleHeight) {
                val hovered = mouseX in x..(x + WIDTH) && mouseY in currentY..(currentY + BUTTON_HEIGHT) && mouseY in top..(top + visibleHeight)

                ctx.drawRect(x, currentY, WIDTH, BUTTON_HEIGHT, bodyBg)
                if (feature.enabled) {
                    ctx.drawRect(x, currentY, WIDTH, BUTTON_HEIGHT, Style.accentColor.withAlpha(100))
                    ctx.drawRect(x, currentY, 2, BUTTON_HEIGHT, Style.accentColor)
                }
                if (hovered) ctx.drawRect(x, currentY, WIDTH, BUTTON_HEIGHT, hoverColor)

                val label = if (feature.toggleable) feature.name else "§7${feature.name}"
                ctx.drawCenteredString(label, x + WIDTH / 2f, currentY + 4f)

                if (hovered && !screen.isMouseOverConfigWindow(mouseX, mouseY)) TooltipManager.hover(feature.description, mouseX, mouseY)
            }
            currentY += BUTTON_HEIGHT
        }
        ctx.disableScissor()

        if (maxScroll > 0 && isOpen()) {
            val barHeight = visibleHeight.toFloat() / totalHeight * visibleHeight
            val barY = top + (scrollOffset / maxScroll) * (visibleHeight - barHeight)
            ctx.drawRect(x + WIDTH - 2, barY, 2, barHeight)
        }
    }

    fun handleScroll(delta: Double) {
        if (!isOpen()) return
        scrollOffset -= (delta * 15).toFloat()
    }

    fun isMouseOver(mx: Int, my: Int) =
        mx in x..(x + WIDTH) && my in y..(y + HEADER_HEIGHT + if (isOpen()) contentHeight(visibleFeatures().size) else 0)

    fun isMouseOverHeader(mx: Int, my: Int) = mx in x..(x + WIDTH) && my in y..(y + HEADER_HEIGHT)

    fun mouseClicked(mouseX: Int, mouseY: Int, button: Int) {
        if (isMouseOverHeader(mouseX, mouseY)) {
            if (button == InputConstants.MOUSE_BUTTON_LEFT) {
                dragging = true
                dragX = mouseX - x
                dragY = mouseY - y
            }
            else if (button == InputConstants.MOUSE_BUTTON_RIGHT) {
                collapsed = !collapsed
                Style.playClickSound(if (collapsed) 0.8f else 1.1f)
            }
            return
        }
        if (!isOpen()) return

        val shown = visibleFeatures()
        val top = y + HEADER_HEIGHT
        if (mouseY !in top..(top + contentHeight(shown.size))) return

        val index = ((mouseY - top + scrollOffset.toInt()) / BUTTON_HEIGHT)
        val feature = shown.getOrNull(index) ?: return

        when {
            button == InputConstants.MOUSE_BUTTON_LEFT && feature.toggleable -> {
                feature.toggle()
                Style.playClickSound(if (feature.enabled) 1.1f else 0.8f)
            }
            button == InputConstants.MOUSE_BUTTON_LEFT || button == InputConstants.MOUSE_BUTTON_RIGHT -> {
                if (feature.settings.isNotEmpty()) screen.openFeatureWindow(feature)
            }
        }
    }

    fun mouseReleased(button: Int) {
        if (button == InputConstants.MOUSE_BUTTON_LEFT) dragging = false
    }
}
