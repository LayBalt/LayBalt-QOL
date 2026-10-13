package puzzles.water

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.laybalt.features.impl.dungeon.solvers.puzzles.water.*
import java.io.File

/**
 * Self-recorded Water Board fixtures (written in game by the "Water Board Recorder", copied into
 * `src/test/resources/waterboard/layouts` and `.../runs`). Only the player's own recordings belong here.
 */
object WaterFixtures {
    val layoutsDir = File("src/test/resources/waterboard/layouts")
    val runsDir = File("src/test/resources/waterboard/runs")

    class Layout(
        val file: File,
        val variant: Int,
        val rotation: Int,
        val layout: BoardLayout,
        /** Lever parity of the dumped planes relative to the untouched board, and which levers of it are a guess. */
        val parity: Int,
        val parityUncertain: Int,
        /** Lever parity of the dumped planes relative to the recording session's reference snapshot. */
        val maskAtDump: Int,
    )

    class Run(
        val file: File,
        val variant: Int,
        val layoutFile: String?,
        val startTick: Int,
        val endTick: Int,
        val maskAtStart: Int,
        val dryStart: Boolean,
        /** (tick, cell, level) */
        val cells: List<IntArray>,
        /** (tick, lever ordinal) */
        val flips: List<IntArray>,
        /** (tick, door digit, closed 0/1) */
        val doors: List<IntArray>,
        val doorsAtStart: Int,
    )

    fun layouts(): List<Layout> = layoutsDir.listFiles { f -> f.name.endsWith(".json") }.orEmpty().sortedBy { it.name }.map(::parseLayout)

    fun runs(): List<Run> = runsDir.listFiles { f -> f.name.endsWith(".json") }.orEmpty().sortedBy { it.name }.map(::parseRun)

    private val TOGGLES = mapOf(
        "minecraft:quartz_block" to WaterLever.QUARTZ, "minecraft:gold_block" to WaterLever.GOLD, "minecraft:coal_block" to WaterLever.COAL,
        "minecraft:diamond_block" to WaterLever.DIAMOND, "minecraft:emerald_block" to WaterLever.EMERALD, "minecraft:terracotta" to WaterLever.CLAY,
    )
    private val PISTONS = setOf("minecraft:piston", "minecraft:sticky_piston", "minecraft:piston_head", "minecraft:moving_piston")

    /** Blocks water flows into under the 1.8 rules (everything else in a dump is taken as solid). */
    private fun open(id: String) = id.endsWith("torch") || id.endsWith("_button") || id.endsWith("_carpet") || id == "minecraft:lever" ||
        id == "minecraft:redstone_wire" || id.endsWith("rail") || id == "minecraft:tripwire" || id.endsWith("_pressure_plate")

    fun code(id: String): Byte = when {
        id == "minecraft:air" || id == "minecraft:cave_air" -> BoardCodes.AIR
        id == "minecraft:water" -> BoardCodes.WATER
        id in TOGGLES -> BoardCodes.toggle(TOGGLES.getValue(id).ordinal)
        id in PISTONS -> BoardCodes.PISTON
        open(id) -> BoardCodes.OPEN
        else -> BoardCodes.WALL
    }

    fun parseLayout(file: File): Layout {
        val json = JsonParser.parseString(file.readText()).asJsonObject
        val palette = json.getAsJsonObject("palette").entrySet().associate { (c, id) -> c.single() to id.asString }
        fun plane(z: Int): ByteArray {
            val rows = json.getAsJsonObject("planes").getAsJsonArray(z.toString()).map { it.asString }
            val codes = ByteArray(BoardGeometry.N) { BoardCodes.WALL }
            for ((i, row) in rows.withIndex()) {
                val y = BoardGeometry.MAX_Y - i
                if (y < BoardGeometry.MIN_Y) break
                for (x in BoardGeometry.MIN_X .. BoardGeometry.MAX_X) {
                    val ch = row.getOrNull(x + 12) ?: continue
                    codes[BoardGeometry.cell(x, y)] = code(palette[ch] ?: "minecraft:stone")
                }
            }
            return codes
        }
        val variant = json.get("variant").asInt
        return Layout(
            file, variant, json.get("rotation").asInt,
            BoardLayout.fromCodes(plane(BoardGeometry.BOARD_Z), plane(BoardGeometry.HIDDEN_Z), variant),
            json.int("parity"), json.int("parityUncertain"), json.int("maskAtDump"),
        )
    }

    fun parseRun(file: File): Run {
        val json = JsonParser.parseString(file.readText()).asJsonObject
        fun rows(name: String) = json.getAsJsonArray(name)?.map { it.asJsonArray } ?: emptyList()
        return Run(
            file, json.get("variant").asInt, json.get("layoutFile")?.takeUnless { it.isJsonNull }?.asString,
            json.get("startTick").asInt, json.get("endTick").asInt, json.int("maskAtStart"),
            // Dry = no water on the board itself; the shaft above it (y > 79) may hold the source.
            rows("levelsAtStart").none { it[1].asInt <= BoardGeometry.DRY_MAX_Y },
            rows("cells").map { intArrayOf(it[0].asInt, BoardGeometry.cell(it[1].asInt, it[2].asInt), it[3].asInt) },
            rows("flips").mapNotNull { r -> WaterLever.fromKey(r[1].asString)?.let { intArrayOf(r[0].asInt, it.ordinal) } },
            rows("doors").map { intArrayOf(it[0].asInt, it[1].asInt, if (it[2].asBoolean) 1 else 0) },
            Door.bitsOf(json.get("doorsAtStart")?.takeUnless { it.isJsonNull }?.asString.orEmpty()),
        )
    }

    private fun JsonObject.int(name: String) = get(name)?.takeUnless { it.isJsonNull }?.asInt ?: 0
}
