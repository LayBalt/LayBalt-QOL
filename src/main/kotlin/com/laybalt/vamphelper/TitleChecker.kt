package com.laybalt.vamphelper

/**
 * Disabled stub. The whole body was already commented out in the Forge version; it is kept below
 * (with the names updated to the Fabric / Minecraft 26.2 API) for reference. Main still calls
 * register(), which does nothing.
 */
class TitleChecker {
    fun register() {
        // intentionally empty - see the commented-out prototype below
    }

//    private val mc: Minecraft = Minecraft.getInstance()
//
//    private var isClickingUp = false
//    private var tickCounter = 0
//    private var originalPitch: Float = 0f
//    private var lastSubtitle: String? = null
//    private var actionCompleted = false
//    private var delayCounter = 0
//    private var isDelayed = false
//
//    fun register() {
//        ClientTickEvents.START_CLIENT_TICK.register { _ -> onClientTick() }
//    }
//
//    // Old: reflection on GuiIngame.displayedTitle / displayedSubTitle / overlayMessage.
//    // New: the access-widened Hud fields mc.gui.hud.title / .subtitle / .overlayMessageString (Components).
//    private fun getSubtitleText(): String? {
//        return try {
//            mc.gui.hud.subtitle?.string
//        } catch (e: Exception) {
//            Main.LOGGER.warn("Ошибка при чтении значения поля: {}", e.message)
//            null
//        }
//    }
//
//    private fun cleanText(text: String): String {
//        return text.replace(Regex("§[0-9a-fk-or]"), "").trim()
//    }
//
//    private fun checkTitles() {
//        val subtitleText = getSubtitleText()?.let { cleanText(it) }
//
//        if (!subtitleText.isNullOrEmpty() && (!actionCompleted || subtitleText != lastSubtitle)) {
//            lastSubtitle = subtitleText
//            Main.LOGGER.info("Новый Subtitle: {}", subtitleText)
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
//            else -> Main.LOGGER.debug("Нет действий для SubTitle: {}", subtitle)
//        }
//    }
//
//    private fun performAction(action: String) {
//        val player = mc.player ?: return // Проверка, что игрок существует
//        when (action.lowercase()) {
//            "clickup" -> {
//                if (!isClickingUp) {
//                    isClickingUp = true
//                    tickCounter = 0
//                    originalPitch = player.xRot // Сохранение текущего угла взгляда
//                    player.xRot = -90f // Изменение угла взгляда на верх
//                }
//            }
//            "jump" -> {
//                player.jumpFromGround() // Прыжок
//            }
//            else -> {
//                Main.LOGGER.debug("Unknown action: {}", action)
//            }
//        }
//    }
//
//    private fun onClientTick() {
//        checkTitles()
//        handleDelay()
//        handleClickUp()
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
//                6 -> ClientActions.leftClick() // Old: clickMouse() via reflection
//                13 -> {
//                    mc.player?.xRot = originalPitch // Возврат к исходному углу взгляда через 10 тиков
//                    isClickingUp = false
//                    Main.LOGGER.info("CLICK UP выполнен")
//                }
//            }
//        }
//
//        // Сброс actionCompleted, если Subtitle исчез
//        if (lastSubtitle != getSubtitleText()?.let { cleanText(it) }) {
//            actionCompleted = false
//        }
//    }
}
