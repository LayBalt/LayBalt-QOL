// Ported from Odin (https://github.com/odtheking/Odin), BSD-3-Clause, Copyright (c) 2025 odtheking
package com.laybalt.features.impl.dungeon

import com.laybalt.config.types.ColorSetting
import com.laybalt.config.types.ToggleSetting
import com.laybalt.event.impl.TickEvent
import com.laybalt.event.impl.WorldChangeEvent
import com.laybalt.features.Feature
import com.laybalt.utils.ChatUtils.unformattedText
import com.laybalt.utils.TabListUtils
import com.laybalt.utils.dungeons.DungeonListener
import com.laybalt.utils.location.LocationUtils
import com.laybalt.utils.render.Render2D.drawRect
import com.laybalt.utils.render.Render2D.drawString
import com.laybalt.utils.render.RenderHelper.width
import java.awt.Color

object PuzzleHud: Feature("Displays the remaining, completed and failed puzzles of the dungeon on your HUD.", name = "Puzzle HUD") {
    private val showPlayerNames by ToggleSetting("Show Player Names", true)
        .withDescription("Shows which player solved or failed each puzzle")
    private val showOnlyPre by ToggleSetting("Show Only Pre-Run")
        .withDescription("Only shows the HUD before any room has been opened (to plan the run)")
    private val background by ColorSetting("Background Color", Color(24, 20, 36, 140))
        .withDescription("Background behind the puzzle list; set the alpha to 0 to hide it")

    private data class Entry(val name: String, val icon: String, val color: String, val player: String?)

    private val example = listOf(
        Entry("Ice Path", "✖", "§c", "odtheking"),
        Entry("Creeper Beams", "✔", "§a", null),
        Entry("???", "✦", "§6", null),
        Entry("???", "✦", "§6", null)
    )

    private val puzzleRegex = Regex("^ (\\w+(?: \\w+)*|\\?\\?\\?): \\[([✖✔✦])] ?(?:\\((\\w+)\\))?$")
    private val puzzleCountRegex = Regex("^Puzzles: \\((\\d+)\\)$")
    private val openedRoomsRegex = Regex("^ Opened Rooms: (\\d+)$")

    private var entries = emptyList<Entry>()
    private var puzzleCount = 0
    private var openedRooms = 0
    private var ticks = 0

    override fun init() {
        hudElement("PuzzleHud", shouldDraw = { LocationUtils.inDungeon && ! LocationUtils.inBoss }) { ctx, isExample ->
            if (! isExample && showOnlyPre.value && openedRooms != 0) return@hudElement 0 to 0
            val count = if (isExample) 4 else puzzleCount
            if (count == 0) return@hudElement 0 to 0
            val list = if (isExample) example else entries + List((count - entries.size).coerceAtLeast(0)) { Entry("???", "✦", "§6", null) }

            val lines = buildList {
                add("§5Puzzles §8(§3$count§8)")
                list.forEach { entry ->
                    val player = if (showPlayerNames.value && entry.player != null) " §7(§6${entry.player}§7)" else ""
                    add("§e${entry.name}§8: [${entry.color}${entry.icon}§8]$player")
                }
            }
            val lineHeight = 12
            val width = lines.maxOf { it.width() } + 4
            val height = lines.size * lineHeight
            if (background.value.alpha > 0) ctx.drawRect(- 2, - 2, width, height, background.value)
            lines.forEachIndexed { i, line -> ctx.drawString(line, 0, i * lineHeight) }
            width to height
        }

        register<TickEvent.Start> {
            if (! LocationUtils.inDungeon || ++ ticks % 10 != 0) return@register
            update()
        }

        register<WorldChangeEvent> {
            entries = emptyList()
            puzzleCount = 0
            openedRooms = 0
        }
    }

    private fun update() {
        val found = mutableListOf<Entry>()
        var count = DungeonListener.maxPuzzleCount
        for ((component, _) in TabListUtils.getTabList()) {
            val line = component.unformattedText
            puzzleCountRegex.find(line.trim())?.let { count = it.groupValues[1].toIntOrNull() ?: count }
            openedRoomsRegex.find(line)?.let { openedRooms = it.groupValues[1].toIntOrNull() ?: openedRooms }
            val match = puzzleRegex.find(line) ?: continue
            val (name, status, player) = match.destructured
            if (name == "???") continue
            val (icon, color) = when (status) {
                "✔" -> "✔" to "§a"
                "✖" -> "✖" to "§c"
                else -> "✦" to "§6"
            }
            found.add(Entry(name, icon, color, player.ifEmpty { null }))
        }
        puzzleCount = count
        entries = found
    }
}
