package fishing

import com.laybalt.features.impl.fishing.AutoFishing.RodWay
import com.laybalt.features.impl.fishing.AutoMobKiller.BlockUses
import com.laybalt.features.impl.fishing.AutoMobKiller.ClearWay
import com.laybalt.features.impl.fishing.AutoMobKiller.FightRules
import com.laybalt.features.impl.fishing.AutoMobKiller.FightRules.PathKind
import com.laybalt.features.impl.fishing.AutoMobKiller.HandLag
import com.laybalt.features.impl.fishing.AutoMobKiller.ScreenGate
import com.laybalt.features.impl.fishing.LoadoutSwitcher.LateMenu
import com.laybalt.features.impl.fishing.TimingModel
import com.laybalt.features.impl.fishing.TimingModel.Action
import com.laybalt.features.impl.fishing.ViewPolicy
import com.laybalt.features.impl.fishing.ViewPolicy.Decision
import com.laybalt.features.impl.fishing.ViewPolicy.TurnBack
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.ButtonBlock
import net.minecraft.world.level.block.CampfireBlock
import net.minecraft.world.level.block.CauldronBlock
import net.minecraft.world.level.block.ChestBlock
import net.minecraft.world.level.block.DoorBlock
import net.minecraft.world.level.block.FenceBlock
import net.minecraft.world.level.block.FenceGateBlock
import net.minecraft.world.level.block.FlowerPotBlock
import net.minecraft.world.level.block.KelpBlock
import net.minecraft.world.level.block.LayeredCauldronBlock
import net.minecraft.world.level.block.LeverBlock
import net.minecraft.world.level.block.NoteBlock
import net.minecraft.world.level.block.SeagrassBlock
import net.minecraft.world.level.block.ShelfBlock
import net.minecraft.world.level.block.SlabBlock
import net.minecraft.world.level.block.StairBlock
import net.minecraft.world.level.block.StandingSignBlock
import net.minecraft.world.level.block.TrapDoorBlock
import net.minecraft.world.level.block.LilyPadBlock
import kotlin.random.Random
import kotlin.test.*

/**
 * The killer's and Auto Fishing's safety rules and the waits that go with them: nothing a click or an ability must not
 * touch ([ClearWay], [FightRules.pathBlocked], [BlockUses], [RodWay]), nothing in the first tick after a screen of yours
 * closed ([ScreenGate]), the rod after a menu the killer closed ([FightRules.offRodAt]), a creature already there reacted
 * to from the death ([Action.DEATH_TO_SWAP]), the turn back timed only by the move in flight at the death ([TurnBack]),
 * and a late loadout menu closed a reaction after it opened ([LateMenu]).
 */
class KillerSafetyTest {
    private fun quantile(xs: List<Long>, q: Double) = xs.sorted()[((xs.size - 1) * q).toInt()]

    @Test
    fun `a click held for something in the way goes a reaction after the way came clear, never in that tick`() {
        val w = ClearWay()
        assertTrue(w.ready(1000, blocked = false) { error("nothing held it") }, "nothing in the way: at once")
        // another player's creature under the crosshair from 1050 to 1300
        for (t in 1050L .. 1300L step 50) assertFalse(w.ready(t, blocked = true) { error("drawn once clear") }, "held at $t")
        assertTrue(w.held)
        var draws = 0
        assertFalse(w.ready(1350, blocked = false) { draws++; 180L }, "not in the tick the way came clear")
        assertFalse(w.ready(1450, blocked = false) { draws++; 180L })
        assertTrue(w.ready(1550, blocked = false) { draws++; 180L }, "your reaction after the first clear tick")
        assertEquals(1, draws)
        assertFalse(w.held)
        // in the way again during the reaction: it waits for the way and a reaction anew
        assertFalse(w.ready(2000, blocked = true) { error("blocked") })
        assertFalse(w.ready(2050, blocked = false) { 200L })
        assertFalse(w.ready(2100, blocked = true) { error("blocked") })
        assertFalse(w.ready(2150, blocked = false) { 100L })
        assertFalse(w.ready(2200, blocked = false) { error("drawn") })
        assertTrue(w.ready(2250, blocked = false) { error("drawn") })
        // the game's own pick found it as it handled the click: the next one waits a reaction after a clear tick too
        w.block()
        assertFalse(w.ready(3000, blocked = false) { 60L })
        assertTrue(w.ready(3050, blocked = false) { error("drawn") })
        // the default: no record logs entities but yours, so a conservative wait, never under a tick, within 1.5 s
        val rng = Random(41)
        val waits = List(20_000) { TimingModel.draw(Action.PATH_CLEAR, emptyList(), null, rng) }
        assertTrue(waits.all { it in 50L .. 1500L }, "${waits.min()} .. ${waits.max()}")
        assertEquals(200.0, quantile(waits, 0.5).toDouble(), 25.0)
        assertTrue(waits.groupingBy { it }.eachCount().values.max() < 20_000 * 0.02, "a pile")
    }

    @Test
    fun `an ability's way is blocked by anything not yours before the first creature of yours on it`() {
        // another player's creature at 6 m with yours at 9 m: the hook meets it first
        assertTrue(FightRules.pathBlocked(listOf(9.0 to PathKind.SHIELD, 6.0 to PathKind.BLOCKER)))
        // yours (or the creature fought) first: it takes the hit, the one behind is never met
        assertFalse(FightRules.pathBlocked(listOf(9.0 to PathKind.BLOCKER, 4.0 to PathKind.SHIELD)))
        // nametags and entities nothing meets are no part of the way; nothing at all is a free way
        assertFalse(FightRules.pathBlocked(listOf(1.0 to PathKind.NONE, 2.0 to PathKind.NONE)))
        assertTrue(FightRules.pathBlocked(listOf(1.0 to PathKind.NONE, 2.0 to PathKind.BLOCKER)))
        assertFalse(FightRules.pathBlocked(emptyList()))
        // what each entity is: yours or the fought one shield, armor stands never count, anything else hit blocks
        assertEquals(PathKind.SHIELD, FightRules.pathKind(shield = true, stand = false, hittable = true))
        assertEquals(PathKind.NONE, FightRules.pathKind(shield = true, stand = true, hittable = true), "your creature's nametag")
        assertEquals(PathKind.NONE, FightRules.pathKind(shield = false, stand = true, hittable = true), "any armor stand")
        assertEquals(PathKind.NONE, FightRules.pathKind(shield = false, stand = false, hittable = false), "an item, an arrow")
        assertEquals(PathKind.BLOCKER, FightRules.pathKind(shield = false, stand = false, hittable = true), "another player's creature, a player, a boat")
    }

    @Test
    fun `a melee click goes only with the very item it was meant for`() {
        assertTrue(FightRules.sameItem(3, "FLAMING_FLAY", 3, "FLAMING_FLAY"))
        assertFalse(FightRules.sameItem(4, "FLAMING_FLAY", 3, "FLAMING_FLAY"), "another slot")
        assertFalse(FightRules.sameItem(3, "ASPECT_OF_THE_VOID", 3, "FLAMING_FLAY"), "the slot's item swapped by the server")
    }

    @Test
    fun `blocks a right click opens or toggles are told by their own use, a lead's or a bucket's use not`() {
        for (c in listOf<Class<*>>(DoorBlock::class.java, TrapDoorBlock::class.java, FenceGateBlock::class.java, LeverBlock::class.java, ButtonBlock::class.java,
            ChestBlock::class.java, NoteBlock::class.java, StandingSignBlock::class.java, FlowerPotBlock::class.java, ShelfBlock::class.java))
            assertTrue(BlockUses.usableClass(c), c.simpleName)
        for (c in listOf<Class<*>>(Block::class.java, SlabBlock::class.java, StairBlock::class.java, SeagrassBlock::class.java, KelpBlock::class.java,
            LilyPadBlock::class.java, FenceBlock::class.java, CauldronBlock::class.java, LayeredCauldronBlock::class.java, CampfireBlock::class.java))
            assertFalse(BlockUses.usableClass(c), c.simpleName)
        assertTrue(BlockUses.overrides(DoorBlock::class.java), "the game's names are readable here")
        assertFalse(BlockUses.overrides(SlabBlock::class.java))
    }

    @Test
    fun `after a screen of yours closed each kind of action waits a reaction of its own, never the next tick`() {
        val draws = HashMap<ScreenGate.Kind, Int>()
        val waits = mapOf(ScreenGate.Kind.SLOT to 400L, ScreenGate.Kind.COMMAND to 200L, ScreenGate.Kind.MOVE to 0L)
        val g = ScreenGate { k -> draws.merge(k, 1, Int::plus); waits.getValue(k) }
        assertTrue(g.ready(ScreenGate.Kind.SLOT, 500), "no screen closed yet")
        assertTrue(draws.isEmpty())
        g.closed(1000)
        assertFalse(g.ready(ScreenGate.Kind.SLOT, 1000), "not in the close's tick")
        assertFalse(g.ready(ScreenGate.Kind.SLOT, 1350))
        assertTrue(g.ready(ScreenGate.Kind.SLOT, 1400))
        assertFalse(g.ready(ScreenGate.Kind.COMMAND, 1150), "drawn from the close, not from its first ask")
        assertTrue(g.ready(ScreenGate.Kind.COMMAND, 1200))
        assertTrue(g.ready(ScreenGate.Kind.MOVE, 1000), "a move may come in the close's tick, as 4 of your 14")
        assertEquals(mapOf(ScreenGate.Kind.SLOT to 1, ScreenGate.Kind.COMMAND to 1, ScreenGate.Kind.MOVE to 1), draws)
        // the next close draws anew
        g.closed(5000)
        assertFalse(g.ready(ScreenGate.Kind.SLOT, 5050))
        assertEquals(2, draws[ScreenGate.Kind.SLOT])

        // your close -> slot change (34: min 51, p50 474), -> /loadout (7: min 107), -> move (14: 4 at 0, the rest p50 245)
        val rng = Random(42)
        val slot = List(20_000) { TimingModel.draw(Action.SCREEN_CLOSE_TO_SLOT, emptyList(), null, rng) }
        assertTrue(slot.all { it in 30L .. 3000L }, "${slot.min()}")
        assertEquals(400.0, quantile(slot, 0.5).toDouble(), 50.0)
        assertTrue(slot.none { TimingModel.dueAt(0, it) <= 0 }, "none in the close's own tick")
        val cmd = List(20_000) { TimingModel.draw(Action.SCREEN_CLOSE_TO_COMMAND, emptyList(), null, rng) }
        assertTrue(cmd.all { it in 95L .. 3000L }, "${cmd.min()}")
        val move = List(20_000) { TimingModel.draw(Action.SCREEN_CLOSE_TO_MOVE, emptyList(), null, rng) }
        assertEquals(0.29, move.count { it == 0L } / 20_000.0, 0.03)
        assertEquals(245.0, quantile(move.filter { it > 0 }, 0.5).toDouble(), 40.0)
    }

    @Test
    fun `the rod after the killer let go comes a wait after the key-up or the menu's close, never in its tick`() {
        val noKey = { error("no key held") }
        val noMenu = { error("no menu") }
        assertEquals(5000L + 205 - 25, FightRules.offRodAt(5000, held = true, atOnce = false, menuClosed = false, keyUp = { 205L }, menu = noMenu))
        assertEquals(5000L + 151 - 25, FightRules.offRodAt(5000, held = false, atOnce = false, menuClosed = true, keyUp = noKey, menu = { 151L }))
        assertEquals(5000L, FightRules.offRodAt(5000, held = false, atOnce = false, menuClosed = false, keyUp = noKey, menu = noMenu), "next tick, through offRodTick")
        assertEquals(5000L, FightRules.offRodAt(5000, held = true, atOnce = true, menuClosed = true, keyUp = noKey, menu = noMenu), "a death: at once")
        val rng = Random(43)
        val after = List(10_000) {
            FightRules.offRodAt(0, held = false, atOnce = false, menuClosed = true, keyUp = noKey, menu = { TimingModel.draw(Action.MENU_CLOSE_TO_ROD, emptyList(), null, rng) })
        }
        assertTrue(after.all { it > 0 }, "never in the menu's close tick: ${after.min()}")
    }

    @Test
    fun `a creature there before your hand was freed is reacted to from the death, with your swaps to another creature`() {
        assertTrue(FightRules.olderThanFree(anchor = 1000, freedAt = 3000), "an older child waiting while another group was fought")
        assertFalse(FightRules.olderThanFree(anchor = 3000, freedAt = 3000), "one split off in the death's tick: from its spawn")
        assertFalse(FightRules.olderThanFree(anchor = 3050, freedAt = 3000))
        assertFalse(FightRules.olderThanFree(anchor = 1000, freedAt = Long.MIN_VALUE), "no hand freed yet")
        // your death -> swap to fight another of yours (45 within 3 s: 50-2951, a third within 603 ms, p50 1296)
        val rng = Random(44)
        val waits = List(40_000) { TimingModel.draw(Action.DEATH_TO_SWAP, emptyList(), null, rng) }
        assertTrue(waits.all { it in 30L .. 3000L }, "${waits.min()} .. ${waits.max()}")
        assertEquals(0.33, waits.count { it <= 603 } / 40_000.0, 0.05)
        assertTrue(quantile(waits, 0.5) in 1000L .. 1450L, "p50 ${quantile(waits, 0.5)}")
        // within three ticks of the death only about as often as yours (3 of 45: 50, 92, 92 ms)
        assertEquals(3.0 / 45, waits.count { TimingModel.dueAt(0, it) < 3 * TimingModel.TICK_MS } / 40_000.0, 0.04)
    }

    @Test
    fun `one there already whose item is in hand comes the plan's own gap after the death, no swap's wait, the key-up first`() {
        // a swap to another item: your swap after a death, as drawn at the death
        assertEquals(1234L, FightRules.olderReact(swaps = true, keyDown = false, swapAt = 1234, from = 0, now = 50) { error("no gap") })
        // the use key held through the death still down: nothing before its key-up, swap or not
        assertEquals(Long.MAX_VALUE, FightRules.olderReact(true, true, Long.MAX_VALUE, 0, 50) { error("") })
        assertEquals(Long.MAX_VALUE, FightRules.olderReact(false, true, Long.MAX_VALUE, 0, 50) { error("") })
        // no swap: the plan's own gap from the death (or the key-up), drawn again until it falls after now
        val gaps = ArrayDeque(listOf(30L, 280L))
        assertEquals(280L - 25, FightRules.olderReact(false, false, Long.MAX_VALUE, 0, 50) { gaps.removeFirst() })
        val rng = Random(47)
        val rod = List(40_000) { FightRules.olderReact(false, false, 9999, 0, 50) { TimingModel.draw(Action.MELEE_GAP_ROD, emptyList(), null, rng) } }
        val swap = List(40_000) { TimingModel.dueAt(0, TimingModel.draw(Action.DEATH_TO_SWAP, emptyList(), null, rng)) }
        // yours with the item in hand: first click at another creature already there p50 448 ms after the death (181; with
        // the rod p50 351, 87), against 1296 for a swap first (45): the swap's wait would be about three times yours
        assertTrue(rod.all { it > 50 }, "never in the tick after the death: ${rod.min()}")
        assertTrue(quantile(rod, 0.5) in 150L .. 450L, "p50 ${quantile(rod, 0.5)}")
        assertTrue(quantile(swap, 0.5) > 2 * quantile(rod, 0.5))
        assertTrue(rod.groupingBy { it }.eachCount().values.max() / 40_000.0 < 0.03, "a pile")
    }

    @Test
    fun `a look started after the death fits the turn back, it never times it anew`() {
        // the turn back due at 1500 (latency from the death at 1000); move 3 was in flight at the death
        val b = TurnBack(TurnBack.Kind.NOW, 1500, 5.0, afterMove = { error("no end of the move at the death") }, from = 1000,
            latency = { error("as drawn") }, deathMove = TurnBack.NO_MOVE)
        // a look around (move 4) out from 1100 and back at rest at 1250
        for (t in 1100L .. 1200L step 50) assertEquals(Decision.NONE, b.update(t, moving = true, restedAt = 900, move = 4))
        assertEquals(Decision.NONE, b.update(1250, moving = false, restedAt = 1240, move = 4))
        assertEquals(1500L, b.at, "the latency stands, no wait after the look's rest on top")
        assertEquals(Decision.START, b.update(1500, moving = false, restedAt = 1240, move = 4))

        // the move in flight at the death went on past it: a wait after its rest, drawn once whatever the rest reads later
        var waits = 0
        val c = TurnBack(TurnBack.Kind.NOW, 1300, 5.0, afterMove = { waits++; 400L }, from = 1000, latency = { error("timed from the rest") }, deathMove = 3)
        assertEquals(Decision.NONE, c.update(1050, moving = true, restedAt = 900, move = 3))
        assertEquals(Decision.NONE, c.update(1150, moving = false, restedAt = 1120, move = 3))
        assertEquals(1520L, c.at)
        // the same rest a millisecond off at the next ticks (the driver's clock against the tick's): no new draw
        assertEquals(Decision.NONE, c.update(1200, moving = false, restedAt = 1121, move = 3))
        assertEquals(Decision.NONE, c.update(1250, moving = false, restedAt = 1119, move = 3))
        assertEquals(1, waits)
        assertEquals(1520L, c.at)
        // then a look (move 4) coming to rest at 1480, 40 ms before the turn back: drawn again after your shortest gap
        // from that move's rest, never from the look's
        val d = TurnBack(TurnBack.Kind.NOW, 1300, 5.0, afterMove = ArrayDeque(listOf(400L, 450L, 700L))::removeFirst, from = 1000,
            latency = { error("timed from the rest") }, deathMove = 3)
        d.update(1150, moving = false, restedAt = 1120, move = 3)
        assertEquals(1520L, d.at)
        d.update(1450, moving = true, restedAt = 1120, move = 4)
        assertEquals(Decision.NONE, d.update(1500, moving = false, restedAt = 1480, move = 4))
        assertEquals(1120L + 700, d.at, "400 and 450 after the rest at 1120 fall within 95 ms of the look's rest")

        // with no move in flight at the death no rest after it is an end: the latency from the death, as your 12
        val starts = List(4000) { seed ->
            val p = ViewPolicy.turnBack(1000, 45.0, false, ViewPolicy.Learned(backRate = 1.0), Random(seed), TurnBack.NO_MOVE)
            val planned = p.at
            // a look out right after the death, back 300 ms before the turn back is due (the looks fit it)
            val restAt = planned - 300
            var t = 1000L
            var start = - 1L
            while (start < 0) {
                val moving = t in 1050L until restAt && restAt > 1050
                if (p.update(t, moving, if (moving) 900 else restAt, move = 7) == Decision.START) start = t
                t += 50
            }
            (start - 1000) to planned - 1000
        }
        assertTrue(starts.all { (s, planned) -> s - planned in 0L .. 50L || planned < 400 }, "${starts.filter { (s, p) -> s - p !in 0L .. 50L && p >= 400 }.take(5)}")
        assertEquals(300.0, quantile(starts.map { it.first }, 0.5).toDouble(), 60.0, "your latency p50, not a wait after the look on top")
    }

    @Test
    fun `a key held through a death goes up at once when something comes onto its way`() {
        val h = HandLag()
        h.died(1000, held = true, style = null, clicking = false, others = true, rng = Random(46), reaction = { 600L }) { 0L }
        assertEquals(1575L, h.keyUpAt)
        h.keyUpBy(1200)
        assertEquals(1200L, h.keyUpAt)
        h.keyUpBy(1400)
        assertEquals(1200L, h.keyUpAt, "never later than due")
    }

    @Test
    fun `Auto Fishing's off-wait with the key held is drawn within your slowest key-up after a death`() {
        // learned spawn reactions reach 1.6 s: drawn under the 950 ms your key-ups after a death never passed, never piled there
        val samples = listOf(310, 352, 401, 455, 502, 610, 752, 820, 980, 1599, 1210, 702)
        val rng = Random(47)
        val waits = List(20_000) { TimingModel.draw(Action.SPAWN_REACTION, samples, null, rng, Action.DEATH_REACTION.capMs) }
        assertTrue(waits.all { it <= 950 }, "${waits.max()}")
        assertTrue(waits.count { it >= 940 } < 20_000 * 0.02, "a pile at the cap")
    }

    @Test
    fun `Auto Fishing's rod click waits for anything that is not yours under the crosshair, and a held bite is lost after your latest reel`() {
        assertTrue(RodWay.blocks(entity = true, own = false, usableBlock = false), "another player's creature, a player, an armor stand")
        assertFalse(RodWay.blocks(entity = true, own = true, usableBlock = false), "your own creature")
        assertTrue(RodWay.blocks(entity = false, own = false, usableBlock = true), "a door, a lever")
        assertFalse(RodWay.blocks(entity = false, own = false, usableBlock = false))
        // a bite at 0 whose reel is due at 200: a player stands in front from 100 to 500, the reel goes your reaction after
        val way = ClearWay()
        var reelAt = - 1L
        var lost = false
        for (t in 0L .. 2000L step 50) {
            if (RodWay.biteLost(t, 0, way.held)) {
                lost = true
                break
            }
            if (t >= 200 && way.ready(t, blocked = t in 100L .. 500L) { 150L }) {
                reelAt = t
                break
            }
        }
        assertFalse(lost)
        assertEquals(700L, reelAt, "the first clear tick at 550 plus 150")
        // in the way until 1200: past your latest reel the bite is lost, nothing is clicked
        val w2 = ClearWay()
        var click = - 1L
        var lostAt = - 1L
        for (t in 0L .. 2000L step 50) {
            if (RodWay.biteLost(t, 0, w2.held)) {
                lostAt = t
                break
            }
            if (t >= 200 && w2.ready(t, blocked = t in 100L .. 1200L) { 150L }) {
                click = t
                break
            }
        }
        assertEquals(- 1L, click)
        assertEquals(1000L, lostAt, "the first tick past 950 ms")
        assertFalse(RodWay.biteLost(5000, 0, held = false), "nothing in the way: the reel is the reel's own business")
    }

    @Test
    fun `a late loadout menu is closed a reaction after it opened, never in its tick, and left to a switch that uses it`() {
        val at = LateMenu.closeAt(1000) { 300L }
        assertEquals(1275L, at)
        assertEquals(LateMenu.Step.WAIT, LateMenu.closes(at, 1000, busy = false, open = true), "not in the tick it opened")
        assertEquals(LateMenu.Step.WAIT, LateMenu.closes(at, 1250, busy = false, open = true))
        assertEquals(LateMenu.Step.CLOSE, LateMenu.closes(at, 1300, busy = false, open = true))
        assertEquals(LateMenu.Step.DROP, LateMenu.closes(at, 1300, busy = true, open = true), "a new switch took it")
        assertEquals(LateMenu.Step.DROP, LateMenu.closes(at, 1100, busy = false, open = false), "you closed it")
        assertEquals(LateMenu.Step.WAIT, LateMenu.closes(0L, 1300, busy = false, open = true), "none")
        // your menu reactions without recordings of them: 260-480 ms, never within a tick
        val rng = Random(48)
        repeat(2000) { assertTrue(LateMenu.closeAt(0) { TimingModel.ranged(emptyList(), 260, 480, rng) } >= TimingModel.TICK_MS) }
    }
}
