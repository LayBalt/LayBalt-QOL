package com.laybalt.ui.clickgui.components

import com.mojang.blaze3d.platform.InputConstants

import com.laybalt.LayBaltQOL.MOD_NAME
import com.laybalt.features.Feature
import com.laybalt.features.FeatureManager
import com.laybalt.features.impl.dev.ClickGui
import com.laybalt.features.impl.general.CommandShortcuts
import com.laybalt.features.impl.misc.sound.SoundManager
import com.laybalt.ui.clickgui.ClickGuiScreen
import com.laybalt.ui.clickgui.SuggestionManager
import com.laybalt.ui.clickgui.TooltipManager
import com.laybalt.ui.Palette
import com.laybalt.ui.clickgui.components.settings.Style
import com.laybalt.ui.clickgui.enums.CategoryType
import com.laybalt.ui.gui.CommandShortcutsScreen
import com.laybalt.ui.gui.SoundManagerScreen
import com.laybalt.ui.notification.NotificationManager
import com.laybalt.ui.utils.Animation
import com.laybalt.utils.ColorUtils.withAlpha
import com.laybalt.utils.GuiUtils
import com.laybalt.utils.render.Render2D.drawCenteredString
import com.laybalt.utils.render.Render2D.drawRect
import com.laybalt.utils.render.Render2D.drawString
import com.laybalt.utils.render.Render2D.scissor
import com.laybalt.utils.render.RenderHelper.width
import net.minecraft.client.gui.GuiGraphicsExtractor

class CategoryPanel(val category: CategoryType, var x: Int, var y: Int, private val screen: ClickGuiScreen) {
    companion object {
        const val WIDTH = 110
        const val HEADER_HEIGHT = 22
        const val BUTTON_HEIGHT = 16
        const val MAX_DISPLAY_HEIGHT = 350
        const val MIN_DISPLAY_HEIGHT = BUTTON_HEIGHT * 3

        private val headerBg get() = Palette.bgPanel
        private val bodyBg = Palette.bgDeep.withAlpha(200)
        private val hoverColor get() = Palette.hover
        private val wipTagBg = Palette.wip.withAlpha(70)
        private val wipRowBg = Palette.bgDeep.withAlpha(120)
    }

    private val features = FeatureManager.getFeaturesByCategory(category)

    private val openAnim = Animation(150)
    /** Body height limit; the screen lowers it so the panel ends above the search bar. Longer lists scroll. */
    var maxDisplayHeight = MAX_DISPLAY_HEIGHT

    var collapsed = false

    private var scrollOffset = 0f
    var dragging = false

    /** True once the user dragged this panel; the screen then stops auto-laying it out. */
    var moved = false
        private set
    private var dragX = 0
    private var dragY = 0

    fun render(context: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        if (dragging) {
            val nx = mouseX - dragX
            val ny = mouseY - dragY
            if (nx != x || ny != y) moved = true
            x = nx
            y = ny
        }

        val filteredFeatures = getSorting()
        if (filteredFeatures.isEmpty() && screen.searchQuery.isNotEmpty()) return

        openAnim.update(if (collapsed && features.size == filteredFeatures.size) 0f else 1f)

        context.drawRect(x, y, WIDTH, HEADER_HEIGHT, headerBg)
        context.drawRect(x, y, WIDTH, 2, Style.accentColor)
        context.drawCenteredString("§l${category.displayName}", x + WIDTH / 2, y + 7, Palette.textPrimary)

        if (openAnim.value > 0.01f || features.size != filteredFeatures.size) {
            val totalContentHeight = filteredFeatures.size * BUTTON_HEIGHT
            val visibleHeight = totalContentHeight.coerceAtMost(maxDisplayHeight)
            val currentScissorHeight = (visibleHeight * openAnim.value).toInt()
            val maxScroll = (totalContentHeight - visibleHeight).coerceAtLeast(0)
            if (scrollOffset > maxScroll) scrollOffset = maxScroll.toFloat()
            if (scrollOffset < 0) scrollOffset = 0f

            var currentY = y + HEADER_HEIGHT - scrollOffset.toInt()

            context.scissor(x, y + HEADER_HEIGHT, WIDTH, currentScissorHeight)

            filteredFeatures.forEach { feature ->
                if (currentY + BUTTON_HEIGHT > y + HEADER_HEIGHT && currentY < y + HEADER_HEIGHT + visibleHeight) {
                    val isHovered = mouseX >= x && mouseX <= x + WIDTH &&
                        mouseY >= currentY && mouseY <= currentY + BUTTON_HEIGHT &&
                        mouseY >= y + HEADER_HEIGHT && mouseY <= y + HEADER_HEIGHT + visibleHeight

                    context.drawRect(x, currentY, WIDTH, BUTTON_HEIGHT, if (feature.wip) wipRowBg else bodyBg)

                    if (feature.wip) drawWipRow(context, feature, currentY, isHovered)
                    else {
                        if (feature.enabled) {
                            context.drawRect(x, currentY, WIDTH, BUTTON_HEIGHT, Palette.accentSoft)
                            context.drawRect(x, currentY, 2, BUTTON_HEIGHT, Style.accentColor)
                        }

                        if (isHovered) {
                            context.drawRect(x, currentY, WIDTH, BUTTON_HEIGHT, hoverColor)
                        }

                        context.drawCenteredString(feature.name, x + WIDTH / 2, currentY + 4, Palette.textPrimary)
                    }

                    if (isHovered && ! screen.isMouseOverConfigWindow(mouseX, mouseY)) {
                        val description = if (feature.wip) "§7(Work in progress) §r${feature.description.orEmpty()}" else feature.description
                        TooltipManager.hover(description, mouseX, mouseY)
                    }
                }
                currentY += BUTTON_HEIGHT
            }
            context.disableScissor()

            if (maxScroll > 0 && ! collapsed) {
                val barHeight = (visibleHeight.toFloat() / totalContentHeight.toFloat()) * visibleHeight
                val barY = (y + HEADER_HEIGHT) + ((scrollOffset / maxScroll) * (visibleHeight - barHeight))

                context.drawRect(x + WIDTH - 2, barY, 2, barHeight, Palette.textSecondary)
            }
        }
    }

    /** Greyed-out row for a work-in-progress feature: muted text, no accent, half alpha, "WIP" tag on the right. */
    private fun drawWipRow(context: GuiGraphicsExtractor, feature: Feature, rowY: Int, hovered: Boolean) {
        if (hovered) context.drawRect(x, rowY, WIDTH, BUTTON_HEIGHT, hoverColor.withAlpha(hoverColor.alpha / 2))

        val tag = "WIP"
        val tagW = tag.width() + 4
        val tagX = x + WIDTH - tagW - 3
        context.drawRect(tagX, rowY + 3, tagW, BUTTON_HEIGHT - 6, wipTagBg)
        context.drawString(tag, tagX + 2, rowY + 4, Palette.textMuted, shadow = false)

        context.drawCenteredString(feature.name, x + WIDTH / 2, rowY + 4, Palette.textMuted, shadow = false)
    }

    /** Header plus the fully expanded (unfiltered, uncollapsed) body, used for the panel layout. */
    fun fullHeight() = HEADER_HEIGHT + (features.size * BUTTON_HEIGHT).coerceAtMost(maxDisplayHeight)

    fun handleScroll(delta: Double) {
        if (collapsed) return
        val filteredFeatures = features.filter { it.name.contains(screen.searchQuery, ignoreCase = true) }
        val totalContentHeight = filteredFeatures.size * BUTTON_HEIGHT
        val visibleHeight = totalContentHeight.coerceAtMost(maxDisplayHeight)

        if (totalContentHeight <= visibleHeight) return

        scrollOffset -= (delta * 15).toFloat()
    }

    fun isMouseOver(mx: Int, my: Int): Boolean {
        val filteredFeatures = getSorting()
        val totalContentHeight = filteredFeatures.size * BUTTON_HEIGHT
        val visibleHeight = totalContentHeight.coerceAtMost(maxDisplayHeight)

        return mx >= x && mx <= x + WIDTH && my >= y && my <= y + HEADER_HEIGHT + visibleHeight
    }

    fun isMouseOverHeader(mx: Double, my: Double): Boolean {
        return mx >= x && mx <= x + WIDTH && my >= y && my <= y + HEADER_HEIGHT
    }

    fun mouseClicked(mouseX: Double, mouseY: Double, button: Int) {
        if (isMouseOverHeader(mouseX, mouseY)) {
            if (button == InputConstants.MOUSE_BUTTON_LEFT) {
                dragging = true
                dragX = (mouseX - x).toInt()
                dragY = (mouseY - y).toInt()
            }
            else if (button == InputConstants.MOUSE_BUTTON_RIGHT) {
                collapsed = ! collapsed
                Style.playClickSound(if (collapsed) 0.8f else 1.1f)
            }
            return
        }

        val filteredFeatures = getSorting()
        if (collapsed && features.size == filteredFeatures.size) return

        val totalContentHeight = filteredFeatures.size * BUTTON_HEIGHT
        val visibleHeight = totalContentHeight.coerceAtMost(maxDisplayHeight)

        if (mouseY < y + HEADER_HEIGHT || mouseY > y + HEADER_HEIGHT + visibleHeight) return

        var currentY = y + HEADER_HEIGHT - scrollOffset.toInt()

        filteredFeatures.forEach { feature ->
            if (mouseX >= x && mouseX <= x + WIDTH && mouseY >= currentY && mouseY <= currentY + BUTTON_HEIGHT) {
                if (button == InputConstants.MOUSE_BUTTON_LEFT) {
                    // Feature.toggle() refuses WIP features and pushes the "work in progress" notification.
                    if (feature.wip) Style.playClickSound(0.6f)
                    feature.toggle()
                    return
                }
                else if (button == InputConstants.MOUSE_BUTTON_RIGHT) {
                    openFeature(feature)
                    return
                }
            }
            currentY += BUTTON_HEIGHT
        }
    }

    fun mouseReleased(button: Int) {
        if (button == InputConstants.MOUSE_BUTTON_LEFT) dragging = false
    }

    private fun getSorting(): Collection<Feature> {
        val suggestions = if (screen.searchQuery.isBlank()) features
        else SuggestionManager.getSuggestions(screen.searchQuery, features)

        return when (ClickGui.panelSorting.value) {
            0 -> suggestions.sortedBy { it.name }
            1 -> suggestions.sortedByDescending { it.name.width() }
            else -> suggestions
        }
    }

    fun openFeature(feature: Feature) {
        if (feature is SoundManager || feature is CommandShortcuts) {
            val screen = if (feature is SoundManager) SoundManagerScreen() else CommandShortcutsScreen()
            if (feature.enabled) GuiUtils.setScreen(screen)
            else NotificationManager.push("$MOD_NAME - ClickGui", "Enable ${feature.name} first to open the settings!")
        }
        else if (feature.configSettings.isNotEmpty()) screen.openFeatureWindow(feature)
    }
}