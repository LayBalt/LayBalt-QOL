package com.laybalt.ui.hud

import com.laybalt.LayBaltQOL.mc
import com.laybalt.config.ConfigManager
import com.laybalt.features.FeatureManager
import com.laybalt.ui.Palette
import com.laybalt.ui.clickgui.UI
import com.laybalt.ui.clickgui.components.Rect
import com.laybalt.ui.clickgui.components.settings.Style
import com.laybalt.ui.render.Icon
import com.laybalt.ui.text.UIFont
import com.laybalt.ui.utils.Resolution
import com.mojang.blaze3d.platform.InputConstants
import com.mojang.blaze3d.platform.cursor.CursorTypes
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import kotlin.math.abs

/**
 * HUD editor: drag HUD elements (snapping to the screen centre and edges, or to an optional grid), scroll over one to
 * resize it, arrow keys nudge the selected one. A floating toolbar at the top holds Grid, Reset and Done.
 * [parent] (e.g. the LayBalt screen) is shown again when the editor closes.
 */
class HudEditorScreen(private val parent: Screen? = null): Screen(Component.literal("HudEditor")) {
    private companion object {
        const val SNAP = 4f
        const val BAR_H = 26f
        const val TOOLBAR_Y = 12f
    }

    private val huds = FeatureManager.hudElements.filter(HudElement::toggle)
    private var resetConfirmUntil = 0L
    private var gridEnabled = false
    private var gridSize = 10
    private var selected: HudElement? = null

    private var guideX: Float? = null
    private var guideY: Float? = null

    private enum class Button { GRID, RESET, DONE }
    private val buttons = LinkedHashMap<Button, Rect>()
    private val buttonHover = Button.entries.associateWith { UI.Smooth() }

    private fun buttonLabel(b: Button) = when (b) {
        Button.GRID -> if (gridEnabled) "Grid · ${gridSize}px" else "Grid"
        Button.RESET -> if (System.currentTimeMillis() < resetConfirmUntil) "Confirm reset?" else "Reset"
        Button.DONE -> "Done"
    }

    private fun buttonIcon(b: Button) = when (b) {
        Button.GRID -> Icon.GRID
        Button.RESET -> Icon.RESET
        Button.DONE -> Icon.CHECK
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        UI.beginFrame()
        UI.alpha = 1f
        Resolution.push(graphics)
        val mx = mouseX / Resolution.scale
        val my = mouseY / Resolution.scale
        val w = Resolution.width
        val h = Resolution.height
        // Editor chrome (tags, toolbar, hints) is enlarged in small windows so it stays readable; HUD positions are not.
        chromeK = (1.25f / (Resolution.scale * mc.window.guiScale)).coerceIn(1f, 1.5f)
        val k = chromeK

        UI.vGradient(graphics, 0f, 0f, w, h, Palette.backdropTop, Palette.backdropBottom)
        if (gridEnabled) drawGrid(graphics, w, h)

        guideX = null
        guideY = null
        val effectiveGrid = if (gridEnabled) gridSize else 0
        val snapping = !(InputConstants.isKeyDown(mc.window, InputConstants.KEY_LSHIFT) || InputConstants.isKeyDown(mc.window, InputConstants.KEY_RSHIFT))
        for (hud in huds) {
            hud.editorSelected = hud === selected
            hud.drawEditor(graphics, mx.toInt(), my.toInt(), effectiveGrid, if (snapping) ::snap else null)
        }

        // name tags last, so another element's content never hides one (the selected/dragged tag on top)
        graphics.nextStratum()
        huds.filter { !it.isDragging && !it.editorSelected }.forEach { it.drawEditorTag(graphics, k) }
        huds.filter { it.isDragging || it.editorSelected }.forEach { it.drawEditorTag(graphics, k) }

        // snapping guides (the vertical one starts below the floating toolbar)
        val guideTop = (TOOLBAR_Y + BAR_H + 4f) * k
        guideX?.let { UI.rect(graphics, it - 0.5f, guideTop, 1f, h - guideTop, 0f, Palette.accent) }
        guideY?.let { UI.rect(graphics, 0f, it - 0.5f, w, 1f, 0f, Palette.accent) }

        val hovered = huds.firstOrNull { it.isDragging } ?: huds.firstOrNull { it.isHovered(mx.toInt(), my.toInt()) }
        if (hovered != null) graphics.requestCursor(if (hovered.isDragging) CursorTypes.RESIZE_ALL else CursorTypes.POINTING_HAND)

        graphics.nextStratum()
        scaledAbout(graphics, w / 2f, 0f, k) {
            drawToolbar(graphics, toolbarX(mx), toolbarY(my), w)
            (hovered ?: selected)?.let { drawInfo(graphics, it, w) }
        }
        val hint = "Drag to move  ·  Scroll to resize  ·  Arrow keys nudge  ·  Hold Shift to place freely"
        scaledAbout(graphics, w / 2f, h, k) { UI.textMidCentered(graphics, hint, w / 2f, h - 14f, Palette.textSecondary, 7.4f) }

        Resolution.pop(graphics)
    }

    private var chromeK = 1f

    /** Toolbar-local mouse position (the toolbar is drawn scaled by [chromeK] about the top centre). */
    private fun toolbarX(mx: Float) = Resolution.width / 2f + (mx - Resolution.width / 2f) / chromeK
    private fun toolbarY(my: Float) = my / chromeK

    private inline fun scaledAbout(ctx: GuiGraphicsExtractor, px: Float, py: Float, k: Float, block: () -> Unit) {
        ctx.pose().pushMatrix()
        ctx.pose().translate(px, py)
        ctx.pose().scale(k, k)
        ctx.pose().translate(-px, -py)
        block()
        ctx.pose().popMatrix()
    }

    /** Snaps the dragged element's left/centre/right and top/centre/bottom to the screen edges and centre lines. */
    private fun snap(hud: HudElement) {
        val w = Resolution.width
        val h = Resolution.height
        val sw = hud.scaledWidth
        val sh = hud.scaledHeight
        val centerOffset = if (hud.centered()) sw / 2f else 0f

        val left = hud.x - centerOffset
        val xs = listOf(
            0f to 0f, w / 2f to sw / 2f, w to sw,          // left edge to screen left, centre to centre, right to right
        )
        for ((target, anchor) in xs) {
            if (abs(left + anchor - target) <= SNAP) {
                hud.x = target - anchor + centerOffset
                guideX = target.coerceIn(0.5f, w - 0.5f)
                break
            }
        }
        val ys = listOf(0f to 0f, h / 2f to sh / 2f, h to sh)
        for ((target, anchor) in ys) {
            if (abs(hud.y + anchor - target) <= SNAP) {
                hud.y = target - anchor
                guideY = target.coerceIn(0.5f, h - 0.5f)
                break
            }
        }
    }

    private fun drawGrid(ctx: GuiGraphicsExtractor, w: Float, h: Float) {
        val color = Palette.grid
        var x = 0f
        while (x <= w) {
            UI.rect(ctx, x, 0f, 0.5f, h, 0f, color)
            x += gridSize
        }
        var y = 0f
        while (y <= h) {
            UI.rect(ctx, 0f, y, w, 0.5f, 0f, color)
            y += gridSize
        }
        UI.rect(ctx, w / 2f - 0.5f, 0f, 1f, h, 0f, Palette.accentGlow)
        UI.rect(ctx, 0f, h / 2f - 0.5f, w, 1f, 0f, Palette.accentGlow)
    }

    private fun drawToolbar(ctx: GuiGraphicsExtractor, mx: Float, my: Float, screenW: Float) {
        val size = 7.4f
        val widths = Button.entries.associateWith { UI.width(buttonLabel(it), size, UIFont.Weight.MEDIUM) + 30f }
        val gap = 4f
        val pad = 4f
        val total = widths.values.sum() + gap * (widths.size - 1) + pad * 2f
        val x0 = (screenW - total) / 2f
        val y0 = TOOLBAR_Y
        UI.shadow(ctx, x0, y0, total, BAR_H, BAR_H / 2f, 14f, Palette.shadowStrong, offsetY = 4f)
        UI.rect(ctx, x0, y0, total, BAR_H, BAR_H / 2f, Palette.popover)
        UI.outline(ctx, x0, y0, total, BAR_H, BAR_H / 2f, Palette.outlineHover)

        var bx = x0 + pad
        buttons.clear()
        for (b in Button.entries) {
            val bw = widths.getValue(b)
            val r = Rect(bx, y0 + pad, bw, BAR_H - pad * 2f)
            buttons[b] = r
            val hot = buttonHover.getValue(b).update(r.contains(mx, my))
            if (hot > 0.5f) ctx.requestCursor(CursorTypes.POINTING_HAND)
            val confirming = b == Button.RESET && System.currentTimeMillis() < resetConfirmUntil
            val style = when {
                b == Button.DONE -> UI.ButtonStyle.PRIMARY
                confirming -> UI.ButtonStyle.DANGER
                b == Button.GRID && gridEnabled -> UI.ButtonStyle.SECONDARY
                else -> UI.ButtonStyle.GHOST
            }
            val radius = r.h / 2f
            if (b == Button.GRID && gridEnabled) {
                UI.rect(ctx, r.x, r.y, r.w, r.h, radius, Palette.accentPill)
                UI.outline(ctx, r.x, r.y, r.w, r.h, radius, Palette.accentBorder)
                val iconS = size + 2f
                val textW = UI.width(buttonLabel(b), size, UIFont.Weight.MEDIUM)
                val sx = r.x + (r.w - iconS - 4f - textW) / 2f
                UI.icon(ctx, Icon.GRID, sx, r.y + (r.h - iconS) / 2f, iconS, Palette.accentText)
                UI.textMid(ctx, buttonLabel(b), sx + iconS + 4f, r.y + r.h / 2f, Palette.accentText, size, UIFont.Weight.MEDIUM)
            }
            else UI.button(ctx, buttonLabel(b), r.x, r.y, r.w, r.h, if (confirming) 1f else hot, style, buttonIcon(b), size)
            bx += bw + gap
        }
    }

    private fun drawInfo(ctx: GuiGraphicsExtractor, hud: HudElement, screenW: Float) {
        val text = "${hud.displayName}   ${hud.x.toInt()}, ${hud.y.toInt()}   ${String.format(java.util.Locale.US, "%.1f", hud.scale)}×"
        val size = 6.8f
        val w = UI.width(text, size, UIFont.Weight.MEDIUM) + 16f
        val x = (screenW - w) / 2f
        val y = 12f + BAR_H + 6f
        UI.rect(ctx, x, y, w, 14f, 7f, UI.fade(Palette.popover, 0.92f))
        UI.outline(ctx, x, y, w, 14f, 7f, Palette.outline)
        UI.textMidCentered(ctx, text, screenW / 2f, y + 7f, Palette.textSecondary, size, UIFont.Weight.MEDIUM)
    }

    private fun clickButton(b: Button) {
        Style.playClickSound(1f)
        when (b) {
            Button.GRID -> gridEnabled = !gridEnabled
            Button.RESET -> {
                if (System.currentTimeMillis() >= resetConfirmUntil) {
                    resetConfirmUntil = System.currentTimeMillis() + 3000
                    return
                }
                resetConfirmUntil = 0L
                FeatureManager.hudElements.forEach {
                    it.x = HudElement.defaultX
                    it.y = HudElement.defaultY
                    it.scale = HudElement.defaultScale
                    it.defaults.invoke(it)
                }
            }
            Button.DONE -> onClose()
        }
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontal: Double, vertical: Double): Boolean {
        val mx = (mouseX / Resolution.scale).toFloat()
        val my = (mouseY / Resolution.scale).toFloat()

        if (buttons[Button.GRID]?.contains(toolbarX(mx), toolbarY(my)) == true) {
            gridEnabled = true
            gridSize = (gridSize + (if (vertical > 0) 5 else -5)).coerceIn(5, 50)
            return true
        }

        val hud = huds.find { it.isDragging } ?: huds.find { it.isHovered(mx.toInt(), my.toInt()) }
        if (hud != null) {
            hud.scale = (hud.scale + (vertical * 0.1).toFloat()).coerceIn(0.5f, 5.0f)
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
        val mx = (mouseButtonEvent.x / Resolution.scale).toFloat()
        val my = (mouseButtonEvent.y / Resolution.scale).toFloat()

        if (mouseButtonEvent.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            buttons.entries.firstOrNull { it.value.contains(toolbarX(mx), toolbarY(my)) }?.let {
                clickButton(it.key)
                return true
            }
            for (hud in huds.asReversed()) {
                hud.startDragging(mx.toInt(), my.toInt())
                if (hud.isDragging) {
                    selected = hud
                    return true
                }
            }
            selected = null
        }
        return super.mouseClicked(mouseButtonEvent, bl)
    }

    override fun mouseReleased(mouseButtonEvent: MouseButtonEvent): Boolean {
        for (hud in huds) hud.isDragging = false
        return super.mouseReleased(mouseButtonEvent)
    }

    override fun keyPressed(keyEvent: KeyEvent): Boolean {
        val hud = selected
        if (hud != null) {
            val step = if (keyEvent.hasShiftDown()) 10f else 1f
            when (keyEvent.key) {
                InputConstants.KEY_LEFT -> hud.x -= step
                InputConstants.KEY_RIGHT -> hud.x += step
                InputConstants.KEY_UP -> hud.y -= step
                InputConstants.KEY_DOWN -> hud.y += step
                else -> return super.keyPressed(keyEvent)
            }
            return true
        }
        return super.keyPressed(keyEvent)
    }

    override fun onClose() {
        huds.forEach { it.editorSelected = false }
        ConfigManager.save()
        mc.gui.setScreen(parent)
    }
}
