package com.laybalt.GUI.clickgui.widget

import com.laybalt.GUI.clickgui.Style
import com.laybalt.GUI.clickgui.setting.ButtonSetting
import com.laybalt.GUI.clickgui.setting.KeybindSetting
import com.laybalt.GUI.clickgui.setting.LabelSetting
import com.laybalt.GUI.clickgui.util.Animation
import com.laybalt.GUI.clickgui.util.Render2D.drawCenteredString
import com.laybalt.GUI.clickgui.util.Render2D.drawRect
import com.laybalt.GUI.clickgui.util.Render2D.drawString
import com.laybalt.GUI.clickgui.util.Render2D.width
import com.laybalt.GUI.clickgui.util.Render2D.withAlpha
import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.gui.GuiGraphicsExtractor
import java.awt.Color

/** Click, then press a key or mouse button. Backspace unbinds, Escape cancels. */
class KeybindWidget(config: KeybindSetting): Widget<Unit>(config) {
    private val cfg = config
    private val hoverAnim = Animation(200)
    private var listening = false

    override fun draw(ctx: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        hoverAnim.update(if (isHovered(mouseX, mouseY) || listening) 1f else 0f)

        Style.drawBackground(ctx, x, y, width, height)
        Style.drawHoverBar(ctx, x.toFloat(), y.toFloat(), height.toFloat(), hoverAnim.value)
        Style.drawNudgedText(ctx, name, x + 8f, y + 6f, hoverAnim.value)

        val bindText = if (listening) "..." else "§7${cfg.displayName()}"
        ctx.drawString(bindText, x + width - bindText.width() - 8f, y + 6f, if (listening) Style.accentColor else Color.WHITE)
    }

    override fun clearFocus() {
        listening = false
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (listening) {
            cfg.bindMouse(button)
            listening = false
            Style.playClickSound(1f)
            return true
        }
        if (!isHovered(mouseX, mouseY)) return false
        listening = true
        Style.playClickSound(1f)
        return true
    }

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        if (!listening) return false
        when (keyCode) {
            InputConstants.KEY_ESCAPE -> {}
            InputConstants.KEY_BACKSPACE -> cfg.unbind()
            else -> cfg.bindKey(keyCode)
        }
        listening = false
        return true
    }
}

class ButtonWidget(config: ButtonSetting): Widget<Unit>(config) {
    private val cfg = config
    private val hoverAnim = Animation(200)

    override fun draw(ctx: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        hoverAnim.update(if (isHovered(mouseX, mouseY)) 1f else 0f)
        val anim = hoverAnim.value

        Style.drawBackground(ctx, x, y, width, height)
        if (anim > 0.01f) ctx.drawRect(x, y, width, height, Color(255, 255, 255, (10 * anim).toInt()))
        ctx.drawCenteredString(name, x + width / 2f, y + 6f)

        if (anim > 0.01f) {
            val lineWidth = (name.width() + 10f) * anim
            ctx.drawRect(x + width / 2f - lineWidth / 2f, y + 15.5f, lineWidth, 1f, Style.accentColor.withAlpha((200 * anim).toInt()))
        }
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (button != InputConstants.MOUSE_BUTTON_LEFT || !isHovered(mouseX, mouseY)) return false
        Style.playClickSound(1f)
        cfg.action()
        return true
    }
}

/** Section title, preceded by a thin separator line when it is not the first row. */
class SectionWidget(title: String, private val withSeparator: Boolean = false): Widget<Unit>(LabelSetting(title)) {
    override val height get() = if (withSeparator) 32 else 22

    override fun draw(ctx: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        var top = y.toFloat()
        if (withSeparator) {
            ctx.drawRect(x + 10f, top + 4.5f, width - 20f, 0.5f, Color(255, 255, 255, 30))
            top += 10f
        }
        ctx.drawRect(x, top, width, 22, Color(255, 255, 255, 10))
        ctx.drawCenteredString("§l$name", x + width / 2f, top + 7f, Style.accentColor)
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int) = false
}
