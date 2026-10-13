package puzzles.water

import com.laybalt.features.impl.dungeon.solvers.puzzles.water.*
import kotlin.test.*

/** A simulator state synced from what the client sees evolves like the board itself. */
class StateSyncTest {
    private val layout = ToyBoards.comb
    private val rules = WaterRules(leverDelay = 1, sourceDelay = 15, externalDelay = 4)

    /** The board as the client sees it: levels, and when and in which order each cell last changed (packet order). */
    private class Observer(size: Int) : SimListener {
        val changeTick = IntArray(size) { BoardTracker.NEVER }
        val changeSeq = IntArray(size)
        var seq = 0
        val flips = ArrayList<Pair<Int, Int>>()

        private fun seen(cell: Int, tick: Int) {
            changeTick[cell] = tick
            changeSeq[cell] = ++ seq
        }

        override fun placed(cell: Int, level: Int, tick: Int) = seen(cell, tick)
        override fun removed(cell: Int, tick: Int) = seen(cell, tick)
        override fun changed(cell: Int, level: Int, tick: Int) = seen(cell, tick)
    }

    /** Runs [s] to [until], tick by tick, recording what an observer would see. */
    private fun watch(s: BoardState, o: Observer, until: Int) {
        while (s.tick < until) {
            val mask = s.mask
            WaterSim.step(s, o)
            if (s.mask != mask) for (l in 0 until 6) if ((s.mask xor mask) and (1 shl l) != 0) o.flips += l to s.tick
        }
    }

    private fun wet(s: BoardState) = (0 until layout.size).filter { s.wet(it) }.toSet()

    @Test
    fun `a board synced in the middle of a run evolves like the board`() {
        for (syncAt in listOf(30, 47, 62, 75, 96, 113)) {
            val truth = BoardState(layout, rules)
            truth.mask = ToyBoards.C.bit
            truth.addExternals(listOf(External.Source(1 + 15, true), External.Flip(1 + 41, ToyBoards.C.ordinal), External.Flip(1 + 41, ToyBoards.B.ordinal), External.Flip(1 + 41, ToyBoards.D.ordinal)))
            val o = Observer(layout.size)
            watch(truth, o, syncAt)
            val arrivedBefore = truth.arrivals.copyOf()
            val synced = StateSync.sync(
                StateSync.SyncInput(layout, rules, truth.level.copyOf(), o.changeTick, o.changeSeq, truth.mask, truth.sourceOn, syncAt,
                    o.flips.filter { it.second > syncAt - 6 }, truth.externals.drop(truth.nextExternal))
            )
            for (t in listOf(syncAt + 5, syncAt + 20, syncAt + 60, syncAt + 150)) {
                WaterSim.run(truth, t, stopWhenSettled = false)
                WaterSim.run(synced, t, stopWhenSettled = false)
                assertEquals(wet(truth), wet(synced), "synced at $syncAt, compared at $t")
            }
            assertContentEquals(IntArray(truth.arrivals.size) { truth.arrivals[it] - arrivedBefore[it] }, synced.arrivals, "arrivals after the sync at $syncAt")
        }
    }

    @Test
    fun `water left in the entry after the source went off drains away`() {
        val s = BoardState(layout, rules)
        s.addExternal(External.Source(1, true))
        val o = Observer(layout.size)
        watch(s, o, 60)
        // The water was switched off a while ago, but the board still shows water in the entry (it is static there).
        val synced = StateSync.sync(StateSync.SyncInput(layout, rules, s.level.copyOf(), o.changeTick, o.changeSeq, s.mask, false, 60))
        WaterSim.run(synced, 400)
        assertEquals(0, synced.waterCount())
    }

    @Test
    fun `a water click whose effect is still on its way is applied when it lands`() {
        val s = BoardState(layout, rules)
        val synced = StateSync.sync(
            StateSync.SyncInput(layout, rules, s.level.copyOf(), IntArray(layout.size) { BoardTracker.NEVER }, IntArray(layout.size), 0, false, 10,
                pending = listOf(External.Source(20, true)))
        )
        WaterSim.run(synced, 19, stopWhenSettled = false)
        assertEquals(0, synced.waterCount())
        WaterSim.run(synced, 40, stopWhenSettled = false)
        assertTrue(synced.waterCount() > 0)
    }
}
