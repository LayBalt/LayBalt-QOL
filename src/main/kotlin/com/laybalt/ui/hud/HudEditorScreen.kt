package com.laybalt.ui.hud

import com.mojang.blaze3d.platform.InputConstants

import com.laybalt.config.ConfigManager
import com.laybalt.features.FeatureManager
import com.laybalt.ui.utils.Resolution
import com.laybalt.ui.utils.componnents.UIButton
import com.laybalt.utils.render.Render2D.drawCenteredString
import com.laybalt.utils.render.Render2D.drawLine
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import com.laybalt.ui.Palette
import com.laybalt.ui.clickgui.components.settings.Style
import com.laybalt.utils.render.Render2D.drawVerticalGradient

class HudEditorScreen: Screen(Component.literal("HudEditor")) {
    private val huds = FeatureManager.hudElements.filter(HudElement::toggle)
    private var resetConfirmed = false
    private var gridEnabled = false
    private var gridSize = 10

    private lateinit var gridToggleButton: UIButton

    override fun init() {
        super.init()

        val btnHeight = 20
        val center = width / 2

        gridToggleButton = addRenderableWidget(UIButton(center - 117, height - 40, 80, btnHeight, "Grid: OFF") {
            gridEnabled = ! gridEnabled
            updateGridButton()
        })

        addRenderableWidget(UIButton(center - 32, height - 40, 35, btnHeight, "-") {
            gridSize = (gridSize - 5).coerceAtLeast(5)
            if (! gridEnabled) gridEnabled = true
            updateGridButton()
        })

        addRenderableWidget(UIButton(center + 8, height - 40, 35, btnHeight, "+") {
            gridSize = (gridSize + 5).coerceAtMost(50)
            if (! gridEnabled) gridEnabled = true
            updateGridButton()
        })

        addRenderableWidget(UIButton(center + 48, height - 40, 70, btnHeight, "Reset") { button ->
            if (! resetConfirmed) {
                button.message = Component.literal("§lConfirm?")
                button.overrideColor = Palette.danger
                resetConfirmed = true
                return@UIButton
            }
            resetConfirmed = false
            button.message = Component.literal("Reset")
            button.overrideColor = null
            FeatureManager.hudElements.forEach {
                it.x = HudElement.defaultX
                it.y = HudElement.defaultY
                it.scale = HudElement.defaultScale
                it.defaults.invoke(it)
            }
        })
    }

    private fun updateGridButton() {
        gridToggleButton.message = Component.literal(
            if (gridEnabled) "Grid: ON (${gridSize}px)" else "Grid: OFF"
        )
        gridToggleButton.overrideColor = if (gridEnabled) Style.accentColor else null
    }

    private fun drawGrid(ctx: GuiGraphicsExtractor) {
        val gridColor = Palette.grid.rgb
        val w = Resolution.width.toInt()
        val h = Resolution.height.toInt()

        var x = 0
        while (x <= w) {
            ctx.fill(x, 0, x + 1, h, gridColor)
            x += gridSize
        }

        var y = 0
        while (y <= h) {
            ctx.fill(0, y, w, y + 1, gridColor)
            y += gridSize
        }

        ctx.drawLine(0, h / 2, w, h / 2, Palette.accentSoft)
        ctx.drawLine(w / 2, 0, w / 2, h, Palette.accentSoft)
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        Resolution.push(graphics)

        val mX = Resolution.getMouseX(mouseX)
        val mY = Resolution.getMouseY(mouseY)
        val midX = Resolution.width / 2

        graphics.drawVerticalGradient(0, 0, Resolution.width, Resolution.height, Palette.backdropTop, Palette.backdropBottom)
        if (gridEnabled) drawGrid(graphics)

        val effectiveGridSize = if (gridEnabled) gridSize else 0
        for (hud in huds) hud.drawEditor(graphics, mX, mY, effectiveGridSize)

        val draggedElement = huds.find { it.isDragging }
        val hoveredElement = if (draggedElement == null) huds.find { it.isHovered(mX, mY) } else null
        val activeElement = draggedElement ?: hoveredElement

        if (activeElement != null) {
            val coords = "(${activeElement.x.toInt()}, ${activeElement.y.toInt()}) @ ${"%.1f".format(activeElement.scale)}x"
            graphics.drawCenteredString("${activeElement.name}  $coords", midX, 10f, Palette.textPrimary, scale = 1.2f)
        }

        Resolution.pop(graphics)
        super.extractRenderState(graphics, mouseX, mouseY, a)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontal: Double, vertical: Double): Boolean {
        val mX = Resolution.getMouseX(mouseX)
        val mY = Resolution.getMouseY(mouseY)
        val hud = huds.find { it.isDragging } ?: huds.find { it.isHovered(mX, mY) }

        if (hud != null) {
            val increment = (vertical * 0.1).toFloat()
            hud.scale = (hud.scale + increment).coerceIn(0.5f, 5.0f)
            return true
        }

        if (gridEnabled) {
            val step = (vertical * 5).toInt()
            if (step != 0) {
                gridSize = (gridSize + step).coerceIn(5, 50)
                return true
            }
        }

        return super.mouseScrolled(mouseX, mouseY, horizontal, vertical)
    }

    override fun mouseClicked(mouseButtonEvent: MouseButtonEvent, bl: Boolean): Boolean {
        val mX = Resolution.getMouseX(mouseButtonEvent.x)
        val mY = Resolution.getMouseY(mouseButtonEvent.y)

        if (mouseButtonEvent.button() == InputConstants.MOUSE_BUTTON_LEFT) huds.forEach {
            it.startDragging(mX, mY)
            if (it.isDragging) return true
        }

        return super.mouseClicked(mouseButtonEvent, bl)
    }

    override fun mouseReleased(mouseButtonEvent: MouseButtonEvent): Boolean {
        for (hud in huds) hud.isDragging = false
        return super.mouseReleased(mouseButtonEvent)
    }

    override fun onClose() {
        ConfigManager.save()
        super.onClose()
    }
}