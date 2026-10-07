package com.laybalt.ui.clickgui.components.settings

import com.laybalt.features.impl.dev.ClickGui
import com.laybalt.utils.ColorUtils.withAlpha
import gg.essential.universal.USound
import net.minecraft.sounds.SoundEvents

/** Live accent colour (ClickGui "Accent Color" setting) and the GUI click sound. Colours live in [com.laybalt.ui.Palette]. */
object Style {
    val accentColor get() = ClickGui.accentColor.value
    val accentColorTrans get() = accentColor.withAlpha(120)

    fun playClickSound(pitch: Float) = USound.playSoundStatic(SoundEvents.UI_BUTTON_CLICK, 0.25f, pitch)
}
