package com.laybalt.ui.clickgui.components

import com.laybalt.ui.Palette
import com.laybalt.ui.clickgui.UI
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent

/** Axis-aligned rectangle in canvas units. */
data class Rect(val x: Float, val y: Float, val w: Float, val h: Float) {
    val right get() = x + w
    val bottom get() = y + h
    fun contains(mx: Float, my: Float) = mx >= x && mx < x + w && my >= y && my < y + h
}

/**
 * A floating card (dropdown list, colour picker, sound list) anchored to a control. It is drawn above everything in
 * its own GUI stratum, gets input before the rows under it and closes on a click outside, Escape or when its anchor
 * scrolls out of view.
 */
abstract class Popover {
    var x = 0f
        private set
    var y = 0f
        private set

    abstract val width: Float
    abstract val height: Float

    /** The control this popover belongs to, in canvas coordinates (read every frame). */
    abstract fun anchor(): Rect

    abstract fun drawContent(ctx: GuiGraphicsExtractor, mx: Float, my: Float)

    open fun mouseClicked(mx: Float, my: Float, button: Int): Boolean = true
    open fun mouseReleased(button: Int) {}
    open fun mouseScrolled(mx: Float, my: Float, delta: Double): Boolean = true
    open fun keyPressed(event: KeyEvent): Boolean = false
    open fun charTyped(event: CharacterEvent): Boolean = false
    open fun onClose() {}

    fun contains(mx: Float, my: Float) = mx >= x && mx < x + width && my >= y && my < y + height

    /** Places the popover under the anchor (right-aligned to it), or above when it would leave [bounds]. */
    fun layout(bounds: Rect) {
        val a = anchor()
        x = (a.right - width).coerceIn(bounds.x + 6f, (bounds.right - width - 6f).coerceAtLeast(bounds.x + 6f))
        val below = a.bottom + 4f
        y = if (below + height <= bounds.bottom - 6f || a.y - 4f - height < bounds.y + 6f) below.coerceAtMost((bounds.bottom - height - 6f).coerceAtLeast(bounds.y + 6f))
        else a.y - 4f - height
    }

    fun draw(ctx: GuiGraphicsExtractor, mx: Float, my: Float) {
        UI.shadow(ctx, x, y, width, height, 8f, 14f, Palette.shadowStrong, offsetY = 4f)
        UI.rect(ctx, x, y, width, height, 8f, Palette.popover)
        UI.outline(ctx, x, y, width, height, 8f, Palette.outlineHover)
        drawContent(ctx, mx, my)
    }
}

/** The (single) open popover of the LayBalt screen. */
object Popovers {
    var current: Popover? = null
        private set

    private val appear = UI.Smooth(0f, 24f)

    fun open(popover: Popover) {
        current?.onClose()
        current = popover
        appear.snap(0f)
    }

    fun close() {
        val p = current ?: return
        current = null
        p.onClose()
    }

    fun isOpen(popover: Popover) = current === popover

    /**
     * Draws the open popover in a new stratum. It is closed when its anchor is no longer inside [visibleArea]
     * (e.g. scrolled away); [bounds] limits where it may be placed.
     */
    fun render(ctx: GuiGraphicsExtractor, mx: Float, my: Float, visibleArea: Rect, bounds: Rect) {
        val p = current ?: return
        val a = p.anchor()
        if (a.bottom < visibleArea.y || a.y > visibleArea.bottom) {
            close()
            return
        }
        p.layout(bounds)
        ctx.nextStratum()
        val t = appear.update(1f)
        val saved = UI.alpha
        UI.alpha = saved * t
        ctx.pose().pushMatrix()
        ctx.pose().translate(0f, (1f - t) * -3f)
        p.draw(ctx, mx, my)
        ctx.pose().popMatrix()
        UI.alpha = saved
    }

    /** Routes a click: inside goes to the popover, outside closes it. Returns true when the click was consumed. */
    fun mouseClicked(mx: Float, my: Float, button: Int): Boolean {
        val p = current ?: return false
        if (p.contains(mx, my)) {
            p.mouseClicked(mx, my, button)
            return true
        }
        close()
        // A click on the popover's own control only closes it (instead of reopening it right away).
        return p.anchor().contains(mx, my)
    }

    fun mouseReleased(button: Int) = current?.mouseReleased(button)

    fun mouseScrolled(mx: Float, my: Float, delta: Double): Boolean {
        val p = current ?: return false
        if (p.contains(mx, my)) return p.mouseScrolled(mx, my, delta)
        close()
        return false
    }

    fun keyPressed(event: KeyEvent): Boolean {
        val p = current ?: return false
        if (p.keyPressed(event)) return true
        if (event.key == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
            close()
            return true
        }
        return false
    }

    fun charTyped(event: CharacterEvent): Boolean = current?.charTyped(event) ?: false
}
