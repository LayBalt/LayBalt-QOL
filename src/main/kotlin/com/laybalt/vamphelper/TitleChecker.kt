package com.laybalt.vamphelper

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiIngame
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent
import net.minecraftforge.fml.common.gameevent.TickEvent
import net.minecraftforge.fml.relauncher.ReflectionHelper
import java.lang.reflect.Field

class TitleChecker {
//    private val mc: Minecraft = Minecraft.getMinecraft()
//
//    private var overlayMessageField: Field? = null
//    private var displayedTitleField: Field? = null
//    private var displayedSubTitleField: Field? = null
//    private var clickMouseMethod: java.lang.reflect.Method? = null
//
//    private var isClickingUp = false
//    private var tickCounter = 0
//    private var originalPitch: Float = 0f
//    private var lastSubtitle: String? = null
//    private var actionCompleted = false
//    private var delayCounter = 0
//    private var isDelayed = false
//
//    init {
//        try {
//            overlayMessageField = ReflectionHelper.findField(GuiIngame::class.java, "field_175199_z", "overlayMessage")
//        } catch (e: Exception) {
//            println("Ошибка при поиске поля ActionBar (overlayMessage): ${e.message}")
//        }
//
//        try {
//            displayedTitleField = ReflectionHelper.findField(GuiIngame::class.java, "field_175201_x", "displayedTitle")
//        } catch (e: Exception) {
//            println("Ошибка при поиске поля Title (displayedTitle): ${e.message}")
//        }
//
//        try {
//            displayedSubTitleField = ReflectionHelper.findField(GuiIngame::class.java, "field_175200_y", "displayedSubTitle")
//        } catch (e: Exception) {
//            println("Ошибка при поиске поля Subtitle (displayedSubTitle): ${e.message}")
//        }
//
//        try {
//            clickMouseMethod = Minecraft::class.java.getDeclaredMethod("clickMouse").apply {
//                isAccessible = true
//            }
//        } catch (e: Exception) {
//            println("Ошибка при поиске метода clickMouse: ${e.message}")
//        }
//    }
//
//    private fun getFieldText(field: Field?): String? {
//        return try {
//            field?.get(mc.ingameGUI) as? String
//        } catch (e: Exception) {
//            println("Ошибка при чтении значения поля: ${e.message}")
//            null
//        }
//    }
//
//    private fun cleanText(text: String): String {
//        return text.replace(Regex("§[0-9a-fk-or]"), "").trim()
//    }
//
//    private fun checkTitles() {
//        val subtitleText = getFieldText(displayedSubTitleField)?.let { cleanText(it) }
//
//        if (!subtitleText.isNullOrEmpty() && (!actionCompleted || subtitleText != lastSubtitle)) {
//            lastSubtitle = subtitleText
//            println("Новый Subtitle: $subtitleText")
//            isDelayed = true
//            delayCounter = 0
//            actionCompleted = true
//        }
//    }
//
//    private fun performActionBasedOnSubTitle(subtitle: String) {
//        when {
//            subtitle.contains("CLICK UP", ignoreCase = true) -> performAction("clickup")
//            subtitle.contains("JUMP", ignoreCase = true) -> performAction("jump")
//            else -> println("Нет действий для SubTitle: $subtitle")
//        }
//    }
//
//    private fun performAction(action: String) {
//        val player = mc.thePlayer ?: return // Проверка, что игрок существует
//        when (action.lowercase()) {
//            "clickup" -> {
//                if (!isClickingUp) {
//                    isClickingUp = true
//                    tickCounter = 0
//                    originalPitch = player.rotationPitch // Сохранение текущего угла взгляда
//                    player.rotationPitch = -90f // Изменение угла взгляда на верх
//                }
//            }
//            "jump" -> {
//                player.jump() // Прыжок
//            }
//            else -> {
//                println("Unknown action: $action")
//            }
//        }
//    }
//
//    @SubscribeEvent
//    fun onClientTick(event: TickEvent.ClientTickEvent) {
//        if (event.phase == TickEvent.Phase.START) {
//            checkTitles()
//            handleDelay()
//            handleClickUp()
//        }
//    }
//
//    private fun handleDelay() {
//        if (isDelayed) {
//            delayCounter++
//            if (delayCounter >= 10) { // Задержка в 10 тиков
//                isDelayed = false
//                performActionBasedOnSubTitle(lastSubtitle ?: "")
//            }
//        }
//    }
//
//    private fun handleClickUp() {
//        if (isClickingUp) {
//            tickCounter++
//            when (tickCounter) {
//                6 -> try {
//                    clickMouseMethod?.invoke(mc) // Клик через рефлексию метода clickMouse
//                } catch (e: Exception) {
//                    println("Ошибка при выполнении clickMouse: ${e.message}")
//                }
//                13 -> {
//                    mc.thePlayer?.rotationPitch = originalPitch // Возврат к исходному углу взгляда через 10 тиков
//                    isClickingUp = false
//                    println("CLICK UP выполнен")
//                }
//            }
//        }
//
//        // Сброс actionCompleted, если Subtitle исчез
//        if (lastSubtitle != getFieldText(displayedSubTitleField)?.let { cleanText(it) }) {
//            actionCompleted = false
//        }
//    }
}
