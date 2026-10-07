package com.laybalt.ui

import com.laybalt.ui.clickgui.components.settings.Style
import com.laybalt.utils.ColorUtils.withAlpha
import java.awt.Color

/**
 * LayBalt QOL UI palette (dark purple-grey surfaces, soft violet accent, lavender text).
 * Every UI colour should come from here so a theme change is a one-file edit.
 */
object Palette {
    val bgDeep = Color(16, 13, 24, 235)
    /** Feature settings window body: near-opaque so panel text behind it does not read through. */
    val windowBody = Color(16, 13, 24, 252)
    val bgPanel = Color(24, 20, 36, 240)
    val bgInset = Color(11, 9, 17, 200)
    val bgSetting = Color(36, 30, 52, 110)
    val bgSlider = Color(58, 50, 80, 200)

    val border = Color(140, 120, 190, 40)
    val borderFocused = Color(160, 140, 215, 70)
    val hover = Color(170, 150, 220, 28)
    val subtle = Color(170, 150, 220, 18)
    val band = Color(170, 150, 220, 14)
    val grid = Color(170, 150, 220, 20)
    val shadow = Color(0, 0, 0, 90)
    val tooltipBg = Color(14, 11, 22, 245)

    val toggleOff = Color(58, 50, 80, 140)
    val knobOff = Color(150, 140, 170)

    /** Default accent; the live accent is [Style.accentColor] (ClickGui "Accent Color" setting). */
    val defaultAccent = Color(158, 119, 237)
    /** Accent colour old configs saved as the default; migrated to [defaultAccent] on load. */
    val legacyAccent = Color(99, 176, 217)

    val accent get() = Style.accentColor
    val accentSoft get() = accent.withAlpha(120)
    val accentGlow get() = accent.withAlpha(60)

    val textPrimary = Color(236, 230, 248)
    val textSecondary = Color(170, 160, 196)
    val textMuted = Color(120, 110, 145)

    val danger = Color(190, 70, 100, 220)
    val backdropTop = Color(10, 8, 16, 120)
    val backdropBottom = Color(18, 12, 30, 185)

    val wip = Color(96, 88, 112)
}
