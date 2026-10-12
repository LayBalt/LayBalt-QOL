// Personal bests, Kuudra splits and the current split HUD ported from Odin (https://github.com/odtheking/Odin), BSD-3-Clause, Copyright (c) 2025 odtheking
package com.laybalt.features.impl.visual

import com.laybalt.config.PersonalBest
import com.laybalt.config.types.DropdownSetting
import com.laybalt.config.types.ToggleSetting
import com.laybalt.event.impl.*
import com.laybalt.features.Feature
import com.laybalt.init.DataDownloader
import com.laybalt.utils.ChatUtils
import com.laybalt.utils.ChatUtils.removeFormatting
import com.laybalt.utils.NumbersUtils.toFixed
import com.laybalt.utils.dungeons.DungeonListener
import com.laybalt.utils.dungeons.DungeonListener.DualTime
import com.laybalt.utils.dungeons.DungeonListener.DualTime.Companion.minus
import com.laybalt.utils.dungeons.map.handlers.DungeonScanner
import com.laybalt.utils.kuudra.KuudraUtils
import com.laybalt.utils.location.LocationUtils
import com.laybalt.utils.render.Render2D.drawString
import com.laybalt.utils.render.RenderHelper.width

object RunSplits: Feature("A Splits HUD for Dungeons (and Kuudra), with personal bests.") {
    private val showWitherDoors by ToggleSetting("Show Wither Doors").withDescription("Show The Number of Wither Doors in the run")
    private val showTotalTime by ToggleSetting("Total Time").withDescription("Shows the total run time.")
    private val showTimeLost by ToggleSetting("Show Time Lost").withDescription("Shows the total time run time that was lost due to server lags.")
    private val show300Score by ToggleSetting("Show 300 Score Time").withDescription("Shows when 300 score was reached.")

    private val showTickTime by ToggleSetting("Show Tick Time", true).section("Personal Bests")
        .withDescription("Shows the server-tick time in brackets next to the real time")
    private val trackPBs by ToggleSetting("Track Personal Bests", true)
        .withDescription("Saves your best real time for every split, per floor (and per Kuudra tier)")
    private val splitMessages by ToggleSetting("Split Messages")
        .withDescription("Prints each finished split in chat with your personal best, and a summary at the end of the run")
    private val comparePB by DropdownSetting("Compare To PB", 0, listOf("Off", "PB Time", "Difference")).showIf { trackPBs.value }
        .withDescription("Adds your personal best, or the difference to it (green = faster), after every split in the HUD")
    private val currentSplitHud by ToggleSetting("Current Split HUD")
        .withDescription("A second small HUD that only shows the tick time of the split you are in (move it with the HUD editor)")
    private val kuudraSplits by ToggleSetting("Kuudra Splits")
        .withDescription("Also shows splits (Supplies, Build, Stun, DPS, Cleared) in Kuudra")

    /** runSplits.json split boundary for Necron's death ([DungeonEvent.NecronDeathEvent]); never a real chat line. */
    private const val NECRON_DEATH_MARKER = "@necron_death"

    private val floorSplits = DataDownloader.loadJson<Map<String, List<DialogueEntry>>>("runSplits.json")
    private val runEndRegex = Regex("^\\s*☠ Defeated (.+) in 0?([\\dhms ]+?)\\s*(\\(NEW RECORD!\\))?$")
    private val currentFloorSplits = linkedMapOf<String, Split>()

    private var score300Timer: DualTime? = null
    private var runEndHandled = false
    // The "☠ Defeated" line is only printed for cleared runs; failed runs get the summary header only.
    private var defeatedSeen = false
    private var endedTicks = 0
    private val recorded = mutableSetOf<String>()
    private val finished = mutableListOf<Pair<String, Double>>()
    private var currentSplit: Pair<String, DualTime>? = null

    private var currentText: List<String> = emptyList()
    private val exampleText = listOf(
        "§8Wither Doors: §72",
        "§4Blood Open: 1:07 §7(§b1:06§7)",
        "§cWatcher Clear: 67 §7(§b66§7)",
        "§dPortal: 4.2 §7(§b4.1§7)",
        "§e300 Score: 1:22 §7(§b1:22§7)",
        "§aBoss Entry: 5:49 §7(§b5:47§7)"
    )

    private val inArea get() = LocationUtils.inDungeon || (kuudraSplits.value && KuudraUtils.inKuudra)

    override fun init() {
        hudElement("Run Splits", shouldDraw = { inArea }) { ctx, example ->
            val text = if (example) exampleText else if (KuudraUtils.inKuudra) Kuudra.lines() else currentText
            if (text.isEmpty()) return@hudElement 0f to 0f

            var currentY = 0
            var width = 0

            for (i in text.indices) {
                val line = text[i]
                ctx.drawString(line, 0, currentY)
                width = maxOf(width, line.width())
                currentY += 9
            }

            return@hudElement width to currentY
        }

        hudElement("Run Splits Current", enabled = { currentSplitHud.value }, shouldDraw = { inArea }, centered = { true }) { ctx, example ->
            val ticks = when {
                example -> 0L
                KuudraUtils.inKuudra -> Kuudra.currentTicks() ?: return@hudElement 0 to 0
                else -> currentSplit?.let { DungeonListener.currentTime - it.second.ticks } ?: return@hudElement 0 to 0
            }
            val text = "§7${(ticks / 20.0).toFixed(2)}s"
            val w = text.width()
            ctx.drawString(text, - w / 2f, 0)
            w to 9
        }

        register<DungeonEvent.Score> {
            if (score300Timer != null) return@register
            if (event.score < 300) return@register
            score300Timer = DualTime(DungeonListener.currentTime)
        }

        register<WorldChangeEvent> {
            currentFloorSplits.clear()
            score300Timer = null
            runEndHandled = false
            defeatedSeen = false
            endedTicks = 0
            recorded.clear()
            finished.clear()
            currentSplit = null
            currentText = emptyList()
            Kuudra.reset()
        }

        register<TickEvent.Server> { Kuudra.ticks ++ }

        register<TickEvent.Start> {
            if (! LocationUtils.inDungeon) return@register
            if (DungeonListener.dungeonEnded) {
                // Wait for the "☠ Defeated" line (or 3 s for failed runs) so Total is only saved for cleared runs.
                if (! runEndHandled && DungeonListener.dungeonStarted && (defeatedSeen || ++ endedTicks >= 60)) onRunEnd()
                return@register
            }

            val now = DualTime(DungeonListener.currentTime)
            val start = DungeonListener.dungeonStartTime ?: now
            val blood = DungeonListener.bloodOpenTime
            val watcher = DungeonListener.watcherClearTime
            val boss = DungeonListener.bossEntryTime

            if (blood != null) record("Blood Open", blood - start)
            if (blood != null && watcher != null) record("Watcher Clear", watcher - blood)
            if (watcher != null && boss != null) record("Portal", boss - watcher)
            if (boss != null) record("Boss Entry", boss - start)
            score300Timer?.let { record("300 Score", it - start) }

            val bloodOpen = when {
                blood == null && DungeonListener.dungeonStarted -> dual(now - start, ::formatTime) + compare("Blood Open", now - start)
                blood != null -> dual(blood - start, ::formatTime) + compare("Blood Open", blood - start)
                else -> "?"
            }

            val watcherClear = when {
                watcher != null && blood != null -> dual(watcher - blood, ::formatSecs) + compare("Watcher Clear", watcher - blood)
                blood != null -> dual(now - blood, ::formatSecs) + compare("Watcher Clear", now - blood)
                else -> "?"
            }

            val score300 = when {
                ! show300Score.value -> null
                score300Timer != null -> dual(score300Timer !! - start, ::formatTime) + compare("300 Score", score300Timer !! - start)
                DungeonListener.dungeonStarted -> dual(now - start, ::formatTime)
                else -> "?"
            }

            val portalTime = when {
                watcher != null && boss == null -> dual(now - watcher, ::formatDec) + compare("Portal", now - watcher)
                watcher != null && boss != null -> dual(boss - watcher, ::formatDec) + compare("Portal", boss - watcher)
                else -> "?"
            }

            val bossEntry = if (DungeonListener.dungeonStarted) dual((boss ?: now) - start, ::formatTime) + compare("Boss Entry", (boss ?: now) - start) else "?"

            currentSplit = when {
                ! DungeonListener.dungeonStarted -> null
                blood == null -> "Blood Open" to start
                watcher == null -> "Watcher Clear" to blood
                boss == null -> "Portal" to watcher
                else -> currentFloorSplits.entries.lastOrNull { it.value.start != null && it.value.end == null }?.let { it.key to it.value.start !! }
            }

            val splitLines = currentFloorSplits.mapNotNull { (name, split) ->
                val s = split.start ?: return@mapNotNull null
                val e = split.end ?: DualTime(DungeonListener.currentTime)
                val diff = e - s
                val t = (diff.ticks / 20.0).toFixed(2)
                val r = (diff.real / 1000.0).toFixed(2)
                (if (showTickTime.value) "$name: $r §7(§b$t§7)§r" else "$name: $r§r") + compare(name.removeFormatting(), diff)
            }.toMutableList().apply {
                indexOfFirst { it.startsWith("&aBoss") }.takeUnless { it == - 1 }?.let { add(removeAt(it)) }
            }

            val clearInfo = mutableListOf<String>()
            if (showWitherDoors.value) clearInfo.add("§8Wither Doors: §7${DungeonScanner.witherDoors}")
            clearInfo.add("§4Blood Open: $bloodOpen")
            clearInfo.add("§cWatcher Clear: $watcherClear")
            clearInfo.add("§dPortal: $portalTime")
            score300?.let { clearInfo.add("§e300 Score: $it") }
            clearInfo.add("§aBoss Entry: $bossEntry")

            if (splitLines.isNotEmpty()) {
                clearInfo.add("-----------------------")
                clearInfo.addAll(splitLines)
            }

            if (DungeonListener.dungeonStarted) {
                val total = now - start
                val diffMs = total.real - (total.ticks * 50)

                if (showTotalTime.value || showTimeLost.value) clearInfo.add("-----------------------")
                if (showTotalTime.value) clearInfo.add("§eTotal: ${dual(total, ::formatTime)}${compare("Total", total)}")
                if (showTimeLost.value && diffMs >= 1000) clearInfo.add("§8Lag Lost: §c+${diffMs / 1000}s")
            }

            currentText = clearInfo
        }

        register<ChatMessageEvent>(receiveCancelled = true) {
            if (KuudraUtils.inKuudra) return@register Kuudra.onMessage(event.unformattedText)
            if (! LocationUtils.inDungeon) return@register

            val msg = event.unformattedText.trim()
            if (DungeonListener.dungeonStarted && runEndRegex.matches(msg)) defeatedSeen = true
            onDungeonLine(msg)
        }

        // NoammAddons 072bf9e6: since the dungeon update no dialogue line marks Necron's death at the right time, so the
        // M7 Necron split of runSplits.json ends on this marker, fed in when Necron's boss bar empties.
        register<DungeonEvent.NecronDeathEvent> {
            if (LocationUtils.inDungeon) onDungeonLine(NECRON_DEATH_MARKER)
        }
    }

    /** Starts/ends the floor splits of runSplits.json whose dialogue line (or marker) is [msg]. */
    private fun onDungeonLine(msg: String) {
        val floor = LocationUtils.dungeonFloor ?: return
        val currentSplits = floorSplits[floor] ?: floorSplits[floor.replace("M", "F")] ?: return

        currentSplits.forEachIndexed { i, entry ->
            val split = currentFloorSplits.getOrPut(entry.name) { Split() }

            if (entry.startMatches(msg) || (i > 0 && currentSplits[i - 1].endMatches(msg))) {
                split.start = DualTime(DungeonListener.currentTime)
            }

            if (entry.endMatches(msg) || (entry.end == null && runEndRegex.matches(msg))) {
                split.end = DualTime(DungeonListener.currentTime)
                split.start?.let { s -> record(entry.name.removeFormatting(), split.end !! - s) }
            }

            if (split.start != null || split.end != null) currentFloorSplits[entry.name] = split
        }
    }

    // region personal bests

    private fun pbKey(group: String, split: String) = "runsplits.$group.${split.removeFormatting().trim()}"

    private fun dungeonGroup() = LocationUtils.dungeonFloor ?: "?"

    /** Records a finished split once: PB check and (optionally) a chat line, like Odin's splits. */
    private fun record(name: String, diff: DualTime, group: String = dungeonGroup()) {
        if (! recorded.add("$group/$name")) return
        val seconds = diff.real / 1000.0
        if (seconds <= 0.0) return
        finished.add(name to seconds)
        if (! trackPBs.value) {
            if (splitMessages.value) ChatUtils.modMessage("§6$name §7took §6${seconds.toFixed(2)}s§7!")
            return
        }
        val key = pbKey(group, name)
        val old = PersonalBest.getPB(key)?.toDouble()
        val isNew = PersonalBest.checkAndSetPB(key, seconds)
        if (! splitMessages.value) return
        val suffix = when {
            isNew && old != null -> "§7(§d§lNew PB§r§7) Old PB was §8${old.toFixed(2)}"
            isNew -> "§7(§d§lNew PB§r§7)"
            else -> "§8(§7${old?.toFixed(2)}§8)"
        }
        ChatUtils.modMessage("§6$name §7took §6${seconds.toFixed(2)}s§7! $suffix")
    }

    private fun compare(name: String, diff: DualTime, group: String = dungeonGroup()): String {
        if (! trackPBs.value || comparePB.value == 0) return ""
        val pb = PersonalBest.getPB(pbKey(group, name))?.toDouble() ?: return ""
        if (comparePB.value == 1) return " §8[§7${pb.toFixed(1)}§8]"
        val delta = diff.real / 1000.0 - pb
        return if (delta <= 0) " §a${delta.toFixed(1)}" else " §c+${delta.toFixed(1)}"
    }

    private fun onRunEnd() {
        runEndHandled = true
        val start = DungeonListener.dungeonStartTime ?: return
        val end = DualTime(DungeonListener.dungeonEndTime ?: DungeonListener.currentTime)
        if (defeatedSeen) record("Total", end - start)
        if (splitMessages.value && finished.isNotEmpty()) {
            ChatUtils.modMessage("§6Run splits §7(§e${dungeonGroup()}§7):")
            finished.forEach { (name, seconds) -> ChatUtils.chat("  §6$name §7took §6${seconds.toFixed(2)}s") }
        }
    }

    /**
     * Imports one of Odin's PB maps ("DungeonF7" / "KuudraT5": split name -> seconds) into this feature's keys.
     * Odin's dungeon split names are matched to LayBaltQOL's; [report] gets one line per entry. Returns how many were applied.
     */
    fun importOdinPBs(odinGroup: String, pbs: Map<String, Double>, report: (String) -> Unit): Int {
        val group = when {
            odinGroup.startsWith("Dungeon") -> odinGroup.removePrefix("Dungeon")
            odinGroup.startsWith("Kuudra") -> odinGroup
            else -> return 0
        }
        var n = 0
        for ((odinName, seconds) in pbs) {
            val plain = odinName.removeFormatting().trim()
            if (! group.startsWith("Kuudra") && plain in odinUnmapped) {
                report("    - PB $group/$plain: skipped (no matching LayBaltQOL split)")
                continue
            }
            val name = if (group.startsWith("Kuudra")) plain else odinNames[plain] ?: plain
            val key = pbKey(group, name)
            if (PersonalBest.importTimePB(key, seconds)) {
                report("    + PB $key = $seconds")
                n ++
            }
            else report("    = PB $key: kept ${PersonalBest.getPB(key)}")
        }
        return n
    }

    /** Odin dungeon split names whose LayBaltQOL split has another name. */
    private val odinNames = mapOf(
        "Blood Clear" to "Watcher Clear", "Portal Entry" to "Portal", "Scarf's minions" to "Undeads",
        "The Guardians" to "Guardians", "Bonzo's Sike" to "First Kill"
    )

    /** Odin dungeon splits that have no LayBaltQOL counterpart. */
    private val odinUnmapped = setOf("Cleared")

    // endregion

    // region Kuudra (Odin's SplitsManager, Kuudra groups)

    private object Kuudra {
        class KSplit(val regex: Regex, val name: String, var time: Long = 0L, var ticks: Long = 0L)

        var ticks = 0L
        private var splits = emptyList<KSplit>()

        fun reset() {
            splits = emptyList()
            ticks = 0L
        }

        private fun group(tier: Int): List<KSplit> = buildList {
            add(KSplit(Regex("^\\[NPC] Elle: Okay adventurers, I will go and fish up Kuudra!$"), "§2Supplies"))
            add(KSplit(Regex("^\\[NPC] Elle: OMG! Great work collecting my supplies!$"), "§bBuild"))
            val ballista = Regex("^\\[NPC] Elle: Phew! The Ballista is finally ready! It should be strong enough to tank Kuudra's blows now!$")
            if (tier == 5) {
                add(KSplit(ballista, "§dEaten"))
                add(KSplit(Regex("^(?!Elle has been eaten by Kuudra!$)(.{1,16}) has been eaten by Kuudra!$"), "§cStun"))
                add(KSplit(Regex("^(.{1,16}) destroyed one of Kuudra's pods!$"), "§4DPS"))
            }
            else add(KSplit(ballista, "§cStun"))
            add(KSplit(Regex("^\\[NPC] Elle: POW! SURELY THAT'S IT! I don't think he has any more in him!$"), "§4Cleared"))
            add(KSplit(Regex("^\\[NPC] Elle: Good job everyone. A hard fought battle come to an end. Let's get out of here before we run into any more trouble!$"), "Total"))
        }

        private val groupName get() = "KuudraT${KuudraUtils.kuudraTier}"

        fun onMessage(message: String) {
            if (! kuudraSplits.value) return
            if (message == "Starting in 1 second.") {
                ticks = 0L
                splits = if (KuudraUtils.kuudraTier in 1 .. 5) group(KuudraUtils.kuudraTier) else emptyList()
                return
            }
            val split = splits.find { it.regex.matches(message) } ?: return
            if (split.time != 0L) return
            split.time = System.currentTimeMillis()
            split.ticks = ticks
            val index = splits.indexOf(split)
            if (index == 0) return
            val previous = splits[index - 1]
            if (previous.time == 0L) return
            record(previous.name.removeFormatting(), DualTime(split.ticks - previous.ticks, split.time - previous.time), groupName)
            if (index == splits.lastIndex) record("Total", DualTime(split.ticks - splits.first().ticks, split.time - splits.first().time), groupName)
        }

        fun currentTicks(): Long? {
            if (splits.isEmpty() || splits[0].time == 0L) return null
            val current = splits.zipWithNext().firstOrNull { (split, next) -> split.time != 0L && next.time == 0L }?.first ?: return null
            return ticks - current.ticks
        }

        fun lines(): List<String> {
            if (! kuudraSplits.value || splits.isEmpty()) return emptyList()
            if (splits[0].time == 0L) return splits.dropLast(1).map { "${it.name}§r: §70s" }
            val nowMs = System.currentTimeMillis()
            val last = splits.last()
            val out = mutableListOf<String>()
            for (i in 0 until splits.lastIndex) {
                val split = splits[i]
                if (split.time == 0L) break
                val next = splits[i + 1]
                val ms = (if (next.time != 0L) next.time else nowMs) - split.time
                val t = (if (next.time != 0L) next.ticks else ticks) - split.ticks
                out.add(line(split.name, DualTime(t, ms)))
            }
            val totalMs = (if (last.time != 0L) last.time else nowMs) - splits.first().time
            val totalTicks = (if (last.time != 0L) last.ticks else ticks) - splits.first().ticks
            out.add("-----------------------")
            out.add(line("§eTotal", DualTime(totalTicks, totalMs)))
            return out
        }

        private fun line(name: String, diff: DualTime): String {
            val seconds = diff.real / 1000.0
            val r = if (seconds >= 60) "${(seconds / 60).toInt()}m ${(seconds % 60).toInt()}s" else "${seconds.toFixed(1)}s"
            val t = (diff.ticks / 20.0).toFixed(1)
            return "$name§r: $r${if (showTickTime.value) " §7(§b$t§7)" else ""}${compare(name.removeFormatting(), diff, groupName)}"
        }
    }

    // endregion

    private data class Split(var start: DualTime? = null, var end: DualTime? = null)
    private data class DialogueEntry(val name: String, val start: String? = null, val end: String? = null) {
        @Transient private val startRegex = start?.toRegex()
        @Transient private val endRegex = end?.toRegex()

        fun startMatches(msg: String) = start == msg || startRegex?.matches(msg) == true
        fun endMatches(msg: String) = end == msg || endRegex?.matches(msg) == true
    }

    private fun dual(diff: DualTime, fmt: (Long) -> String) =
        if (showTickTime.value) "${fmt(diff.real / 50)} §7(§b${fmt(diff.ticks)}§7)" else fmt(diff.real / 50)

    private fun formatTime(ticks: Long) = "${ticks / 20 / 60}:${(ticks / 20 % 60).toString().padStart(2, '0')}"
    private fun formatSecs(ticks: Long) = "${ticks / 20}"
    private fun formatDec(ticks: Long) = if (ticks / 20 >= 60) formatTime(ticks) else (ticks / 20.0).toFixed(1)
}
