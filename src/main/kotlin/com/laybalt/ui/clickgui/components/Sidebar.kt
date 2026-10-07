package com.laybalt.ui.clickgui.components

import com.laybalt.features.FeatureManager
import com.laybalt.ui.Palette
import com.laybalt.ui.clickgui.UI
import com.laybalt.ui.clickgui.enums.CategoryType
import com.laybalt.ui.render.CategoryIcons
import com.laybalt.ui.render.Icon
import com.laybalt.ui.render.Shapes.roundedRectGradient
import com.laybalt.ui.text.UIFont
import com.mojang.blaze3d.platform.cursor.CursorTypes
import net.minecraft.client.gui.GuiGraphicsExtractor
import java.awt.Color

/**
 * Left column of the LayBalt window: wordmark, category list with enabled/total chips, and the HUD Editor, Discord
 * and Client (settings) entries at the bottom.
 */
class Sidebar(
    private val selected: () -> CategoryType?,
    private val onCategory: (CategoryType) -> Unit,
    private val onHudEditor: () -> Unit,
    private val onDiscord: () -> Unit
) {
    companion object {
        const val WIDTH = 150f
        private const val ROW_MAX = 24f
        private const val GAP = 2f
        private const val INSET = 8f
        private val listed = CategoryType.entries.filter { it != CategoryType.DEV }
    }

    private sealed interface Entry {
        data class Category(val category: CategoryType): Entry
        data object Hud: Entry
        data object Discord: Entry
    }

    private val hover = HashMap<Entry, UI.Smooth>()
    private val selection = UI.Smooth(0f, 18f)
    private var selectionY = Float.NaN
    private val rows = ArrayList<Pair<Entry, Rect>>()

    private fun smooth(e: Entry) = hover.getOrPut(e) { UI.Smooth() }

    fun draw(ctx: GuiGraphicsExtractor, r: Rect, mx: Float, my: Float) {
        UI.rect(ctx, r.x, r.y, r.w, r.h, 10f, 0f, 0f, 10f, Palette.surfaceRaised)
        UI.rect(ctx, r.right - UI.hair(ctx), r.y, UI.hair(ctx), r.h, 0f, Palette.divider)

        drawWordmark(ctx, r.x + 14f, r.y + 14f)

        // layout
        rows.clear()
        val bottomEntries = listOf(Entry.Hud, Entry.Discord, Entry.Category(CategoryType.DEV))
        val bottomRowH = 22f
        val bottomTop = r.bottom - 10f - bottomEntries.size * (bottomRowH + GAP) + GAP
        val listTop = r.y + 56f
        val available = bottomTop - 14f - listTop
        val rowH = ((available + GAP) / listed.size - GAP).coerceIn(14f, ROW_MAX)
        listed.forEachIndexed { i, c -> rows += Entry.Category(c) to Rect(r.x + INSET, listTop + i * (rowH + GAP), r.w - INSET * 2f, rowH) }
        bottomEntries.forEachIndexed { i, e -> rows += e to Rect(r.x + INSET, bottomTop + i * (bottomRowH + GAP), r.w - INSET * 2f, bottomRowH) }

        UI.textMid(ctx, "CATEGORIES", r.x + INSET + 7f, listTop - 8f, Palette.textMuted, 5.8f, UIFont.Weight.SEMIBOLD)
        UI.hLine(ctx, r.x + 14f, bottomTop - 8f, r.w - 28f, Palette.divider)

        // animated selection pill
        val current = selected()
        val selRect = rows.firstOrNull { (e, _) -> e is Entry.Category && e.category == current }?.second
        if (selRect != null) {
            selectionY = if (selectionY.isNaN()) selRect.y else UI.approach(selectionY, selRect.y, UI.dt, 22f)
            val sy = selectionY
            val pillH = selRect.h
            selection.update(1f)
            UI.rect(ctx, selRect.x, sy, selRect.w, pillH, 7f, UI.fade(Palette.accentPill, selection.value))
            UI.rect(ctx, selRect.x, sy + pillH / 2f - 6f, 3f, 12f, 1.5f, Palette.accent)
        }
        else selection.update(0f)

        for ((entry, row) in rows) {
            val hot = smooth(entry).update(row.contains(mx, my))
            if (hot > 0.5f) ctx.requestCursor(CursorTypes.POINTING_HAND)
            val isSelected = entry is Entry.Category && entry.category == current
            if (!isSelected && hot > 0.01f) UI.rect(ctx, row.x, row.y, row.w, row.h, 7f, UI.fade(Palette.chip, hot))
            when (entry) {
                is Entry.Category -> drawCategory(ctx, entry.category, row, isSelected, hot)
                Entry.Hud -> drawAction(ctx, row, Icon.LAYOUT, "HUD Editor", hot, null)
                Entry.Discord -> drawAction(ctx, row, Icon.CHAT, "Discord", hot, Icon.LINK)
            }
        }
    }

    private fun drawWordmark(ctx: GuiGraphicsExtractor, x: Float, y: Float) {
        // Logo tile: rounded accent square with a light "L" stroke.
        val s = 18f
        UI.shadow(ctx, x, y, s, s, 5f, 6f, UI.fade(Palette.accent, 0.35f), offsetY = 1.5f)
        ctx.roundedRectGradient(x, y, s, s, 5f, UI.c(Palette.accentText), UI.c(Palette.accent))
        UI.line(ctx, x + 6.5f, y + 4.5f, x + 6.5f, y + 13f, Color(255, 255, 255), 2f)
        UI.line(ctx, x + 5.5f, y + 13f, x + 12.5f, y + 13f, Color(255, 255, 255), 2f)
        val tx = x + s + 7f
        UI.textMid(ctx, "LayBalt", tx, y + s / 2f, Palette.accentText, 11f, UIFont.Weight.BOLD)
        UI.textMid(ctx, "QOL", tx + UI.width("LayBalt", 11f, UIFont.Weight.BOLD) + 4f, y + s / 2f, Palette.textMuted, 11f, UIFont.Weight.MEDIUM)
    }

    private fun drawCategory(ctx: GuiGraphicsExtractor, category: CategoryType, row: Rect, selected: Boolean, hot: Float) {
        val features = FeatureManager.getFeaturesByCategory(category)
        val enabled = features.count { it.enabled && !it.wip }
        val iconSize = (row.h - 6f).coerceAtMost(16f)
        if (UI.alpha > 0.55f) CategoryIcons.draw(ctx, category, row.x + 7f, row.y + (row.h - iconSize) / 2f, iconSize)
        val label = if (category == CategoryType.DEV) "Client" else category.displayName
        val color = if (selected) Palette.textPrimary else UI.mix(Palette.textSecondary, Palette.textPrimary, hot)
        UI.textMid(ctx, label, row.x + 13f + iconSize, row.y + row.h / 2f, color, 8f, if (selected) UIFont.Weight.SEMIBOLD else UIFont.Weight.MEDIUM)
        val chip = "$enabled/${features.size}"
        val cw = UI.chipWidth(chip, 6.2f, padX = 4.5f)
        UI.chip(
            ctx, chip, row.right - 6f - cw, row.y + (row.h - 12f) / 2f, 12f,
            if (selected) UI.fade(Palette.accent, 0.3f) else Palette.chip,
            if (selected) Palette.accentText else if (enabled > 0) Palette.chipText else Palette.textMuted,
            6.2f, padX = 4.5f
        )
    }

    private fun drawAction(ctx: GuiGraphicsExtractor, row: Rect, icon: Icon, label: String, hot: Float, trailing: Icon?) {
        val color = UI.mix(Palette.textSecondary, Palette.textPrimary, hot)
        UI.icon(ctx, icon, row.x + 8f, row.y + (row.h - 13f) / 2f, 13f, UI.mix(Palette.textSecondary, Palette.accentText, hot))
        UI.textMid(ctx, label, row.x + 29f, row.y + row.h / 2f, color, 8f, UIFont.Weight.MEDIUM)
        if (trailing != null) UI.icon(ctx, trailing, row.right - 17f, row.y + (row.h - 9f) / 2f, 9f, UI.mix(Palette.textMuted, Palette.textSecondary, hot))
    }

    fun mouseClicked(mx: Float, my: Float): Boolean {
        val entry = rows.firstOrNull { it.second.contains(mx, my) }?.first ?: return false
        when (entry) {
            is Entry.Category -> onCategory(entry.category)
            Entry.Hud -> onHudEditor()
            Entry.Discord -> onDiscord()
        }
        return true
    }
}
