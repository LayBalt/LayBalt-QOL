package com.laybalt.ui.clickgui.enums

/**
 * Click GUI categories. A feature's category is the package segment after `features.impl.`
 * (e.g. `com.laybalt.features.impl.kuudra.Foo` -> [KUUDRA]); entry order is the panel order.
 */
enum class CategoryType(val displayName: String) {
    GENERAL("General"),
    DUNGEON("Dungeon"),
    FLOOR7("Floor 7"),
    VISUAL("Visual"),
    RENDER("Render"),
    MISC("Misc"),
    FISHING("Fishing"),
    RIFT("Rift"),
    KUUDRA("Kuudra"),
    DEV("Client")
}
