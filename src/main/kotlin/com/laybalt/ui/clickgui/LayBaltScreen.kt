package com.laybalt.ui.clickgui

import com.laybalt.LayBaltQOL.mc
import com.laybalt.config.ConfigManager
import com.laybalt.config.types.ButtonSetting
import com.laybalt.features.Feature
import com.laybalt.features.FeatureManager
import com.laybalt.features.impl.dev.ClickGui
import com.laybalt.features.impl.general.CommandShortcuts
import com.laybalt.features.impl.misc.sound.SoundManager
import com.laybalt.ui.Palette
import com.laybalt.ui.clickgui.components.FeatureGrid
import com.laybalt.ui.clickgui.components.Popovers
import com.laybalt.ui.clickgui.components.Rect
import com.laybalt.ui.clickgui.components.SearchField
import com.laybalt.ui.clickgui.components.SettingsPanel
import com.laybalt.ui.clickgui.components.Sidebar
import com.laybalt.ui.clickgui.components.settings.Style
import com.laybalt.ui.clickgui.components.settings.WidgetFactory
import com.laybalt.ui.clickgui.enums.CategoryType
import com.laybalt.ui.hud.HudEditorScreen
import com.laybalt.ui.text.UIFont
import com.laybalt.ui.utils.Resolution
import com.laybalt.utils.Utils
import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component

/**
 * The LayBalt QOL main screen: one centred rounded window with a category sidebar, a search bar, a responsive grid of
 * feature cards and a settings panel that slides in from the right. Opened by /lbq, /laybalt, the ClickGui keybind
 * and Mod Menu; [parent] is shown again when it closes.
 */
class LayBaltScreen(private val parent: Screen? = null): Screen(Component.literal("LayBalt QOL")) {
    private companion object {
        const val OPEN_MS = 180f
        const val CLOSE_MS = 130f
        const val TOP_BAR_H = 46f
    }

    private var openedAt = System.currentTimeMillis()
    private var closingAt = 0L

    private var category = GuiState.category

    private val search = SearchField { onSearchChanged() }
    private val panel = SettingsPanel()
    private val grid = FeatureGrid(::hasSettings, ::openFeature) { panel.isOpen && panel.feature === it }
    private val sidebar = Sidebar(
        selected = { if (searching) null else category },
        onCategory = ::selectCategory,
        onHudEditor = ::openHudEditor,
        onDiscord = { Utils.openDiscordLink() }
    )

    private var window = Rect(0f, 0f, 0f, 0f)
    private var sideRect = Rect(0f, 0f, 0f, 0f)
    private var contentRect = Rect(0f, 0f, 0f, 0f)
    private var gridRect = Rect(0f, 0f, 0f, 0f)
    private var lastSearching = false

    private val searching get() = search.query.isNotBlank()

    init {
        grid.scroll.set(GuiState.scroll(category), instant = true)
    }

    // ------------------------------------------------------------------ data

    private fun hasSettings(feature: Feature) =
        feature is SoundManager || feature is CommandShortcuts || feature.configSettings.any(WidgetFactory::hasWidget)

    private var cachedQuery = ""
    private var cachedResults = emptyList<Feature>()
    private var cachedAt = 0L

    private fun currentFeatures(): List<Feature> {
        if (searching) {
            // Ranking walks every setting of every feature; recompute only when the query changes (or every 0.5 s).
            val now = System.currentTimeMillis()
            if (search.query != cachedQuery || now - cachedAt > 500) {
                cachedQuery = search.query
                cachedAt = now
                cachedResults = SuggestionManager.getSuggestions(search.query, FeatureManager.features.toList()).toList()
            }
            return cachedResults
        }
        val list = FeatureManager.getFeaturesByCategory(category)
        return when (ClickGui.panelSorting.value) {
            0 -> list.sortedBy { it.name.lowercase() }
            1 -> list.sortedByDescending { UIFont.width(it.name, 8f, UIFont.Weight.SEMIBOLD) }
            else -> list
        }
    }

    private fun selectCategory(c: CategoryType) {
        val changed = c != category || searching
        if (!changed) return
        if (!searching) GuiState.setScroll(category, grid.scroll.target)
        category = c
        GuiState.category = c
        search.clear()
        search.unfocus()
        panel.close()
        grid.scroll.set(GuiState.scroll(c), instant = true)
        grid.resetAppear()
        Style.playClickSound(1.15f)
    }

    private fun onSearchChanged() {
        val now = searching
        if (now && !lastSearching) GuiState.setScroll(category, grid.scroll.target)
        grid.scroll.set(if (now) 0f else GuiState.scroll(category), instant = true)
        if (now != lastSearching) grid.resetAppear()
        lastSearching = now
    }

    private fun openFeature(feature: Feature) {
        // From search results, jump to the setting that matched the query.
        val q = search.query.trim()
        val matchedSetting = if (searching && !feature.name.contains(q, ignoreCase = true))
            feature.configSettings.firstOrNull { it.name.replace(" ", "").contains(q.replace(" ", ""), ignoreCase = true) && it !is ButtonSetting }?.name
        else null
        search.unfocus()
        panel.open(feature, matchedSetting)
    }

    private fun openHudEditor() {
        Style.playClickSound(1f)
        mc.gui.setScreen(HudEditorScreen(this))
    }

    // ------------------------------------------------------------------ layout & render

    private fun layout() {
        val cw = Resolution.width
        val ch = Resolution.height
        val w = (cw * 0.78f).coerceIn(640f.coerceAtMost(cw - 20f), cw - 20f)
        val h = (ch * 0.80f).coerceIn(380f.coerceAtMost(ch - 20f), ch - 20f)
        window = Rect(((cw - w) / 2f), ((ch - h) / 2f), w, h)
        sideRect = Rect(window.x, window.y, Sidebar.WIDTH, window.h)
        contentRect = Rect(window.x + Sidebar.WIDTH, window.y, window.w - Sidebar.WIDTH, window.h)
        gridRect = Rect(contentRect.x, window.y + TOP_BAR_H, contentRect.w, window.h - TOP_BAR_H - 4f)
    }

    /**
     * False until the first frame after [added]: the character event of the key that opened this screen (a letter
     * open keybind) arrives right after the key press and must not start a search.
     */
    private var acceptChars = false

    override fun added() {
        super.added()
        acceptChars = false
        openedAt = System.currentTimeMillis()
        closingAt = 0L
        closeScheduled = false
    }

    override fun extractRenderState(ctx: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        acceptChars = true
        UI.beginFrame()
        Resolution.push(ctx)
        val mx = mouseX / Resolution.scale
        val my = mouseY / Resolution.scale
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
        ctx.drawFullScrim(t)

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
        // Mouse position in the (possibly scaled) window space.
        val wmx = cx + (mx - cx) / scale
        val wmy = cy + (my - cy) / scale
        val interactive = closingAt == 0L

        drawWindow(ctx, wmx, wmy, interactive)

        ctx.pose().popMatrix()
        UIFont.transientScale = 1f
        TooltipManager.draw(ctx, Resolution.width, Resolution.height)
        Resolution.pop(ctx)
        UI.alpha = 1f
    }

    private fun GuiGraphicsExtractor.drawFullScrim(t: Float) {
        UI.vGradient(this, 0f, 0f, Resolution.width, Resolution.height, Palette.backdropTop, Palette.scrim)
        // faint accent glow behind the window
        UI.shadow(this, window.x, window.y, window.w, window.h, 10f, 70f, UI.fade(Palette.accent, 0.10f * t), spread = -10f)
    }

    private fun drawWindow(ctx: GuiGraphicsExtractor, mx: Float, my: Float, interactive: Boolean) {
        val w = window
        val panelBlocks = panel.isVisible && panel.contains(mx, my)
        val popoverBlocks = Popovers.current?.contains(mx, my) == true
        val free = interactive && !panelBlocks && !popoverBlocks
        val fmx = if (free) mx else -10000f
        val fmy = if (free) my else -10000f

        UI.shadow(ctx, w.x, w.y, w.w, w.h, 10f, 26f, Palette.shadowStrong, offsetY = 8f)
        UI.rect(ctx, w.x, w.y, w.w, w.h, 10f, Palette.surface)

        sidebar.draw(ctx, sideRect, fmx, fmy)
        drawTopBar(ctx, fmx, fmy)
        UI.hLine(ctx, contentRect.x, gridRect.y - UI.hair(ctx), contentRect.w, Palette.divider)

        val features = currentFeatures()
        grid.draw(ctx, gridRect, features, searching, search.query.trim(), fmx, fmy, free)

        UI.outline(ctx, w.x, w.y, w.w, w.h, 10f, Palette.outline)

        if (panel.isVisible) {
            ctx.nextStratum()
            val pmx = if (interactive && !popoverBlocks) mx else -10000f
            val pmy = if (interactive && !popoverBlocks) my else -10000f
            panel.draw(ctx, w, contentRect, pmx, pmy)
            if (Popovers.current != null && panel.isOpen) Popovers.render(ctx, mx, my, panel.listArea, w)
        }
        else if (Popovers.current != null) Popovers.close()
    }

    private fun drawTopBar(ctx: GuiGraphicsExtractor, mx: Float, my: Float) {
        val c = contentRect
        val searchW = (c.w * 0.46f).coerceIn(170f, 300f)
        search.draw(ctx, Rect(c.x + 16f, window.y + 12f, searchW, 22f), mx, my)

        val right = c.right - 18f
        val (title, sub) = if (searching) {
            val n = currentFeatures().size
            "Search" to (if (n == 1) "1 result" else "$n results")
        }
        else {
            val list = FeatureManager.getFeaturesByCategory(category)
            val enabled = list.count { it.enabled && !it.wip }
            val name = if (category == CategoryType.DEV) "Client" else category.displayName
            name to "${list.size} features · $enabled enabled"
        }
        UI.textMidRight(ctx, title, right, window.y + 17.5f, Palette.textPrimary, 10f, UIFont.Weight.SEMIBOLD)
        UI.textMidRight(ctx, sub, right, window.y + 30.5f, Palette.textMuted, 6.8f)
    }

    // ------------------------------------------------------------------ input

    private fun canvasX(x: Double) = (x / Resolution.scale).toFloat()
    private fun canvasY(y: Double) = (y / Resolution.scale).toFloat()

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        if (closingAt != 0L) return true
        val mx = canvasX(event.x)
        val my = canvasY(event.y)
        val button = event.button()

        if (Popovers.mouseClicked(mx, my, button)) return true
        if (panel.capturing != null) return panel.mouseClicked(mx, my, button)
        if (panel.isOpen && panel.contains(mx, my)) {
            search.unfocus()
            return panel.mouseClicked(mx, my, button)
        }
        if (search.mouseClicked(mx, my, button)) {
            panel.clearFocus()
            return true
        }
        if (sidebar.mouseClicked(mx, my)) return true

        if (panel.isOpen) {
            panel.clearFocus()
            // Clicking another card switches the panel (its switch just toggles); any other click closes it.
            if (grid.mouseClicked(mx, my, button) != FeatureGrid.Hit.CARD) panel.close()
            return true
        }
        if (grid.mouseClicked(mx, my, button) != FeatureGrid.Hit.OUTSIDE) return true
        return super.mouseClicked(event, doubleClick)
    }

    override fun mouseReleased(event: MouseButtonEvent): Boolean {
        val button = event.button()
        Popovers.mouseReleased(button)
        panel.mouseReleased(button)
        grid.mouseReleased()
        search.mouseReleased()
        return super.mouseReleased(event)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontal: Double, vertical: Double): Boolean {
        if (closingAt != 0L) return true
        val mx = canvasX(mouseX)
        val my = canvasY(mouseY)
        if (Popovers.current != null && Popovers.mouseScrolled(mx, my, vertical)) return true
        if (panel.mouseScrolled(mx, my, vertical)) return true
        if (grid.mouseScrolled(mx, my, vertical)) return true
        return super.mouseScrolled(mouseX, mouseY, horizontal, vertical)
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        if (closingAt != 0L) return true
        if (Popovers.keyPressed(event)) return true
        if (panel.keyPressed(event)) return true
        if (search.keyPressed(event)) return true

        if (event.hasControlDown() && event.key == InputConstants.KEY_F) {
            panel.clearFocus()
            search.focus()
            return true
        }
        if (event.key == InputConstants.KEY_ESCAPE) {
            if (panel.isOpen) panel.close()
            else onClose()
            return true
        }
        return super.keyPressed(event)
    }

    override fun charTyped(event: CharacterEvent): Boolean {
        if (closingAt != 0L || !acceptChars) return true
        if (Popovers.charTyped(event)) return true
        if (panel.charTyped(event)) return true
        if (search.charTyped(event)) return true
        // Typing anywhere starts a search.
        val c = event.codepoint
        if (!panel.typing && c > 32 && !Character.isISOControl(c)) {
            search.focus()
            return search.charTyped(event)
        }
        return super.charTyped(event)
    }

    // ------------------------------------------------------------------ lifecycle

    override fun shouldCloseOnEsc() = true

    override fun onClose() {
        if (closingAt != 0L) return
        closingAt = System.currentTimeMillis()
        Popovers.close()
        panel.clearFocus()
        search.unfocus()
    }

    private var closeScheduled = false

    private fun finishClose() {
        UI.alpha = 1f
        UIFont.transientScale = 1f
        if (closeScheduled) return
        closeScheduled = true
        mc.execute { if (mc.gui.screen() === this) mc.gui.setScreen(parent) }
    }

    override fun removed() {
        Popovers.close()
        panel.clearFocus()
        search.unfocus()
        if (!searching) GuiState.setScroll(category, grid.scroll.target)
        GuiState.category = category
        GuiState.save()
        ConfigManager.save()
        super.removed()
    }
}
