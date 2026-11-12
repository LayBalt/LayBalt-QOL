package com.laybalt.ui.gui

import com.laybalt.features.impl.general.CommandShortcuts
import com.laybalt.ui.Palette
import com.laybalt.ui.clickgui.LayBaltScreen
import com.laybalt.ui.clickgui.TooltipManager
import com.laybalt.ui.clickgui.UI
import com.laybalt.ui.clickgui.components.Rect
import com.laybalt.ui.clickgui.components.ScrollArea
import com.laybalt.ui.clickgui.components.settings.Style
import com.laybalt.ui.render.Icon
import com.laybalt.ui.text.UIFont
import com.laybalt.ui.utils.TextInputHandler
import com.mojang.blaze3d.platform.InputConstants
import com.mojang.blaze3d.platform.cursor.CursorTypes
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.input.MouseButtonInfo

/**
 * Editor of the Command Shortcuts feature: one row per shortcut ("/dh" runs "/warp dungeon_hub"), rows can be added
 * and deleted; everything is saved and re-registered when the window closes.
 */
class CommandShortcutsScreen(private val parent: Screen? = null): LayBaltSubScreen("Command Shortcuts") {
    private companion object {
        const val ROW_H = 32f
        const val ROW_GAP = 5f
        const val FIELD_H = 20f
        const val COMMAND_W = 128f
        const val ARROW_W = 26f
        const val FOOTER_H = 42f
        const val TEXT_SIZE = 7.6f
    }

    private class Row(var command: String, var replacement: String) {
        val commandInput = TextInputHandler({ command }, { command = it }).apply { fontSize = TEXT_SIZE; padding = 0f }
        val replacementInput = TextInputHandler({ replacement }, { replacement = it }).apply { fontSize = TEXT_SIZE; padding = 0f }
        val hover = UI.Smooth()
        val deleteHover = UI.Smooth()
        val appear = UI.Smooth(0f, 14f)
        val inputs get() = listOf(commandInput, replacementInput)
    }

    private val rows = CommandShortcuts.shortcuts.get().map { (command, replacement) -> Row(command, replacement) }.toMutableList()
    private val scroll = ScrollArea(ROW_H * 2f)
    private val addHover = UI.Smooth()
    private val doneHover = UI.Smooth()

    private var listRect = Rect(0f, 0f, 0f, 0f)
    private var footerRect = Rect(0f, 0f, 0f, 0f)
    private var addRect = Rect(0f, 0f, 0f, 0f)
    private var doneRect = Rect(0f, 0f, 0f, 0f)

    init {
        rows.forEach { it.appear.snap(1f) }
    }

    override val heading get() = "Command Shortcuts"
    override val subheading get() = when (rows.size) {
        0 -> "Short commands that run longer ones"
        1 -> "1 shortcut · type it in chat to run the full command"
        else -> "${rows.size} shortcuts · type one in chat to run the full command"
    }
    override val headingIcon get() = Icon.COMMAND

    override fun windowSize(canvasW: Float, canvasH: Float) =
        (canvasW * 0.56f).coerceIn(500f, 660f) to (canvasH * 0.70f).coerceIn(320f, 440f)

    override fun returnTo() = parent ?: LayBaltScreen()

    // ------------------------------------------------------------------ drawing

    private fun rowRect(index: Int) = Rect(listRect.x, listRect.y + 2f - scroll.offset + index * (ROW_H + ROW_GAP), listRect.w, ROW_H)
    private fun commandField(r: Rect) = Rect(r.x + 8f, r.y + (r.h - FIELD_H) / 2f, COMMAND_W, FIELD_H)
    private fun replacementField(r: Rect): Rect {
        val x = r.x + 8f + COMMAND_W + ARROW_W
        return Rect(x, r.y + (r.h - FIELD_H) / 2f, deleteRect(r).x - 26f - x, FIELD_H)
    }
    private fun deleteRect(r: Rect) = Rect(r.right - 8f - 20f, r.y + (r.h - 20f) / 2f, 20f, 20f)
    private fun warningRect(r: Rect) = Rect(deleteRect(r).x - 22f, r.y + (r.h - 16f) / 2f, 16f, 16f)

    override fun drawBody(ctx: GuiGraphicsExtractor, mx: Float, my: Float) {
        val b = body
        footerRect = Rect(b.x, b.bottom - FOOTER_H, b.w, FOOTER_H)
        listRect = Rect(b.x + PAD, b.y + 28f, b.w - PAD * 2f - 6f, b.h - 28f - FOOTER_H - 4f)

        // column labels
        val labelY = b.y + 16f
        UI.textMid(ctx, "SHORTCUT", listRect.x + 8f, labelY, Palette.textMuted, 5.8f, UIFont.Weight.SEMIBOLD)
        UI.textMid(ctx, "RUNS", listRect.x + 8f + COMMAND_W + ARROW_W, labelY, Palette.textMuted, 5.8f, UIFont.Weight.SEMIBOLD)

        val contentH = rows.size * (ROW_H + ROW_GAP) + 4f
        scroll.update(listRect.right + 8f, listRect.y, listRect.h, contentH, my)
        val inList = listRect.contains(mx, my)
        val rmx = if (inList) mx else -10000f
        val rmy = if (inList) my else -10000f

        if (rows.isEmpty()) drawEmptyState(ctx, listRect, Icon.COMMAND, "No shortcuts yet", "Add one to turn /dh into /warp dungeon_hub.")

        ctx.enableScissor(listRect.x.toInt() - 2, listRect.y.toInt(), listRect.right.toInt() + 2, listRect.bottom.toInt())
        val keys = rows.map { it.command.trim().substringBefore(" ").lowercase() }
        rows.forEachIndexed { i, row ->
            val r = rowRect(i)
            positionRow(row, r)
            if (r.bottom < listRect.y - 4f || r.y > listRect.bottom + 4f) return@forEachIndexed
            val duplicate = keys[i].isNotEmpty() && keys.lastIndexOf(keys[i]) != i
            drawRow(ctx, row, r, rmx, rmy, duplicate)
        }
        ctx.disableScissor()
        if (scroll.offset > 1f) UI.vGradient(ctx, listRect.x - 2f, listRect.y, listRect.w + 4f, 8f, Palette.surface, UI.fade(Palette.surface, 0f))
        if (scroll.offset < scroll.max - 1f) UI.vGradient(ctx, listRect.x - 2f, listRect.bottom - 8f, listRect.w + 4f, 8f, UI.fade(Palette.surface, 0f), Palette.surface)
        scroll.draw(ctx, mx, my)

        drawFooter(ctx, mx, my)
    }

    private fun positionRow(row: Row, r: Rect) {
        val c = commandField(r)
        row.commandInput.x = c.x + 15f
        row.commandInput.y = c.y
        row.commandInput.width = c.w - 21f
        row.commandInput.height = c.h
        val p = replacementField(r)
        row.replacementInput.x = p.x + 15f
        row.replacementInput.y = p.y
        row.replacementInput.width = p.w - 21f
        row.replacementInput.height = p.h
    }

    private fun drawRow(ctx: GuiGraphicsExtractor, row: Row, r: Rect, mx: Float, my: Float, duplicate: Boolean) {
        val a = row.appear.update(1f)
        val saved = UI.alpha
        UI.alpha = saved * a
        val focused = row.commandInput.listening || row.replacementInput.listening
        val hot = row.hover.update(r.contains(mx, my) || focused)
        UI.rect(ctx, r.x, r.y, r.w, r.h, 7f, UI.mix(Palette.card, Palette.cardHover, hot))
        UI.outline(ctx, r.x, r.y, r.w, r.h, 7f, UI.mix(Palette.divider, Palette.outline, hot))

        drawField(ctx, commandField(r), row.commandInput, row.command, "dh", mx, my)
        val arrowX = r.x + 8f + COMMAND_W + (ARROW_W - 11f) / 2f
        UI.icon(ctx, Icon.ARROW_RIGHT, arrowX, r.y + (r.h - 11f) / 2f, 11f, UI.mix(Palette.textMuted, Palette.accentText, hot * 0.6f))
        drawField(ctx, replacementField(r), row.replacementInput, row.replacement, "warp dungeon_hub", mx, my)

        // issues
        val incomplete = !focused && (row.command.isBlank() != row.replacement.isBlank())
        val issue = when {
            incomplete -> "Incomplete shortcuts are not saved."
            duplicate -> "/${row.command.trim().substringBefore(" ").lowercase()} is used again further down; the last one wins."
            else -> null
        }
        if (issue != null) {
            val w = warningRect(r)
            UI.icon(ctx, Icon.WARNING, w.x, w.y, w.w, Palette.warning)
            if (w.contains(mx, my)) TooltipManager.hover(issue, mx, my)
        }

        val d = deleteRect(r)
        val dh = row.deleteHover.update(d.contains(mx, my))
        if (dh > 0.5f) {
            ctx.requestCursor(CursorTypes.POINTING_HAND)
            TooltipManager.hover("Delete shortcut", mx, my)
        }
        if (dh > 0.01f) UI.rect(ctx, d.x, d.y, d.w, d.h, 6f, UI.fade(Palette.danger, 0.22f * dh))
        UI.icon(ctx, Icon.TRASH, d.x + 4f, d.y + 4f, 12f, UI.mix(Palette.textMuted, Palette.danger.brighter(), dh))
        UI.alpha = saved
    }

    private fun drawField(ctx: GuiGraphicsExtractor, f: Rect, handler: TextInputHandler, text: String, placeholder: String, mx: Float, my: Float) {
        val hot = f.contains(mx, my)
        if (hot) ctx.requestCursor(CursorTypes.IBEAM)
        val focused = handler.listening
        UI.rect(ctx, f.x, f.y, f.w, f.h, 5f, if (focused || hot) Palette.inputHover else Palette.input)
        UI.outline(ctx, f.x, f.y, f.w, f.h, 5f, if (focused) Palette.accentBorder else if (hot) Palette.outlineHover else Palette.outline)
        UI.textMid(ctx, "/", f.x + 7f, f.y + f.h / 2f, if (focused) Palette.accentText else Palette.textMuted, TEXT_SIZE, UIFont.Weight.MEDIUM)
        handler.opacity = UI.alpha
        if (text.isEmpty() && !focused) UI.textMid(ctx, placeholder, handler.x, f.y + f.h / 2f, UI.fade(Palette.textMuted, 0.75f), TEXT_SIZE)
        else handler.draw(ctx, mx, my)
    }

    private fun drawFooter(ctx: GuiGraphicsExtractor, mx: Float, my: Float) {
        val f = footerRect
        UI.hLine(ctx, f.x, f.y, f.w, Palette.divider)
        val bh = 22f
        val by = f.y + (f.h - bh) / 2f
        val doneW = UI.width("Done", 7.4f, UIFont.Weight.MEDIUM) + 36f
        doneRect = Rect(f.right - PAD - doneW, by, doneW, bh)
        val addW = UI.width("Add shortcut", 7.4f, UIFont.Weight.MEDIUM) + 36f
        addRect = Rect(doneRect.x - 8f - addW, by, addW, bh)

        val ah = addHover.update(addRect.contains(mx, my))
        val dh = doneHover.update(doneRect.contains(mx, my))
        if (ah > 0.5f || dh > 0.5f) ctx.requestCursor(CursorTypes.POINTING_HAND)
        UI.button(ctx, "Add shortcut", addRect.x, addRect.y, addRect.w, addRect.h, ah, UI.ButtonStyle.SECONDARY, Icon.PLUS, 7.4f)
        UI.button(ctx, "Done", doneRect.x, doneRect.y, doneRect.w, doneRect.h, dh, UI.ButtonStyle.PRIMARY, Icon.CHECK, 7.4f)
        UI.textMid(ctx, UIFont.trim("Saved when you close this window", addRect.x - 12f - (f.x + PAD), 7f), f.x + PAD, f.y + f.h / 2f, Palette.textMuted, 7f)
    }

    // ------------------------------------------------------------------ input

    private fun addRow() {
        rows.forEach { r -> r.inputs.forEach { it.resetState() } }
        val row = Row("", "")
        rows.add(row)
        scroll.set(rows.size * (ROW_H + ROW_GAP))
        row.commandInput.focusEnd()
        Style.playClickSound(1.1f)
    }

    override fun onMouseClicked(mx: Float, my: Float, button: Int): Boolean {
        if (button == InputConstants.MOUSE_BUTTON_LEFT) {
            if (addRect.contains(mx, my)) {
                addRow()
                return true
            }
            if (doneRect.contains(mx, my)) {
                Style.playClickSound(1f)
                onClose()
                return true
            }
            if (scroll.mouseClicked(mx, my)) return true
        }

        val inList = listRect.contains(mx, my)
        if (inList && button == InputConstants.MOUSE_BUTTON_LEFT) {
            rows.forEachIndexed { i, row ->
                if (deleteRect(rowRect(i)).contains(mx, my)) {
                    rows.removeAt(i)
                    Style.playClickSound(0.9f)
                    return true
                }
            }
        }

        // Every field gets the click so the others lose focus; fields outside the list see an impossible position.
        val event = MouseButtonEvent(mx.toDouble(), my.toDouble(), MouseButtonInfo(button, 0))
        var consumed = false
        rows.forEachIndexed { i, row ->
            val r = rowRect(i)
            val visible = inList && r.contains(mx, my)
            val x = if (visible) mx else -10000f
            val y = if (visible) my else -10000f
            // A click on the "/" prefix or field padding focuses the field too.
            if (visible && commandField(r).contains(mx, my) && !row.commandInput.let { mx >= it.x && mx <= it.x + it.width }) {
                row.replacementInput.resetState()
                row.commandInput.focusEnd()
                consumed = true
                return@forEachIndexed
            }
            if (visible && replacementField(r).contains(mx, my) && !row.replacementInput.let { mx >= it.x && mx <= it.x + it.width }) {
                row.commandInput.resetState()
                row.replacementInput.focusEnd()
                consumed = true
                return@forEachIndexed
            }
            if (row.commandInput.mouseClicked(x, y, event)) consumed = true
            if (row.replacementInput.mouseClicked(x, y, event)) consumed = true
        }
        return consumed || inList
    }

    override fun onMouseReleased(button: Int) {
        scroll.mouseReleased()
        rows.forEach { r -> r.inputs.forEach { it.mouseReleased() } }
    }

    override fun onMouseScrolled(mx: Float, my: Float, delta: Double): Boolean {
        scroll.scroll(delta)
        return true
    }

    override fun onKeyPressed(event: KeyEvent): Boolean {
        val all = rows.flatMap { it.inputs }
        val focused = all.indexOfFirst { it.listening }
        if (event.key == InputConstants.KEY_TAB && focused >= 0) {
            all[focused].resetState()
            val next = (focused + if (event.hasShiftDown()) -1 else 1).mod(all.size)
            all[next].focusEnd()
            scroll.reveal((next / 2) * (ROW_H + ROW_GAP), ROW_H)
            return true
        }
        if (event.key == InputConstants.KEY_SLASH || event.key == InputConstants.KEY_BACKSLASH) return focused >= 0
        if (focused >= 0) {
            if (event.key == InputConstants.KEY_ESCAPE || event.key == InputConstants.KEY_RETURN || event.key == InputConstants.KEY_NUMPADENTER) {
                all[focused].resetState()
                return true
            }
            all[focused].keyPressed(event)
            return true
        }
        if (event.hasControlDown() && event.key == InputConstants.KEY_N) {
            addRow()
            return true
        }
        return false
    }

    override fun onCharTyped(event: CharacterEvent): Boolean {
        // Shortcuts are stored without the leading slash.
        if (event.codepoint == '/'.code || event.codepoint == '\\'.code) return true
        rows.forEach { r -> r.inputs.forEach { if (it.keyTyped(event)) return true } }
        return false
    }

    override fun onClosing() {
        rows.forEach { r -> r.inputs.forEach { it.resetState() } }
        val shortcuts = linkedMapOf<String, String>()
        rows.forEach { row ->
            val command = row.command.trim().substringBefore(" ").lowercase()
            val replacement = row.replacement.trim()
            if (command.isNotEmpty() && replacement.isNotEmpty()) shortcuts[command] = replacement
        }
        CommandShortcuts.shortcuts.set(shortcuts)
        CommandShortcuts.rebuild()
    }
}
