package experiments

import com.laybalt.features.impl.general.experiments.ItemKind
import kotlin.random.Random

/**
 * Ultrasequencer as Hypixel runs it (old LayBalt debug logs, Skyblocker, SkyHanni, NoFrills, OdinClient): the playing
 * field (rows 1-4) is filled with stained glass panes of one colour per round; during "Remember the pattern!" k of them
 * are replaced by dyes named "1".."k" (stack size = number), shown for ~2 s plus 1 s every third round; then the dyes turn
 * back into panes and the status becomes "Timer: (k+3)s"; the panes must be clicked in number order. The last correct
 * click starts the next round (new colour, k+1 numbers) at once; a wrong click or the timer running out ends it.
 */
class UltrasequencerServer(private val rng: Random) : SimServer() {
    private enum class Phase { REMEMBER, TIMER }

    private val field = (1 .. 4).flatMap { row -> (1 .. 7).map { col -> row * 9 + col } }
    private val colors = listOf(0, 3, 11, 10, 2, 6, 14, 1, 4, 5, 13, 9, 7, 8, 12)

    /** slot of number i+1 */
    private val order = ArrayList<Int>()
    private var phase = Phase.REMEMBER
    private var phaseEnd = 0
    private var progress = 0
    private var round = 0

    private fun startRound(tick: Int) {
        round ++
        order.clear()
        order += field.shuffled(rng).take(round)
        phase = Phase.REMEMBER
        progress = 0
        phaseEnd = tick + 40 + 20 * ((round - 1) / 3)
    }

    override fun onTick(tick: Int) {
        if (tick == 0) {
            for (slot in 0 until 54) board[slot] = SimItem.BORDER
            startRound(0)
        }
        when (phase) {
            Phase.REMEMBER -> if (tick >= phaseEnd) {
                phase = Phase.TIMER
                phaseEnd = tick + (round + 3) * 20
            }

            Phase.TIMER -> if (tick >= phaseEnd) {
                failure = "timer ran out in round $round after $progress clicks"
                return
            }
        }

        val color = colors[(round - 1) % colors.size]
        for (slot in field) board[slot] = SimItem(ItemKind.PANE, color, name = " ")
        if (phase == Phase.REMEMBER) order.forEachIndexed { i, slot -> board[slot] = SimItem(ItemKind.OTHER, name = "${i + 1}", count = i + 1) }
        board[49] = SimItem.status(if (phase == Phase.TIMER) "Timer: ${(phaseEnd - tick + 19) / 20}s" else "Remember the pattern!")
    }

    override fun expectedSlot(index: Int) = if (phase == Phase.TIMER) order.getOrNull(index) else null

    override fun onClick(tick: Int, slot: Int) {
        if (phase != Phase.TIMER) {
            ignoredClicks ++
            return
        }
        if (slot != order[progress]) {
            failure = "wrong click in round $round, click ${progress + 1}: slot $slot instead of ${order[progress]}"
            return
        }
        if (++ progress == order.size) {
            roundsCompleted = round
            startRound(tick)
        }
    }
}
