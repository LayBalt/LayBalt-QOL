package experiments

import com.laybalt.features.impl.general.experiments.ExperimentGame
import com.laybalt.features.impl.general.experiments.Stake
import com.laybalt.features.impl.general.experiments.UltrasequencerSolver
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The lore lines are made up around the reward lines SkyHanni reads ("Chain of N:" / "Series of N:"); the counts are the wiki's Metaphysical ones. */
class ExperimentStakesTest {
    @Test
    fun `the last chain or series line of the picked stake is the round count for the most clicks`() {
        val chronomatron = listOf(
            "Stakes: Metaphysical", "", "Rewards:", "Chain of 5: +1 Superpairs Click", "Chain of 9: +2 Superpairs Clicks",
            "Chain of 12: +3 Superpairs Clicks", "", "Click to play!",
        )
        assertEquals(Stake.Rewards(12), Stake.of(chronomatron))
        assertEquals(Stake.Rewards(9), Stake.of(listOf("Series of 5: +1 Superpairs Click", "Series of 7: +2", "Series of 9: +3", "Click to play!")))
    }

    @Test
    fun `practice, a locked stake or a stake without reward lines`() {
        assertEquals(Stake.Practice, Stake.of(listOf("Practice mode has no rewards", "Click to play!")))
        assertNull(Stake.of(listOf("Chain of 12: +3 Superpairs Clicks", "Enchanting level too low!")), "the click starts no game")
        assertNull(Stake.of(listOf("Chain of 12: +3 Superpairs Clicks", "Not enough experience!")))
        assertNull(Stake.of(listOf("Click to play!")))
        assertNull(Stake.of(emptyList()))
    }

    @Test
    fun `chronomatron answers all N rounds, ultrasequencer the whole series unless the early stop is on`() {
        // the solvers stop when "size > target"
        assertEquals(11, Stake.target(ExperimentGame.CHRONOMATRON, 12))
        assertEquals(11, Stake.target(ExperimentGame.CHRONOMATRON, 12, ultrasequencerEarly = true), "Chronomatron is never cut short")
        assertEquals(9, Stake.target(ExperimentGame.ULTRASEQUENCER, 9))
        assertEquals(8, Stake.target(ExperimentGame.ULTRASEQUENCER, 9, ultrasequencerEarly = true))
    }

    private fun ultrasequencer(target: Int, seed: Long) = ExperimentSimulation(
        UltrasequencerServer(Random(seed * 17 + 3)), SimConfig(clickType = ClickType.LEFT, clickDelay = 200, delayVariance = 50, target = target, seed = seed)
    ) { host, _ -> UltrasequencerSolver(host) }.run()

    @Test
    fun `ultrasequencer answers the series of N and stops as the next sequence shows, or as N shows with the early stop`() {
        for (seed in 1L .. 10L) {
            val safe = ultrasequencer(Stake.target(ExperimentGame.ULTRASEQUENCER, 8), seed)
            assertTrue(safe.failure == null && safe.roundsCompleted == 8 && safe.stoppedAt == 9 && safe.ignoredClicks == 0, "seed $seed: $safe")
            val early = ultrasequencer(Stake.target(ExperimentGame.ULTRASEQUENCER, 8, ultrasequencerEarly = true), seed)
            assertTrue(early.failure == null && early.roundsCompleted == 7 && early.stoppedAt == 8 && early.ignoredClicks == 0, "seed $seed: $early")
        }
    }
}
