// Blaze settings, Puzzle Timers and Draft Prompt ported from Odin (https://github.com/odtheking/Odin), BSD-3-Clause, Copyright (c) 2025 odtheking
package com.laybalt.features.impl.dungeon.solvers

import com.laybalt.config.types.*
import com.laybalt.event.impl.*
import com.laybalt.features.Feature
import com.laybalt.features.impl.dungeon.solvers.puzzles.*
import com.laybalt.utils.ColorUtils.withAlpha
import com.laybalt.utils.location.LocationUtils.inBoss
import com.laybalt.utils.render.Render2D.drawCenteredString
import com.laybalt.utils.render.RenderHelper.width
import com.laybalt.config.PersonalBest
import com.laybalt.utils.ChatUtils
import com.laybalt.utils.NumbersUtils.toFixed
import com.laybalt.utils.dungeons.map.core.RoomState
import com.laybalt.utils.dungeons.map.core.RoomType
import com.laybalt.utils.dungeons.map.utils.ScanUtils
import com.laybalt.utils.location.LocationUtils
import java.awt.Color
import java.util.concurrent.ConcurrentHashMap

object PuzzleSolvers: Feature() {
    val blaze by ToggleSetting("Enabled").section("Blaze Solver")
        .withDescription("Solves the Higher Or Lower (Blaze) puzzle: boxes the blazes in kill order and connects the next ones with lines.")
    val blazeLineNext by ToggleSetting("Blaze Next Line", true)
        .withDescription("Draws a line from each blaze to the one you have to kill after it.")
        .showIf { blaze.value }
    val blazeLineAmount by SliderSetting("Blaze Lines", 1, 1, 10, 1)
        .withDescription("How many of the upcoming blazes are connected with lines.")
        .showIf { blaze.value && blazeLineNext.value }
    val blazeLineWidth by SliderSetting("Blaze Line Width", 2f, 0.5f, 5f, 0.1f)
        .withDescription("Thickness of the blaze lines.")
        .showIf { blaze.value && blazeLineNext.value }
    val blazeStyle by DropdownSetting("Blaze Box Style", 2, listOf("Fill", "Outline", "Filled Outline"))
        .withDescription("How the boxes around the blazes are drawn.")
        .showIf { blaze.value }
    val blazePhase by ToggleSetting("Blaze Through Walls", false)
        .withDescription("Renders the blaze boxes and lines through blocks.")
        .showIf { blaze.value }
    val blazeFirstColor by ColorSetting("First Blaze Color", Color(85, 255, 85).withAlpha(191))
        .withDescription("Colour of the blaze you have to kill now.")
        .showIf { blaze.value }
    val blazeSecondColor by ColorSetting("Second Blaze Color", Color(255, 170, 0).withAlpha(191))
        .withDescription("Colour of the second blaze in kill order.")
        .showIf { blaze.value }
    val blazeThirdColor by ColorSetting("Third Blaze Color", Color(255, 85, 85).withAlpha(191))
        .withDescription("Colour of the third blaze in kill order.")
        .showIf { blaze.value }
    val blazeOtherColor by ColorSetting("Other Blazes Color", Color.WHITE.withAlpha(77))
        .withDescription("Colour of every remaining blaze after the third.")
        .showIf { blaze.value }
    val blazeSendComplete by ToggleSetting("Send Blaze Complete", false)
        .withDescription("Sends a party chat message when the last blaze dies.")
        .showIf { blaze.value }
    val blazeCompleteMessage by TextInputSetting("Blaze Complete Message", "Blaze puzzle solved!")
        .withDescription("The party chat message sent when the Blaze puzzle is finished.")
        .showIf { blaze.value && blazeSendComplete.value }
    val blazeReset by ButtonSetting("Reset Blaze") { BlazeSolver.reset() }
        .withDescription("Clears the Blaze solver state, use it if the solver gets stuck.")
        .showIf { blaze.value }

    val boulder by ToggleSetting("Enabled ").section("Boulder Solver")
    val showAll by ToggleSetting("Show All Moves", false).showIf { boulder.value }
    val boxColor by ColorSetting("Boulder Box Color", Color.BLUE.withAlpha(100)).showIf { boulder.value }
    val clickColor by ColorSetting("Click Color", Color.RED.withAlpha(100)).showIf { boulder.value }

    val creeper by ToggleSetting("Enabled  ").section("Creeper Beam Solver")
    val renderLines by ToggleSetting("Render Lines", true).showIf { creeper.value }
    val phase by ToggleSetting("Phase Through Walls", true).showIf { creeper.value }

    val quiz by ToggleSetting("Enabled   ").section("Quiz Solver")
    val answerColor by ColorSetting("Answer Color", Color.CYAN.withAlpha(128)).showIf { quiz.value }
    val quizTimer by ToggleSetting("Quiz Timer", true).showIf { quiz.value }
    val quizBlockWrongClicks by ToggleSetting("Block Wrong Clicks", true)
        .withDescription("Prevents clicking a wrong Quiz answer. &eSneak to override.")
        .showIf { quiz.value }

    val tpmaze by ToggleSetting("Enabled    ").section("Teleport Maze Solver")
    val correctTpPadColor by ColorSetting("Correct Pad Color", Color.GREEN).showIf { tpmaze.value }
    val wrongTpPadColor by ColorSetting("Wrong Pad Color", Color.RED).showIf { tpmaze.value }

    val weirdos by ToggleSetting("Enabled     ").section("Three Weirdos Solver")
    val removeChests by ToggleSetting("Hide Wrong Chests", true).showIf { weirdos.value }
    val colorCorrect by ColorSetting("Correct Chest Color", Color.GREEN).showIf { weirdos.value }
    val colorWrong by ColorSetting("Wrong Chest Color", Color.RED).showIf { weirdos.value }

    val ttt by ToggleSetting("Enabled      ").section("TicTacToe Solver")
    val preventMissClick by ToggleSetting("Prevent Miss Click", true).showIf { ttt.value }
    val color by ColorSetting("Highlight Color", Color.GREEN).showIf { ttt.value }
    val prediction by ToggleSetting("Prediction").showIf { ttt.value }
    val predictionColor by ColorSetting("Prediction Color", Color.ORANGE).showIf { ttt.value && prediction.value }

    val water by ToggleSetting("Enabled       ").section("Water Board Solver")
    val currentClickColor by ColorSetting("Click Color", Color.GREEN).showIf { water.value }
    val nextColor by ColorSetting("Next Click Color", Color.YELLOW).showIf { water.value }

    val icefill by ToggleSetting("Enabled        ").section("Ice Fill Solver")
    val icefillColor by ColorSetting("Click Color", Color.GREEN).showIf { icefill.value }

    val icepath by ToggleSetting("Enabled         ").section("Ice Path Solver")
    val icePathFirstColor by ColorSetting("Next Segment Color", Color.GREEN).showIf { icepath.value }
    val icePathColor by ColorSetting("Segments Color", Color.RED).showIf { icepath.value }

    val puzzleTimers by ToggleSetting("Puzzle Timers", true).section("Puzzle Utilities")
        .withDescription("Prints how long each puzzle took from the moment you first entered its room, together with your personal best.")
    val draftPrompt by ToggleSetting("Draft Prompt", true)
        .withDescription("When someone fails a puzzle, prints a clickable chat message that fetches an Architect's First Draft from your sacks.")

    private val failRegex = Regex("""^PUZZLE FAIL! (\w{1,16}) .+$|^\[STATUE] Oruo the Omniscient: (\w{1,16}) chose the wrong answer! I shall never forget this moment of misrememberance\.$""")

    private data class PuzzleTimer(val timeEntered: Long = System.currentTimeMillis(), var sentMessage: Boolean = false)
    private val puzzleTimersMap = ConcurrentHashMap<String, PuzzleTimer>()

    /** Minimum plausible solve time per puzzle room (seconds); faster "solves" are ignored as bogus. From Odin's Puzzle.timeToBeat. */
    private val timeToBeat = mapOf(
        "Lower Blaze" to 6, "Higher Blaze" to 6, "Creeper Beams" to 6, "Three Weirdos" to 2,
        "Water Board" to 10, "Teleport Maze" to 6, "Boulder" to 6, "Ice Fill" to 10, "Quiz" to 10
    )

    /**
     * Called when a puzzle is detected as solved, either by a solver (Blaze) or by the room turning green on the map.
     * Prints the solve time and the personal best once per puzzle room.
     */
    fun onPuzzleComplete(puzzleName: String) {
        if (! enabled || ! puzzleTimers.value) return
        val timer = puzzleTimersMap[puzzleName] ?: return
        if (timer.sentMessage) return

        val puzzleTime = (System.currentTimeMillis() - timer.timeEntered) / 1000f
        if (puzzleTime < (timeToBeat[puzzleName] ?: 0)) return
        timer.sentMessage = true

        val key = "puzzle_${puzzleName.lowercase().replace(' ', '_')}"
        val oldPB = PersonalBest.getPB(key)?.toFloat()
        val isNewPB = PersonalBest.checkAndSetPB(key, puzzleTime)
        val pbText = when {
            isNewPB && oldPB != null -> "§7(§d§lNew PB§r§7) Old PB was §8${oldPB.toFixed(2)}s"
            isNewPB -> "§7(§d§lNew PB§r§7)"
            else -> "§8(§7${oldPB?.toFixed(2)}s§8)"
        }

        ChatUtils.modMessage("§a$puzzleName §7solved in §6${puzzleTime.toFixed(2)}s§7! $pbText")
    }

    override fun init() {
        val puzzles = PuzzleSolver::class.sealedSubclasses.mapNotNull { it.objectInstance }

        hudElement(
            name = "Quiz Timer",
            enabled = { quiz.value && quizTimer.value },
            shouldDraw = { QuizSolver.shouldShowTimer },
            centered = { true }
        ) { ctx, example ->
            val text = QuizSolver.timerText(example)
            ctx.drawCenteredString(text, 0f, 0f)
            return@hudElement text.width().toFloat() to 9f
        } defaults {
            scale = 3f
        }

        register<DungeonEvent.RoomEvent.onStateChange> { puzzles.forEach { if (it.enabled) it.onStateChange(event) } }
        register<PlayerInteractEvent.RIGHT_CLICK.BLOCK> { puzzles.forEach { if (it.enabled) it.onInteract(event) } }
        register<MainThreadPacketReceivedEvent.Pre> { puzzles.forEach { if (it.enabled) it.onPacket(event) } }
        register<DungeonEvent.RoomEvent.onEnter> { puzzles.forEach { if (it.enabled && ! inBoss) it.onRoomEnter(event) } }
        register<RenderWorldEvent> { puzzles.forEach { if (it.enabled) it.onRenderWorld(event.ctx) } }
        register<DungeonEvent.RoomEvent.onExit> { puzzles.forEach { if (it.enabled) it.onRoomExit() } }
        register<ChatMessageEvent> { puzzles.forEach { if (it.enabled) it.onChat(event) } }
        register<TickEvent.Server> { puzzles.forEach { if (it.enabled) it.onTick() } }
        register<WorldChangeEvent> { puzzles.forEach { if (it.enabled) it.reset() } }
        register<DungeonEvent.BossEnterEvent> { puzzles.forEach { if (it.enabled) it.reset() } }
        register<TickEvent.End> { puzzles.forEach { if (it.enabled) it.onClientTick() } }

        register<DungeonEvent.RoomEvent.onEnter> {
            if (! puzzleTimers.value || inBoss || event.room.data.type != RoomType.PUZZLE) return@register
            puzzleTimersMap.putIfAbsent(event.room.name, PuzzleTimer())
        }

        register<DungeonEvent.RoomEvent.onStateChange> {
            if (event.room.data.type != RoomType.PUZZLE || event.newState != RoomState.GREEN) return@register
            if (event.oldState != RoomState.DISCOVERED && event.oldState != RoomState.FAILED) return@register
            onPuzzleComplete(event.room.name)
        }

        register<ChatMessageEvent> {
            if (! draftPrompt.value || ! LocationUtils.inDungeon || inBoss) return@register
            if (ScanUtils.currentRoom?.data?.type != RoomType.PUZZLE) return@register
            if (! failRegex.matches(event.unformattedText)) return@register
            ChatUtils.clickableChat(
                "§7Click §ehere §7to fetch an Architect's First Draft",
                prefix = true,
                command = "gfs ARCHITECT_FIRST_DRAFT 1",
                hover = "§eClick to fetch an Architect's First Draft from your sacks"
            )
        }

        register<WorldChangeEvent> { puzzleTimersMap.clear() }
    }
}