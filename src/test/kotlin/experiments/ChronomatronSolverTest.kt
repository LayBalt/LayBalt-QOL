package experiments

import com.laybalt.features.impl.general.experiments.ChronomatronSolver
import com.laybalt.features.impl.general.experiments.ExperimentHost
import com.laybalt.features.impl.general.experiments.ExperimentSolver
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Replays Chronomatron against [ChronomatronServer], a model of Hypixel's behaviour reconstructed from real debug logs
 * (see the class docs of [ChronomatronSolver]), through the same [com.laybalt.features.impl.general.experiments.ExperimentDriver]
 * the feature uses.
 */
class ChronomatronSolverTest {
    /** The bug report's settings (config.json of the user): Click Delay 460, Delay Variance 180, Click Type Left. */
    private val userSettings = SimConfig(clickType = ClickType.LEFT, clickDelay = 460, delayVariance = 180, target = 11)

    private val fixed: (ExperimentHost, Boolean) -> ExperimentSolver = { host, atStart -> ChronomatronSolver(host, attachedAtStart = atStart) }
    private val legacy: (ExperimentHost, Boolean) -> ExperimentSolver = { host, atStart -> LegacyChronomatronSolver(host, watchFirstRound = ! atStart) }

    private fun run(
        layout: ChronomatronLayout,
        config: SimConfig,
        forced: List<Int> = emptyList(),
        timing: ChronomatronTiming = ChronomatronTiming(),
        solver: (ExperimentHost, Boolean) -> ExperimentSolver = fixed
    ): SimResult {
        val server = ChronomatronServer(layout, Random(config.seed * 31 + 7), timing, forced)
        return ExperimentSimulation(server, config, solver).run()
    }

    /** Runs every (layout, seed) and fails with one line per broken run plus the log of the first one. */
    private fun assertAllSolved(
        rounds: Int,
        layouts: List<ChronomatronLayout> = ChronomatronLayout.ALL,
        seeds: LongRange = 1L .. 20L,
        extra: (SimResult) -> String? = { null },
        case: (ChronomatronLayout, Long) -> SimResult
    ) {
        val broken = ArrayList<Pair<String, SimResult>>()
        for (layout in layouts) for (seed in seeds) {
            val result = case(layout, seed)
            val clean = result.failure == null && result.stoppedAt == rounds && result.roundsCompleted == rounds && result.ignoredClicks == 0
            val problem = if (clean) extra(result) else "not solved"
            if (problem != null) broken += "$layout seed $seed: $problem; $result" to result
        }
        if (broken.isEmpty()) return
        fail(
            "${broken.size} of ${layouts.size * seeds.count()} runs did not answer $rounds rounds cleanly:\n" +
                broken.joinToString("\n") { it.first } + "\n--- log of the first one ---\n" + broken.first().second.dump()
        )
    }

    @Test
    fun `answers 12 rounds on every board with the reported settings`() {
        assertAllSolved(12) { layout, seed -> run(layout, userSettings.copy(seed = seed)) }
    }

    /**
     * The simulator reproduces the bug report with the solver shipped in 3.2.2: on the 3-slot Supreme board it clicks a
     * phantom note as the 3rd click of the experiment (round 2, click 2), on the 2-slot Transcendent/Metaphysical boards
     * in round 3 - exactly like the user's Metaphysical trace of 2026-10-12 22:58 ("pane 15 lit #1 of 3",
     * "pane 24 lit #2 of 3", click 15, click 24, game over).
     */
    @Test
    fun `the 3_2_2 solver reproduces the bug report`() {
        val failures = ChronomatronLayout.ALL.associateWith { layout ->
            (1L .. 20L).map { seed -> run(layout, userSettings.copy(seed = seed), solver = legacy) }
        }
        for ((layout, results) in failures) for (result in results) {
            val failure = result.failure ?: fail("$layout: the 3.2.2 solver unexpectedly survived: $result")
            assertTrue(failure.startsWith("wrong click in round 2") || failure.startsWith("wrong click in round 3"), "$layout: $failure")
        }
        assertTrue(failures.getValue(ChronomatronLayout.SUPREME).any { it.failure!!.startsWith("wrong click in round 2, click 2") }, "Supreme: no 3rd-click failure")
        for (layout in listOf(ChronomatronLayout.TRANSCENDENT, ChronomatronLayout.METAPHYSICAL)) {
            assertTrue(failures.getValue(layout).all { it.failure!!.startsWith("wrong click in round 3") }, "$layout: ${failures.getValue(layout).map { it.failure }}")
        }
    }

    /** The trace of the bug report: Metaphysical, aqua (15/24), then cyan, then purple (33/42). */
    @Test
    fun `the reported Metaphysical game is answered note by note`() {
        val layout = ChronomatronLayout.METAPHYSICAL
        val aqua = layout.notes.indexOfFirst { it.colorName == "Aqua" }
        val cyan = layout.notes.indexOfFirst { it.colorName == "Cyan" }
        val purple = layout.notes.indexOfFirst { it.colorName == "Purple" }
        val forced = listOf(aqua, cyan, purple, aqua, aqua, cyan)
        for (seed in 1L .. 10L) {
            val result = run(layout, userSettings.copy(seed = seed, target = 5), forced)
            if (result.failure != null || result.stoppedAt != 6) fail("seed $seed: $result\n${result.dump()}")
            val clicked = result.clicks.map { layout.noteOf(it.slot) }
            val expected = (1 .. 6).flatMap { round -> forced.take(round) }
            assertEquals(expected, clicked, "seed $seed")
        }
    }

    @Test
    fun `repeated notes are clicked as often as they were shown`() {
        val patterns = listOf(
            listOf(0, 0, 0, 1, 1, 0, 0, 2, 2, 2, 2, 1),
            listOf(3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3),
            listOf(1, 2, 1, 2, 2, 1, 1, 1, 2, 2, 1, 2)
        )
        for (pattern in patterns) assertAllSolved(12, seeds = 1L .. 8L) { layout, seed -> run(layout, userSettings.copy(seed = seed), pattern) }
    }

    @Test
    fun `any packet order within a server tick`() {
        for (order in PacketOrder.entries) {
            assertAllSolved(12, seeds = 1L .. 10L) { layout, seed -> run(layout, userSettings.copy(seed = seed, packetOrder = order)) }
        }
    }

    @Test
    fun `middle clicks, fast clicks and no resync`() {
        assertAllSolved(12, seeds = 1L .. 10L) { layout, seed -> run(layout, userSettings.copy(seed = seed, clickType = ClickType.MIDDLE)) }
        assertAllSolved(12, seeds = 1L .. 10L) { layout, seed -> run(layout, userSettings.copy(seed = seed, clickDelay = 100, delayVariance = 0)) }
        assertAllSolved(12, seeds = 1L .. 10L) { layout, seed -> run(layout, userSettings.copy(seed = seed, clickDelay = 200, delayVariance = 50)) }
        assertAllSolved(12, seeds = 1L .. 10L) { layout, seed -> run(layout, userSettings.copy(seed = seed, resyncOnClick = false)) }
    }

    @Test
    fun `high latency and lag spikes`() {
        assertAllSolved(12, seeds = 1L .. 10L) { layout, seed -> run(layout, userSettings.copy(seed = seed, latencyMs = 150L .. 400L)) }
        assertAllSolved(12, seeds = 1L .. 10L) { layout, seed ->
            val rng = Random(seed)
            val freezes = (1 .. 25).map { it * 4_000L + rng.nextLong(0, 3_000) to rng.nextLong(200, 900) }
            run(layout, userSettings.copy(seed = seed, freezes = freezes))
        }
    }

    /** Glows long enough to run into the next replay (same note: no dark gap) and notes lit for almost a whole period. */
    @Test
    fun `long glows and long notes`() {
        val slow = ChronomatronTiming(firstShow = 10 .. 14, period = 16 .. 18, lit = 13 .. 15, glow = 12 .. 20)
        assertAllSolved(12, seeds = 1L .. 15L) { layout, seed -> run(layout, userSettings.copy(seed = seed), timing = slow) }
        assertAllSolved(12, seeds = 1L .. 5L) { layout, seed -> run(layout, userSettings.copy(seed = seed), listOf(0, 0, 0, 0, 1, 1, 1, 0, 0, 1, 1, 0), slow) }
    }

    /**
     * Notes lit longer than the replay period: two notes overlap, and the same note shown twice in a row (or replayed
     * last and added as the new note) never goes dark in between. Not what the logs show, but the known chain must carry
     * the solver through it.
     */
    @Test
    fun `overlapping notes`() {
        val overlapping = ChronomatronTiming(firstShow = 14 .. 18, period = 14 .. 16, lit = 18 .. 22, glow = 6 .. 10)
        assertAllSolved(12, seeds = 1L .. 10L) { layout, seed -> run(layout, userSettings.copy(seed = seed), timing = overlapping) }
        assertAllSolved(12, seeds = 1L .. 5L) { layout, seed -> run(layout, userSettings.copy(seed = seed), listOf(0, 1, 1, 2, 2, 2, 0, 0, 1, 1, 1, 1), overlapping) }
    }

    /** Settings of the user: Chronomatron Rounds 10 - 3 serums = 7, so the experiment is closed after the 8th round. */
    @Test
    fun `stop rule closes after the target round`() {
        assertAllSolved(8, seeds = 1L .. 10L) { layout, seed -> run(layout, userSettings.copy(seed = seed, target = 7)) }
    }

    private val handover: (SimResult) -> String? = { result ->
        when {
            result.humanClicks == 0 -> "the player never had to answer"
            result.blockedHumanClicks > 0 -> "the player was blocked while the solver was not answering"
            result.clicks.isEmpty() -> "the solver never took over"
            else -> null
        }
    }

    /** Enabled mid-game: watches (without blocking the player) while the chain is unknown, then takes over. */
    @Test
    fun `joining mid-experiment watches and then takes over`() {
        for (order in PacketOrder.entries) assertAllSolved(12, seeds = 1L .. 10L, extra = handover) { layout, seed ->
            val attach = 9_000L + Random(seed).nextLong(0, 4_000)
            run(layout, userSettings.copy(seed = seed, packetOrder = order, attachAtMs = attach, manualPlayer = true))
        }
    }

    /** Enabled just as a round starts: the whole replay is seen and matches the timer, so that round is answered already. */
    @Test
    fun `joining at a round start answers that round from the replay`() {
        for (round in listOf(2, 4, 6)) assertAllSolved(12, seeds = 1L .. 10L, extra = { result ->
            val expected = (1 until round).sum()
            if (result.humanClicks != expected) "the player had to click ${result.humanClicks} times instead of $expected" else handover(result)
        }) { layout, seed ->
            run(layout, userSettings.copy(seed = seed, attachAtRemember = round, manualPlayer = true))
        }
    }
}
