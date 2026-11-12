package fishing

import com.laybalt.features.impl.fishing.AutoMobKiller.BoxEntry
import com.laybalt.features.impl.fishing.AutoMobKiller.Escalation
import com.laybalt.features.impl.fishing.AutoMobKiller.FightRules
import com.laybalt.features.impl.fishing.AutoMobKiller.FreeWait
import com.laybalt.features.impl.fishing.AutoMobKiller.HandLag
import com.laybalt.features.impl.fishing.AutoMobKiller.HoldCycle
import com.laybalt.features.impl.fishing.AutoMobKiller.HomeWatch
import com.laybalt.features.impl.fishing.AutoMobKiller.MeleeOutcome
import com.laybalt.features.impl.fishing.AutoMobKiller.MissOdds
import com.laybalt.features.impl.fishing.AutoMobKiller.ParkedLoadout
import com.laybalt.features.impl.fishing.AutoMobKiller.PlanBook
import com.laybalt.features.impl.fishing.AutoMobKiller.PlanBook.Change
import com.laybalt.features.impl.fishing.AutoMobKiller.Reaction
import com.laybalt.features.impl.fishing.AutoMobKiller.SlotWatch
import com.laybalt.features.impl.fishing.AutoMobKiller.UseOwner
import com.laybalt.features.impl.fishing.AutoMobKiller.WonBack
import com.laybalt.features.impl.fishing.MotorModel.View
import com.laybalt.features.impl.fishing.BehaviorProfile
import com.laybalt.features.impl.fishing.BobberFlight
import com.laybalt.features.impl.fishing.BehaviorProfile.Habit
import com.laybalt.features.impl.fishing.ClickStyle
import com.laybalt.features.impl.fishing.HumanAim
import com.laybalt.features.impl.fishing.KillRules.AttackMode
import com.laybalt.features.impl.fishing.KillRules.KillAction
import com.laybalt.features.impl.fishing.KillRules.KillRule
import com.laybalt.features.impl.fishing.LoadoutSwitcher
import com.laybalt.features.impl.fishing.RecastTimer
import com.laybalt.features.impl.fishing.TimingModel
import com.laybalt.features.impl.fishing.ViewPolicy
import com.laybalt.features.impl.fishing.ViewPolicy.TurnBack
import com.laybalt.features.impl.fishing.WeaponPlanner
import com.laybalt.features.impl.fishing.WeaponPlanner.Plan
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.random.Random
import kotlin.test.*

/** The pure parts of Auto Fishing's recast, the killer's view angles, plans, escalation and holds, and the loadout confirmation. */
class AutoMobKillerTest {
    private val rod = WeaponPlanner.rodPlan
    private val flay = Plan("FLAMING_FLAY", ClickStyle.HOLD, 10.5, - 6.0, 4.0, 32.0, false, 21.0)
    private val hyperion = Plan("HYPERION", ClickStyle.RIGHT, 3.0, - 3.0, 3.0, 178.0, false, 146.0, 89f)
    private val all = setOf("ROD", "FLAMING_FLAY", "HYPERION")

    /** A chooser like [WeaponPlanner.choose] over [order]: the first one in [available] that is not excluded and reaches [hd], [dy]. */
    private fun chooser(hd: Double, dy: Double, order: List<Plan> = listOf(rod, flay), available: Set<String> = all): (Set<String>, Boolean) -> Plan? =
        { excluded, shrink -> order.firstOrNull { it.option in available && it.option !in excluded && WeaponPlanner.inEnvelope(it, hd, dy, shrink) } }

    private fun PlanBook.at(id: Int, now: Long, hd: Double, dy: Double, order: List<Plan> = listOf(rod, flay), available: Set<String> = all) =
        update(id, now, hd, dy, available, chooser(hd, dy, order, available))

    @Test
    fun `a plan's need for the view, as far as a look around goes`() {
        assertEquals(ViewPolicy.Need.MELEE, FightRules.planNeed(rod))
        assertEquals(ViewPolicy.Need.RANGED, FightRules.planNeed(flay))
        assertEquals(ViewPolicy.Need.PITCH, FightRules.planNeed(hyperion), "a teleport blade looks down")
        assertEquals(ViewPolicy.Need.NONE, FightRules.planNeed(Plan("HELLFIRE_ROD", ClickStyle.RIGHT, 8.0, - 6.0, 4.0, 94.0, false, 90.0)), "an area weapon")
    }

    @Test
    fun `the recast after a catch comes on the 4th tick after the reel click, like a held use button`() {
        val timer = RecastTimer()
        timer.start(200)
        assertTrue(timer.pending)
        assertEquals(listOf(false, false, false, true), List(4) { timer.tick() })
        assertFalse(timer.pending)
        assertFalse(timer.tick(), "fires once")

        // a recorded 180-220 ms is the same 4 ticks; at least one tick, never on the reel tick itself
        assertEquals(4, RecastTimer.ticks(180))
        assertEquals(4, RecastTimer.ticks(220))
        assertEquals(6, RecastTimer.ticks(300))
        assertEquals(1, RecastTimer.ticks(0))

        timer.start(200)
        timer.cancel()
        assertFalse(timer.pending)
        assertFalse(timer.tick())
    }

    @Test
    fun `view angles are great-circle angles, so a yaw swing looking straight down is a small turn`() {
        assertEquals(90f, HumanAim.angle(0f, 0f, 90f, 0f), 1e-3f)
        assertEquals(20f, HumanAim.angle(170f, 0f, - 170f, 0f), 1e-3f, "across the yaw wrap")
        assertEquals(30f, HumanAim.angle(0f, 10f, 0f, 40f), 1e-3f)
        // looking almost straight down, 64 degrees of yaw are about 4 degrees of turn
        assertEquals(4.24f, HumanAim.angle(0f, 86f, 64f, 86f), 0.01f)
        assertEquals(0f, HumanAim.angle(0f, 90f, 180f, 90f), 1e-3f)
    }

    @Test
    fun `turning back to a view straight up or down only counts the pitch`() {
        assertEquals(0.3f, HumanAim.distance(37f, 89.7f, 200f, 90f), 1e-3f)
        assertTrue(HumanAim.distance(37f, - 89.6f, 200f, - 89.5f) <= 1f, "nothing to turn back")
        // below the pole pitch the yaw counts again
        assertTrue(HumanAim.distance(37f, 60f, 200f, 60f) > 1f)
    }

    @Test
    fun `a loadout click is confirmed by the chat line naming it`() {
        val c = LoadoutSwitcher.Confirmation
        assertTrue(c.confirms("You equipped Loadout 8!", "Loadout 8"))
        assertTrue(c.confirms("  You equipped loadout 8!  ", "Loadout 8"), "case and spaces")
        assertFalse(c.confirms("You equipped Loadout 9!", "Loadout 8"), "another loadout")
        assertTrue(c.confirms("You equipped Fishing!", ""), "any loadout while its name is unknown")
        assertFalse(c.confirms("You equipped Loadout 8", "Loadout 8"))
        assertFalse(c.confirms("[MVP+] Someone: You equipped Loadout 8!", "Loadout 8"), "player chat")
    }

    @Test
    fun `a plan sticks while the creature stays in its reach and never goes back to the rod by itself`() {
        val book = PlanBook { 2000L }
        assertEquals(Change.PLANNED, book.at(1, 0, 2.0, 0.0))
        assertEquals("ROD", book.plans[1]?.option)
        // it swims off: out of the rod's reach the Flay takes over
        assertEquals(Change.SWITCHED, book.at(1, 100, 5.0, 0.0))
        assertEquals("FLAMING_FLAY", book.plans[1]?.option)
        // back within 3 m the Flay still reaches it and stays (no rod-Flay-rod swapping)
        assertEquals(Change.NONE, book.at(1, 200, 2.0, 0.0))
        assertEquals("FLAMING_FLAY", book.plans[1]?.option)
        // the re-choose after 2 s would pick the rod: never back to the rod
        assertEquals(Change.NONE, book.at(1, 2200, 2.0, 0.0))
        assertEquals("FLAMING_FLAY", book.plans[1]?.option)
        // ... but it may move on to another weapon
        assertEquals(Change.NONE, book.at(1, 2300, 2.0, 0.0, listOf(hyperion, flay)), "not before another 2 s")
        assertEquals(Change.SWITCHED, book.at(1, 4300, 2.0, 0.0, listOf(hyperion, flay)))
        assertEquals("HYPERION", book.plans[1]?.option)
    }

    @Test
    fun `a plan is chosen again after a drawn interval each time, so no switch falls on a fixed grid`() {
        val intervals = ArrayDeque(listOf(1700L, 2600L, 2100L))
        val book = PlanBook { intervals.removeFirst() }
        assertEquals(Change.PLANNED, book.at(1, 0, 2.0, 0.0))
        assertEquals("ROD", book.plans[1]?.option)
        // next re-choose at 1700, the one after 2600 later
        assertEquals(Change.NONE, book.at(1, 1650, 2.0, 0.0, listOf(hyperion, flay)))
        assertEquals(Change.SWITCHED, book.at(1, 1700, 2.0, 0.0, listOf(hyperion, flay)))
        assertEquals(1, intervals.size)
        // the default: around the 2 s it used to be, never twice the same
        val rng = Random(41)
        val d = List(40_000) { TimingModel.draw(TimingModel.Action.RECHOOSE, emptyList(), null, rng) }.sorted()
        assertEquals(2000.0, d[20_000].toDouble(), 100.0)
        assertTrue(d[4_000] < 1500 && d[36_000] > 2700, "p10 ${d[4_000]}, p90 ${d[36_000]}")
        assertTrue(d.groupingBy { it }.eachCount().values.max() < 40_000 * 0.01, "a pile")
    }

    @Test
    fun `a creature nothing reaches is parked and comes back once a plan reaches it with a margin after 1,5 s`() {
        val book = PlanBook()
        // sunk 25 blocks below you: parked, Auto Fishing goes on
        assertEquals(Change.PARKED, book.at(1, 0, 5.0, - 25.0))
        assertNull(book.plans[1])
        assertTrue(1 in book.parked)
        // checked again only every 500 ms
        assertEquals(Change.NONE, book.at(1, 400, 2.0, 0.0))
        assertTrue(1 in book.parked)
        // in reach, but parked for less than 1.5 s
        assertEquals(Change.NONE, book.at(1, 600, 2.0, 0.0))
        assertTrue(1 in book.parked)
        // later, but only at the edge of the Flay's reach (10.5): no margin, so it stays parked
        assertEquals(Change.NONE, book.at(1, 1600, 10.2, 0.0))
        assertTrue(1 in book.parked)
        // well inside the reach: back
        assertEquals(Change.UNPARKED, book.at(1, 2200, 5.0, 0.0))
        assertEquals("FLAMING_FLAY", book.plans[1]?.option)
        assertEquals(2200L, book.unparkedAt[1])
        assertFalse(1 in book.parked)
    }

    @Test
    fun `an option that did nothing is excluded for that creature only, and back 15 s after the last one`() {
        val book = PlanBook()
        book.at(1, 0, 2.0, 0.0)
        book.exclude(1, "ROD", 1000)
        assertEquals(setOf("ROD"), book.excluded(1))
        assertNull(book.plans[1])
        assertEquals(Change.PLANNED, book.at(1, 1000, 2.0, 0.0))
        assertEquals("FLAMING_FLAY", book.plans[1]?.option)
        // another creature still gets the rod
        assertEquals(Change.PLANNED, book.at(2, 1000, 2.0, 0.0))
        assertEquals("ROD", book.plans[2]?.option)
        // everything excluded: parked, not given up
        book.exclude(1, "FLAMING_FLAY", 2000)
        assertEquals(Change.PARKED, book.at(1, 2000, 2.0, 0.0))
        assertEquals(Change.NONE, book.at(1, 16_000, 2.0, 0.0), "still excluded")
        assertTrue(1 in book.parked)
        assertEquals(Change.UNPARKED, book.at(1, 17_000, 2.0, 0.0))
        assertEquals("ROD", book.plans[1]?.option)
        assertTrue(book.excluded(1).isEmpty())
    }

    @Test
    fun `a plan whose item left the hotbar is chosen again, and gone creatures are forgotten`() {
        val book = PlanBook()
        book.at(1, 0, 5.0, 0.0)
        assertEquals("FLAMING_FLAY", book.plans[1]?.option)
        // the Flay is gone from the hotbar and the rod does not reach 5 m
        assertEquals(Change.PARKED, book.at(1, 100, 5.0, 0.0, available = setOf("ROD")))

        book.at(2, 0, 2.0, 0.0)
        book.exclude(2, "ROD", 0)
        book.retain(setOf(2))
        assertTrue(book.plans.isEmpty(), "2 has no plan after its exclusion, 1 is gone")
        assertFalse(1 in book.parked)
        assertEquals(setOf("ROD"), book.excluded(2))
        book.clear()
        assertTrue(book.excluded(2).isEmpty())
    }

    @Test
    fun `an option escalates after its drawn number of uses without an hp drop, and not within its drawn time`() {
        val uses = ArrayDeque(listOf(8, 6))
        val times = ArrayDeque(listOf(1500L, 2200L))
        val e = Escalation({ uses.removeFirst() }, { times.removeFirst() })
        e.start("ROD", 0)
        repeat(7) { e.use() }
        assertFalse(e.due(5000), "7 uses")
        e.use()
        assertFalse(e.due(1499), "8 uses, too soon")
        assertTrue(e.due(1500))
        // an hp drop starts over, with a number and a time of its own
        e.hpDrop(2000)
        assertEquals(0, e.uses)
        assertFalse(e.due(10_000))
        repeat(6) { e.use() }
        assertFalse(e.due(4199))
        assertTrue(e.due(4200))
        // explicit rules have no option and never escalate (nothing drawn for them)
        e.start("", 0)
        repeat(20) { e.use() }
        assertFalse(e.due(10_000))

        // drawn: never the same number of uses before every switch (it was 8, always), around the old 8 and 1.5 s; nothing
        // of yours measures a weapon that did nothing for long
        val rng = Random(51)
        val n = List(40_000) { FightRules.escalateUses(rng) }
        assertTrue(n.all { it in 4 .. 20 }, "${n.min()}..${n.max()}")
        assertEquals(8, n.sorted()[20_000])
        assertTrue(n.groupingBy { it }.eachCount().values.max() < 40_000 * 0.2, "${n.groupingBy { it }.eachCount()}")
        val ms = List(40_000) { TimingModel.draw(TimingModel.Action.ESCALATE, emptyList(), null, rng) }.sorted()
        assertEquals(1500.0, ms[20_000].toDouble(), 100.0)
        assertTrue(ms.last() <= 5000 && ms.groupingBy { it }.eachCount().values.max() < 40_000 * 0.01)
    }

    @Test
    fun `a held use key repeats every 4 ticks and is let go after its time`() {
        val h = HoldCycle()
        assertEquals(0, h.newRepeats(10), "nothing held")
        h.start(1000, 100, 800)
        assertTrue(h.holding)
        assertEquals(0, h.newRepeats(103))
        assertEquals(1, h.newRepeats(104))
        assertEquals(0, h.newRepeats(105))
        assertEquals(2, h.newRepeats(112), "ticks 108 and 112")
        assertFalse(h.releaseDue(1799))
        assertTrue(h.releaseDue(1800))
        h.stop()
        assertFalse(h.holding)
        assertFalse(h.releaseDue(5000))
        assertEquals(0, h.newRepeats(200))
    }

    @Test
    fun `a learned click needs the view within the plan's aim, an area weapon none`() {
        // when the view turns is ViewPolicy's (ViewPolicyTest); the plans keep their aim for the click
        val t = FightRules
        assertTrue(t.aimOk(32.0, 32.0))
        assertFalse(t.aimOk(33.0, 32.0))
        assertTrue(t.aimOk(179.0, 178.0), "90 degrees or more: no aim needed")
    }

    @Test
    fun `a melee use that does not land counts towards escalation only with the view still on the creature and no turn pending`() {
        assertTrue(FightRules.countsUse(onTarget = true, moving = false, waiting = false), "on it, but too far or below you")
        assertFalse(FightRules.countsUse(onTarget = false, moving = false, waiting = false), "the creature is off the crosshair: the view, not the plan")
        assertFalse(FightRules.countsUse(onTarget = true, moving = true, waiting = false), "a move is still in flight")
        assertFalse(FightRules.countsUse(onTarget = true, moving = false, waiting = true), "the policy still waits to turn")
    }

    @Test
    fun `REACT waits for a pending recast only while it is due before the hit or swap it goes before, and only until then`() {
        assertFalse(FightRules.waitsForRecast(now = 1050, recastDue = 1100, leadAt = 1600, pending = false), "none pending: none will come for this catch")
        assertTrue(FightRules.waitsForRecast(now = 1050, recastDue = 1100, leadAt = 1600, pending = true), "the recast due first goes first")
        assertFalse(FightRules.waitsForRecast(now = 1050, recastDue = 2500, leadAt = 1600, pending = true), "a slow one due after the hit: the hit goes first")
        assertTrue(FightRules.waitsForRecast(now = 1550, recastDue = 1100, leadAt = 1600, pending = true), "held a while, still before the hit's time")
        assertFalse(FightRules.waitsForRecast(now = 1600, recastDue = 1100, leadAt = 1600, pending = true), "held past the hit's time: the hit goes then")
        // the catches as yours come: the reel 35-452 ms before the spawn, Auto Fishing's recast your wait after the reel
        // (slow ones now and then), the first hit from the spawn alone; whichever is due first goes
        val rng = Random(43)
        val catchRecast = TimingModel.StreakState()
        val hits = ArrayList<Long>()
        val lateRecast = ArrayList<Boolean>()
        val slowHits = ArrayList<Long>()
        repeat(40_000) {
            val reel = - rng.nextLong(35, 453)
            val due = TimingModel.dueAt(reel, TimingModel.draw(TimingModel.Action.CATCH_RECAST, emptyList(), catchRecast, rng))
            val lead = FightRules.firstRodHit(0L, null, TimingModel.TICK_MS, { error("no recast came") },
                { TimingModel.draw(TimingModel.Action.FIRST_HIT, emptyList(), null, rng) }) { TimingModel.draw(TimingModel.Action.FIRST_HIT, emptyList(), null, rng) }
            // out by REACT's first tick, or due before the hit and waited for
            val recastFirst = due <= TimingModel.TICK_MS || FightRules.waitsForRecast(TimingModel.TICK_MS, due, lead, pending = true)
            val hit = if (recastFirst) TimingModel.dueAt(due, TimingModel.draw(TimingModel.Action.BOBBER_MISS, emptyList(), null, rng)) else lead
            hits += hit
            if (due >= 800) {
                lateRecast += ! recastFirst
                if (! recastFirst) slowHits += hit
            }
        }
        // a recast 800 ms or more after the spawn: you hit first 6 times of 7, at about your first hits from the spawn
        assertTrue(lateRecast.size > 100 && lateRecast.count { it } > 0.6 * lateRecast.size, "${lateRecast.count { it }} of ${lateRecast.size}")
        val s = slowHits.sorted()
        assertTrue(s[s.size / 2] in 350L .. 900L, "p50 ${s[s.size / 2]}")
        // no edge anywhere: nothing piles a tick or two past 3 s (where the old wait ended) or at any other moment
        val bins = hits.groupingBy { it / 100 }.eachCount()
        assertTrue((bins[30] ?: 0) + (bins[31] ?: 0) <= 2 * ((bins[27] ?: 0) + (bins[28] ?: 0)) + 20, "a pile past 3 s: $bins")
        assertTrue(hits.groupingBy { it }.eachCount().values.max() / 40_000.0 < 0.03, "a pile")
    }

    @Test
    fun `a first hit drawn only to be weighed against a recast moves your streak of slow first hits on only when it is the one taken`() {
        val rng = Random(47)
        val state = TimingModel.StreakState(0.36)
        state.slow = true
        state.high = false
        // drawn on a copy: the streak stays as it was
        val trial = FightRules.peek(state)
        repeat(50) { TimingModel.draw(TimingModel.Action.FIRST_HIT, emptyList(), trial, rng) }
        assertEquals(true, state.slow)
        assertEquals(false, state.high)
        // the hit taken: the streak moves on as if it had been drawn on it
        FightRules.carry(trial, state)
        assertEquals(trial.slow, state.slow)
        assertEquals(trial.high, state.high)
        assertEquals(0.36, state.stay)
    }

    @Test
    fun `with no recast pending your first rod hit comes from the spawn as yours did, none held to a wait's end`() {
        val rng = Random(41)
        // REACT's first tick after the spawn at 0 (no recast pending, so no wait): the hit drawn from the spawn alone
        val hits = List(40_000) {
            FightRules.firstRodHit(0L, null, TimingModel.TICK_MS, { error("no recast came") }, { TimingModel.draw(TimingModel.Action.FIRST_HIT, emptyList(), null, rng) }) {
                TimingModel.draw(TimingModel.Action.FIRST_HIT, emptyList(), null, rng)
            }
        }.sorted()
        val own = List(40_000) { TimingModel.dueAt(0L, TimingModel.draw(TimingModel.Action.FIRST_HIT, emptyList(), null, rng)) }.sorted()
        // yours (352 first rod hits): p10 248, p50 599, p90 1552; before: none under about 650 ms (p10 659)
        for (q in listOf(0.1, 0.5, 0.9)) {
            val i = (q * hits.size).toInt()
            assertEquals(own[i].toDouble(), hits[i].toDouble(), 0.05 * own[i] + TimingModel.TICK_MS, "p${(q * 100).toInt()}: as your first hits from the spawn")
        }
        assertTrue(hits[4_000] < 400, "p10 ${hits[4_000]}: one in ten of yours came before 248 ms")
        assertTrue(hits.all { it > TimingModel.TICK_MS }, "never in REACT's first tick")
        assertTrue(hits.groupingBy { it }.eachCount().values.max() / 40_000.0 < 0.03, "a pile")
        // drawn again only when it would fall before REACT's tick: after a tick 3 s on (a recast held past the hit) every one
        // falls after it, none at it
        val late = List(2_000) {
            FightRules.firstRodHit(0L, null, FightRules.RECAST_PENDING_MS, { error("") }, { TimingModel.draw(TimingModel.Action.FIRST_HIT, emptyList(), null, rng) }) {
                TimingModel.draw(TimingModel.Action.FIRST_HIT, emptyList(), null, rng)
            }
        }
        assertTrue(late.all { it > FightRules.RECAST_PENDING_MS })
    }

    @Test
    fun `the recast-first rod turn is timed with the hand's usual turn, and not at all when no turn would come`() {
        assertEquals(380L, FightRules.turnMs(error = 60.0, tolerance = 4.0, falls = false, typicalMs = 380.4))
        assertEquals(0L, FightRules.turnMs(60.0, 4.0, falls = true, typicalMs = 380.0), "it falls into the crosshair")
        assertEquals(0L, FightRules.turnMs(3.0, 4.0, falls = false, typicalMs = 380.0), "on it already")
    }

    @Test
    fun `the first click after a swap can go out in the swap tick itself`() {
        assertEquals(TimingModel.Action.SWAP_CLICK_HYPERION, FightRules.swapClickAction("HYPERION"))
        assertEquals(TimingModel.Action.SWAP_CLICK_HYPERION, FightRules.swapClickAction("VALKYRIE"))
        assertEquals(TimingModel.Action.SWAP_CLICK_FLAY, FightRules.swapClickAction("FLAMING_FLAY"))
        assertEquals(TimingModel.Action.SWAP_CLICK, FightRules.swapClickAction("DARK_CLAYMORE"))
        val rng = kotlin.random.Random(3)
        val draws = List(2000) { TimingModel.draw(FightRules.swapClickAction("HYPERION"), emptyList(), null, rng) }
        val swapTick = draws.count { TimingModel.dueAt(1000L, it) <= 1000L }
        // yours: 29 % in the swap tick
        assertTrue(swapTick in 400 .. 800, "$swapTick of 2000 in the swap tick")
        assertTrue(draws.sorted()[1000] in 30L .. 90L, "p50 ${draws.sorted()[1000]}")
    }

    @Test
    fun `the bobber flight decides where a cast lands, and only when it cannot tell does a view within 6 degrees of home count`() {
        assertTrue(FightRules.landsInFluid(true, 30.0))
        assertFalse(FightRules.landsInFluid(false, 3.0))
        assertTrue(FightRules.landsInFluid(null, 5.0))
        assertTrue(FightRules.landsInFluid(null, 5.9))
        assertFalse(FightRules.landsInFluid(null, 6.1))
        assertFalse(FightRules.landsInFluid(null, 7.0))
    }

    @Test
    fun `a turn back stops your usual error short of home, else a draw under the largest that still lands like home's, never on home`() {
        // the bound the draw gets, handed back as the draw
        val bound = { b: Double -> b }
        assertEquals(12.0, FightRules.backShort(12.0, bound) { true })
        val asked = ArrayList<Double>()
        assertEquals(12.0, FightRules.backShort(12.0, bound) { asked += it; true })
        assertEquals(listOf(12.0), asked, "an undershoot that lands is kept")
        assertTrue(FightRules.backShort(12.0, bound) { it <= 6.0 } in 6.0 - 12.0 / 64 .. 6.0)
        assertTrue(FightRules.backShort(12.0, bound) { it <= 1.0 } in 1.0 - 12.0 / 64 .. 1.0)
        assertEquals(BobberFlight.NEAR_HOME, FightRules.backShort(12.0, bound) { false }, "nothing lands: near home, not on it")
        // the flight cannot tell (no world, home's own cast lands in no fluid): never farther than the 6° fallback counts,
        // and past it drawn again below it
        assertEquals(FightRules.FLUID_FALLBACK_DEG, FightRules.backShort(12.0, bound) { null })
        assertEquals(5.9, FightRules.backShort(5.9, bound) { null })
        assertEquals(3.0, FightRules.backShort(3.0, bound) { null })
        // your errors (p50 5.1° at the next cast): drawn below the fallback, not piled there; a pool 2° wide, below 2°
        val learned = ViewPolicy.Learned.of(BehaviorProfile.Policy().apply { backCastErr = BehaviorProfile.Dist(40, 1.5, 5.1, 19.1) }, null)
        val rng = Random(11)
        for ((lands, cap) in listOf<Pair<(Double) -> Boolean?, Double>>({ _: Double -> null } to FightRules.FLUID_FALLBACK_DEG, { s: Double -> s <= 2.0 } to 2.0)) {
            val shorts = List(20_000) { FightRules.backShort(ViewPolicy.backUndershoot(learned, rng), { ViewPolicy.backUndershoot(learned, rng, it) }, lands) }
            assertTrue(shorts.all { it > 0.0 && it <= cap }, "cap $cap: ${shorts.min()}..${shorts.max()}")
            val top = shorts.count { it >= 0.99 * cap }.toDouble() / shorts.size
            // min(shortBy, 5) put about half of them at exactly 5°: drawn, none within 1 % of the bound but its density
            assertTrue(top < 0.03, "cap $cap: %.1f %% within 1 %% of it".format(top * 100))
            assertTrue(shorts.groupingBy { it }.eachCount().values.max() < 3, "cap $cap: one undershoot repeated")
        }
    }

    @Test
    fun `a fight ending far from home skips its turn back only while the cast from there lands like home's`() {
        // 0.78 of fights are turned back from at once whatever the cast; the others wait for a late one unless it lands
        val never = ViewPolicy.Learned(backRate = 0.0)
        val always = ViewPolicy.Learned(backRate = 1.0)
        repeat(50) { seed ->
            assertEquals(TurnBack.Kind.NONE, ViewPolicy.turnBack(0, 40.0, FightRules.landsInFluid(true, 40.0), never, kotlin.random.Random(seed)).kind)
            assertEquals(TurnBack.Kind.LATE, ViewPolicy.turnBack(0, 40.0, FightRules.landsInFluid(false, 40.0), never, kotlin.random.Random(seed)).kind)
            assertEquals(TurnBack.Kind.LATE, ViewPolicy.turnBack(0, 40.0, FightRules.landsInFluid(null, 40.0), never, kotlin.random.Random(seed)).kind)
            assertEquals(TurnBack.Kind.NOW, ViewPolicy.turnBack(0, 40.0, FightRules.landsInFluid(true, 40.0), always, kotlin.random.Random(seed)).kind)
            assertEquals(TurnBack.Kind.NOW, ViewPolicy.turnBack(0, 40.0, FightRules.landsInFluid(false, 40.0), always, kotlin.random.Random(seed)).kind)
            assertEquals(TurnBack.Kind.NONE, ViewPolicy.turnBack(0, 7.0, FightRules.landsInFluid(false, 7.0), always, kotlin.random.Random(seed)).kind, "within 8°")
        }
    }

    @Test
    fun `a teleport blade clicks only looking well down`() {
        assertFalse(FightRules.bladeAllows("HYPERION", 79.9f))
        assertTrue(FightRules.bladeAllows("HYPERION", 80f))
        assertFalse(FightRules.bladeAllows("VALKYRIE", 0f))
        assertTrue(FightRules.bladeAllows("FLAMING_FLAY", - 30f), "any other item")
        assertTrue(FightRules.pitchReached(84f, 89f))
        assertFalse(FightRules.pitchReached(83.9f, 89f))
    }

    @Test
    fun `the rod is skipped when it takes more than 8 hits and you fought the creature with something else`() {
        val turtle = Habit(rodHitShare = mutableListOf(0.11f, 0.12f, 0.115f), choices = linkedMapOf("near/level" to linkedMapOf("ROD" to 5, "FLAMING_FLAY" to 1), "mid/level" to linkedMapOf("FLAMING_FLAY" to 1)))
        assertTrue(FightRules.rodTooSlow(turtle, setOf("ROD", "FLAMING_FLAY")), "2 Flay fights over all situations")
        assertFalse(FightRules.rodTooSlow(turtle, setOf("ROD")), "the Flay is not in the hotbar")
        // a Bayou Sludge takes 19 % a hit: 5 hits
        assertFalse(FightRules.rodTooSlow(Habit(rodHitShare = mutableListOf(0.19f, 0.2f, 0.18f), choices = turtle.choices), setOf("ROD", "FLAMING_FLAY")))
        assertFalse(FightRules.rodTooSlow(Habit(rodHitShare = mutableListOf(0.1f, 0.1f), choices = turtle.choices), setOf("ROD", "FLAMING_FLAY")), "2 hits say too little")
        assertFalse(FightRules.rodTooSlow(Habit(rodHitShare = turtle.rodHitShare, choices = linkedMapOf("near/level" to linkedMapOf("ROD" to 5, "FLAMING_FLAY" to 1))), setOf("ROD", "FLAMING_FLAY")), "one other fight")
    }

    @Test
    fun `a plan fights with the rod's learned mode or the plan's weapon and click`() {
        val r = FightRules.ruleForPlan(rod, 8)
        assertEquals(KillAction.ROD, r.action)
        assertEquals(AttackMode.AUTO, r.attack)
        assertEquals(0, r.loadout)

        val w = FightRules.ruleForPlan(flay, 8)
        assertEquals(KillAction.WEAPON, w.action)
        assertEquals("FLAMING_FLAY", w.weaponId)
        assertEquals("Flaming Flay", w.weaponName)
        assertEquals(AttackMode.RIGHT, w.attack, "a hold is a right click")
        assertEquals(8, w.loadout)
        assertEquals(AttackMode.LEFT, FightRules.ruleForPlan(Plan("DARK_CLAYMORE", ClickStyle.LEFT, 4.0, - 3.0, 4.5, 6.0, true), 0).attack)
    }

    @Test
    fun `a weapon plan brings the creature's usual loadout only with its usual weapon`() {
        val titanoboa = Habit(weapons = linkedMapOf("FLAMING_FLAY" to 5, "HYPERION" to 1), loadouts = linkedMapOf(8 to 2))
        assertEquals(8, FightRules.learnedLoadout(titanoboa, "FLAMING_FLAY"))
        assertEquals(0, FightRules.learnedLoadout(titanoboa, "HYPERION"))
        assertEquals(0, FightRules.learnedLoadout(titanoboa, "ROD"))
        assertEquals(0, FightRules.learnedLoadout(Habit(weapons = linkedMapOf("FLAMING_FLAY" to 5), loadouts = linkedMapOf(8 to 1)), "FLAMING_FLAY"), "one fight in it")
        assertEquals(0, FightRules.learnedLoadout(null, "FLAMING_FLAY"))
    }

    @Test
    fun `learned targets join the group of the item their plan uses`() {
        val flayInLoadout = KillRule(KillAction.WEAPON, 8, "FLAMING_FLAY")
        assertTrue(FightRules.sameGroup(flayInLoadout, KillRule(KillAction.WEAPON, 0, "FLAMING_FLAY"), otherLearned = true))
        assertFalse(FightRules.sameGroup(flayInLoadout, KillRule(KillAction.WEAPON, 0, "FLAMING_FLAY"), otherLearned = false), "an explicit rule keeps its loadout")
        assertFalse(FightRules.sameGroup(flayInLoadout, KillRule(KillAction.WEAPON, 8, "HYPERION"), otherLearned = true))
        assertFalse(FightRules.sameGroup(flayInLoadout, KillRule(KillAction.ROD), otherLearned = true))
        assertTrue(FightRules.sameGroup(KillRule(KillAction.ROD), KillRule(KillAction.ROD, 3, "OLD_WEAPON"), otherLearned = false), "old Rod rules may carry a weapon")
    }

    @Test
    fun `explicit rules click as before, rod-type weapons now right click and hold`() {
        val right = KillRule(KillAction.WEAPON, 0, "FLAMING_FLAY", attack = AttackMode.RIGHT)
        assertEquals(ClickStyle.HOLD, FightRules.explicitStyle(right, "FLAMING_FLAY", realRod = false, learned = ClickStyle.HOLD, prefersRight = false))
        assertEquals(ClickStyle.RIGHT, FightRules.explicitStyle(right, "FLAMING_FLAY", realRod = false, learned = ClickStyle.RIGHT, prefersRight = false))
        assertEquals(ClickStyle.RIGHT, FightRules.explicitStyle(right, "FLAMING_FLAY", realRod = false, learned = null, prefersRight = false))
        assertEquals(ClickStyle.LEFT, FightRules.explicitStyle(right, "ROD_OF_THE_SEA", realRod = true, learned = ClickStyle.HOLD, prefersRight = true), "a fishing rod would cast")
        assertEquals(ClickStyle.LEFT, FightRules.explicitStyle(KillRule(KillAction.WEAPON, attack = AttackMode.LEFT), "HYPERION", false, ClickStyle.HOLD, true))
        assertEquals(ClickStyle.LEFT, FightRules.explicitStyle(KillRule(KillAction.ROD, attack = AttackMode.RIGHT), "FLAMING_FLAY", false, ClickStyle.HOLD, true), "Rod rules melee")

        val auto = KillRule(KillAction.WEAPON, attack = AttackMode.AUTO)
        assertEquals(ClickStyle.HOLD, FightRules.explicitStyle(auto, "FLAMING_FLAY", false, ClickStyle.HOLD, false), "the item's learned style")
        assertEquals(ClickStyle.RIGHT, FightRules.explicitStyle(auto, "HYPERION", false, null, true))
        assertEquals(ClickStyle.LEFT, FightRules.explicitStyle(auto, "HYPERION", false, null, false))
    }

    @Test
    fun `an explicit rule never right clicks with an item that moves you, a tool or another item than its weapon`() {
        val flay = KillRule(KillAction.WEAPON, 8, "FLAMING_FLAY", attack = AttackMode.RIGHT)
        // you took the Aspect of the Void (or the server put it there): no Instant Transmission towards the creature
        assertEquals(ClickStyle.LEFT, FightRules.explicitStyle(flay, "ASPECT_OF_THE_VOID", realRod = false, learned = null, prefersRight = true))
        // the Flay is not in your hotbar: never a right click with whatever is in hand instead
        assertEquals(ClickStyle.LEFT, FightRules.explicitStyle(flay, "HYPERION", realRod = false, learned = ClickStyle.RIGHT, prefersRight = true))
        // a Weapon rule without a weapon uses the item in hand, but never one that moves you or a fishing tool
        val inHand = KillRule(KillAction.WEAPON, attack = AttackMode.RIGHT)
        assertEquals(ClickStyle.RIGHT, FightRules.explicitStyle(inHand, "HYPERION", false, null, false))
        assertEquals(ClickStyle.LEFT, FightRules.explicitStyle(inHand, "GRAPPLING_HOOK", false, null, false), "the hook would cast and pull you")
        assertEquals(ClickStyle.LEFT, FightRules.explicitStyle(inHand, "ASPECT_OF_THE_END", false, null, false))
        assertEquals(ClickStyle.LEFT, FightRules.explicitStyle(inHand, "HOTSPOT_RADAR", false, null, false))
        assertEquals(ClickStyle.LEFT, FightRules.explicitStyle(KillRule(KillAction.WEAPON, attack = AttackMode.AUTO), "ASPECT_OF_THE_VOID", false, null, true))
        // a rule naming the Aspect of the Void as its weapon only hits with it
        assertEquals(ClickStyle.LEFT, FightRules.explicitStyle(KillRule(KillAction.WEAPON, 0, "ASPECT_OF_THE_VOID", attack = AttackMode.RIGHT), "ASPECT_OF_THE_VOID", false, null, true))
    }

    @Test
    fun `no right click or hold goes out with a fishing rod, an item that moves you, a tool, or a teleport blade not looking down`() {
        assertTrue(FightRules.useAllowed("FLAMING_FLAY", realRod = false, xRot = 20f), "a rod-type weapon")
        assertTrue(FightRules.useAllowed("HYPERION", false, 85f))
        assertFalse(FightRules.useAllowed("HYPERION", false, 30f))
        assertFalse(FightRules.useAllowed("ROD_OF_THE_SEA", realRod = true, xRot = 20f), "it would cast")
        for (id in WeaponPlanner.MOBILITY + WeaponPlanner.NOT_WEAPONS) assertFalse(FightRules.useAllowed(id, false, 89f), id)
        // the Grappling Hook is a fishing_rod item but no fishing rod: only the mobility check stops it
        assertFalse(FightRules.useAllowed("GRAPPLING_HOOK", realRod = false, xRot = 0f))
    }

    @Test
    fun `your mouse turn between fights becomes your fishing view once settled, one in or right before a fight never does`() {
        val w = HomeWatch(1000)
        val home = View(10.0, 40.0)
        val view = View(30.0, 35.0)
        assertNull(w.settled(0, between = true, home, view), "no turn yet")
        w.turned(1000, between = true, yaw = 20.0, pitch = - 5.0)
        assertTrue(w.recently(1500))
        assertNull(w.settled(1900, between = true, home, view), "still settling")
        assertEquals(view, w.settled(2000, between = true, home, view), "a second after your last turn")
        assertNull(w.settled(3000, between = true, view, view), "taken once")
        assertFalse(w.recently(3000))
        // a fight you took over and won without moving the mouse for a while: its view is no fishing view, the turn back comes
        w.turned(4000, between = false, yaw = 20.0, pitch = 0.0)
        assertTrue(w.recently(4500), "your mouse all the same: idle re-aims wait")
        assertNull(w.settled(6000, between = true, home, view))
        // a turn right before a spawn is forgotten by the fight
        w.turned(7000, between = true, yaw = 20.0, pitch = 0.0)
        assertNull(w.settled(7500, between = false, home, view))
        assertNull(w.settled(9000, between = true, home, view))
        // without a fishing view yet even a bump gives one
        w.turned(10_000, between = true, yaw = 0.15, pitch = 0.0)
        assertEquals(view, w.settled(11_000, between = true, null, view))
    }

    @Test
    fun `a hand resting on the mouse moves your fishing view by as much, never to where the mod's motion left the view`() {
        val w = HomeWatch(1000)
        val home = View(10.0, 40.0)
        // a re-aim or a look's return left the view 4 deg short of home; one mouse step of yours
        val left = View(14.0, 40.0)
        w.turned(1000, between = true, yaw = 0.15, pitch = 0.0)
        val moved = w.settled(2000, between = true, home, View(left.yaw + 0.15, left.pitch))!!
        assertEquals(10.15, moved.yaw, 1e-9)
        assertEquals(40.0, moved.pitch, 1e-9)
        // small steps a moment apart add up to a turn of yours: the view is yours
        repeat(20) { w.turned(3000L + it * 100, between = true, yaw = - 0.15, pitch = 0.0) }
        assertEquals(left, w.settled(6000, between = true, home, left), "%.2f deg in all".format(20 * 0.15))
        // just under the turn: home moves by the steps' yaw and pitch, the pitch staying on the view's range
        repeat(2) { w.turned(7000L + it * 100, between = true, yaw = 1.0, pitch = 0.6) }
        val nudged = w.settled(8200, between = true, View(0.0, 89.5), left)!!
        assertEquals(2.0, nudged.yaw, 1e-9)
        assertEquals(90.0, nudged.pitch, 1e-9)
        assertTrue(hypot(2.0, 1.2) < HomeWatch.REAL_TURN_DEG)
        // a hand going back and forth (a jiggle) leaves the view where it was: your fishing view stays
        repeat(40) { w.turned(10_000L + it * 50, between = true, yaw = if (it % 2 == 0) 0.6 else - 0.6, pitch = 0.0) }
        val jiggled = w.settled(13_000, between = true, home, left)!!
        assertEquals(home.yaw, jiggled.yaw, 1e-9)
        assertEquals(home.pitch, jiggled.pitch, 1e-9)
    }

    @Test
    fun `a bump while a look around keeps the view away is no turn, a real one of yours drops the look and is your view`() {
        val w = HomeWatch(1000)
        val home = View(10.0, 40.0)
        val look = View(10.0, 15.0)
        // one mouse step during the look: the look goes on (no turn of yours for its gate) and comes back home
        w.turned(1000, between = true, yaw = 0.15, pitch = 0.0, lookOut = true)
        assertFalse(w.within(1100, 2000), "the look is not given up for a bump")
        assertNull(w.settled(3000, between = true, home, look), "the look's view never becomes your fishing view")
        // bumps far apart never add up, however long the look stays away, nor does a hand going back and forth
        repeat(40) { w.turned(4000L + it * 1500, between = true, yaw = 0.15, pitch = 0.15, lookOut = true) }
        repeat(40) { w.turned(65_000L + it * 50, between = true, yaw = if (it % 2 == 0) 1.2 else - 1.2, pitch = 0.0, lookOut = true) }
        assertFalse(w.within(70_000, 70_000))
        assertNull(w.settled(70_000, between = true, home, look))
        // a slow turn of yours while the look is away: once it adds up it is a turn, the look stops and its view is yours
        val turnedTo = View(14.0, 15.0)
        var at = 80_000L
        var steps = 0
        while (! w.within(at, 1)) {
            at += 300
            steps++
            w.turned(at, between = true, yaw = 0.3, pitch = 0.0, lookOut = true)
        }
        assertTrue(steps * 0.3 >= HomeWatch.REAL_TURN_DEG && (steps - 1) * 0.3 < HomeWatch.REAL_TURN_DEG, "a turn from %.1f deg on, not before: $steps steps".format(HomeWatch.REAL_TURN_DEG))
        assertNull(w.settled(at, between = true, home, turnedTo), "still settling")
        assertEquals(turnedTo, w.settled(at + 1000, between = true, home, turnedTo))
    }

    @Test
    fun `a cast is yours only after your own press, the mod's casts never are whatever the ping`() {
        val u = UseOwner(1500)
        val never = Long.MIN_VALUE / 2
        assertFalse(u.yours(1000, never, held = false, cast = true), "no press of yours")
        assertFalse(u.mods(1000, never, cast = true), "nobody used the item")
        // Auto Fishing's click at 1000 with 900 ms ping: its bobber at 1900 is the mod's
        assertFalse(u.yours(1900, 1000, held = false, cast = true))
        assertTrue(u.mods(1900, 1000, cast = true))
        // your press at 2000: the bobber a moment later is yours, even at 1.4 s
        u.pressed(2000)
        assertTrue(u.yours(2300, 1000, held = false, cast = true))
        assertTrue(u.yours(3400, 1000, held = false, cast = true))
        assertFalse(u.mods(2300, 1000, cast = true))
        assertFalse(u.yours(3600, 1000, held = false, cast = true), "long after your press: another reel, a slot change")
        assertTrue(u.yours(9000, 1000, held = true, cast = true), "the use key you hold casts and reels every 4 ticks")
        // you reeled in by hand, Auto Fishing casts again 400 ms later: that cast is the mod's
        assertFalse(u.yours(2700, 2400, held = false, cast = true))
        assertTrue(u.mods(2700, 2400, cast = true))
        assertFalse(u.yours(2700, 2400, held = true, cast = true), "your key held down does not make the mod's click yours")
        // the killer's hold keeps using the item (its last use is now), and its bobbers stay its own after it lets go
        assertFalse(u.yours(5000, 5000, held = false, cast = true))
        assertTrue(u.mods(5000, 5000, cast = true))
        assertTrue(u.mods(5000 + 1200, 5000, cast = false))
        assertFalse(u.mods(5000 + 1600, 5000, cast = true), "nothing recent: neither")
    }

    @Test
    fun `a press of yours before Auto Fishing's cast showed up reels that cast in, so the cast stays the mod's`() {
        val u = UseOwner(1500)
        // Auto Fishing casts at 1000 (300 ms ping), you press at 1100 before its bobber shows up at 1300: the server casts
        // first and your use reels that bobber in at 1400
        u.pressed(1100, modCastAt = 1000, castShownAt = 500)
        assertFalse(u.yours(1300, 1000, held = false, cast = true), "the mod's cast is never your fishing view")
        assertTrue(u.mods(1300, 1000, cast = true))
        assertTrue(u.yours(1400, 1000, held = false, cast = false), "the reel is yours")
        assertFalse(u.mods(1400, 1000, cast = false))
        // a press after that cast showed up, or after a reel click of the mod's, casts: yours
        u.pressed(2000, modCastAt = 1000, castShownAt = 1300)
        assertTrue(u.yours(2300, 1000, held = false, cast = true))
        assertFalse(u.mods(2300, 1000, cast = true))
        u.pressed(4100, modCastAt = 1000, castShownAt = 1300)
        assertTrue(u.yours(4400, 4000, held = false, cast = true), "Auto Fishing's last click at 4000 reeled")
        // Auto Fishing's cast that never showed up long ago is no cast on its way any more
        u.pressed(6000, modCastAt = 4000, castShownAt = 1300)
        assertTrue(u.yours(6300, 4000, held = false, cast = true))
    }

    @Test
    fun `a reel Auto Fishing clicks again while the reeled bobber still shows counts as a cast for your press after it`() {
        val u = UseOwner(1500)
        // its cast showed up at 500 and was reeled at 1000; the client still shows that bobber at 3050 (server lag), so it
        // clicks again (stamped like a cast). The server had reeled it already: that click casts, and your press at 3200
        // reels the cast it sent before it showed up at 3350
        u.pressed(3200, modCastAt = 3050, castShownAt = 500)
        assertFalse(u.yours(3350, 3050, held = false, cast = true), "the mod's cast never becomes your fishing view")
        assertTrue(u.mods(3350, 3050, cast = true))
        assertTrue(u.yours(3450, 3050, held = false, cast = false), "the reel is yours")
        // unstamped (a reel click only) your press would have taken that cast for yours
        val reelOnly = UseOwner(1500)
        reelOnly.pressed(3200, modCastAt = 0, castShownAt = 500)
        assertTrue(reelOnly.yours(3350, 3050, held = false, cast = true))
    }

    @Test
    fun `a hold of the killer's whose key went up without it still makes its late bobber the mod's`() {
        val u = UseOwner(1500)
        // the killer pressed at 3000 and held; the key went up at 4200 (a screen): that moment is its last use (killerUseAt)
        assertTrue(u.mods(4200 + 900, 4200, cast = true), "the last repeat's bobber shows up after the key went up")
        assertFalse(u.yours(4200 + 900, 4200, held = false, cast = true))
        assertFalse(u.mods(4200 + 900, 3000, cast = true), "without that stamp it was neither the mod's nor yours")
    }

    @Test
    fun `a slot change the killer did not make is yours, a hotbar key at once`() {
        val w = SlotWatch()
        w.note(3)
        assertFalse(w.changed(3))
        // the scroll wheel changed it before the killer's tick
        assertTrue(w.changed(4))
        // the killer's own swap
        w.note(4)
        assertFalse(w.changed(4))
        // a hotbar key changes the slot only in the game's key handling, after the killer's tick: it counts before that
        assertTrue(w.hotbarKey(0, selected = 4))
        assertTrue(w.changed(4))
        // handled: the slot in hand is noted again
        w.note(4)
        assertFalse(w.changed(4))
        // the key of the slot already in hand changes nothing
        assertFalse(w.hotbarKey(4, selected = 4))
        assertFalse(w.changed(4))
    }

    @Test
    fun `a loadout kept for parked creatures goes back a drawn while after the first fight that kept it, not after every later fight`() {
        // your /loadout back came 3.7-22.7 s after the rod while a creature of yours stayed alive (6, p50 about 9 s), your
        // reaction to the moment it may drawn on top of the keep: never a fixed 10 s
        val rng = Random(52)
        val keeps = List(40_000) { TimingModel.draw(TimingModel.Action.PARKED_KEEP, emptyList(), null, rng) }.sorted()
        assertEquals(8000.0, keeps[20_000].toDouble(), 800.0)
        assertTrue(keeps[4_000] in 2500L .. 4500L && keeps[36_000] in 13_000L .. 23_000L, "p10 ${keeps[4_000]}, p90 ${keeps[36_000]}")
        assertTrue(keeps.last() <= 25_000 && keeps.groupingBy { it / 100 }.eachCount().values.max() < 40_000 * 0.02, "a pile")
        val drawn = ArrayDeque(listOf(7000L, 12000L))
        val d = ParkedLoadout { drawn.removeFirst() }
        d.fightEnded(1000)
        assertFalse(d.restoreDue(7999, anyParked = true))
        assertTrue(d.restoreDue(8000, anyParked = true))
        d.unparked(9000)
        assertEquals(21_000L, d.until, "each one drawn")

        val k = ParkedLoadout { 10_000L }
        k.fightEnded(0)
        assertFalse(k.restoreDue(9_999, anyParked = true))
        // fights against new catches every few seconds while the creature stays parked do not keep it on longer
        k.fightEnded(4_000)
        k.fightEnded(8_000)
        assertTrue(k.restoreDue(10_000, anyParked = true))
        // a parked creature comes back in reach: its fight is soon
        k.unparked(9_000)
        assertFalse(k.restoreDue(18_999, anyParked = true))
        assertTrue(k.restoreDue(19_000, anyParked = true))
        assertTrue(k.restoreDue(0, anyParked = false), "nothing parked")
        // the Fishing Loadout came back (or nothing is parked any more): the next fight that keeps a loadout starts over
        k.clear()
        k.fightEnded(30_000)
        assertFalse(k.restoreDue(39_999, anyParked = true))
        assertTrue(k.restoreDue(40_000, anyParked = true))
    }

    @Test
    fun `a fight started with the Flay in hand never remembers it as the rod`() {
        assertEquals(2 to "ROD_OF_THE_SEA", FightRules.rodSlotAtStart(true, 2, "ROD_OF_THE_SEA", 0, "HELLFIRE_ROD", true))
        assertEquals(0 to "HELLFIRE_ROD", FightRules.rodSlotAtStart(false, 3, "FLAMING_FLAY", 0, "HELLFIRE_ROD", true))
        assertEquals(- 1 to "", FightRules.rodSlotAtStart(false, 3, "FLAMING_FLAY", 0, "HELLFIRE_ROD", false))
        assertEquals(- 1 to "", FightRules.rodSlotAtStart(false, 3, "FLAMING_FLAY", - 1, "", false))
    }

    @Test
    fun `parked creatures are described by where they are`() {
        assertEquals("26 m below", FightRules.where(5.0, - 26.0))
        assertEquals("6 m above", FightRules.where(2.0, 6.0))
        val away = FightRules.where(12.0, 0.0)
        assertTrue(away.startsWith("12") && away.endsWith(" m away"), away)
    }

    @Test
    fun `the killer's hits owe misses at the odds that keep your share, one at a time`() {
        val rng = Random(7)
        // a click due next to the creature after every hit: every owed miss is taken, the share is yours
        for (share in listOf(0.13, 0.2, 0.35)) {
            val m = MissOdds()
            var hits = 0
            var misses = 0
            repeat(200_000) {
                m.hit(share, rng)
                hits++
                if (m.owed) {
                    m.missed()
                    misses++
                }
            }
            assertEquals(share, misses.toDouble() / (hits + misses), 0.005, "share $share")
        }
        val m = MissOdds()
        assertFalse(m.owed, "nothing owed")
        // a share of a half owes a miss on every hit, more than that counts as a half: never a burst
        repeat(5) { m.hit(0.9, rng) }
        assertTrue(m.owed)
        m.missed()
        assertFalse(m.owed, "one owed at most")
        repeat(1000) { m.hit(Double.NaN, rng) }
        repeat(1000) { m.hit(0.0, rng) }
        assertFalse(m.owed, "no share, no misses")
    }

    /** Your [share] of misses with [unplanned] of the clicks meant as hits missing (a creature moving off the crosshair as the game picks again). */
    private fun missShare(share: Double, unplanned: Double, rng: Random): Double {
        val m = MissOdds()
        var hits = 0
        var misses = 0
        repeat(300_000) {
            if (rng.nextDouble() < unplanned) {
                m.missed()
                misses++
            }
            else {
                m.hit(share, rng)
                hits++
            }
            if (m.owed) {
                m.missed()
                misses++
            }
        }
        return misses.toDouble() / (hits + misses)
    }

    @Test
    fun `clicks meant as hits that missed pay the misses owed, so the share stays yours instead of adding up`() {
        val rng = Random(11)
        // a few of them: the owed misses still go out next to the creature, the share about yours (a little above it, as
        // one paid ahead at most lets two in a row count once)
        for ((share, unplanned) in listOf(0.2 to 0.05, 0.13 to 0.03, 0.35 to 0.1, 0.2 to 0.02)) {
            val s = missShare(share, unplanned, rng)
            assertTrue(s in share - 0.005 .. share + 0.015, "share $share with $unplanned unplanned: $s")
        }
        // more of them than your share asks for: they come on top of it, no planned miss is kept away for long
        for ((share, unplanned) in listOf(0.13 to 0.2, 0.2 to 0.3)) {
            val s = missShare(share, unplanned, rng)
            assertTrue(s in unplanned .. unplanned + share * 0.75, "share $share with $unplanned unplanned: $s")
        }
        // an unplanned miss pays the one owed...
        val m = MissOdds()
        m.hit(0.5, rng)
        assertTrue(m.owed)
        m.missed()
        assertFalse(m.owed)
        // ...and with none owed the next one: that hit owes nothing
        m.missed()
        m.hit(0.5, rng)
        assertFalse(m.owed, "paid ahead")
        m.hit(0.5, rng)
        assertTrue(m.owed)
        // more of them than your share asks for: no miss owed while they keep coming
        val n = MissOdds()
        var owed = 0
        repeat(10_000) {
            n.missed()
            n.hit(0.2, rng)
            if (n.owed) owed++
        }
        assertEquals(0, owed)
        // a burst of them in one awkward fight pays one miss ahead, not the planned misses of the fights after it
        val b = MissOdds()
        repeat(10) { b.missed() }
        b.hit(0.5, rng)
        assertFalse(b.owed, "the one paid ahead")
        b.hit(0.5, rng)
        assertTrue(b.owed, "the next hit owes one again")
    }

    @Test
    fun `a melee click is what the game's fresh pick finds, and never breaks a block at once`() {
        // a miss into the air: the pick's hit result names the air at the ray's end, whose strength 0 breaks "at once"
        assertFalse(FightRules.breaksAtOnce(block = false) { Float.POSITIVE_INFINITY })
        assertFalse(FightRules.breaksAtOnce(block = false) { error("a miss has no block to ask") })
        // seagrass, kelp, a flower: at once; stone, water's edge blocks: no
        assertTrue(FightRules.breaksAtOnce(block = true) { Float.POSITIVE_INFINITY })
        assertTrue(FightRules.breaksAtOnce(block = true) { 1f })
        assertTrue(FightRules.breaksAtOnce(block = true) { Float.NaN }, "a strengthless block with no speed")
        assertFalse(FightRules.breaksAtOnce(block = true) { 0.99f })
        assertFalse(FightRules.breaksAtOnce(block = true) { 0.0f })

        val never = { error("not asked") }
        // the meant creature under the crosshair: a hit, whatever the click was meant as
        assertEquals(MeleeOutcome.HIT, FightRules.meleeOutcome(sameSlot = true, entity = true, meant = true, breaks = never))
        assertEquals(MeleeOutcome.OTHER_ENTITY, FightRules.meleeOutcome(sameSlot = true, entity = true, meant = false, breaks = never))
        // air or a block that does not break at once: a miss, also for a click meant as a hit
        assertEquals(MeleeOutcome.MISS, FightRules.meleeOutcome(sameSlot = true, entity = false, meant = false) { false })
        // seagrass behind a creature that moved: no swing at all, meant as a hit or a miss
        assertEquals(MeleeOutcome.CANCELLED, FightRules.meleeOutcome(sameSlot = true, entity = false, meant = false) { true })
        // another item in hand than the one clicked with
        for (entity in listOf(true, false))
            assertEquals(MeleeOutcome.CANCELLED, FightRules.meleeOutcome(sameSlot = false, entity = entity, meant = entity, breaks = never))
    }

    @Test
    fun `the turn for a recast-first first hit gets there a reaction early, and that hit waits the same reaction`() {
        var drawn = 0
        val reaction = { drawn++; 120L }
        // no turn comes: the fight starts at the hit, nothing drawn
        assertEquals(5000L, FightRules.turnAtForHit(5000, 0L, reaction))
        assertEquals(0, drawn)
        // a 300 ms turn: it starts 420 ms before the hit
        assertEquals(5000L - 300 - 120, FightRules.turnAtForHit(5000, 300L, reaction))
        assertEquals(1, drawn)

        // the crosshair gets onto the creature 120 ms before the hit: the hit is due at its time, not a reaction later
        val e = BoxEntry()
        e.reset(firstReaction = 120L)
        val other = { error("drawn ahead") }
        assertEquals(Long.MIN_VALUE, e.update(4580, on = false, other))
        assertEquals(4880L + 120 - 25, e.update(4880, on = true, other))
        // the next entry draws its own
        e.update(4900, on = false) { 80L }
        e.update(5000, on = false) { 80L }
        assertEquals(5050L + 80 - 25, e.update(5050, on = true) { 80L })
        // on the creature from the target's first tick: no reaction, and the one drawn ahead is not kept for later
        e.reset(firstReaction = 120L)
        assertEquals(Long.MIN_VALUE, e.update(6000, on = true, other))
        e.update(6050, on = false) { 60L }
        e.update(6200, on = false) { 60L }
        assertEquals(6250L + 60 - 25, e.update(6250, on = true) { 60L })
    }

    @Test
    fun `a miss goes out only next to the creature, and no click while the view turns faster than yours did`() {
        // within two box half-sizes of the aim point, the creature within 4 m of your eye (as your misses were counted)
        assertTrue(FightRules.missNear(5.9, 3.0, 2.0))
        assertTrue(FightRules.missNear(6.0, 3.0, BehaviorProfile.Profile.MISS_NEAR))
        assertFalse(FightRules.missNear(6.1, 3.0, 2.0), "farther off than two half-sizes")
        assertFalse(FightRules.missNear(1.0, 3.0, 4.1), "out of reach of your misses")
        // 15 deg in a tick is 300 deg/s, the fastest your view turned at a click of yours
        assertTrue(FightRules.clickTurnOk(0.0))
        assertTrue(FightRules.clickTurnOk(15.0))
        assertFalse(FightRules.clickTurnOk(15.1))
    }

    @Test
    fun `a hit goes out a reaction after the crosshair came onto the creature, not on a bobbing edge`() {
        val e = BoxEntry()
        var drawn = 0
        val reaction = { drawn++; 120L }
        // the crosshair on the target from its first tick: your reaction came before the fight
        assertEquals(Long.MIN_VALUE, e.update(1000, on = true, reaction))
        assertEquals(0, drawn)
        // a turn brings it there after 300 ms off: the hit waits for the reaction (due on the nearest tick)
        assertEquals(Long.MIN_VALUE, e.update(1050, on = false, reaction))
        assertEquals(Long.MIN_VALUE, e.update(1300, on = false, reaction))
        assertEquals(1350L + 120 - 25, e.update(1350, on = true, reaction))
        assertEquals(1, drawn)
        assertEquals(1350L + 120 - 25, e.update(1400, on = true, reaction))
        // off for one tick at the edge of its box: no new reaction
        assertEquals(1350L + 120 - 25, e.update(1450, on = false, reaction))
        assertEquals(1350L + 120 - 25, e.update(1500, on = true, reaction))
        assertEquals(1, drawn)
        // off for two ticks: it came back onto it, a new reaction
        e.update(1550, on = false, reaction)
        e.update(1600, on = false, reaction)
        assertEquals(1650L + 120 - 25, e.update(1650, on = true, reaction))
        assertEquals(2, drawn)
        // a new target starts over
        e.reset()
        assertEquals(Long.MIN_VALUE, e.update(2000, on = true, reaction))
        assertEquals(2, drawn)

        // the default reaction: like your first hits after a turn, p50 about 100 ms, one in about 14 due in that tick
        val rng = Random(3)
        val waits = List(100_000) { TimingModel.draw(TimingModel.Action.BOX_ENTRY_CLICK, emptyList(), null, rng) }
        val sameTick = waits.count { TimingModel.dueAt(0, it) <= 0 } / 1e5
        assertTrue(sameTick in 0.04 .. 0.12, "in the entry tick: $sameTick")
        assertEquals(100.0, waits.sorted()[50_000].toDouble(), 10.0)
        assertTrue(waits.all { it <= TimingModel.Action.BOX_ENTRY_CLICK.capMs }, "${waits.max()}")
    }

    @Test
    fun `the first click after the killer's loadout menu closed waits like yours`() {
        // yours: 102-533 ms after the close, p50 392
        val rng = Random(4)
        val waits = List(50_000) { TimingModel.draw(TimingModel.Action.MENU_CLOSE_TO_CLICK, emptyList(), null, rng) }.sorted()
        assertEquals(390.0, waits[25_000].toDouble(), 20.0)
        assertTrue(waits[2_500] > 120, "p5 ${waits[2_500]}")
        val learned = List(20) { 200 + it * 10 }
        assertTrue(List(5000) { TimingModel.draw(TimingModel.Action.MENU_CLOSE_TO_CLICK, learned, null, rng) }.all { it in 150L .. 500L }, "yours once learned")
    }

    @Test
    fun `after the killer's loadout menu a turn gets there a reaction before the first click, a big one as the menu closes`() {
        var drawn = 0
        val reaction = { drawn++; 100L }
        // the menu closed at 1000 and the first click is due at 1390: no turn comes, none is timed (the policy turns on its
        // own if the creature moves off meanwhile)
        assertEquals(Long.MIN_VALUE, FightRules.menuTurnAt(1390, 0L, reaction))
        assertEquals(0, drawn)
        // a small correction of 150 ms starts 140 ms after the close, not in its tick
        assertEquals(1390L - 150 - 100, FightRules.menuTurnAt(1390, 150L, reaction))
        assertEquals(1, drawn)
        // a big turn's moment is before the close: it starts as the menu closes, like yours
        assertTrue(FightRules.menuTurnAt(1390, 450L, reaction) < 1000L)
    }

    @Test
    fun `a fight's end holds Auto Fishing only when the killer did something in it`() {
        // after the kill: a weapon fight (or one without a rule) holds until the rod is back, a rod fight lets it go on
        assertTrue(FightRules.endHolds(afterKill = true, rod = false, lookOnly = false))
        assertFalse(FightRules.endHolds(afterKill = true, rod = true, lookOnly = false))
        // the rod coming back and the turn back hold whatever fought
        assertTrue(FightRules.endHolds(afterKill = false, rod = true, lookOnly = false))
        assertTrue(FightRules.endHolds(afterKill = false, rod = false, lookOnly = false))
        // the recast's bobber killed it while a look around was out: nothing to hold for, the look's move holds by itself
        for (afterKill in listOf(true, false)) for (rod in listOf(true, false))
            assertFalse(FightRules.endHolds(afterKill, rod, lookOnly = true), "after kill $afterKill, rod $rod")
    }

    @Test
    fun `after the target died the hand lets go and stops a reaction later, never in the death's tick`() {
        val lag = HandLag()
        // a hold: the key goes up and a move still turning comes to rest, each after a reaction of its own
        val reactions = ArrayDeque(listOf(250L, 120L))
        lag.died(1000, held = true, style = ClickStyle.HOLD, clicking = true, others = false, rng = Random(1), reaction = { reactions.removeFirst() }) { error("no click after a hold") }
        assertEquals(1000L + 250 - 25, lag.keyUpAt)
        assertEquals(1000L + 120 - 25, lag.stopAt)
        assertEquals(HandLag.NONE, lag.clickAt)
        // nothing held: nothing to let go; not clicking at it lately: no click on its way
        lag.died(2000, held = false, style = ClickStyle.LEFT, clicking = false, others = false, rng = Random(1), reaction = { 200L }) { error("no click on its way") }
        assertEquals(HandLag.NONE, lag.keyUpAt)
        assertEquals(2000L + 200 - 25, lag.stopAt)
        assertEquals(HandLag.NONE, lag.clickAt)
        // the next fight's hand takes the key, a new move ends the old one's stop, a takeover forgets all of it
        lag.died(3000, held = true, style = ClickStyle.LEFT, clicking = false, others = false, rng = Random(1), reaction = { 200L }) { 0L }
        lag.keyUp()
        lag.stopped()
        assertEquals(HandLag.NONE, lag.keyUpAt)
        assertEquals(HandLag.NONE, lag.stopAt)
        // a new creature while a move turns: it comes to rest a reaction after it, or earlier when one is due already
        lag.stopBy(800)
        lag.stopBy(1200)
        assertEquals(800L, lag.stopAt)
        lag.clear()
        assertEquals(HandLag.NONE, lag.stopAt)

        // the default reaction: your key went up 50-950 ms after a death with nothing else of yours alive, p50 250; never
        // later than your slowest (a key held on past it was up to 3 s, the Flay going off wherever the view was)
        val rng = Random(11)
        val waits = List(40_000) { TimingModel.draw(TimingModel.Action.DEATH_REACTION, emptyList(), null, rng) }.sorted()
        assertEquals(212.0, waits[20_000].toDouble(), 20.0)
        assertTrue(waits[4_000] in 40L .. 110L, "p10 ${waits[4_000]}")
        assertTrue(waits[36_000] in 400L .. 800L, "p90 ${waits[36_000]}")
        assertTrue(waits.last() <= 950L, "${waits.last()}")
        assertTrue(waits.count { it >= 900 } < 40_000 * 0.02, "no pile at the bound")
        // within a tick of the death now and then (yours: 6 of 28), not every time
        val quick = waits.count { TimingModel.dueAt(0, it) < 50 } / 40_000.0
        assertTrue(quick in 0.05 .. 0.25, "$quick")
    }

    @Test
    fun `a click already on its way at the death still goes out now and then, at your share and delay`() {
        val lag = HandLag()
        fun share(style: ClickStyle, others: Boolean): Double {
            val rng = Random(7)
            var n = 0
            repeat(40_000) {
                lag.died(0, held = false, style = style, clicking = true, others = others, rng = rng, reaction = { 200L }) { 0L }
                if (lag.clickAt != HandLag.NONE) n++
            }
            return n / 40_000.0
        }
        // yours: after 35 of 215 melee kills with nothing else alive, 38 of 119 with another one, 9 of 40 right-press kills
        assertEquals(0.163, share(ClickStyle.LEFT, others = false), 0.01)
        assertEquals(0.319, share(ClickStyle.LEFT, others = true), 0.01)
        assertEquals(0.225, share(ClickStyle.RIGHT, others = false), 0.01)
        assertEquals(0.0, share(ClickStyle.HOLD, others = false), "a hold's key stays down instead")

        // due at its drawn delay; one more melee click in rhythm after 37 % of them, never a third
        val rng = Random(3)
        var firsts = 0
        var seconds = 0
        repeat(40_000) {
            lag.died(1000, held = false, style = ClickStyle.LEFT, clicking = true, others = false, rng = rng, reaction = { 200L }) { 60L }
            if (lag.clickAt == HandLag.NONE) return@repeat
            firsts++
            assertEquals(1000L + 60 - 25, lag.clickAt)
            lag.clicked(1050, rng) { 180L }
            if (lag.clickAt == HandLag.NONE) return@repeat
            seconds++
            assertEquals(1050L + 180 - 25, lag.clickAt)
            lag.clicked(1250, rng) { 180L }
            assertEquals(HandLag.NONE, lag.clickAt, "at most two")
        }
        assertEquals(0.37, seconds / firsts.toDouble(), 0.03)
        // a press never gets a second one
        lag.died(0, held = false, style = ClickStyle.RIGHT, clicking = true, others = false, rng = Random(1), reaction = { 200L }) { 100L }
        val press = Random(2)
        while (lag.clickAt == HandLag.NONE) lag.died(0, held = false, style = ClickStyle.RIGHT, clicking = true, others = false, rng = press, reaction = { 200L }) { 100L }
        lag.clicked(100, press) { error("no rhythm for presses") }
        assertEquals(HandLag.NONE, lag.clickAt)
        // the swap to the rod drops it
        while (lag.clickAt == HandLag.NONE) lag.died(0, held = false, style = ClickStyle.LEFT, clicking = true, others = false, rng = press, reaction = { 200L }) { 100L }
        lag.dropClick()
        assertEquals(HandLag.NONE, lag.clickAt)

        // the delays: 38 % of yours in the death's tick, the rest p50 146 ms, none past a second
        val draws = List(40_000) { TimingModel.draw(TimingModel.Action.DEATH_CLICK, emptyList(), null, rng) }
        assertEquals(0.38, draws.count { TimingModel.dueAt(0, it) <= 0 } / 40_000.0, 0.04)
        val later = draws.filter { it > 25 }.sorted()
        assertEquals(146.0, later[later.size / 2].toDouble(), 25.0)
        assertTrue(draws.all { it <= 1000L })
        val presses = List(40_000) { TimingModel.draw(TimingModel.Action.DEATH_PRESS, emptyList(), null, rng) }
        assertEquals(0.11, presses.count { it == 0L } / 40_000.0, 0.03)
    }

    @Test
    fun `after a fight you took over and won the rod comes back a while after the death, not over a slot you took`() {
        // due at 1450 (your wait after a kill, drawn at the death)
        assertEquals(WonBack.WAIT, FightRules.wonBack(now = 1000, dueAt = 1450, newCreature = false, sameSlot = true))
        assertEquals(WonBack.BACK, FightRules.wonBack(now = 1450, dueAt = 1450, newCreature = false, sameSlot = true))
        // you took another item meanwhile: it stays in your hand
        assertEquals(WonBack.DROP, FightRules.wonBack(now = 1450, dueAt = 1450, newCreature = false, sameSlot = false))
        // a new creature's fight takes the hotbar itself
        assertEquals(WonBack.DROP, FightRules.wonBack(now = 1000, dueAt = 1450, newCreature = true, sameSlot = true))
    }

    @Test
    fun `a moment's reaction is drawn when its conditions first hold and drawn anew once they broke`() {
        val r = Reaction()
        var draws = 0
        // a parked creature died at 1000: your /loadout came a while after it, not in that tick
        assertFalse(r.ready(1000, true) { draws++; 300L })
        assertFalse(r.ready(1250, true) { error("drawn once") })
        assertTrue(r.ready(1300, true) { error("drawn once") })
        // the conditions broke (Auto Fishing cast meanwhile): the next moment they hold gets a reaction of its own
        assertFalse(r.ready(1350, false) { error("nothing to draw") })
        assertFalse(r.ready(1400, true) { draws++; 100L })
        assertTrue(r.ready(1500, true) { error("drawn once") })
        assertEquals(2, draws)
        r.clear()
        assertFalse(r.ready(1500, true) { draws++; 200L })
        assertEquals(3, draws)
    }

    @Test
    fun `a weapon swap is timed from the recast before it, and never piles where the wait for a recast ended`() {
        // the recast's swap: yours came 485-1315 ms after the recast (p10-p90), p50 747, one in 108 the tick after it
        val rng = Random(13)
        val swaps = List(40_000) { TimingModel.draw(TimingModel.Action.RECAST_TO_SWAP, emptyList(), null, rng) }.sorted()
        assertEquals(734.0, swaps[20_000].toDouble(), 40.0)
        assertTrue(swaps[4_000] in 330L .. 520L, "p10 ${swaps[4_000]}")
        assertTrue(swaps.count { it < 100 } / 40_000.0 < 0.01, "the tick after the recast")

        // a wait that ended with no recast (a pending one's, here at 600 from the spawn at 0): the swap reaction drawn again
        // until it falls after it
        val draws = ArrayDeque(listOf(300L, 500L, 700L))
        assertEquals(700L - 25, FightRules.reactAfter(0, 600, { draws.removeFirst() }) { error("one came after it") })
        // a wait longer than any reaction: a late one from its end
        assertEquals(4000L + 340 - 25, FightRules.reactAfter(0, 4000, { 300L }) { 340L })
        // with your swap reaction none piles at the wait's end (22 % of them did at exactly 600 ms)
        val at = List(20_000) { FightRules.reactAfter(0, 600, { TimingModel.draw(TimingModel.Action.SWAP_REACTION, emptyList(), null, rng) }) { error("late") } }
        assertTrue(at.all { it > 600 })
        assertTrue(at.count { it < 650 } / 20_000.0 < 0.1, "${at.count { it < 650 }} in the tick after the wait")
        assertTrue(at.groupingBy { it }.eachCount().values.max() / 20_000.0 < 0.03, "a pile")
        // the rod's path the same way: your reaction to the spawn and your first rod hit after it (a draw with your streak
        // first, the ones drawn again without) fall after the wait, never the fight's start at exactly its end with the
        // first hit due in that tick
        val starts = List(20_000) { FightRules.reactAfter(0, 600, { TimingModel.draw(TimingModel.Action.SPAWN_REACTION, emptyList(), null, rng) }) {
            TimingModel.draw(TimingModel.Action.SPAWN_REACTION, emptyList(), null, rng) } }
        val hits = List(20_000) { FightRules.reactAfter(0, 600, { TimingModel.draw(TimingModel.Action.FIRST_HIT, emptyList(), null, rng) }) {
            TimingModel.draw(TimingModel.Action.FIRST_HIT, emptyList(), null, rng) } }
        for (xs in listOf(starts, hits)) {
            assertTrue(xs.all { it > 600 }, "${xs.min()}")
            assertTrue(xs.groupingBy { it }.eachCount().values.max() / 20_000.0 < 0.03, "a pile")
        }
        assertTrue(hits.count { it < 650 } / 20_000.0 < 0.15, "${hits.count { it < 650 }} first hits in the tick after the wait")
    }

    @Test
    fun `after the killer's loadout menu the weapon it moved is taken a moment later, the key you held a moment before the swap`() {
        val rng = Random(17)
        // yours: 151-451 ms after the close (4 weapon swaps), any slot change 151-704 (8)
        val close = List(40_000) { TimingModel.draw(TimingModel.Action.MENU_CLOSE_TO_SWAP, emptyList(), null, rng) }.sorted()
        assertEquals(250.0, close[20_000].toDouble(), 20.0)
        assertTrue(close.count { TimingModel.dueAt(1000, it) < 1050 } / 40_000.0 < 0.02, "in the close's tick or the next")
        // your swap after the key you held through a kill went up: 103-2649 ms, p50 202
        val keyUp = List(40_000) { TimingModel.draw(TimingModel.Action.KEY_UP_TO_SWAP, emptyList(), null, rng) }.sorted()
        assertEquals(205.0, keyUp[20_000].toDouble(), 20.0)
    }

    @Test
    fun `after the loadout menu the first turn is timed for the move its plan makes`() {
        // a teleport blade's look-down: none with the pitch reached, else your hand's usual look-down
        assertEquals(0L, FightRules.lookDownMs(86f, 89f) { error("no look-down") })
        assertEquals(180L, FightRules.lookDownMs(30f, 89f) { 180.4 })
        // a ranged plan turns to within its aim; an explicit rule to within your usual right-click error
        assertEquals(21.0, FightRules.rangedTolerance(21.0, List(20) { 3f }))
        assertEquals(FightRules.EXPLICIT_ERR_DEG, FightRules.rangedTolerance(null, listOf(3f, 4f)))
        // the median of the errors an explicit rule accepts (1.5-12 degrees: the 1 is none of them)
        assertEquals(6.0, FightRules.rangedTolerance(null, List(9) { (9 - it).toFloat() }))
        // none within: your casts and reels say nothing about your aim
        assertEquals(FightRules.EXPLICIT_ERR_DEG, FightRules.rangedTolerance(null, List(9) { 20f }))
        // a ranged plan that makes no turn (the creature within its aim) times none and draws no reaction for it
        var drawn = 0
        val reaction = { drawn++; 100L }
        assertEquals(Long.MIN_VALUE, FightRules.menuTurnAt(1390, FightRules.turnMs(8.0, FightRules.rangedTolerance(21.0, emptyList()), false, 300.0), reaction))
        assertEquals(Long.MIN_VALUE, FightRules.menuTurnAt(1390, FightRules.lookDownMs(88f, 89f) { 200.0 }, reaction))
        assertEquals(0, drawn)
        // a look-down of 200 ms gets there a reaction before the click
        assertEquals(1390L - 200 - 100, FightRules.menuTurnAt(1390, FightRules.lookDownMs(20f, 89f) { 200.0 }, reaction))
        assertEquals(1, drawn)
    }

    @Test
    fun `a melee move's point of the box is beside its centre line across the line of sight, at its height share`() {
        // straight ahead along +z, 3 m away, a box 0.6 wide and 1.8 tall: half a half-width to the side is 0.15 m along x
        val ahead = FightRules.landOffset(0.0, 3.0, 0.6, 1.8, ViewPolicy.Landing(0.5, 0.25))
        assertEquals(- 0.15, ahead[0], 1e-9)
        assertEquals(0.45, ahead[1], 1e-9)
        assertEquals(0.0, ahead[2], 1e-9)
        // seen at 45 degrees the box shows its diagonal: the half-width is 0.3 * sqrt 2, across the line of sight
        val slant = FightRules.landOffset(2.0, 2.0, 0.6, 1.8, ViewPolicy.Landing(1.0, 0.5))
        assertEquals(0.3 * Math.sqrt(2.0), hypot(slant[0], slant[2]), 1e-9)
        assertEquals(0.0, slant[0] * 2.0 + slant[2] * 2.0, 1e-9, "across the line of sight")
        // the centre line itself, and a creature right above or below the eye: only the height
        assertContentEquals(doubleArrayOf(0.0, 0.9, 0.0), FightRules.landOffset(1.0, 2.0, 0.6, 1.8, ViewPolicy.Landing.CENTRE).map { it + 0.0 }.toDoubleArray())
        assertContentEquals(doubleArrayOf(0.0, 1.8 * 0.7, 0.0), FightRules.landOffset(0.0, 0.0, 0.6, 1.8, ViewPolicy.Landing(0.8, 0.7)))
    }

    @Test
    fun `aim heights and an explicit rule's acceptance are drawn within their bounds, never piled at them`() {
        val rng = Random(21)
        // 9 of your 85 aim heights lie below 0.2 (clamped, a tenth of the draws piled there)
        val heights = List(76) { 0.25f + 0.6f * it / 75f } + List(9) { 0.03f + 0.015f * it }
        val h = List(40_000) { FightRules.aimHeight(heights, rng) }
        assertTrue(h.all { it in FightRules.AIM_HEIGHT_MIN .. FightRules.AIM_HEIGHT_MAX })
        assertTrue(h.count { it < 0.21 } / 40_000.0 < 0.01, "${h.count { it < 0.21 }} at the floor")
        assertTrue(h.groupingBy { it }.eachCount().values.max() < 10, "a pile")
        // too few: anywhere in 0.5-0.8
        assertTrue(List(2000) { FightRules.aimHeight(listOf(0.1f, 0.9f), rng) }.all { it in 0.5 .. 0.8 })

        // your right-click errors: casts and reels far off any creature, a few presses at one (91 % of them past 12)
        val errs = List(90) { 40f + it } + List(10) { 2f + it }
        val a = List(40_000) { FightRules.explicitAcceptance(errs, rng) }
        assertTrue(a.all { it in FightRules.EXPLICIT_MIN_DEG .. FightRules.EXPLICIT_MAX_DEG })
        assertTrue(a.count { it == FightRules.EXPLICIT_MAX_DEG } == 0, "piled at 12")
        assertEquals(6.5, a.average(), 0.3)
        // fewer than 8 within: anywhere in 2.5-6
        assertTrue(List(2000) { FightRules.explicitAcceptance(List(20) { 30f }, rng) }.all { it in 2.5 .. 6.0 })
    }

    @Test
    fun `a look-down comes to rest at one of yours, a third at the bottom of the view like yours`() {
        val rng = Random(23)
        // your blade clicks: some at the bottom, most a mouse step or two short of it, a few looking elsewhere
        val pitches = List(7) { 90f } + List(10) { 89.7f } + List(4) { 87.45f } + List(5) { 45f }
        val d = List(20_000) { FightRules.lookDownPitch(pitches, 89.7f, rng) }
        // never short of where the plan's pitch counts as reached (plus a degree), never past the bottom
        assertTrue(d.all { it >= 89.7 - FightRules.PITCH_SLACK + 1 - 1e-4 && it <= 90.0 })
        assertEquals(7.0 / 21.0, d.count { it == 90.0 } / 20_000.0, 0.02)
        // the others: within a mouse step of one of yours, not all on the same pitch
        assertTrue(d.filter { it < 90.0 }.all { abs(it - 89.7) <= 0.15 + 1e-4 || abs(it - 87.45) <= 0.15 + 1e-4 })
        assertTrue(d.filter { it < 90.0 }.groupingBy { it }.eachCount().values.max() < 10, "a pile")
        // too few of yours: a third at the bottom, the rest a little short of it
        val none = List(20_000) { FightRules.lookDownPitch(emptyList(), 89f, rng) }
        assertEquals(0.32, none.count { it == 90.0 } / 20_000.0, 0.02)
        assertTrue(none.all { it >= 85.0 && it <= 90.0 })
        assertEquals(89.65, none.filter { it < 90.0 }.sorted()[none.count { it < 90.0 } / 2], 0.1)
    }

    @Test
    fun `a ranged press and a teleport blade's click wait a reaction after the view got there`() {
        assertEquals(TimingModel.Action.BOX_ENTRY_CLICK, FightRules.entryAction(ViewPolicy.Need.MELEE))
        assertEquals(TimingModel.Action.AIM_ENTRY_PRESS, FightRules.entryAction(ViewPolicy.Need.RANGED))
        assertEquals(TimingModel.Action.AIM_ENTRY_PRESS, FightRules.entryAction(ViewPolicy.Need.NONE))
        assertEquals(TimingModel.Action.LOOK_DOWN_CLICK, FightRules.entryAction(ViewPolicy.Need.PITCH))
        val rng = Random(25)
        // yours: Flay presses 1-445 ms after the error came within the aim, p50 150, one of 19 in that tick
        val press = List(40_000) { TimingModel.draw(TimingModel.Action.AIM_ENTRY_PRESS, emptyList(), null, rng) }.sorted()
        assertEquals(150.0, press[20_000].toDouble(), 10.0)
        assertTrue(press[4_000] in 45L .. 75L, "p10 ${press[4_000]}")
        assertTrue(press.count { TimingModel.dueAt(0, it) <= 0 } / 40_000.0 < 0.03, "in the tick the aim was reached")
        // yours: blade clicks 1-299 ms after the look-down came within 5 degrees of its rest, p50 53, one of 7 in that tick
        val blade = List(40_000) { TimingModel.draw(TimingModel.Action.LOOK_DOWN_CLICK, emptyList(), null, rng) }.sorted()
        assertEquals(60.0, blade[20_000].toDouble(), 6.0)
        assertTrue(blade.count { TimingModel.dueAt(0, it) <= 0 } / 40_000.0 in 0.08 .. 0.2, "in the tick the pitch was reached")
        // the press waits it once a turn brought the view within the aim (the same entry as melee's), none from the first tick on
        val e = BoxEntry()
        assertEquals(Long.MIN_VALUE, e.update(1000, on = false) { error("none while off") })
        assertEquals(1100L + 150 - 25, e.update(1100, on = true) { 150L })
    }

    @Test
    fun `a rod creature past its bobber's chance in a rod fight is one the plan turns to, no look goes there`() {
        // melee plans as before
        assertTrue(FightRules.planTurns(ViewPolicy.Need.MELEE, bobberRod = false, chancePassed = false, rodFight = false))
        // the recast's creature after its bobber's chance, the rod fighting: its rod melee comes once it is the target
        assertTrue(FightRules.planTurns(ViewPolicy.Need.NONE, bobberRod = true, chancePassed = true, rodFight = true))
        // still the bobber's, or a weapon fight (it is fought in a group of its own later), or no rod creature: looks may go
        assertFalse(FightRules.planTurns(ViewPolicy.Need.NONE, bobberRod = true, chancePassed = false, rodFight = true))
        assertFalse(FightRules.planTurns(ViewPolicy.Need.NONE, bobberRod = true, chancePassed = true, rodFight = false))
        assertFalse(FightRules.planTurns(ViewPolicy.Need.RANGED, bobberRod = false, chancePassed = true, rodFight = true))
    }

    @Test
    fun `no two clicks of the killer in one tick, each one guarded, and the next one a gap of yours after a click on its way`() {
        // a tick of its own, nothing waiting for the game to handle it: a click may go out
        assertTrue(FightRules.clickFree(tick = 101, lastTick = 100, meleePending = false, usePending = false, now = 5000, notBefore = Long.MIN_VALUE))
        // the click on its way after a death went out in this tick (lagTick runs before the fight): the next target's
        // first click in the same tick would find the single guard slot taken and go out unchecked, it waits
        assertFalse(FightRules.clickFree(101, 101, meleePending = true, usePending = false, now = 5000, notBefore = Long.MIN_VALUE))
        assertFalse(FightRules.clickFree(101, 101, meleePending = false, usePending = true, now = 5000, notBefore = Long.MIN_VALUE), "a right press")
        assertFalse(FightRules.clickFree(101, 100, meleePending = true, usePending = false, now = 5000, notBefore = Long.MIN_VALUE), "one still pending")
        // the next tick: a gap of yours after that click first (yours: none closer than 92 ms left, 96 right)
        assertFalse(FightRules.clickFree(102, 101, false, false, now = 5050, notBefore = 5000 + 160 - 25))
        assertTrue(FightRules.clickFree(105, 101, false, false, now = 5150, notBefore = 5000 + 160 - 25))
        // the gaps it draws for that: your melee gaps (defaults never closer than 92 ms either)
        val rng = Random(43)
        for (a in listOf(TimingModel.Action.MELEE_GAP_ROD, TimingModel.Action.MELEE_GAP_WEAPON))
            assertTrue(List(40_000) { TimingModel.draw(a, emptyList(), null, rng) }.all { it >= 92 }, "$a")

        // the click on its way comes a beat of your rhythm after the last click at the creature at the soonest (yours: 97 ms,
        // p50 200), and never later than a second after the death (yours: 902 ms at the latest)
        val lag = HandLag()
        val r = Random(44)
        while (lag.clickAt == HandLag.NONE)
            lag.died(1000, held = false, style = ClickStyle.LEFT, clicking = true, others = true, rng = r, reaction = { 200L }, notBefore = { 950L + 175 - 25 }) { 0L }
        assertEquals(1100L, lag.clickAt, "not in the death's tick 50 ms after the last click")
        assertFalse(lag.clickExpired(2000))
        assertTrue(lag.clickExpired(2001))
        // its own delay when that comes later
        while (true) {
            lag.died(3000, held = false, style = ClickStyle.LEFT, clicking = true, others = true, rng = r, reaction = { 200L }, notBefore = { 2700L }) { 300L }
            if (lag.clickAt != HandLag.NONE) break
        }
        assertEquals(3275L, lag.clickAt)
    }

    @Test
    fun `parked, given up or a plan switched, the hand lets go and stops a reaction later as at a death`() {
        val lag = HandLag()
        val reactions = ArrayDeque(listOf(260L, 140L))
        lag.letGo(1000, held = true) { reactions.removeFirst() }
        assertEquals(1000L + 260 - 25, lag.keyUpAt)
        assertEquals(1000L + 140 - 25, lag.stopAt)
        assertEquals(HandLag.NONE, lag.clickAt, "nothing died: no click on its way")
        // a plan given up on (escalation) is switched a tick later: the reaction already on its way is the one, never
        // drawn again from the switch
        lag.letGo(1050, held = true) { error("on its way already") }
        assertEquals(1000L + 260 - 25, lag.keyUpAt)
        assertEquals(1000L + 140 - 25, lag.stopAt)
        lag.keyUp()
        lag.stopped()
        lag.letGo(2000, held = false) { 90L }
        assertEquals(HandLag.NONE, lag.keyUpAt)
        assertEquals(2000L + 90 - 25, lag.stopAt)
    }

    @Test
    fun `a ranged press waits a reaction after the view came within its aim, also the tick after the target was picked`() {
        // the very function ranged() and melee() decide with, fed every tick: the round-6 wiring fed it only on ticks the
        // view was within the aim (`aimed && now >= update(...)`), so no reaction was ever drawn and the press went out in
        // the crossing tick
        val e = BoxEntry()
        var drawn = 0
        e.reset()
        assertFalse(e.ready(1000, on = false) { error("none while off") })
        assertFalse(e.ready(1050, on = true) { drawn++; 150L }, "the tick the error came within the aim")
        assertEquals(1, drawn)
        assertFalse(e.ready(1150, on = true) { error("drawn once") })
        assertTrue(e.ready(1200, on = true) { error("drawn once") }, "due at 1050 + 150 on the nearest tick")
        // the old wiring on the same ticks: the press in the crossing tick
        val old = BoxEntry()
        old.reset()
        fun oldReady(now: Long, on: Boolean) = on && now >= old.update(now, on) { 150L }
        assertFalse(oldReady(1000, false))
        assertTrue(oldReady(1050, true), "the round-6 signature")
        // a turn timed to get there a reaction before the first click (the loadout menu): that reaction is the one waited
        e.reset(firstReaction = 120L)
        assertFalse(e.ready(2000, on = false) { error("drawn ahead") })
        assertFalse(e.ready(2300, on = true) { error("drawn ahead") })
        assertTrue(e.ready(2400, on = true) { error("drawn ahead") })
        // within the aim from the target's first tick: your reaction came before the fight
        e.reset(firstReaction = 120L)
        assertTrue(e.ready(3000, on = true) { error("none") })
        // a blade's look-down: the same entry with your look-down click reaction
        val blade = BoxEntry()
        blade.reset()
        blade.ready(4000, on = false) { error("none") }
        assertFalse(blade.ready(4050, on = true) { TimingModel.draw(TimingModel.Action.LOOK_DOWN_CLICK, emptyList(), null, Random(1)).coerceAtLeast(60L) })
    }

    @Test
    fun `after a parent's death with no child the hand waits your wait after such a death, not when children would come`() {
        // when your children came up, given that they came: 0-596 ms after the death (25 within 1.5 s), 18 in its tick;
        // only Auto Fishing's cast waits for that
        val rng = Random(45)
        val waits = List(40_000) { TimingModel.draw(TimingModel.Action.CHILD_WAIT, emptyList(), null, rng) }
        assertEquals(0.72, waits.count { TimingModel.dueAt(0, it) <= 0 } / 40_000.0, 0.03)
        val later = waits.filter { it > 25 }.sorted()
        assertEquals(150.0, later[later.size / 2].toDouble(), 40.0)
        assertTrue(waits.all { it <= 1500 })
        // your hand after a parent's death with no child within 1.5 s: first slot change 0-4149 ms (27, p10 248, p50 797,
        // p90 3990), slower than after other kills (p50 459); the rod came back at death + 800 for 66 % of them once, and
        // with the child wait as a hand's wait 72 % of them had none at all
        val hand = List(40_000) { TimingModel.draw(TimingModel.Action.PARENT_AFTER_KILL, emptyList(), null, rng) }.sorted()
        assertEquals(850.0, hand[20_000].toDouble(), 120.0)
        assertTrue(hand[4_000] in 150L .. 330L, "p10 ${hand[4_000]}")
        assertTrue(hand[36_000] in 2500L .. 4500L, "p90 ${hand[36_000]}")
        assertTrue(hand.last() <= 6000 && hand.groupingBy { it }.eachCount().values.max() < 40_000 * 0.01, "a pile")
    }

    @Test
    fun `after the kill an owed loadout that waited for Auto Fishing's rod goes a reaction after it is free`() {
        val w = FreeWait()
        // the rod free all along: due at once, nothing drawn
        assertTrue(w.ready(1000, free = true) { error("no wait") })
        // the rod reeling or recasting: it waits, then a reaction after the first tick it is free (never the tick after the cast)
        assertFalse(w.ready(2000, free = false) { error("not free") })
        assertFalse(w.ready(2050, free = true) { 300L })
        assertFalse(w.ready(2300, free = true) { error("drawn once") })
        assertTrue(w.ready(2325, free = true) { error("drawn once") })
        // done with it: the next one goes at once again
        assertTrue(w.ready(3000, free = true) { error("no wait") })
        // busy again before the reaction was over: a reaction of its own after it
        assertFalse(w.ready(4000, free = false) { error("not free") })
        assertFalse(w.ready(4050, free = true) { 200L })
        assertFalse(w.ready(4100, free = false) { error("not free") })
        assertFalse(w.ready(4150, free = true) { 400L })
        assertFalse(w.ready(4500, free = true) { error("drawn once") })
        assertTrue(w.ready(4525, free = true) { error("drawn once") })
    }

    @Test
    fun `the held use key goes up a reaction after the view or distance stopped keeping it, at once when it may not be used`() {
        val off = Reaction()
        // kept: held on
        assertFalse(FightRules.holdEnds(allowed = true, due = false, keep = true, off = off, now = 1000) { error("kept") })
        // the creature left the aim: a reaction later, not in that tick
        assertFalse(FightRules.holdEnds(true, false, keep = false, off = off, now = 1050) { 200L })
        assertFalse(FightRules.holdEnds(true, false, keep = false, off = off, now = 1200) { error("drawn once") })
        assertTrue(FightRules.holdEnds(true, false, keep = false, off = off, now = 1225) { error("drawn once") })
        // back in the aim before it: the next leaving gets a reaction of its own
        off.clear()
        assertFalse(FightRules.holdEnds(true, false, keep = false, off = off, now = 2000) { 300L })
        assertFalse(FightRules.holdEnds(true, false, keep = true, off = off, now = 2050) { error("kept") })
        assertFalse(FightRules.holdEnds(true, false, keep = false, off = off, now = 2100) { 100L })
        assertTrue(FightRules.holdEnds(true, false, keep = false, off = off, now = 2175) { error("drawn once") })
        // a player under the crosshair or a blade not looking down: at once; the hold's own time: at once
        assertTrue(FightRules.holdEnds(allowed = false, due = false, keep = true, off = Reaction(), now = 3000) { error("at once") })
        assertTrue(FightRules.holdEnds(allowed = true, due = true, keep = true, off = Reaction(), now = 3000) { error("at once") })
    }

    @Test
    fun `a creature right above or below you is waited for a drawn while to fall into the crosshair, never a fixed second`() {
        val rng = Random(47)
        val d = List(40_000) { TimingModel.draw(TimingModel.Action.FALL_WAIT, emptyList(), null, rng) }.sorted()
        assertEquals(1000.0, d[20_000].toDouble(), 50.0)
        assertTrue(d[4_000] < 750 && d[36_000] > 1300, "p10 ${d[4_000]}, p90 ${d[36_000]}")
        assertTrue(d.groupingBy { it }.eachCount().values.max() < 40_000 * 0.01, "a pile")
    }

    @Test
    fun `what follows the killer's loadout menu is timed from its close, never from a confirmation that came after it`() {
        // the confirmation came before the close (yours always did): drawn from the close as it is
        assertEquals(1000L + 390 - 25, FightRules.afterMenu(closedAt = 1000, doneAt = 1000) { 390L })
        // it came 600 ms after the close: what fell before is drawn again after it, never piled in the next tick
        val draws = ArrayDeque(listOf(250L, 400L, 700L))
        assertEquals(1000L + 700 - 25, FightRules.afterMenu(1000, 1600) { draws.removeFirst() })
        val rng = Random(49)
        val at = List(20_000) { FightRules.afterMenu(1000, 1600) { TimingModel.draw(TimingModel.Action.MENU_CLOSE_TO_CLICK, emptyList(), null, rng) } }
        assertTrue(at.all { it > 1600 })
        assertTrue(at.count { it <= 1650 } < 1.5 * at.count { it in 1651L .. 1700L }, "the draw's own density, no pile")
    }

    @Test
    fun `when Auto Fishing stops the killer lets go after a reaction, at once for a death, the Recorder or your takeover`() {
        // a rare catch or its key: your reaction first, then the rod back
        val off = FightRules.offExit(dead = false, recorder = false, takeover = false, armed = true, slotChanged = false)
        assertFalse(off.atOnce)
        assertTrue(off.restore)
        // the Fishing Recorder: nothing of the killer's goes on into your lines, no slot change either
        val rec = FightRules.offExit(dead = false, recorder = true, takeover = false, armed = true, slotChanged = false)
        assertTrue(rec.atOnce)
        assertFalse(rec.restore)
        // your hand during that wait: yours at once
        val yours = FightRules.offExit(dead = false, recorder = false, takeover = true, armed = true, slotChanged = false)
        assertTrue(yours.atOnce)
        assertFalse(yours.restore)
        // a death: at once, the rod back as before; a slot you took stays
        assertTrue(FightRules.offExit(dead = true, recorder = false, takeover = false, armed = true, slotChanged = false).let { it.atOnce && it.restore })
        assertFalse(FightRules.offExit(dead = false, recorder = false, takeover = false, armed = true, slotChanged = true).restore)
        assertFalse(FightRules.offExit(dead = false, recorder = false, takeover = false, armed = false, slotChanged = false).restore, "no item taken")
        // the use key held up to the reaction goes up then, the rod a moment after it (key-up, brake and swap all fell in
        // one tick); nothing held, or at once: with it
        assertEquals(5000L + 205 - 25, FightRules.offRodAt(5000, held = true, atOnce = false) { 205L })
        assertEquals(5000L, FightRules.offRodAt(5000, held = false, atOnce = false) { error("nothing held") })
        assertEquals(5000L, FightRules.offRodAt(5000, held = true, atOnce = true) { error("a death: at once") })
        val rng = Random(53)
        assertTrue(List(20_000) { FightRules.offRodAt(0, true, false) { TimingModel.draw(TimingModel.Action.KEY_UP_TO_SWAP, emptyList(), null, rng) } }.all { it > 0 },
            "never in the key-up's tick")
    }

    @Test
    fun `no use of the killer's interacts with another entity than the creature it fights`() {
        // the game's fresh pick found nothing (air, a block): as before; the creature fought: allowed
        assertFalse(FightRules.usesOnOther(entity = false, fought = false))
        assertFalse(FightRules.usesOnOther(entity = true, fought = true))
        // another player's sea creature, a player, an armor stand, any entity: cancelled, the held key up at once
        assertTrue(FightRules.usesOnOther(entity = true, fought = false))
        // a hold the view no longer keeps on its creature goes up at once over another entity, not after a reaction
        assertTrue(FightRules.holdEnds(allowed = ! FightRules.usesOnOther(true, false), due = false, keep = false, off = Reaction(), now = 0) { error("at once") })
    }

    @Test
    fun `a confirmation is late only when the switch was over a tick after its menu closed, never by a millisecond`() {
        // the callback read the clock a millisecond after the close in the close's own tick: no late confirmation, nothing
        // timed from the close is drawn again (it was redrawn to close + a swap's wait at random)
        assertEquals(1000L, FightRules.menuDone(closedAt = 1000, done = 1001, closedTicksAgo = 0))
        assertEquals(1000L, FightRules.menuDone(closedAt = 1000, done = 1000, closedTicksAgo = - 1), "no menu")
        // a tick or more later: the switch was over then
        assertEquals(1060L, FightRules.menuDone(closedAt = 1000, done = 1060, closedTicksAgo = 1))
        // and what follows the close then keeps its draw from the close, as it is
        assertEquals(1000L + 390 - 25, FightRules.afterMenu(1000, FightRules.menuDone(1000, 1001, 0)) { 390L })
    }

    @Test
    fun `a beat read off your rhythm for a click that may never go out leaves the rhythm as it was`() {
        val s = TimingModel.StreakState(0.8)
        s.slow = true
        s.high = false
        val rng = Random(54)
        repeat(200) { TimingModel.draw(TimingModel.Action.MELEE_GAP_ROD, emptyList(), FightRules.peek(s), rng) }
        assertEquals(true, s.slow)
        assertEquals(false, s.high)
        assertEquals(0.8, s.stay)
        // the copy draws in the rhythm the hand is in: the slow one here, kept 80 % of the time
        val slow = List(4000) { TimingModel.draw(TimingModel.Action.MELEE_GAP_ROD, emptyList(), FightRules.peek(s), rng) }.count { it >= TimingModel.RHYTHM_SPLIT_MS }
        assertTrue(slow > 4000 * 0.8, "$slow of 4000 in the slow rhythm")
    }

    @Test
    fun `the next creature of a group comes a gap of the dead one's own clicks after its death`() {
        assertEquals(170L, FightRules.nextTargetGap(ClickStyle.LEFT, { 170L }, { error("melee") }) { error("melee") })
        assertEquals(170L, FightRules.nextTargetGap(null, { 170L }, { error("melee") }) { error("melee") })
        assertEquals(345L, FightRules.nextTargetGap(ClickStyle.RIGHT, { error("presses") }, { 345L }) { error("presses") })
        assertEquals(598L, FightRules.nextTargetGap(ClickStyle.HOLD, { error("holds") }, { error("holds") }) { 598L })
    }

    @Test
    fun `a click after a screen of yours closed waits your reaction to the close, never the first tick after it`() {
        // yours: 0-1898 ms after the close (20, p50 575, one in its tick, one a tick after)
        val rng = Random(55)
        val waits = List(40_000) { TimingModel.draw(TimingModel.Action.SCREEN_CLOSE_TO_CLICK, emptyList(), null, rng) }.sorted()
        assertEquals(412.0, waits[20_000].toDouble(), 40.0)
        assertTrue(waits.count { TimingModel.dueAt(0, it) < 50 } / 40_000.0 in 0.01 .. 0.08, "the first tick after the close now and then")
        // a press due before the close goes a tick of its own after that reaction (the guard slot free, the gap kept)
        val closedAt = 10_000L
        val clickAt = TimingModel.dueAt(closedAt, 400L)
        assertFalse(FightRules.clickFree(tick = 201, lastTick = 150, meleePending = false, usePending = false, now = closedAt + 50, notBefore = clickAt))
        assertTrue(FightRules.clickFree(tick = 209, lastTick = 150, meleePending = false, usePending = false, now = closedAt + 400, notBefore = clickAt))
    }

    @Test
    fun `a plan switched on the same creature waits a fresh entry reaction once the view came where the new plan needs it`() {
        val e = BoxEntry()
        // the old plan's view was on the creature; the switch resets the entry, and the SWITCH ticks feed it
        e.reset()
        e.update(1000, on = false) { error("off") }
        e.update(1050, on = false) { error("off") }
        // the view came within the new plan's aim in a SWITCH tick: its reaction runs from then, not from the swap
        assertEquals(1100L + 150 - 25, e.update(1100, on = true) { 150L })
        assertTrue(e.ready(1250, on = true) { error("drawn once") })
        // on it all along through the switch: no reaction of its own (your swap's wait to the first click holds it)
        val f = BoxEntry()
        f.reset()
        assertTrue(f.ready(2000, on = true) { error("none") })
    }
}
