package com.laybalt.config.types

import com.laybalt.config.ConfigHolder
import com.laybalt.config.Savable
import com.laybalt.utils.GsonUtils.gsonArray
import com.laybalt.utils.MathUtils
import com.google.gson.JsonElement

/**
 * A `start .. end` pair on one slider (NoammAddons RangeSliderSetting, with [random] from NoammAddons 5dff2334).
 * [random] rolls a normally distributed value between the two ends (the old "Random Delay" min/max behaviour), or
 * returns the value itself when both ends meet (the old fixed delay).
 *
 * Saved as `[start, end]`. A single number, the value of a plain [SliderSetting] this setting replaced under the same
 * json name, reads as the fixed range `n .. n`, so such a setting keeps its saved value.
 */
class RangeSliderSetting<T: Number>(
    name: String,
    defaultValue: Pair<T, T>,
    val min: T,
    val max: T,
    step: T,
    val suffix: String = ""
): ConfigHolder<Pair<T, T>>(name, defaultValue), Savable {
    private val scale = SliderSetting(name, min, min, max, step, suffix)
    val step: T get() = scale.step

    val start get() = value.first
    val end get() = value.second

    override fun write() = gsonArray {
        add(value.first)
        add(value.second)
    }

    override fun read(element: JsonElement) {
        value = if (element.isJsonArray) {
            val array = element.asJsonArray
            clampRange(snapToStep(array[0].asDouble), snapToStep(array[1].asDouble))
        }
        else snapToStep(element.asDouble).let { it to it }
    }

    /** The pair in order (the larger end second). */
    fun clampRange(start: T, end: T): Pair<T, T> {
        if (start.toDouble() > end.toDouble()) return end to start
        return start to end
    }

    operator fun contains(number: Number) = number.toDouble() in start.toDouble() .. end.toDouble()

    /** A gaussian value between the ends, or the value itself when both ends are equal. */
    fun random(): Int = roll(start.toInt(), end.toInt())

    fun snapToStep(rawDouble: Double) = scale.snapToStep(rawDouble)
    fun stringfy(v: T) = scale.stringfy(v)
    fun getPercent(valueIn: T) = scale.getPercent(valueIn)

    /** "120 - 150ms", or "150ms" when both ends are equal. */
    fun display(range: Pair<T, T> = value) =
        if (range.first.toDouble() == range.second.toDouble()) stringfy(range.first) + suffix
        else "${stringfy(range.first)} - ${stringfy(range.second)}$suffix"

    /**
     * Parses typed text: "120-150", "120 150", "120..150" or "120, 150" for a range, a single number for a fixed value.
     * The ends are snapped to the step and put in order; null when the text is not one of those forms.
     */
    fun parse(text: String): Pair<T, T>? {
        val match = rangeRegex.matchEntire(text) ?: return null
        val first = match.groupValues[1].toDoubleOrNull() ?: return null
        val second = match.groupValues[2].takeIf(String::isNotEmpty)?.let { it.toDoubleOrNull() ?: return null } ?: first
        return clampRange(snapToStep(first), snapToStep(second))
    }

    companion object {
        private val rangeRegex = Regex("""^\s*(-?\d+(?:\.\d*)?)\s*(?:(?:\.\.|-|–|,|\s)\s*(-?\d+(?:\.\d*)?))?\s*$""")

        /** A gaussian value in `min(a, b) .. max(a, b)`, or [a] when both are equal. */
        fun roll(a: Int, b: Int): Int = if (a == b) a else MathUtils.gaussianRandom(minOf(a, b), maxOf(a, b))
    }
}
