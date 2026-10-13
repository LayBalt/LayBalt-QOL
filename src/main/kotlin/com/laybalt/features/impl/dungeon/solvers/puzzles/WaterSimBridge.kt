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
    private var lastSwitch: BoardEvent.WaterSwitched? = null
    private val recentFlips = ArrayDeque<IntArray>()
    private val doorToggled = IntArray(Door.entries.size) { BoardTracker.NEVER }
    private var lastPlanNote = ""
    private var doorsWithoutWater = 0

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

    private fun runGate(measured: Int?) {
        val layout = layout ?: return
        val table = table ?: return
        val f = frame() ?: return
        val input = CalibrationInput(layout, table, WaterRules(rotation = f.rot), tracker.startOffset, tracker.parityUncertain, measured)
        WaterPlannerExecutor.submit(WaterPlannerExecutor.Kind.CALIBRATION, sessionId, { Calibration.calibrate(input, it) }) { apply(it) }
    }

    private fun apply(result: CalibrationResult) {
        calibration = result
        trust = if (demotions > 0 && result.trust == SimTrust.TRUSTED) SimTrust.DEGRADED else result.trust
        if (result.startOffsetChanged) tracker.overrideStart(result.startOffset, "calibration (the shipped solutions only work from it)")
        LayBaltQOL.logger.info("[WaterBoard] calibration: $trust - ${result.summary}")
        if (WaterBoardRecorder.enabled) WaterBoardRecorder.write("calibration-v${tracker.variant}-${LocalDateTime.now().format(STAMP)}.json", calibrationJson(result))
    }

    // ================================================================================================================
    // Per tick
    // ================================================================================================================

    fun onEvents(events: List<BoardEvent>) {
        for (e in events) when (e) {
            is BoardEvent.LeverFlipped -> {
                recentFlips.addLast(intArrayOf(e.lever.ordinal, e.tick))
                shadow?.onFlip(e.lever.ordinal, e.tick)
            }

            is BoardEvent.DoorToggled -> {
                doorToggled[e.door.digit] = e.tick
                checkDoorHadWater(e)
            }

            is BoardEvent.WaterSwitched -> {
                lastSwitch = e
                val l = layout
                if (e.on && shadow == null && l != null && trust.simulates && tracker.boardWater == 0) {
                    shadow = ShadowCheck(l, calibration.rules).also { it.start(tracker.now, tracker.mask) }
                }
            }

        }
    }

    /**
     * The whole recovery rests on doors toggling only when water reaches their outlet (and keeping their state when the
     * board drains). A door that changes without water at its outlet is logged, so recordings can show if Hypixel does
     * otherwise (for example resetting the doors when the water is pulled again).
     */
    private fun checkDoorHadWater(e: BoardEvent.DoorToggled) {
        val l = layout ?: return
        val perm = calibration.outletToDoor ?: return
        val o = perm.indexOf(e.door.digit)
        if (o < 0) return
        val cell = l.outlets[o]
        if (tracker.water[cell] >= 0 || e.tick - tracker.wetSince[cell] <= DOOR_WATER_WINDOW) return
        doorsWithoutWater ++
        LayBaltQOL.logger.info("[WaterBoard] the ${e.door.displayName} door ${if (e.closed) "closed" else "opened"} at ${e.tick} without water at its outlet (x=${BoardGeometry.x(cell)})")
    }

    /** Doors whose outlet just got water but which have not toggled since (their toggle is on its way). */
    private fun pendingDoors(now: Int): Int {
        val l = layout ?: return 0
        val perm = calibration.outletToDoor ?: return 0
        var pending = 0
        for (o in l.outlets.indices) {
            val cell = l.outlets[o]
            val since = tracker.wetSince[cell]
            if (tracker.water[cell] < 0 || now - since > PENDING_DOOR_TICKS) continue
            val door = perm[o]
            if (doorToggled[door] < since) pending = pending or (1 shl door)
        }
        return pending
    }

    fun tick(now: Int) {
        while (recentFlips.isNotEmpty() && recentFlips.first()[1] < now - 10) recentFlips.removeFirst()
        val sh = shadow
        if (sh != null) {
            if (tracker.waterOn || tracker.boardWater > 0) {
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
            runGate(tracker.lag(2) - tracker.lag(0))
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
                WaterPlannerExecutor.submit(WaterPlannerExecutor.Kind.PLAN, sessionId, { Planner.plan(req, it) }) { result ->
                    lastPlanNote = result.note
                    LayBaltQOL.logger.info("[WaterBoard] plan: ${result.best ?: "none, restart"} (${result.note})")
                    sup.onPlanResult(r.id, result.best, tracker)
                }
            }

            is WaterSupervisor.CheckNeeded -> {
                val req = request(now, r.remaining, null, sup) ?: continue
                WaterPlannerExecutor.submit(WaterPlannerExecutor.Kind.CHECK, sessionId, { Planner.check(req, req.remaining.orEmpty(), it) }) { e ->
                    sup.onCheckResult(r.id, e.solved, reason(e), tracker)
                }
            }
        }
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
        // count as clicks still to come.
        val inFlight = tracker.unmatchedClicks(max(tracker.lag(0), 1) + 3)
        val left = remaining?.toMutableList()
        val extra = ArrayList<External>()
        var on = tracker.waterOn
        for ((lever, click) in inFlight) {
            if (lever == WaterLever.WATER) {
                if (lastSwitch?.clickTick == click) continue
                on = ! on
                extra += External.Source(click + human.waterLag, on)
            }
            else extra += External.Flip(click + human.flipLag, lever.ordinal)
            left?.let { steps ->
                val i = steps.indexOfFirst { it.lever == lever && kotlin.math.abs(it.clickTick - click) <= sup.toleranceTicks }
                if (i >= 0) steps.removeAt(i)
            }
        }
        val state = sync(now, extra) ?: return null
        val restartEnd = now + (if (tracker.waterOn) 20 else 0) + restartCost(sup)
        val budget = SearchBudget(timed = trust == SimTrust.TRUSTED)
        return PlanRequest(l, calibration.rules, state, now, tracker.doors, perm, human, left, fault, restartEnd, budget, pendingDoors = pendingDoors(now))
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
        if (sw != null && ! sw.correction && sw.clickTick + waterLag() > now) {
            sourceOn = ! sw.on
            pending += External.Source(sw.clickTick + waterLag(), sw.on)
        }
        return StateSync.sync(
            StateSync.SyncInput(
                l, calibration.rules, tracker.water, tracker.cellChangeTick, tracker.cellChangeSeq, tracker.mask, sourceOn, now,
                recentFlips.map { it[0] to it[1] }, pending,
            )
        )
    }

    /** Ticks from a water click to the water reaching the entry hole. */
    private fun waterLag(): Int =
        if (tracker.measured(2)) tracker.lag(2)
        else tracker.lag(0) + max(0, calibration.rules.sourceDelay - calibration.rules.leverDelay)

    private fun human(now: Int): HumanModel {
        val rtt = tracker.rttTicks
        val compensate = PuzzleSolvers.waterPingCompensation.value
        val flipLag = tracker.lag(0).let { if (compensate) it else max(1, it - rtt) }
        val waterLag = waterLag().let { if (compensate) it else max(1, it - rtt) }
        val f = frame()
        val player = LayBaltQOL.mc.player
        if (f == null || player == null) return HumanModel(flipLag, waterLag)
        val rel = ScanUtils.getRelativeCoord(BlockPos.containing(player.x, player.y, player.z), f.center, f.rot)
        return HumanModel(flipLag, waterLag, playerX = rel.x + 0.5, playerZ = rel.z + 0.5)
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

    override fun staticPlay(closed: Int, parity: Int): ShippedPlay? {
        if (! PuzzleSolvers.waterQuickFixes.value || ! trust.simulates || ! ready) return null
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
        c.outletToDoor?.let { perm ->
            val l = layout
            val text = perm.indices.joinToString(", ") { o ->
                val x = l?.let { BoardGeometry.x(it.outlets[o]) } ?: o
                "x=$x ${Door.entries[perm[o]].colored}§7"
            }
            lines += "§7Outlets: $text"
        }
        shadow?.let { lines += "§7Shadow check: §f${it.ticksCompared} §7ticks, mean §f${"%.1f".format(it.meanDiff)} §7max §f${it.maxDiff} §7cells off" }
        if (demotions > 0) lines += "§7Shadow demotions: §f$demotions"
        if (doorsWithoutWater > 0) lines += "§7Doors that changed without water at their outlet: §f$doorsWithoutWater §7(see the log)"
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

        /** Water that reached an outlet this recently may still be on its way to toggling the door. */
        const val PENDING_DOOR_TICKS = 10

        /** A door toggling this long after its outlet last got water is logged as toggling without water. */
        const val DOOR_WATER_WINDOW = 40
    }
}
