package com.laybalt.features.impl.dungeon

import com.laybalt.utils.ColorUtils.lerp
import com.laybalt.utils.MathUtils
import com.laybalt.utils.dungeons.DungeonListener
import com.laybalt.utils.dungeons.DungeonPlayer
import com.laybalt.utils.dungeons.F7Listener
import net.minecraft.client.gui.GuiGraphicsExtractor
import java.awt.Color

/** One layout of the custom leap menu (NoammAddons 4d3cbf6e split them into their own renderers). */
abstract class LeapMenuRenderer {
    private val boxBg = Color(33, 33, 33)
    private val boxBgHover = Color(67, 67, 67)
    private val doorOpenerBg = Color(135, 135, 135)
    private val doorOpenerBgHover = Color.WHITE

    // NoammAddons 81339d4f: teammates inside the F7/M7 core once it has opened
    private val inCoreColor = Color(0, 120, 0)
    private val inCoreBox = MathUtils.aabb(68, 106, 54, 42, 155, 119)

    abstract fun render(ctx: GuiGraphicsExtractor, mouseX: Int, mouseY: Int)

    /** Index into [LeapMenu.players] under the mouse, or null. */
    abstract fun getHoveredIndex(): Int?

    fun leapBoxColor(player: DungeonPlayer, isHovered: Boolean) = when {
        LeapMenu.showLastDoorOpener.value && DungeonListener.lastDoorOpener == player -> if (isHovered) doorOpenerBgHover else doorOpenerBg
        LeapMenu.tintDeadPlayers.value && player.isDead -> boxBg.lerp(Color.RED, 0.2f).let { if (isHovered) it.brighter() else it }
        LeapMenu.highlightInCore.value && F7Listener.coreOpen && player.entity?.let { inCoreBox.contains(it.x, it.y, it.z) } == true -> inCoreColor
        else -> if (isHovered) boxBgHover else boxBg
    }
}
