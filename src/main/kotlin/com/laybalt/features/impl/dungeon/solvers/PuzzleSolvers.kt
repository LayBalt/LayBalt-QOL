// Blaze settings, Puzzle Timers and Draft Prompt ported from Odin (https://github.com/odtheking/Odin), BSD-3-Clause, Copyright (c) 2025 odtheking
package com.laybalt.features.impl.dungeon.solvers

import com.laybalt.config.types.*
import com.laybalt.event.impl.*
import com.laybalt.features.Feature
import com.laybalt.features.impl.dungeon.solvers.puzzles.*
import com.laybalt.utils.ColorUtils.withAlpha
import com.laybalt.utils.location.LocationUtils.inBoss
import com.laybalt.ui.utils.Resolution
import com.laybalt.utils.render.Render2D.drawCenteredString
import com.laybalt.utils.render.RenderHelper.width
import com.laybalt.config.ConfigManager
import com.laybalt.config.PersonalBest
import com.laybalt.utils.ChatUtils
import com.laybalt.utils.NumbersUtils.toFixed
import com.laybalt.utils.dungeons.map.core.RoomState
import com.laybalt.utils.dungeons.map.core.RoomType
import com.laybalt.utils.dungeons.map.utils.ScanUtils
import com.laybalt.utils.location.LocationUtils
import java.awt.Color
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.protocol.game.ClientboundBlockEventPacket
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.monster.Blaze
import net.minecraft.world.level.block.Blocks

object PuzzleSolvers: Feature() {
    val blaze by ToggleSetting("Enabled").section("Blaze Solver")
        .withDescription("Solves the Higher Or Lower (Blaze) puzzle: boxes the blazes in kill order and connects the next ones with lines.")
    val blazeLineNext by ToggleSetting("Blaze Next Line", true)
        .withDescription("Draws a line from each blaze to the one you have to kill after it.")
        .showIf { blaze.value }
    val blazeLineAmount by SliderSetting("Blaze Lines", 2, 1, 10, 1)
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
    val hideBlazes by ToggleSetting("Hide Blazes", true)
        .withDescription("Hides the blazes while the solver is active.")
        .showIf { blaze.value }
    val blazeReset by ButtonSetting("Reset Blaze") { BlazeSolver.reset() }
        .withDescription("Clears the Blaze solver state, use it if the solver gets stuck.")
        .showIf { blaze.value }

    val boulder by ToggleSetting("Enabled ").section("Boulder Solver")
    val showAll by ToggleSetting("Show All Moves", false).showIf { boulder.value }
    val boxColor by ColorSetting("Boulder Box Color", Color.BLUE.withAlpha(100)).showIf { boulder.value }
    // NoammAddons eea0d140: the three "Click Color" settings need distinct json names
    val clickColor by ColorSetting("Click Color", Color.RED.withAlpha(100)).showIf { boulder.value }.jsonName("Boulder Click color")

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
    val currentClickColor by ColorSetting("Click Color", Color.GREEN).showIf { water.value }.jsonName("Water Board Click Color")
    val nextColor by ColorSetting("Next Click Color", Color.YELLOW).showIf { water.value }
    val waterRecovery by ToggleSetting("Water Recovery Guidance", true)
        .withDescription("Watches the real board (doors, water, lever blocks) and, when the run goes wrong - water in the wrong channel, water that stops short, flips off the timing, levers moved by teammates - shows how to finish from where the board is: a quick fix when one is known to work, otherwise turn the water off, let it drain and run the solutions that open the doors still closed. The first run is always the plain timer display; the recovery takes over once a run ended with doors still closed or someone else's run changed the doors, and only while the board can be read reliably. Off: the plain 3.2.2 timer display only.")
        .showIf { water.value }
    val waterBoardFirst by ToggleSetting("Water Board-Reading First", false)
        .withDescription("Experimental: follows the board from the first run on (flip-back markers for levers moved by teammates, live judgement of the run) instead of the plain timer display. Off: the timer display leads until a run fails, then the recovery takes over.")
        .showIf { water.value && waterRecovery.value }
    val waterTolerance by SliderSetting("Water Timing Tolerance", 1.0, 0.2, 3.0, 0.1, "s")
        .withDescription("How far a flip may be from its planned time. Beyond it the run counts as off track: a quick fix from the board when one is known to work, otherwise turn the water off, drain and restart. Flips by teammates playing their own timings are not judged.")
        .showIf { water.value && waterRecovery.value }
    val waterFixColor by ColorSetting("Water Fix Color", Color(255, 85, 255))
        .withDescription("Colour of the recovery markers (levers to flip, the water lever to turn off or pull again).")
        .showIf { water.value && waterRecovery.value }
    val waterFailMessage by ToggleSetting("Water Failure Message", true)
        .withDescription("Prints a chat message explaining what went wrong and how it will be fixed.")
        .showIf { water.value && waterRecovery.value }
    val waterQuickFixes by ToggleSetting("Water Quick Fixes", true)
        .withDescription("When the simulator reproduces every shipped solution of the board in front of you, a run that goes wrong is finished from where it is (flip these levers now / on these timers) instead of draining and restarting, and door states that need several restarts are opened in one run. Off: always drain and restart.")
        .showIf { water.value && waterRecovery.value }
    val waterPingCompensation by ToggleSetting("Water Ping Compensation", true)
        .withDescription("Shows the CLICK of computed fixes earlier by your ping, so the flip reaches the server on time.")
        .showIf { water.value && waterRecovery.value && waterQuickFixes.value }
    val waterRecorder by ToggleSetting("Water Board Recorder", true)
        .withDescription("Keeps a short trace of every Water Board room (when the doors closed, lever flips, the water, which display was shown and why) plus the room layout, runs and calibration in config/LayBaltQOL/waterboard - local files only, nothing is sent anywhere, and only the newest files are kept. If the solver ever misbehaves, these files show what happened. Also /lbq water record on|off.")
        .showIf { water.value }
    val waterReset by ButtonSetting("Reset Water") { WaterBoardSolver.resetSession() }
        .withDescription("Forgets everything about the Water Board room and reads the board again.")
        .showIf { water.value }

    val icefill by ToggleSetting("Enabled        ").section("Ice Fill Solver")
    val icefillColor by ColorSetting("Line Color", Color.GREEN).showIf { icefill.value }.jsonName("Ice Fill Line Color")

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

    /** Puzzles whose completion Odin detects through the reward chest block event. */
    private val chestCompletedPuzzles = setOf("Three Weirdos", "Ice Fill", "Teleport Maze", "Water Board", "Boulder")

    /** Minimum plausible solve time per puzzle room (seconds); faster "solves" are ignored as bogus. From Odin's Puzzle.timeToBeat. */
    private val timeToBeat = mapOf(
        "Lower Blaze" to 6, "Higher Blaze" to 6, "Creeper Beams" to 6, "Three Weirdos" to 2,
        "Water Board" to 10, "Teleport Maze" to 6, "Boulder" to 6, "Ice Fill" to 10, "Quiz" to 10
    )

    private fun shouldHideBlaze(entity: Entity) = blaze.value && hideBlazes.value && LocationUtils.inDungeon && ! inBoss &&
        (entity is Blaze || BlazeSolver.isBlazeTag(entity))

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

        // The recorder became a default-on diagnostic (its trace is what field reports are made of). Configs saved before
        // hold the old default (off), so it is switched on once; turning it off afterwards sticks.
        ConfigManager.oneShotDefault("water-board-recorder-on") { waterRecorder.value = true }

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

        hudElement(
            name = "Water Board Recovery",
            // Off with the recovery: the plain 3.2.2 display shows the timers on the levers only.
            enabled = { water.value && waterRecovery.value },
            shouldDraw = { WaterBoardSolver.hudText != null },
            centered = { true }
        ) { ctx, example ->
            val text = if (example) "§cWater Board off track §7- §fturn the water §cOFF" else WaterBoardSolver.hudText ?: ""
            ctx.drawCenteredString(text, 0f, 0f)
            return@hudElement text.width().toFloat() to 9f
        } defaults {
            x = Resolution.width / 2f
            y = Resolution.height * 0.3f
            scale = 1.5f
        }

        register<DungeonEvent.RoomEvent.onStateChange> { puzzles.forEach { if (it.enabled) it.onStateChange(event) } }
        register<PlayerInteractEvent.RIGHT_CLICK.BLOCK> { puzzles.forEach { if (it.enabled) it.onInteract(event) } }
        register<MainThreadPacketReceivedEvent.Pre> { puzzles.forEach { if (it.enabled) it.onPacket(event) } }
        register<DungeonEvent.RoomEvent.onEnter> { puzzles.forEach { if (it.enabled && ! inBoss) it.onRoomEnter(event) } }
        register<DungeonEvent.RoomEvent.RotationFound> { puzzles.forEach { if (it.enabled && ! inBoss) it.onRotationFound(event) } }
        register<BlockChangeEvent> { if (water.value) WaterBoardSolver.onBlockChange(event) }
        register<MainThreadPacketReceivedEvent.Post> { if (water.value) WaterBoardSolver.onPacketPost(event.packet) }

        // Odin 1acdc46d: blaze name tags are tracked from entity data packets, the puzzle ends on the last blaze's death
        register<MainThreadPacketReceivedEvent.Post> { if (blaze.value) BlazeSolver.onPacketPost(event.packet) }
        register<EntityUnloadEvent> { if (blaze.value) BlazeSolver.onEntityRemove(event.entity) }
        // Odin 1acdc46d / b412ad1c: Hide Blazes hides the blazes, their name tags and the blaze room's smoke
        register<CheckEntityRenderEvent> { if (shouldHideBlaze(event.entity)) event.isCanceled = true }
        register<ParticleEvent> {
            if (! blaze.value || ! hideBlazes.value) return@register
            if (event.particle != ParticleTypes.LARGE_SMOKE || ! BlazeSolver.inBlazeRoom) return@register
            event.isCanceled = true
        }
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

        // Odin: the reward chest of these puzzles opens with a CHERRY_LOG block event the moment the puzzle
        // is solved, which is earlier than the map checkmark. Odin matches exact chest positions; here any
        // such event inside the current puzzle room (1x1, 31 blocks wide) counts.
        register<MainThreadPacketReceivedEvent.Pre> {
            if (! puzzleTimers.value || inBoss || ! LocationUtils.inDungeon) return@register
            val packet = event.packet as? ClientboundBlockEventPacket ?: return@register
            if (packet.block != Blocks.CHERRY_LOG) return@register
            val room = ScanUtils.currentRoom?.takeIf { it.data.type == RoomType.PUZZLE } ?: return@register
            if (room.name !in chestCompletedPuzzles) return@register
            val pos = packet.pos
            if (abs(pos.x - room.centerPos.x) > 16 || abs(pos.z - room.centerPos.z) > 16) return@register
            onPuzzleComplete(room.name)
        }

        // Fallback for every other puzzle: the room turning green on the dungeon map.
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

        register<WorldChangeEvent> {
            puzzleTimersMap.clear()
            // The Water Board session lives for the whole dungeon; it must not survive into the next one even when the solver is off.
            WaterBoardSolver.reset()
        }
        register<DungeonEvent.BossEnterEvent> { WaterBoardSolver.reset() }
    }
}