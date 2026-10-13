package com.laybalt.features.impl.dungeon.solvers.puzzles

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.laybalt.LayBaltQOL
import com.laybalt.features.impl.dungeon.solvers.PuzzleSolvers
import com.laybalt.features.impl.dungeon.solvers.puzzles.water.BoardGeometry
import com.laybalt.features.impl.dungeon.solvers.puzzles.water.Door
import com.laybalt.features.impl.dungeon.solvers.puzzles.water.FlipSource
import com.laybalt.features.impl.dungeon.solvers.puzzles.water.RunRecord
import com.laybalt.features.impl.dungeon.solvers.puzzles.water.WaterLever
import com.laybalt.utils.WorldUtils
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.LiquidBlock
import net.minecraft.world.level.block.state.BlockState
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors

/**
 * Self-recorded Water Board data (setting "Water Board Recorder" or `/lbq water record on`): a dump of the room the first
 * time it is read, and the observed timeline of every run, written to `config/LayBaltQOL/waterboard/`. These files are
 * the player's own recordings; copied into `src/test/resources/waterboard/` they become the simulator's test fixtures.
 *
 * World reads happen on the client thread; the files are written on a background thread.
 */
object WaterBoardRecorder {
    val enabled get() = PuzzleSolvers.water.value && PuzzleSolvers.waterRecorder.value

    val dir: Path get() = FabricLoader.getInstance().configDir.resolve(LayBaltQOL.CONFIG_DIR).resolve("waterboard")

    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
    private val compact = GsonBuilder().disableHtmlEscaping().create()
    private val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    private val io = Executors.newSingleThreadExecutor { Thread(it, "LayBalt-WaterRecorder").apply { isDaemon = true } }

    /** Characters handed out to block ids in a dump, in order of appearance ('.' is air, '~' is water). */
    private const val PALETTE_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@$%&*+=?<>^"

    private class Palette {
        val ids = LinkedHashMap<String, Char>()
        fun char(state: BlockState): Char {
            if (state.isAir) return '.'
            if (state.block == Blocks.WATER) return '~'
            val id = BuiltInRegistries.BLOCK.getKey(state.block).toString()
            return ids.getOrPut(id) { PALETTE_CHARS.getOrElse(ids.size) { '#' } }
        }

        fun json() = JsonObject().apply {
            addProperty(".", "minecraft:air")
            addProperty("~", "minecraft:water")
            ids.forEach { (id, c) -> addProperty(c.toString(), id) }
        }
    }

    /**
     * Dumps the room as it is now (client thread): both board planes and the planes around them, the whole room below
     * the board down to the door pit, the water levels, the levers' powered states and the door blocks. Returns the file
     * name, or null when the room is not readable.
     */
    fun dumpLayout(session: WaterRoomSession, reason: String): String? {
        val frame = session.frame ?: return null
        if (! WaterBoardReader.loaded(frame)) return null
        val tracker = session.tracker
        val palette = Palette()
        val json = JsonObject()
        json.addProperty("format", 1)
        json.addProperty("kind", "layout")
        json.addProperty("reason", reason)
        json.addProperty("variant", tracker.variant)
        json.addProperty("recordedAt", Instant.now().toString())
        json.addProperty("mod", LayBaltQOL.MOD_VERSION)
        json.addProperty("rotation", frame.rot)
        json.addProperty("doorsClosed", Door.keyOf(tracker.doors))
        json.addProperty("rttTicks", tracker.rttTicks)
        json.addProperty("leverLagTicks", tracker.lag(0))
        json.addProperty("untouchedFrom", tracker.startSource)
        json.addProperty("parity", tracker.parity)
        json.addProperty("parityUncertain", tracker.parityUncertain)
        // Lever parity of the dumped planes relative to the session's reference snapshot (runs record theirs the same way).
        json.addProperty("maskAtDump", tracker.mask)
        json.addProperty("boardWater", tracker.boardWater)

        // Board planes: one row per y (84 down to 56), x -12..12.
        val planes = JsonObject()
        for (z in BoardGeometry.FRONT_Z - 1 .. BoardGeometry.BACK_Z + 1) {
            val rows = JsonArray()
            for (y in BoardGeometry.MAX_Y downTo BoardGeometry.MIN_Y) {
                rows.add(buildString { for (x in - 12 .. 12) append(palette.char(WorldUtils.getStateAt(frame.world(x, y, z)))) })
            }
            planes.add(z.toString(), rows)
        }
        json.add("planes", planes)
        json.add("planeBox", JsonObject().apply {
            add("x", JsonArray().apply { add(- 12); add(12) })
            add("y", JsonArray().apply { add(BoardGeometry.MAX_Y); add(BoardGeometry.MIN_Y) })
        })

        // The room below the board (channels, basins, door pit): one slice per y, rows z -15..15, columns x -15..15.
        val floor = JsonObject()
        for (y in FLOOR_MIN_Y .. FLOOR_MAX_Y) {
            val rows = JsonArray()
            for (z in - 15 .. 15) rows.add(buildString { for (x in - 15 .. 15) append(palette.char(WorldUtils.getStateAt(frame.world(x, y, z)))) })
            floor.add(y.toString(), rows)
        }
        json.add("floor", floor)

        // Water levels in the board plane: [x, y, level].
        val water = JsonArray()
        for (y in BoardGeometry.MAX_Y downTo BoardGeometry.MIN_Y) for (x in BoardGeometry.MIN_X .. BoardGeometry.MAX_X) {
            val state = WorldUtils.getStateAt(frame.world(x, y, BoardGeometry.BOARD_Z))
            if (state.block == Blocks.WATER) water.add(JsonArray().apply { add(x); add(y); add(state.getValue(LiquidBlock.LEVEL)) })
        }
        json.add("water", water)

        json.add("levers", JsonObject().apply {
            for (lever in WaterLever.entries) {
                val powered = WaterBoardReader.leverPowered(frame.lever(lever))
                if (powered == null) addProperty(lever.key, "missing") else addProperty(lever.key, powered)
            }
        })
        json.add("doors", JsonObject().apply {
            for (door in Door.entries) {
                val low = WaterBoardReader.blockName(frame.world(0, BoardGeometry.DOOR_Y, door.z))
                val high = WaterBoardReader.blockName(frame.world(0, BoardGeometry.DOOR_Y + 1, door.z))
                addProperty(door.digit.toString(), "$low / $high")
            }
        })
        json.add("palette", palette.json())

        val name = "layout-v${tracker.variant}-${LocalDateTime.now().format(stamp)}.json"
        session.layoutFile = name
        write(name, gson.toJson(json))
        return name
    }

    /** Writes the observed timeline of one run. Cells are written as [tick, x, y, level]. */
    fun writeRun(session: WaterRoomSession, record: RunRecord) {
        val frame = session.frame
        val json = JsonObject()
        json.addProperty("format", 1)
        json.addProperty("kind", "run")
        json.addProperty("variant", record.variant)
        json.addProperty("doorsAtStart", record.key)
        json.addProperty("layoutFile", session.layoutFile)
        json.addProperty("rotation", frame?.rot)
        json.addProperty("startTick", record.startTick)
        json.addProperty("endTick", record.endTick)
        json.addProperty("result", record.result)
        json.addProperty("maskAtStart", record.mask)
        json.addProperty("rttTicks", session.tracker.rttTicks)
        json.addProperty("leverLagTicks", session.tracker.lag(0))
        json.addProperty("truncated", record.truncated)
        json.add("levelsAtStart", JsonArray().apply {
            record.levels.forEachIndexed { cell, lvl -> if (lvl >= 0) add(JsonArray().apply { add(BoardGeometry.x(cell)); add(BoardGeometry.y(cell)); add(lvl.toInt()) }) }
        })
        json.add("cells", triples(record.cells.size / 3) { i ->
            val cell = record.cells[i * 3 + 1]
            intArrayOf(record.cells[i * 3], BoardGeometry.x(cell), BoardGeometry.y(cell), record.cells[i * 3 + 2])
        })
        json.add("flips", triples(record.flips.size / 3) { i ->
            arrayOf<Any>(record.flips[i * 3], WaterLever.entries[record.flips[i * 3 + 1]].key, FlipSource.entries[record.flips[i * 3 + 2]].name)
        })
        json.add("water", triples(record.waters.size / 3) { i ->
            arrayOf<Any>(record.waters[i * 3], record.waters[i * 3 + 1] == 1, FlipSource.entries[record.waters[i * 3 + 2]].name)
        })
        json.add("doors", triples(record.doors.size / 3) { i ->
            arrayOf<Any>(record.doors[i * 3], record.doors[i * 3 + 1], record.doors[i * 3 + 2] == 1)
        })
        json.add("clicks", triples(record.clicks.size / 2) { i ->
            arrayOf<Any>(record.clicks[i * 2], WaterLever.entries[record.clicks[i * 2 + 1]].key)
        })
        val name = "run-v${record.variant}-${record.key.orEmpty().ifEmpty { "none" }}-${LocalDateTime.now().format(stamp)}.json"
        write(name, compact.toJson(json))
    }

    /** Appends one line to [file] (for the `.jsonl` logs). */
    fun appendLine(file: String, line: String) = io.execute {
        runCatching {
            Files.createDirectories(dir)
            Files.writeString(dir.resolve(file), line + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        }.onFailure { LayBaltQOL.logger.warn("[WaterBoard] could not append to $file", it) }
    }

    fun write(name: String, text: String) = io.execute {
        runCatching {
            Files.createDirectories(dir)
            Files.writeString(dir.resolve(name), text)
            LayBaltQOL.logger.info("[WaterBoard] wrote ${dir.resolve(name)}")
        }.onFailure { LayBaltQOL.logger.warn("[WaterBoard] could not write $name", it) }
    }

    private fun triples(n: Int, row: (Int) -> Any): JsonArray = JsonArray().apply {
        for (i in 0 until n) add(JsonArray().apply {
            when (val r = row(i)) {
                is IntArray -> r.forEach { add(it) }
                is Array<*> -> r.forEach {
                    when (it) {
                        is Int -> add(it)
                        is Boolean -> add(it)
                        else -> add(it.toString())
                    }
                }
            }
        })
    }

    private const val FLOOR_MIN_Y = 54
    private const val FLOOR_MAX_Y = 61
}
