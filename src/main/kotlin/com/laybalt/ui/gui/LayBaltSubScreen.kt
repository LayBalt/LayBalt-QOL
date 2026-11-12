package com.laybalt.ui.gui

import com.laybalt.LayBaltQOL.mc
import com.laybalt.ui.Palette
import com.laybalt.ui.clickgui.TooltipManager
import com.laybalt.ui.clickgui.UI
import com.laybalt.ui.clickgui.components.Rect
import com.laybalt.ui.clickgui.components.settings.Style
import com.laybalt.ui.render.Icon
import com.laybalt.ui.render.Shapes.roundedRectGradient
import com.laybalt.ui.notification.NotificationManager
import com.laybalt.ui.text.UIFont
import com.laybalt.ui.utils.Resolution
import com.mojang.blaze3d.platform.InputConstants
import com.mojang.blaze3d.platform.cursor.CursorTypes
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component

/**
 * Shared frame of the smaller LayBalt windows (Sound Manager, Command Shortcuts, Waypoint editor): a centred rounded
 * window with shadow, a header (icon tile, title, subtitle, close button) and the same fade + scale open/close
 * animation as the main screen. Subclasses lay out and draw [body] and receive input in canvas units.
 *
 * When the window closes, [returnTo] is shown (the screen it was opened from, or nothing for in-game editors).
 */
abstract class LayBaltSubScreen(name: String): Screen(Component.literal(name)) {
    protected companion object {
        const val OPEN_MS = 180f
        const val CLOSE_MS = 130f
        const val HEADER_H = 50f
        const val PAD = 16f
        const val RADIUS = 10f
        const val TITLE_SIZE = 10.5f
        const val SUBTITLE_SIZE = 7f
    }

    /** Header texts and icon. */
    protected abstract val heading: String
    protected open val subheading: String? get() = null
    protected abstract val headingIcon: Icon

    /** Window size for a canvas of [canvasW] x [canvasH]. */
    protected abstract fun windowSize(canvasW: Float, canvasH: Float): Pair<Float, Float>

    /** The screen shown after this one closes. */
    protected abstract fun returnTo(): Screen?

    /** Called once when the window starts closing (save edits here). */
    protected open fun onClosing() {}

    protected abstract fun drawBody(ctx: GuiGraphicsExtractor, mx: Float, my: Float)

    /** Right limit of the header subtitle (subclasses with header extras move it left). */
    protected open fun subtitleRight() = closeRect.x - 12f

    /** Extra header content to the left of the close button (e.g. a search field); [right] is where it may end. */
    protected open fun drawHeaderExtras(ctx: GuiGraphicsExtractor, right: Float, mx: Float, my: Float) {}

    protected open fun onMouseClicked(mx: Float, my: Float, button: Int): Boolean = false
    protected open fun onMouseReleased(button: Int) {}
    protected open fun onMouseScrolled(mx: Float, my: Float, delta: Double): Boolean = false
    protected open fun onKeyPressed(event: KeyEvent): Boolean = false
    protected open fun onCharTyped(event: CharacterEvent): Boolean = false

    protected var window = Rect(0f, 0f, 0f, 0f)
        private set
    /** Area under the header. */
    protected var body = Rect(0f, 0f, 0f, 0f)
        private set
    protected var closeRect = Rect(0f, 0f, 0f, 0f)
        private set

    private var openedAt = System.currentTimeMillis()
    private var closingAt = 0L
    private var closeScheduled = false
    private val closeHover = UI.Smooth()

    protected val closing get() = closingAt != 0L

    private fun layout() {
        val cw = Resolution.width
        val ch = Resolution.height
        val (w0, h0) = windowSize(cw, ch)
        val w = w0.coerceAtMost(cw - 20f)
        val h = h0.coerceAtMost(ch - 20f)
        window = Rect((cw - w) / 2f, (ch - h) / 2f, w, h)
        body = Rect(window.x, window.y + HEADER_H, window.w, window.h - HEADER_H)
        closeRect = Rect(window.right - PAD - 18f, window.y + (HEADER_H - 20f) / 2f, 20f, 20f)
    }

    override fun added() {
        super.added()
        openedAt = System.currentTimeMillis()
        closingAt = 0L
        closeScheduled = false
    }

    override fun extractRenderState(ctx: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        UI.beginFrame()
        Resolution.pushUi(ctx)
        canvasScale = Resolution.scale
        val mx = mouseX / canvasScale
        val my = mouseY / canvasScale
        layout()

        val now = System.currentTimeMillis()
        val t = if (closingAt != 0L) {
            val p = 1f - (now - closingAt) / CLOSE_MS
            if (p <= 0f) {
                Resolution.pop(ctx)
                finishClose()
                return
            }
            p
        }
        else UI.easeOutCubic((now - openedAt) / OPEN_MS)

        UI.alpha = t
        UI.vGradient(ctx, 0f, 0f, Resolution.width, Resolution.height, Palette.backdropTop, Palette.scrim)
        UI.shadow(ctx, window.x, window.y, window.w, window.h, RADIUS, 60f, UI.fade(Palette.accent, 0.09f * t), spread = -10f)

        val scale = 0.96f + 0.04f * t
        val cx = window.x + window.w / 2f
        val cy = window.y + window.h / 2f
        ctx.pose().pushMatrix()
        if (scale < 0.9999f) {
            ctx.pose().translate(cx, cy)
            ctx.pose().scale(scale, scale)
            ctx.pose().translate(-cx, -cy)
        }
        UIFont.transientScale = scale
        val interactive = closingAt == 0L
        val wmx = if (interactive) cx + (mx - cx) / scale else -10000f
        val wmy = if (interactive) cy + (my - cy) / scale else -10000f

        drawFrame(ctx, wmx, wmy)
        drawBody(ctx, wmx, wmy)

        ctx.pose().popMatrix()
        UIFont.transientScale = 1f
        NotificationManager.anchorTo(window.right * canvasScale, window.bottom * canvasScale)
        TooltipManager.draw(ctx, Resolution.width, Resolution.height)
        Resolution.pop(ctx)
        UI.alpha = 1f
    }

    private fun drawFrame(ctx: GuiGraphicsExtractor, mx: Float, my: Float) {
        val w = window
        UI.shadow(ctx, w.x, w.y, w.w, w.h, RADIUS, 26f, Palette.shadowStrong, offsetY = 8f)
        UI.rect(ctx, w.x, w.y, w.w, w.h, RADIUS, Palette.surface)
        UI.rect(ctx, w.x, w.y, w.w, HEADER_H, RADIUS, RADIUS, 0f, 0f, Palette.surfaceRaised)
        UI.hLine(ctx, w.x, w.y + HEADER_H - UI.hair(ctx), w.w, Palette.divider)

        // icon tile
        val tile = 24f
        val tx = w.x + PAD
        val ty = w.y + (HEADER_H - tile) / 2f
        UI.shadow(ctx, tx, ty, tile, tile, 7f, 7f, UI.fade(Palette.accent, 0.3f), offsetY = 1.5f)
        ctx.roundedRectGradient(tx, ty, tile, tile, 7f, UI.c(UI.fade(Palette.accentText, 0.95f)), UI.c(Palette.accent))
        UI.icon(ctx, headingIcon, tx + 5f, ty + 5f, 14f, java.awt.Color(252, 250, 255), 1.8f)

        val textX = tx + tile + 10f
        val sub = subheading
        if (sub == null) UI.textMid(ctx, heading, textX, w.y + HEADER_H / 2f, Palette.textPrimary, TITLE_SIZE, UIFont.Weight.BOLD)
        else {
            UI.textMid(ctx, heading, textX, w.y + HEADER_H / 2f - 5.5f, Palette.textPrimary, TITLE_SIZE, UIFont.Weight.BOLD)
            val maxSub = subtitleRight() - textX
            UI.textMid(ctx, UIFont.trim(sub, maxSub, SUBTITLE_SIZE), textX, w.y + HEADER_H / 2f + 7f, Palette.textMuted, SUBTITLE_SIZE)
        }

        drawHeaderExtras(ctx, closeRect.x - 8f, mx, my)

        val ch = closeHover.update(closeRect.contains(mx, my))
        if (ch > 0.5f) ctx.requestCursor(CursorTypes.POINTING_HAND)
        UI.iconButton(ctx, Icon.CLOSE, closeRect.x, closeRect.y, closeRect.w, ch, Palette.textSecondary, Palette.textPrimary, 0.5f)

        UI.outline(ctx, w.x, w.y, w.w, w.h, RADIUS, Palette.outline)
    }

    // ------------------------------------------------------------------ input

    /** Canvas scale of the last frame (input arrives between frames, after other code may have re-pushed [Resolution]). */
    private var canvasScale = 1f

    private fun canvasX(x: Double) = (x / canvasScale).toFloat()
    private fun canvasY(y: Double) = (y / canvasScale).toFloat()

    final override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        if (closing) return true
        val mx = canvasX(event.x)
        val my = canvasY(event.y)
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && closeRect.contains(mx, my)) {
            Style.playClickSound(0.9f)
            onClose()
            return true
        }
        if (onMouseClicked(mx, my, event.button())) return true
        return super.mouseClicked(event, doubleClick)
    }

    final override fun mouseReleased(event: MouseButtonEvent): Boolean {
        onMouseReleased(event.button())
        return super.mouseReleased(event)
    }

    final override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontal: Double, vertical: Double): Boolean {
        if (closing) return true
        if (onMouseScrolled(canvasX(mouseX), canvasY(mouseY), vertical)) return true
        return super.mouseScrolled(mouseX, mouseY, horizontal, vertical)
    }

    final override fun keyPressed(event: KeyEvent): Boolean {
        if (closing) return true
        if (onKeyPressed(event)) return true
        if (event.key == InputConstants.KEY_ESCAPE) {
            onClose()
            return true
        }
        return super.keyPressed(event)
    }

    final override fun charTyped(event: CharacterEvent): Boolean {
        if (closing) return true
        if (onCharTyped(event)) return true
        return super.charTyped(event)
    }

    // ------------------------------------------------------------------ lifecycle

    override fun shouldCloseOnEsc() = true

    final override fun onClose() {
        if (closing) return
        closingAt = System.currentTimeMillis()
        onClosing()
    }

    private fun finishClose() {
        UI.alpha = 1f
        UIFont.transientScale = 1f
        if (closeScheduled) return
        closeScheduled = true
        val next = returnTo()
        mc.execute { if (mc.gui.screen() === this) mc.gui.setScreen(next) }
    }

    /** Centred icon, title and hint for an empty list. */
    protected fun drawEmptyState(ctx: GuiGraphicsExtractor, area: Rect, icon: Icon, title: String, hint: String?) {
        val cx = area.x + area.w / 2f
        val cy = area.y + area.h / 2f - 14f
        UI.circle(ctx, cx, cy, 16f, Palette.chip)
        UI.icon(ctx, icon, cx - 8f, cy - 8f, 16f, Palette.textMuted)
        UI.textMidCentered(ctx, title, cx, cy + 28f, Palette.textSecondary, 8f, UIFont.Weight.MEDIUM)
        if (hint != null) UI.textMidCentered(ctx, hint, cx, cy + 40f, Palette.textMuted, 7f)
    }
}
