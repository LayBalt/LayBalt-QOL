package com.laybalt.vamphelper

import com.laybalt.GUI.LBQConfig
import com.laybalt.Main
import com.laybalt.util.ClientActions
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.world.scores.DisplaySlot
import net.minecraft.world.scores.PlayerTeam
import kotlin.random.Random

/**
 * Vampire Slayer helper (Hypixel SkyBlock, Rift): reacts to the "Impel: ..." subtitle shown
 * during the boss fight by looking up/down + clicking, jumping or sneaking.
 *
 * Port notes (Forge 1.8.9 -> Fabric / Minecraft 26.2):
 * - The scoreboard scan that lived in RenderGameOverlayEvent.Text now runs at the start of
 *   every client tick, right before the subtitle check.
 * - The HUD title / subtitle / action-bar fields are read directly (access widener) instead of
 *   through reflection; they are Components, so `.string` is used.
 * - clickMouse() reflection -> ClientActions.leftClick().
 * - INTENTIONAL: everything is additionally gated on LBQConfig.vampireSlayerHelperEnabled (the
 *   key bind toggles it). The original never checked that switch.
 * - INTENTIONAL: the original "sneak" action was a no-op (it only read isSneaking); it now
 *   really holds the sneak key for 6 ticks.
 */
class VHelper {
    private val mc: Minecraft = Minecraft.getInstance()
    private var isInRiftDimension = false
    private var hasActiveSlayerQuest = false
    private var isBossFight = false

    private var isClickingUp = false
    private var isClickingDown = false

    // Sneak action state (ported from the no-op original, see class comment).
    private var isSneaking = false
    private var sneakTickCounter = 0
    // True only when the helper itself pressed the sneak key (so we never release the player's own sneak).
    private var sneakForcedByHelper = false

    private var tickCounter = 0
    private var originalPitch: Float = 0f
    private var lastSubtitle: String? = null
    private var actionCompleted = false
    private var delayCounter = 0
    private var isDelayed = false

    fun register() {
        ClientTickEvents.START_CLIENT_TICK.register { _ -> onClientTick() }
    }

    private fun onClientTick() {
        // INTENTIONAL CHANGE: the helper only works while enabled in the config / via the key bind.
        if (!LBQConfig.vampireSlayerHelperEnabled) {
            resetState()
            return
        }

        scanScoreboard()
        checkTitles()
        if (isInRiftDimension && hasActiveSlayerQuest && isBossFight) {
            handleDelay()
            handleClickUp()
            handleClickDown()
        }
        // Runs outside the boss-fight gate so the sneak key is always released again.
        handleSneak()
    }

    /** Old RenderGameOverlayEvent.Text handler: reads the sidebar to detect the Rift boss fight. */
    private fun scanScoreboard() {
        isInRiftDimension = false
        hasActiveSlayerQuest = false
        isBossFight = false

        for (scoreInfo in getScoreboardValues()) {
            val cleanedText = cleanText(scoreInfo)
            when {
                cleanedText.contains("Rift Dimension", true) -> isInRiftDimension = true
                cleanedText.contains("Slayer Quest", true) -> hasActiveSlayerQuest = true
                cleanedText.contains("Slay the boss!", true) -> isBossFight = true
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
        // Получаем текст подзаголовка и обрабатываем его
        val subtitleText = currentSubtitle()

        if (!subtitleText.isNullOrEmpty()) {
            // Если обработанный текст отличается от последнего сохранённого подзаголовка
            if (!actionCompleted || subtitleText != lastSubtitle) {
                lastSubtitle = subtitleText
                Main.LOGGER.info("Новый Subtitle: {}", subtitleText)
                isDelayed = true
                delayCounter = 0
                actionCompleted = true
            }
        }
    }

    private fun performActionBasedOnSubTitle(subtitle: String) {
        when (cleanText(subtitle)) {
            "Impel: CLICK UP" -> performAction("clickup")
            "Impel: CLICK DOWN" -> performAction("clickdown")
            "Impel: JUMP" -> performAction("jump")
            "Impel: SNEAK" -> performAction("sneak")
            else -> Main.LOGGER.debug("Unknown Impel action: {}", cleanText(subtitle))
        }
    }

    private fun performAction(action: String) {
        val player = mc.player ?: return
        when (action.lowercase()) {
            "clickup" -> {
                if (!isClickingUp) {
                    isClickingUp = true
                    tickCounter = 0
                    originalPitch = player.xRot
                    player.xRot = Random.nextFloat() * (-85f - -90f) + -90f
                }
            }

            "clickdown" -> {
                if (!isClickingDown) {
                    isClickingDown = true
                    tickCounter = 0
                    originalPitch = player.xRot
                    player.xRot = Random.nextFloat() * (90f - 85f) + 85f
                }
            }

            "jump" -> {
                player.jumpFromGround() // Прыжок
            }

            "sneak" -> {
                // The original only read player.isSneaking (no-op); now a real 6-tick sneak.
                if (!isSneaking) {
                    isSneaking = true
                    sneakTickCounter = 0
                    sneakForcedByHelper = !mc.options.keyShift.isDown
                    if (sneakForcedByHelper) mc.options.keyShift.setDown(true)
                }
            }

            else -> {
                Main.LOGGER.debug("Unknown action: {}", action)
            }
        }
    }

    private fun handleClickUp() {
        if (isClickingUp) {
            tickCounter++
            when (tickCounter) {
                6 -> ClientActions.leftClick() // Old: clickMouse() via reflection

                13 -> {
                    mc.player?.xRot = originalPitch // Возврат к исходному углу взгляда
                    isClickingUp = false
                    tickCounter = 0 // Сброс тик-счётчика
                    Main.LOGGER.info("CLICK UP выполнен")
                }
            }
        }

        // Сброс actionCompleted, если Subtitle изменился
        if (lastSubtitle != currentSubtitle()) {
            actionCompleted = false
        }
    }

    private fun handleClickDown() {
        if (isClickingDown) {
            tickCounter++
            when (tickCounter) {
                6 -> ClientActions.leftClick() // Old: clickMouse() via reflection

                13 -> {
                    mc.player?.xRot = originalPitch // Возврат к исходному углу взгляда
                    isClickingDown = false
                    tickCounter = 0 // Сброс тик-счётчика
                    Main.LOGGER.info("CLICK DOWN выполнен")
                }
            }
        }

        // Сброс actionCompleted, если Subtitle изменился
        if (lastSubtitle != currentSubtitle()) {
            actionCompleted = false
        }
    }

    private fun handleSneak() {
        if (isSneaking) {
            sneakTickCounter++
            if (sneakTickCounter >= 6) {
                releaseSneak()
                Main.LOGGER.info("SNEAK выполнен")
            }
        }
    }

    /**
     * Releases the sneak key, but only if the helper pressed it itself (a sneak the player was
     * already holding / toggled is left untouched). keyShift is a ToggleKeyMapping: with
     * "toggle crouch" enabled, setDown(false) is a no-op and setDown(true) flips the state, so
     * both modes are handled.
     */
    private fun releaseSneak() {
        isSneaking = false
        sneakTickCounter = 0
        if (!sneakForcedByHelper) return
        sneakForcedByHelper = false
        val shift = mc.options.keyShift
        if (shift.isDown) {
            shift.setDown(false)
            if (shift.isDown) shift.setDown(true) // toggle mode: flip it back off
        }
    }

    /** Called while the helper is disabled: restore the view / keys if we were mid-action. */
    private fun resetState() {
        if (isClickingUp || isClickingDown) {
            mc.player?.xRot = originalPitch
            isClickingUp = false
            isClickingDown = false
            tickCounter = 0
        }
        if (isSneaking) releaseSneak()
        isDelayed = false
        delayCounter = 0
        actionCompleted = false
        lastSubtitle = null
        isInRiftDimension = false
        hasActiveSlayerQuest = false
        isBossFight = false
    }

    /**
     * Visible sidebar lines. On Hypixel the text is team prefix + suffix, so each entry has to be
     * formatted for its team (the old code only read playerName).
     */
    private fun getScoreboardValues(): List<String> {
        val scoreboard = mc.level?.scoreboard ?: return emptyList()
        val objective = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR) ?: return emptyList()
        return scoreboard.listPlayerScores(objective).map { entry ->
            val name: Component = entry.display() ?: Component.literal(entry.owner())
            PlayerTeam.formatNameForTeam(scoreboard.getPlayersTeam(entry.owner()), name).string
        }
    }

    /** Replaces the reflective getFieldText(displayedSubTitleField). */
    private fun currentSubtitle(): String? = hudText(mc.gui.hud.subtitle)?.let { cleanText(it) }

    @Suppress("unused")
    private fun currentTitle(): String? = hudText(mc.gui.hud.title)?.let { cleanText(it) }

    @Suppress("unused")
    private fun currentActionBar(): String? = hudText(mc.gui.hud.overlayMessageString)?.let { cleanText(it) }

    private fun hudText(component: Component?): String? {
        return try {
            component?.string
        } catch (e: Exception) {
            Main.LOGGER.warn("Ошибка при чтении значения поля: {}", e.message)
            null
        }
    }

    private fun cleanText(subtitle: String): String {
        return subtitle
            .replace(Regex("§[0-9a-fk-or]"), "")
            .replace(Regex("\\s\\d+\\.\\d+s$"), "")
            .trim()
    }
}
