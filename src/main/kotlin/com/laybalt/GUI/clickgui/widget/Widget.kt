package com.laybalt.GUI.clickgui.widget

import com.laybalt.GUI.clickgui.setting.*
import net.minecraft.client.gui.GuiGraphicsExtractor

abstract class Widget<T>(val config: Setting<T>) {
    val name get() = config.name

    open var value: T
        get() = config.value
        set(v) {
            config.value = v
        }

    var x = 0
    var y = 0
    var width = 0
    open val height: Int get() = 20

    fun isHovered(mouseX: Number, mouseY: Number, h: Int = height) =
        mouseX.toDouble() in x.toDouble()..(x + width).toDouble() && mouseY.toDouble() in y.toDouble()..(y + h).toDouble()

    abstract fun draw(ctx: GuiGraphicsExtractor, mouseX: Int, mouseY: Int)
    abstract fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean

    open fun clearFocus() {}
    open fun mouseReleased(button: Int) {}
    open fun mouseScrolled(mouseX: Int, mouseY: Int, delta: Double) = false
    open fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int) = false
    open fun charTyped(codePoint: Char) = false

    companion object {
        fun fromSetting(setting: Setting<*>): Widget<*> = when (setting) {
            is ToggleSetting -> ToggleWidget(setting)
            is SliderSetting -> SliderWidget(setting)
            is DropdownSetting -> DropdownWidget(setting)
            is ColorSetting -> ColorWidget(setting)
            is KeybindSetting -> KeybindWidget(setting)
            is ButtonSetting -> ButtonWidget(setting)
            is LabelSetting -> SectionWidget(setting.name)
            else -> error("Unknown setting type: ${setting::class.qualifiedName}")
        }
    }
}
