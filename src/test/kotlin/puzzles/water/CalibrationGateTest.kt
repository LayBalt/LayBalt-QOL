package puzzles.water

import com.laybalt.features.impl.dungeon.solvers.puzzles.water.*
import puzzles.water.ToyBoards.A
import puzzles.water.ToyBoards.B
import puzzles.water.ToyBoards.C
import puzzles.water.ToyBoards.D
import puzzles.water.ToyBoards.E
import puzzles.water.ToyBoards.F
import kotlin.test.*

/**
 * The calibration gate on the toy comb with a synthetic "shipped table": hand-written plays recorded in the simulator
 * under planted timing, a planted outlet-to-door mapping and a planted untouched lever state. The gate must find them
 * again. No Hypixel data is involved.
 */
class CalibrationGateTest {
    private val layout = ToyBoards.comb
    private val planted = WaterRules(leverDelay = 1, sourceDelay = 15, externalDelay = 4)
    private val plantedPerm = intArrayOf(2, 4, 0, 1, 3) // outlet index -> door digit

    /**
     * Two plays whose outcome depends on when the water lands under the entry (6 ticks after it reaches the entry hole):
     * the first only works if C, open at the start, is closed again before the water falls into it (water delay >= 11),
     * the second only if the water spreads before C opens under it (water delay < 21).
     */
    private val timingSensitive = listOf(
        setOf(C) to listOf(C to 1.0, B to 3.0, D to 3.0, C to 4.0),
        emptySet<WaterLever>() to listOf(C to 1.5, A to 3.5, E to 3.5),
    )

    /** Records every candidate under [rules] and keeps those that toggle three distinct outlets once each. */
    private fun syntheticTable(rules: WaterRules, perm: IntArray, startOffset: Int = 0): ShippedTable {
        val plays = LinkedHashMap<Int, ShippedPlay>()
        for ((pre, timed) in timingSensitive + ToyBoards.candidates) {
            val probe = ToyBoards.play("probe", pre, *timed.toTypedArray())
            val outcome = SimRunner.runPlay(layout, rules, probe, startOffset)
            if (outcome.rewet || Integer.bitCount(outcome.arrived) != 3) continue
            val doors = CalibrationResult.mapDoors(outcome.arrived, perm)
            if (doors in plays) continue
            plays[doors] = ToyBoards.play(Door.keyOf(doors), pre, *timed.toTypedArray())
        }
        return ShippedTable(0, plays.values)
    }

    @Test
    fun `the synthetic table covers every outlet`() {
        val table = syntheticTable(planted, plantedPerm)
        assertTrue(table.plays.size >= 8, "${table.plays.size} plays")
        assertEquals(Door.ALL, table.plays.keys.fold(0) { acc, d -> acc or d })
    }

    @Test
    fun `the gate recovers the planted timing and outlet mapping`() {
        val table = syntheticTable(planted, plantedPerm)
        val result = Calibration.calibrate(CalibrationInput(layout, table, WaterRules(), startOffset = 0, uncertain = 0))
        assertEquals(SimTrust.TRUSTED, result.trust, result.summary)
        assertEquals(table.plays.size, result.passes)
        assertContentEquals(plantedPerm, result.outletToDoor)
        val waterMinusLever = result.rules.sourceDelay - result.rules.leverDelay
        assertTrue(waterMinusLever in 10 .. 19, "fitted water delay $waterMinusLever (planted 14)")
        assertTrue(result.checks.all { it.passed })
    }

    @Test
    fun `a measured delay narrows the grid to the same answer`() {
        val table = syntheticTable(planted, plantedPerm)
        val result = Calibration.calibrate(CalibrationInput(layout, table, WaterRules(), 0, 0, measuredWaterMinusLever = 14))
        assertEquals(SimTrust.TRUSTED, result.trust)
        assertTrue(result.rules.sourceDelay - result.rules.leverDelay in 11 .. 17)
    }

    @Test
    fun `a wrong guess of the untouched lever state is corrected when that lever is uncertain`() {
        val table = syntheticTable(planted, plantedPerm)
        // We believe F was flipped before our snapshot (it was not): plays through B and A fail until the gate corrects it.
        val wrong = Calibration.calibrate(CalibrationInput(layout, table, WaterRules(), startOffset = F.bit, uncertain = 0))
        assertTrue(wrong.passes < table.plays.size)
        val fixed = Calibration.calibrate(CalibrationInput(layout, table, WaterRules(), startOffset = F.bit, uncertain = F.bit or A.bit))
        assertEquals(SimTrust.TRUSTED, fixed.trust, fixed.summary)
        assertEquals(0, fixed.startOffset)
        assertTrue(fixed.startOffsetChanged)
    }

    @Test
    fun `with every lever in doubt the gate still finds the one untouched state that makes the plays work`() {
        val table = syntheticTable(planted, plantedPerm)
        val fixed = Calibration.calibrate(CalibrationInput(layout, table, WaterRules(), startOffset = F.bit or C.bit, uncertain = 0b111111))
        assertEquals(SimTrust.TRUSTED, fixed.trust, fixed.summary)
        assertEquals(0, fixed.startOffset)
    }

    @Test
    fun `a board the simulator cannot reproduce stays untrusted`() {
        val table = syntheticTable(planted, plantedPerm)
        // Same plays, but the doors scrambled per play: no single mapping and timing explains them.
        val scrambled = ShippedTable(0, table.plays.values.mapIndexed { i, p ->
            val doors = table.keys[(i + 3) % table.keys.size]
            ShippedPlay(0, Door.keyOf(doors), p.times)
        }.distinctBy { it.doors })
        val result = Calibration.calibrate(CalibrationInput(layout, scrambled, WaterRules(), 0, 0))
        assertNotEquals(SimTrust.TRUSTED, result.trust)
    }

    @Test
    fun `with one play no timing reproduces, the timing kept is the middle of the best ones and the play is named`() {
        // 3.2.4 field logs: 9/10 on variants 0-2 with "water +1" / "+3" - the first grid point that passed nine plays.
        val table = syntheticTable(planted, plantedPerm)
        val plays = table.plays.values.sortedBy { it.key }
        // One play gets another play's times: it toggles that play's doors under every timing and mapping.
        val victim = plays.first()
        val donor = plays.first { it.doors and victim.doors == 0 || Integer.bitCount(it.doors and victim.doors) == 1 }
        val broken = ShippedTable(0, plays.map { if (it === victim) ShippedPlay(0, victim.key, donor.times) else it })
        val result = Calibration.calibrate(CalibrationInput(layout, broken, WaterRules(), 0, 0))
        assertEquals(plays.size - 1, result.passes, result.summary)
        assertEquals(SimTrust.DEGRADED, result.trust)
        assertTrue(result.summary.contains("failing: ${victim.key}"), result.summary)
        // Without the broken play the others already pass with the water right after the lever (the grid's first
        // points) - which is what 3.2.4 kept. The middle of the run of equally good timings is kept now.
        val low = Calibration.calibrate(CalibrationInput(layout, broken, WaterRules(), 0, 0, measuredWaterMinusLever = 0))
        assertEquals(plays.size - 1, low.passes, "timings right after the lever pass as many plays: ${low.summary}")
        val waterMinusLever = result.rules.sourceDelay - result.rules.leverDelay
        assertTrue(waterMinusLever > 4, "kept water delay $waterMinusLever, not the first grid point: ${result.summary}")
    }

    @Test
    fun `a board without five outlets is untrusted at once`() {
        val rows = ToyBoards.combRows().toMutableList()
        rows[5] = rows[5].replaceFirst('O', '.')
        val result = Calibration.calibrate(CalibrationInput(BoardLayout.fromAscii(rows), syntheticTable(planted, plantedPerm), WaterRules(), 0, 0))
        assertEquals(SimTrust.UNTRUSTED, result.trust)
        assertTrue(result.summary.contains("4 outlets"))
    }

    @Test
    fun `the static table lists the doors of each lever setting from a dry board`() {
        val table = syntheticTable(planted, plantedPerm)
        val result = Calibration.calibrate(CalibrationInput(layout, table, WaterRules(), 0, 0))
        val static = assertNotNull(result.staticFromDry)
        fun doorOfLever(l: WaterLever) = 1 shl plantedPerm[ToyBoards.outletOfLever(l.ordinal)]
        assertEquals(0, static[0])
        assertEquals(doorOfLever(C), static[C.bit])
        assertEquals(doorOfLever(B) or doorOfLever(D), static[B.bit or D.bit])
        assertEquals(doorOfLever(A) or doorOfLever(E), static[A.bit or E.bit])
    }

    @Test
    fun `cancellation stops the gate`() {
        val table = syntheticTable(planted, plantedPerm)
        var calls = 0
        assertFailsWith<java.util.concurrent.CancellationException> {
            Calibration.calibrate(CalibrationInput(layout, table, WaterRules(), 0, 0), CancelToken { ++ calls > 3 })
        }
    }

    /**
     * Plays that turn the water off and then open holes: what the slug still reaches depends on how much sooner the
     * water stops than it starts. Kept: those whose doors differ between [WaterRules.sourceOffLead] 0 and 5.
     */
    private fun slugPlays(truth: WaterRules, other: WaterRules, taken: Set<Int>): List<ShippedPlay> {
        val out = ArrayList<ShippedPlay>()
        val used = HashSet(taken)
        // Turn the water off, open one hole, then a second one: a longer slug still reaches the second hole.
        val holes = listOf(A, B, C, D, E)
        for (off in listOf(0.75, 1.0, 1.25, 1.5)) for (first in holes) for (second in holes) for (gap in 0 .. 15) for (d in listOf(0.25, 0.5, 1.0)) {
            if (first == second) continue
            val at = off + gap * 0.1
            val times = mapOf(WaterLever.WATER to listOf(0.0, off), first to listOf(at), second to listOf(at + d))
            val probe = ShippedPlay(0, "probe", times)
            val real = SimRunner.runPlay(layout, truth, probe, 0)
            val alt = SimRunner.runPlay(layout, other, probe, 0)
            if (real.rewet || real.arrived == 0 || real.arrivals.contentEquals(alt.arrivals)) continue
            val doors = CalibrationResult.mapDoors(real.arrived, plantedPerm)
            if (! used.add(doors)) continue
            out += ShippedPlay(0, Door.keyOf(doors), times)
        }
        return out
    }

    @Test
    fun `the gate fits how much sooner the water stops than it starts`() {
        val truth = planted.copy(sourceOffLead = 5)
        val base = syntheticTable(truth, plantedPerm)
        val slug = slugPlays(truth, truth.copy(sourceOffLead = 0), base.plays.keys)
        assertTrue(slug.isNotEmpty(), "no play on the comb depends on the length of the slug")
        val table = ShippedTable(0, base.plays.values + slug)
        val result = Calibration.calibrate(CalibrationInput(layout, table, WaterRules(), 0, 0))
        assertEquals(SimTrust.TRUSTED, result.trust, result.summary)
        assertEquals(5, result.rules.sourceOffLead, result.summary)

        // Measured on our own clicks, the lead narrows the grid; a wrong measurement cannot reproduce the slug plays.
        val wrong = Calibration.calibrate(CalibrationInput(layout, table, WaterRules(), 0, 0, measuredOffLead = 0))
        assertTrue(wrong.passes < table.plays.size, wrong.summary)
    }
}
