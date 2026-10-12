package experiments

import com.laybalt.features.impl.general.experiments.ExperimentBoard
import com.laybalt.features.impl.general.experiments.ExperimentDriver
import com.laybalt.features.impl.general.experiments.ExperimentGame
import com.laybalt.features.impl.general.experiments.ExperimentHost
import com.laybalt.features.impl.general.experiments.ExperimentSolver
import com.laybalt.features.impl.general.experiments.ItemKind
import java.util.PriorityQueue
import kotlin.random.Random

/** One slot of the simulated chest, with exactly the properties the solvers read. */
data class SimItem(
    val kind: ItemKind,
    val color: Int = - 1,
    val lit: Boolean = false,
    val name: String = "",
    val count: Int = 1,
    val card: String? = null
) {
    companion object {
        val EMPTY = SimItem(ItemKind.EMPTY)
        val BORDER = SimItem(ItemKind.PANE, color = 15, name = " ")
        fun status(name: String) = SimItem(ItemKind.OTHER, name = name)
    }
}

/** The client's copy of the menu. */
class SimBoard: ExperimentBoard {
    val slots = Array(54) { SimItem.EMPTY }
    override val status get() = slots[49].name
    override fun kind(slot: Int) = slots[slot].kind
    override fun color(slot: Int) = slots[slot].color
    override fun isLit(slot: Int) = slots[slot].kind != ItemKind.EMPTY && slots[slot].lit
    override fun name(slot: Int) = slots[slot].name
    override fun count(slot: Int) = slots[slot].count
    override fun cardKey(slot: Int): Any? = slots[slot].takeUnless { it.kind == ItemKind.EMPTY }?.let { it.card ?: "${it.kind}/${it.color}/${it.name}" }
}

enum class ClickType { MIDDLE, LEFT }

/** How the simulated server orders the updates of one tick. */
enum class PacketOrder {
    /** Like a 1.8 server's detectAndSendChanges: one set-slot packet per changed slot, ascending slot index. */
    ASCENDING,
    /** Descending: the status item (slot 49) arrives before the board slots of the same tick. */
    DESCENDING,
    /** Random order within a tick. */
    SHUFFLED
}

/** A Hypixel add-on game as seen from the server: owns the authoritative board, advances per tick, judges clicks. */
abstract class SimServer {
    val board = Array(54) { SimItem.EMPTY }
    var failure: String? = null
        protected set
    var roundsCompleted = 0
        protected set
    /** Clicks the server ignored because no answer was expected (e.g. during "Remember the pattern!"). */
    var ignoredClicks = 0
        protected set
    val over get() = failure != null

    /** The game ended normally (e.g. Superpairs: out of clicks or all pairs found). */
    open val finished: Boolean get() = false

    abstract fun onTick(tick: Int)
    abstract fun onClick(tick: Int, slot: Int)

    /** Oracle for the simulated human: the slot of the [index]-th click of the current answer, null when none is due. */
    open fun expectedSlot(index: Int): Int? = null
}

data class SimConfig(
    val seed: Long = 1,
    val clickType: ClickType = ClickType.LEFT,
    val clickDelay: Long = 460,
    val delayVariance: Long = 180,
    val latencyMs: LongRange = 50L .. 150L,
    val packetOrder: PacketOrder = PacketOrder.ASCENDING,
    /** Server answers every click with a full window resync (what a cancelled inventory click does on Hypixel). */
    val resyncOnClick: Boolean = true,
    /** When the solver is attached; 0 = opened together with the window (ContainerEvent.Open). */
    val attachAtMs: Long = 0,
    /** Attach (lazily, on the next client tick) once the client has seen this many "Remember the pattern!" phases; 0 = off. */
    val attachAtRemember: Int = 0,
    /** Client freezes (lag spikes): (start, duration) in ms; packets pile up and are processed in one burst. */
    val freezes: List<Pair<Long, Long>> = emptyList(),
    val maxMs: Long = 300_000,
    /** A human answers every round the solver leaves alone (1.5 s into the timer, 400 ms per click) unless blocked. */
    val manualPlayer: Boolean = false,
    /** Stop rule handed to the solver (size > target = stop). */
    val target: Int = 99
)

data class SimClick(val atMs: Long, val slot: Int)

/** Pseudo slot index for the carried (cursor) item inside a resync packet. */
const val CURSOR = - 1

class SimResult(
    val roundsCompleted: Int,
    val failure: String?,
    val stoppedAt: Int?,
    val clicks: List<SimClick>,
    val ignoredClicks: Int,
    val humanClicks: Int,
    val blockedHumanClicks: Int,
    val log: List<String>
) {
    override fun toString() = "rounds=$roundsCompleted failure=$failure stoppedAt=$stoppedAt clicks=${clicks.size} ignored=$ignoredClicks " +
        "human=$humanClicks blockedHuman=$blockedHumanClicks"
    fun dump(lastLines: Int = 120) = log.takeLast(lastLines).joinToString("\n")
}

/**
 * Discrete-event simulation of one experiment: server ticks (50 ms), per-tick slot packets with latency (TCP order kept),
 * client ticks (50 ms, random phase), the solver driven exactly like the feature does (update on every container
 * packet, tick + click on every client tick), clicks travelling back with latency, optional Left-click prediction.
 */
class ExperimentSimulation(
    private val server: SimServer,
    private val config: SimConfig,
    private val solverFactory: (ExperimentHost, attachedAtStart: Boolean) -> ExperimentSolver
) {
    private val rng = Random(config.seed)
    private val client = SimBoard()
    private var cursor = SimItem.EMPTY
    private val log = ArrayList<String>()
    private val clicks = ArrayList<SimClick>()
    private val lastSent = Array(54) { SimItem.EMPTY }
    private var driver: ExperimentDriver? = null
    private var stoppedAt: Int? = null
    private var now = 0L
    private var lastDownArrival = 0L
    private var lastUpArrival = 0L
    private var seq = 0L

    private sealed class Event(val at: Long, val order: Long) {
        class ServerTick(at: Long, order: Long, val tick: Int): Event(at, order)
        class ClientTick(at: Long, order: Long): Event(at, order)
        class Packet(at: Long, order: Long, val slots: Map<Int, SimItem>): Event(at, order)
        class Click(at: Long, order: Long, val slot: Int): Event(at, order)
    }

    private val queue = PriorityQueue<Event>(compareBy<Event>({ it.at }, { it.order }))

    // the simulated human
    private var timerSeenAt = - 1L
    private var solverClickedInTimer = false
    private var humanIndex = 0
    private var humanNextAt = 0L
    private var humanClicks = 0
    private var blockedHumanClicks = 0
    private var remembers = 0
    private var lastClientStatus = ""
    private val pendingClicks = ArrayList<Int>()

    private val host = object: ExperimentHost {
        override fun debug(msg: String) {
            log += "[${now}ms] $msg"
        }

        override fun reachedTarget(game: ExperimentGame, size: Int) = size > config.target

        override fun onTargetReached(size: Int) {
            log += "[${now}ms] target reached at $size, closing"
            stoppedAt = size
        }
    }

    private fun latency() = rng.nextLong(config.latencyMs.first, config.latencyMs.last + 1)

    private fun frozenUntil(at: Long): Long {
        for ((start, duration) in config.freezes) if (at >= start && at < start + duration) return start + duration
        return at
    }

    fun run(): SimResult {
        queue += Event.ServerTick(0, seq ++, 0)
        val clientPhase = rng.nextLong(0, 50)
        queue += Event.ClientTick(clientPhase, seq ++)

        while (queue.isNotEmpty()) {
            val event = queue.poll()
            now = event.at
            if (now > config.maxMs || stoppedAt != null) break
            when (event) {
                is Event.ServerTick -> {
                    serverTick(event.tick)
                    if (server.finished) {
                        log += "[${now}ms] SERVER: finished"
                        break
                    }
                    if (server.over) {
                        // let the solver see nothing more: the experiment is over
                        log += "[${now}ms] SERVER: ${server.failure}"
                        break
                    }
                    queue += Event.ServerTick(now + 50, seq ++, event.tick + 1)
                }

                is Event.ClientTick -> {
                    clientTick()
                    queue += Event.ClientTick(now + 50, seq ++)
                }

                is Event.Packet -> {
                    // a frozen client handles the packet once it wakes up, still in order
                    val wake = frozenUntil(now)
                    if (wake > now) {
                        queue += Event.Packet(wake, event.order, event.slots)
                        continue
                    }
                    for ((slot, item) in event.slots) {
                        if (slot == CURSOR) cursor = item else client.slots[slot] = item
                    }
                    if (client.status != lastClientStatus) {
                        if (client.status == "Remember the pattern!") remembers ++
                        lastClientStatus = client.status
                    }
                    driver?.update(client, now)
                }

                is Event.Click -> pendingClicks += event.slot
            }
        }
        return SimResult(server.roundsCompleted, server.failure, stoppedAt, clicks, server.ignoredClicks, humanClicks, blockedHumanClicks, log)
    }

    private fun serverTick(tick: Int) {
        val packets = ArrayList<Map<Int, SimItem>>()
        if (pendingClicks.isNotEmpty()) {
            for (slot in pendingClicks) {
                server.onClick(tick, slot)
                log += "[${now}ms] SERVER tick $tick: click $slot -> rounds ${server.roundsCompleted}${server.failure?.let { ", FAIL: $it" } ?: ""}"
                if (server.over) return
            }
            pendingClicks.clear()
            // A cancelled inventory click is answered with the whole window (and the cursor) right away.
            if (config.resyncOnClick) packets += snapshot()
        }

        server.onTick(tick)
        if (server.over) return
        if (tick == 0) packets += snapshot() // the window opens with all its items
        else {
            val changed = (0 until 54).filter { server.board[it] != lastSent[it] }.toMutableList()
            when (config.packetOrder) {
                PacketOrder.ASCENDING -> Unit
                PacketOrder.DESCENDING -> changed.reverse()
                PacketOrder.SHUFFLED -> changed.shuffle(rng)
            }
            for (slot in changed) packets += mapOf(slot to server.board[slot])
        }
        for (slot in 0 until 54) lastSent[slot] = server.board[slot]

        for (packet in packets) {
            val arrival = maxOf(lastDownArrival, now + latency())
            lastDownArrival = arrival
            queue += Event.Packet(arrival, seq ++, packet)
        }
    }

    private fun snapshot(): Map<Int, SimItem> {
        for (slot in 0 until 54) lastSent[slot] = server.board[slot]
        return (0 until 54).associateWith { server.board[it] } + (CURSOR to SimItem.EMPTY)
    }

    private fun clientTick() {
        if (frozenUntil(now) > now) return
        val lazy = config.attachAtMs > 0 || config.attachAtRemember > 0
        val attach = if (config.attachAtRemember > 0) remembers >= config.attachAtRemember else now >= config.attachAtMs
        if (driver == null && attach) {
            val solver = solverFactory(host, ! lazy)
            driver = ExperimentDriver(solver, host, { config.clickDelay + if (config.delayVariance > 0) rng.nextLong(0, config.delayVariance + 1) else 0L }, now, config.clickDelay)
            log += "[${now}ms] solver attached (start=${! lazy})"
        }

        val timer = client.status.startsWith("Timer:")
        if (! timer) {
            timerSeenAt = - 1
            solverClickedInTimer = false
            humanIndex = 0
        }
        else if (timerSeenAt < 0) timerSeenAt = now

        val slot = driver?.tick(client, now)
        if (slot != null) {
            if (timer) solverClickedInTimer = true
            clicks += SimClick(now, slot)
            log += "[${now}ms] CLIENT click $slot (${client.slots[slot].name}${if (client.slots[slot].lit) ", lit" else ""})"
            sendClick(slot, config.clickType)
        }
        else if (config.manualPlayer && timer && ! solverClickedInTimer && now - timerSeenAt >= 1500 && now >= humanNextAt) {
            val human = server.expectedSlot(humanIndex) ?: return
            humanNextAt = now + 400
            val solver = driver?.solver
            if (solver != null && ! solver.stopped && solver.blocksInput) {
                blockedHumanClicks ++
                log += "[${now}ms] HUMAN click $human blocked by the solver"
                return
            }
            humanIndex ++
            humanClicks ++
            log += "[${now}ms] HUMAN click $human"
            sendClick(human, ClickType.LEFT)
        }
    }

    private fun sendClick(slot: Int, type: ClickType) {
        if (type == ClickType.LEFT) {
            // Vanilla PICKUP prediction: the item jumps onto the cursor (or the carried one is put down) until the
            // server's resync arrives. A middle click (CLONE) changes nothing client-side for a survival player.
            val item = client.slots[slot]
            client.slots[slot] = cursor
            cursor = item
        }
        val arrival = maxOf(lastUpArrival, now + latency())
        lastUpArrival = arrival
        queue += Event.Click(arrival, seq ++, slot)
    }
}
