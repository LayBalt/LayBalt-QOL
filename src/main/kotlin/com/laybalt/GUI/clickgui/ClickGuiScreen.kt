package com.laybalt.GUI.clickgui

import com.laybalt.GUI.LBQConfig
import com.laybalt.GUI.clickgui.util.MouseHelper
import com.laybalt.GUI.clickgui.util.Render2D.drawRect
import com.laybalt.GUI.clickgui.util.Render2D.drawTexture
import com.laybalt.GUI.clickgui.util.Render2D.drawVerticalGradient
import com.laybalt.GUI.clickgui.util.Render2D.withAlpha
import com.laybalt.GUI.clickgui.util.Resolution
import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.util.Util
import org.lwjgl.glfw.GLFW
import java.awt.Color

/**
 * LayBalt click GUI: one draggable panel per [Category] plus floating settings windows.
 * Everything is laid out on the virtual canvas of [Resolution].
 */
class ClickGuiScreen(private val parent: Screen? = null): Screen(Component.literal("LayBalt QOL")) {
    private companion object {
        const val WINDOW_WIDTH = 220f
        const val WINDOW_HEIGHT = 260f
        const val WINDOW_CASCADE = 18f
        const val SIDE_BUTTON = 22f
        val DISCORD_TEXTURE: Identifier = Identifier.fromNamespaceAndPath("lbqmod", "textures/gui/discord.png")
    }

    private val panels = Category.entries.mapIndexed { index, category -> CategoryPanel(category, 20 + index * 120, 20, this) }
    private val windows = mutableListOf<FeatureConfigWindow>()
    private val searchBar = SearchBar()

    val searchQuery get() = searchBar.text

    override fun extractRenderState(ctx: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        Resolution.push(ctx)
        val mx = Resolution.toVirtual(mouseX.toDouble())
        val my = Resolution.toVirtual(mouseY.toDouble())

        ctx.drawVerticalGradient(0, 0, Resolution.width, Resolution.height, Color(0, 0, 0, 100), Color(0, 0, 0, 150))

        searchBar.x = Resolution.width / 2f - searchBar.width / 2f
        searchBar.y = Resolution.height - 40f
        searchBar.draw(ctx)
        drawDiscordButton(ctx, mx.toFloat(), my.toFloat())
        drawSettingsButton(ctx, mx.toFloat(), my.toFloat())

        val dragged = panels.find { it.dragging }
        panels.forEach { if (it !== dragged) it.render(ctx, mx, my) }
        dragged?.render(ctx, mx, my)

        windows.forEachIndexed { index, window -> window.render(ctx, mx, my, index == windows.lastIndex) }

        val cursor = windows.asReversed().firstNotNullOfOrNull { it.cursorAt(mx.toFloat(), my.toFloat()) }
        if (cursor == null) MouseHelper.resetCursor() else MouseHelper.setCursor(cursor)

        TooltipManager.draw(ctx, Resolution.width, Resolution.height)
        Resolution.pop(ctx)
    }

    // ---------------------------------------------------------------- side buttons

    private fun discordButtonX() = searchBar.x - 6f - SIDE_BUTTON
    private fun settingsButtonX() = searchBar.x + searchBar.width + 6f
    private fun sideButtonY() = searchBar.y + (searchBar.height - SIDE_BUTTON) / 2f

    private fun isOverSideButton(buttonX: Float, mx: Float, my: Float) =
        mx in buttonX..(buttonX + SIDE_BUTTON) && my in sideButtonY()..(sideButtonY() + SIDE_BUTTON)

    private fun drawSideButtonFrame(ctx: GuiGraphicsExtractor, bx: Float, hovered: Boolean) {
        val by = sideButtonY()
        ctx.drawRect(bx, by, SIDE_BUTTON, SIDE_BUTTON, Color(15, 15, 15, 200))
        ctx.drawRect(bx, by + SIDE_BUTTON - 2f, SIDE_BUTTON, 2f, if (hovered) Style.accentColor else Color(255, 255, 255, 30))
    }

    private fun drawDiscordButton(ctx: GuiGraphicsExtractor, mx: Float, my: Float) {
        val bx = discordButtonX()
        drawSideButtonFrame(ctx, bx, isOverSideButton(bx, mx, my))
        val icon = 18f
        ctx.drawTexture(DISCORD_TEXTURE, bx + (SIDE_BUTTON - icon) / 2f, sideButtonY() + (SIDE_BUTTON - icon) / 2f, icon, icon)
    }

    /** Three slider lines: opens the "Click GUI" settings window. */
    private fun drawSettingsButton(ctx: GuiGraphicsExtractor, mx: Float, my: Float) {
        val bx = settingsButtonX()
        val hovered = isOverSideButton(bx, mx, my)
        drawSideButtonFrame(ctx, bx, hovered)

        val color = if (hovered) Style.accentColor else Color.WHITE
        val left = bx + 5f
        val lineWidth = SIDE_BUTTON - 10f
        val top = sideButtonY() + 6f
        listOf(0.65f, 0.3f, 0.8f).forEachIndexed { row, knob ->
            val ly = top + row * 4f
            ctx.drawRect(left, ly + 0.5f, lineWidth, 1f, color.withAlpha(if (hovered) 160 else 110))
            ctx.drawRect(left + lineWidth * knob - 1.5f, ly - 0.5f, 3f, 3f, color)
        }
    }

    // ---------------------------------------------------------------- input

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        val mx = Resolution.toVirtual(event.x())
        val my = Resolution.toVirtual(event.y())
        val button = event.button()

        windows.asReversed().find { it.contains(mx.toFloat(), my.toFloat()) }?.let { window ->
            focusWindow(window)
            searchBar.focused = false
            if (window.mouseClicked(mx, my, button) == FeatureConfigWindow.ClickResult.CLOSE) closeWindow(window)
            return true
        }

        panels.find { it.isMouseOverHeader(mx, my) }?.let { panel ->
            panel.mouseClicked(mx, my, button)
            searchBar.focused = false
            return true
        }

        if (button == InputConstants.MOUSE_BUTTON_LEFT) {
            if (isOverSideButton(discordButtonX(), mx.toFloat(), my.toFloat())) {
                Style.playClickSound(1f)
                Util.getPlatform().openUri(GuiFeatures.DISCORD_URL)
                return true
            }
            if (isOverSideButton(settingsButtonX(), mx.toFloat(), my.toFloat())) {
                Style.playClickSound(1f)
                openFeatureWindow(GuiFeatures.clickGui)
                return true
            }
        }

        if (searchBar.mouseClicked(mx.toFloat(), my.toFloat(), button)) return true

        panels.asReversed().find { it.isMouseOver(mx, my) }?.let { panel ->
            panel.mouseClicked(mx, my, button)
            return true
        }

        return super.mouseClicked(event, doubleClick)
    }

    override fun mouseReleased(event: MouseButtonEvent): Boolean {
        val button = event.button()
        windows.forEach { it.mouseReleased(button) }
        panels.forEach { it.mouseReleased(button) }
        return super.mouseReleased(event)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontal: Double, vertical: Double): Boolean {
        val mx = Resolution.toVirtual(mouseX)
        val my = Resolution.toVirtual(mouseY)

        windows.asReversed().find { it.contains(mx.toFloat(), my.toFloat()) }?.let { window ->
            focusWindow(window)
            window.mouseScrolled(mx, my, vertical)
            return true
        }

        panels.asReversed().find { it.isMouseOver(mx, my) }?.let { panel ->
            panel.handleScroll(vertical)
            return true
        }

        return super.mouseScrolled(mouseX, mouseY, horizontal, vertical)
    }

    override fun charTyped(event: CharacterEvent): Boolean {
        val char = event.codepoint().toChar()
        if (windows.lastOrNull()?.charTyped(char) == true) return true
        if (searchBar.charTyped(char)) return true
        return super.charTyped(event)
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        windows.lastOrNull()?.let { window ->
            if (window.keyPressed(event.key(), event.scancode(), event.modifiers())) return true
            if (event.key() == InputConstants.KEY_ESCAPE) {
                closeWindow(window)
                return true
            }
        }

        if (searchBar.keyPressed(event.key(), event.modifiers())) return true

        if (event.key() == InputConstants.KEY_F && (event.modifiers() and GLFW.GLFW_MOD_CONTROL) != 0) {
            searchBar.focused = true
            return true
        }

        return super.keyPressed(event)
    }

    // ---------------------------------------------------------------- windows

    fun openFeatureWindow(feature: GuiFeature) {
        searchBar.focused = false
        windows.find { it.feature === feature }?.let {
            focusWindow(it)
            return
        }

        val offset = windows.size * WINDOW_CASCADE
        windows.add(
            FeatureConfigWindow(
                feature,
                (Resolution.width - WINDOW_WIDTH) / 2f + offset,
                (Resolution.height - WINDOW_HEIGHT) / 2f + offset,
                WINDOW_WIDTH,
                WINDOW_HEIGHT
            ).also { it.clampToScreen() }
        )
    }

    fun isMouseOverConfigWindow(mouseX: Int, mouseY: Int) = windows.any { it.contains(mouseX.toFloat(), mouseY.toFloat()) }

    private fun focusWindow(window: FeatureConfigWindow) {
        windows.filter { it !== window }.forEach { it.clearFocus() }
        windows.remove(window)
        windows.add(window)
    }

    private fun closeWindow(window: FeatureConfigWindow) {
        window.clearFocus()
        windows.remove(window)
    }

    // ---------------------------------------------------------------- lifecycle

    /** Called whenever this screen is replaced or closed, so changes are written even when leaving via a button. */
    override fun removed() {
        windows.forEach { it.clearFocus() }
        searchBar.focused = false
        MouseHelper.resetCursor()
        LBQConfig.save()
        super.removed()
    }

    override fun onClose() {
        Minecraft.getInstance().gui.setScreen(parent)
    }
}
