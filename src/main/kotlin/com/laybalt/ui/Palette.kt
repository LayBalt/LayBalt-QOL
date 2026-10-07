package com.laybalt.ui

import com.laybalt.ui.clickgui.components.settings.Style
import com.laybalt.utils.ColorUtils.withAlpha
import java.awt.Color

/**
 * LayBalt QOL UI palette (dark purple-grey surfaces, soft violet accent, lavender text).
 * Every UI colour should come from here so a theme change is a one-file edit.
 */
object Palette {
    /** Default accent; the live accent is [Style.accentColor] (ClickGui "Accent Color" setting). */
    val defaultAccent = Color(158, 119, 237)
    /** Accent colour old configs saved as the default; migrated to [defaultAccent] on load. */
    val legacyAccent = Color(99, 176, 217)

    val accent get() = Style.accentColor
    val accentGlow get() = accent.withAlpha(60)

    val textPrimary = Color(236, 230, 248)
    val textSecondary = Color(170, 160, 196)
    val textMuted = Color(120, 110, 145)

    val danger = Color(190, 70, 100, 220)
    val backdropTop = Color(10, 8, 16, 120)
    val backdropBottom = Color(18, 12, 30, 185)

    /** In-game HUD element background (HUD editor previews). */
    val bgDeep = Color(16, 13, 24, 235)
    /** HUD editor grid lines. */
    val grid = Color(170, 150, 220, 20)

    // ---- LayBalt window (rounded, Inter) ----

    /** Full-screen dim behind the main window. */
    val scrim = Color(6, 4, 12, 150)
    /** Main window body. */
    val surface = Color(19, 16, 28, 246)
    /** Sidebar, top bar and settings panel. */
    val surfaceRaised = Color(25, 21, 37, 252)
    /** Feature cards and setting rows. */
    val card = Color(31, 26, 46, 255)
    val cardHover = Color(38, 32, 56, 255)
    /** Inputs: search field, text fields, slider track, dropdown body. */
    val input = Color(13, 11, 20, 235)
    val inputHover = Color(18, 15, 27, 240)
    /** Popovers: dropdown list, colour picker, tooltips. */
    val popover = Color(22, 18, 33, 252)

    /** Hairline borders on dark surfaces (1px). */
    val outline = Color(150, 130, 205, 34)
    val outlineHover = Color(170, 150, 225, 70)
    val divider = Color(150, 130, 205, 22)

    /** Drop shadows under the window, cards and popovers. */
    val shadowSoft = Color(0, 0, 0, 90)
    val shadowStrong = Color(0, 0, 0, 150)

    /** Switch/toggle parts. */
    val switchOff = Color(58, 50, 80, 255)
    val switchKnob = Color(244, 240, 252)
    val switchKnobDisabled = Color(130, 122, 150)

    /** Chips ("3/12", "WIP", category labels on search results). */
    val chip = Color(170, 150, 220, 26)
    val chipText = Color(190, 178, 220)

    val success = Color(118, 206, 150)
    val warning = Color(232, 184, 96)

    /** Selected-row pill and accent-tinted fills. */
    val accentPill get() = accent.withAlpha(44)
    val accentHover get() = accent.withAlpha(24)
    /** Accent-coloured borders on enabled cards. */
    val accentBorder get() = accent.withAlpha(150)
    /** Accent lightened toward white, for text on dark surfaces (wordmark, links). */
    val accentText: Color get() {
        val a = accent
        return Color(a.red + (255 - a.red) * 3 / 10, a.green + (255 - a.green) * 3 / 10, a.blue + (255 - a.blue) * 3 / 10)
    }

    // ---- Legacy flat tokens of the removed NA-style click GUI, mapped onto the new ones ----

    @Deprecated("Flat-GUI token", ReplaceWith("Palette.surfaceRaised")) val bgPanel get() = surfaceRaised
    @Deprecated("Flat-GUI token", ReplaceWith("Palette.surface")) val windowBody get() = surface
    @Deprecated("Flat-GUI token", ReplaceWith("Palette.input")) val bgInset get() = input
    @Deprecated("Flat-GUI token", ReplaceWith("Palette.card")) val bgSetting get() = card
    @Deprecated("Flat-GUI token", ReplaceWith("Palette.switchOff")) val bgSlider get() = switchOff
    @Deprecated("Flat-GUI token", ReplaceWith("Palette.outline")) val border get() = outline
    @Deprecated("Flat-GUI token", ReplaceWith("Palette.outlineHover")) val borderFocused get() = outlineHover
    @Deprecated("Flat-GUI token", ReplaceWith("Palette.chip")) val hover get() = chip
    @Deprecated("Flat-GUI token", ReplaceWith("Palette.divider")) val subtle get() = divider
    @Deprecated("Flat-GUI token", ReplaceWith("Palette.divider")) val band get() = divider
    @Deprecated("Flat-GUI token", ReplaceWith("Palette.shadowSoft")) val shadow get() = shadowSoft
    @Deprecated("Flat-GUI token", ReplaceWith("Palette.popover")) val tooltipBg get() = popover
    @Deprecated("Flat-GUI token", ReplaceWith("Palette.switchOff")) val toggleOff get() = switchOff
    @Deprecated("Flat-GUI token", ReplaceWith("Palette.switchKnobDisabled")) val knobOff get() = switchKnobDisabled
    @Deprecated("Flat-GUI token", ReplaceWith("Palette.accentBorder")) val accentSoft get() = accentBorder
    @Deprecated("Flat-GUI token", ReplaceWith("Palette.textMuted")) val wip get() = textMuted
}
