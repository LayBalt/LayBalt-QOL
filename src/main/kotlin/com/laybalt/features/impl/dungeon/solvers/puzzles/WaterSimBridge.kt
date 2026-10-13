package com.laybalt.features.impl.dungeon.solvers.puzzles

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.laybalt.LayBaltQOL
import com.laybalt.features.impl.dungeon.solvers.PuzzleSolvers
import com.laybalt.features.impl.dungeon.solvers.puzzles.water.*
import com.laybalt.utils.dungeons.map.utils.ScanUtils
import net.minecraft.core.BlockPos
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.max

/**
 * The simulator side of a Water Board session (client thread; the heavy work goes to [WaterPlannerExecutor]).
 *
 * Once the board's layout is read it runs the calibration gate: the simulator is only used if it reproduces the shipped
 * solutions of this very board. Then it answers the [WaterSupervisor]'s requests (plans and checks of the run, built from
 * a simulator state synced to the board), offers static one-run settings for door states without a shipped play, and
 * shadows real runs to demote the simulator when it stops matching the board.
 */
class WaterSimBridge(
    private val tracker: BoardTracker,
    /** Runs a result handler on the client thread; an exception in it switches the board-truth side off, not the game. */
    private val guard: (String, () -> Unit) -> Unit = { _, block -> block() },
    /** A line for the room's trace. */
    private val trace: (String) -> Unit = {},
    private val frame: () -> RoomFrame?,
) : WaterSupervisor.SimSupport {
    val sessionId = WaterPlannerExecutor.newSession()

    var layout: BoardLayout? = null
        private set
    var calibration: CalibrationResult = CalibrationResult.pending()
        private set

    /** Trust in the simulator for this room right now (the gate's verdict, lowered by shadow demotions). */
    var trust = SimTrust.PENDING
        private set
    var zLeaks = 0
        private set

    private var table: ShippedTable? = null
    private var shadow: ShadowCheck? = null
    private var demotions = 0
    private var recalibrated = false
    private var startRechecked = false
    private var lastSwitch: BoardEvent.WaterSwitched? = null
    private val recentFlips = ArrayDeque<IntArray>()
    private val doorToggled = IntArray(Door.entries.size) { BoardTracker.NEVER }
    private var lastPlanNote = ""

    /** Ticks a plan typically takes from the request to the player (measured; plans start no earlier). */
    private var planLatency = 8
    private val requestedAt = HashMap<Int, Int>()

    /** Door toggles without water in their outlet column (a diagnostic for the log and the trace). */
    private val doorWater = DoorWaterCheck(tracker)

    // ================================================================================================================
    // Layout and calibration
    // ================================================================================================================

    fun onLayoutCaptured(table: ShippedTable?) {
        val f = frame() ?: return
        val (front, back) = tracker.referenceCodes()
        val built = BoardLayout.fromCodes(front, back, tracker.variant)
        layout = built
        this.table = table
        zLeaks = WaterBoardReader.zLeaks(f, tracker)
        when {
            table == null -> untrusted("no shipped solutions for this layout")
            zLeaks > 0 -> untrusted("$zLeaks open board cells leak to the front or back plane")
            else -> runGate(null)
        }
    }

    private fun untrusted(why: String) {
        trust = SimTrust.UNTRUSTED
        calibration = CalibrationResult(SimTrust.UNTRUSTED, WaterRules(), null, tracker.startOffset, false, 0, 0, emptyList(), null, why)
        LayBaltQOL.logger.info("[WaterBoard] simulator not used: $why")
    }

    private fun runGate(measured: Int?, uncertain: Int = tracker.parityUncertain) {
        val layout = layout ?: return
        val table = table ?: return
        val f = frame() ?: return
        val offLead = if (tracker.measured(2) && tracker.measured(3)) tracker.lag(2) - tracker.lag(3) else null
        val input = CalibrationInput(layout, table, WaterRules(rotation = f.rot), tracker.startOffset, uncertain, measured, offLead)
        WaterPlannerExecutor.submit(WaterPlannerExecutor.Kind.CALIBRATION, sessionId, { Calibration.calibrate(input, it) }) {
            guard("calibration result") { apply(it) }
        }
    }

    private fun apply(result: CalibrationResult) {
        calibration = result
        trust = if (demotions > 0 && result.trust == SimTrust.TRUSTED) SimTrust.DEGRADED else result.trust
        if (result.startOffsetChanged) tracker.overrideStart(result.startOffset, "calibration (the shipped solutions only work from it)")
        LayBaltQOL.logger.info("[WaterBoard] calibration: $trust - ${result.summary}")
        trace("calibration $trust - ${result.summary}" + result.outletToDoor?.let { perm ->
            val l = layout
            ", outlets " + perm.indices.joinToString(" ") { o -> "x=${l?.let { BoardGeometry.x(it.outlets[o]) } ?: o}:${Door.entries[perm[o]].displayName}" }
        }.orEmpty())
        if (WaterBoardRecorder.enabled) WaterBoardRecorder.write("calibration-v${tracker.variant}-${LocalDateTime.now().format(STAMP)}.json", calibrationJson(result))
    }

    // ================================================================================================================
    // Per tick
    // ================================================================================================================

    fun onEvents(events: List<BoardEvent>) {
        doorWater.onEvents(events)
        for (e in events) when (e) {
            is BoardEvent.LeverFlipped -> {
                recentFlips.addLast(intArrayOf(e.lever.ordinal, e.tick))
                shadow?.onFlip(e.lever.ordinal, e.tick)
            }

            is BoardEvent.DoorToggled -> {
                doorToggled[e.door.digit] = e.tick
                learnDoorLatency(e)
            }

            is BoardEvent.WaterSwitched -> {
                lastSwitch = e
                val l = layout
                if (e.on && shadow == null && l != null && trust.simulates && tracker.boardWater == 0) {
                    shadow = ShadowCheck(l, calibration.rules).also { it.start(tracker.now, tracker.mask) }
                }
            }

            is BoardEvent.Resynced -> {
                // Nothing in between was seen: the shadow run, recent flips and the last water switch no longer describe
                // the board.
                shadow?.stop()
                shadow = null
                recentFlips.clear()
                lastSwitch = null
            }
        }
    }

    /**
     * The outlet column (x) of [door]: the fitted mapping of a board the simulator reproduces, otherwise the old
     * Skyblocker mapping (which the fitted one matched on every board of the 3.2.4 field logs).
     */
    private fun outletX(door: Door): Int {
        val l = layout
        val perm = calibration.outletToDoor
        if (l != null && perm != null && trust.simulates) {
            val o = perm.indexOf(door.digit)
            if (o >= 0) return BoardGeometry.x(l.outlets[o])
        }
        return DoorWaterCheck.DEFAULT_OUTLET_X[door.digit]
    }

    private fun learnDoorLatency(e: BoardEvent.DoorToggled) {
        // Only with the fitted mapping: the latency feeds the plans of a trusted simulator.
        if (! trust.simulates) return
        val l = layout ?: return
        val perm = calibration.outletToDoor ?: return
        val o = perm.indexOf(e.door.digit)
        if (o < 0) return
        val cell = l.outlets[o]
        if (tracker.water[cell] >= 0) doorLatencies.learn(e.tick - tracker.wetSince[cell])
    }

    /** Ticks from water reaching an outlet to its door toggling, measured on this board (plans and pending doors use it). */
    private val doorLatencies = DoorLatency()

    /** Typical outlet-to-door latency on this board: measured, or the calibrated default until a door has toggled. */
    val doorLatency: Int get() = doorLatencies.value(calibration.rules.doorDelay)

    /** Doors whose outlet just got water but which have not toggled since (their toggle is on its way). */
    private fun pendingDoors(now: Int): Int {
        val l = layout ?: return 0
        val perm = calibration.outletToDoor ?: return 0
        // A door is only reported once it held its new state for the tracker's debounce.
        val window = DoorLatency.pendingWindow(doorLatency) + BoardTracker.DOOR_DEBOUNCE
        var pending = 0
        for (o in l.outlets.indices) {
            val cell = l.outlets[o]
            val since = tracker.wetSince[cell]
            if (tracker.water[cell] < 0 || now - since > window) continue
            val door = perm[o]
            if (doorToggled[door] < since) pending = pending or (1 shl door)
        }
        return pending
    }

    /** [silentFailures]: plays in a row that failed although every flip was on time (see [WaterSupervisor.silentFailures]). */
    fun tick(now: Int, silentFailures: Int = 0, solved: Boolean = false) {
        while (recentFlips.isNotEmpty() && recentFlips.first()[1] < now - 10) recentFlips.removeFirst()
        for (text in doorWater.tick(now, ::outletX)) {
            LayBaltQOL.logger.info("[WaterBoard] $text")
            trace(text)
        }
        val sh = shadow
        if (sh != null) {
            if (! solved && (tracker.waterOn || tracker.boardWater > 0)) {
                val wasDemoted = sh.demoted
                sh.tick(now, tracker.water)
                if (sh.demoted && ! wasDemoted) {
                    demotions ++
                    if (trust == SimTrust.TRUSTED) trust = SimTrust.DEGRADED
                    LayBaltQOL.logger.info("[WaterBoard] the simulation stopped matching the board (max ${sh.maxDiff} cells off): timed fixes off for this room")
                }
            }
            else {
                if (WaterBoardRecorder.enabled && sh.ticksCompared > 0) {
                    WaterBoardRecorder.appendLine("selfcheck.jsonl", sh.summaryJson(tracker.variant, Door.keyOf(tracker.doors), calibration.rules.describe()))
                }
                sh.stop()
                shadow = null
            }
        }
        // Our own water click tells the water's delay: if the gate failed without it, try once more with it.
        if (! recalibrated && trust != SimTrust.TRUSTED && trust != SimTrust.PENDING && zLeaks == 0 && table != null &&
            tracker.measured(0) && tracker.measured(2)
        ) {
            recalibrated = true
            // On samples only (lag 2): the entry drying after an off click has its own delay (lag 3).
            runGate(tracker.lag(2) - tracker.lag(0))
        }
        // Runs that fail with every flip on time point at a wrong guess of the untouched levers: let the gate try every
        // lever state against the shipped solutions once (it adopts one only if it alone makes them work).
        if (! startRechecked && silentFailures >= 2 && zLeaks == 0 && table != null && trust != SimTrust.PENDING) {
            startRechecked = true
            LayBaltQOL.logger.info("[WaterBoard] runs fail with every flip on time: checking every untouched lever state against the shipped solutions")
            runGate(if (tracker.measured(0) && tracker.measured(2)) tracker.lag(2) - tracker.lag(0) else null, uncertain = BoardTracker.ALL_LEVERS)
        }
    }

    // ================================================================================================================
    // Requests of the supervisor
    // ================================================================================================================

    fun handle(requests: List<WaterSupervisor.Request>, sup: WaterSupervisor, now: Int) {
        for (r in requests) when (r) {
            is WaterSupervisor.PlanNeeded -> {
                val req = request(now, r.remaining, r.fault, sup)
                if (req == null) {
                    sup.onPlanResult(r.id, null, tracker)
                    continue
                }
                requestedAt[r.id] = now
                WaterPlannerExecutor.submit(WaterPlannerExecutor.Kind.PLAN, sessionId, { Planner.plan(req, it) }) { result ->
                    guard("plan result") {
                        requestedAt.remove(r.id)?.let { at -> learnLatency(tracker.now - at) }
                        lastPlanNote = result.note
                        LayBaltQOL.logger.info("[WaterBoard] plan: ${result.best ?: "none, restart"} (${result.note})")
                        trace("plan: ${result.best ?: "none, restart"} (${result.note})")
                        sup.onPlanResult(r.id, result.best, tracker)
                    }
                }
            }

            is WaterSupervisor.CheckNeeded -> {
                val req = request(now, r.remaining, null, sup) ?: continue
                WaterPlannerExecutor.submit(WaterPlannerExecutor.Kind.CHECK, sessionId, { Planner.check(req, req.remaining.orEmpty(), it) }) { e ->
                    guard("check result") { sup.onCheckResult(r.id, e.solved, reason(e), tracker) }
                }
            }
        }
    }

    private fun learnLatency(ticks: Int) {
        planLatency = if (ticks > planLatency) (ticks + 2).coerceAtMost(40) else maxOf(4, (planLatency * 3 + ticks) / 4)
        requestedAt.keys.removeAll { it < 0 }
    }

    private fun reason(e: Evaluation): String? = when {
        e.solved -> null
        e.wrongDoor >= 0 -> "the water is heading into the ${Door.entries[e.wrongDoor].colored}§f channel, which has to stay dry"
        e.missing != 0 -> "the water is not going to reach ${Door.names(e.missing)}"
        else -> null
    }

    private fun request(now: Int, remaining: List<PlanStep>?, fault: PlanFault?, sup: WaterSupervisor): PlanRequest? {
        val l = layout ?: return null
        val perm = calibration.outletToDoor ?: return null
        val human = human(now)
        // Our clicks the board has not shown yet: the simulation plays them where they will land, and they no longer
        // count as clicks still to come. They were sent already, so they land after the measured lag (ping included)
        // whatever the ping compensation setting says; the setting only changes how clicks still to come are shown.
        val inFlight = tracker.unmatchedClicks(max(tracker.lag(0), 1) + 3)
        val left = remaining?.toMutableList()
        val extra = ArrayList<External>()
        var on = tracker.waterOn
        for ((lever, click) in inFlight) {
            if (lever == WaterLever.WATER) {
                if (lastSwitch?.clickTick == click) continue
                on = ! on
                extra += External.Source(click + waterLag(on), on)
            }
            else extra += External.Flip(click + tracker.lag(0), lever.ordinal)
            left?.let { steps ->
                val i = steps.indexOfFirst { it.lever == lever && kotlin.math.abs(it.clickTick - click) <= sup.toleranceTicks }
                if (i >= 0) steps.removeAt(i)
            }
        }
        val state = sync(now, extra) ?: return null
        val restartEnd = now + (if (tracker.waterOn) 20 else 0) + restartCost(sup)
        val budget = SearchBudget(timed = trust == SimTrust.TRUSTED)
        return PlanRequest(
            l, calibration.rules.copy(doorDelay = doorLatency), state, now, tracker.doors, perm, human, left, fault, restartEnd, budget,
            pendingDoors = pendingDoors(now), startAt = now + planLatency,
        )
    }

    /** Ticks a drain and restart would take from here (the cheaper of the shipped plays and a static setting). */
    private fun restartCost(sup: WaterSupervisor): Int {
        val table = sup.table
        val algebra = DoorAlgebra.best(tracker.doors, table, tracker.parity, boardReady = false)
        var cost = if (algebra.isEmpty()) Int.MAX_VALUE / 4 else DoorAlgebra.cost(algebra, tracker.parity, boardReady = false)
        staticPlay(tracker.doors, tracker.parity)?.let { play ->
            val model = DoorAlgebra.CostModel()
            val static = model.drainTicks + Integer.bitCount(tracker.parity xor play.startParity) * model.fixTicks + (play.expectedTicks ?: 200)
            cost = minOf(cost, static)
        }
        return cost
    }

    /** The board as a simulator state at [now], with the water's reaction to a recent water click still to come. */
    fun sync(now: Int, extra: List<External> = emptyList()): BoardState? {
        val l = layout ?: return null
        val pending = ArrayList<External>(extra)
        var sourceOn = tracker.waterOn
        val sw = lastSwitch
        if (sw != null && ! sw.correction && sw.clickTick + waterLag(sw.on) > now) {
            sourceOn = ! sw.on
            pending += External.Source(sw.clickTick + waterLag(sw.on), sw.on)
        }
        return StateSync.sync(
            StateSync.SyncInput(
                l, calibration.rules, tracker.water, tracker.cellChangeTick, tracker.cellChangeSeq, tracker.mask, sourceOn, now,
                recentFlips.map { it[0] to it[1] }, pending,
            )
        )
    }

    /**
     * Ticks from a water click to the water reaching ([on]) or no longer reaching the entry hole, each measured on its
     * own; an unmeasured off delay is the on delay minus the calibrated [WaterRules.sourceOffLead].
     */
    private fun waterLag(on: Boolean): Int {
        val rules = calibration.rules
        val onLag = if (tracker.measured(2)) tracker.lag(2) else tracker.lag(0) + max(0, rules.sourceDelay - rules.leverDelay)
        if (on) return onLag
        return if (tracker.measured(3)) tracker.lag(3) else max(1, onLag - rules.sourceOffLead)
    }

    private fun human(now: Int): HumanModel {
        val rtt = tracker.rttTicks
        val compensate = PuzzleSolvers.waterPingCompensation.value
        fun shown(lag: Int) = if (compensate) lag else max(1, lag - rtt)
        val flipLag = shown(tracker.lag(0))
        val waterLag = shown(waterLag(true))
        val waterOffLag = shown(waterLag(false))
        val f = frame()
        val player = LayBaltQOL.mc.player
        if (f == null || player == null) return HumanModel(flipLag, waterLag, waterOffLag = waterOffLag)
        val rel = ScanUtils.getRelativeCoord(BlockPos.containing(player.x, player.y, player.z), f.center, f.rot)
        return HumanModel(flipLag, waterLag, waterOffLag = waterOffLag, playerX = rel.x + 0.5, playerZ = rel.z + 0.5)
    }

    // ================================================================================================================
    // SimSupport
    // ================================================================================================================

    private val ready get() = layout != null && calibration.outletToDoor != null

    override fun continuationsAllowed(): Boolean {
        if (! PuzzleSolvers.waterQuickFixes.value || ! ready) return false
        return when (trust) {
            SimTrust.TRUSTED -> true
            // A partly reproduced board: only while the run in progress still matches the simulation.
            SimTrust.DEGRADED -> shadow?.let { it.ticksCompared > 0 && it.clean } ?: false
            else -> false
        }
    }

    override fun checksAllowed() = PuzzleSolvers.waterQuickFixes.value && ready && trust == SimTrust.TRUSTED

    /**
     * Only on a fully trusted board: a partly reproduced (or shadow-demoted) simulator is known to be wrong somewhere,
     * and the shipped plays a static play would replace are known to work.
     */
    override fun staticPlay(closed: Int, parity: Int): ShippedPlay? {
        if (! PuzzleSolvers.waterQuickFixes.value || trust != SimTrust.TRUSTED || ! ready) return null
        val doors = calibration.staticFromDry ?: return null
        val ticks = calibration.staticTicks
        val m = (0 until 64).filter { doors[it] == closed }
            .minWithOrNull(compareBy({ Integer.bitCount(it xor parity) }, { ticks?.get(it) ?: 0 }, { it })) ?: return null
        return ShippedPlay.static(tracker.variant, closed, m, ticks?.get(m)?.takeIf { it >= 0 })
    }

    // ================================================================================================================
    // Status
    // ================================================================================================================

    fun status(): List<String> {
        val lines = ArrayList<String>()
        val c = calibration
        lines += "§7Simulator: §f$trust §7- ${c.summary}"
        c.outletToDoor?.takeIf { trust.simulates }?.let { perm ->
            val l = layout
            val text = perm.indices.joinToString(", ") { o ->
                val x = l?.let { BoardGeometry.x(it.outlets[o]) } ?: o
                "x=$x ${Door.entries[perm[o]].colored}§7"
            }
            lines += "§7Outlets: $text"
        }
        shadow?.let { lines += "§7Shadow check: §f${it.ticksCompared} §7ticks, mean §f${"%.1f".format(it.meanDiff)} §7max §f${it.maxDiff} §7cells off" }
        if (demotions > 0) lines += "§7Shadow demotions: §f$demotions"
        lines += "§7Water to door: §f$doorLatency §7ticks" + if (doorLatencies.measured) "" else " §8(default)"
        if (doorWater.withoutWater > 0) lines += "§7Doors that changed without water in their outlet column: §f${doorWater.withoutWater} §7(see the log)"
        if (lastPlanNote.isNotEmpty()) lines += "§7Last plan: §f$lastPlanNote"
        return lines
    }

    private fun calibrationJson(r: CalibrationResult): String {
        val json = JsonObject()
        json.addProperty("format", 1)
        json.addProperty("kind", "calibration")
        json.addProperty("variant", tracker.variant)
        json.addProperty("trust", r.trust.name)
        json.addProperty("passes", r.passes)
        json.addProperty("total", r.total)
        json.addProperty("summary", r.summary)
        json.addProperty("leverDelay", r.rules.leverDelay)
        json.addProperty("sourceDelay", r.rules.sourceDelay)
        json.addProperty("sourceOffLead", r.rules.sourceOffLead)
        json.addProperty("externalDelay", r.rules.externalDelay)
        json.addProperty("dripArrivals", r.rules.dripArrivals)
        json.addProperty("rotation", r.rules.rotation)
        json.addProperty("startOffset", r.startOffset)
        json.addProperty("zLeaks", zLeaks)
        r.outletToDoor?.let { perm -> json.add("outletToDoor", JsonArray().apply { perm.forEach { add(it) } }) }
        json.add("plays", JsonArray().apply {
            r.checks.forEach { c ->
                add(JsonObject().apply {
                    addProperty("key", c.key)
                    addProperty("passed", c.passed)
                    addProperty("doors", Door.keyOf(c.doors))
                    addProperty("rewet", c.rewet)
                    addProperty("lastArrival", c.lastArrival)
                    addProperty("lastClick", c.lastClick)
                })
            }
        })
        return GsonBuilder().setPrettyPrinting().create().toJson(json)
    }

    companion object {
        private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    }
}
