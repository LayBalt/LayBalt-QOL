package com.laybalt.GUI.clickgui.util

import com.mojang.blaze3d.platform.cursor.CursorType
import com.mojang.blaze3d.platform.cursor.CursorTypes
import net.minecraft.client.Minecraft
import org.lwjgl.glfw.GLFW

enum class ResizeCorner(val cursor: CursorType) {
    NONE(CursorTypes.ARROW),
    LEFT(CursorTypes.RESIZE_EW),
    RIGHT(CursorTypes.RESIZE_EW),
    TOP(CursorTypes.RESIZE_NS),
    BOTTOM(CursorTypes.RESIZE_NS),
    TOP_LEFT(MouseHelper.NWSE),
    TOP_RIGHT(MouseHelper.NESW),
    BOTTOM_LEFT(MouseHelper.NESW),
    BOTTOM_RIGHT(MouseHelper.NWSE);
}

object MouseHelper {
    val NWSE: CursorType = CursorType.createStandardCursor(GLFW.GLFW_RESIZE_NWSE_CURSOR, "lbqmod:nwse_resize", CursorTypes.RESIZE_ALL)
    val NESW: CursorType = CursorType.createStandardCursor(GLFW.GLFW_RESIZE_NESW_CURSOR, "lbqmod:nesw_resize", CursorTypes.RESIZE_ALL)

    fun setCursor(shape: CursorType) = Minecraft.getInstance().window.selectCursor(shape)

    fun resetCursor() = setCursor(CursorTypes.ARROW)

    fun getResizeCorner(mx: Float, my: Float, x: Float, y: Float, w: Float, h: Float, margin: Float): ResizeCorner {
        if (mx !in x..(x + w) || my !in y..(y + h)) return ResizeCorner.NONE

        val onLeft = mx <= x + margin
        val onRight = mx >= x + w - margin
        val onTop = my <= y + margin
        val onBottom = my >= y + h - margin

        return when {
            onLeft && onTop -> ResizeCorner.TOP_LEFT
            onRight && onTop -> ResizeCorner.TOP_RIGHT
            onLeft && onBottom -> ResizeCorner.BOTTOM_LEFT
            onRight && onBottom -> ResizeCorner.BOTTOM_RIGHT
            onLeft -> ResizeCorner.LEFT
            onRight -> ResizeCorner.RIGHT
            onTop -> ResizeCorner.TOP
            onBottom -> ResizeCorner.BOTTOM
            else -> ResizeCorner.NONE
        }
    }
}
