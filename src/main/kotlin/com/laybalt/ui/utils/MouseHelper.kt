package com.laybalt.ui.utils

import com.laybalt.LayBaltQOL
import com.mojang.blaze3d.platform.cursor.CursorType
import com.mojang.blaze3d.platform.cursor.CursorTypes

object MouseHelper {
    fun setCursor(shape: CursorType) = LayBaltQOL.mc.window.selectCursor(shape)

    fun resetCursor() = setCursor(CursorTypes.ARROW)
}
