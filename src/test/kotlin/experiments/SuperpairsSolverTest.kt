package experiments

import com.laybalt.features.impl.general.experiments.SuperpairsSolver
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.fail

class SuperpairsSolverTest {
    private fun run(config: SimConfig, powerups: Int = 0, clicks: Int = 80, flipBackTicks: Int = 20): Pair<SuperpairsServer, SimResult> {
        val server = SuperpairsServer(Random(config.seed * 13 + 5), powerups = powerups, clicksLeft = clicks, flipBackTicks = flipBackTicks)
        return server to ExperimentSimulation(server, config) { host, _ -> SuperpairsSolver(host, Random(config.seed)) }.run()
    }

    private fun assertAllFound(maxReveals: Int, seeds: LongRange = 1L .. 20L, case: (Long) -> Pair<SuperpairsServer, SimResult>) {
        val broken = ArrayList<Pair<String, SimResult>>()
        for (seed in seeds) {
            val (server, result) = case(seed)
            val problem = when {
                result.failure != null -> "failed"
                server.pairsFound != server.pairs -> "found ${server.pairsFound} of ${server.pairs} pairs"
                result.ignoredClicks > 0 -> "${result.ignoredClicks} clicks on revealed cards or while cards flipped back"
                server.reveals > maxReveals -> "${server.reveals} reveals (memory not used, max $maxReveals)"
                else -> null
            }
            if (problem != null) broken += "seed $seed: $problem; $result reveals=${server.reveals}" to result
        }
        if (broken.isEmpty()) return
        fail("${broken.size} of ${seeds.count()} runs broke:\n" + broken.joinToString("\n") { it.first } + "\n--- log of the first one ---\n" + broken.first().second.dump(80))
    }

    @Test
    fun `finds every pair with the reported settings`() {
        // 18 pairs: with perfect memory every card is explored once and re-revealed at most once more (36 + 36);
        // perfect-memory play averages ~3.2 reveals per pair.
        assertAllFound(72) { seed -> run(SimConfig(seed = seed, clickType = ClickType.LEFT, clickDelay = 460, delayVariance = 180)) }
    }

    @Test
    fun `middle clicks, fast clicks and powerups`() {
        assertAllFound(72) { seed -> run(SimConfig(seed = seed, clickType = ClickType.MIDDLE, clickDelay = 200, delayVariance = 50)) }
        assertAllFound(72) { seed -> run(SimConfig(seed = seed, clickType = ClickType.LEFT, clickDelay = 100, delayVariance = 0, latencyMs = 100L .. 250L)) }
        assertAllFound(74, seeds = 1L .. 10L) { seed -> run(SimConfig(seed = seed, clickType = ClickType.LEFT), powerups = 2) }
    }

    /** A wrong pair that takes longer than 1.5 s to flip back must not be clicked through. */
    @Test
    fun `slow flip back`() {
        assertAllFound(72, seeds = 1L .. 10L) { seed -> run(SimConfig(seed = seed, clickType = ClickType.LEFT, clickDelay = 200, delayVariance = 0), flipBackTicks = 50) }
    }

    /** Out of clicks: the solver stops instead of clicking into a finished game. */
    @Test
    fun `stops when the clicks run out`() {
        for (seed in 1L .. 10L) {
            val server = SuperpairsServer(Random(seed * 13 + 5), clicksLeft = 20)
            val result = ExperimentSimulation(server, SimConfig(seed = seed, clickType = ClickType.LEFT, clickDelay = 200, delayVariance = 0, maxMs = 40_000)) { host, _ -> SuperpairsSolver(host, Random(seed)) }.run()
            if (result.failure != null || server.reveals != 20 || result.ignoredClicks > 0) fail("seed $seed: $result reveals=${server.reveals}\n${result.dump(40)}")
        }
    }
}
