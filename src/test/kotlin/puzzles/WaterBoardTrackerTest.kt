package puzzles

import com.google.gson.JsonParser
import com.laybalt.features.impl.dungeon.solvers.puzzles.WaterBoardTracker
import com.laybalt.features.impl.dungeon.solvers.puzzles.WaterBoardTracker.Cause
import com.laybalt.features.impl.dungeon.solvers.puzzles.WaterBoardTracker.Phase
import com.laybalt.features.impl.dungeon.solvers.puzzles.WaterLever
import com.laybalt.features.impl.dungeon.solvers.puzzles.WaterLever.*
import java.io.File
import kotlin.random.Random
import kotlin.test.*

class WaterBoardTrackerTest {
    // Variant 0 / gates "014" of waterSolutions.json: nothing before the water, everything timed.
    private val timed = mapOf(
        WATER to listOf(0.0), CLAY to listOf(3.2), GOLD to listOf(4.7),
        QUARTZ to listOf(7.5, 12.2), DIAMOND to listOf(10.0), EMERALD to listOf(11.1)
    )

    // Variant 0 / gates "012": four levers flipped before the water.
    private val preStart = mapOf(
        CLAY to listOf(0.0), QUARTZ to listOf(0.0, 10.0), WATER to listOf(0.0),
        DIAMOND to listOf(0.0), GOLD to listOf(0.0), EMERALD to listOf(7.9)
    )

    // Variant 1 / gates "123": the solution itself turns the water off at 6.9 s.
    private val waterOff = mapOf(
        QUARTZ to listOf(0.0), GOLD to listOf(0.0, 9.0), DIAMOND to listOf(10.0),
        EMERALD to listOf(0.0), CLAY to listOf(0.0, 5.6), WATER to listOf(0.0, 6.9)
    )

    private fun ticks(seconds: Double) = Math.round(seconds * 20)

    /**
     * Plays [solution] perfectly from [start]: pre-water flips and the water at [start], every other flip at
     * its scheduled tick (shifted by [jitter]), ticking every tick until [until] seconds after the start.
     */
    private fun play(
        tracker: WaterBoardTracker,
        solution: Map<WaterLever, List<Double>>,
        start: Long,
        until: Double = 16.0,
        skipPreStart: Boolean = false,
        jitter: (WaterLever, Int) -> Long = { _, _ -> 0L }
    ): List<WaterBoardTracker.Failure> {
        val failures = mutableListOf<WaterBoardTracker.Failure>()
        if (! skipPreStart) solution.forEach { (lever, times) ->
            if (lever != WATER) repeat(times.count { it == 0.0 }) { tracker.onClick(lever, start)?.let(failures::add) }
        }
        tracker.onClick(WATER, start)?.let(failures::add)

        val clicks = solution.flatMap { (lever, times) ->
            times.withIndex().filter { it.value > 0.0 }.map { (i, t) -> (start + ticks(t) + jitter(lever, i)) to lever }
        }.groupBy({ it.first }, { it.second })

        for (now in start + 1 .. start + ticks(until)) {
            clicks[now]?.forEach { lever -> tracker.onClick(lever, now)?.let(failures::add) }
            tracker.onTick(now)?.let(failures::add)
        }
        return failures
    }

    private fun assertAllDone(tracker: WaterBoardTracker, solution: Map<WaterLever, List<Double>>) {
        solution.forEach { (lever, times) -> assertEquals(times.size, tracker.consumed(lever), "consumed $lever") }
    }

    @Test
    fun `correct run never fails`() {
        val tracker = WaterBoardTracker(timed)
        assertEquals(emptyList(), play(tracker, timed, start = 100))
        assertEquals(Phase.RUNNING, tracker.phase)
        assertNull(tracker.lastFailure)
        assertAllDone(tracker, timed)
    }

    @Test
    fun `every shipped solution plays through without a failure, also with jitter inside the tolerance`() {
        val json = JsonParser.parseString(File("src/main/resources/data/waterSolutions.json").readText()).asJsonObject
        val random = Random(42)
        var played = 0
        for ((variant, gates) in json.entrySet()) for ((gate, levers) in gates.asJsonObject.entrySet()) {
            val solution = levers.asJsonObject.entrySet().associate { (key, times) ->
                assertNotNull(WaterLever.fromKey(key), "unknown lever $key") to times.asJsonArray.map { it.asDouble }
            }
            val exact = WaterBoardTracker(solution)
            assertEquals(emptyList(), play(exact, solution, start = 7), "variant $variant gates $gate")
            assertAllDone(exact, solution)

            val jittered = WaterBoardTracker(solution)
            assertEquals(emptyList(), play(jittered, solution, start = 7) { _, _ -> random.nextLong(- 10, 11) }, "jittered $variant $gate")
            assertAllDone(jittered, solution)
            played ++
        }
        assertEquals(40, played)
    }

    @Test
    fun `wrong lever before the water start lists it to flip back, then the run continues normally`() {
        val tracker = WaterBoardTracker(preStart)
        assertEquals(emptyList(), tracker.preStartFixes())

        assertNull(tracker.onClick(COAL, 10))       // not part of this solution
        assertNull(tracker.onClick(EMERALD, 12))    // only flipped at 7.9 s, not before the water
        assertNull(tracker.onClick(GOLD, 14))       // a correct pre-water flip
        assertEquals(listOf(COAL, EMERALD), tracker.preStartFixes())
        assertEquals(Phase.WAITING, tracker.phase)

        assertNull(tracker.onClick(COAL, 20))
        assertNull(tracker.onClick(EMERALD, 21))
        assertEquals(emptyList(), tracker.preStartFixes())

        // GOLD is done already, the others are flipped by play().
        val rest = preStart - GOLD + (GOLD to listOf())
        val failures = play(tracker, rest, start = 40)
        assertEquals(emptyList(), failures)
        assertAllDone(tracker, preStart - GOLD)
        assertEquals(1, tracker.consumed(GOLD))
    }

    @Test
    fun `pre-water lever flipped twice is shown as pending again, not as a fix`() {
        val tracker = WaterBoardTracker(preStart)
        tracker.onClick(GOLD, 1)
        assertEquals(1, tracker.consumed(GOLD))
        tracker.onClick(GOLD, 2)
        assertEquals(0, tracker.consumed(GOLD))
        assertEquals(emptyList(), tracker.preStartFixes())
    }

    @Test
    fun `pre-water flip right after the water is accepted within the tolerance`() {
        val tracker = WaterBoardTracker(preStart, toleranceTicks = 20)
        listOf(CLAY, QUARTZ, DIAMOND).forEach { tracker.onClick(it, 0) }
        assertNull(tracker.onClick(WATER, 0))
        assertNull(tracker.onTick(5))
        assertNull(tracker.onClick(GOLD, 10))
        assertNull(tracker.onTick(30))
        assertEquals(Phase.RUNNING, tracker.phase)
    }

    @Test
    fun `pre-water flip forgotten after the water fails once the tolerance passed`() {
        val tracker = WaterBoardTracker(preStart, toleranceTicks = 20)
        listOf(CLAY, QUARTZ, DIAMOND).forEach { tracker.onClick(it, 0) }
        tracker.onClick(WATER, 0)
        assertNull(tracker.onTick(20))
        val failure = tracker.onTick(21)
        assertEquals(Cause.MISSED_CLICK, failure?.cause)
        assertEquals(GOLD, failure?.lever)
    }

    @Test
    fun `starting the water with a wrong lever flipped fails and asks to turn the water off`() {
        val tracker = WaterBoardTracker(timed)
        tracker.onClick(COAL, 0)
        val failure = tracker.onClick(WATER, 5)
        assertEquals(Cause.WRONG_START_STATE, failure?.cause)
        assertEquals(COAL, failure?.lever)
        assertEquals(Phase.WATER_ON_FAILED, tracker.phase)
    }

    @Test
    fun `wrong lever after the start - turn water off, drain, reset list, restart from zero`() {
        val tracker = WaterBoardTracker(preStart)
        listOf(CLAY, QUARTZ, DIAMOND, GOLD).forEach { tracker.onClick(it, 0) }
        tracker.onClick(WATER, 0)
        for (now in 1L .. 60L) assertNull(tracker.onTick(now))

        // COAL is not part of this solution at all.
        val failure = tracker.onClick(COAL, 61)
        assertEquals(Cause.WRONG_LEVER, failure?.cause)
        assertEquals(COAL, failure?.lever)
        assertEquals(Phase.WATER_ON_FAILED, tracker.phase)
        assertTrue(tracker.waterOn)
        assertFalse(tracker.followsSolution)

        // Further flips while the water is still on are only tracked.
        assertNull(tracker.onClick(EMERALD, 70))
        assertNull(tracker.onTick(71, 40))
        assertEquals(Phase.WATER_ON_FAILED, tracker.phase)

        // Turn the water off -> draining until the board is dry.
        assertNull(tracker.onClick(WATER, 80))
        assertEquals(Phase.DRAINING, tracker.phase)
        assertFalse(tracker.waterOn)
        var water = 60
        var now = 81L
        while (water > 0) {
            assertNull(tracker.onTick(now ++, water))
            water -= 2
        }
        assertEquals(Phase.DRAINING, tracker.phase)
        assertNotNull(tracker.drainEta)
        repeat(WaterBoardTracker.DRY_TICKS - 1) { tracker.onTick(now ++, 0) }
        assertEquals(Phase.DRAINING, tracker.phase)
        tracker.onTick(now ++, 0)
        assertEquals(Phase.RESTORE, tracker.phase)

        // Starting state: CLAY, QUARTZ, DIAMOND, GOLD flipped (their pre-water flips), everything else not.
        // COAL and EMERALD were flipped by mistake; the pre-water levers are still in the right state.
        assertEquals(listOf(COAL, EMERALD), tracker.restoreFixes())
        tracker.onClick(COAL, now ++)
        assertEquals(listOf(EMERALD), tracker.restoreFixes())
        tracker.onClick(EMERALD, now ++)
        assertEquals(emptyList(), tracker.restoreFixes())

        // Pull the water lever: the timeline restarts from zero and a correct rerun passes.
        val restart = now
        val rerun = preStart.mapValues { (lever, times) -> if (lever == WATER) times else times.filter { it > 0.0 } }
        assertEquals(emptyList(), play(tracker, rerun, start = restart, skipPreStart = true))
        assertEquals(restart, tracker.startTick)
        assertEquals(1, tracker.restarts)
        assertEquals(Phase.RUNNING, tracker.phase)
        assertAllDone(tracker, preStart)
    }

    @Test
    fun `restore after a run that already flipped timed levers lists exactly the changed ones`() {
        val tracker = WaterBoardTracker(timed, toleranceTicks = 20)
        tracker.onClick(WATER, 0)
        tracker.onClick(CLAY, ticks(3.2))
        tracker.onClick(GOLD, ticks(4.7))
        val failure = (ticks(4.7) + 1 .. ticks(20.0)).firstNotNullOfOrNull { tracker.onTick(it) }
        assertEquals(Cause.MISSED_CLICK, failure?.cause)
        assertEquals(QUARTZ, failure?.lever)
        assertEquals(ticks(7.5) + 21, failure?.tick)

        tracker.onClick(WATER, 200)
        tracker.onTick(201, 0)
        repeat(WaterBoardTracker.DRY_TICKS) { tracker.onTick(202L + it, 0) }
        assertEquals(Phase.RESTORE, tracker.phase)
        assertEquals(listOf(GOLD, CLAY), tracker.restoreFixes())
    }

    @Test
    fun `missed timing fails exactly after the tolerance`() {
        val tracker = WaterBoardTracker(timed, toleranceTicks = 20)
        tracker.onClick(WATER, 0)
        for (now in 1L .. ticks(3.2) + 20) assertNull(tracker.onTick(now), "tick $now")
        val failure = tracker.onTick(ticks(3.2) + 21)
        assertEquals(Cause.MISSED_CLICK, failure?.cause)
        assertEquals(CLAY, failure?.lever)
        assertEquals(21, failure?.offsetTicks)
        assertEquals(Phase.WATER_ON_FAILED, tracker.phase)
    }

    @Test
    fun `late click within the tolerance is fine`() {
        val tracker = WaterBoardTracker(timed, toleranceTicks = 20)
        tracker.onClick(WATER, 0)
        for (now in 1L .. ticks(3.2) + 19) assertNull(tracker.onTick(now))
        assertNull(tracker.onClick(CLAY, ticks(3.2) + 19))
        assertNull(tracker.onTick(ticks(3.2) + 30))
        assertEquals(Phase.RUNNING, tracker.phase)
    }

    @Test
    fun `click too early fails`() {
        val tracker = WaterBoardTracker(timed, toleranceTicks = 20)
        tracker.onClick(WATER, 0)
        val failure = tracker.onClick(CLAY, ticks(3.2) - 21)
        assertEquals(Cause.TOO_EARLY, failure?.cause)
        assertEquals(21, failure?.offsetTicks)
    }

    @Test
    fun `extra click on a used lever fails`() {
        val tracker = WaterBoardTracker(timed)
        tracker.onClick(WATER, 0)
        assertNull(tracker.onClick(CLAY, ticks(3.2)))
        assertEquals(Cause.EXTRA_CLICK, tracker.onClick(CLAY, ticks(3.5))?.cause)
    }

    @Test
    fun `turning the water off early fails straight into draining`() {
        val tracker = WaterBoardTracker(timed)
        tracker.onClick(WATER, 0)
        val failure = tracker.onClick(WATER, 30)
        assertEquals(Cause.EXTRA_CLICK, failure?.cause)
        assertEquals(WATER, failure?.lever)
        assertEquals(Phase.DRAINING, tracker.phase)
    }

    @Test
    fun `solution that turns the water off itself, then fails, goes straight to draining`() {
        val tracker = WaterBoardTracker(waterOff)
        assertEquals(emptyList(), play(tracker, waterOff, start = 0, until = 8.0))
        assertFalse(tracker.waterOn)
        assertEquals(Cause.WRONG_LEVER, tracker.onClick(COAL, ticks(8.5))?.cause)
        assertEquals(Phase.DRAINING, tracker.phase)
    }

    @Test
    fun `water turned back on while draining asks to turn it off again`() {
        val tracker = WaterBoardTracker(timed)
        tracker.onClick(WATER, 0)
        tracker.onClick(COAL, 10)
        tracker.onClick(WATER, 20)
        assertEquals(Phase.DRAINING, tracker.phase)
        assertNull(tracker.onClick(WATER, 25))
        assertEquals(Phase.WATER_ON_FAILED, tracker.phase)
    }

    @Test
    fun `pulling the water before the board is restored fails again`() {
        val tracker = WaterBoardTracker(timed)
        tracker.onClick(WATER, 0)
        tracker.onClick(COAL, 10)
        tracker.onClick(WATER, 20)
        repeat(WaterBoardTracker.DRY_TICKS) { tracker.onTick(21L + it, 0) }
        assertEquals(listOf(COAL), tracker.restoreFixes())
        assertEquals(Cause.WRONG_START_STATE, tracker.onClick(WATER, 50)?.cause)
        assertEquals(Phase.WATER_ON_FAILED, tracker.phase)
    }

    @Test
    fun `draining falls back to a timeout when the board cannot be read or stops draining`() {
        val unknown = WaterBoardTracker(timed)
        unknown.onClick(WATER, 0)
        unknown.onClick(WATER, 1)
        unknown.onTick(1L + WaterBoardTracker.UNKNOWN_DRAIN_TICKS - 1, null)
        assertEquals(Phase.DRAINING, unknown.phase)
        unknown.onTick(1L + WaterBoardTracker.UNKNOWN_DRAIN_TICKS, null)
        assertEquals(Phase.RESTORE, unknown.phase)

        val stalled = WaterBoardTracker(timed)
        stalled.onClick(WATER, 0)
        stalled.onClick(WATER, 1)
        for (now in 2L until 1L + WaterBoardTracker.STALLED_DRAIN_TICKS) stalled.onTick(now, 5)
        assertEquals(Phase.DRAINING, stalled.phase)
        stalled.onTick(1L + WaterBoardTracker.STALLED_DRAIN_TICKS + 1, 5)
        assertEquals(Phase.RESTORE, stalled.phase)
    }

    @Test
    fun `solved puzzle stops the guidance`() {
        val tracker = WaterBoardTracker(timed)
        tracker.onClick(WATER, 0)
        tracker.onClick(COAL, 10)
        tracker.markSolved()
        assertEquals(Phase.SOLVED, tracker.phase)
        assertFalse(tracker.followsSolution)
        assertEquals(emptyList(), tracker.restoreFixes())
    }
}
