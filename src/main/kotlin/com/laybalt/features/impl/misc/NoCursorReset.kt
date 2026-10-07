// Ported from Odin (https://github.com/odtheking/Odin), BSD-3-Clause, Copyright (c) 2025 odtheking
package com.laybalt.features.impl.misc

import com.laybalt.config.types.SliderSetting
import com.laybalt.config.types.ToggleSetting
import com.laybalt.event.impl.TickEvent
import com.laybalt.features.Feature
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.gui.screens.inventory.ContainerScreen

/**
 * When Hypixel swaps one menu for another the game briefly grabs the mouse and then releases it again, which puts the
 * cursor back in the middle of the window. Within [unhookTimeout] ms of a menu being open (or closed), the release
 * restores the cursor to where it was before the grab instead (hook: `MixinMouseHandlerNoCursorReset`).
 */
object NoCursorReset: Feature("Prevents the cursor from being reset to the middle of the screen when a menu opens right after another.") {
    private val unhookTimeout by SliderSetting("Unhook Timeout", 150, 0, 1000, 10, "ms")
        .withDescription("For how many milliseconds after a menu was open (or closed) the cursor position is kept when the next menu opens")
    private val allInventories by ToggleSetting("All Inventories")
        .withDescription("Also keeps the cursor for every inventory screen (furnaces, anvils, your own inventory...), not only chest menus like Odin")

    private var clock = System.currentTimeMillis()
    private var wasNotNull = false

    override fun init() {
        register<TickEvent.End> {
            if (mc.gui.screen() != null) {
                wasNotNull = true
                clock = System.currentTimeMillis()
            }
            else if (wasNotNull) {
                wasNotNull = false
                clock = System.currentTimeMillis()
            }
        }
    }

    /** Called from the mouse release: true when the cursor should go back to its pre-grab position. */
    @JvmStatic
    fun shouldRestoreCursor(): Boolean {
        if (! enabled) return false
        val screen = mc.gui.screen()
        val validScreen = if (allInventories.value) screen is AbstractContainerScreen<*> else screen is ContainerScreen
        return validScreen && System.currentTimeMillis() - clock < unhookTimeout.value
    }
}
