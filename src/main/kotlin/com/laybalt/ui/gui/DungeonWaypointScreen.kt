package com.laybalt.ui.gui

import com.laybalt.features.impl.dungeon.DungeonWaypoints
import com.laybalt.ui.Palette
import com.laybalt.ui.clickgui.UI
import com.laybalt.ui.clickgui.components.Rect
import com.laybalt.ui.clickgui.components.settings.Style
import com.laybalt.ui.render.Icon
import com.laybalt.ui.text.UIFont
import com.laybalt.utils.ColorUtils.withAlpha
import com.mojang.blaze3d.platform.InputConstants
import com.mojang.blaze3d.platform.cursor.CursorTypes
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.KeyEvent
import net.minecraft.core.BlockPos
import java.awt.Color

/** Small editor opened in a dungeon room to create or change the waypoint on the looked-at block. */
class DungeonWaypointScreen(
    private val roomName: String,
    private val absolutePos: BlockPos,
    private val relativePos: BlockPos,
    private val initialState: DungeonWaypoints.DungeonWaypoint? = null
): LayBaltSubScreen("Waypoint Editor") {
    private companion object {
        const val ROW_H = 30f
        const val ROW_GAP = 5f
        const val SWATCH_R = 9f
        const val FOOTER_H = 42f
        val colorPalette = listOf(
            Color.GREEN, Color.RED, Color.BLUE, Color.CYAN,
            Color.MAGENTA, Color.YELLOW, Color.WHITE, Color.BLACK, Color.ORANGE
        )
        val colorNames = listOf("Green", "Red", "Blue", "Cyan", "Magenta", "Yellow", "White", "Black", "Orange")
    }

    private class Toggle(val label: String, val description: String, val isEnabled: () -> Boolean, val setEnabled: (Boolean) -> Unit) {
        val hover = UI.Smooth()
        val knob = UI.Smooth(0f, 18f)
        var rect = Rect(0f, 0f, 0f, 0f)
    }

    private var filled = true
    private var outline = true
    private var phase = true
    private var selectedColorIndex = 0

    private val toggles = listOf(
        Toggle("Filled", "Fill the block with a see-through colour", { filled }, { filled = it }),
        Toggle("Outline", "Draw the edges of the block", { outline }, { outline = it }),
        Toggle("See through walls", "Keep the waypoint visible behind blocks", { phase }, { phase = it })
    )

    private val swatchHover = List(colorPalette.size) { UI.Smooth() }
    private val swatchRects = ArrayList<Rect>()
    private val selectedRing = UI.Smooth(0f, 20f)
    private var ringX = Float.NaN
    private val saveHover = UI.Smooth()
    private val cancelHover = UI.Smooth()
    private var saveRect = Rect(0f, 0f, 0f, 0f)
    private var cancelRect = Rect(0f, 0f, 0f, 0f)

    private val valid get() = filled || outline

    override val heading get() = if (initialState == null) "New waypoint" else "Edit waypoint"
    override val subheading get() = "Secret waypoint for this dungeon room"
    override val headingIcon get() = Icon.PIN

    override fun windowSize(canvasW: Float, canvasH: Float) = 380f to 338f

    override fun returnTo(): Screen? = null

    override fun init() {
        super.init()
        val state = initialState ?: return
        filled = state.filled
        outline = state.outline
        phase = state.phase
        val initialRgb = state.color.rgb and 0xFFFFFF
        selectedColorIndex = colorPalette.indexOfFirst { (it.rgb and 0xFFFFFF) == initialRgb }.coerceAtLeast(0)
    }

    override fun drawBody(ctx: GuiGraphicsExtractor, mx: Float, my: Float) {
        val b = body
        val x = b.x + PAD
        val w = b.w - PAD * 2f
        var y = b.y + 12f

        // room + position
        UI.rect(ctx, x, y, w, ROW_H, 7f, Palette.card)
        UI.outline(ctx, x, y, w, ROW_H, 7f, Palette.divider)
        val coords = "${absolutePos.x}, ${absolutePos.y}, ${absolutePos.z}"
        val coordsW = UI.chipWidth(coords, 6.6f, padX = 6f)
        UI.textMid(ctx, "ROOM", x + 10f, y + ROW_H / 2f, Palette.textMuted, 5.8f, UIFont.Weight.SEMIBOLD)
        val nameX = x + 10f + UI.width("ROOM", 5.8f, UIFont.Weight.SEMIBOLD) + 8f
        UI.textMid(ctx, UIFont.trim(roomName, x + w - 10f - coordsW - 8f - nameX, 7.8f, UIFont.Weight.SEMIBOLD), nameX, y + ROW_H / 2f, Palette.accentText, 7.8f, UIFont.Weight.SEMIBOLD)
        UI.chip(ctx, coords, x + w - 10f - coordsW, y + (ROW_H - 14f) / 2f, 14f, Palette.chip, Palette.textSecondary, 6.6f, padX = 6f)
        y += ROW_H + 14f

        // style toggles
        UI.textMid(ctx, "STYLE", x + 2f, y + 4f, Palette.textMuted, 5.8f, UIFont.Weight.SEMIBOLD)
        y += 12f
        for (t in toggles) {
            t.rect = Rect(x, y, w, ROW_H)
            drawToggle(ctx, t, mx, my)
            y += ROW_H + ROW_GAP
        }
        y += 9f

        // colour
        UI.textMid(ctx, "COLOUR", x + 2f, y + 4f, Palette.textMuted, 5.8f, UIFont.Weight.SEMIBOLD)
        UI.textMid(ctx, colorNames[selectedColorIndex], x + 2f + UI.width("COLOUR", 5.8f, UIFont.Weight.SEMIBOLD) + 6f, y + 4f, Palette.textSecondary, 6.6f, UIFont.Weight.MEDIUM)
        y += 14f
        drawSwatches(ctx, x, y, w, mx, my)

        drawFooter(ctx, mx, my)
    }

    private fun drawToggle(ctx: GuiGraphicsExtractor, t: Toggle, mx: Float, my: Float) {
        val r = t.rect
        val hot = t.hover.update(r.contains(mx, my))
        if (hot > 0.5f) ctx.requestCursor(CursorTypes.POINTING_HAND)
        val on = t.isEnabled()
        UI.rect(ctx, r.x, r.y, r.w, r.h, 7f, UI.mix(Palette.card, Palette.cardHover, hot))
        UI.outline(ctx, r.x, r.y, r.w, r.h, 7f, UI.mix(Palette.divider, Palette.outline, hot))
        UI.text(ctx, t.label, r.x + 10f, r.y + 4.5f, if (on) Palette.textPrimary else Palette.textSecondary, 7.8f, UIFont.Weight.MEDIUM)
        UI.text(ctx, t.description, r.x + 10f, r.y + 16.5f, Palette.textMuted, 6.4f)
        UI.switch(ctx, r.right - 10f - UI.SWITCH_W, r.y + (r.h - UI.SWITCH_H) / 2f, t.knob.update(on), hot)
    }

    private fun drawSwatches(ctx: GuiGraphicsExtractor, x: Float, y: Float, w: Float, mx: Float, my: Float) {
        // preview tile on the right, swatches spread over the rest
        val previewS = 24f
        val px = x + w - previewS - 2f
        val step = (px - 14f - x - SWATCH_R * 2f) / (colorPalette.size - 1)
        swatchRects.clear()
        colorPalette.forEachIndexed { i, _ -> swatchRects += Rect(x + 2f + i * step, y, SWATCH_R * 2f, SWATCH_R * 2f) }

        val sel = swatchRects[selectedColorIndex]
        ringX = if (ringX.isNaN()) sel.x else UI.approach(ringX, sel.x, UI.dt, 22f)
        UI.circle(ctx, ringX + SWATCH_R, y + SWATCH_R, SWATCH_R + 3f, UI.fade(Palette.accent, selectedRing.update(1f)))
        UI.circle(ctx, ringX + SWATCH_R, y + SWATCH_R, SWATCH_R + 1.6f, Palette.surface)

        colorPalette.forEachIndexed { i, color ->
            val r = swatchRects[i]
            val hot = swatchHover[i].update(r.contains(mx, my))
            if (hot > 0.5f) ctx.requestCursor(CursorTypes.POINTING_HAND)
            val radius = SWATCH_R - 1f + hot
            UI.circle(ctx, r.x + SWATCH_R, r.y + SWATCH_R, radius, color)
            UI.outline(ctx, r.x + SWATCH_R - radius, r.y + SWATCH_R - radius, radius * 2f, radius * 2f, radius, Palette.outlineHover)
            if (i == selectedColorIndex) {
                val dark = (color.red * 299 + color.green * 587 + color.blue * 114) / 1000 > 150
                UI.icon(ctx, Icon.CHECK, r.x + SWATCH_R - 5.5f, r.y + SWATCH_R - 5.5f, 11f, if (dark) Color(20, 16, 30) else Color(250, 248, 255), 2.2f)
            }
        }

        // what the waypoint will look like
        val base = colorPalette[selectedColorIndex]
        val py = y + SWATCH_R - previewS / 2f
        UI.rect(ctx, px, py, previewS, previewS, 4f, Palette.input)
        if (filled) UI.rect(ctx, px + 3f, py + 3f, previewS - 6f, previewS - 6f, 2f, base.withAlpha(90))
        if (outline) UI.outline(ctx, px + 3f, py + 3f, previewS - 6f, previewS - 6f, 2f, base, 1.5f)
        if (!valid) UI.icon(ctx, Icon.CLOSE, px + 6f, py + 6f, 12f, Palette.textMuted)
    }

    private fun drawFooter(ctx: GuiGraphicsExtractor, mx: Float, my: Float) {
        val f = Rect(body.x, body.bottom - FOOTER_H, body.w, FOOTER_H)
        UI.hLine(ctx, f.x, f.y, f.w, Palette.divider)
        val bh = 22f
        val by = f.y + (f.h - bh) / 2f
        val saveW = UI.width("Save", 7.4f, UIFont.Weight.MEDIUM) + 38f
        saveRect = Rect(f.right - PAD - saveW, by, saveW, bh)
        val cancelW = UI.width("Cancel", 7.4f, UIFont.Weight.MEDIUM) + 26f
        cancelRect = Rect(saveRect.x - 8f - cancelW, by, cancelW, bh)

        val ch = cancelHover.update(cancelRect.contains(mx, my))
        val sh = saveHover.update(valid && saveRect.contains(mx, my))
        if (ch > 0.5f || sh > 0.5f) ctx.requestCursor(CursorTypes.POINTING_HAND)
        else if (!valid && saveRect.contains(mx, my)) ctx.requestCursor(CursorTypes.NOT_ALLOWED)
        UI.button(ctx, "Cancel", cancelRect.x, cancelRect.y, cancelRect.w, cancelRect.h, ch, UI.ButtonStyle.GHOST, size = 7.4f)
        val saved = UI.alpha
        if (!valid) UI.alpha = saved * 0.4f
        UI.button(ctx, "Save", saveRect.x, saveRect.y, saveRect.w, saveRect.h, sh, UI.ButtonStyle.PRIMARY, Icon.CHECK, 7.4f)
        UI.alpha = saved

        if (!valid) {
            UI.icon(ctx, Icon.WARNING, f.x + PAD, f.y + (f.h - 12f) / 2f, 12f, Palette.warning)
            UI.textMid(ctx, UIFont.trim("Turn on Filled or Outline", cancelRect.x - 12f - (f.x + PAD + 17f), 7f), f.x + PAD + 17f, f.y + f.h / 2f, Palette.warning, 7f, UIFont.Weight.MEDIUM)
        }
        else UI.textMid(ctx, "Enter to save  ·  Esc to cancel", f.x + PAD, f.y + f.h / 2f, Palette.textMuted, 7f)
    }

    override fun onMouseClicked(mx: Float, my: Float, button: Int): Boolean {
        if (button != InputConstants.MOUSE_BUTTON_LEFT) return false
        toggles.firstOrNull { it.rect.contains(mx, my) }?.let {
            it.setEnabled(!it.isEnabled())
            Style.playClickSound(if (it.isEnabled()) 1.1f else 0.9f)
            return true
        }
        swatchRects.indexOfFirst { it.contains(mx, my) }.takeIf { it >= 0 }?.let {
            selectedColorIndex = it
            Style.playClickSound(1.15f)
            return true
        }
        if (saveRect.contains(mx, my)) {
            trySave()
            return true
        }
        if (cancelRect.contains(mx, my)) {
            Style.playClickSound(0.9f)
            onClose()
            return true
        }
        return window.contains(mx, my)
    }

    override fun onKeyPressed(event: KeyEvent): Boolean {
        if (event.key == InputConstants.KEY_RETURN || event.key == InputConstants.KEY_NUMPADENTER) {
            trySave()
            return true
        }
        return false
    }

    private fun trySave() {
        if (!valid) {
            Style.playClickSound(0.6f)
            return
        }
        Style.playClickSound(1.2f)
        val base = colorPalette[selectedColorIndex]
        val finalColor = if (filled) base.withAlpha(60) else base
        DungeonWaypoints.saveWaypoint(absolutePos, relativePos, roomName, finalColor, filled, outline, phase)
        onClose()
    }
}
