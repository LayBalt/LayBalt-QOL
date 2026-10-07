package com.laybalt.features.impl.visual

import com.laybalt.config.ConfigHolder
import com.laybalt.config.types.ButtonSetting
import com.laybalt.config.types.DropdownSetting
import com.laybalt.config.types.SliderSetting
import com.laybalt.config.types.ToggleSetting
import com.laybalt.features.Feature
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

object Animations: Feature("Allows you to modify your hand view-model") {
    @JvmStatic val mainHandItemScale by SliderSetting("Item Scale", 0.0, - 1.5f, 1.5f, 0.05f).withDescription("0 is normal size. -0.5 is half size. 1 is double size.")

    @JvmStatic val mainHandX by SliderSetting("X", .0, - 2.0, 2.0, 0.01)
    @JvmStatic val mainHandY by SliderSetting("Y", 0.0, - 2.0, 2.0, 0.01)
    @JvmStatic val mainHandZ by SliderSetting("Z", 0.0, - 2.0, 2.0, 0.01)

    @JvmStatic val mainHandPositiveX by SliderSetting("Rotation X", 0f, - 50f, 50f, 1)
    @JvmStatic val mainHandPositiveY by SliderSetting("Rotation Y", 0f, - 50f, 50f, 1)
    @JvmStatic val mainHandPositiveZ by SliderSetting("Rotation Z", 0f, - 50f, 50f, 1)

    @JvmStatic val swingX by SliderSetting("swingX", 1.0, 0.0, 2.0, 0.01)
    @JvmStatic val swingY by SliderSetting("swingY", 1.0, 0.0, 2.0, 0.01)
    @JvmStatic val swingZ by SliderSetting("swingZ", 1.0, 0.0, 2.0, 0.01)

    @JvmStatic val disableHandMove by ToggleSetting("Disable hand movement").withDescription("Stops the held item from moving when you look around.")
    @JvmStatic val disableEquip by ToggleSetting("Disable equip animation").withDescription("Disables the equip animation when your held item changes.")
    @JvmStatic val disableSwapMain by ToggleSetting("Disable Swap Main")
        .withDescription("Disables the equip animation for the main hand only. Hidden while \"Disable equip animation\" (both hands) is on.")
        .hideIf { disableEquip.value }
    @JvmStatic val disableSwapOff by ToggleSetting("Disable Swap Off")
        .withDescription("Disables the equip animation for the off hand only. Hidden while \"Disable equip animation\" (both hands) is on.")
        .hideIf { disableEquip.value }
    @JvmStatic val disableSwingAnimation by ToggleSetting("Disable swing animation").withDescription("Disables the held item swing animation.")
    @JvmStatic val terminatorOnly by ToggleSetting("Terminator Only").withDescription("Disables the swing animation only for terminator.").showIf { disableSwingAnimation.value }

    @JvmStatic val slowAnimation by ToggleSetting("Slow Animation")
        .withDescription("Makes every swing last a fixed number of ticks (Slow Value) instead of the vanilla length. Overrides Swing Speed and Ignore Haste.")
    @JvmStatic val slowValue by SliderSetting("Slow Value", 12, 1, 50, 1, " ticks")
        .withDescription("Length of one swing in ticks (vanilla is 6). Higher = slower swing.").showIf { slowAnimation.value }

    @JvmStatic val swingSpeed by SliderSetting("Swing Speed", .0, - 2f, 1f, 0.05).hideIf { disableSwingAnimation.value && ! terminatorOnly.value || slowAnimation.value }
    @JvmStatic val ignoreHaste by ToggleSetting("Ignore Haste").withDescription("Ignores the haste speed boost.").hideIf { disableSwingAnimation.value && ! terminatorOnly.value || slowAnimation.value }

    private val styleNames = listOf(
        "Vanilla", "Smooth", "Block Hit", "Slide", "Stab", "Spin", "Chop", "Push",
        "Swirl", "Tap", "Dip", "Wave", "Flick", "Lunge", "Sway"
    )

    @JvmStatic val swingStyle by DropdownSetting("Swing Style", 0, styleNames)
        .withDescription(
            "Shape of the first-person swing. Vanilla keeps Minecraft's swing (and the swingX/Y/Z sliders, which only apply to Vanilla). " +
                "Smooth: no sideways sway. Block Hit: old-style angled block. Slide: sweeps sideways. Stab: thrusts forward. " +
                "Spin: spins the item. Chop: hacks straight down. Push: shoves forward and tips. Swirl: rolls the item. " +
                "Tap: a tiny bob. Dip: drops the hand. Wave: rocks side to side. Flick: quick wrist flick. " +
                "Lunge: big forward lunge. Sway: horizontal sweep."
        )

    private val reset by ButtonSetting("Reset") {
        configSettings.forEach(ConfigHolder<*>::reset)
    }

    /** Fixed swing length in ticks, or -1 when "Slow Animation" is off. Used by `MixinLivingEntity`. */
    @JvmStatic
    fun fixedSwingDuration(): Int = if (enabled && slowAnimation.value) slowValue.value.toInt() else - 1

    /** True when a custom swing style replaces vanilla's swing transform. */
    @JvmStatic
    fun hasCustomSwingStyle() = enabled && swingStyle.value != 0

    /**
     * Applies the selected swing style in place of vanilla's swing (translation + attack rotation).
     * [invert] is 1 for the right arm and -1 for the left arm; [attack] is the swing progress 0..1.
     */
    @JvmStatic
    fun applySwingStyle(pose: PoseStack, attack: Float, invert: Int) {
        val p = attack.coerceIn(0f, 1f)
        val inv = invert.toFloat()
        val s = sin(sqrt(p) * PI.toFloat()) // fast rise, slow fall
        val q = sin(p * p * PI.toFloat()) // slow rise, fast fall
        val w = sin(p * PI.toFloat()) // symmetric bump
        val turn = (p * 1.2f).coerceIn(0f, 1f)

        fun rx(deg: Float) = pose.mulPose(Axis.XP.rotationDegrees(deg))
        fun ry(deg: Float) = pose.mulPose(Axis.YP.rotationDegrees(deg))
        fun rz(deg: Float) = pose.mulPose(Axis.ZP.rotationDegrees(deg))

        when (swingStyle.value) {
            1 -> { // Smooth: the vanilla rotation without the positional sway
                ry(inv * (45f - q * 20f))
                rz(inv * - s * 20f)
                rx(- s * 80f)
                ry(inv * - 45f)
            }
            2 -> { // Block Hit: item held at an angle, short chop
                pose.translate(inv * - 0.08f, 0.12f, 0.02f)
                ry(inv * 30f)
                rz(inv * - 25f)
                rx(- 20f - s * 45f)
            }
            3 -> { // Slide: sweeps towards the middle of the screen
                pose.translate(inv * - 0.45f * s, - 0.05f * w, - 0.1f * s)
                rz(inv * - 15f * s)
            }
            4 -> { // Stab: straight thrust
                pose.translate(0f, 0.02f * w, - 0.55f * w)
                rx(- 12f * w)
            }
            5 -> ry(inv * 360f * turn) // Spin: one full turn around the vertical axis
            6 -> { // Chop: hack downwards
                pose.translate(0f, 0.1f * s, 0f)
                rx(- 110f * s)
            }
            7 -> { // Push: shove forward and tip
                pose.translate(0f, - 0.05f * s, - 0.3f * s)
                rx(- 35f * s)
            }
            8 -> rz(inv * 360f * turn) // Swirl: roll around the view axis
            9 -> { // Tap: tiny bob
                pose.translate(0f, - 0.12f * w, - 0.04f * w)
                rx(- 10f * w)
            }
            10 -> { // Dip: hand drops and recovers
                pose.translate(0f, - 0.4f * s, 0f)
                rx(- 20f * s)
            }
            11 -> { // Wave: rocks side to side
                rz(inv * - 55f * s)
                ry(inv * 20f * w)
            }
            12 -> { // Flick: quick wrist flick near the end of the swing
                rx(- 90f * q)
                ry(inv * - 30f * q)
            }
            13 -> { // Lunge: big forward lunge
                pose.translate(inv * - 0.2f * s, 0.1f * s, - 0.45f * s)
                ry(inv * 30f * s)
                rx(- 40f * s)
            }
            14 -> { // Sway: horizontal sweep from outside to inside
                ry(inv * (45f - 70f * s))
                rx(- 15f * w)
                ry(inv * - 45f)
            }
        }
    }
}
