package com.laybalt.features

import net.minecraft.client.Minecraft

/** The recorder-only jar's `mc` shortcut (LayBalt QOL's has more, which the recorder does not use). */
interface Shortcuts {
    val Shortcuts.mc: Minecraft get() = Minecraft.getInstance()
}
