package com.laybalt.ui.clickgui.components

import com.laybalt.features.Feature
import com.laybalt.ui.Palette
import com.laybalt.ui.clickgui.TooltipManager
import com.laybalt.ui.clickgui.UI
import com.laybalt.ui.clickgui.components.settings.Style
import com.laybalt.ui.render.Icon
import com.laybalt.ui.render.Shapes.roundedRectHorizontalGradient
import com.laybalt.ui.text.UIFont
import com.mojang.blaze3d.platform.InputConstants
import com.mojang.blaze3d.platform.cursor.CursorTypes
import net.minecraft.client.gui.GuiGraphicsExtractor
import kotlin.math.ceil

/**
 * Responsive grid of feature cards (2-4 columns): name, two lines of description, an iOS-style switch and a gear when
 * the feature has settings. Enabled cards get an accent border and dot, hovered cards lift, WIP cards are greyed out.
 */
class FeatureGrid(
    private val hasSettings: (Feature) -> Boolean,
    private val onOpen: (Feature) -> Unit,
    private val isOpen: (Feature) -> Boolean
) {
    companion object {
        const val MIN_CARD_W = 168f
        const val CARD_H = 82f
        const val GAP = 10f
        const val PAD = 16f
        private const val NAME_SIZE = 8.6f
        private const val DESC_SIZE = 7.1f
    }

    val scroll = ScrollArea(52f)

    private class CardState {
        val hover = UI.Smooth(0f, 18f)
        val knob = UI.Smooth(-1f, 18f)
        val gear = UI.Smooth()
        var descWidth = -1f
        var desc = emptyList<String>()
        /** Search-result entrance stagger. */
        var appear = UI.Smooth(0f, 14f)
    }

    private val states = HashMap<Feature, CardState>()
    private var cards = emptyList<Pair<Feature, Rect>>()
    private var area = Rect(0f, 0f, 0f, 0f)
    private var showCategory = false

    private fun state(f: Feature) = states.getOrPut(f) { CardState() }

    /** Restarts the card entrance animation (category or search change). */
    fun resetAppear() = states.values.forEach { it.appear.snap(0f) }

    fun draw(ctx: GuiGraphicsExtractor, r: Rect, features: List<Feature>, searching: Boolean, query: String, mx: Float, my: Float, interactive: Boolean) {
        area = r
        showCategory = searching
        val innerW = r.w - PAD * 2f
        val cols = ((innerW + GAP) / (MIN_CARD_W + GAP)).toInt().coerceIn(2, 4)
        val cardW = (innerW - GAP * (cols - 1)) / cols
        val rowsCount = ceil(features.size / cols.toFloat()).toInt()
        val contentH = PAD + rowsCount * (CARD_H + GAP) - (if (rowsCount > 0) GAP else 0f) + PAD

        scroll.update(r.right - 2f, r.y, r.h, contentH, my)
        val mouseIn = interactive && r.contains(mx, my)

        ctx.enableScissor(r.x.toInt(), r.y.toInt(), r.right.toInt(), r.bottom.toInt())
        val list = ArrayList<Pair<Feature, Rect>>(features.size)
        features.forEachIndexed { i, feature ->
            val col = i % cols
            val row = i / cols
            val cx = r.x + PAD + col * (cardW + GAP)
            val cy = r.y + PAD + row * (CARD_H + GAP) - scroll.offset
            val rect = Rect(cx, cy, cardW, CARD_H)
            list += feature to rect
            if (cy + CARD_H < r.y - 8f || cy > r.bottom + 8f) return@forEachIndexed
            val st = state(feature)
            st.appear.update(1f)
            drawCard(ctx, feature, rect, st, mouseIn, mx, my, i)
        }
        cards = list
        ctx.disableScissor()

        if (features.isEmpty()) drawEmpty(ctx, r, searching, query)

        // Fade cards out under the top bar edge.
        UI.vGradient(ctx, r.x, r.y, r.w, 8f, Palette.surface, UI.fade(Palette.surface, 0f))
        scroll.draw(ctx, mx, my)
    }

    private fun drawCard(ctx: GuiGraphicsExtractor, feature: Feature, base: Rect, st: CardState, mouseIn: Boolean, mx: Float, my: Float, index: Int) {
        val wip = feature.wip
        val enabled = feature.enabled && !wip
        val hotRaw = mouseIn && base.contains(mx, my)
        val selected = isOpen(feature)
        val t = st.hover.update(hotRaw || selected)
        if (st.knob.value < 0f) st.knob.snap(if (enabled) 1f else 0f)
        val knob = st.knob.update(enabled)
        val appear = st.appear.value

        val lift = t * 1.5f + (1f - appear) * 6f
        val x = base.x
        val y = base.y - lift
        val w = base.w
        val h = base.h
        val saved = UI.alpha
        UI.alpha = saved * appear

        UI.shadow(ctx, x, y, w, h, 9f, 6f + 6f * t, if (t > 0.01f) UI.mix(Palette.shadowSoft, Palette.shadowStrong, t) else Palette.shadowSoft, offsetY = 2f + 2.5f * t)
        val bg = if (wip) Palette.card.darkerBy(0.82f) else UI.mix(Palette.card, Palette.cardHover, t)
        UI.rect(ctx, x, y, w, h, 9f, bg)
        if (enabled) {
            // soft accent wash in the top-left corner of enabled cards
            ctx.roundedRectHorizontalGradient(x, y, w, h, 9f, UI.c(UI.fade(Palette.accent, 0.10f + 0.05f * t)), UI.c(UI.fade(Palette.accent, 0f)))
        }
        val border = when {
            selected -> Palette.accent
            enabled -> UI.mix(Palette.accentBorder, Palette.accentText, t * 0.5f)
            else -> UI.mix(Palette.outline, Palette.outlineHover, t)
        }
        UI.outline(ctx, x, y, w, h, 9f, border, if (selected) 1.1f else UI.hair(ctx))

        val pad = 11f
        // top-right marker: WIP chip, or accent dot for enabled features
        var nameRight = x + w - pad
        if (wip) {
            val cw = UI.chipWidth("WIP", 6f)
            UI.chip(ctx, "WIP", x + w - pad - cw, y + 9f, 12f, UI.fade(Palette.warning, 0.16f), Palette.warning, 6f)
            nameRight -= cw + 5f
        }
        else if (knob > 0.01f) {
            val dx = x + w - pad - 3f
            val dy = y + 15f
            UI.circle(ctx, dx, dy, 5.5f * knob, UI.fade(Palette.accent, 0.22f * knob))
            UI.circle(ctx, dx, dy, 2.6f * knob, Palette.accent)
            nameRight -= 12f
        }
        val nameColor = if (wip) Palette.textMuted else Palette.textPrimary
        UI.text(ctx, UIFont.trim(feature.name, nameRight - (x + pad), NAME_SIZE, UIFont.Weight.SEMIBOLD), x + pad, y + 9f, nameColor, NAME_SIZE, UIFont.Weight.SEMIBOLD)

        // description (cached wrap)
        val descW = w - pad * 2f
        if (st.descWidth != descW) {
            st.descWidth = descW
            st.desc = feature.description?.takeIf { it.isNotBlank() }?.let { UIFont.wrap(it.replace('\n', ' '), descW, DESC_SIZE, maxLines = 2) } ?: listOf("No description.")
        }
        val descColor = if (wip) Palette.textMuted.darkerBy(0.85f) else if (feature.description.isNullOrBlank()) Palette.textMuted else Palette.textSecondary
        st.desc.forEachIndexed { i, line ->
            UI.text(ctx, line, x + pad, y + 25f + i * (UIFont.lineHeight(DESC_SIZE) + 1f), descColor, DESC_SIZE)
        }

        // bottom row: switch, category chip (search), gear
        val rowY = y + h - 21f
        val sw = switchRect(base)
        val switchHot = mouseIn && sw.contains(mx, my)
        UI.switch(ctx, sw.x, rowY + (13f - UI.SWITCH_H) / 2f, knob, if (switchHot) 1f else t * 0.4f, enabled = !wip)
        var chipX = sw.right + 8f
        if (showCategory) {
            val label = if (feature.category.name == "DEV") "Client" else feature.category.displayName
            chipX += UI.chip(ctx, label, chipX, rowY, 13f, Palette.chip, Palette.chipText, 6.2f) + 5f
        }
        if (wip) UI.textMid(ctx, "Coming soon", chipX, rowY + 6.5f, Palette.textMuted, 6.4f, UIFont.Weight.MEDIUM)
        if (hasSettings(feature)) {
            val g = gearRect(base)
            val gh = st.gear.update(mouseIn && g.contains(mx, my))
            UI.iconButton(ctx, Icon.GEAR, g.x, g.y - lift, g.w, gh, if (t > 0.3f || selected) Palette.textSecondary else Palette.textMuted, Palette.accentText, 0.68f)
        }

        if (hotRaw) {
            ctx.requestCursor(if (switchHot && wip) CursorTypes.NOT_ALLOWED else CursorTypes.POINTING_HAND)
            if (wip) TooltipManager.hover("§7(Work in progress) §r${feature.description.orEmpty()}", mx, my)
            else if (st.desc.lastOrNull()?.endsWith("…") == true && my < y + h - 24f) TooltipManager.hover(feature.description, mx, my)
        }
        UI.alpha = saved
    }

    private fun switchRect(card: Rect) = Rect(card.x + 11f, card.bottom - 21f, UI.SWITCH_W, 13f)
    private fun gearRect(card: Rect) = Rect(card.right - 11f - 17f, card.bottom - 23.5f, 17f, 17f)

    private fun drawEmpty(ctx: GuiGraphicsExtractor, r: Rect, searching: Boolean, query: String) {
        val cx = r.x + r.w / 2f
        val cy = r.y + r.h / 2f - 16f
        UI.circle(ctx, cx, cy, 20f, Palette.chip)
        UI.icon(ctx, if (searching) Icon.SEARCH else Icon.GRID, cx - 10f, cy - 10f, 20f, Palette.textMuted)
        val title = if (searching) "Nothing found" else "No features here yet"
        UI.textMidCentered(ctx, title, cx, cy + 34f, Palette.textPrimary, 9f, UIFont.Weight.SEMIBOLD)
        val sub = if (searching) UIFont.trim("No feature or setting matches “$query”.", r.w - 40f, 7.4f) else "Features of this category will show up here."
        UI.textMidCentered(ctx, sub, cx, cy + 48f, Palette.textMuted, 7.4f)
    }

    private fun java.awt.Color.darkerBy(f: Float) = java.awt.Color((red * f).toInt(), (green * f).toInt(), (blue * f).toInt(), alpha)

    enum class Hit { OUTSIDE, EMPTY, CARD }

    /** Handles a click: [Hit.CARD] when a card (or the scrollbar) used it, [Hit.EMPTY] for empty grid space. */
    fun mouseClicked(mx: Float, my: Float, button: Int): Hit {
        if (!area.contains(mx, my)) return Hit.OUTSIDE
        if (button == InputConstants.MOUSE_BUTTON_LEFT && scroll.mouseClicked(mx, my)) return Hit.CARD
        val (feature, rect) = cards.firstOrNull { it.second.contains(mx, my) } ?: return Hit.EMPTY
        when {
            button == InputConstants.MOUSE_BUTTON_LEFT && switchRect(rect).let { Rect(it.x - 3f, it.y - 3f, it.w + 6f, it.h + 6f) }.contains(mx, my) -> toggle(feature)
            button == InputConstants.MOUSE_BUTTON_LEFT || button == InputConstants.MOUSE_BUTTON_RIGHT -> {
                Style.playClickSound(1.2f)
                onOpen(feature)
            }
            button == InputConstants.MOUSE_BUTTON_MIDDLE -> toggle(feature)
        }
        return Hit.CARD
    }

    private fun toggle(feature: Feature) {
        // Feature.toggle() refuses WIP features and shows the "work in progress" notification.
        feature.toggle()
        Style.playClickSound(if (feature.wip) 0.6f else if (feature.enabled) 1.1f else 0.9f)
    }

    fun mouseReleased() = scroll.mouseReleased()

    fun mouseScrolled(mx: Float, my: Float, delta: Double): Boolean {
        if (!area.contains(mx, my)) return false
        scroll.scroll(delta)
        return true
    }

    /** Index-independent lookup used to keep the open feature's card in view. */
    fun reveal(feature: Feature) {
        val rect = cards.firstOrNull { it.first == feature }?.second ?: return
        scroll.reveal(rect.y - area.y + scroll.offset, rect.h)
    }
}
