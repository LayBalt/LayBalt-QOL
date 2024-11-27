package com.laybalt.vamphelper

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiIngame
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent
import net.minecraftforge.fml.common.gameevent.TickEvent
import net.minecraftforge.fml.relauncher.ReflectionHelper
import java.lang.reflect.Field
import java.lang.reflect.Method

class TitleChecker {
    private val mc: Minecraft = Minecraft.getMinecraft()
    private var overlayMessageField: Field? = null
    private var displayedTitleField: Field? = null
    private var displayedSubTitleField: Field? = null
    private var clickMouseMethod: Method? = null

    private var targetPitch: Float? = null // Целевой угол наклона головы
    private var targetYaw: Float? = null // Целевой угол поворота головы
    private var pitchSpeed: Float = 5f    // Скорость изменения угла наклона головы
    private var yawSpeed: Float = 5f      // Скорость изменения угла поворота головы
    private var isPerformingClick: Boolean = false
    private var lastSubtitle: String? = null // Последний зарегистрированный Subtitle

    init {
        try {
            overlayMessageField = ReflectionHelper.findField(GuiIngame::class.java, "field_175199_z", "overlayMessage")
        } catch (e: Exception) {
            println("Ошибка при поиске поля ActionBar (overlayMessage): ${e.message}")
        }

        try {
            displayedTitleField = ReflectionHelper.findField(GuiIngame::class.java, "field_175201_x", "displayedTitle")
        } catch (e: Exception) {
            println("Ошибка при поиске поля Title (displayedTitle): ${e.message}")
        }

        try {
            displayedSubTitleField = ReflectionHelper.findField(GuiIngame::class.java, "field_175200_y", "displayedSubTitle")
        } catch (e: Exception) {
            println("Ошибка при поиске поля Subtitle (displayedSubTitle): ${e.message}")
        }

        try {
            clickMouseMethod = Minecraft::class.java.getDeclaredMethod("clickMouse").apply {
                isAccessible = true
            }
        } catch (e: Exception) {
            println("Ошибка при поиске метода clickMouse: ${e.message}")
        }
    }

    private fun getFieldText(field: Field?): String? {
        return try {
            field?.get(mc.ingameGUI) as? String
        } catch (e: Exception) {
            println("Ошибка при чтении значения поля: ${e.message}")
            null
        }
    }

    private fun cleanText(text: String): String {
        return text.replace(Regex("§[0-9a-fk-or]"), "").trim()
    }

    private fun checkTitles() {
        val subtitleText = getFieldText(displayedSubTitleField)?.let { cleanText(it) }

        if (!subtitleText.isNullOrEmpty() && subtitleText != lastSubtitle) {
            println("Новый Subtitle: $subtitleText")
            lastSubtitle = subtitleText
            performActionBasedOnSubTitle(subtitleText)
        }
    }

    private fun performActionBasedOnSubTitle(subtitle: String) {
        when {
            subtitle.contains("CLICK UP", ignoreCase = true) -> performAction("clickup")
            subtitle.contains("JUMP", ignoreCase = true) -> performAction("jump")
            else -> println("Нет действий для SubTitle: $subtitle")
        }
    }

    private fun performAction(action: String) {
        when (action.lowercase()) {
            "clickup" -> {
                targetPitch = -90f // Устанавливаем цель для движения головы вверх
                targetYaw = mc.thePlayer.rotationYaw // Сохраняем текущий угол поворота головы
                isPerformingClick = true
            }
            "jump" -> {
                mc.thePlayer.jump() // Прыжок
            }
            else -> {
                println("Unknown action: $action")
            }
        }
    }

    @SubscribeEvent
    fun onClientTick(event: TickEvent.ClientTickEvent) {
        if (event.phase == TickEvent.Phase.START) {
            checkTitles()
        }
    }
}
