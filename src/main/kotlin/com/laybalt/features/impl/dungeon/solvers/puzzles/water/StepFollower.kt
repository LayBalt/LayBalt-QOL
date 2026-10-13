package com.laybalt.features.impl.dungeon.solvers.puzzles.water

import com.laybalt.features.impl.dungeon.solvers.puzzles.water.PlanFollower.Divergence
import com.laybalt.features.impl.dungeon.solvers.puzzles.water.PlanFollower.Kind

/**
 * Follows the clicks of a computed plan ([ContinuationPlan.steps], click frame) with the same rules as [PlanFollower]:
 * a flip matches its lever's next planned click unless it comes more than the tolerance early; a planned click more
 * than the tolerance overdue is missed; a lever with nothing left to click is an extra flip, one the plan never uses a
 * wrong lever.
 */
class StepFollower(steps: List<PlanStep>, var toleranceTicks: Int = 20) {
    private val steps = steps.sortedBy { it.clickTick }
    private val done = BooleanArray(this.steps.size)

    fun remaining(): List<PlanStep> = steps.filterIndexed { i, _ -> ! done[i] }

    /** How many clicks of [lever] are done (for label stacking). */
    fun consumed(lever: WaterLever) = steps.indices.count { done[it] && steps[it].lever == lever }

    val finished get() = done.all { it }

    val lastClick get() = steps.maxOfOrNull { it.clickTick }

    fun onFlip(lever: WaterLever, tick: Int, source: FlipSource = FlipSource.OWN): Divergence? {
        val next = steps.indices.firstOrNull { ! done[it] && steps[it].lever == lever }
        if (next != null && steps[next].clickTick - tick <= toleranceTicks) {
            done[next] = true
            return null
        }
        return when {
            steps.none { it.lever == lever } -> Divergence(lever, Kind.WRONG_LEVER, tick, source = source)
            next == null -> Divergence(lever, Kind.EXTRA, tick, source = source)
            else -> Divergence(lever, Kind.EARLY, tick, steps[next].clickTick - tick, source)
        }
    }

    fun onTick(now: Int): Divergence? {
        for (i in steps.indices) {
            if (done[i]) continue
            val late = now - steps[i].clickTick
            if (late > toleranceTicks) return Divergence(steps[i].lever, Kind.MISSED, now, late)
        }
        return null
    }
}
