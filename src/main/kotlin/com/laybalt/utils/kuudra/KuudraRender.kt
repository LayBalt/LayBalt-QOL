// Ported from Odin (https://github.com/odtheking/Odin), BSD-3-Clause, Copyright (c) 2025 odtheking (parts of this file)
package com.laybalt.utils.kuudra

import com.laybalt.LayBaltQOL.mc
import com.laybalt.utils.ColorUtils.withAlpha
import com.laybalt.utils.MathUtils.center
import com.laybalt.utils.render.world.Render3D.renderBoxBounds
import com.laybalt.utils.render.world.Render3D.renderString
import com.laybalt.utils.render.world.RenderContext
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.AABB
import java.awt.Color
import kotlin.math.max

/** Waypoint helpers shared by the Kuudra features (NA has no beacon-beam renderer, so the beam is a thin translucent column). */
object KuudraRender {
    /**
     * Beacon-style waypoint: an outline of the block (visible through walls), a light beam going up from it and a
     * [title] above it. [distance] appends the distance in blocks; [increase] scales the title up with distance.
     */
    fun RenderContext.renderBeacon(title: String, pos: BlockPos, color: Color, increase: Boolean = true, distance: Boolean = true) {
        val dist = mc.player?.blockPosition()?.distManhattan(pos) ?: return

        renderBoxBounds(AABB(pos), color, fill = false, phase = true)

        val beamHalf = 0.125
        renderBoxBounds(
            pos.x + 0.5 - beamHalf, pos.y + 1.0, pos.z + 0.5 - beamHalf,
            pos.x + 0.5 + beamHalf, pos.y + 256.0, pos.z + 0.5 + beamHalf,
            color, color.withAlpha(90), outline = false, fill = true
        )

        renderString(
            if (distance) "$title §f(§3${dist}m§f)" else title,
            pos.center.add(0.0, 1.7, 0.0),
            scale = if (increase) max(1f, dist * 0.05f) else 2f,
            phase = true
        )
    }
}
