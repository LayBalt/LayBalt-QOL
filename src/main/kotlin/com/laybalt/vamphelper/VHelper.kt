package com.laybalt.vamphelper

import net.minecraft.client.Minecraft
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent
import net.minecraftforge.fml.common.gameevent.TickEvent
import net.minecraftforge.client.event.RenderGameOverlayEvent
import net.minecraft.util.ChatComponentText
import net.minecraft.util.StringUtils

class VHelper {
    private val mc: Minecraft = Minecraft.getMinecraft()
    private var isInRiftDimension = false
    private var hasActiveSlayerQuest = false
    private var isBossFight = false
    private var currentSubtitle: String = ""
    private var impelActionRequired: String? = null

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
        if (event.phase != TickEvent.Phase.START) return

        updateSubtitle()

        if (isInRiftDimension && hasActiveSlayerQuest && isBossFight) {
            checkSubtitle()
        }
    }

    private fun updateSubtitle() {
        val guiIngame = mc.ingameGUI ?: return
        try {
            val subtitleField = guiIngame.javaClass.getDeclaredField("displayedSubTitle")
            subtitleField.isAccessible = true
            currentSubtitle = StringUtils.stripControlCodes(subtitleField.get(guiIngame) as? String ?: "")
        } catch (e: Exception) {
            // Игнорируем ошибку, если поле не найдено
        }
    }

    private fun checkSubtitle() {
        val player = mc.thePlayer ?: return
        val cleanSubtitle = cleanText(currentSubtitle)

        if (cleanSubtitle.contains(player.name, true) && cleanSubtitle.contains("Impel:", true)) {
            impelActionRequired = cleanSubtitle.substringAfter("Impel:", "").trim().lowercase()
            checkForImpelAction()
        }
    }

    private fun checkForImpelAction() {
        impelActionRequired?.let { action ->
            when {
                action.contains("up") -> performAction("clickup")
                action.contains("down") -> performAction("clickdown")
                action.contains("jump") -> performAction("jump")
                action.contains("sneak") || action.contains("snake") -> performAction("sneak")
                else -> sendMessageToChat("Unknown Impel action: $action")
            }
            impelActionRequired = null
        }
    }

    private fun performAction(action: String) {
        sendMessageToChat("Performing action: $action")
    }

    private fun sendMessageToChat(message: String) {
        mc.thePlayer?.addChatMessage(ChatComponentText(message))
    }

    private fun getScoreboardValues(objectiveName: String): List<String> {
        val scoreboard = mc.theWorld?.scoreboard ?: return emptyList()
        val objective = scoreboard.getObjective(objectiveName) ?: return emptyList()
        return scoreboard.getSortedScores(objective).map { it.playerName }
    }

    private fun cleanText(text: String): String {
        return text.replace(Regex("§[0-9a-fk-or]"), "").replace(Regex("\\s+"), " ").trim()
    }
}
