package puzzles.water

import com.laybalt.features.impl.dungeon.solvers.puzzles.water.*
import com.laybalt.features.impl.dungeon.solvers.puzzles.water.WaterLever.*
import kotlin.test.*

/** The 3.2.3 baseline display: activation from the doors, the shipped play for the doors closed now, click counting. */
class ClassicDisplayTest {
    private val stable = ClassicDisplay.GATE_STABLE_TICKS

    private fun display(variant: Int = 2) = ClassicDisplay(WaterTestData.tables).also { it.onVariant(variant, 0) }

    /** Reads [closed] at the door blocks every tick for [ticks] ticks from [from]; returns the tick after. */
    private fun ClassicDisplay.read(closed: String, from: Int, ticks: Int = stable + 1, moving: Int = 0): Int {
        for (t in from until from + ticks) {
            onGates(GateRead(Door.bitsOf(closed), moving), t)
            tick(t)
        }
        return from + ticks
    }

    private fun Guidance?.act() = assertIs<Guidance.Act>(this)
    private fun Guidance.Act.texts(lever: WaterLever) = labels.filter { it.lever == lever }.map { it.text }

    @Test
    fun `no door closed is not started - nothing is shown and nothing is solved, however long`() {
        val c = display()
        c.read("", 0, 2000)
        assertFalse(c.activated)
        assertFalse(c.solved)
        assertNull(c.guidance(2000))
    }

    @Test
    fun `three doors closed show the shipped play for them, as 3_2_3 did`() {
        val c = display(2)
        val t = c.read("034", 100)
        assertTrue(c.activated)
        assertEquals("034", c.play?.key)
        // v2/034: quartz 0, gold 0, emerald 0 3.2 4.4 10.2, water 0 5, diamond 7.9 11.9
        val g = c.guidance(t).act()
        assertEquals(listOf("§a§lCLICK"), g.texts(QUARTZ))
        assertEquals(listOf("§a§lCLICK"), g.texts(GOLD))
        assertEquals(listOf("§a§lCLICK", "§e3.2s", "§e4.4s", "§a10.2s"), g.texts(EMERALD))
        assertEquals(listOf("§a§lCLICK", "§e5.0s"), g.texts(WATER))
        assertEquals(listOf("§a7.9s", "§a11.9s"), g.texts(DIAMOND))
        assertEquals(QUARTZ, g.tracer)
        assertEquals(QUARTZ to GOLD, g.line)
        assertNull(g.headline)
        // Labels stack half a block per flip of the same lever.
        assertEquals(listOf(0, 1, 2, 3), g.labels.filter { it.lever == EMERALD }.map { it.stack })
    }

    @Test
    fun `our clicks consume the labels and the first click on the water starts the countdowns`() {
        val c = display(2)
        var t = c.read("034", 100)
        c.onClick(QUARTZ, t)
        c.onClick(GOLD, t)
        c.onClick(EMERALD, t)
        var g = c.guidance(t).act()
        assertTrue(g.texts(QUARTZ).isEmpty())
        assertEquals(WATER, g.tracer, "the water is next")
        c.onClick(WATER, t)
        assertEquals(t, c.waterTick)
        t += 20
        g = c.guidance(t).act()
        assertEquals(listOf("§e2.2s", "§e3.4s", "§a9.2s"), g.texts(EMERALD))
        assertEquals(listOf("§e4.0s"), g.texts(WATER))
        t += 60
        g = c.guidance(t).act()
        assertEquals("§a§lCLICK", g.texts(EMERALD).first(), "due")
        // A click for the first timed flip consumes it; the stack of the rest moves up.
        c.onClick(EMERALD, t)
        g = c.guidance(t).act()
        assertEquals(2, g.texts(EMERALD).size)
        assertEquals(listOf(2, 3), g.labels.filter { it.lever == EMERALD }.map { it.stack })
    }

    @Test
    fun `a door read must hold before it counts - a flicker or an unknown read changes nothing`() {
        val c = display(3)
        var t = c.read("012", 0)
        // Blue reads open for 3 ticks: shorter than the stable time.
        t = c.read("01", t, 3)
        t = c.read("012", t, 20)
        assertEquals(Door.bitsOf("012"), c.gates)
        assertEquals(1, c.rekeys)
        // Unloaded: no read at all for a long time, then the doors are back as they were.
        repeat(300) { c.onGates(null, t + it) }
        t = c.read("012", t + 300)
        assertEquals(Door.bitsOf("012"), c.gates)
        assertFalse(c.solved)
        // A piston moving Purple: neither open nor closed, the last state is kept.
        t = c.read("12", t, 20, moving = Door.PURPLE.bit)
        assertEquals(Door.bitsOf("012"), c.gates)
    }

    @Test
    fun `the play follows the doors closed now while no run is going, and stays during a run`() {
        val c = display(0)
        var t = c.read("014", 0)
        // Before the water: doors changing (activation spread over ticks, a reset) give the play for the new doors.
        t = c.read("024", t)
        assertEquals("024", c.play?.key)
        c.onClick(WATER, t)
        // During the run doors change as the water reaches them: the play stays.
        t = c.read("24", t)
        assertEquals("024", c.play?.key)
        // Once the run is over (long past its last click) the doors closed now get their plays, fresh.
        t = c.read("24", t, WaterTestData.play(0, "024").lastClickTick + ClassicDisplay.RUN_OVER_TICKS)
        assertEquals(Door.bitsOf("24"), c.key)
        assertEquals(- 1, c.waterTick)
        assertEquals(2, c.chain.size, "two closed doors take two shipped plays")
        val g = c.guidance(t).act()
        assertTrue(g.headline!!.startsWith("§dRun 1/2"), g.headline)
    }

    @Test
    fun `every door that was closed open again is solved - and a door closing again undoes that`() {
        val c = display(1)
        var t = c.read("134", 0)
        c.onClick(WATER, t)
        t = c.read("", t)
        assertTrue(c.solved)
        assertEquals(Guidance.Solved, c.guidance(t))
        t = c.read("3", t)
        assertFalse(c.solved)
        assertNotEquals(Guidance.Solved, c.guidance(t))
    }

    @Test
    fun `an unknown layout says so, the variant read later brings the play`() {
        val c = ClassicDisplay(WaterTestData.tables)
        var t = c.read("012", 0)
        assertTrue(c.activated)
        val waiting = assertIs<Guidance.Status>(c.guidance(t))
        assertTrue(waiting.headline.contains("layout"))
        c.onVariant(3, t)
        t ++
        assertEquals("012", c.play?.key)
        assertTrue(c.guidance(t) is Guidance.Act)
    }

    @Test
    fun `without the solution data it never claims anything`() {
        val c = ClassicDisplay(null).also { it.onVariant(2, 0) }
        val t = c.read("034", 0)
        val g = assertIs<Guidance.Status>(c.guidance(t))
        assertTrue(g.headline.contains("no solution"))
    }

    @Test
    fun `every shipped play of every variant is shown with a label per flip`() {
        for ((variant, table) in WaterTestData.tables) for (play in table.plays.values) {
            val c = display(variant)
            val t = c.read(play.key, 0)
            val g = c.guidance(t).act()
            assertEquals(play.times.values.sumOf { it.size }, g.labels.size, "$play")
            assertFalse(g.silent)
        }
    }
}
