package com.laybalt.ui.utils.componnents

import com.laybalt.ui.Palette
import com.laybalt.ui.clickgui.UI
import com.laybalt.ui.clickgui.components.settings.Style
import com.laybalt.ui.text.UIFont
import com.laybalt.utils.ChatUtils.addColor
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.sounds.SoundManager
import net.minecraft.network.chat.Component
import java.awt.Color

/**
 * Vanilla [Button] drawn in the LayBalt style (rounded card, hairline border, Inter label) for screens that use
 * Minecraft widgets. [colorProvider] / [overrideColor] tint the border and label (state buttons such as "Mode: X").
 */
class UIButton(
    x: Int,
    y: Int,
    width: Int,
    height: Int,
    text: String,
    private val colorProvider: (() -> Color)? = null,
    private val action: (UIButton) -> Unit
): Button(x, y, width, height, Component.literal(text.addColor()), { btn -> action(btn as UIButton) }, DEFAULT_NARRATION) {

    var overrideColor: Color? = null

    override fun extractContents(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        val savedAlpha = UI.alpha
        UI.alpha = if (active) 1f else 0.5f
        val hot = isHovered && active
        val stateColor = colorProvider?.invoke() ?: overrideColor
        val radius = (height / 2f).coerceAtMost(6f)
        val fx = x.toFloat()
        val fy = y.toFloat()
        val fw = width.toFloat()
        val fh = height.toFloat()

        if (hot) UI.shadow(graphics, fx, fy, fw, fh, radius, 6f, Palette.shadowSoft, offsetY = 1.5f)
        UI.rect(graphics, fx, fy, fw, fh, radius, if (hot) Palette.cardHover else Palette.card)
        val border = when {
            stateColor != null -> UI.fade(stateColor, if (hot) 1f else 0.7f)
            isFocused || hot -> Palette.accentBorder
            else -> Palette.outlineHover
        }
        UI.outline(graphics, fx, fy, fw, fh, radius, border)

        val label = message.string
        val size = (fh * 0.42f).coerceIn(6.5f, 8f)
        val color = when {
            hot -> Palette.accentText
            stateColor != null -> stateColor
            else -> Palette.textPrimary
        }
        val text = UIFont.trim(label, fw - 10f, size, UIFont.Weight.MEDIUM)
        UI.textMidCentered(graphics, text, fx + fw / 2f, fy + fh / 2f, color, size, UIFont.Weight.MEDIUM)
        UI.alpha = savedAlpha
    }

    override fun playDownSound(soundManager: SoundManager) = Style.playClickSound(1f)
}
