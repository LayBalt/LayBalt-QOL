package com.laybalt.features.impl.render

import com.laybalt.config.types.ColorSetting
import com.laybalt.config.types.SliderSetting
import com.laybalt.config.types.ToggleSetting
import com.laybalt.features.Feature
import org.joml.Vector4f
import java.awt.Color

/**
 * Enchantment glint colour, strength, speed and size.
 *
 * The 26.2 glint shader computes `texture(glint) * ColorModulator` and adds it to the item, so the colour is changed
 * through the ColorModulator uniform of the glint render types (`MixinRenderTypeGlintColor`). The vanilla glint
 * textures are purple, so each channel is divided by the texture's average channel value (measured from the 26.2
 * textures) to turn the purple pattern into the chosen colour at roughly the same brightness.
 * Speed and size go through the glint texture matrix (`MixinTextureTransformGlintColor`).
 */
object GlintColor: Feature("Recolours the enchantment glint on items and armour and changes its strength, speed and size.") {
    /** Average R, G, B of textures/misc/enchanted_glint_item.png and enchanted_glint_armor.png (0..1). */
    private val ITEM_MEAN = floatArrayOf(0.347f, 0.166f, 0.562f)
    private val ARMOR_MEAN = floatArrayOf(0.071f, 0.031f, 0.182f)

    private val recolor by ToggleSetting("Recolor", true)
        .withDescription("Replaces the purple glint with the colour below. Turn off to keep the vanilla purple and only change strength, speed and size.")
    private val itemColor by ColorSetting("Color", Color(85, 255, 255), false)
        .withDescription("Glint colour on items (in hand, in inventories and dropped) and, unless Separate Armor Color is on, on worn armour.")
        .showIf { recolor.value && ! rainbow.value }
    private val separateArmor by ToggleSetting("Separate Armor Color")
        .withDescription("Gives worn armour (and elytra) its own glint colour.")
        .showIf { recolor.value && ! rainbow.value }
    private val armorColor by ColorSetting("Armor Color", Color(255, 85, 255), false)
        .withDescription("Glint colour on worn armour.")
        .showIf { recolor.value && ! rainbow.value && separateArmor.value }
    private val rainbow by ToggleSetting("Rainbow")
        .withDescription("Cycles the glint colour through the rainbow.")
        .showIf { recolor.value }
    private val rainbowSpeed by SliderSetting("Rainbow Speed", 1.0, 0.1, 5.0, 0.1, "x")
        .withDescription("How fast the rainbow cycles.")
        .showIf { recolor.value && rainbow.value }
    private val strength by SliderSetting("Strength", 100, 0, 300, 5, "%")
        .withDescription("Brightness of the glint. 100% is vanilla brightness, 0% hides the glint. Stacks with the vanilla Glint Strength accessibility option.")
    private val speed by SliderSetting("Speed", 1.0, 0.0, 5.0, 0.05, "x")
        .withDescription("How fast the glint pattern scrolls, multiplied with the vanilla Glint Speed accessibility option (if that is 0 the glint stays still).")
    private val size by SliderSetting("Pattern Size", 1.0, 0.25, 4.0, 0.05, "x")
        .withDescription("Size of the glint pattern. Above 1 the stripes are wider, below 1 they are finer.")

    /** Colour modulator for a glint draw, or null to keep vanilla. Called on the render thread for every glint draw. */
    @JvmStatic
    fun modulator(armor: Boolean): Vector4f? {
        if (! enabled) return null
        val st = strength.value.toFloat() / 100f
        if (! recolor.value) {
            if (st == 1f) return null
            return Vector4f(st, st, st, 1f)
        }

        val c = when {
            rainbow.value -> Color(Color.HSBtoRGB((System.currentTimeMillis() * rainbowSpeed.value / 4000.0 % 1.0).toFloat(), 1f, 1f))
            armor && separateArmor.value -> armorColor.value
            else -> itemColor.value
        }
        val mean = if (armor) ARMOR_MEAN else ITEM_MEAN
        val avg = (mean[0] + mean[1] + mean[2]) / 3f
        return Vector4f(
            c.red / 255f * avg / mean[0] * st,
            c.green / 255f * avg / mean[1] * st,
            c.blue / 255f * avg / mean[2] * st,
            1f
        )
    }

    @JvmStatic
    fun speedMultiplier(): Double = if (enabled) speed.value.toDouble() else 1.0

    @JvmStatic
    fun sizeMultiplier(): Float = if (enabled) size.value.toFloat().coerceAtLeast(0.01f) else 1f
}
