package com.laybalt.GUI.clickgui

import com.laybalt.GUI.LBQConfig
import com.laybalt.GUI.clickgui.util.Render2D.drawRect
import com.laybalt.GUI.clickgui.util.Render2D.drawString
import com.laybalt.GUI.clickgui.util.Render2D.withAlpha
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.sounds.SoundEvents
import java.awt.Color

object Style {
    val accentColor: Color get() = LBQConfig.ClickGuiAccentColor
    val accentColorTrans get() = accentColor.withAlpha(120)
    val settingBackgroundColor = Color(10, 10, 10, 100)
    private val sliderBackgroundColor = Color(40, 40, 40, 200)

    fun drawBackground(ctx: GuiGraphicsExtractor, x: Number, y: Number, w: Number, h: Number) {
        ctx.drawRect(x, y, w, h, settingBackgroundColor)
    }

    /** Accent bar on the left edge that grows with the hover animation. */
    fun drawHoverBar(ctx: GuiGraphicsExtractor, x: Float, y: Float, height: Float, anim: Float) {
        if (anim <= 0.01f) return
        val barH = (height - 6f) * anim
        ctx.drawRect(x, y + (height / 2f) - (barH / 2f), 1.5f, barH, accentColor.withAlpha((200 * anim).toInt()))
    }

    /** Label that slides 2px to the right while hovered. */
    fun drawNudgedText(ctx: GuiGraphicsExtractor, text: String, x: Float, y: Float, anim: Float, color: Color = Color.WHITE) {
        ctx.drawString(text, x + 2f * anim, y, color)
    }

    fun drawSlider(ctx: GuiGraphicsExtractor, x: Float, y: Float, w: Float, progress: Float, color: Color) {
        val knob = 4f
        val h = 2.5f
        ctx.drawRect(x, y, w, h, sliderBackgroundColor)
        ctx.drawRect(x, y, w * progress, h, color)
        ctx.drawRect(x + (w * progress) - (knob / 2f), y + (h / 2f) - (knob / 2f), knob, knob)
    }

    fun playClickSound(pitch: Float) {
        Minecraft.getInstance().soundManager.play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), pitch, 0.25f))
    }
}
