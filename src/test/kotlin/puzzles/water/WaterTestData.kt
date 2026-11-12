package puzzles.water

import com.google.gson.JsonParser
import com.laybalt.features.impl.dungeon.solvers.puzzles.water.ShippedPlay
import com.laybalt.features.impl.dungeon.solvers.puzzles.water.ShippedTable
import com.laybalt.features.impl.dungeon.solvers.puzzles.water.WaterLever
import com.laybalt.features.impl.dungeon.solvers.puzzles.water.WaterLever.*
import java.io.File

/** Shared fixtures: the bundled solution table and a few hand-picked plays. */
object WaterTestData {
    val json: Map<String, Map<String, Map<String, List<Double>>>> by lazy {
        val root = JsonParser.parseString(File("src/main/resources/data/waterSolutions.json").readText()).asJsonObject
        root.entrySet().associate { (variant, keys) ->
            variant to keys.asJsonObject.entrySet().associate { (key, levers) ->
                key to levers.asJsonObject.entrySet().associate { (lever, times) -> lever to times.asJsonArray.map { it.asDouble } }
            }
        }
    }

    val tables: Map<Int, ShippedTable> by lazy { ShippedTable.parseAll(json) }

    fun play(variant: Int, key: String): ShippedPlay = tables.getValue(variant).plays.values.first { it.key == key }

    fun play(times: Map<WaterLever, List<Double>>, key: String = "014", variant: Int = 0) = ShippedPlay(variant, key, times)

    // Variant 0 / "014": nothing before the water, everything timed.
    val timed = mapOf(
        WATER to listOf(0.0), CLAY to listOf(3.2), GOLD to listOf(4.7),
        QUARTZ to listOf(7.5, 12.2), DIAMOND to listOf(10.0), EMERALD to listOf(11.1)
    )

    // Variant 0 / "012": four levers flipped before the water.
    val preStart = mapOf(
        CLAY to listOf(0.0), QUARTZ to listOf(0.0, 10.0), WATER to listOf(0.0),
        DIAMOND to listOf(0.0), GOLD to listOf(0.0), EMERALD to listOf(7.9)
    )

    // Variant 1 / "123": the solution itself turns the water off at 6.9 s.
    val waterOff = mapOf(
        QUARTZ to listOf(0.0), GOLD to listOf(0.0, 9.0), DIAMOND to listOf(10.0),
        EMERALD to listOf(0.0), CLAY to listOf(0.0, 5.6), WATER to listOf(0.0, 6.9)
    )

    fun ticks(seconds: Double) = Math.round(seconds * 20).toInt()
}
