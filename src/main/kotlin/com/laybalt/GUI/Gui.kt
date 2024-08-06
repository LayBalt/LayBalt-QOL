package com.laybalt.GUI

import gg.essential.elementa.ElementaVersion
import gg.essential.elementa.UIComponent
import gg.essential.elementa.WindowScreen
import gg.essential.elementa.components.*
import gg.essential.elementa.constraints.*
import gg.essential.elementa.constraints.animation.Animations
import gg.essential.elementa.dsl.*
import java.awt.Color

class Gui : WindowScreen(ElementaVersion.V2) {
    private val mainPurple = Color(186, 85, 211)
    private val lighterPurple = Color(216, 191, 216)
    private val darkerPurple = Color(148, 0, 211)

    private val mainMenu = UIContainer().constrain {
        x = CenterConstraint()
        y = 10.pixels()
        width = ChildBasedSizeConstraint()
        height = ChildBasedSizeConstraint()
    } childOf window

    private val subMenuContainer = UIContainer().constrain {
        x = CenterConstraint()
        y = SiblingConstraint(padding = 5f)
        width = ChildBasedMaxSizeConstraint()
        height = ChildBasedSizeConstraint()
    } childOf window

    private val configMenuContainer = UIContainer().constrain {
        x = CenterConstraint()
        y = SiblingConstraint(padding = 5f)
        width = ChildBasedSizeConstraint()
        height = ChildBasedSizeConstraint()
    } childOf window

    init {
        val combatButton = createMainButton("combat")
        val skillButton = createMainButton("skill")

        combatButton childOf mainMenu
        skillButton childOf mainMenu

        val combatSubMenu = createSubMenu()
        val autoClickerButton = createFunctionButton("Auto Clicker")
        autoClickerButton childOf combatSubMenu

        val skillSubMenu = createSubMenu()
        // Add skill functions here

        val autoClickerConfig = createConfigMenu()
        val enableToggle = createToggleButton("Enable")
        val cpsSlider = createSlider("CPS", 2f, 20f, 14f)

        enableToggle childOf autoClickerConfig
        cpsSlider childOf autoClickerConfig

        combatButton.onMouseClick { toggleSubMenu(combatSubMenu) }
        skillButton.onMouseClick { toggleSubMenu(skillSubMenu) }
        autoClickerButton.onMouseClick { toggleConfigMenu(autoClickerConfig) }
    }

    private fun createMainButton(text: String): UIComponent {
        return UIBlock(mainPurple).constrain {
            x = SiblingConstraint(padding = 5f)
            y = 0.pixels()
            width = 100.pixels()
            height = 30.pixels()
        }.also { button ->
            UIText(text, shadow = false).constrain {
                x = CenterConstraint()
                y = CenterConstraint()
                textScale = 1.5f.pixels()
                color = Color.WHITE.toConstraint()
            } childOf button
        }
    }

    private fun createSubMenu(): UIContainer {
        return UIContainer().constrain {
            x = 0.pixels()
            y = 0.pixels()
            width = 100.pixels()
            height = 0.pixels() // Start hidden
        } childOf subMenuContainer
    }

    private fun createFunctionButton(text: String): UIComponent {
        return UIBlock(lighterPurple).constrain {
            x = 0.pixels()
            y = SiblingConstraint(padding = 2f)
            width = 100.pixels()
            height = 25.pixels()
        }.also { button ->
            UIText(text, shadow = false).constrain {
                x = CenterConstraint()
                y = CenterConstraint()
                textScale = 1.2f.pixels()
                color = Color.BLACK.toConstraint()
            } childOf button
        }
    }

    private fun createConfigMenu(): UIBlock {
        return UIBlock(lighterPurple).constrain {
            x = CenterConstraint()
            y = 0.pixels()
            width = 200.pixels()
            height = 0.pixels() // Start hidden
        }.also { container ->
            UIText("Clicker Config", shadow = false).constrain {
                x = CenterConstraint()
                y = 5.pixels()
                textScale = 1.5f.pixels()
                color = Color.BLACK.toConstraint()
            } childOf container
        } childOf configMenuContainer
    }

    private fun createToggleButton(text: String): UIComponent {
        val toggle = UIBlock(Color.GRAY).constrain {
            x = SiblingConstraint(padding = 5f)
            y = CenterConstraint()
            width = 20.pixels()
            height = 10.pixels()
        }

        val toggleIndicator = UIBlock(Color.WHITE).constrain {
            x = 0.pixels()
            y = 0.pixels()
            width = 10.pixels()
            height = 10.pixels()
        } childOf toggle

        var isEnabled = false

        toggle.onMouseClick {
            isEnabled = !isEnabled
            toggleIndicator.animate {
                setXAnimation(Animations.OUT_EXP, 0.5f, if (isEnabled) 10.pixels() else 0.pixels())
            }
            toggle.setColor(if (isEnabled) Color.GREEN else Color.GRAY)
        }

        return UIContainer().constrain {
            x = 10.pixels()
            y = SiblingConstraint(padding = 5f)
            width = ChildBasedSizeConstraint()
            height = 20.pixels()
        }.also { container ->
            UIText(text, shadow = false).constrain {
                x = 0.pixels()
                y = CenterConstraint()
                textScale = 1.2f.pixels()
                color = Color.BLACK.toConstraint()
            } childOf container
            toggle childOf container
        }
    }

    private fun createSlider(text: String, min: Float, max: Float, default: Float): UIComponent {
        val slider = UIRoundedRectangle(3f).constrain {
            x = 0.pixels()
            y = SiblingConstraint(padding = 5f)
            width = RelativeConstraint(0.8f)
            height = 10.pixels()
            color = Color.GRAY.constraint
        }

        val sliderKnob = UIBlock(darkerPurple).constrain {
            x = 0.pixels()
            y = 0.pixels()
            width = 10.pixels()
            height = 10.pixels()
        } childOf slider

        var currentValue = default
        val range = max - min

        slider.onMouseClick { event ->
            val relativeX = (event.relativeX / slider.getWidth()).coerceIn(0f, 1f)
            currentValue = min + (relativeX * range)
            sliderKnob.setX((relativeX * (slider.getWidth() - 10)).pixels())
        }

        return UIContainer().constrain {
            x = 10.pixels()
            y = SiblingConstraint(padding = 10f)
            width = RelativeConstraint()
            height = ChildBasedSizeConstraint()
        }.also { container ->
            UIText("$text: $default", shadow = false).constrain {
                x = 0.pixels()
                y = 0.pixels()
                textScale = 1.2f.pixels()
                color = Color.BLACK.toConstraint()
            } childOf container
            slider childOf container
        }
    }

    private fun toggleSubMenu(subMenu: UIContainer) {
        val isMenuVisible = subMenu.getHeight().toDouble() != 0.0
        val targetHeight = if (isMenuVisible) 0.pixels() else ChildBasedSizeConstraint()
        subMenu.animate {
            setHeightAnimation(Animations.OUT_EXP, 0.5f, targetHeight)
        }
        subMenuContainer.children.forEach { child ->
            if (child != subMenu && child.getHeight().toDouble() != 0.0) {
                child.animate {
                    setHeightAnimation(Animations.OUT_EXP, 0.5f, 0.pixels())
                }
            }
        }
        configMenuContainer.children.forEach { child ->
            child.animate {
                setHeightAnimation(Animations.OUT_EXP, 0.5f, 0.pixels())
            }
        }
    }

    private fun toggleConfigMenu(configMenu: UIBlock) {
        val isMenuVisible = configMenu.getHeight().toDouble() != 0.0
        val targetHeight = if (isMenuVisible) 0.pixels() else ChildBasedSizeConstraint()
        configMenu.animate {
            setHeightAnimation(Animations.OUT_EXP, 0.5f, targetHeight)
        }
    }
}