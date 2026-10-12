package puzzles.water

import com.laybalt.features.impl.dungeon.solvers.puzzles.water.Door
import com.laybalt.features.impl.dungeon.solvers.puzzles.water.DoorAlgebra
import kotlin.test.*

class DoorAlgebraTest {
    private val expectedPlays = mapOf(1 to 3, 2 to 2, 3 to 1, 4 to 2, 5 to 3)
    private val expectedOptions = mapOf(1 to 10, 2 to 3, 3 to 1, 4 to 3, 5 to 10)

    @Test
    fun `every door state is reachable with the minimal number of plays`() {
        for (table in WaterTestData.tables.values) for (closed in 1 .. Door.ALL) {
            val combos = DoorAlgebra.combos(closed, table.keys)
            val weight = Door.count(closed)
            assertEquals(expectedOptions[weight], combos.size, "variant ${table.variant} closed ${Door.keyOf(closed)}")
            combos.forEach { combo ->
                assertEquals(expectedPlays[weight], combo.size)
                assertEquals(closed, combo.fold(0) { acc, i -> acc xor table.keys[i] })
            }
        }
    }

    @Test
    fun `best chain XORs to the closed doors and is deterministic`() {
        for (table in WaterTestData.tables.values) for (closed in 1 .. Door.ALL) for (parity in listOf(0, 0b101101)) {
            val chain = DoorAlgebra.best(closed, table, parity, boardReady = true)
            assertEquals(expectedPlays[Door.count(closed)], chain.size, "${Door.keyOf(closed)}")
            assertEquals(closed, chain.fold(0) { acc, p -> acc xor p.doors })
            assertEquals(chain, DoorAlgebra.best(closed, table, parity, boardReady = true))
        }
    }

    @Test
    fun `three closed doors give the matching shipped play`() {
        val table = WaterTestData.tables.getValue(2)
        val chain = DoorAlgebra.best(Door.bitsOf("024"), table, 0, boardReady = true)
        assertEquals(listOf("024"), chain.map { it.key })
    }

    @Test
    fun `nothing closed needs no play`() {
        val table = WaterTestData.tables.getValue(0)
        assertEquals(emptyList(), DoorAlgebra.combos(0, table.keys))
        assertEquals(emptyList(), DoorAlgebra.best(0, table, 0, boardReady = true))
    }

    @Test
    fun `a missing play is worked around with a longer chain`() {
        val table = WaterTestData.tables.getValue(0)
        val keys = table.keys.filter { it != Door.bitsOf("012") }.toIntArray()
        val combos = DoorAlgebra.combos(Door.bitsOf("012"), keys)
        assertTrue(combos.isNotEmpty())
        combos.forEach { combo -> assertEquals(Door.bitsOf("012"), combo.fold(0) { acc, i -> acc xor keys[i] }) }
        assertTrue(combos.all { it.size == 3 })
    }

    @Test
    fun `cost prefers chains whose start state is closest to the current levers`() {
        val table = WaterTestData.tables.getValue(0)
        val chain = DoorAlgebra.best(Door.bitsOf("01"), table, 0, boardReady = true)
        val cost = DoorAlgebra.cost(chain, 0, boardReady = true)
        for (combo in DoorAlgebra.combos(Door.bitsOf("01"), table.keys)) {
            val plays = combo.map { table.play(table.keys[it])!! }
            assertTrue(cost <= DoorAlgebra.cost(plays, 0, true) && cost <= DoorAlgebra.cost(plays.reversed(), 0, true))
        }
    }
}
