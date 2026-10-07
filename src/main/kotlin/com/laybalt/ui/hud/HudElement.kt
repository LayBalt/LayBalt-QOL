package com.laybalt.ui.hud

import com.laybalt.ui.Palette
import com.laybalt.ui.clickgui.UI
import com.laybalt.ui.text.UIFont
import net.minecraft.client.gui.GuiGraphicsExtractor
import kotlin.math.roundToInt
import kotlin.reflect.jvm.jvmName

abstract class HudElement {
    open val name = this::class.simpleName ?: this::class.jvmName
    /** Name shown in the HUD editor (the config id [name] humanised by default). */
    open val displayName: String get() = humanize(name)

    abstract val toggle: Boolean
    open val shouldDraw = true
    open val centered: () -> Boolean = { false }

    var x = defaultX
    var y = defaultY
    var scale = defaultScale

    var width = 0f
    var height = 0f

    var defaults: HudElement.() -> Unit = {
        x = defaultX
        y = defaultY
        scale = defaultScale
    }

    var isDragging = false
    private var dragX = 0f
    private var dragY = 0f

    /** Set by the HUD editor for the element that was clicked last. */
    var editorSelected = false
    private val editorHover = UI.Smooth()
    private var editorHot = 0f

    abstract fun draw(ctx: GuiGraphicsExtractor, example: Boolean): Pair<Number, Number>

    /** Left edge of the drawn element (centred elements are drawn around [x]). */
    val left get() = x - if (centered()) width * scale / 2f else 0f
    val scaledWidth get() = width * scale
    val scaledHeight get() = height * scale

    /** HUD editor frame: translucent rounded box with an outline (accent when selected); the name tag is [drawEditorTag]. */
    open fun drawBackground(ctx: GuiGraphicsExtractor, mx: Int, my: Int) = drawEditorFrame(ctx, mx.toFloat(), my.toFloat())

    fun drawEditorFrame(ctx: GuiGraphicsExtractor, mx: Float, my: Float) {
        val bx = left - 2f
        val by = y - 2f
        val bw = scaledWidth + 4f
        val bh = scaledHeight + 4f
        val active = isDragging || editorSelected
        val hot = editorHover.update(active || isHovered(mx.toInt(), my.toInt()))
        editorHot = hot

        UI.rect(ctx, bx, by, bw, bh, 4f, if (active) UI.fade(Palette.accent, 0.14f) else UI.fade(Palette.bgDeep, 0.45f + 0.15f * hot))
        UI.outline(ctx, bx, by, bw, bh, 4f, if (active) Palette.accent else UI.mix(Palette.outlineHover, Palette.accentBorder, hot), if (active) 1.1f else UI.hair(ctx))
    }

    /**
     * The element's name tag above its frame (or below when there is no room). The HUD editor draws every tag in a
     * pass after all elements so no other element's content can cover one. [k] enlarges the tag in small windows.
     */
    fun drawEditorTag(ctx: GuiGraphicsExtractor, k: Float = 1f) {
        if (! toggle) return
        val bx = left - 2f
        val by = y - 2f
        val bh = scaledHeight + 4f
        val active = isDragging || editorSelected
        val hot = editorHot
        val size = 6.8f * k
        val label = displayName
        val tw = UI.width(label, size, UIFont.Weight.MEDIUM) + 8f * k
        val tagH = 11f * k
        val ty = if (by - tagH - 2f >= 0f) by - tagH - 2f else by + bh + 2f
        val tx = bx
        UI.rect(ctx, tx, ty, tw, tagH, 3f * k, if (active) Palette.accent else UI.fade(Palette.popover, 0.75f + 0.25f * hot))
        UI.textMid(ctx, label, tx + 4f * k, ty + tagH / 2f, if (active) java.awt.Color(250, 247, 255) else UI.mix(Palette.textSecondary, Palette.textPrimary, hot), size, UIFont.Weight.MEDIUM)
    }

    fun renderElement(ctx: GuiGraphicsExtractor, example: Boolean) {
        if (! toggle) return

        ctx.pose().pushMatrix()
        ctx.pose().translate(x, y)
        ctx.pose().scale(scale, scale)

        draw(ctx, example).run {
            width = first.toFloat()
            height = second.toFloat()
        }

        ctx.pose().popMatrix()
    }

    /**
     * Moves the element while it is dragged ([gridSize] > 0 snaps to the grid, [snapper] adjusts the new position,
     * e.g. to screen guides) and draws the editor frame plus the element.
     */
    fun drawEditor(ctx: GuiGraphicsExtractor, mx: Int, my: Int, gridSize: Int = 0, snapper: ((HudElement) -> Unit)? = null) {
        if (! toggle) return

        if (isDragging) {
            x = mx - dragX
            y = my - dragY
            if (gridSize > 0) {
                x = (x / gridSize).roundToInt() * gridSize.toFloat()
                y = (y / gridSize).roundToInt() * gridSize.toFloat()
            }
            else snapper?.invoke(this)
        }

        drawBackground(ctx, mx, my)
        renderElement(ctx, true)
    }

    open fun isHovered(mx: Int, my: Int): Boolean {
        val scaledW = width * scale
        val scaledH = height * scale
        val centeredOffset = (if (centered()) scaledW / 2 else 0).toInt()
        return mx >= x - centeredOffset && mx <= x - centeredOffset + scaledW && my >= y && my <= y + scaledH
    }

    fun startDragging(mx: Int, my: Int) {
        if (isHovered(mx, my)) {
            isDragging = true
            dragX = mx - x
            dragY = my - y
        }
    }

    companion object {
        const val defaultX = 20f
        const val defaultY = 20f
        const val defaultScale = 1f

        private val ACRONYMS = setOf("HUD", "FPS", "TPS", "CPS", "XYZ", "PB", "API")

        /** "SessionTimer" -> "Session Timer", "ArmorHud" -> "Armor HUD", "FpsDisplay" -> "FPS Display". */
        fun humanize(id: String): String {
            val spaced = if (id.contains(' ')) id else id.replace(Regex("(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])"), " ")
            return spaced.split(' ').joinToString(" ") { word -> if (word.uppercase() in ACRONYMS) word.uppercase() else word.replaceFirstChar { it.uppercase() } }
        }
    }
}
