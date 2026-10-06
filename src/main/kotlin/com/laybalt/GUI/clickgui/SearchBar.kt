package com.laybalt.GUI.clickgui

import com.laybalt.GUI.clickgui.util.Render2D.drawCenteredString
import com.laybalt.GUI.clickgui.util.Render2D.drawRect
import com.laybalt.GUI.clickgui.util.Render2D.drawString
import com.laybalt.GUI.clickgui.util.Render2D.scissor
import com.laybalt.GUI.clickgui.util.Render2D.width
import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import org.lwjgl.glfw.GLFW
import java.awt.Color

/** Single-line search field. Ctrl+F focuses it, Escape or a click elsewhere unfocuses it. */
class SearchBar {
    var text = ""
        private set

    var x = 0f
    var y = 0f
    var width = 150f
    var height = 22f

    var focused = false
        set(v) {
            if (field == v) return
            field = v
            Minecraft.getInstance().textInputManager().onTextInputFocusChange(v)
        }

    fun draw(ctx: GuiGraphicsExtractor) {
        ctx.drawRect(x, y, width, height, Color(15, 15, 15, 200))
        ctx.drawRect(x, y + height - 2f, width, 2f, if (focused) Style.accentColor else Color(255, 255, 255, 30))

        if (text.isEmpty() && !focused) {
            ctx.drawCenteredString("§8Search...", x + width / 2f, y + 7f, shadow = false)
            return
        }

        val cursor = if (focused && (System.currentTimeMillis() / 500) % 2 == 0L) "§7|" else ""
        val shown = text + cursor
        // Keep the end of long queries visible.
        val overflow = (shown.width() - (width - 12f)).coerceAtLeast(0f)
        ctx.scissor(x + 4f, y, width - 8f, height)
        ctx.drawString(shown, x + 6f - overflow, y + 7f)
        ctx.disableScissor()
    }

    fun contains(mx: Float, my: Float) = mx in x..(x + width) && my in y..(y + height)

    fun mouseClicked(mx: Float, my: Float, button: Int): Boolean {
        if (!contains(mx, my)) {
            focused = false
            return false
        }
        if (button == InputConstants.MOUSE_BUTTON_RIGHT) text = ""
        focused = true
        return true
    }

    fun keyPressed(keyCode: Int, modifiers: Int): Boolean {
        if (!focused) return false
        val ctrl = (modifiers and GLFW.GLFW_MOD_CONTROL) != 0
        when (keyCode) {
            InputConstants.KEY_ESCAPE, InputConstants.KEY_RETURN, InputConstants.KEY_NUMPADENTER -> focused = false
            InputConstants.KEY_BACKSPACE -> text = if (ctrl) text.trimEnd().substringBeforeLast(' ', "") else text.dropLast(1)
            else -> if (ctrl && keyCode == InputConstants.KEY_A) text = ""
        }
        return true
    }

    fun charTyped(codePoint: Char): Boolean {
        if (!focused) return false
        if (codePoint >= ' ' && text.length < 32) text += codePoint
        return true
    }
}
