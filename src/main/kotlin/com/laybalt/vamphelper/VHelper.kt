package com.laybalt.vamphelper

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiIngame
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent
import net.minecraftforge.fml.common.gameevent.TickEvent
import net.minecraftforge.client.event.RenderGameOverlayEvent
import net.minecraft.util.ChatComponentText
import net.minecraft.util.StringUtils
import net.minecraftforge.fml.relauncher.ReflectionHelper
import java.lang.reflect.Field

class VHelper {
    private val mc: Minecraft = Minecraft.getMinecraft()
    private var isInRiftDimension = false
    private var hasActiveSlayerQuest = false
    private var isBossFight = false

    private var overlayMessageField: Field? = null
    private var displayedTitleField: Field? = null
    private var displayedSubTitleField: Field? = null
    private var clickMouseMethod: java.lang.reflect.Method? = null

    private var isClickingUp = false
    private var isClickingDown = false

    private var tickCounter = 0
    private var originalPitch: Float = 0f
    private var lastSubtitle: String? = null
    private var actionCompleted = false
    private var delayCounter = 0
    private var isDelayed = false

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

    @SubscribeEvent
    fun onRenderGameOverlay(event: RenderGameOverlayEvent.Text) {
        if (event.type != RenderGameOverlayEvent.ElementType.TEXT) return

        isInRiftDimension = false
        hasActiveSlayerQuest = false
        isBossFight = false

        val scoreboard = mc.theWorld?.scoreboard ?: return
        val objective = scoreboard.getObjectiveInDisplaySlot(1) ?: return
        val scoreboardValues = getScoreboardValues(objective.name)

        for (scoreInfo in scoreboardValues) {
            val cleanedText = cleanText(scoreInfo)
            when {
                cleanedText.contains("Rift Dimension", true) -> isInRiftDimension = true
                cleanedText.contains("Slayer Quest", true) -> hasActiveSlayerQuest = true
                cleanedText.contains("Slay the boss!", true) -> isBossFight = true
            }
        }
    }

    @SubscribeEvent
    fun onClientTick(event: TickEvent.ClientTickEvent) {
        if (event.phase == TickEvent.Phase.START) {
            checkTitles()
            if (isInRiftDimension && hasActiveSlayerQuest && isBossFight) {
                handleDelay()
                handleClickUp()
                handleClickDown()
            }
        }
    }

    private fun handleDelay() {
        if (isDelayed) {
            delayCounter++
            if (delayCounter >= 10) { // Задержка в 10 тиков
                isDelayed = false
                performActionBasedOnSubTitle(lastSubtitle ?: "")
            }
        }
    }

    private fun checkTitles() {
        val subtitleText = getFieldText(displayedSubTitleField)?.let { cleanText(it) }

        if (!subtitleText.isNullOrEmpty() && (!actionCompleted || subtitleText != lastSubtitle)) {
            lastSubtitle = subtitleText
            println("Новый Subtitle: $subtitleText")
            isDelayed = true
            delayCounter = 0
            actionCompleted = true
        }
    }

    private fun performActionBasedOnSubTitle(subtitle: String) {
        when {
            subtitle.contains("IMPEL: CLICK UP", ignoreCase = true) -> performAction("clickup")
            subtitle.contains("IMPEL: CLICK DOWN", ignoreCase = true) -> performAction("clickdown")
            subtitle.contains("IMPEL: JUMP", ignoreCase = true) -> performAction("jump")
            subtitle.contains("IMPEL: SNEAK", ignoreCase = true) -> performAction("sneak")

            else -> println("Unknown Impel action: $subtitle")
        }
    }

    private fun performAction(action: String) {
        val player = mc.thePlayer ?: return // Проверка, что игрок существует
        when (action.lowercase()) {
            "clickup" -> {
                if (!isClickingUp) {
                    isClickingUp = true
                    tickCounter = 0
                    originalPitch = player.rotationPitch // Сохранение текущего угла взгляда
                    player.rotationPitch = -90f // Изменение угла взгляда на верх
                }
            }
            "clickdown" -> {
                if (!isClickingDown) {
                    isClickingDown = true
                    tickCounter = 0
                    originalPitch = player.rotationPitch
                    player.rotationPitch = 0f
                }
            }
            "jump" -> {
                player.jump() // Прыжок
            }
            "sneak" -> {
                player.isSneaking
            }
            else -> {
                println("Unknown action: $action")
            }
        }
    }

    private fun handleClickUp() {
        if (isClickingUp) {
            tickCounter++
            when (tickCounter) {
                6 -> try {
                    clickMouseMethod?.invoke(mc) // Клик через рефлексию метода clickMouse
                } catch (e: Exception) {
                    println("Ошибка при выполнении clickMouse: ${e.message}")
                }
                13 -> {
                    mc.thePlayer?.rotationPitch = originalPitch // Возврат к исходному углу взгляда через 10 тиков
                    isClickingUp = false
                    println("CLICK UP выполнен")
                }
            }
        }

        // Сброс actionCompleted, если Subtitle исчез
        if (lastSubtitle != getFieldText(displayedSubTitleField)?.let { cleanText(it) }) {
            actionCompleted = false
        }
    }

    private fun handleClickDown() {
        if (isClickingDown) {
            tickCounter++
            when (tickCounter) {
                6 -> try {
                    clickMouseMethod?.invoke(mc) // Клик через рефлексию метода clickMouse
                } catch (e: Exception) {
                    println("Ошибка при выполнении clickMouse: ${e.message}")
                }
                13 -> {
                    mc.thePlayer?.rotationPitch = originalPitch // Возврат к исходному углу взгляда через 10 тиков
                    isClickingUp = false
                    println("CLICK DOWN выполнен")
                }
            }
        }

        // Сброс actionCompleted, если Subtitle исчез
        if (lastSubtitle != getFieldText(displayedSubTitleField)?.let { cleanText(it) }) {
            actionCompleted = false
        }
    }

    private fun getScoreboardValues(objectiveName: String): List<String> {
        val scoreboard = mc.theWorld?.scoreboard ?: return emptyList()
        val objective = scoreboard.getObjective(objectiveName) ?: return emptyList()
        return scoreboard.getSortedScores(objective).map { it.playerName }
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
}
