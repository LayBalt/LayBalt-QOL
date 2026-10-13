package puzzles.water

import com.laybalt.features.impl.dungeon.solvers.puzzles.water.*

/**
 * A closed loop on the toy comb: the simulator plays the real board (with planted timing and outlet-to-door mapping),
 * a [WaterSupervisor] watches it as it would watch Hypixel's board, its planner requests are answered by the real
 * [Planner] (synchronously, one tick later), and a bot clicks whatever the guidance says, when it says so. Faults are
 * injected as extra clicks.
 */
class ToyWorld(
    closedDoors: Int,
    val trusted: Boolean = true,
    override val flipLag: Int = 1,
    val waterLag: Int = 15,
) : BoardView {
    val layout = ToyBoards.comb
    val rules = WaterRules(leverDelay = 1, sourceDelay = 15, externalDelay = 4)
    val perm = intArrayOf(2, 4, 0, 1, 3)
    val table: ShippedTable = syntheticTable()
    val world = BoardState(layout, rules)
    val sup = WaterSupervisor(table)
    val messages = ArrayList<String>()

    /** What the planner is told a drain and restart would take from now. */
    var restartTicks = 2000

    /** Timed search allowed. */
    var timedSearch = true
    val stages = ArrayList<WaterSupervisor.Stage>()
    val plans = ArrayList<ContinuationPlan?>()
    var clicks = ArrayList<Pair<Int, WaterLever>>()

    private val pendingEvents = ArrayList<BoardEvent>()
    private val pendingDoors = ArrayList<IntArray>() // [tick, door]
    private val pendingResults = ArrayList<() -> Unit>()
    private var lastClickOn = IntArray(WaterLever.entries.size) { - 100 }
    private val staticDoors: IntArray = Calibration.staticTable(layout, rules, perm, 0, CancelToken.NONE)

    override var now = 0
        private set
    override val ready get() = true
    override var doors = closedDoors
        private set
    override val parity get() = world.mask
    override val transit get() = 0
    override val parityUncertain get() = 0
    override val waterOn: Boolean
        get() {
            var on = world.sourceOn
            for (i in world.nextExternal until world.externals.size) (world.externals[i] as? External.Source)?.let { on = it.on }
            return on
        }
    override val waterClickPending get() = false
    override val boardWater get() = world.waterCount()
    override val lastWaterChange get() = world.lastChange

    init {
        sup.sim = object : WaterSupervisor.SimSupport {
            override fun continuationsAllowed() = trusted
            override fun checksAllowed() = trusted
            override fun staticPlay(closed: Int, parity: Int): ShippedPlay? {
                if (! trusted) return null
                val m = (0 until 64).filter { staticDoors[it] == closed }.minByOrNull { Integer.bitCount(it xor parity) } ?: return null
                return ShippedPlay.static(0, closed, m, 200)
            }
        }
    }

    // ---- the real board ----------------------------------------------------------------------------------------------

    /** The player clicks [lever] now; the board shows it after the lag. */
    fun click(lever: WaterLever, source: FlipSource = FlipSource.OWN) {
        clicks += now to lever
        lastClickOn[lever.ordinal] = now
        val click = now
        if (lever == WaterLever.WATER) {
            val on = ! waterOn
            world.addExternal(External.Source(click + waterLag, on))
            // The tracker sees the water lever's state change right away (its powered state), dated at the click.
            pendingEvents += BoardEvent.WaterSwitched(on, click + 1, click, source)
        }
        else {
            world.addExternal(External.Flip(click + flipLag, lever.ordinal))
            pendingEvents += BoardEvent.LeverFlipped(lever, click + flipLag, click, source)
        }
    }

    fun step() {
        now ++
        val toggled = ArrayList<Int>()
        WaterSim.step(world, object : SimListener {
            override fun arrival(outlet: Int, tick: Int) {
                pendingDoors += intArrayOf(tick + rules.doorDelay, perm[outlet])
            }
        })
        val events = ArrayList<BoardEvent>()
        pendingEvents.removeAll { e -> if (e.tick <= now) { events += e; true } else false }
        pendingDoors.removeAll { d ->
            if (d[0] <= now) {
                doors = doors xor (1 shl d[1])
                toggled += d[1]
                events += BoardEvent.DoorToggled(Door.entries[d[1]], doors and (1 shl d[1]) != 0, now)
                true
            }
            else false
        }
        // Planner answers from last tick arrive now.
        val results = pendingResults.toList()
        pendingResults.clear()
        results.forEach { it() }
        sup.update(this, events)
        answer(sup.drainRequests())
        messages += sup.drainMessages()
        stages += sup.stage
        check(sup.stage == WaterSupervisor.Stage.SOLVED || ! sup.guidance(this).silent) { "silent in ${sup.stage}" }
    }

    private fun answer(requests: List<WaterSupervisor.Request>) {
        for (r in requests) {
            // Like the real session: clicks the board has not shown yet are in the state already, not still to come.
            val remaining = ((r as? WaterSupervisor.PlanNeeded)?.remaining ?: (r as? WaterSupervisor.CheckNeeded)?.remaining)?.toMutableList()
            for (e in pendingEvents) {
                val lever = when (e) {
                    is BoardEvent.LeverFlipped -> e.lever
                    is BoardEvent.WaterSwitched -> WaterLever.WATER
                    else -> continue
                }
                val click = when (e) {
                    is BoardEvent.LeverFlipped -> e.clickTick
                    is BoardEvent.WaterSwitched -> e.clickTick
                    else -> continue
                }
                remaining?.indexOfFirst { it.lever == lever && kotlin.math.abs(it.clickTick - click) <= sup.toleranceTicks }?.takeIf { it >= 0 }?.let { remaining.removeAt(it) }
            }
            val req = PlanRequest(
                layout, rules, world.copy(), now, doors, perm, HumanModel(flipLag, waterLag, reaction = 8),
                remaining, (r as? WaterSupervisor.PlanNeeded)?.fault, now + restartTicks, SearchBudget(wallClockMs = 0, timed = timedSearch),
                pendingDoors = pendingDoors.fold(0) { acc, d -> acc or (1 shl d[1]) },
            )
            when (r) {
                is WaterSupervisor.PlanNeeded -> {
                    val result = Planner.plan(req)
                    plans += result.best
                    pendingResults += { sup.onPlanResult(r.id, result.best, this) }
                }

                is WaterSupervisor.CheckNeeded -> {
                    val e = Planner.check(req, req.remaining.orEmpty())
                    pendingResults += { sup.onCheckResult(r.id, e.solved, if (e.solved) null else "the water is going wrong", this) }
                }
            }
        }
    }

    // ---- the bot -----------------------------------------------------------------------------------------------------

    private val ignoredUntil = IntArray(WaterLever.entries.size)

    /** The bot does not click [lever] for the next [ticks] ticks (a forgotten click). */
    fun ignore(lever: WaterLever, ticks: Int) {
        ignoredUntil[lever.ordinal] = now + ticks
    }

    /** Clicks what the guidance says is due now (one lever per tick, never the same lever twice within its lag). */
    fun bot() {
        val g = sup.guidance(this) as? Guidance.Act ?: return
        val due = g.labels.filter { label ->
            label.text.contains("CLICK") || label.text.contains("FLIP") || label.text.contains("WATER OFF") || label.text.contains("PULL")
        }.map { it.lever }.distinct().filter { now >= ignoredUntil[it.ordinal] }
        // Block levers first (pre-water fixes come before the water), then the water.
        val lever = due.firstOrNull { it != WaterLever.WATER } ?: due.firstOrNull() ?: return
        val lag = if (lever == WaterLever.WATER) waterLag else flipLag
        if (now - lastClickOn[lever.ordinal] <= lag + 2) return
        click(lever)
    }

    /** Runs the loop for [ticks] ticks (or until solved), the bot playing along; [fault] may click something extra. */
    fun run(ticks: Int, fault: (ToyWorld) -> Unit = {}) {
        repeat(ticks) {
            if (sup.stage == WaterSupervisor.Stage.SOLVED) return
            fault(this)
            bot()
            step()
        }
    }

    private fun syntheticTable(): ShippedTable {
        val plays = LinkedHashMap<Int, ShippedPlay>()
        for ((pre, timed) in ToyBoards.candidates) {
            val probe = ToyBoards.play("probe", pre, *timed.toTypedArray())
            val outcome = SimRunner.runPlay(layout, rules, probe, 0)
            if (outcome.rewet || Integer.bitCount(outcome.arrived) != 3) continue
            val d = CalibrationResult.mapDoors(outcome.arrived, perm)
            if (d !in plays) plays[d] = ToyBoards.play(Door.keyOf(d), pre, *timed.toTypedArray())
        }
        return ShippedTable(0, plays.values)
    }
}
