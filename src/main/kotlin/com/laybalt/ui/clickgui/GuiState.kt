package com.laybalt.ui.clickgui

import com.laybalt.config.PogObject
import com.laybalt.ui.clickgui.enums.CategoryType

/** Remembers the last opened category and the scroll position of every category between openings of the screen. */
object GuiState {
    class Data {
        var category: String? = CategoryType.GENERAL.name
        var scroll: MutableMap<String, Float>? = mutableMapOf()
    }

    private val store = PogObject("clickgui_state", Data())

    var category: CategoryType
        get() = CategoryType.entries.firstOrNull { it.name == store.get().category } ?: CategoryType.GENERAL
        set(value) {
            store.get().category = value.name
        }

    fun scroll(category: CategoryType) = store.get().scroll?.get(category.name) ?: 0f

    fun setScroll(category: CategoryType, value: Float) {
        val data = store.get()
        val map = data.scroll ?: mutableMapOf<String, Float>().also { data.scroll = it }
        map[category.name] = value
    }

    fun save() = store.save()

    /** Back to the first category with every scroll position at the top (/lbq reset gui). */
    fun reset() {
        store.get().category = CategoryType.GENERAL.name
        store.get().scroll = mutableMapOf()
        save()
    }
}
