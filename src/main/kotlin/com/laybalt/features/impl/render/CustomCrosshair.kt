package com.laybalt.features.impl.render

import com.laybalt.config.types.ColorSetting
import com.laybalt.config.types.DropdownSetting
import com.laybalt.config.types.SliderSetting
import com.laybalt.config.types.ToggleSetting
import com.laybalt.event.impl.MouseClickEvent
import com.laybalt.event.impl.WorldChangeEvent
import com.laybalt.features.Feature
import com.laybalt.utils.ColorUtils.withAlpha
import com.laybalt.utils.render.Render2D.drawAnnularSegment
import com.laybalt.utils.render.Render2D.drawLine
import com.laybalt.utils.render.Render2D.drawRect
import com.mojang.blaze3d.platform.InputConstants
import gg.essential.universal.UMinecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.debug.DebugScreenEntries
import net.minecraft.world.level.GameType
import net.minecraft.world.phys.HitResult
import java.awt.Color
import kotlin.math.PI
import kotlin.math.sqrt

/**
 * Draws a configurable crosshair in place of (or on top of) the vanilla one.
 *
 * Shapes are built from three primitives (rectangles, lines and rings), each drawn twice: first enlarged in the
 * outline colour, then in the main colour. Dynamic spread pushes the arms outward while you move, jump or attack
 * and eases back when you stop.
 *
 * @see com.laybalt.mixin.MixinHudCustomCrosshair
 */
object CustomCrosshair: Feature("Replaces the crosshair with your own shape, size, colours and a dynamic spread.") {
    private val SHAPES = listOf(
        "Cross", "Cross + Dot", "Dot", "Circle", "Circle + Dot", "Circle + Cross",
        "T-Shape", "X", "X + Dot", "Square", "Brackets", "Chevron"
    )

    private val shape by DropdownSetting("Shape", 1, SHAPES)
        .withDescription("Form of the crosshair.").section("Shape")
    private val length by SliderSetting("Length", 4.0, 1.0, 20.0, 0.5)
        .withDescription("Length of each arm in GUI pixels. For Circle it sets the ring size, for Square and Brackets the box size.")
    private val gap by SliderSetting("Gap", 2.0, 0.0, 15.0, 0.5)
        .withDescription("Empty space between the centre and the start of each arm.")
    private val thickness by SliderSetting("Thickness", 1.0, 0.5, 6.0, 0.5)
        .withDescription("Width of the arms, ring and lines.")
    private val dotSize by SliderSetting("Dot Size", 1.5, 0.5, 6.0, 0.5)
        .withDescription("Size of the centre dot.").showIf { hasDot() }

    private val color by ColorSetting("Color", Color(255, 255, 255, 235))
        .withDescription("Main colour of the crosshair.").section("Colors")
    private val targetColorToggle by ToggleSetting("Color On Target")
        .withDescription("Switches to another colour while the crosshair is on an entity.")
    private val targetColor by ColorSetting("Target Color", Color(255, 85, 110, 245))
        .withDescription("Colour used while you aim at an entity.").showIf { targetColorToggle.value }
    private val outline by ToggleSetting("Outline", true)
        .withDescription("Draws a border around every part of the crosshair so it stays visible on bright backgrounds.")
    private val outlineColor by ColorSetting("Outline Color", Color(10, 8, 16, 200))
        .withDescription("Colour of the border.").showIf { outline.value }
    private val outlineWidth by SliderSetting("Outline Width", 0.5, 0.5, 3.0, 0.5)
        .withDescription("Thickness of the border.").showIf { outline.value }

    private val dynamic by ToggleSetting("Dynamic Spread")
        .withDescription("Pushes the crosshair apart while you move or attack, like in shooters.").section("Dynamic")
    private val spreadMove by ToggleSetting("Spread When Moving", true)
        .withDescription("Spreads with your walking speed, and a bit more while in the air.").showIf { dynamic.value }
    private val spreadAttack by ToggleSetting("Spread When Attacking", true)
        .withDescription("Kicks the crosshair apart when you left click and while your attack is recharging.").showIf { dynamic.value }
    private val maxSpread by SliderSetting("Max Spread", 6.0, 1.0, 20.0, 0.5)
        .withDescription("Largest extra gap the spread can add, in GUI pixels.").showIf { dynamic.value }
    private val recovery by SliderSetting("Recovery Speed", 8.0, 1.0, 20.0, 0.5)
        .withDescription("How quickly the crosshair follows the spread and closes again. Higher is snappier.").showIf { dynamic.value }

    private val attackIndicator by DropdownSetting("Attack Indicator", 0, listOf("Off", "Bar", "Arc"))
        .withDescription("Shows your attack cooldown under the crosshair as a bar, or as an arc around it, while it recharges.").section("Behaviour")
    private val hideVanilla by ToggleSetting("Hide Vanilla", true)
        .withDescription("Hides the vanilla crosshair (and its attack indicator). Turn off to draw on top of it.")
    private val thirdPerson by ToggleSetting("Show In Third Person")
        .withDescription("Also draws the crosshair in third person view, where vanilla hides it.")

    private var spread = 0f
    private var attackKick = 0f
    private var lastFrame = 0L

    override fun init() {
        register<MouseClickEvent> {
            if (event.button == 0 && event.action == InputConstants.PRESS && UMinecraft.currentScreenObj == null) attackKick = 1f
        }
        register<WorldChangeEvent> {
            spread = 0f
            attackKick = 0f
        }
    }

    private fun hasDot() = shape.value in setOf(1, 2, 4, 8)

    private fun threeDimensionalDebug() = mc.debugEntries.isCurrentlyEnabled(DebugScreenEntries.THREE_DIMENSIONAL_CROSSHAIR)

    /** True when the vanilla crosshair should be skipped and ours drawn instead. */
    @JvmStatic
    fun replacesVanilla() = enabled && hideVanilla.value && ! threeDimensionalDebug()

    /** True when ours should be drawn after vanilla (vanilla kept). */
    @JvmStatic
    fun drawsOverVanilla() = enabled && ! hideVanilla.value && ! threeDimensionalDebug()

    @JvmStatic
    fun render(ctx: GuiGraphicsExtractor) {
        val player = mc.player ?: return
        if (! mc.options.cameraType.isFirstPerson && ! thirdPerson.value) return
        if (mc.gameMode?.playerMode == GameType.SPECTATOR && mc.hitResult?.type != HitResult.Type.ENTITY) return

        updateSpread(player.deltaMovement.x, player.deltaMovement.z, player.onGround(), player.getAttackStrengthScale(0f))

        val cx = ((ctx.guiWidth() - 15) / 2) + 7.5f
        val cy = ((ctx.guiHeight() - 15) / 2) + 7.5f
        val main = if (targetColorToggle.value && mc.crosshairPickEntity != null) targetColor.value else color.value

        if (outline.value) drawShape(ctx, cx, cy, outlineColor.value, outlineWidth.value.toFloat())
        drawShape(ctx, cx, cy, main, 0f)
        drawAttackIndicator(ctx, cx, cy, player.getAttackStrengthScale(0f), main)
    }

    private fun updateSpread(vx: Double, vz: Double, onGround: Boolean, attackStrength: Float) {
        val now = System.nanoTime()
        val dt = if (lastFrame == 0L) 0f else ((now - lastFrame) / 1_000_000_000f).coerceIn(0f, 0.1f)
        lastFrame = now

        attackKick = (attackKick - dt * 4f).coerceAtLeast(0f)
        if (! dynamic.value) {
            spread = 0f
            return
        }

        var factor = 0f
        if (spreadMove.value) {
            val speed = sqrt(vx * vx + vz * vz).toFloat()
            factor = maxOf(factor, (speed / 0.28f).coerceIn(0f, 1f) + if (onGround) 0f else 0.35f)
        }
        if (spreadAttack.value) factor = maxOf(factor, attackKick, (1f - attackStrength) * 0.8f)

        val target = maxSpread.value.toFloat() * factor.coerceIn(0f, 1.2f)
        spread += (target - spread) * (dt * recovery.value.toFloat()).coerceAtMost(1f)
    }

    /** Draws every primitive of the current shape. [grow] > 0 draws the enlarged outline pass. */
    private fun drawShape(ctx: GuiGraphicsExtractor, cx: Float, cy: Float, c: Color, grow: Float) {
        val t = thickness.value.toFloat()
        val len = length.value.toFloat()
        val g = gap.value.toFloat() + spread
        val ht = t / 2f

        fun rect(x: Float, y: Float, w: Float, h: Float) = ctx.drawRect(x - grow, y - grow, w + grow * 2, h + grow * 2, c)

        fun line(x1: Float, y1: Float, x2: Float, y2: Float) {
            val dx = x2 - x1
            val dy = y2 - y1
            val d = sqrt(dx * dx + dy * dy).takeIf { it > 0f } ?: return
            val ex = dx / d * grow
            val ey = dy / d * grow
            ctx.drawLine(x1 - ex, y1 - ey, x2 + ex, y2 + ey, c, t + grow * 2)
        }

        fun ring(radius: Float) = ctx.drawAnnularSegment(cx, cy, (radius - ht - grow).coerceAtLeast(0f), radius + ht + grow, 0.0, 2 * PI, c)

        fun dot() {
            val d = dotSize.value.toFloat()
            rect(cx - d / 2f, cy - d / 2f, d, d)
        }

        fun cross(top: Boolean = true) {
            if (top) rect(cx - ht, cy - g - len, t, len)
            rect(cx - ht, cy + g, t, len)
            rect(cx - g - len, cy - ht, len, t)
            rect(cx + g, cy - ht, len, t)
        }

        fun diagonals() {
            val k = (1.0 / sqrt(2.0)).toFloat()
            for ((sx, sy) in listOf(1f to 1f, - 1f to 1f, 1f to - 1f, - 1f to - 1f)) {
                line(cx + sx * g * k, cy + sy * g * k, cx + sx * (g + len) * k, cy + sy * (g + len) * k)
            }
        }

        when (shape.value) {
            0 -> cross()
            1 -> { cross(); dot() }
            2 -> dot()
            3 -> ring(g + len / 2f)
            4 -> { ring(g + len / 2f); dot() }
            5 -> { ring(g + len + t * 1.5f); cross() }
            6 -> cross(top = false)
            7 -> diagonals()
            8 -> { diagonals(); dot() }
            9 -> {
                val h = g + len / 2f
                rect(cx - h, cy - h, h * 2, t)
                rect(cx - h, cy + h - t, h * 2, t)
                rect(cx - h, cy - h + t, t, h * 2 - t * 2)
                rect(cx + h - t, cy - h + t, t, h * 2 - t * 2)
            }
            10 -> {
                val h = len
                val arm = (len / 2f).coerceAtLeast(t * 2)
                for (side in listOf(- 1f, 1f)) {
                    val x = if (side < 0) cx - g - t else cx + g
                    rect(x, cy - h, t, h * 2)
                    val armX = if (side < 0) x else x + t - arm
                    rect(armX, cy - h, arm, t)
                    rect(armX, cy + h - t, arm, t)
                }
            }
            11 -> {
                val reach = len * 0.75f
                line(cx, cy + g, cx - reach, cy + g + reach)
                line(cx, cy + g, cx + reach, cy + g + reach)
            }
        }
    }

    private fun drawAttackIndicator(ctx: GuiGraphicsExtractor, cx: Float, cy: Float, strength: Float, c: Color) {
        if (strength >= 1f) return
        val extent = gap.value.toFloat() + spread + length.value.toFloat()
        when (attackIndicator.value) {
            1 -> {
                val w = 16f
                val y = cy + extent + 4f
                ctx.drawRect(cx - w / 2f, y, w, 2f, Color(0, 0, 0, 140))
                ctx.drawRect(cx - w / 2f, y, w * strength, 2f, c)
            }
            2 -> {
                val r = extent + 3f
                ctx.drawAnnularSegment(cx, cy, r, r + 1.5f, 0.0, 2 * PI, c.withAlpha(50))
                ctx.drawAnnularSegment(cx, cy, r, r + 1.5f, - PI / 2, - PI / 2 + 2 * PI * strength, c)
            }
        }
    }
}
