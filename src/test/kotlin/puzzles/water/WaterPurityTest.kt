package puzzles.water

import java.io.File
import kotlin.test.*

/** The water package is pure (no Minecraft, no LayBalt runtime), so it runs in plain unit tests and on any thread. */
class WaterPurityTest {
    private val forbidden = listOf("net.minecraft.", "com.mojang.", "com.laybalt.utils.", "com.laybalt.event.", "com.laybalt.LayBaltQOL", "com.laybalt.features.impl.dungeon.solvers.PuzzleSolvers")

    @Test
    fun `the water package imports nothing from Minecraft or the mod runtime`() {
        val dir = File("src/main/kotlin/com/laybalt/features/impl/dungeon/solvers/puzzles/water")
        val files = dir.listFiles { f -> f.name.endsWith(".kt") }.orEmpty()
        assertTrue(files.size >= 10, "found ${files.size} files in $dir")
        for (file in files) {
            file.readLines().filter { it.startsWith("import ") }.forEach { line ->
                assertTrue(forbidden.none { line.removePrefix("import ").startsWith(it) }, "${file.name}: $line")
            }
        }
    }
}
