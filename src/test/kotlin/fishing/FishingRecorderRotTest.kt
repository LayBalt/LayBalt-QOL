package fishing

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.laybalt.features.impl.fishing.FishingRecorder.RotGate
import com.laybalt.features.impl.fishing.FishingRecorder.RotLine
import net.minecraft.world.phys.Vec3
import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.random.Random
import kotlin.test.*

/** Which ticks get a `rot` line: every tick of a fight; outside fights view changes, a heartbeat and teleports. */
class FishingRecorderRotTest {
    private val here = Vec3(0.0, 64.0, 0.0)

    @Test
    fun `a still view outside fights gets a line at the start and then one a second`() {
        val gate = RotGate()
        val lines = (1 .. 61).filter { gate.next(it, 10.0, 20.0, here, fight = false) != RotLine.NONE }
        assertEquals(listOf(1, 1 + RotGate.HEARTBEAT_TICKS, 1 + 2 * RotGate.HEARTBEAT_TICKS, 1 + 3 * RotGate.HEARTBEAT_TICKS), lines)
    }

    @Test
    fun `a changed view gets its line in the tick it changed`() {
        val gate = RotGate()
        assertEquals(RotLine.LINE, gate.next(1, 10.0, 20.0, here, fight = false))
        assertEquals(RotLine.NONE, gate.next(2, 10.0, 20.0, here, fight = false))
        assertEquals(RotLine.LINE, gate.next(3, 10.15, 20.0, here, fight = false))
        assertEquals(RotLine.LINE, gate.next(4, 10.15, 19.85, here, fight = false))
        assertEquals(RotLine.NONE, gate.next(5, 10.15, 19.85, here, fight = false))
        // the heartbeat counts from the last line of any kind
        assertEquals(RotLine.NONE, gate.next(3 + RotGate.HEARTBEAT_TICKS, 10.15, 19.85, here, fight = false))
        assertEquals(RotLine.LINE, gate.next(4 + RotGate.HEARTBEAT_TICKS, 10.15, 19.85, here, fight = false))
    }

    @Test
    fun `every tick of a fight gets a line, and the fight's last view is what the next idle tick is compared with`() {
        val gate = RotGate()
        for (tick in 1 .. 30) assertEquals(RotLine.LINE, gate.next(tick, 10.0, 20.0, here, fight = true), "tick $tick")
        assertEquals(RotLine.NONE, gate.next(31, 10.0, 20.0, here, fight = false))
        assertEquals(RotLine.LINE, gate.next(32, 11.0, 20.0, here, fight = false))
    }

    @Test
    fun `a tick without a line had the last line's view`() {
        val rng = Random(7)
        val gate = RotGate()
        var yaw = 0.0
        var pitch = 0.0
        var written = - 1.0 to - 1.0
        var lines = 0
        val ticks = 20_000
        for (tick in 1 .. ticks) {
            // still most of the time, sometimes a grid step, sometimes a fast stretch
            when (rng.nextInt(100)) {
                in 0 .. 3 -> yaw = round2(yaw + 0.15 * (rng.nextInt(5) - 2))
                in 4 .. 5 -> pitch = round2((pitch + 3.0 * rng.nextDouble(- 1.0, 1.0)).coerceIn(- 90.0, 90.0))
            }
            val rot = gate.next(tick, yaw, pitch, here, fight = false)
            if (rot != RotLine.NONE) {
                written = yaw to pitch
                lines ++
            }
            assertEquals(yaw to pitch, written, "tick $tick")
        }
        // changes on about 6 % of ticks plus the heartbeat: far fewer lines than ticks
        assertTrue(lines < ticks / 6, "$lines lines in $ticks ticks")
    }

    @Test
    fun `a jump of more than 4 m in one tick is a teleport, sprinting and falling are not`() {
        val gate = RotGate()
        // the first tick has nothing to jump from
        assertEquals(RotLine.LINE, gate.next(1, 0.0, 0.0, Vec3(500.0, 64.0, - 300.0), fight = false))
        var pos = here
        assertEquals(RotLine.TELEPORT, gate.next(2, 0.0, 0.0, pos, fight = false))
        // sprinting covers more than 4 m between two heartbeat lines, but never in one tick
        for (tick in 3 .. 42) {
            pos = pos.add(0.28, 0.0, 0.0)
            assertNotEquals(RotLine.TELEPORT, gate.next(tick, 0.0, 0.0, pos, fight = false), "tick $tick")
        }
        for (tick in 43 .. 50) {
            pos = pos.add(0.0, - 3.92, 0.0)
            assertNotEquals(RotLine.TELEPORT, gate.next(tick, 0.0, 0.0, pos, fight = false), "tick $tick")
        }
        // a teleport with a still view is written although no heartbeat is due
        pos = pos.add(8.0, 0.0, 0.0)
        assertEquals(RotLine.TELEPORT, gate.next(51, 0.0, 0.0, pos, fight = false))
        assertEquals(RotLine.NONE, gate.next(52, 0.0, 0.0, pos, fight = false))
        assertEquals(RotLine.TELEPORT, gate.next(53, 0.0, 0.0, pos.add(0.0, 0.0, 12.0), fight = true))
        assertEquals(RotLine.LINE, gate.next(54, 0.0, 0.0, pos.add(0.0, 0.0, 12.0), fight = true))
    }

    /**
     * His recorded fight motion (`LBQ_RECORDINGS_DIR`, skipped without it) read as if it were idle: the thinned lines give
     * back every tick's view, at a fraction of the lines.
     */
    @Test
    fun `recorded motion thinned to idle lines loses no view`() {
        val path = System.getenv("LBQ_RECORDINGS_DIR")
        assumeTrue("LBQ_RECORDINGS_DIR is not set", ! path.isNullOrBlank())
        val files = File(path).listFiles { f -> f.name.endsWith(".jsonl") }?.sortedBy { it.name }.orEmpty()
        assumeTrue("no recordings in $path", files.isNotEmpty())
        var allTicks = 0
        var allLines = 0
        for (file in files) {
            val rots = file.readLines().filter { "\"e\":\"rot\"" in it }.map { JsonParser.parseString(it).asJsonObject }
            var ticks = 0
            var lines = 0
            var teleports = 0
            var gate = RotGate()
            var lastT = 0L
            var written: Pair<Double, Double>? = null
            for (o in rots) {
                val t = o["t"].asLong
                // a gap between fights: nothing was recorded in it, so the next fight starts afresh
                if (t - lastT > 120) gate = RotGate()
                lastT = t
                ticks ++
                val view = o.double("yaw") to o.double("pitch")
                val rot = gate.next(ticks, view.first, view.second, Vec3(o.double("x"), o.double("y"), o.double("z")), fight = false)
                if (rot != RotLine.NONE) {
                    written = view
                    lines ++
                }
                if (rot == RotLine.TELEPORT) teleports ++
                assertEquals(view, written, "${file.name} at ${o["t"]}")
            }
            println("${file.name}: $lines of $ticks ticks get an idle line (%.1f %%), %d teleports".format(100.0 * lines / ticks, teleports))
            allTicks += ticks
            allLines += lines
        }
        assertTrue(allLines < allTicks * 0.4, "$allLines lines for $allTicks ticks")
    }

    private fun JsonObject.double(key: String) = get(key).asDouble
    private fun round2(v: Double) = Math.round(v * 100.0) / 100.0
}
