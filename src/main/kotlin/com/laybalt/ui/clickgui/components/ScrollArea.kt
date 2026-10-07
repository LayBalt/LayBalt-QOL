package com.laybalt.ui.clickgui.components

import com.laybalt.ui.Palette
import com.laybalt.ui.clickgui.UI
import net.minecraft.client.gui.GuiGraphicsExtractor

/**
 * Smooth vertical scrolling for a viewport: wheel input moves [target], [offset] eases toward it every frame, and a
 * slim rounded scrollbar (wider while hovered or dragged) can be dragged or clicked to jump.
 */
class ScrollArea(private val wheelStep: Float = 42f) {
    var target = 0f
        private set
    var offset = 0f
        private set
    var max = 0f
        private set

    private var barX = 0f
    private var viewY = 0f
    private var viewH = 0f
    private var contentH = 0f

    private val hover = UI.Smooth()
    private val visible = UI.Smooth()
    private var dragging = false
    private var dragStartY = 0f
    private var dragStartTarget = 0f

    val canScroll get() = max > 0.5f

    /** Updates the geometry for this frame and advances the easing; [barX] is the scrollbar's right edge. */
    fun update(barX: Float, viewY: Float, viewH: Float, contentH: Float, mouseY: Float) {
        this.barX = barX
        this.viewY = viewY
        this.viewH = viewH
        this.contentH = contentH
        max = (contentH - viewH).coerceAtLeast(0f)
        if (dragging) {
            val travel = (viewH - thumbH()).coerceAtLeast(1f)
            target = (dragStartTarget + (mouseY - dragStartY) / travel * max)
        }
        target = target.coerceIn(0f, max)
        offset = if (dragging) target else UI.approach(offset, target, UI.dt, 18f)
        offset = offset.coerceIn(0f, max)
    }

    fun scroll(delta: Double): Boolean {
        if (!canScroll) return false
        target = (target - (delta * wheelStep).toFloat()).coerceIn(0f, max)
        return true
    }

    /** Sets the scroll position; it is clamped to the content on the next [update] (the content may change first). */
    fun set(value: Float, instant: Boolean = false) {
        target = value.coerceAtLeast(0f)
        if (instant) offset = target
    }

    /** Scrolls so the span [top]..[top]+[height] (content coordinates) is visible with [margin] around it. */
    fun reveal(top: Float, height: Float, margin: Float = 12f) {
        if (top - margin < target) target = (top - margin).coerceAtLeast(0f)
        else if (top + height + margin > target + viewH) target = (top + height + margin - viewH).coerceIn(0f, max.coerceAtLeast(0f))
    }

    private fun thumbH() = if (contentH <= 0f) viewH else (viewH / contentH * viewH).coerceIn(18f, viewH)
    private fun thumbY() = viewY + if (max <= 0f) 0f else offset / max * (viewH - thumbH())

    private fun overBar(mx: Float, my: Float) = mx >= barX - 8f && mx <= barX + 1f && my >= viewY && my <= viewY + viewH

    fun draw(ctx: GuiGraphicsExtractor, mx: Float, my: Float) {
        visible.update(canScroll)
        if (visible.value < 0.01f) return
        hover.update(dragging || overBar(mx, my))
        val w = 3f + 2f * hover.value
        val x = barX - w - 2f
        val color = UI.mix(Palette.outlineHover, Palette.accentBorder, hover.value)
        UI.rect(ctx, x, thumbY(), w, thumbH(), w / 2f, UI.fade(color, visible.value))
    }

    fun mouseClicked(mx: Float, my: Float): Boolean {
        if (!canScroll || !overBar(mx, my)) return false
        val ty = thumbY()
        val th = thumbH()
        if (my < ty || my > ty + th) {
            val ratio = ((my - viewY - th / 2f) / (viewH - th).coerceAtLeast(1f)).coerceIn(0f, 1f)
            target = ratio * max
            offset = target
        }
        dragging = true
        dragStartY = my
        dragStartTarget = target
        return true
    }

    fun mouseReleased() {
        dragging = false
    }
}
