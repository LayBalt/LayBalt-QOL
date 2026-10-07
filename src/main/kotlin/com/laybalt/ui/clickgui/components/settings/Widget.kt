package com.laybalt.ui.clickgui.components.settings

import com.laybalt.config.ConfigHolder
import com.laybalt.ui.Palette
import com.laybalt.ui.clickgui.UI
import com.laybalt.ui.clickgui.components.Rect
import com.laybalt.ui.text.UIFont
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import java.awt.Color

/**
 * One setting row in the settings panel. The panel sets [x], [y] and [width] every frame before [draw]; all
 * coordinates are canvas units (see [com.laybalt.ui.utils.Resolution]).
 */
abstract class Widget<T>(val config: ConfigHolder<T>) {
    companion object {
        const val ROW_H = 26f
        const val RADIUS = 7f
        const val PAD = 10f
        const val LABEL_SIZE = 7.8f
        const val VALUE_SIZE = 7.2f
    }

    val name get() = config.name

    open var value: T
        get() = config.value
        set(value) {
            config.value = value
        }

    var x = 0f
    var y = 0f
    var width = 0f
    open val height: Float get() = ROW_H

    /** Rows highlighted from a search result flash their border for a moment. */
    var highlightUntil = 0L

    protected val hover = UI.Smooth()

    abstract fun draw(ctx: GuiGraphicsExtractor, mx: Float, my: Float)
    open fun mouseClicked(mx: Float, my: Float, button: Int): Boolean = false

    /** True while the widget must receive the next click/key before anything else (keybind listening). */
    open val capturing: Boolean get() = false

    /** True while a text field of this widget has keyboard focus. */
    open val typing: Boolean get() = false

    open fun clearFocus() {}
    open fun mouseReleased(button: Int) {}
    open fun mouseScrolled(mx: Float, my: Float, delta: Double) = false
    open fun keyPressed(event: KeyEvent) = false
    open fun charTyped(event: CharacterEvent) = false

    fun isHovered(mx: Float, my: Float, h: Float = height) = UI.inside(mx, my, x, y, width, h)

    /** Region whose hover shows the setting's description tooltip. */
    open fun tooltipArea() = Rect(x, y, width, ROW_H.coerceAtMost(height))

    /** Rounded row background (+ hover tint and search highlight) of height [h]. */
    protected fun drawRow(ctx: GuiGraphicsExtractor, mx: Float, my: Float, h: Float = height, active: Boolean = false) {
        val t = hover.update(isHovered(mx, my, h) || active)
        UI.rect(ctx, x, y, width, h, RADIUS, UI.mix(Palette.card, Palette.cardHover, t))
        UI.outline(ctx, x, y, width, h, RADIUS, UI.mix(Palette.divider, Palette.outline, t))
        val left = highlightUntil - System.currentTimeMillis()
        if (left > 0) UI.outline(ctx, x, y, width, h, RADIUS, UI.fade(Palette.accent, (left / 1400f).coerceIn(0f, 1f)), 1.2f)
    }

    /** Setting name on the left of a row, vertically centred in the first [ROW_H] units. */
    protected fun drawLabel(ctx: GuiGraphicsExtractor, maxWidth: Float = width * 0.55f, color: Color = Palette.textPrimary) =
        UI.textMid(ctx, UIFont.trim(name, maxWidth, LABEL_SIZE, UIFont.Weight.MEDIUM), x + PAD, y + ROW_H / 2f, color, LABEL_SIZE, UIFont.Weight.MEDIUM)

    /** Rounded "select" box (value + chevron) right-aligned in the first row; returns its rectangle. */
    protected fun selectBox(text: String, maxWidth: Float = width * 0.45f): Rect {
        val w = (UI.width(text, VALUE_SIZE) + 26f).coerceIn(64f, maxWidth.coerceAtLeast(64f))
        return Rect(x + width - PAD - w + 3f, y + (ROW_H - 18f) / 2f, w, 18f)
    }

    protected fun drawSelectBox(ctx: GuiGraphicsExtractor, box: Rect, text: String, mx: Float, my: Float, open: Boolean, swatch: Color? = null) {
        val hot = open || box.contains(mx, my)
        UI.rect(ctx, box.x, box.y, box.w, box.h, 5f, if (hot) Palette.inputHover else Palette.input)
        UI.outline(ctx, box.x, box.y, box.w, box.h, 5f, if (open) Palette.accentBorder else if (hot) Palette.outlineHover else Palette.outline)
        var tx = box.x + 7f
        if (swatch != null) {
            UI.rect(ctx, tx, box.y + 5f, 8f, 8f, 2.5f, swatch)
            tx += 12f
        }
        val maxText = box.right - 17f - tx
        UI.textMid(ctx, UIFont.trim(text, maxText, VALUE_SIZE), tx, box.y + box.h / 2f, if (open) Palette.textPrimary else Palette.textSecondary, VALUE_SIZE)
        UI.icon(ctx, if (open) com.laybalt.ui.render.Icon.CHEVRON_UP else com.laybalt.ui.render.Icon.CHEVRON_DOWN, box.right - 14f, box.y + 5f, 8f, Palette.textSecondary)
    }
}
