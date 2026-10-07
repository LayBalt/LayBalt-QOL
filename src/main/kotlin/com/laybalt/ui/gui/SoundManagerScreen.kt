package com.laybalt.ui.gui

import com.laybalt.features.impl.misc.sound.SoundManager
import com.laybalt.ui.Palette
import com.laybalt.ui.clickgui.LayBaltScreen
import com.laybalt.ui.clickgui.TooltipManager
import com.laybalt.ui.clickgui.UI
import com.laybalt.ui.clickgui.components.Rect
import com.laybalt.ui.clickgui.components.ScrollArea
import com.laybalt.ui.clickgui.components.SearchField
import com.laybalt.ui.clickgui.components.settings.Style
import com.laybalt.ui.render.Icon
import com.laybalt.ui.text.UIFont
import com.laybalt.utils.spaceCaps
import com.mojang.blaze3d.platform.InputConstants
import com.mojang.blaze3d.platform.cursor.CursorTypes
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.sounds.SoundEvent
import java.awt.Color

/**
 * Per-sound volume editor of the Sound Manager feature: categories on the left, a searchable list of every game sound
 * with a 0-200% slider, a play button and a reset button on the right.
 */
class SoundManagerScreen(private val parent: Screen? = null): LayBaltSubScreen("Sound Manager") {
    private companion object {
        const val SIDE_W = 136f
        const val ROW_H = 30f
        const val ROW_GAP = 4f
        const val HEADER_ROW_H = 22f
        const val FOOTER_H = 34f
        const val SLIDER_W = 118f
        const val CHIP_W = 34f
        const val NAME_SIZE = 7.6f
        const val ID_SIZE = 6f
    }

    private enum class SoundCategory {
        All, Recent, Modified, Blocks, HostileMobs, NeutralMobs, Music, Ambient, Items, UI, Misc;

        val renderName = if (name == "UI") "Interface" else name.spaceCaps()
    }

    private sealed interface Item {
        val height: Float
    }

    private class Header(val name: String, val count: Int): Item {
        override val height get() = HEADER_ROW_H
    }

    private class Sound(val id: String, val cleanName: String, val category: SoundCategory, val sound: SoundEvent): Item {
        val searchText = "$id $cleanName".lowercase()
        val shortId = id.removePrefix("minecraft:")
        override val height get() = ROW_H + ROW_GAP
    }

    private val search = SearchField("Search sounds…", showHint = false) { updateFilter() }
    private val scroll = ScrollArea(ROW_H * 2.5f)

    private var selectedCategory = SoundCategory.All
    private var recentSoundsVersion = -1L

    private val allSounds = BuiltInRegistries.SOUND_EVENT.entrySet().map { entry ->
        val id = entry.key.identifier().toString()
        Sound(id, cleanName(id), category(id), entry.value)
    }.sortedBy(Sound::id)
    private val soundsById = allSounds.associateBy(Sound::id)

    private val items = ArrayList<Item>()
    private val offsets = ArrayList<Float>()
    private var contentH = 0f

    private val categoryHover = HashMap<SoundCategory, UI.Smooth>()
    private val rowHover = HashMap<String, UI.Smooth>()
    private val fills = HashMap<String, UI.Smooth>()
    private val selection = UI.Smooth(0f, 18f)
    private var selectionY = Float.NaN
    private val resetAllHover = UI.Smooth()
    private var resetConfirmUntil = 0L

    // slider drag state (the list can scroll while dragging, so the track position is captured on press)
    private var draggingId: String? = null
    private var dragTrackX = 0f

    private var listRect = Rect(0f, 0f, 0f, 0f)
    private var sideRect = Rect(0f, 0f, 0f, 0f)
    private var footerRect = Rect(0f, 0f, 0f, 0f)
    private var resetAllRect = Rect(0f, 0f, 0f, 0f)
    private val categoryRects = LinkedHashMap<SoundCategory, Rect>()
    private var modifiedCount = 0
    private var modifiedCheckedAt = 0L

    override val heading get() = "Sound Manager"
    override val subheading get() = "Change the volume of any game sound · ${allSounds.size} sounds"
    override val headingIcon get() = Icon.VOLUME

    override fun windowSize(canvasW: Float, canvasH: Float) =
        (canvasW * 0.64f).coerceIn(580f, 780f) to (canvasH * 0.76f).coerceIn(340f, 480f)

    override fun returnTo() = parent ?: LayBaltScreen()

    override fun init() {
        super.init()
        updateFilter()
    }

    // ------------------------------------------------------------------ data

    private fun updateFilter(resetScroll: Boolean = true) {
        val query = search.query.trim().lowercase()
        val sounds = when (selectedCategory) {
            SoundCategory.Recent -> SoundManager.getRecentSoundIds().mapNotNull(soundsById::get)
            SoundCategory.Modified -> allSounds.filter { SoundManager.getVolumePercent(it.id) != 100 }
            SoundCategory.All -> allSounds
            else -> allSounds.filter { it.category == selectedCategory }
        }.filter { query.isEmpty() || it.searchText.contains(query) }

        items.clear()
        if (selectedCategory == SoundCategory.Recent || selectedCategory == SoundCategory.Modified) {
            if (sounds.isNotEmpty()) items.add(Header(selectedCategory.renderName, sounds.size))
            items.addAll(sounds)
            recentSoundsVersion = SoundManager.recentSoundsVersion
        }
        else sounds.groupBy(Sound::category).toSortedMap().forEach { (category, list) ->
            items.add(Header(category.renderName, list.size))
            items.addAll(list)
        }

        offsets.clear()
        var y = 0f
        for (item in items) {
            offsets.add(y)
            y += item.height
        }
        contentH = y
        if (resetScroll) scroll.set(0f, instant = true)
    }

    private fun countModified(): Int {
        val now = System.currentTimeMillis()
        if (now - modifiedCheckedAt > 400) {
            modifiedCheckedAt = now
            modifiedCount = allSounds.count { SoundManager.getVolumePercent(it.id) != 100 }
        }
        return modifiedCount
    }

    // ------------------------------------------------------------------ layout & drawing

    private fun layout() {
        sideRect = Rect(body.x, body.y, SIDE_W, body.h)
        footerRect = Rect(body.x + SIDE_W, body.bottom - FOOTER_H, body.w - SIDE_W, FOOTER_H)
        listRect = Rect(body.x + SIDE_W + 12f, body.y + 8f, body.w - SIDE_W - 26f, body.h - 8f - FOOTER_H)
    }

    override fun drawHeaderExtras(ctx: GuiGraphicsExtractor, right: Float, mx: Float, my: Float) {
        val w = 190f.coerceAtMost(window.w * 0.32f)
        search.draw(ctx, Rect(right - w, window.y + (HEADER_H - 22f) / 2f, w, 22f), mx, my)
    }

    override fun subtitleRight(): Float {
        val w = 190f.coerceAtMost(window.w * 0.32f)
        return closeRect.x - 8f - w - 12f
    }

    override fun drawBody(ctx: GuiGraphicsExtractor, mx: Float, my: Float) {
        layout()
        if (selectedCategory == SoundCategory.Recent && recentSoundsVersion != SoundManager.recentSoundsVersion) updateFilter(resetScroll = false)

        drawCategories(ctx, mx, my)
        drawList(ctx, mx, my)
        drawFooter(ctx, mx, my)
    }

    private fun drawCategories(ctx: GuiGraphicsExtractor, mx: Float, my: Float) {
        val r = sideRect
        UI.rect(ctx, r.x, r.y, r.w, r.h, 0f, 0f, 0f, RADIUS, Palette.surfaceRaised)
        UI.rect(ctx, r.right - UI.hair(ctx), r.y, UI.hair(ctx), r.h, 0f, Palette.divider)
        UI.textMid(ctx, "CATEGORIES", r.x + 15f, r.y + 15f, Palette.textMuted, 5.8f, UIFont.Weight.SEMIBOLD)

        categoryRects.clear()
        val rowH = ((r.h - 36f) / SoundCategory.entries.size - 2f).coerceIn(14f, 21f)
        SoundCategory.entries.forEachIndexed { i, c -> categoryRects[c] = Rect(r.x + 8f, r.y + 26f + i * (rowH + 2f), r.w - 16f, rowH) }

        val sel = categoryRects.getValue(selectedCategory)
        selectionY = if (selectionY.isNaN()) sel.y else UI.approach(selectionY, sel.y, UI.dt, 22f)
        UI.rect(ctx, sel.x, selectionY, sel.w, sel.h, 6f, UI.fade(Palette.accentPill, selection.update(1f)))
        UI.rect(ctx, sel.x, selectionY + sel.h / 2f - 5f, 3f, 10f, 1.5f, Palette.accent)

        for ((c, row) in categoryRects) {
            val hot = categoryHover.getOrPut(c) { UI.Smooth() }.update(row.contains(mx, my))
            if (hot > 0.5f) ctx.requestCursor(CursorTypes.POINTING_HAND)
            val selected = c == selectedCategory
            if (!selected && hot > 0.01f) UI.rect(ctx, row.x, row.y, row.w, row.h, 6f, UI.fade(Palette.chip, hot))
            val color = if (selected) Palette.textPrimary else UI.mix(Palette.textSecondary, Palette.textPrimary, hot)
            UI.textMid(ctx, c.renderName, row.x + 10f, row.y + row.h / 2f, color, 7.6f, if (selected) UIFont.Weight.SEMIBOLD else UIFont.Weight.MEDIUM)
            if (c == SoundCategory.Modified) {
                val n = countModified()
                if (n > 0) {
                    val text = n.toString()
                    val cw = UI.chipWidth(text, 6f, padX = 4f)
                    UI.chip(ctx, text, row.right - 6f - cw, row.y + (row.h - 11f) / 2f, 11f, if (selected) UI.fade(Palette.accent, 0.3f) else Palette.chip, if (selected) Palette.accentText else Palette.chipText, 6f, padX = 4f)
                }
            }
        }
    }

    private fun drawList(ctx: GuiGraphicsExtractor, mx: Float, my: Float) {
        val lr = listRect
        scroll.update(lr.right + 10f, lr.y, lr.h, contentH, my)
        val inList = lr.contains(mx, my)
        val rmx = if (inList || draggingId != null) mx else -10000f
        val rmy = if (inList || draggingId != null) my else -10000f

        if (items.isEmpty()) {
            val (title, hint) = when {
                search.query.isNotBlank() -> "No sounds match \"${search.query.trim()}\"" to "Try another name or pick a different category."
                selectedCategory == SoundCategory.Recent -> "No sounds played yet" to "Sounds you hear in game show up here."
                selectedCategory == SoundCategory.Modified -> "Every sound is at 100%" to "Sounds you change show up here."
                else -> "Nothing here" to null
            }
            drawEmptyState(ctx, lr, Icon.VOLUME, title, hint)
            return
        }

        ctx.enableScissor(lr.x.toInt() - 2, lr.y.toInt(), lr.right.toInt() + 2, lr.bottom.toInt())
        val top = lr.y - scroll.offset
        // first visible item: binary search over the offsets
        var lo = 0
        var hi = items.size - 1
        val start = scroll.offset - 40f
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (offsets[mid] <= start) lo = mid else hi = mid - 1
        }
        var i = lo
        while (i < items.size) {
            val y = top + offsets[i]
            if (y > lr.bottom + 10f) break
            when (val item = items[i]) {
                is Header -> {
                    val label = item.name.uppercase()
                    UI.textMid(ctx, label, lr.x + 2f, y + HEADER_ROW_H / 2f + 1f, Palette.textMuted, 6f, UIFont.Weight.SEMIBOLD)
                    val lw = UI.width(label, 6f, UIFont.Weight.SEMIBOLD)
                    UI.textMid(ctx, item.count.toString(), lr.x + lw + 8f, y + HEADER_ROW_H / 2f + 1f, UI.fade(Palette.textMuted, 0.7f), 6f, UIFont.Weight.MEDIUM)
                }
                is Sound -> drawSound(ctx, item, Rect(lr.x, y, lr.w, ROW_H), rmx, rmy)
            }
            i++
        }
        ctx.disableScissor()
        if (scroll.offset > 1f) UI.vGradient(ctx, lr.x - 2f, lr.y, lr.w + 4f, 8f, Palette.surface, UI.fade(Palette.surface, 0f))
        if (scroll.offset < scroll.max - 1f) UI.vGradient(ctx, lr.x - 2f, lr.bottom - 8f, lr.w + 4f, 8f, UI.fade(Palette.surface, 0f), Palette.surface)
        scroll.draw(ctx, mx, my)
    }

    private fun playRect(r: Rect) = Rect(r.right - 26f, r.y + (r.h - 20f) / 2f, 20f, 20f)
    private fun chipRect(r: Rect) = Rect(playRect(r).x - 8f - CHIP_W, r.y + (r.h - 15f) / 2f, CHIP_W, 15f)
    private fun trackX(r: Rect) = chipRect(r).x - 14f - SLIDER_W
    private fun resetRect(r: Rect) = Rect(trackX(r) - 30f, r.y + (r.h - 18f) / 2f, 18f, 18f)

    private fun drawSound(ctx: GuiGraphicsExtractor, sound: Sound, r: Rect, mx: Float, my: Float) {
        if (draggingId == sound.id) setVolume(sound.id, mx, dragTrackX)
        val volume = SoundManager.getVolumePercent(sound.id)
        val modified = volume != 100
        val hot = rowHover.getOrPut(sound.id) { UI.Smooth() }.update(r.contains(mx, my) || draggingId == sound.id)

        UI.rect(ctx, r.x, r.y, r.w, r.h, 7f, UI.mix(Palette.card, Palette.cardHover, hot))
        UI.outline(ctx, r.x, r.y, r.w, r.h, 7f, if (modified) UI.mix(UI.fade(Palette.accentBorder, 0.55f), Palette.accentBorder, hot) else UI.mix(Palette.divider, Palette.outline, hot))

        // name + id
        val textX = r.x + 10f
        val maxText = resetRect(r).x - 8f - textX
        UI.text(ctx, UIFont.trim(sound.cleanName, maxText, NAME_SIZE, UIFont.Weight.MEDIUM), textX, r.y + 4.5f, Palette.textPrimary, NAME_SIZE, UIFont.Weight.MEDIUM)
        UI.text(ctx, UIFont.trim(sound.shortId, maxText, ID_SIZE), textX, r.y + 16.5f, Palette.textMuted, ID_SIZE)

        // reset (only when changed)
        if (modified) {
            val rr = resetRect(r)
            val rh = rr.contains(mx, my)
            if (rh) {
                ctx.requestCursor(CursorTypes.POINTING_HAND)
                TooltipManager.hover("Reset to 100%", mx, my)
            }
            UI.iconButton(ctx, Icon.RESET, rr.x, rr.y, rr.w, if (rh) 1f else 0f, Palette.textMuted, Palette.accentText, 0.62f)
        }

        // slider: 0..200 %, with a tick at the default 100 %
        val tX = trackX(r)
        val tY = r.y + r.h / 2f
        val fill = fills.getOrPut(sound.id) { UI.Smooth(volume / 200f, 20f) }
        if (draggingId == sound.id) fill.snap(volume / 200f) else fill.update(volume / 200f)
        UI.rect(ctx, tX, tY - 2f, SLIDER_W, 4f, 2f, Palette.input)
        UI.rect(ctx, tX + SLIDER_W / 2f - 0.5f, tY - 4f, 1f, 8f, 0.5f, Palette.outlineHover)
        val fx = SLIDER_W * fill.value
        if (fx > 0.5f) UI.rect(ctx, tX, tY - 2f, fx.coerceAtLeast(4f), 4f, 2f, Palette.accent)
        val knobHot = draggingId == sound.id || UI.inside(mx, my, tX - 6f, r.y, SLIDER_W + 12f, r.h)
        if (knobHot) ctx.requestCursor(CursorTypes.POINTING_HAND)
        val kr = if (knobHot) 5f else 4.5f
        if (knobHot) UI.circle(ctx, tX + fx, tY, kr + 3f, UI.fade(Palette.accent, 0.2f))
        UI.shadow(ctx, tX + fx - kr, tY - kr, kr * 2f, kr * 2f, kr, 2f, Palette.shadowSoft, offsetY = 0.6f)
        UI.circle(ctx, tX + fx, tY, kr, Palette.switchKnob)
        UI.circle(ctx, tX + fx, tY, kr * 0.42f, Palette.accent)

        // value chip
        val c = chipRect(r)
        UI.rect(ctx, c.x, c.y, c.w, c.h, 4.5f, if (modified) UI.fade(Palette.accent, 0.18f) else UI.fade(Palette.chip, 0.7f))
        UI.textMidCentered(ctx, "$volume%", c.x + c.w / 2f, c.y + c.h / 2f, if (modified) Palette.accentText else Palette.textSecondary, 6.8f, UIFont.Weight.MEDIUM)

        // play
        val p = playRect(r)
        val ph = p.contains(mx, my)
        if (ph) ctx.requestCursor(CursorTypes.POINTING_HAND)
        UI.rect(ctx, p.x, p.y, p.w, p.h, p.h / 2f, if (ph) Palette.accent else Palette.chip)
        UI.icon(ctx, Icon.PLAY, p.x + 5.5f, p.y + 5f, 10f, if (ph) WHITE else Palette.accentText)
    }

    private val WHITE = Color(250, 247, 255)

    private fun drawFooter(ctx: GuiGraphicsExtractor, mx: Float, my: Float) {
        val f = footerRect
        UI.hLine(ctx, f.x, f.y, f.w, Palette.divider)
        val n = countModified()
        val text = when (n) {
            0 -> "All sounds at 100%  ·  drag a slider to change one (0–200%)"
            1 -> "1 sound changed"
            else -> "$n sounds changed"
        }
        UI.textMid(ctx, text, f.x + 14f, f.y + f.h / 2f, Palette.textMuted, 7f)
        if (n == 0) {
            resetAllRect = Rect(0f, 0f, 0f, 0f)
            return
        }
        val confirming = System.currentTimeMillis() < resetConfirmUntil
        val label = if (confirming) "Click again to reset" else "Reset all"
        val bw = UI.width(label, 7.2f, UIFont.Weight.MEDIUM) + 32f
        resetAllRect = Rect(f.right - 14f - bw, f.y + (f.h - 20f) / 2f, bw, 20f)
        val hot = resetAllHover.update(resetAllRect.contains(mx, my))
        if (hot > 0.5f) ctx.requestCursor(CursorTypes.POINTING_HAND)
        UI.button(ctx, label, resetAllRect.x, resetAllRect.y, resetAllRect.w, resetAllRect.h, if (confirming) 1f else hot, if (confirming) UI.ButtonStyle.DANGER else UI.ButtonStyle.SECONDARY, Icon.RESET, 7.2f)
    }

    // ------------------------------------------------------------------ input

    override fun onMouseClicked(mx: Float, my: Float, button: Int): Boolean {
        if (search.mouseClicked(mx, my, button)) return true
        search.unfocus()
        if (button != InputConstants.MOUSE_BUTTON_LEFT) return false

        categoryRects.entries.firstOrNull { it.value.contains(mx, my) }?.let { (c, _) ->
            if (selectedCategory != c) {
                selectedCategory = c
                updateFilter()
                Style.playClickSound(1.15f)
            }
            return true
        }

        if (resetAllRect.contains(mx, my)) {
            if (System.currentTimeMillis() < resetConfirmUntil) {
                resetConfirmUntil = 0L
                allSounds.forEach { if (SoundManager.getVolumePercent(it.id) != 100) SoundManager.setVolumePercent(it.id, 100) }
                modifiedCheckedAt = 0L
                if (selectedCategory == SoundCategory.Modified) updateFilter()
                Style.playClickSound(0.8f)
            }
            else {
                resetConfirmUntil = System.currentTimeMillis() + 3000L
                Style.playClickSound(1f)
            }
            return true
        }

        if (scroll.mouseClicked(mx, my)) return true
        if (!listRect.contains(mx, my)) return false

        val top = listRect.y - scroll.offset
        for (i in items.indices) {
            val item = items[i] as? Sound ?: continue
            val r = Rect(listRect.x, top + offsets[i], listRect.w, ROW_H)
            if (!r.contains(mx, my)) continue
            if (playRect(r).contains(mx, my)) {
                SoundManager.playPreview(item.sound)
                return true
            }
            if (SoundManager.getVolumePercent(item.id) != 100 && resetRect(r).contains(mx, my)) {
                SoundManager.setVolumePercent(item.id, 100)
                modifiedCheckedAt = 0L
                Style.playClickSound(0.9f)
                return true
            }
            val tX = trackX(r)
            if (mx >= tX - 6f && mx <= tX + SLIDER_W + 6f) {
                draggingId = item.id
                dragTrackX = tX
                setVolume(item.id, mx, tX)
            }
            return true
        }
        return false
    }

    private fun setVolume(id: String, mouseX: Float, trackX: Float) {
        val progress = ((mouseX - trackX) / SLIDER_W).coerceIn(0f, 1f)
        val before = SoundManager.getVolumePercent(id)
        SoundManager.setVolumePercent(id, (progress * 200).toInt())
        if (before != SoundManager.getVolumePercent(id)) modifiedCheckedAt = 0L
    }

    override fun onMouseReleased(button: Int) {
        draggingId = null
        scroll.mouseReleased()
        search.mouseReleased()
    }

    override fun onMouseScrolled(mx: Float, my: Float, delta: Double): Boolean {
        if (sideRect.contains(mx, my)) return true
        scroll.scroll(delta)
        return true
    }

    override fun onKeyPressed(event: KeyEvent): Boolean {
        if (search.keyPressed(event)) return true
        if (event.hasControlDown() && event.key == InputConstants.KEY_F) {
            search.focus()
            return true
        }
        return false
    }

    override fun onCharTyped(event: CharacterEvent): Boolean {
        if (search.charTyped(event)) return true
        // Typing anywhere starts a search.
        val c = event.codepoint
        if (c > 32 && !Character.isISOControl(c)) {
            search.focus()
            return search.charTyped(event)
        }
        return false
    }

    // ------------------------------------------------------------------ names

    private fun cleanName(id: String): String {
        val path = id.removePrefix("minecraft:")
        val name = when {
            path.startsWith("entity.hostile.") -> path.removePrefix("entity.hostile.")
            path.startsWith("entity.") -> path.removePrefix("entity.")
            path.contains(".") -> path.substringAfter(".")
            else -> path
        }.replace(".", " ").replace("_", " ")
        return name.replaceFirstChar { it.uppercase() }
    }

    private fun category(id: String): SoundCategory {
        val path = id.removePrefix("minecraft:")
        return when {
            path.startsWith("block") -> SoundCategory.Blocks
            path.startsWith("entity.hostile") -> SoundCategory.HostileMobs
            path.startsWith("entity") -> SoundCategory.NeutralMobs
            path.startsWith("music") -> SoundCategory.Music
            path.startsWith("ambient") -> SoundCategory.Ambient
            path.startsWith("item") -> SoundCategory.Items
            path.startsWith("ui") -> SoundCategory.UI
            else -> SoundCategory.Misc
        }
    }
}
