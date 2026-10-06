package com.laybalt.GUI.clickgui.setting

import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import java.awt.Color
import kotlin.math.roundToInt

/**
 * A click GUI setting. It owns no state: [value] reads and writes the existing [com.laybalt.GUI.LBQConfig]
 * properties, so the config file and every feature keep working unchanged.
 */
abstract class Setting<T>(val name: String, private val getter: () -> T, private val setter: (T) -> Unit) {
    var description: String? = null
    var section: String? = null
    var visibility: () -> Boolean = { true }

    open var value: T
        get() = getter()
        set(v) = setter(v)
}

fun <S: Setting<*>> S.withDescription(text: String) = apply { description = text }
fun <S: Setting<*>> S.section(name: String) = apply { section = name }
fun <S: Setting<*>> S.showIf(condition: () -> Boolean) = apply { visibility = condition }

class ToggleSetting(name: String, getter: () -> Boolean, setter: (Boolean) -> Unit): Setting<Boolean>(name, getter, setter)

class SliderSetting(
    name: String,
    val min: Int,
    val max: Int,
    val step: Int = 1,
    val suffix: String = "",
    getter: () -> Int,
    setter: (Int) -> Unit
): Setting<Int>(name, getter, setter) {
    fun percent(v: Int) = ((v - min).toFloat() / (max - min).toFloat()).coerceIn(0f, 1f)

    fun snap(raw: Double): Int {
        val stepped = min + ((raw - min) / step).roundToInt() * step
        return stepped.coerceIn(min, max)
    }
}

class DropdownSetting(name: String, val options: List<String>, getter: () -> Int, setter: (Int) -> Unit): Setting<Int>(name, getter, setter)

class ColorSetting(name: String, val withAlpha: Boolean, getter: () -> Color, setter: (Color) -> Unit): Setting<Color>(name, getter, setter)

class ButtonSetting(name: String, val action: () -> Unit): Setting<Unit>(name, {}, {})

/** Section header / separator rows inserted by the config window. */
class LabelSetting(name: String): Setting<Unit>(name, {}, {})

/**
 * Rebinds one of the mod's vanilla [KeyMapping]s (the same binding shown in Options > Controls),
 * looked up by its translation key.
 */
class KeybindSetting(name: String, private val translationKey: String): Setting<Unit>(name, {}, {}) {
    private val mc get() = Minecraft.getInstance()
    private val mapping: KeyMapping? get() = mc.options.keyMappings.firstOrNull { it.name == translationKey }

    fun displayName(): String {
        val m = mapping ?: return "?"
        return if (m.isUnbound) "NONE" else m.translatedKeyMessage.string.uppercase()
    }

    fun bindKey(keyCode: Int) = bind(InputConstants.Type.KEYSYM.getOrCreate(keyCode))
    fun bindMouse(button: Int) = bind(InputConstants.Type.MOUSE.getOrCreate(button))
    fun unbind() = bind(InputConstants.UNKNOWN)

    private fun bind(key: InputConstants.Key) {
        val m = mapping ?: return
        m.setKey(key)
        KeyMapping.resetMapping()
        mc.options.save()
    }
}
