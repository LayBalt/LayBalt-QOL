package com.laybalt.features.impl.render

import com.laybalt.config.types.ColorSetting
import com.laybalt.config.types.DropdownSetting
import com.laybalt.config.types.SliderSetting
import com.laybalt.config.types.ToggleSetting
import com.laybalt.event.impl.RenderWorldEvent
import com.laybalt.event.impl.WorldChangeEvent
import com.laybalt.features.Feature
import com.laybalt.features.impl.render.WorldBoxKit.drawBox
import com.laybalt.mixin.IMultiPlayerGameModeBreakHighlight
import com.laybalt.utils.ColorUtils.withAlpha
import com.laybalt.utils.render.world.Render3D.renderString
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.AABB
import java.awt.Color

/**
 * Draws a box on the block being mined that fills as the block breaks.
 *
 * Progress sources: the local mining progress (smooth, 0..1) read from MultiPlayerGameMode, and the block
 * destruction stages the server sends (ClientLevel.destructionProgress, 10 stages). SkyBlock mining uses the
 * latter, so the higher of the two is used for your own block. Other players' blocks come from the stages only.
 *
 * @see com.laybalt.mixin.IMultiPlayerGameModeBreakHighlight
 */
object BreakHighlight: Feature("Shows a box on the block you are mining that fills up as the block breaks.") {
    private val fillStyle by DropdownSetting("Fill Style", 0, listOf("Grow", "Fill Up", "Fade In", "Shrink"))
        .withDescription("Grow expands from the centre, Fill Up rises from the bottom, Fade In gets more opaque, Shrink starts full and shrinks to nothing.")
        .section("Box")
    private val startColor by ColorSetting("Start Color", Color(255, 80, 90, 90))
        .withDescription("Fill colour when mining starts. The colour blends to the End Color as the block breaks.")
    private val endColor by ColorSetting("End Color", Color(90, 255, 140, 140))
        .withDescription("Fill colour right before the block breaks.")
    private val smooth by ToggleSetting("Smooth", true)
        .withDescription("Animates the box smoothly between progress updates instead of jumping.")
    private val phase by ToggleSetting("See Through")
        .withDescription("Draws the highlight through other blocks.")

    private val outline by ToggleSetting("Outline", true)
        .withDescription("Draws an outline around the whole block as well.").section("Outline")
    private val outlineStyle by DropdownSetting("Outline Style", 0, WorldBoxKit.OUTLINE_STYLES)
        .withDescription("Full draws every edge, Corners draws only short brackets at the eight corners.").showIf { outline.value }
    private val outlineFollows by ToggleSetting("Outline Follows Progress", true)
        .withDescription("Colours the outline with the current progress colour instead of a fixed colour.").showIf { outline.value }
    private val outlineColor by ColorSetting("Outline Color", Color(158, 119, 237))
        .withDescription("Fixed outline colour, used when Outline Follows Progress is off.").showIf { outline.value && ! outlineFollows.value }
    private val lineWidth by SliderSetting("Line Width", 2.0, 1.0, 6.0, 0.1)
        .withDescription("Thickness of the outline.").showIf { outline.value }

    private val showPercent by ToggleSetting("Show Percent")
        .withDescription("Shows the mining progress in percent in the middle of the block.").section("Extras")
    private val textScale by SliderSetting("Text Scale", 1.0, 0.5, 3.0, 0.1)
        .withDescription("Size of the percent text.").showIf { showPercent.value }
    private val others by ToggleSetting("Other Players")
        .withDescription("Also highlights blocks other players are breaking, using the break stages the server sends.")
    private val othersColor by ColorSetting("Other Players Color", Color(255, 190, 70, 80))
        .withDescription("Fill colour for blocks broken by other players.").showIf { others.value }

    private const val MAX_DISTANCE_SQ = 32.0 * 32.0

    private class Target(val pos: BlockPos, val progress: Float, val own: Boolean)

    /** Progress currently drawn per block, eased toward the real value when Smooth is on. */
    private val shown = HashMap<BlockPos, Float>()
    private var lastFrame = 0L

    override fun init() {
        register<WorldChangeEvent> { shown.clear() }

        register<RenderWorldEvent> {
            val targets = collectTargets()
            val now = System.nanoTime()
            val dt = if (lastFrame == 0L) 0f else ((now - lastFrame) / 1_000_000_000f).coerceIn(0f, 0.25f)
            lastFrame = now

            shown.keys.retainAll(targets.mapTo(HashSet()) { it.pos })
            if (targets.isEmpty()) return@register

            for (target in targets) {
                val previous = shown[target.pos]
                val progress = when {
                    ! smooth.value || previous == null || target.progress < previous - 0.3f -> target.progress
                    else -> previous + (target.progress - previous) * (dt * 14f).coerceAtMost(1f)
                }
                shown[target.pos] = progress
                draw(event, target, progress.coerceIn(0f, 1f))
            }
        }
    }

    private fun collectTargets(): List<Target> {
        val level = mc.level ?: return emptyList()
        val player = mc.player ?: return emptyList()
        val result = LinkedHashMap<BlockPos, Target>()

        val gameMode = mc.gameMode
        var ownPos: BlockPos? = null
        var ownProgress = 0f
        if (gameMode != null && gameMode.isDestroying) {
            val access = gameMode as IMultiPlayerGameModeBreakHighlight
            ownPos = access.`lbqmod$getDestroyBlockPos`()?.immutable()
            ownProgress = access.`lbqmod$getDestroyProgress`()
        }

        val eye = player.eyePosition
        for (entry in level.destructionProgress().long2ObjectEntrySet()) {
            val set = entry.value ?: continue
            if (set.isEmpty()) continue
            val pos = BlockPos.of(entry.longKey)
            if (pos.distToCenterSqr(eye.x, eye.y, eye.z) > MAX_DISTANCE_SQ) continue
            val last = set.last()
            val stage = (last.progress + 1) / 10f
            val isOwn = pos == ownPos || set.any { it.id == player.id }
            if (isOwn) {
                if (pos == ownPos) ownProgress = maxOf(ownProgress, stage)
                else result[pos] = Target(pos, stage, true)
            }
            else if (others.value) result[pos] = Target(pos, stage, false)
        }

        if (ownPos != null && ! level.getBlockState(ownPos).isAir) result[ownPos] = Target(ownPos, ownProgress, true)
        return result.values.toList()
    }

    private fun draw(event: RenderWorldEvent, target: Target, progress: Float) {
        val level = mc.level ?: return
        val state = level.getBlockState(target.pos)
        if (state.isAir) return
        val shape = state.getShape(level, target.pos)
        val block = (if (shape.isEmpty) AABB(0.0, 0.0, 0.0, 1.0, 1.0, 1.0) else shape.bounds()).move(target.pos)

        val color = if (target.own) WorldBoxKit.lerp(startColor.value, endColor.value, progress) else othersColor.value
        val p = progress.toDouble()
        val center = block.center
        val fillBox = when (fillStyle.value) {
            0 -> scaled(block, center.x, center.y, center.z, p.coerceAtLeast(0.02))
            1 -> AABB(block.minX, block.minY, block.minZ, block.maxX, block.minY + (block.maxY - block.minY) * p.coerceAtLeast(0.02), block.maxZ)
            3 -> scaled(block, center.x, center.y, center.z, (1.0 - p).coerceAtLeast(0.02))
            else -> block
        }
        val fillColor = if (fillStyle.value == 2) color.withAlpha((color.alpha * progress).toInt()) else color

        event.ctx.drawBox(fillBox, fillColor, fillColor, outline = false, fill = true, phase = phase.value, lineWidth = 1f)

        if (outline.value) {
            val lineColor = if (outlineFollows.value) color.withAlpha(255) else outlineColor.value
            event.ctx.drawBox(
                block, lineColor, lineColor, outline = true, fill = false, phase = phase.value,
                lineWidth = lineWidth.value.toFloat(), corners = outlineStyle.value == 1
            )
        }

        if (showPercent.value) {
            event.ctx.renderString(
                "${(progress * 100).toInt()}%", center.x, center.y - 0.1, center.z,
                color.withAlpha(255), textScale.value, phase = true
            )
        }
    }

    private fun scaled(box: AABB, cx: Double, cy: Double, cz: Double, f: Double) = AABB(
        cx - (cx - box.minX) * f, cy - (cy - box.minY) * f, cz - (cz - box.minZ) * f,
        cx + (box.maxX - cx) * f, cy + (box.maxY - cy) * f, cz + (box.maxZ - cz) * f
    )
}
