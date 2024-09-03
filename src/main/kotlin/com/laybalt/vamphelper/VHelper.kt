package com.laybalt.vamphelper

import net.minecraft.client.Minecraft
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent
import net.minecraftforge.fml.common.gameevent.TickEvent
import net.minecraft.client.settings.KeyBinding
import net.minecraftforge.client.event.RenderGameOverlayEvent
import com.laybalt.GUI.LBQConfig
import net.minecraft.util.StringUtils
import net.minecraft.entity.item.EntityArmorStand
import net.minecraft.util.AxisAlignedBB

class VHelper {
    private val mc: Minecraft = Minecraft.getMinecraft()
    private var isInRiftDimension = false
    private var hasActiveSlayerQuest = false
    private var isBossFight = false
    private var currentTitle: String = ""
    private var currentSubtitle: String = ""
    private var impelActionRequired: String? = null
    private var sneakTimer: Int = 0
    private var rotationSteps: Int = 0
    private var targetYaw: Float = 0f
    private var targetPitch: Float = 0f
    private var originalPitch: Float = 0f

    private var twinClawsUsedInCurrentFight = false
    private var twinClawsTimer = 0
    private var isPerformingTwinClaws = false
    private var twinClawsStage = 0

    private var rightClickTimer = 0
    private var isPerformingRightClick = false

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
                cleanedText.contains("Rift Dimension") -> {
                    isInRiftDimension = true
                }
                cleanedText.contains("Slayer Quest") -> {
                    hasActiveSlayerQuest = true
                }
                cleanedText.contains("Slay the boss!") -> {
                    isBossFight = true
                }
            }
        }
    }

    @SubscribeEvent
    fun onClientTick(event: TickEvent.ClientTickEvent) {
        if (event.phase != TickEvent.Phase.START) return
        val player = mc.thePlayer ?: return
        val world = mc.theWorld ?: return

        updateTitleAndSubtitle()
        handleSneak()
        handleRotation()
        handleRightClick()
        handleTwinClawsSequence()

        if (isInRiftDimension && hasActiveSlayerQuest && isBossFight) {
            checkHolograms()
            checkTitles()
        } else {
            twinClawsUsedInCurrentFight = false
        }
    }

    private fun updateTitleAndSubtitle() {
        val guiIngame = mc.ingameGUI
        if (guiIngame != null) {
            try {
                val titleField = guiIngame.javaClass.getDeclaredField("displayedTitle")
                titleField.isAccessible = true
                currentTitle = StringUtils.stripControlCodes(titleField.get(guiIngame) as? String ?: "")

                val subtitleField = guiIngame.javaClass.getDeclaredField("displayedSubTitle")
                subtitleField.isAccessible = true
                currentSubtitle = StringUtils.stripControlCodes(subtitleField.get(guiIngame) as? String ?: "")
            } catch (e: Exception) {
                // Игнорируем ошибку, если поля не найдены
            }
        }
    }

    private fun handleSneak() {
        if (sneakTimer > 0) {
            sneakTimer--
            if (sneakTimer == 0) {
                KeyBinding.setKeyBindState(mc.gameSettings.keyBindSneak.keyCode, false)
            }
        }
    }

    private fun handleRotation() {
        if (rotationSteps > 0) {
            val player = mc.thePlayer ?: return
            val progress = (10 - rotationSteps + 1).toFloat() / 10
            val newPitch = originalPitch + (targetPitch - originalPitch) * progress

            player.setPositionAndRotation(
                player.posX, player.posY, player.posZ,
                targetYaw, newPitch
            )

            rotationSteps--

            if (rotationSteps == 0) {
                player.rotationPitch = originalPitch
                performRightClick()
            }
        }
    }

    private fun checkHolograms() {
        if (!isInRiftDimension || !hasActiveSlayerQuest || !isBossFight) {
            return
        }

        val player = mc.thePlayer ?: return
        val world = mc.theWorld ?: return
        val searchRadius = 10.0

        val boundingBox = AxisAlignedBB(
            player.posX - searchRadius,
            player.posY - searchRadius,
            player.posZ - searchRadius,
            player.posX + searchRadius,
            player.posY + searchRadius,
            player.posZ + searchRadius
        )

        val entities = world.getEntitiesWithinAABB(EntityArmorStand::class.java, boundingBox)

        var actionRequired: String? = null
        var bossNameFound = false
        var spawnedByPlayerFound = false
        var twinClawsFound = false

        for (entity in entities) {
            val customName = cleanText(entity.customNameTag ?: continue)

            when {
                customName.contains("Bloodfiend", ignoreCase = true) -> {
                    bossNameFound = true
                }
                customName.startsWith("Spawned by:", ignoreCase = true) && customName.contains(player.name, ignoreCase = true) -> {
                    spawnedByPlayerFound = true
                }
            }

            if (customName.contains("TWINCLAWS", ignoreCase = true)) {
                twinClawsFound = true
            }

            if (customName.contains("IMPEL", ignoreCase = true)) {
                actionRequired = "impel"
            }
        }

        if (bossNameFound && spawnedByPlayerFound) {
            if (twinClawsFound && !twinClawsUsedInCurrentFight) {
                performTwinClaws()
                twinClawsUsedInCurrentFight = true
            } else if (actionRequired == "impel") {
                checkForImpelAction()
            }
        } else {
            twinClawsUsedInCurrentFight = false
        }
    }

    private fun checkTitles() {
        val cleanSubtitle = cleanText(currentSubtitle)

        if (cleanSubtitle.contains("Impel:", ignoreCase = true)) {
            val action = cleanSubtitle.substringAfter("Impel:", "").trim().toLowerCase()
            impelActionRequired = action
            checkForImpelAction()
        }
    }

    private fun checkForImpelAction() {
        impelActionRequired?.let { action ->
            when {
                action.contains("up") -> performAction("clickup")
                action.contains("down") -> performAction("clickdown")
                action.contains("jump") -> performAction("jump")
                action.contains("sneak") || action.contains("snake") -> performAction("snake")
                else -> VHelperMessage.sendMessage("Unknown Impel action: $action")
            }
            impelActionRequired = null
        } ?: run {
        }
    }

    private fun performTwinClaws() {
        if (!LBQConfig.vampireSlayerAutoTwinClaws) {
            return
        }
        if (isPerformingTwinClaws) {
            return
        }
        val player = mc.thePlayer ?: return

        isPerformingTwinClaws = true
        twinClawsStage = 0
        twinClawsTimer = 0
    }

    private fun handleTwinClawsSequence() {
        if (!isPerformingTwinClaws) return

        twinClawsTimer++
        when (twinClawsStage) {
            0 -> {
                if (twinClawsTimer >= 5) {  // ~250ms delay (5 ticks)
                    mc.thePlayer.inventory.currentItem = LBQConfig.vampireSlayerHolyIceSlot - 1
                    performRightClick()
                    twinClawsStage++
                    twinClawsTimer = 0
                }
            }
            1 -> {
                if (twinClawsTimer >= 5) {  // ~250ms delay (5 ticks)
                    mc.thePlayer.inventory.currentItem = LBQConfig.vampireSlayerSwordSlot - 1
                    isPerformingTwinClaws = false
                }
            }
        }
    }

    private fun performAction(action: String) {
        val player = mc.thePlayer ?: return
        when (action) {
            "clickdown", "clickup" -> {
                if ((action == "clickdown" && LBQConfig.vampireSlayerAutoClickDown) ||
                    (action == "clickup" && LBQConfig.vampireSlayerAutoClickUp)) {
                    targetYaw = player.rotationYaw
                    targetPitch = if (action == "clickdown") 90f else -90f
                    originalPitch = player.rotationPitch
                    rotationSteps = 10
                }
            }
            "jump" -> {
                if (LBQConfig.vampireSlayerAutoJump) {
                    player.jump()
                }
            }
            "snake" -> {
                if (LBQConfig.vampireSlayerAutoSneak) {
                    KeyBinding.setKeyBindState(mc.gameSettings.keyBindSneak.keyCode, true)
                    sneakTimer = 20
                }
            }
        }
    }

    private fun performRightClick() {
        if (!isPerformingRightClick) {
            isPerformingRightClick = true
            rightClickTimer = 0
        }
    }

    private fun handleRightClick() {
        if (!isPerformingRightClick) return

        rightClickTimer++
        if (rightClickTimer >= 1) {  // Выполняем правый клик на следующем тике
            try {
                val rightClickMouseMethod = mc.javaClass.getDeclaredMethod("rightClickMouse")
                rightClickMouseMethod.isAccessible = true
                rightClickMouseMethod.invoke(mc)
            } catch (e: Exception) {
            }
            isPerformingRightClick = false
        }
    }

    private fun getScoreboardValues(objectiveName: String): List<String> {
        val mc = Minecraft.getMinecraft()
        val world = mc.theWorld ?: return emptyList()
        val scoreboard = world.scoreboard
        val objective = scoreboard.getObjective(objectiveName) ?: return emptyList()

        val scores = scoreboard.getSortedScores(objective)
        return scores.map { score ->
            val scorePlayerName = scoreboard.getPlayersTeam(score.playerName)?.formatString(score.playerName) ?: score.playerName
            "$scorePlayerName: ${score.scorePoints}"
        }
    }

    private fun cleanText(text: String): String {
        return text.replace(Regex("§[0-9a-fk-or]"), "")
            .replace(Regex("[^\\x00-\\x7F]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}