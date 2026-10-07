package com.laybalt.ui.clickgui.components.settings.impl

import com.laybalt.config.types.SoundSetting
import com.laybalt.ui.Palette
import com.laybalt.ui.clickgui.UI
import com.laybalt.ui.clickgui.components.Popover
import com.laybalt.ui.clickgui.components.Popovers
import com.laybalt.ui.clickgui.components.Rect
import com.laybalt.ui.clickgui.components.ScrollArea
import com.laybalt.ui.clickgui.components.settings.Style
import com.laybalt.ui.clickgui.components.settings.Widget
import com.laybalt.ui.render.Icon
import com.laybalt.ui.text.UIFont
import com.laybalt.ui.utils.TextInputHandler
import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.input.MouseButtonInfo
import net.minecraft.sounds.SoundEvent

/** Select with the sound's name; the popover has a search field and a scrollable list of every sound. */
class SoundWidget(config: SoundSetting): Widget<SoundEvent>(config) {
    private inline val cfg get() = config as SoundSetting

    private var box = Rect(0f, 0f, 0f, 0f)
    private val list = SoundList()

    override val typing get() = list.search.listening

    override fun draw(ctx: GuiGraphicsExtractor, mx: Float, my: Float) {
        val open = Popovers.isOpen(list)
        drawRow(ctx, mx, my, active = open)
        val text = cfg.prettyName(value) ?: value.location.path
        box = selectBox(text, width * 0.55f)
        drawLabel(ctx, box.x - x - PAD - 6f)
        drawSelectBox(ctx, box, text, mx, my, open)
    }

    override fun mouseClicked(mx: Float, my: Float, button: Int): Boolean {
        if (button != InputConstants.MOUSE_BUTTON_LEFT || !isHovered(mx, my)) return false
        list.reset()
        Popovers.open(list)
        Style.playClickSound(1f)
        return true
    }

    override fun clearFocus() = list.search.resetState()

    private inner class SoundList: Popover() {
        private var query = ""
        private var filtered = SoundSetting.allSounds
        val search = TextInputHandler({ query }, {
            query = it
            filtered = if (it.isBlank()) SoundSetting.allSounds
            else SoundSetting.allSounds.filter { s -> (cfg.prettyName(s) ?: s.location.toString()).contains(it, ignoreCase = true) }
            scroll.set(0f, instant = true)
        }).apply {
            fontSize = VALUE_SIZE
            padding = 20f
        }
        val scroll = ScrollArea(ITEM_H * 3f)

        private val pad = 6f
        override val width get() = 210f
        override val height get() = 172f

        override fun anchor() = box

        fun reset() {
            if (query.isNotEmpty()) {
                query = ""
                filtered = SoundSetting.allSounds
            }
            val index = filtered.indexOf(value).coerceAtLeast(0)
            scroll.set(((index - 3) * ITEM_H).coerceAtLeast(0f), instant = true)
            search.focusEnd()
        }

        private fun searchRect() = Rect(x + pad, y + pad, width - pad * 2f, 20f)
        private fun listY() = y + pad + 24f
        private fun listH() = height - pad * 2f - 24f

        override fun drawContent(ctx: GuiGraphicsExtractor, mx: Float, my: Float) {
            val sr = searchRect()
            UI.rect(ctx, sr.x, sr.y, sr.w, sr.h, 6f, Palette.input)
            UI.outline(ctx, sr.x, sr.y, sr.w, sr.h, 6f, if (search.listening) Palette.accentBorder else Palette.outline)
            UI.icon(ctx, Icon.SEARCH, sr.x + 6f, sr.y + 5f, 10f, Palette.textMuted)
            search.x = sr.x
            search.y = sr.y
            search.width = sr.w
            search.height = sr.h
            search.opacity = UI.alpha
            if (query.isEmpty() && !search.listening) UI.textMid(ctx, "Search sounds…", sr.x + 20f, sr.y + sr.h / 2f, Palette.textMuted, VALUE_SIZE)
            else search.draw(ctx, mx, my)

            val ly = listY()
            val lh = listH()
            scroll.update(x + width - 2f, ly, lh, filtered.size * ITEM_H, my)
            if (filtered.isEmpty()) {
                UI.textMidCentered(ctx, "No sounds found", x + width / 2f, ly + lh / 2f, Palette.textMuted, VALUE_SIZE)
                return
            }
            ctx.enableScissor(x.toInt(), ly.toInt(), (x + width).toInt(), (ly + lh).toInt())
            val first = (scroll.offset / ITEM_H).toInt().coerceAtLeast(0)
            val last = ((scroll.offset + lh) / ITEM_H).toInt().coerceAtMost(filtered.lastIndex)
            for (i in first..last) {
                val sound = filtered[i]
                val iy = ly + i * ITEM_H - scroll.offset
                val hot = UI.inside(mx, my, x + 4f, iy, width - 8f, ITEM_H) && my >= ly && my < ly + lh
                val selected = sound == value
                if (hot) UI.rect(ctx, x + 4f, iy + 0.5f, width - 12f, ITEM_H - 1f, 4.5f, Palette.accentPill)
                val color = if (selected) Palette.accentText else if (hot) Palette.textPrimary else Palette.textSecondary
                val label = cfg.prettyName(sound) ?: sound.location.path
                UI.textMid(ctx, UIFont.trim(label, width - 34f, VALUE_SIZE), x + 10f, iy + ITEM_H / 2f, color, VALUE_SIZE, if (selected) UIFont.Weight.SEMIBOLD else UIFont.Weight.REGULAR)
                if (selected) UI.icon(ctx, Icon.CHECK, x + width - 22f, iy + (ITEM_H - 8f) / 2f, 8f, Palette.accentText)
            }
            ctx.disableScissor()
            scroll.draw(ctx, mx, my)
        }

        override fun mouseClicked(mx: Float, my: Float, button: Int): Boolean {
            if (search.mouseClicked(mx, my, MouseButtonEvent(mx.toDouble(), my.toDouble(), MouseButtonInfo(button, 0)))) return true
            if (button != InputConstants.MOUSE_BUTTON_LEFT) return true
            if (scroll.mouseClicked(mx, my)) return true
            val ly = listY()
            if (my < ly || my >= ly + listH()) return true
            val index = ((my - ly + scroll.offset) / ITEM_H).toInt()
            if (index in filtered.indices) {
                value = SoundSetting.getSound(filtered[index].location())?.value() ?: filtered[index]
                Style.playClickSound(1f)
                Popovers.close()
            }
            return true
        }

        override fun mouseReleased(button: Int) {
            search.mouseReleased()
            scroll.mouseReleased()
        }

        override fun mouseScrolled(mx: Float, my: Float, delta: Double): Boolean {
            scroll.scroll(delta)
            return true
        }

        override fun keyPressed(event: KeyEvent): Boolean {
            if (event.key == InputConstants.KEY_ESCAPE) return false
            return search.keyPressed(event)
        }

        override fun charTyped(event: CharacterEvent) = search.keyTyped(event)

        override fun onClose() = search.resetState()
    }

    private companion object {
        const val ITEM_H = 16f
    }
}
