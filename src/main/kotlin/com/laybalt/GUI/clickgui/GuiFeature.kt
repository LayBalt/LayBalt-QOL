package com.laybalt.GUI.clickgui

import com.laybalt.GUI.clickgui.setting.Setting

enum class Category(val displayName: String) {
    FISHING("Fishing"),
    QOL("QOL"),
    COMBAT("Combat"),
    SLAYER("Slayer"),
    LAYBALT("LayBalt")
}

/**
 * One row in a category panel. Left click toggles it (when [toggleable]), right click opens its settings.
 * The enabled state is read from and written to the matching LBQConfig switch.
 */
class GuiFeature(
    val name: String,
    val description: String?,
    val category: Category,
    private val enabledGetter: (() -> Boolean)?,
    private val enabledSetter: ((Boolean) -> Unit)?,
    val settings: List<Setting<*>>
) {
    val toggleable get() = enabledGetter != null && enabledSetter != null
    val enabled get() = enabledGetter?.invoke() ?: false

    fun toggle() {
        val setter = enabledSetter ?: return
        setter(!enabled)
    }
}
