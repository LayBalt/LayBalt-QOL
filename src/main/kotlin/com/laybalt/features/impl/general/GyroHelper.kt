// Cooldown colour, depth check and mage cooldown reduction ported from Odin GyroWand
// (https://github.com/odtheking/Odin), BSD-3-Clause, Copyright (c) 2025 odtheking
package com.laybalt.features.impl.general

import com.laybalt.config.types.ColorSetting
import com.laybalt.config.types.SliderSetting
import com.laybalt.config.types.ToggleSetting
import com.laybalt.event.impl.ActionBarMessageEvent
import com.laybalt.event.impl.ParticleEvent
import com.laybalt.event.impl.RenderWorldEvent
import com.laybalt.event.impl.WorldChangeEvent
import com.laybalt.features.Feature
import com.laybalt.utils.ColorUtils.withAlpha
import com.laybalt.utils.MathUtils.add
import com.laybalt.utils.MathUtils.toVec
import com.laybalt.utils.Utils
import com.laybalt.utils.WorldUtils
import com.laybalt.utils.dungeons.DungeonListener
import com.laybalt.utils.dungeons.enums.DungeonClass
import com.laybalt.utils.items.EtherwarpHelper
import com.laybalt.utils.items.ItemUtils.skyblockId
import com.laybalt.utils.location.LocationUtils
import com.laybalt.utils.render.world.Render3D.renderBox
import com.laybalt.utils.render.world.Render3D.renderCircle
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.tags.BlockTags
import net.minecraft.world.phys.shapes.CollisionContext
import java.awt.Color
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToLong

object GyroHelper: Feature("Renders a circle where your gyro will be located.", "Gyro Helper") {
    private val drawBox by ToggleSetting("Draw Box", true).withDescription("Draws a box in the middle of the Gyrokinetic Wand sucking range").section("Render")
    private val drawRing by ToggleSetting("Draw Sucking Range", true).withDescription("Draws the sucking range of the Gyrokinetic Wand")
    private val lineWidth by SliderSetting("Ring Width", 2, 1, 10, 1).withDescription("Controls the thickness of the ring")
    private val depthCheck by ToggleSetting("Depth Check", true).withDescription("When on, the box and ring are hidden behind blocks. Turn off to see them through walls")

    private val boxColor by ColorSetting("Box Color", Utils.favoriteColor.withAlpha(0.3f)).section("Color")
    private val ringColor by ColorSetting("Ring Color", Utils.favoriteColor)

    private val showCooldown by ToggleSetting("Show Cooldown", true).withDescription("Recolours the box and ring while Gravity Storm is on cooldown (30s, reduced by the Mage cooldown reduction in dungeons)").section("Cooldown")
    private val cooldownColor by ColorSetting("Cooldown Color", Color(255, 85, 85, 128)).withDescription("Colour of the box and ring while the wand is on cooldown").showIf { showCooldown.value }

    private val gravityStormRegex = Regex("-\\d[\\d,]* Mana \\(Gravity Storm\\)")
    private const val BASE_COOLDOWN = 30_000L
    private var lastUse = 0L

    // Odin b06b9d2a: Gravity Storm spawns a burst of motionless WITCH particles at one height
    private data class Burst(val start: Long = 0L, val y: Double = 0.0, val count: Int = 0)
    private var burst = Burst()

    override fun init() {
        // Hypixel no longer sends this action bar message; kept as a harmless fallback.
        register<ActionBarMessageEvent> {
            if (gravityStormRegex.containsMatchIn(event.unformattedText)) lastUse = System.currentTimeMillis()
        }

        register<ParticleEvent> {
            if (event.particle != ParticleTypes.WITCH || event.xd != 0.0 || event.yd != 0.0 || event.zd != 0.0) return@register
            val now = System.currentTimeMillis()
            if (now - lastUse < abilityCooldown(BASE_COOLDOWN)) return@register

            burst = if (now - burst.start > 120L || abs(event.y - burst.y) > 0.02) Burst(now, event.y, 1)
            else burst.copy(count = burst.count + 1)

            if (burst.count >= 5) {
                lastUse = now
                burst = Burst()
            }
        }

        register<WorldChangeEvent> {
            lastUse = 0L
            burst = Burst()
        }

        register<RenderWorldEvent> {
            if (! drawRing.value && ! drawBox.value) return@register
            if (player.mainHandItem.skyblockId != "GYROKINETIC_WAND") return@register
            val onCooldown = showCooldown.value && System.currentTimeMillis() - lastUse < abilityCooldown(BASE_COOLDOWN)
            val box = if (onCooldown) cooldownColor.value.withAlpha(boxColor.value.alpha) else boxColor.value
            val ring = if (onCooldown) cooldownColor.value else ringColor.value
            if (box.alpha + ring.alpha == 0) return@register

            // NoammAddons 029687b6: Hypixel aims the gyro like a slightly changed Etherwarp, not like the vanilla block pick
            val ray = EtherwarpHelper.getEtherPos(player.position(), player.lookAngle, 25.0)
            val gyroPos = ray.pos?.immutable() ?: return@register
            val gyroPosAbove = gyroPos.above()

            val stateAbove = WorldUtils.getStateAt(gyroPosAbove)
            val empty = stateAbove.getCollisionShape(level, gyroPosAbove, CollisionContext.empty()).isEmpty

            if (! ray.succeeded && ! empty || (! empty && ! stateAbove.`is`(BlockTags.WOOL_CARPETS))) return@register

            val phase = ! depthCheck.value
            if (drawBox.value) event.ctx.renderBox(gyroPos.x + 0.5, gyroPos.y, gyroPos.z + 0.5, 1, 1, box, phase = phase)
            if (drawRing.value) {
                val center = gyroPos.add(0.5, 2.05, 0.5).toVec()
                event.ctx.renderCircle(center, 10.0, ring, lineWidth.value, phase)
            }
        }
    }

    /** Odin DungeonUtils.getAbilityCooldown: Mage cooldown reduction is 25% + floor(level / 2)%, doubled when you are the only Mage. */
    private fun abilityCooldown(base: Long): Long {
        if (! LocationUtils.inDungeon) return base
        val self = DungeonListener.thePlayer ?: return base
        if (self.clazz != DungeonClass.Mage) return base
        val soleMage = DungeonListener.dungeonTeammates.count { it.clazz == DungeonClass.Mage } == 1
        val multiplier = 1 - 0.25 - (floor(self.clazzLvl / 2.0) / 100) * if (soleMage) 2 else 1
        return (base * multiplier).roundToLong()
    }
}
