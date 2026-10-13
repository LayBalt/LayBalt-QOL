package puzzles.water

import com.laybalt.features.impl.dungeon.solvers.puzzles.water.*
import com.laybalt.features.impl.dungeon.solvers.puzzles.water.WaterLever.*
import com.laybalt.features.impl.dungeon.solvers.puzzles.water.WaterRoomCore.Mode
import com.laybalt.features.impl.dungeon.solvers.puzzles.water.WaterSupervisor.Stage
import kotlin.test.*

/**
 * The door, water and lever sequences of the 3.2.4 field logs (latest-2026-10-13.log, 2026-10-13-5.log) replayed through
 * the real room logic ([WaterRoomCore]: tracker, supervisor, classic display and the choice between them). In every
 * 3.2.4 session the board was read before Hypixel closed the doors, the supervisor called it solved after 100 ticks and
 * the player saw nothing at all for the rest of the dungeon.
 */
class FieldReplayTest {

    @Test
    fun `23-12 session - read from the start room, doors close on entry 27 s later, the solution shows`() {
        val b = FieldBoard(variant = 2)
        // 23:12:09 "variant 2, closed doors -": read from the start room, before "Starting in 4 seconds".
        b.ticks(27 * 20)
        assertEquals(Stage.READING, b.sup!!.stage, "a board whose doors never closed is not started")
        assertFalse(b.core.classic.activated)
        assertEquals(Mode.STATUS, b.core.mode)
        assertTrue((b.display as Guidance.Status).headline.contains("waiting for the doors"))
        assertTrue(b.shown.none { it == Guidance.Solved }, "never solved before the doors closed")

        // 23:12:36 "the Purple/Green/Red door closed at 392657": all three in one tick as someone walks in.
        b.tick { closeDoors("034") }
        b.ticks(12)
        assertTrue(b.modes.contains(Mode.CLASSIC), "the classic display is up first")
        assertEquals(Mode.ADVANCED, b.core.mode)
        assertEquals(Stage.PREPARE, b.sup!!.stage)
        assertEquals(listOf("034"), b.sup!!.chain.map { it.key })
        assertEquals("034", Door.keyOf(b.core.classic.key))
        // The untouched display: CLICK on the levers flipped before the water, the timers of the rest.
        for (lever in listOf(QUARTZ, GOLD, EMERALD)) assertEquals("§a§lCLICK", b.labels(lever).first(), "$lever")
        assertTrue(b.labels(DIAMOND).any { it.endsWith("7.9s") })
        assertTrue(b.act!!.labels.isNotEmpty())

        // 23:13:5x-23:14:04: our own clicks before the water, then the water.
        b.tick { click(QUARTZ) }
        b.tick { click(GOLD) }
        b.tick { click(EMERALD) }
        b.ticks(3)
        assertEquals("§a§lCLICK", b.labels(WATER).first(), "the water is next")
        b.tick { click(WATER) }
        val start = b.now - 1
        val play = WaterTestData.play(2, "034")
        // The run, on time: every timed click of v2/034 (the water goes off again at 5 s); the water reaches the entry.
        fun runUntil(t: Int, extra: FieldBoard.(Int) -> Unit = {}) {
            while (b.now - 1 - start < t) b.tick {
                val at = now - 1 - start
                for (c in play.timedClicks) if (c.tick == at) click(c.lever)
                if (at == 12) entry(true)
                if (at in 12 until 110) boardWater(8)
                if (at == 112) entry(false)
                extra(at)
            }
        }
        runUntil(20)
        assertEquals(Stage.RUNNING, b.sup!!.stage)
        assertEquals(Mode.ADVANCED, b.core.mode)
        assertTrue(b.labels(EMERALD).any { it.endsWith("s") }, "countdowns: ${b.labels(EMERALD)}")

        // 23:14:11, about 7 s in: Red reads open for 3 ticks and closed again, then Green - no door toggled.
        runUntil(170) { at ->
            when (at) {
                140 -> doors = doors and Door.RED.bit.inv()
                143 -> doors = doors or Door.RED.bit
                144 -> doors = doors and Door.GREEN.bit.inv()
                147 -> doors = doors or Door.GREEN.bit
            }
        }
        assertEquals(Door.bitsOf("034"), b.core.tracker.doors)
        assertEquals(Door.bitsOf("034"), b.core.classic.gates)
        assertEquals(Stage.RUNNING, b.sup!!.stage, "the run goes on: ${b.messages}")
        assertEquals(emptyList(), b.messages, "no failure for a flicker")
        assertTrue(b.tracedToggles().none { it.contains("opened") }, "no door toggle: ${b.tracedToggles()}")
    }

    @Test
    fun `every variant and door set - after the doors close the shipped play for them is shown`() {
        for ((variant, table) in WaterTestData.tables) for (play in table.plays.values) {
            val b = FieldBoard(variant)
            b.ticks(200)
            assertEquals(Stage.READING, b.sup!!.stage, "$play")
            b.tick { closeDoors(play.key) }
            b.ticks(12)
            assertEquals(Mode.ADVANCED, b.core.mode, "$play: ${b.core.advancedProblem}")
            assertEquals(listOf(play.key), b.sup!!.chain.map { it.key }, "$play")
            assertEquals(play.key, b.core.classic.play?.key, "$play")
            assertTrue(b.act!!.labels.isNotEmpty(), "$play")
            assertNull(b.act!!.headline, "$play: the untouched display")
        }
    }

    @Test
    fun `22-59 session - a teammate pulls the water while we walk away, we are revived 39 s later`() {
        val b = FieldBoard(variant = 3)
        b.ticks(400)
        // 22:59:22 a teammate walks in: Purple, Orange and Blue close.
        b.tick { closeDoors("012") }
        b.ticks(12)
        assertEquals(Stage.PREPARE, b.sup!!.stage)
        // The teammate sets the levers of v3/012 and pulls the water.
        val play = WaterTestData.play(3, "012")
        for (lever in WaterLever.inMask(play.startParity)) b.tick { flip(lever) }
        b.tick { flip(WATER) }
        b.ticks(4)
        // We walk away (bridges-6): the board's chunks are dropped before the water reaches the entry.
        b.available = false
        b.ticks(60 * 10)
        assertTrue(b.log.none { it.contains("no water") }, "no 'water lever changed but no water came in' while unwatched: ${b.log}")
        assertTrue(b.traced("correction").isEmpty())
        // 23:00:01 revived: the board is read again. Purple and Orange opened meanwhile, the water still runs.
        b.available = true
        b.tick {
            doors = Door.bitsOf("2")
            entry(true)
            boardWater(10)
        }
        assertTrue(b.traced("read again after").isNotEmpty(), b.traceLines.takeLast(5).toString())
        assertTrue(b.tracedToggles().none { it.contains("opened") }, "doors found open by the re-read are no toggles: ${b.tracedToggles()}")
        assertEquals(Stage.FOREIGN, b.sup!!.stage, "a run nobody here watched is watched like a teammate's")
        assertEquals(emptyList(), b.messages)
        // The water is turned off and drains: the doors left (Blue) are planned.
        b.tick { flip(WATER) }
        b.ticks(20) { entry(false); boardWater(0) }
        b.ticks(30)
        assertEquals(Stage.PREPARE, b.sup!!.stage)
        assertEquals(Door.bitsOf("2"), b.sup!!.chain.fold(0) { acc, p -> acc xor p.doors })
        assertTrue(b.act!!.labels.isNotEmpty())
        assertEquals(Mode.ADVANCED, b.core.mode)
    }

    @Test
    fun `a teammate solves the board while we watch - solved, every toggle had water at its outlet`() {
        val check = DoorWaterHooks()
        val b = FieldBoard(variant = 3, hooks = check)
        check.tracker = b.core.tracker
        b.ticks(20)
        b.tick { closeDoors("012") }
        b.ticks(12)
        b.tick { flip(WATER) }
        b.ticks(12) { entry(true) }
        // Water reaches the Purple (x=9), Orange (x=5) and Blue (x=0) outlets one after the other; each door follows.
        for ((x, door) in listOf(9 to Door.PURPLE, 5 to Door.ORANGE, 0 to Door.BLUE)) {
            b.ticks(20) { boardWater(10) }
            b.tick { wet(x, 61); wet(x, 60) }
            b.ticks(3)
            b.tick { doors = doors and door.bit.inv() }
            b.ticks(15)
        }
        b.ticks(20)
        assertEquals(Stage.SOLVED, b.sup!!.stage)
        assertEquals(Guidance.Solved, b.display)
        assertEquals(emptyList(), check.reports, "every door had water in its outlet column")
        assertEquals(emptyList(), b.messages)
    }

    @Test
    fun `door changes when the doors are set up and after a re-read are not reported as toggles without water`() {
        val check = DoorWaterHooks()
        val b = FieldBoard(variant = 1, hooks = check)
        check.tracker = b.core.tracker
        b.ticks(20)
        // 18:17:23 "the Orange/Green/Red door closed at 46961 without water at its outlet": that was the activation.
        b.tick { closeDoors("134") }
        b.ticks(30)
        assertEquals(emptyList(), check.reports)
        // Water ran, then we were away and Green opened meanwhile: found by the re-read, not reported.
        b.tick { flip(WATER) }
        b.ticks(12) { entry(true) }
        b.available = false
        b.ticks(100)
        b.available = true
        b.tick { doors = Door.bitsOf("14") }
        b.ticks(30)
        assertEquals(emptyList(), check.reports)
        // A door that really changes with no water anywhere near its outlet is reported.
        b.tick { doors = Door.bitsOf("4") }
        b.ticks(30)
        assertEquals(1, check.reports.size, "${check.reports}")
        assertTrue(check.reports.single().contains("Orange"))
    }

    @Test
    fun `death and revive - a lever moved while we were away is shown as a fix, no failure`() {
        val b = FieldBoard(variant = 0)
        b.ticks(20)
        b.tick { closeDoors("014") }
        b.ticks(12)
        assertEquals(Stage.PREPARE, b.sup!!.stage)
        // Killed by a Crypt Lurker: the chunks go, a teammate flips Coal meanwhile, we are revived 6 s later.
        b.available = false
        b.ticks(120)
        b.flip(COAL)
        b.available = true
        b.ticks(2)
        assertTrue(b.traced("read again after").isNotEmpty())
        assertEquals(Stage.PREPARE, b.sup!!.stage)
        assertEquals(Mode.ADVANCED, b.core.mode)
        assertEquals(listOf("§lFLIP BACK"), b.labels(COAL), "Coal is not part of v0/014: flip it back first")
        assertEquals(emptyList(), b.messages)
        assertTrue(b.traced("flip Coal").isEmpty(), "the unseen flip is no timed flip")
    }

    @Test
    fun `the water lever flicked twice between two reads is no water switch`() {
        val b = FieldBoard(variant = 3)
        b.ticks(20)
        b.tick { closeDoors("012") }
        b.ticks(12)
        // Both flips land before the next read (a double flick): the lever reads unchanged... or changed and back.
        b.core.tracker.setPowered(WATER, true)
        b.core.tracker.setPowered(WATER, false)
        b.ticks(80)
        assertEquals(Stage.PREPARE, b.sup!!.stage)
        assertTrue(b.traced("water on").isEmpty(), b.traceLines.toString())
        assertTrue(b.traced("correction").isEmpty())
        assertEquals(emptyList(), b.messages)
    }

    @Test
    fun `an exception in the board-truth side leaves the classic display, never nothing`() {
        val b = FieldBoard(variant = 2, hooks = object : WaterRoomCore.Hooks {
            override fun events(core: WaterRoomCore, events: List<BoardEvent>) {
                if (events.any { it is BoardEvent.DoorToggled }) error("boom")
            }
        })
        b.ticks(20)
        b.tick { closeDoors("034") }
        b.ticks(15)
        assertTrue(b.core.advancedFailed)
        assertEquals(Mode.CLASSIC, b.core.mode)
        val g = assertIs<Guidance.Act>(b.display)
        assertEquals("§a§lCLICK", g.labels.first { it.lever == QUARTZ }.text)
        assertEquals(QUARTZ, g.tracer)
        assertTrue(b.traced("failed").isNotEmpty())
        // The classic display goes on with our clicks: after the pre-water flips the water is next.
        b.tick { click(QUARTZ) }
        b.tick { click(GOLD) }
        b.tick { click(EMERALD) }
        assertEquals(WATER, (b.display as Guidance.Act).tracer)
    }

    @Test
    fun `unloaded chunks are never read as open doors - nothing is concluded until they are back`() {
        val b = FieldBoard(variant = 1)
        b.available = false
        b.doorsLoaded = false
        b.ticks(400)
        assertEquals(Mode.STATUS, b.core.mode)
        assertTrue((b.display as Guidance.Status).headline.contains("walk closer"))
        assertFalse(b.core.classic.activated)
        assertTrue(b.shown.none { it == Guidance.Solved })
        // Back in range with the doors already closed: the puzzle is taken from them.
        b.available = true
        b.doorsLoaded = true
        b.closeDoors("024")
        b.ticks(12)
        assertEquals(Mode.ADVANCED, b.core.mode)
        assertEquals(listOf("024"), b.sup!!.chain.map { it.key })

        // Chunks dropped again later: the doors are not read as open, the puzzle is not solved.
        b.available = false
        b.doorsLoaded = false
        b.doors = 0
        b.ticks(200)
        assertFalse(b.core.classic.solved)
        assertNotEquals(Guidance.Solved, b.display)
        assertEquals(Door.bitsOf("024"), b.core.classic.gates)
    }

    @Test
    fun `doors that close while the rest of the board is unloaded still bring up the classic display`() {
        val b = FieldBoard(variant = 0)
        b.ticks(30)
        // The board's chunks are dropped, the door blocks' chunk stays: the doors are read, the board is not.
        b.available = false
        b.ticks(30)
        b.tick { closeDoors("123") }
        b.ticks(10)
        assertEquals(Mode.CLASSIC, b.core.mode, "${b.core.advancedProblem}")
        assertEquals("123", b.core.classic.play?.key)
        assertTrue(b.act!!.labels.isNotEmpty())
    }

    @Test
    fun `without any read of the layout the classic display says so instead of guessing`() {
        val b = FieldBoard(variant = 0)
        b.available = false
        b.ticks(30)
        b.tick { closeDoors("123") }
        b.ticks(10)
        assertEquals(Mode.CLASSIC, b.core.mode)
        assertTrue((b.display as Guidance.Status).headline.contains("layout"))
    }

    @Test
    fun `a door piston caught mid-move keeps the door's last state`() {
        val b = FieldBoard(variant = 3)
        b.ticks(20)
        b.tick { closeDoors("012") }
        b.ticks(12)
        // Blue is being moved: neither open nor closed for 3 ticks, then closed again.
        b.ticks(3) { doorsMoving = Door.BLUE.bit; doors = doors and Door.BLUE.bit.inv() }
        b.tick { doorsMoving = 0; doors = doors or Door.BLUE.bit }
        b.ticks(20)
        assertEquals(Door.bitsOf("012"), b.core.classic.gates)
        assertEquals(Door.bitsOf("012"), b.core.tracker.doors)
        assertEquals(Mode.ADVANCED, b.core.mode)
    }

    @Test
    fun `solved from the doors, then a door closes again before the chest - the guidance comes back`() {
        val b = FieldBoard(variant = 0)
        b.ticks(20)
        b.tick { closeDoors("014") }
        b.ticks(12)
        b.tick { flip(WATER) }
        b.ticks(12) { entry(true); boardWater(5) }
        b.tick { doors = 0 }
        b.ticks(12)
        assertEquals(Guidance.Solved, b.display)
        b.tick { doors = Door.BLUE.bit }
        b.ticks(12)
        assertNotEquals(Guidance.Solved, b.display)
        assertFalse(b.display.silent)

        // Once the map (or the chest) says done, it stays done.
        b.core.markSolved("the map shows the room done")
        b.tick { doors = Door.bitsOf("01") }
        b.ticks(12)
        assertEquals(Guidance.Solved, b.display)
        assertEquals(Mode.SOLVED, b.core.mode)
    }

    @Test
    fun `the layout read and the indicator disagreeing hands the display to the classic one`() {
        // The indicator blocks read as another layout than the board's toggle blocks: the board-truth side is not trusted.
        val b = FieldBoard(variant = 2).apply { indicatorRead = 1 }
        b.ticks(20)
        b.tick { closeDoors("034") }
        b.ticks(12)
        assertEquals(Mode.CLASSIC, b.core.mode)
        assertTrue(b.core.advancedProblem!!.contains("layout"), b.core.advancedProblem)
        assertFalse(b.display.silent)
    }

    /** Feeds a [DoorWaterCheck] like the simulator bridge does, collecting its reports. */
    private class DoorWaterHooks : WaterRoomCore.Hooks {
        lateinit var tracker: BoardTracker
        private val check by lazy { DoorWaterCheck(tracker) }
        val reports = ArrayList<String>()

        override fun events(core: WaterRoomCore, events: List<BoardEvent>) = check.onEvents(events)

        override fun afterUpdate(core: WaterRoomCore, solved: Boolean) {
            reports += check.tick(core.now) { DoorWaterCheck.DEFAULT_OUTLET_X[it.digit] }
        }
    }
}
