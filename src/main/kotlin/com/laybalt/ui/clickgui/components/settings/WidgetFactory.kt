package com.laybalt.ui.clickgui.components.settings

import com.laybalt.config.ConfigHolder
import com.laybalt.config.types.*
import com.laybalt.ui.clickgui.components.settings.impl.*

object WidgetFactory {
    fun fromSetting(configHolder: ConfigHolder<*>): Widget<*>? = when (configHolder) {
        is ToggleSetting -> ToggleWidget(configHolder)
        is DropdownSetting -> DropdownWidget(configHolder)
        is MultiCheckboxSetting -> MultiCheckboxWidget(configHolder)
        is ColorSetting -> ColorWidget(configHolder)
        is ColorCodeSetting -> ColorCodeWidget(configHolder)
        is KeybindSetting -> KeybindWidget(configHolder)
        is TextInputSetting -> TextInputWidget(configHolder)
        is SoundSetting -> SoundWidget(configHolder)
        is ButtonSetting -> ButtonWidget(configHolder)
        is SliderSetting -> SliderWidget(configHolder)
        is UnitSetting -> null
        is MapSetting<*, *> -> null
        is ListSetting<*> -> null
        else -> error("Unknown config holder type: ${configHolder::class.qualifiedName}")
    }

    /** Whether [configHolder] is shown as a row in the settings panel. */
    fun hasWidget(configHolder: ConfigHolder<*>) = configHolder !is UnitSetting && configHolder !is MapSetting<*, *> && configHolder !is ListSetting<*>
}
