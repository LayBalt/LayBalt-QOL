package com.laybalt.ui.clickgui.components

import com.laybalt.LayBaltQOL.MOD_NAME
import com.laybalt.LayBaltQOL.mc
import com.laybalt.config.types.ButtonSetting
import com.laybalt.features.Feature
import com.laybalt.features.impl.general.CommandShortcuts
import com.laybalt.features.impl.misc.sound.SoundManager
import com.laybalt.ui.Palette
import com.laybalt.ui.clickgui.TooltipManager
import com.laybalt.ui.clickgui.UI
import com.laybalt.ui.clickgui.components.settings.Style
import com.laybalt.ui.clickgui.components.settings.Widget
import com.laybalt.ui.clickgui.components.settings.WidgetFactory
import com.laybalt.ui.clickgui.components.settings.impl.ButtonWidget
import com.laybalt.ui.clickgui.components.settings.impl.CategoryWidget
import com.laybalt.ui.clickgui.components.settings.impl.SeparatorWidget
import com.laybalt.ui.gui.CommandShortcutsScreen
import com.laybalt.ui.gui.SoundManagerScreen
import com.laybalt.ui.notification.NotificationManager
import com.laybalt.ui.render.Icon
import com.laybalt.ui.text.UIFont
import com.laybalt.utils.GuiUtils
import com.mojang.blaze3d.platform.InputConstants
import com.mojang.blaze3d.platform.cursor.CursorTypes
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent

/**
 * Settings of one feature: slides in from the right edge of the window over the cards (~45% of the window width,
 * 200 ms ease-out). Header with name, description, enable switch and close button; below it a scrollable list of the
 * feature's settings grouped by their sections.
 */
class SettingsPanel {
    companion object {
        private const val OPEN_MS = 200f
        private const val CLOSE_MS = 150f
        private const val PAD = 16f
        private const val ROW_GAP = 5f
        private const val NAME_SIZE = 11f
        private const val DESC_SIZE = 7.3f
    }

    var feature: Feature? = null
        private set
    private var widgets = emptyList<Widget<*>>()
    private var visible = emptyList<Widget<*>>()

    private var openedAt = 0L
    private var closedAt = 0L
    private var progress = 0f
    private val contentFade = UI.Smooth(1f, 14f)
    private val switchKnob = UI.Smooth(0f, 18f)
    private val closeHover = UI.Smooth()
    private val switchHover = UI.Smooth()
    private val scroll = ScrollArea(46f)
    private var pendingHighlight: String? = null
    private var mouseDown = false

    var rect = Rect(0f, 0f, 0f, 0f)
        private set
    private var listRect = Rect(0f, 0f, 0f, 0f)
    private var closeRect = Rect(0f, 0f, 0f, 0f)
    private var switchRect = Rect(0f, 0f, 0f, 0f)
    private var descCacheW = -1f
    private var descLines = emptyList<String>()

    /** Open (or opening) and accepting input. */
    val isOpen get() = feature != null && closedAt == 0L

    /** Anything drawn (also while sliding out). */
    val isVisible get() = feature != null

    /** A widget that wants the next click/key (keybind waiting for input). */
    val capturing get() = if (isOpen) visible.firstOrNull { it.capturing } else null

    val typing get() = isOpen && visible.any { it.typing || it.capturing }

    fun open(f: Feature, highlightSetting: String? = null) {
        if (feature === f && isOpen) {
            pendingHighlight = highlightSetting
            return
        }
        val wasOpen = isOpen
        clearFocus()
        Popovers.close()
        feature = f
        widgets = buildWidgets(f)
        scroll.set(0f, instant = true)
        descCacheW = -1f
        switchKnob.snap(if (f.enabled && !f.wip) 1f else 0f)
        pendingHighlight = highlightSetting
        closedAt = 0L
        if (wasOpen) contentFade.snap(0.2f)
        else {
            openedAt = System.currentTimeMillis()
            contentFade.snap(1f)
        }
    }

    fun close() {
        if (!isOpen) return
        clearFocus()
        Popovers.close()
        closedAt = System.currentTimeMillis()
    }

    fun clearFocus() = widgets.forEach { it.clearFocus() }

    private fun buildWidgets(f: Feature): List<Widget<*>> = buildList {
        when (f) {
            is SoundManager -> add(ButtonWidget(ButtonSetting("Open Sound Manager") { openScreen(f) { SoundManagerScreen(mc.gui.screen()) } }))
            is CommandShortcuts -> add(ButtonWidget(ButtonSetting("Open Command Shortcuts") { openScreen(f) { CommandShortcutsScreen(mc.gui.screen()) } }))
        }
        val created = f.configSettings.mapNotNull(WidgetFactory::fromSetting)
        var lastSection: String? = null
        for (widget in created) {
            val section = widget.config.section
            if (section != null && section != lastSection) {
                val members = created.dropWhile { it !== widget }.takeWhile { it === widget || it.config.section == null }
                val sectionVisible = { members.any { it.config.visibility() } }
                if (isNotEmpty()) add(SeparatorWidget().also { it.config.visibility = sectionVisible })
                add(CategoryWidget(section).also { it.config.visibility = sectionVisible })
                lastSection = section
            }
            add(widget)
        }
    }

    private fun openScreen(f: Feature, screen: () -> net.minecraft.client.gui.screens.Screen) {
        if (f.enabled) GuiUtils.setScreen(screen())
        else NotificationManager.push("$MOD_NAME - ClickGui", "Enable ${f.name} first to open the settings!")
    }

    /** Draws the panel (with the dim over [content]) inside [window]; must be called in its own stratum. */
    fun draw(ctx: GuiGraphicsExtractor, window: Rect, content: Rect, mx: Float, my: Float) {
        val f = feature ?: return
        val now = System.currentTimeMillis()
        progress = if (closedAt != 0L) {
            val t = 1f - (now - closedAt) / CLOSE_MS
            if (t <= 0f) {
                feature = null
                widgets = emptyList()
                visible = emptyList()
                closedAt = 0L
                return
            }
            // ease-in on the way out
            t * t
        }
        else UI.easeOutCubic((now - openedAt) / OPEN_MS)

        val w = (window.w * 0.45f).coerceIn(270f, (content.w - 60f).coerceAtLeast(270f))
        val x = content.right - w * progress
        rect = Rect(x, window.y, w, window.h)

        // dim the cards behind the panel
        UI.rect(ctx, content.x, content.y, content.w, content.h, 0f, 10f, 10f, 0f, UI.fade(Palette.scrim, 0.55f * progress))

        ctx.enableScissor(content.x.toInt(), window.y.toInt(), window.right.toInt(), window.bottom.toInt())
        UI.shadow(ctx, x, rect.y, w, rect.h, 10f, 22f, UI.fade(Palette.shadowStrong, progress), offsetX = -6f)
        UI.rect(ctx, x, rect.y, w, rect.h, 10f, Palette.surfaceRaised)
        UI.outline(ctx, x, rect.y, w, rect.h, 10f, Palette.outline)
        val fade = contentFade.update(1f)
        val saved = UI.alpha
        UI.alpha = saved * fade

        val bottomOfHeader = drawHeader(ctx, f, mx, my)
        val listTop = bottomOfHeader + 8f
        listRect = Rect(x + 1f, listTop, w - 2f, rect.bottom - 8f - listTop)
        drawList(ctx, mx, my)
        UI.alpha = saved
        ctx.disableScissor()
    }

    private fun drawHeader(ctx: GuiGraphicsExtractor, f: Feature, mx: Float, my: Float): Float {
        val x = rect.x
        val top = rect.y + PAD
        closeRect = Rect(rect.right - PAD - 20f + 4f, top - 3f, 20f, 20f)
        val ch = closeHover.update(isOpen && closeRect.contains(mx, my))
        UI.iconButton(ctx, Icon.CLOSE, closeRect.x, closeRect.y, closeRect.w, ch, Palette.textSecondary, Palette.textPrimary, 0.5f)

        switchRect = Rect(closeRect.x - 10f - UI.SWITCH_W, top + 1f, UI.SWITCH_W, UI.SWITCH_H)
        val sh = switchHover.update(isOpen && switchRect.contains(mx, my))
        UI.switch(ctx, switchRect.x, switchRect.y, switchKnob.update(f.enabled && !f.wip), sh, enabled = !f.wip)
        if (ch > 0.5f || sh > 0.5f) ctx.requestCursor(if (sh > 0.5f && f.wip) CursorTypes.NOT_ALLOWED else CursorTypes.POINTING_HAND)

        val nameMax = switchRect.x - 10f - (x + PAD)
        UI.textMid(ctx, UIFont.trim(f.name, nameMax, NAME_SIZE, UIFont.Weight.BOLD), x + PAD, top + 6f, Palette.textPrimary, NAME_SIZE, UIFont.Weight.BOLD)

        var y = top + 16f
        val label = if (f.category.name == "DEV") "Client" else f.category.displayName
        var cx = x + PAD
        cx += UI.chip(ctx, label, cx, y, 12f, Palette.chip, Palette.chipText, 6.2f) + 4f
        if (f.wip) cx += UI.chip(ctx, "WIP", cx, y, 12f, UI.fade(Palette.warning, 0.16f), Palette.warning, 6.2f) + 4f
        // every setting of the feature, including ones hidden until their parent option is switched on
        val count = widgets.count { it !is CategoryWidget && it !is SeparatorWidget }
        if (count > 0) UI.chip(ctx, if (count == 1) "1 setting" else "$count settings", cx, y, 12f, UI.fade(Palette.chip, 0.6f), Palette.textMuted, 6.2f)
        y += 18f

        val desc = f.description?.takeIf { it.isNotBlank() }
        if (desc != null) {
            val dw = rect.w - PAD * 2f
            if (descCacheW != dw) {
                descCacheW = dw
                descLines = UIFont.wrap(desc, dw, DESC_SIZE, maxLines = 4)
            }
            val lh = UIFont.lineHeight(DESC_SIZE) + 1.2f
            descLines.forEachIndexed { i, line -> UI.text(ctx, line, x + PAD, y + i * lh, Palette.textSecondary, DESC_SIZE) }
            y += descLines.size * lh
            if (descLines.lastOrNull()?.endsWith("…") == true && UI.inside(mx, my, x + PAD, y - descLines.size * lh, dw, descLines.size * lh)) TooltipManager.hover(desc, mx, my)
        }
        y += 6f
        UI.hLine(ctx, x + PAD, y, rect.w - PAD * 2f, Palette.divider)
        return y
    }

    private fun drawList(ctx: GuiGraphicsExtractor, mx: Float, my: Float) {
        val lr = listRect
        visible = widgets.filter { it.config.visibility() }
        val rowX = lr.x + PAD - 1f
        var rowW = lr.w - (PAD - 1f) * 2f
        // Widths first: some heights depend on them (wrapped chips).
        visible.forEach { it.width = rowW }
        var total = visible.sumOf { it.height.toDouble() }.toFloat() + (visible.size - 1).coerceAtLeast(0) * ROW_GAP + 4f
        if (total > lr.h) {
            rowW -= 4f
            visible.forEach { it.width = rowW }
            total = visible.sumOf { it.height.toDouble() }.toFloat() + (visible.size - 1).coerceAtLeast(0) * ROW_GAP + 4f
        }

        pendingHighlight?.let { target ->
            pendingHighlight = null
            var offset = 0f
            for (wgt in visible) {
                if (wgt !is CategoryWidget && wgt !is SeparatorWidget && wgt.name.replace(" ", "").contains(target.replace(" ", ""), ignoreCase = true)) {
                    scroll.update(lr.right, lr.y, lr.h, total, my)
                    scroll.set(offset - 24f, instant = false)
                    wgt.highlightUntil = System.currentTimeMillis() + 1600
                    break
                }
                offset += wgt.height + ROW_GAP
            }
        }

        scroll.update(lr.right - 1f, lr.y, lr.h, total, my)
        val mouseInList = isOpen && lr.contains(mx, my) && Popovers.current?.contains(mx, my) != true
        // While a button is held (slider drag, text selection) widgets need the real position even outside the list.
        val wmx = if (mouseInList || mouseDown) mx else -10000f
        val wmy = if (mouseInList || mouseDown) my else -10000f

        ctx.enableScissor(lr.x.toInt(), lr.y.toInt(), lr.right.toInt(), lr.bottom.toInt())
        if (visible.isEmpty()) {
            val cy = lr.y + 40f
            UI.circle(ctx, lr.x + lr.w / 2f, cy, 15f, Palette.chip)
            UI.icon(ctx, Icon.INFO, lr.x + lr.w / 2f - 8f, cy - 8f, 16f, Palette.textMuted)
            UI.textMidCentered(ctx, "This feature has no settings.", lr.x + lr.w / 2f, cy + 28f, Palette.textMuted, 7.6f)
        }
        var y = lr.y + 2f - scroll.offset
        for (wgt in visible) {
            wgt.x = rowX
            wgt.y = y
            val h = wgt.height
            if (y + h >= lr.y - 40f && y <= lr.bottom + 40f) {
                wgt.draw(ctx, wmx, wmy)
                if (mouseInList && Popovers.current == null && capturing == null && wgt.tooltipArea().contains(mx, my)) TooltipManager.hover(wgt.config.description, mx, my)
            }
            y += h + ROW_GAP
        }
        ctx.disableScissor()
        // soft edges where rows scroll under
        if (scroll.offset > 1f) UI.vGradient(ctx, lr.x, lr.y, lr.w, 8f, Palette.surfaceRaised, UI.fade(Palette.surfaceRaised, 0f))
        if (scroll.offset < scroll.max - 1f) UI.vGradient(ctx, lr.x, lr.bottom - 8f, lr.w, 8f, UI.fade(Palette.surfaceRaised, 0f), Palette.surfaceRaised)
        scroll.draw(ctx, mx, my)
    }

    fun contains(mx: Float, my: Float) = isVisible && rect.contains(mx, my)

    /** Rows of the settings list (popovers close when their row scrolls out of it). */
    val listArea get() = listRect

    fun mouseClicked(mx: Float, my: Float, button: Int): Boolean {
        if (!isOpen) return false
        mouseDown = true
        capturing?.let { it.mouseClicked(mx, my, button); return true }
        if (!rect.contains(mx, my)) return false
        val f = feature ?: return true

        if (button == InputConstants.MOUSE_BUTTON_LEFT && closeRect.contains(mx, my)) {
            Style.playClickSound(0.9f)
            close()
            return true
        }
        if (button == InputConstants.MOUSE_BUTTON_LEFT && switchRect.let { Rect(it.x - 3f, it.y - 3f, it.w + 6f, it.h + 6f) }.contains(mx, my)) {
            f.toggle()
            Style.playClickSound(if (f.wip) 0.6f else if (f.enabled) 1.1f else 0.9f)
            return true
        }
        if (button == InputConstants.MOUSE_BUTTON_LEFT && scroll.mouseClicked(mx, my)) return true

        if (listRect.contains(mx, my)) {
            var consumer: Widget<*>? = null
            for (wgt in visible) {
                if (consumer == null) {
                    if (wgt.mouseClicked(mx, my, button)) consumer = wgt
                }
                else wgt.clearFocus()
            }
        }
        else visible.forEach { it.clearFocus() }
        return true
    }

    fun mouseReleased(button: Int) {
        mouseDown = false
        scroll.mouseReleased()
        visible.forEach { it.mouseReleased(button) }
    }

    fun mouseScrolled(mx: Float, my: Float, delta: Double): Boolean {
        if (!isOpen || !rect.contains(mx, my)) return false
        if (listRect.contains(mx, my)) visible.forEach { if (it.mouseScrolled(mx, my, delta)) return true }
        scroll.scroll(delta)
        return true
    }

    fun keyPressed(event: KeyEvent): Boolean {
        if (!isOpen) return false
        capturing?.let { return it.keyPressed(event) }
        visible.forEach { if (it.keyPressed(event)) return true }
        return false
    }

    fun charTyped(event: CharacterEvent): Boolean {
        if (!isOpen) return false
        visible.forEach { if (it.charTyped(event)) return true }
        return false
    }
}
