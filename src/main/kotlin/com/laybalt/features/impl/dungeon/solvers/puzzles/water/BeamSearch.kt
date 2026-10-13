package com.laybalt.features.impl.dungeon.solvers.puzzles.water

import kotlin.math.max
import kotlin.math.min

/**
 * Timed continuation search: from the board as it is, which levers to flip and when, so that the water still reaches every
 * closed door once and no open one. A beam search over decision points every [SearchBudget.decisionStep] ticks (the
 * water's own rhythm); at each point a line of play either waits or clicks one lever the player can reach in time.
 *
 * Lines that wet a door that had to stay dry (or one twice) are dropped at once, and so are lines that can no longer do
 * anything. The rest are ranked by doors reached, then by how close the water is to the doors still missing (a
 * breadth-first distance over the board, ignoring the toggle blocks, so it never overestimates), then by fewer clicks.
 * Equal states are kept once ([BoardState.key]). Once a layer has a solved line the search goes on for
 * [SearchBudget.collectLayers] more layers (later clicks are often the ones with room for error) and returns the solved
 * lines, soonest first; the [Planner] then checks each for robustness.
 *
 * Deterministic: no randomness, no hash-ordered iteration; budgets count nodes (the wall clock is only a guard).
 */
object BeamSearch {
    private class Node(
        val state: BoardState,
        val steps: List<PlanStep>,
        val lastLever: WaterLever?,
        val lastClick: Int,
        val reached: Int,
        val counts: IntArray,
        var h: Int = 0,
        var solvedAt: Int = - 1,
    )

    fun search(req: PlanRequest, seeds: List<List<PlanStep>>, bestSoFar: Int, cancel: CancelToken): List<List<PlanStep>> {
        val budget = req.budget
        val layout = req.layout
        val dist = distances(layout)
        val lowerBound = req.now + req.rules.tickDelay * lowerBoundSteps(req, dist)
        // Not worth searching: a cheap plan is already within reach of the best possible.
        if (bestSoFar <= lowerBound + 40) return emptyList()

        val started = System.nanoTime()
        val pool = ArrayDeque<BoardState>()
        var nodes = 0
        val first = req.now + 1
        var layer = listOf(Node(req.state.copy(), emptyList(), null, Int.MIN_VALUE / 2, 0, req.initialCounts()))
        layer.forEach { it.h = heuristic(req, it, dist) }
        val solved = ArrayList<Node>()
        var solvedLayer = - 1
        val horizon = req.now + budget.horizon
        var t = first
        while (layer.isNotEmpty() && t < horizon) {
            cancel.check()
            if (budget.wallClockMs > 0 && (System.nanoTime() - started) / 1_000_000 > budget.wallClockMs) break
            if (solvedLayer >= 0 && t > solvedLayer + budget.collectLayers * budget.decisionStep) break
            if (solved.size >= budget.maxSolutions) break
            val next = ArrayList<Node>()
            val seen = HashSet<Long>()
            for (node in layer) {
                for (lever in options(req, node, t)) {
                    if (nodes >= budget.maxNodes) break
                    nodes ++
                    val child = expand(req, node, lever, t, t + budget.decisionStep, pool) ?: continue
                    if (child.solvedAt >= 0) {
                        solved += child
                        if (solvedLayer < 0) solvedLayer = t
                        continue
                    }
                    if (! seen.add(child.state.key() xor child.steps.size.toLong())) {
                        pool.addLast(child.state)
                        continue
                    }
                    child.h = heuristic(req, child, dist)
                    next += child
                }
            }
            // The previous layer is done with; states that do not make the next beam go back to the pool.
            layer.forEach { pool.addLast(it.state) }
            if (nodes >= budget.maxNodes) break
            val ranked = next.sortedWith(compareBy<Node>({ - Integer.bitCount(it.reached) }, { it.h }, { it.steps.size }, { stepsKey(it.steps) }))
            layer = ranked.take(budget.beamWidth)
            for (i in budget.beamWidth until ranked.size) pool.addLast(ranked[i].state)
            t += budget.decisionStep
        }
        return solved.sortedWith(compareBy<Node>({ it.solvedAt }, { it.steps.size }, { stepsKey(it.steps) }))
            .map { it.steps }
            .distinct()
            .filter { it !in seeds }
    }

    /** Levers a line may click at [t] (null = wait). */
    private fun options(req: PlanRequest, node: Node, t: Int): List<WaterLever?> {
        val out = ArrayList<WaterLever?>(8)
        out += null
        if (node.steps.size >= req.budget.maxFlips) return out
        for (lever in WaterLever.entries) {
            val earliest = if (node.lastLever == null) req.human.firstClick(req.startAt, lever) else node.lastClick + req.human.gap(node.lastLever, lever)
            if (t >= earliest) out += lever
        }
        return out
    }

    private fun expand(req: PlanRequest, node: Node, lever: WaterLever?, t: Int, until: Int, pool: ArrayDeque<BoardState>): Node? {
        val s = pool.removeLastOrNull() ?: BoardState(node.state.layout, node.state.rules)
        node.state.copyInto(s)
        var steps = node.steps
        var lastLever = node.lastLever
        var lastClick = node.lastClick
        if (lever != null) {
            val at = max(req.human.observed(lever, t), s.tick + 1)
            if (lever == WaterLever.WATER) {
                var on = s.sourceOn
                for (i in s.nextExternal until s.externals.size) (s.externals[i] as? External.Source)?.let { on = it.on }
                s.addExternal(External.Source(at, ! on))
            }
            else s.addExternal(External.Flip(at, lever.ordinal))
            steps = steps + PlanStep(lever, t)
            lastLever = lever
            lastClick = t
        }
        val counts = node.counts.copyOf()
        var reached = node.reached
        var wrong = false
        var solvedAt = - 1
        val listener = object : SimListener {
            override fun arrival(outlet: Int, tick: Int) {
                val door = req.outletToDoor.getOrNull(outlet) ?: return
                counts[door] ++
                if (req.target and (1 shl door) == 0 || counts[door] > 1) wrong = true
                else {
                    reached = reached or (1 shl door)
                    if (reached == req.target) solvedAt = tick
                }
            }
        }
        while (s.tick < until && ! wrong && solvedAt < 0) WaterSim.step(s, listener)
        val dead = solvedAt < 0 && s.settled && s.waterCount() == 0 && steps.size >= req.budget.maxFlips
        if (wrong || dead) {
            pool.addLast(s)
            return null
        }
        return Node(s, steps, lastLever, lastClick, reached, counts, solvedAt = solvedAt)
    }

    /** Sum over the closed doors not reached yet of the distance from the nearest water to their outlet. */
    private fun heuristic(req: PlanRequest, node: Node, dist: Array<IntArray>): Int {
        val s = node.state
        var total = 0
        for (o in req.outletToDoor.indices) {
            val door = req.outletToDoor[o]
            if (req.target and (1 shl door) == 0 || node.reached and (1 shl door) != 0) continue
            var best = UNREACHABLE
            val d = dist[o]
            for (c in 0 until s.layout.size) if (s.level[c] >= 0 && d[c] < best) best = d[c]
            if (best == UNREACHABLE && s.layout.source >= 0) best = d[s.layout.source] + 8
            total += min(best, UNREACHABLE)
        }
        return total
    }

    private fun lowerBoundSteps(req: PlanRequest, dist: Array<IntArray>): Int {
        var worst = 0
        val s = req.state
        for (o in req.outletToDoor.indices) {
            if (req.target and (1 shl req.outletToDoor[o]) == 0) continue
            var best = UNREACHABLE
            for (c in 0 until s.layout.size) if (s.level[c] >= 0 && dist[o][c] < best) best = dist[o][c]
            if (best == UNREACHABLE && s.layout.source >= 0) best = dist[o][s.layout.source]
            worst = max(worst, min(best, 200))
        }
        return worst
    }

    private const val UNREACHABLE = 10_000

    private val distanceCache = java.util.WeakHashMap<BoardLayout, Array<IntArray>>()

    /** For every outlet, the number of steps from each cell (walls excepted; toggles count as open) to it. */
    fun distances(layout: BoardLayout): Array<IntArray> = synchronized(distanceCache) {
        distanceCache.getOrPut(layout) {
            Array(layout.outlets.size) { o ->
                val d = IntArray(layout.size) { UNREACHABLE }
                val queue = IntArray(layout.size)
                var head = 0
                var tail = 0
                d[layout.outlets[o]] = 0
                queue[tail ++] = layout.outlets[o]
                while (head < tail) {
                    val c = queue[head ++]
                    for (dir in 0 until 4) {
                        val n = layout.neighbour(c, dir)
                        if (n < 0 || layout.wall[n] || d[n] != UNREACHABLE) continue
                        d[n] = d[c] + 1
                        queue[tail ++] = n
                    }
                }
                d
            }
        }
    }

    private fun stepsKey(steps: List<PlanStep>) = steps.joinToString(",") { "${it.lever.ordinal}:${it.clickTick}" }
}
