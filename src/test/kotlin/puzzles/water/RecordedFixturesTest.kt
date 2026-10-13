package puzzles.water

import com.laybalt.features.impl.dungeon.solvers.puzzles.water.*
import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.Files
import kotlin.test.*

/**
 * The simulator against the player's own recordings ([WaterFixtures]). Without recordings these tests are skipped: no
 * board layout of any variant is available from a source LayBalt may use, so they only start to bite once the
 * "Water Board Recorder" files of all four variants are copied into `src/test/resources/waterboard/`.
 */
class RecordedFixturesTest {
    @Test
    fun `every recorded layout reproduces its ten shipped solutions`() {
        val layouts = WaterFixtures.layouts()
        assumeTrue("no recorded layouts in ${WaterFixtures.layoutsDir}", layouts.isNotEmpty())
        for (l in layouts) {
            val table = WaterTestData.tables.getValue(l.variant)
            val result = Calibration.calibrate(CalibrationInput(l.layout, table, WaterRules(rotation = l.rotation), l.parity, l.parityUncertain))
            println("${l.file.name}: ${result.trust} ${result.summary}")
            result.checks.forEach { println("  ${it.key}: ${if (it.passed) "ok" else "FAIL"} doors=${Door.keyOf(it.doors)} rewet=${it.rewet} last arrival ${it.lastArrival} (last click ${it.lastClick})") }
            assertEquals(SimTrust.TRUSTED, result.trust, "${l.file.name}: ${result.summary}")
        }
    }

    @Test
    fun `recorded runs replay in the simulator`() {
        val runs = WaterFixtures.runs().filter { it.dryStart && it.layoutFile != null && File(WaterFixtures.layoutsDir, it.layoutFile).exists() }
        assumeTrue("no recorded runs with their layout in ${WaterFixtures.runsDir}", runs.isNotEmpty())
        for (run in runs) {
            val l = WaterFixtures.parseLayout(File(WaterFixtures.layoutsDir, run.layoutFile!!))
            val gate = Calibration.calibrate(CalibrationInput(l.layout, WaterTestData.tables.getValue(l.variant), WaterRules(rotation = l.rotation), l.parity, l.parityUncertain))
            val shadow = ShadowCheck(l.layout, gate.rules)
            shadow.start(run.startTick, run.maskAtStart xor l.maskAtDump)
            val levels = ByteArray(l.layout.size) { - 1 }
            var wetTicks = 0L
            val cells = run.cells.sortedBy { it[0] }
            var next = 0
            for (t in run.startTick + 1 .. run.endTick) {
                while (next < cells.size && cells[next][0] <= t) {
                    levels[cells[next][1]] = cells[next][2].toByte()
                    next ++
                }
                run.flips.filter { it[0] == t }.forEach { shadow.onFlip(it[1], t) }
                shadow.tick(t, levels)
                wetTicks += levels.count { it >= 0 }
            }
            val share = shadow.totalDiff.toDouble() / maxOf(1L, wetTicks)
            println("${run.file.name}: ${shadow.ticksCompared} ticks, ${"%.1f".format(share * 100)}% of wet cells off, max ${shadow.maxDiff}")
            assertTrue(share <= 0.05, "${run.file.name}: ${"%.1f".format(share * 100)}% of the wet cell-ticks differ")
            assertFalse(shadow.demoted, run.file.name)
        }
    }

    @Test
    fun `the fixture reader understands the recorder's dump format`() {
        // A hand-made dump: walls everywhere, an open column at x=0 from the entry to an outlet, gold extended at (0,70).
        val rows = (BoardGeometry.MAX_Y downTo BoardGeometry.MIN_Y).map { y ->
            buildString {
                for (x in - 12 .. 12) append(
                    when {
                        x == 0 && y == 70 -> 'G'
                        x == 0 && y in BoardGeometry.OUTLET_Y .. BoardGeometry.ENTRY_Y -> '.'
                        else -> 'A'
                    }
                )
            }
        }
        val back = rows.map { it.replace('G', 'A') }
        val json = """
            {"format":1,"kind":"layout","variant":3,"rotation":90,"parity":2,"parityUncertain":0,"maskAtDump":0,
             "planes":{"11":${rows.joinToString(",", "[", "]") { "\"$it\"" }},"12":${back.joinToString(",", "[", "]") { "\"$it\"" }}},
             "palette":{".":"minecraft:air","~":"minecraft:water","A":"minecraft:stone_bricks","G":"minecraft:gold_block"}}
        """.trimIndent()
        val file = Files.createTempFile("layout", ".json").toFile()
        try {
            file.writeText(json)
            val l = WaterFixtures.parseLayout(file)
            assertEquals(3, l.variant)
            assertEquals(90, l.rotation)
            assertEquals(2, l.parity)
            val gold = BoardGeometry.cell(0, 70)
            assertEquals(WaterLever.GOLD.ordinal, l.layout.toggleLever[gold].toInt())
            assertTrue(l.layout.refExtended[gold])
            assertTrue(l.layout.wall[BoardGeometry.cell(3, 70)])
            assertFalse(l.layout.wall[BoardGeometry.cell(0, 65)])
            assertEquals(listOf(BoardGeometry.cell(0, BoardGeometry.OUTLET_Y)), l.layout.outlets.toList())
        }
        finally {
            file.delete()
        }
    }
}
