package experiments

import com.laybalt.features.impl.general.experiments.UltrasequencerSolver
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.fail

class UltrasequencerSolverTest {
    /** The user's settings: Click Delay 460, Delay Variance 180, Left click, Ultrasequencer Rounds 9 - 3 serums = 6. */
    private val userSettings = SimConfig(clickType = ClickType.LEFT, clickDelay = 460, delayVariance = 180, target = 6)

    private fun run(config: SimConfig): SimResult {
        val server = UltrasequencerServer(Random(config.seed * 17 + 3))
        return ExperimentSimulation(server, config) { host, _ -> UltrasequencerSolver(host) }.run()
    }

    /**
     * The stop rule fires before a sequence longer than the target is answered: [rounds] answered, stopped at [rounds] + 1.
     */
    private fun assertAllSolved(rounds: Int, seeds: LongRange = 1L .. 20L, extra: (SimResult) -> String? = { null }, case: (Long) -> SimResult) {
        val broken = ArrayList<Pair<String, SimResult>>()
        for (seed in seeds) {
            val result = case(seed)
            val clean = result.failure == null && result.stoppedAt == rounds + 1 && result.roundsCompleted == rounds && result.ignoredClicks == 0
            val problem = if (clean) extra(result) else "not solved"
            if (problem != null) broken += "seed $seed: $problem; $result" to result
        }
        if (broken.isEmpty()) return
        fail("${broken.size} of ${seeds.count()} runs broke:\n" + broken.joinToString("\n") { it.first } + "\n--- log of the first one ---\n" + broken.first().second.dump())
    }

    @Test
    fun `answers up to the target with the reported settings`() {
        assertAllSolved(6) { seed -> run(userSettings.copy(seed = seed)) }
    }

    @Test
    fun `max xp sequences`() {
        assertAllSolved(20, seeds = 1L .. 8L) { seed -> run(userSettings.copy(seed = seed, target = 20, clickDelay = 200, delayVariance = 50)) }
    }

    @Test
    fun `any packet order, click type, latency and lag`() {
        for (order in PacketOrder.entries) assertAllSolved(9, seeds = 1L .. 8L) { seed -> run(userSettings.copy(seed = seed, target = 9, packetOrder = order)) }
        assertAllSolved(9, seeds = 1L .. 8L) { seed -> run(userSettings.copy(seed = seed, target = 9, clickType = ClickType.MIDDLE, clickDelay = 100, delayVariance = 0)) }
        assertAllSolved(9, seeds = 1L .. 8L) { seed -> run(userSettings.copy(seed = seed, target = 9, latencyMs = 150L .. 400L, resyncOnClick = false)) }
        assertAllSolved(9, seeds = 1L .. 8L) { seed ->
            val rng = Random(seed)
            run(userSettings.copy(seed = seed, target = 9, freezes = (1 .. 20).map { it * 4_000L + rng.nextLong(0, 3_000) to rng.nextLong(200, 900) }))
        }
    }

    /** Enabled mid-game: a round already in its timer is left to the player, the next one is answered. */
    @Test
    fun `joining mid-experiment`() {
        assertAllSolved(9, seeds = 1L .. 10L, extra = { if (it.blockedHumanClicks > 0) "the player was blocked" else null }) { seed ->
            run(userSettings.copy(seed = seed, target = 9, attachAtMs = 5_000L + Random(seed).nextLong(0, 15_000), manualPlayer = true))
        }
    }
}
