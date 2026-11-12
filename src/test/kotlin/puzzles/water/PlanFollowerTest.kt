package puzzles.water

import com.laybalt.features.impl.dungeon.solvers.puzzles.water.FlipSource
import com.laybalt.features.impl.dungeon.solvers.puzzles.water.PlanFollower
import com.laybalt.features.impl.dungeon.solvers.puzzles.water.PlanFollower.Kind
import com.laybalt.features.impl.dungeon.solvers.puzzles.water.ShippedPlay
import com.laybalt.features.impl.dungeon.solvers.puzzles.water.WaterLever
import com.laybalt.features.impl.dungeon.solvers.puzzles.water.WaterLever.*
import puzzles.water.WaterTestData.ticks
import kotlin.random.Random
import kotlin.test.*

/**
 * The timing rules of the 3.2.2 `WaterBoardTracker` (its RUNNING phase), kept for the follower that now reads flips from
 * the board. Test names match the old ones where the scenario is the same.
 */
class PlanFollowerTest {
    /** Plays [play] from click-frame [start] with every timed flip shifted by [jitter]; returns all divergences. */
    private fun run(
        play: ShippedPlay,
        start: Int,
        parity: Int = play.startParity,
        until: Double = 16.0,
        tolerance: Int = 20,
        jitter: (WaterLever, Int) -> Int = { _, _ -> 0 },
    ): Pair<PlanFollower, List<PlanFollower.Divergence>> {
        val follower = PlanFollower(play, start, tolerance)
        val divergences = mutableListOf<PlanFollower.Divergence>()
        follower.start(parity)?.let(divergences::add)
        val clicks = play.timedClicks.withIndex().groupBy({ (i, c) -> start + c.tick + jitter(c.lever, i) }, { it.value.lever })
        for (now in start + 1 .. start + ticks(until)) {
            clicks[now]?.forEach { lever -> follower.onFlip(lever, now)?.let(divergences::add) }
            follower.onTick(now)?.let(divergences::add)
        }
        return follower to divergences
    }

    @Test
    fun `correct run never fails`() {
        val (follower, divergences) = run(WaterTestData.play(WaterTestData.timed), start = 100)
        assertEquals(emptyList(), divergences)
        assertTrue(follower.finished)
    }

    @Test
    fun `every shipped solution plays through without a divergence, also with jitter inside the tolerance`() {
        val random = Random(42)
        var played = 0
        for (table in WaterTestData.tables.values) for (play in table.plays.values) {
            val (exact, d1) = run(play, start = 7)
            assertEquals(emptyList(), d1, "$play")
            assertTrue(exact.finished, "$play")

            val (jittered, d2) = run(play, start = 7) { _, _ -> random.nextInt(- 10, 11) }
            assertEquals(emptyList(), d2, "jittered $play")
            assertTrue(jittered.finished, "jittered $play")
            played ++
        }
        assertEquals(40, played)
    }

    @Test
    fun `pre-water flip right after the water is accepted within the tolerance`() {
        val play = WaterTestData.play(WaterTestData.preStart, key = "012")
        val follower = PlanFollower(play, anchor = 0, toleranceTicks = 20)
        // Gold (one pre-water flip) is still unflipped when the water starts.
        assertNull(follower.start(play.startParity xor GOLD.bit))
        assertEquals(0, follower.consumed(GOLD))
        assertNull(follower.onTick(5))
        assertNull(follower.onFlip(GOLD, 10))
        assertNull(follower.onTick(30))
        assertEquals(1, follower.consumed(GOLD))
    }

    @Test
    fun `pre-water flip forgotten after the water fails once the tolerance passed`() {
        val play = WaterTestData.play(WaterTestData.preStart, key = "012")
        val follower = PlanFollower(play, anchor = 0, toleranceTicks = 20)
        assertNull(follower.start(play.startParity xor GOLD.bit))
        assertNull(follower.onTick(20))
        val divergence = follower.onTick(21)
        assertEquals(Kind.MISSED, divergence?.kind)
        assertEquals(GOLD, divergence?.lever)
    }

    @Test
    fun `pre-water lever flipped twice is pending again, not a wrong start`() {
        val play = WaterTestData.play(WaterTestData.preStart, key = "012")
        val follower = PlanFollower(play, anchor = 0)
        // Flipped twice = parity back to 0: its one pre-water flip is pending, not a wrong lever.
        assertNull(follower.start(play.startParity xor GOLD.bit))
        assertEquals(0, follower.consumed(GOLD))
    }

    @Test
    fun `starting the water with a wrong lever flipped is a start-state divergence`() {
        val play = WaterTestData.play(WaterTestData.timed)
        val divergence = PlanFollower(play, anchor = 5).start(COAL.bit)
        assertEquals(Kind.START_STATE, divergence?.kind)
        assertEquals(COAL, divergence?.lever)
    }

    @Test
    fun `missed timing fails exactly after the tolerance`() {
        val follower = PlanFollower(WaterTestData.play(WaterTestData.timed), anchor = 0, toleranceTicks = 20)
        assertNull(follower.start(0))
        for (now in 1 .. ticks(3.2) + 20) assertNull(follower.onTick(now), "tick $now")
        val divergence = follower.onTick(ticks(3.2) + 21)
        assertEquals(Kind.MISSED, divergence?.kind)
        assertEquals(CLAY, divergence?.lever)
        assertEquals(21, divergence?.offset)
    }

    @Test
    fun `late click within the tolerance is fine`() {
        val follower = PlanFollower(WaterTestData.play(WaterTestData.timed), anchor = 0, toleranceTicks = 20)
        follower.start(0)
        for (now in 1 .. ticks(3.2) + 19) assertNull(follower.onTick(now))
        assertNull(follower.onFlip(CLAY, ticks(3.2) + 19))
        assertNull(follower.onTick(ticks(3.2) + 30))
    }

    @Test
    fun `click too early fails`() {
        val follower = PlanFollower(WaterTestData.play(WaterTestData.timed), anchor = 0, toleranceTicks = 20)
        follower.start(0)
        val divergence = follower.onFlip(CLAY, ticks(3.2) - 21)
        assertEquals(Kind.EARLY, divergence?.kind)
        assertEquals(21, divergence?.offset)
    }

    @Test
    fun `extra click on a used lever fails`() {
        val follower = PlanFollower(WaterTestData.play(WaterTestData.timed), anchor = 0)
        follower.start(0)
        assertNull(follower.onFlip(CLAY, ticks(3.2)))
        assertEquals(Kind.EXTRA, follower.onFlip(CLAY, ticks(3.5))?.kind)
    }

    @Test
    fun `wrong lever is reported with its source`() {
        val follower = PlanFollower(WaterTestData.play(WaterTestData.timed), anchor = 0)
        follower.start(0)
        val divergence = follower.onFlip(COAL, 40, FlipSource.OTHER)
        assertEquals(Kind.WRONG_LEVER, divergence?.kind)
        assertEquals(FlipSource.OTHER, divergence?.source)
    }

    @Test
    fun `turning the water off early is an extra water flip`() {
        val follower = PlanFollower(WaterTestData.play(WaterTestData.timed), anchor = 0)
        follower.start(0)
        val divergence = follower.onFlip(WATER, 30)
        assertEquals(Kind.EXTRA, divergence?.kind)
        assertEquals(WATER, divergence?.lever)
    }

    @Test
    fun `a solution that turns the water off itself does not count as a divergence`() {
        val play = WaterTestData.play(WaterTestData.waterOff, key = "123", variant = 1)
        val (follower, divergences) = run(play, start = 0, until = 12.0)
        assertEquals(emptyList(), divergences)
        assertTrue(follower.finished)
    }

    @Test
    fun `pending lists the remaining flips soonest first`() {
        val follower = PlanFollower(WaterTestData.play(WaterTestData.timed), anchor = 0)
        follower.start(0)
        follower.onFlip(CLAY, ticks(3.2))
        val pending = follower.pending()
        assertEquals(listOf(GOLD, QUARTZ, DIAMOND, EMERALD, QUARTZ), pending.map { it.lever })
        assertEquals(ticks(4.7), pending.first().tick)
        assertEquals(ticks(12.2), follower.lastPlannedTick)
    }
}
