package experiments

import com.laybalt.features.impl.general.experiments.ItemKind
import kotlin.random.Random

/**
 * Superpairs (Skyblocker, SkyHanni, NoFrills): a grid of hidden cards (cyan stained glass named "Click any button!" /
 * "Click a second button!" while one card is open); a click reveals the reward under a card; two equal rewards stay
 * revealed, otherwise both flip back after a moment. Every reveal costs one of the limited clicks ("Remaining Clicks: N"
 * in slot 4). Powerups are single cards.
 */
class SuperpairsServer(
    rng: Random,
    private val slots: List<Int> = (9 .. 44).toList(),
    powerups: Int = 0,
    private var clicksLeft: Int = 80,
    private val flipBackTicks: Int = 20
): SimServer() {
    private val rewards = HashMap<Int, String>()
    private val revealed = HashSet<Int>()
    private val matched = HashSet<Int>()
    private var open = - 1
    private var flipAt = - 1
    private var flipping = emptyList<Int>()
    var pairsFound = 0
        private set
    var reveals = 0
        private set
    val pairs: Int
    override val finished get() = pairsFound == pairs

    init {
        val cards = slots.shuffled(rng)
        val pairCards = cards.size - powerups
        pairs = pairCards / 2
        for (i in 0 until pairs * 2) rewards[cards[i]] = "reward${i / 2}"
        for (i in pairs * 2 until cards.size) rewards[cards[i]] = "powerup${i}"
    }

    override fun onTick(tick: Int) {
        if (flipAt in 0 .. tick) {
            revealed.removeAll(flipping)
            flipAt = - 1
        }
        for (slot in 0 until 54) board[slot] = SimItem.BORDER
        for (slot in slots) board[slot] = when {
            slot in revealed || slot in matched -> SimItem(ItemKind.OTHER, name = rewards.getValue(slot), card = rewards.getValue(slot))
            else -> SimItem(ItemKind.GLASS, color = 9, name = if (open >= 0) "Click a second button!" else "Click any button!")
        }
        board[4] = SimItem.status("Remaining Clicks: $clicksLeft")
        board[49] = SimItem.status("Superpairs")
    }

    override fun onClick(tick: Int, slot: Int) {
        if (slot !in rewards || slot in revealed || slot in matched || flipAt >= 0 || clicksLeft == 0) {
            ignoredClicks ++
            return
        }
        clicksLeft --
        reveals ++
        revealed += slot
        val reward = rewards.getValue(slot)
        when {
            reward.startsWith("powerup") -> Unit // a single card: stays revealed
            open < 0 -> open = slot
            rewards[open] == reward -> {
                matched += listOf(open, slot)
                revealed.removeAll(listOf(open, slot))
                pairsFound ++
                roundsCompleted = pairsFound
                open = - 1
            }
            else -> {
                flipping = listOf(open, slot)
                flipAt = tick + flipBackTicks
                open = - 1
            }
        }
    }
}
