package com.laybalt.ui.utils

import com.mojang.blaze3d.platform.cursor.CursorType
import com.mojang.blaze3d.platform.cursor.CursorTypes
import org.lwjgl.glfw.GLFW

enum class ResizeCorner(val cursor: CursorType) {
    NONE(CursorTypes.ARROW),
    LEFT(CursorTypes.RESIZE_EW),
    RIGHT(CursorTypes.RESIZE_EW),
    TOP(CursorTypes.RESIZE_NS),
    BOTTOM(CursorTypes.RESIZE_NS),
    TOP_LEFT(CursorType.createStandardCursor(GLFW.GLFW_RESIZE_NWSE_CURSOR, "lbqmod:nwse_resize", CursorTypes.RESIZE_ALL)),
    TOP_RIGHT(CursorType.createStandardCursor(GLFW.GLFW_RESIZE_NESW_CURSOR, "lbqmod:nesw_resize", CursorTypes.RESIZE_ALL)),
    BOTTOM_LEFT(CursorType.createStandardCursor(GLFW.GLFW_RESIZE_NESW_CURSOR, "lbqmod:nesw_resize", CursorTypes.RESIZE_ALL)),
    BOTTOM_RIGHT(CursorType.createStandardCursor(GLFW.GLFW_RESIZE_NWSE_CURSOR, "lbqmod:nwse_resize", CursorTypes.RESIZE_ALL));
}