package com.laybalt.ui.utils.componnents

import com.laybalt.ui.Palette
import com.laybalt.ui.clickgui.components.settings.Style
import com.laybalt.utils.ColorUtils.withAlpha
import com.laybalt.utils.ChatUtils.addColor
import com.laybalt.utils.render.Render2D.drawRect
import gg.essential.universal.UMinecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.sounds.SoundManager
import net.minecraft.network.chat.Component
import java.awt.Color

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
        val bgColor = if (isHovered) Palette.bgSetting.withAlpha(220) else Palette.bgPanel
        graphics.drawRect(x, y, width, height, bgColor)

        val stateColor = colorProvider?.invoke() ?: overrideColor
        val borderColor = stateColor ?: Palette.border
        val textColor = if (isHovered) Style.accentColor else stateColor ?: Palette.textPrimary

        graphics.drawRect(x, y, width, 1, borderColor)
        graphics.drawRect(x, y + height - 1, width, 1, borderColor)
        graphics.drawRect(x, y, 1, height, borderColor)
        graphics.drawRect(x + width - 1, y, 1, height, borderColor)

        graphics.centeredText(
            UMinecraft.getFontRenderer(),
            message,
            x + width / 2,
            y + (height - 8) / 2,
            textColor.rgb
        )
    }

    override fun playDownSound(soundManager: SoundManager) = Style.playClickSound(1f)
}

