package fishing

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.laybalt.features.impl.fishing.BehaviorLearner
import com.laybalt.features.impl.fishing.BehaviorProfile
import com.laybalt.features.impl.fishing.BehaviorProfile.Habit
import com.laybalt.features.impl.fishing.BehaviorProfile.Motor
import com.laybalt.features.impl.fishing.BehaviorProfile.Profile
import com.laybalt.features.impl.fishing.ClickStyle
import com.laybalt.features.impl.fishing.FishingRecorder
import com.laybalt.features.impl.fishing.GlanceMeasure
import com.laybalt.features.impl.fishing.HumanAim
import com.laybalt.features.impl.fishing.MotorParams
import com.laybalt.features.impl.fishing.RestMotion
import com.laybalt.features.impl.fishing.Humanize
import com.laybalt.features.impl.fishing.KillRules.AttackMode
import com.laybalt.features.impl.fishing.KillRules.KillAction
import com.laybalt.features.impl.fishing.ViewMotion
import com.laybalt.features.impl.fishing.ViewPolicy
import com.laybalt.features.impl.fishing.WeaponPlanner
import java.io.File
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.*

/** Learns from a synthetic recording in the format [com.laybalt.features.impl.fishing.FishingRecorder] writes. */
class BehaviorLearnerTest {
    private val lines = mutableListOf<String>()

    private fun ev(type: String, t: Long, build: JsonObject.() -> Unit = {}) {
        val o = JsonObject()
        o.addProperty("e", type)
        o.addProperty("t", t)
        o.build()
        lines += o.toString()
    }

    /** Player at (0, 64, 0); creature [id] standing at (10, 64, 0) unless moved: looking at it there is yaw -90, pitch ~3. */
    private fun rot(t: Long, yaw: Float, pitch: Float, id: Int, cx: Double = 10.0, cy: Double = 64.0, cz: Double = 0.0) = ev("rot", t) {
        addProperty("yaw", yaw)
        addProperty("pitch", pitch)
        addProperty("x", 0.0)
        addProperty("y", 64.0)
        addProperty("z", 0.0)
        addProperty("keys", 0)
        add("c", JsonArray().apply { add(JsonArray().apply { add(id); add(cx); add(cy); add(cz); add(1.8) }) })
    }

    private fun spawn(t: Long, id: Int, name: String) = ev("spawn", t) {
        addProperty("id", id)
        addProperty("name", name)
        addProperty("own", true)
    }

    private fun learn(skipRecorderBuild: Boolean = false): Profile {
        val file = File.createTempFile("lbq-rec", ".jsonl")
        file.deleteOnExit()
        file.writeText(lines.joinToString("\n"))
        return BehaviorLearner.learn(listOf(file), skipRecorderBuild)
    }

    /** Your right click with the rod (a reel or a cast). */
    private fun rodClick(t: Long) = ev("click", t) {
        addProperty("b", "R")
        addProperty("auto", false)
        addProperty("item", "ROD_OF_THE_SEA")
        addProperty("rod", true)
    }

    private fun recordRodFight() {
        ev("session", 0) { addProperty("v", 1) }
        rodClick(150)
        ev("cast", 200) { addProperty("auto", false) }
        ev("bite", 1000)
        rodClick(1180)
        // the catch line arrives from chat before the tick that notices the reel
        ev("catch", 1240) { addProperty("name", "Sea Walker") }
        ev("reel", 1250) { addProperty("auto", false) }
        spawn(2000, 5, "Sea Walker")
        // still for 300 ms, then a turn with a small overshoot, then still on the creature
        var t = 2000L
        repeat(7) { rot(t, 0f, 0f, 5); t += 50 }
        val curve = listOf(0.1f, 0.35f, 0.65f, 0.88f, 1.02f, 1.0f, 1.0f)
        for (k in curve) { rot(t, - 90f * k, 3.09f * k, 5); t += 50 }
        for (i in 0 until 5) ev("click", 2700L + i * 150) {
            addProperty("b", "L")
            addProperty("auto", false)
            addProperty("item", "ROD_OF_THE_SEA")
            addProperty("rod", true)
            addProperty("target", 5)
            addProperty("dist", 2.5)
            addProperty("ay", 0.6)
        }
        ev("death", 3400) { addProperty("id", 5) }
        rodClick(3750)
        ev("cast", 3800) { addProperty("auto", false) }
    }

    @Test
    fun `one of yours seen again after the tracker stopped starts no fight at that line`() {
        recordRodFight()
        ev("end", 4000)
        val i = lines.indexOfFirst { it.contains("\"e\":\"spawn\"") }
        assertTrue(i >= 0)
        lines[i] = lines[i].replace("\"own\":true", "\"own\":true,\"kept\":true")
        val p = learn()
        assertTrue(p.spawnReactionMs.isEmpty(), "a re-sighting is no spawn to react to: ${p.spawnReactionMs}")
        assertTrue(p.firstHitMs.isEmpty(), "nor a first hit after one: ${p.firstHitMs}")
    }

    @Test
    fun `rod fight gives timings, a turn and a rod suggestion`() {
        recordRodFight()
        ev("end", 4000)
        val p = learn()

        assertEquals(1, p.sessions)
        assertEquals(listOf(180), p.biteReactionMs, "from the bite to your reel click, not to the server's reel")
        assertEquals(listOf(300), p.spawnReactionMs)
        assertEquals(listOf(700), p.firstHitMs)
        assertEquals(listOf(150, 150, 150, 150), p.clickLeftMs)
        assertEquals(listOf(150, 150, 150, 150), p.clickLeftRodMs)
        assertTrue(p.clickLeftWeaponMs.isEmpty())
        assertEquals(listOf(350), p.afterKillMs, "from the death to your own recast click")
        assertTrue(p.castDelayMs.isEmpty(), "a reel with a catch is not a plain recast")
        assertTrue(p.catchRecastMs.isEmpty(), "the recast came after the fight")
        assertTrue(p.bobberMissMs.isEmpty(), "no recast between the catch and the first hit")
        assertEquals(1, p.kills)

        // ends on the first still tick after 80 % of the way
        val turn = p.turns.single()
        assertTrue(abs(turn.angle - 90f) < 1f, "angle ${turn.angle}")
        assertEquals(300, turn.ms)
        assertEquals(6, turn.progress.size)
        assertTrue(abs(turn.progress.max() - 1.02f) < 0.02f, "overshoot is kept: ${turn.progress.toList()}")
        assertTrue(abs(turn.progress.last() - 1f) < 0.01f, "${turn.progress.toList()}")

        val habit = p.habits.getValue("Sea Walker")
        assertEquals(1, habit.killed)
        assertEquals(0, habit.right, "rod casts and reels are not attacks")
        assertTrue(habit.weapons.isEmpty(), "a rod is never suggested as the weapon to equip")
        assertTrue(p.clickRightMs.isEmpty())
        val rule = assertNotNull(habit.suggestion())
        assertEquals(KillAction.ROD, rule.action)
        assertEquals(AttackMode.AUTO, rule.attack, "a rod always hits")
    }

    @Test
    fun `a plain reel gives the recast delay, a reel with a catch the post-catch recast`() {
        ev("session", 0) { addProperty("v", 1) }
        ev("bite", 10_000)
        rodClick(10_200)
        ev("reel", 10_260) { addProperty("auto", false) }
        rodClick(10_600)
        ev("cast", 10_650) { addProperty("auto", false) }
        ev("bite", 20_000)
        rodClick(20_300)
        ev("reel", 20_350) { addProperty("auto", false) }
        ev("catch", 20_400) { addProperty("name", "Squid") }
        rodClick(20_900)
        val p = learn()
        assertEquals(listOf(200, 300), p.biteReactionMs)
        assertEquals(listOf(400), p.castDelayMs, "from your reel click to your recast click")
        assertEquals(listOf(600), p.catchRecastMs)
        assertEquals(1, p.recasts)
        assertEquals(0, p.holdRecasts)
    }

    /** Your held use key: the repeat every 4 ticks. */
    private fun holdClick(t: Long) = ev("click", t) {
        addProperty("b", "R")
        addProperty("hold", true)
        addProperty("auto", false)
        addProperty("item", "ROD_OF_THE_SEA")
        addProperty("rod", true)
    }

    /** A bite, your reel click 200 ms later and the catch line; the creature's spawn follows at [t] + 330. */
    private fun catchAt(t: Long, name: String) {
        ev("bite", t)
        rodClick(t + 200)
        ev("catch", t + 320) { addProperty("name", name) }
        ev("reel", t + 330) { addProperty("auto", false) }
    }

    @Test
    fun `a hold repeat after a catch reel is the post-catch recast, one after a swap is not`() {
        ev("session", 0) { addProperty("v", 1) }
        catchAt(10_000, "Squid")
        holdClick(10_400)
        ev("cast", 10_550) { addProperty("auto", false) }
        catchAt(20_000, "Squid")
        // a fight first: the recast that follows is not your rhythm
        ev("slot", 20_500) {
            addProperty("slot", 1)
            addProperty("item", "DARK_CLAYMORE")
            addProperty("rod", false)
        }
        holdClick(21_000)
        val p = learn()
        assertEquals(listOf(200), p.catchRecastMs, "from your reel click to the held repeat")
        assertEquals(1, p.recasts)
        assertEquals(1, p.holdRecasts)
        assertTrue(p.castDelayMs.isEmpty())
    }

    @Test
    fun `the planned first stroke follows your first share's p90, by the plan's share the motor measures`() {
        assertEquals(Motor().firstMu, BehaviorLearner.firstMuOf(1.09), 1e-12, "the reference's p90: its own plan")
        // near the reference the motor measures 0.54 of a plan's shift at the p90: a p90 0.0054 higher plans 0.01 more
        assertEquals(Motor().firstMu + 0.01, BehaviorLearner.firstMuOf(1.09 + 0.0054), 2e-4)
        assertEquals(Motor().firstMu - 0.01, BehaviorLearner.firstMuOf(1.09 - 0.0054), 2e-4)
    }

    @Test
    fun `a first share far off the reference's plans close to a bound the motor takes, never past it`() {
        // a plan the motor does not take falls back to its default: a careful hand (p90 0.87) planned past a careless one
        var prev = Double.NEGATIVE_INFINITY
        for (i in 0 .. 160) {
            val p90 = 0.4 + i * 0.01
            val mu = BehaviorLearner.firstMuOf(p90)
            assertTrue(mu > prev, "rising at $p90: $mu after $prev")
            assertEquals(mu, HumanAim.paramsOf(Motor(firstMu = mu)).firstMean, 1e-12, "the motor takes the plan of p90 $p90")
            prev = mu
        }
        assertTrue(BehaviorLearner.firstMuOf(0.0) > 0.35 && BehaviorLearner.firstMuOf(5.0) < 1.2, "within the motor's range at any p90")
    }

    @Test
    fun `a way back's first stroke follows your ways back's median, rising and bounded away from both ends of its draw`() {
        // the median of all the reference's ways back plans the motor's reference stroke
        val plan = 0.87
        assertEquals(plan, BehaviorLearner.backFirstMuOf(0.727), 1e-12, "the reference's median: its own plan")
        // near the reference the motor measures a quarter of a plan's shift at the median
        assertEquals(plan + 0.01, BehaviorLearner.backFirstMuOf(0.727 + 0.0025), 2e-4)
        assertEquals(plan - 0.01, BehaviorLearner.backFirstMuOf(0.727 - 0.0025), 2e-4)
        var prev = Double.NEGATIVE_INFINITY
        // any median a way back's first stroke can have: rising all the way (past the goal it saturates at the bound)
        for (i in 0 .. 100) {
            val p50 = i * 0.01
            val mu = BehaviorLearner.backFirstMuOf(p50)
            assertTrue(mu > prev, "rising at $p50: $mu after $prev")
            assertTrue(mu > BehaviorLearner.BACK_MU_MIN && mu < BehaviorLearner.BACK_MU_MAX, "within its bounds at $p50: $mu")
            // both hands take the plan as learned: a careful hand's first strokes no longer crowd at their 0.35 floor
            assertEquals(mu, HumanAim.returnParamsOf(Motor(returnFirstMu = mu)).firstMean, 1e-12, "the motor takes the plan of p50 $p50")
            assertEquals(mu, HumanAim.turnBackParamsOf(Motor(backFirstMu = mu)).firstMean, 1e-12, "the turn back's too")
            prev = mu
        }
        for (p50 in listOf(- 5.0, 1.5, 5.0)) {
            val mu = BehaviorLearner.backFirstMuOf(p50)
            assertTrue(mu in BehaviorLearner.BACK_MU_MIN .. BehaviorLearner.BACK_MU_MAX, "never past a bound at $p50: $mu")
            assertEquals(mu, HumanAim.returnParamsOf(Motor(returnFirstMu = mu)).firstMean, 1e-12)
        }
        assertTrue(BehaviorLearner.BACK_MU_MIN - MotorParams().firstMin >= 0.1, "a careful hand's mean stays clear of the stroke's floor")
        assertTrue(1.0 - BehaviorLearner.BACK_MU_MAX >= 0.05, "and of the goal")
    }

    @Test
    fun `your ways back are the turn backs, returns and turns back to the fight of 20 degrees or more in two strokes`() {
        fun move(amp: Double, vararg strokes: Double) = ViewMotion.Move(
            0, 1, false, 0L, 500L, 500.0, amp, amp, 300.0, 0.5, 0.0, 0.0, strokes.size, amp, 0.0, 0.0, 0.0, amp, 0.0, Double.NaN, amp, amp, 300.0, 0.1, 0.1,
            strokes.map { ViewMotion.Stroke(it, 200.0, 300.0) }, null,
        )
        fun measured(kind: GlanceMeasure.Kind, m: ViewMotion.Move) = GlanceMeasure.Measured(m, kind, null, true)
        val moves = listOf(
            measured(GlanceMeasure.Kind.TURN_BACK, move(40.0, 30.0, 10.0)),
            measured(GlanceMeasure.Kind.RETURN, move(50.0, 20.0, 30.0)),
            measured(GlanceMeasure.Kind.BACK_TO_FIGHT, move(25.0, 25.0, 1.0)),
            // a turn toward a creature, a look, a small or a one- or three-stroke way back: none of them
            measured(GlanceMeasure.Kind.AIM, move(40.0, 30.0, 10.0)),
            measured(GlanceMeasure.Kind.LOOK, move(40.0, 30.0, 10.0)),
            measured(GlanceMeasure.Kind.RETURN, move(19.0, 10.0, 9.0)),
            measured(GlanceMeasure.Kind.RETURN, move(40.0, 40.0)),
            measured(GlanceMeasure.Kind.TURN_BACK, move(40.0, 20.0, 15.0, 5.0)),
        )
        assertEquals(listOf(0.75, 0.4, 1.0), BehaviorLearner.WaysBack.firstShares(moves))
        // the turn backs' hand and the hand back from a look are learned apart
        assertEquals(listOf(0.75), BehaviorLearner.WaysBack.firstShares(moves, BehaviorLearner.WaysBack.TURN_BACKS))
        assertEquals(listOf(0.4, 1.0), BehaviorLearner.WaysBack.firstShares(moves, BehaviorLearner.WaysBack.FROM_LOOKS))
    }

    @Test
    fun `each kind of way back is estimated with the others by how far they lie apart against their own spread`() {
        val wb = BehaviorLearner.WaysBack
        // nothing learned: the reference's
        assertEquals(listOf(0.727, 0.727), wb.estimates(listOf(emptyList(), emptyList())))
        assertEquals(wb.plans(emptyList(), emptyList()), BehaviorLearner.backFirstMuOf(0.727) to BehaviorLearner.backFirstMuOf(0.727))
        // one kind only: both are all of yours, drawn towards the reference by the ways back each was measured from
        val one = listOf(0.6, 0.62, 0.64)
        val prior = (3 * 0.62 + wb.REF_BACK_N * 0.727) / (3 + wb.REF_BACK_N)
        wb.estimates(listOf(one, emptyList())).forEach { assertEquals(prior, it, 1e-12) }
        // two kinds lying far apart, each tight: each keeps nearly its own median
        val tightA = List(8) { 0.75 + 0.01 * (it - 3.5) }
        val tightB = List(5) { 0.50 + 0.01 * (it - 2) }
        val (a, b) = wb.estimates(listOf(tightA, tightB))
        assertTrue(abs(a - 0.75) < 0.02 && abs(b - 0.50) < 0.02, "$a / $b")
        // two kinds no further apart than their spread says chance puts them: one hand, the pooled one for both
        val wideA = listOf(0.3, 0.5, 0.7, 0.9, 0.62, 0.41)
        val wideB = listOf(0.35, 0.95, 0.55, 0.75, 0.6)
        val (wa, wbk) = wb.estimates(listOf(wideA, wideB))
        assertEquals(wa, wbk, 1e-12)
        // with more of a kind (its median spreads less) it keeps more of its own
        val looks = listOf(0.40, 0.45, 0.511, 0.60, 0.88)
        val back = listOf(0.70, 0.72, 0.74, 0.75, 0.752, 0.76, 0.80, 0.87)
        val few = wb.estimates(listOf(back, looks))[1]
        val many = wb.estimates(listOf(back, looks + looks + looks))[1]
        assertTrue(abs(many - 0.511) < abs(few - 0.511), "$few -> $many")
        // the reference's own (8 turn backs at 0.751, 5 back from a look at 0.511 with a wide spread): the turn backs keep
        // theirs, the ways back from a look most of theirs (a fixed 10 pseudo-ways back kept a third of it: 0.655)
        val (turnBacks, fromLooks) = wb.estimates(listOf(back, looks))
        assertTrue(abs(turnBacks - 0.751) < 0.01, "$turnBacks")
        assertTrue(fromLooks in 0.52 .. 0.62, "$fromLooks")
        assertEquals(BehaviorLearner.backFirstMuOf(turnBacks) to BehaviorLearner.backFirstMuOf(fromLooks), wb.plans(back, looks))
        // a single way back from a look far off the rest: its median spreads as wide as all of yours, so it moves the hand little
        val (_, single) = wb.estimates(listOf(back, listOf(0.2)))
        assertTrue(single > 0.45, "one way back from a look at 0.2: $single")
    }

    @Test
    fun `a phase's look rate is shrunk toward your pooled one with a quarter minute, never above the most`() {
        val g = BehaviorProfile.Glances
        // the wait after your kills: 11 looks in 2.9 minutes against 0.76 a minute over all phases
        assertEquals((11 + 0.25 * 0.76) / (2.92 + 0.25), g.rateOf(11, 2.92, 0.76), 1e-12)
        assertTrue(g.rateOf(11, 2.92, 0.76) > 3.5, "close to its own 3.8, not the pooled one")
        assertEquals(0.76, g.rateOf(0, 0.0, 0.76), 1e-12, "a phase never seen: the pooled rate")
        assertEquals(BehaviorProfile.Glances.MAX_RATE, g.rateOf(3, 0.1, 0.76), "three looks in 6 s: capped")
    }

    @Test
    fun `your rest nudges' sizes become the shares of each, larger ones left out`() {
        val rules = BehaviorLearner.RestRules
        assertEquals<List<Double>?>(listOf(0.5, 0.0, 0.25, 0.25), rules.shares(listOf(1, 1, 3, 4, 25, 0), 20), "25 steps is past the largest kept, 0 no nudge")
        assertNull(rules.shares(listOf(30, 0), 20))
        assertNull(rules.shares(emptyList(), 20))
    }

    @Test
    fun `your yaw nudges near the pole give the lognormal the motor draws them from`() {
        // drawn by the motor itself from a known lognormal, then fitted back
        val rng = Random(31)
        for ((p50, sigma) in listOf(1.03 to 2.24, 3.0 to 1.0)) {
            val drawn = List(4000) { RestMotion.poleSteps(MotorParams(restPoleP50 = p50, restPoleSigma = sigma), rng) }
            val (fp50, fs) = BehaviorLearner.RestRules.lognormal(drawn, MotorParams().restPoleMax)!!
            assertEquals(p50, fp50, 0.15 * p50, "median of $p50 / $sigma")
            assertEquals(sigma, fs, 0.12 * sigma, "ln-sd of $p50 / $sigma")
        }
        assertNull(BehaviorLearner.RestRules.lognormal(listOf(0, 400), 150))
    }

    @Test
    fun `recasting through catches needs enough quick recasts, mostly held`() {
        assertTrue(Profile(catchRecastMs = MutableList(8) { 200 }, recasts = 8, holdRecasts = 4).recastThroughCatches)
        assertFalse(Profile(catchRecastMs = MutableList(8) { 200 }, recasts = 8, holdRecasts = 3).recastThroughCatches, "mostly clicked")
        assertFalse(Profile(catchRecastMs = MutableList(8) { 450 }, recasts = 8, holdRecasts = 8).recastThroughCatches, "too slow")
        assertFalse(Profile(catchRecastMs = MutableList(7) { 200 }, recasts = 7, holdRecasts = 7).recastThroughCatches, "too few")
    }

    @Test
    fun `the recast's bobber killing a creature is a kill, not a death without your clicks`() {
        ev("session", 0) { addProperty("v", 1) }
        for (i in 0 until 3) {
            val t = 10_000L * (i + 1)
            catchAt(t - 330, "Frozen Steve")
            spawn(t, 10 + i, "Frozen Steve")
            holdClick(t + 1)
            ev("cast", t + 150) { addProperty("auto", false) }
            ev("death", t + 200) { addProperty("id", 10 + i) }
        }
        val p = learn()
        val habit = p.habits.getValue("Frozen Steve")
        assertEquals(3, habit.bobberKills)
        assertEquals(3, habit.killed)
        assertEquals(3, habit.fought)
        assertEquals(0, habit.diedUnfought)
        assertEquals(3, p.kills)
        assertEquals(listOf(199, 199, 199), p.bobberDeathMs)
        assertTrue(p.firstHitMs.isEmpty(), "a recast is not a first hit")
        assertEquals(0, habit.right, "the recast is not an attack")
        val rule = assertNotNull(habit.suggestion())
        assertEquals(KillAction.ROD, rule.action)
        assertEquals(AttackMode.RIGHT, rule.attack, "recast first")
    }

    @Test
    fun `a death long after the recast or right after your hit is not the bobber's`() {
        ev("session", 0) { addProperty("v", 1) }
        // dead 2 s after the recast, no click: something else killed it
        catchAt(9_670, "Frozen Steve")
        spawn(10_000, 1, "Frozen Steve")
        holdClick(10_001)
        ev("cast", 10_150) { addProperty("auto", false) }
        ev("death", 12_000) { addProperty("id", 1) }
        // your rod hit 150 ms before the death killed it, not the bobber
        catchAt(19_670, "Frog Man")
        spawn(20_000, 2, "Frog Man")
        holdClick(20_001)
        ev("cast", 20_150) { addProperty("auto", false) }
        rodHit(20_200, 2)
        ev("death", 20_350) { addProperty("id", 2) }
        val p = learn()
        val steve = p.habits.getValue("Frozen Steve")
        assertEquals(0, steve.bobberKills)
        assertEquals(1, steve.diedUnfought)
        val frog = p.habits.getValue("Frog Man")
        assertEquals(0, frog.bobberKills)
        assertEquals(1, frog.killed)
        assertEquals(1, frog.rodClicks)
        assertTrue(p.bobberDeathMs.isEmpty())
    }

    @Test
    fun `newer recordings mark the casting click and a creature the tracker lost`() {
        ev("session", 0) { addProperty("v", 1) }
        spawn(10_000, 1, "Frozen Steve")
        ev("click", 10_001) {
            addProperty("b", "R")
            addProperty("hold", true)
            addProperty("cast", true)
            addProperty("auto", false)
            addProperty("item", "ROD_OF_THE_SEA")
            addProperty("rod", true)
        }
        ev("death", 10_200) {
            addProperty("id", 1)
            addProperty("cause", "stand")
        }
        spawn(20_000, 2, "Frozen Steve")
        ev("death", 23_000) {
            addProperty("id", 2)
            addProperty("cause", "gone")
        }
        val p = learn()
        val habit = p.habits.getValue("Frozen Steve")
        assertEquals(1, habit.bobberKills, "the marked click cast without a cast event seen")
        assertEquals(1, habit.killed, "gone is not a kill")
        assertEquals(0, habit.diedUnfought)
    }

    @Test
    fun `a creature that survived the recast gives the time to your first hit after it`() {
        ev("session", 0) { addProperty("v", 1) }
        catchAt(9_670, "Inkling")
        spawn(10_000, 1, "Inkling")
        rodClick(10_000)
        ev("cast", 10_150) { addProperty("auto", false) }
        rodHit(10_650, 1)
        rodHit(11_150, 1)
        ev("death", 11_300) { addProperty("id", 1) }
        val p = learn()
        assertEquals(listOf(650), p.bobberMissMs)
        assertEquals(0, p.habits.getValue("Inkling").bobberKills)
        assertEquals(listOf(500), p.clickLeftRodMs)
    }

    @Test
    fun `a weapon's first swing after the recast is neither a missed bobber nor a first rod hit`() {
        ev("session", 0) { addProperty("v", 1) }
        catchAt(9_670, "Nutcracker")
        spawn(10_000, 1, "Nutcracker")
        rodClick(10_000)
        ev("cast", 10_150) { addProperty("auto", false) }
        ev("slot", 10_700) { addProperty("slot", 1); addProperty("item", "DARK_CLAYMORE") }
        for (t in listOf(10_900L, 11_100L, 11_300L)) ev("click", t) {
            addProperty("b", "L")
            addProperty("auto", false)
            addProperty("item", "DARK_CLAYMORE")
            addProperty("rod", false)
            addProperty("target", 1)
        }
        ev("death", 11_400) { addProperty("id", 1) }
        val p = learn()
        assertTrue(p.bobberMissMs.isEmpty(), "claymore swing learned as a missed bobber: ${p.bobberMissMs}")
        assertTrue(p.firstHitMs.isEmpty(), "claymore swing learned as a first rod hit: ${p.firstHitMs}")
        assertEquals(1, p.habits.getValue("Nutcracker").killed)
    }

    @Test
    fun `ownership decided after the spawn is followed`() {
        ev("session", 0) { addProperty("v", 1) }
        ev("spawn", 1000) {
            addProperty("id", 7)
            addProperty("name", "Sea Archer")
            addProperty("own", false)
        }
        ev("own", 1100) {
            addProperty("id", 7)
            addProperty("own", true)
        }
        spawn(2000, 8, "Sea Archer")
        ev("own", 3600) {
            addProperty("id", 8)
            addProperty("own", false)
        }
        ev("click", 4000) {
            addProperty("b", "L")
            addProperty("auto", false)
            addProperty("item", "ROD_OF_THE_SEA")
            addProperty("rod", true)
            addProperty("target", 7)
        }
        ev("death", 4500) { addProperty("id", 7) }
        val p = learn()
        val habit = p.habits.getValue("Sea Archer")
        assertEquals(1, habit.seen, "the revoked claim is not counted")
        assertEquals(1, habit.killed)
        assertEquals(listOf(3000), p.firstHitMs, "measured from the spawn, not from the late claim")
    }

    @Test
    fun `weapon fight after a loadout click suggests that weapon and loadout`() {
        recordRodFight()
        spawn(5000, 9, "Sea Leech")
        ev("menu", 5150) { addProperty("title", "(1/2) Loadouts") }
        ev("menuClick", 5500) {
            addProperty("title", "(1/2) Loadouts")
            addProperty("slot", 15)
        }
        ev("menuClose", 5750)
        for (i in 0 until 3) ev("click", 6200L + i * 320) {
            addProperty("b", "R")
            addProperty("auto", false)
            addProperty("item", "HYPERION")
            addProperty("rod", false)
            addProperty("near", 9)
            addProperty("err", 3.5)
        }
        ev("death", 7000) { addProperty("id", 9) }
        val p = learn()

        val rule = assertNotNull(p.habits.getValue("Sea Leech").suggestion())
        assertEquals(KillAction.WEAPON, rule.action)
        assertEquals("HYPERION", rule.weaponId)
        assertEquals("Hyperion", rule.weaponName)
        assertEquals(2, rule.loadout)
        assertEquals(AttackMode.RIGHT, rule.attack)
        assertEquals(listOf(350), p.menuOpenToClickMs)
        assertEquals(listOf(250), p.menuClickToCloseMs)
        assertEquals(listOf(320, 320), p.clickRightMs)
        assertEquals(2, p.kills)
    }

    @Test
    fun `automatic casts and clicks are not learned`() {
        ev("session", 0) { addProperty("v", 1) }
        ev("bite", 1000)
        // Auto Fishing's reel click right after the bite: not your reaction
        ev("click", 1150) {
            addProperty("b", "R")
            addProperty("auto", true)
            addProperty("item", "ROD_OF_THE_SEA")
            addProperty("rod", true)
        }
        ev("reel", 1200) { addProperty("auto", true) }
        ev("cast", 1500) { addProperty("auto", true) }
        // your later click is outside the bite window and has no reel click of yours before it
        rodClick(3500)
        spawn(2000, 3, "Squid")
        ev("click", 2400) {
            addProperty("b", "L")
            addProperty("auto", true)
            addProperty("target", 3)
        }
        val p = learn()
        assertTrue(p.biteReactionMs.isEmpty())
        assertTrue(p.castDelayMs.isEmpty())
        assertEquals(0, p.habits.getValue("Squid").fought)
    }

    @Test
    fun `clicks naming no creature belong to the only creature alive`() {
        ev("session", 0) { addProperty("v", 1) }
        spawn(30_000, 21, "Sea Leech")
        // an ability aimed at your feet, far off the creature
        for (t in listOf(30_500L, 30_900L)) ev("click", t) {
            addProperty("b", "R")
            addProperty("auto", false)
            addProperty("item", "HYPERION")
            addProperty("rod", false)
            addProperty("near", 21)
            addProperty("err", 80.0)
        }
        ev("death", 31_500) { addProperty("id", 21) }
        val p = learn()
        val habit = p.habits.getValue("Sea Leech")
        assertEquals(1, habit.fought)
        val rule = assertNotNull(habit.suggestion())
        assertEquals(KillAction.WEAPON, rule.action)
        assertEquals("HYPERION", rule.weaponId)
        assertEquals(AttackMode.RIGHT, rule.attack)
    }

    @Test
    fun `creatures that died without your clicks are not suggested as Ignore`() {
        ev("session", 0) { addProperty("v", 1) }
        for (i in 0 until 3) {
            spawn(10_000L * (i + 1), 40 + i, "Squid")
            ev("death", 10_000L * (i + 1) + 2000) { addProperty("id", 40 + i) }
        }
        for (i in 0 until 3) spawn(50_000L + i * 1000, 60 + i, "Sea Walker")
        // watched for more than a minute without a click
        ev("end", 120_000)
        val p = learn()
        assertNull(p.habits.getValue("Squid").suggestion(), "something killed them: unknown, not ignored")
        assertEquals(KillAction.IGNORE, p.habits.getValue("Sea Walker").suggestion()?.action, "left alone three times")
    }

    private fun rodHit(t: Long, id: Int) = ev("click", t) {
        addProperty("b", "L")
        addProperty("auto", false)
        addProperty("item", "ROD_OF_THE_SEA")
        addProperty("rod", true)
        addProperty("target", id)
    }

    @Test
    fun `after a kill only your own move back to fishing is learned`() {
        ev("session", 0) { addProperty("v", 1) }
        // A: Auto Fishing casts first after the kill -> no sample
        spawn(1000, 1, "Squid")
        rodHit(1500, 1)
        ev("death", 2000) { addProperty("id", 1) }
        ev("click", 2300) {
            addProperty("b", "R")
            addProperty("auto", true)
            addProperty("rod", true)
        }
        rodClick(2600)
        // B and C overlap: only the last death (C) gives a sample
        spawn(10_000, 2, "Squid")
        spawn(10_100, 3, "Squid")
        rodHit(10_500, 2)
        rodHit(10_700, 3)
        ev("death", 11_000) { addProperty("id", 2) }
        ev("death", 12_000) { addProperty("id", 3) }
        rodClick(12_400)
        val p = learn()
        assertEquals(listOf(400), p.afterKillMs)
    }

    @Test
    fun `held rod clicks and a new bite do not make reel or recast samples`() {
        ev("session", 0) { addProperty("v", 1) }
        ev("bite", 1000)
        ev("click", 1100) {
            addProperty("b", "R")
            addProperty("auto", false)
            addProperty("rod", true)
            addProperty("hold", true)
        }
        rodClick(1300)
        // a new bite before the recast: the old reel click is over
        ev("bite", 1500)
        rodClick(3600)
        val p = learn()
        assertEquals(listOf(300), p.biteReactionMs, "the held repeat is not your reaction")
        assertTrue(p.castDelayMs.isEmpty())
    }

    @Test
    fun `a creature left behind by a world change does not block later fights`() {
        ev("session", 0) { addProperty("v", 1) }
        spawn(1000, 1, "Squid")
        rodHit(1200, 1)
        ev("world", 2000)
        spawn(10_000, 2, "Sea Leech")
        for (t in listOf(10_500L, 10_900L)) ev("click", t) {
            addProperty("b", "R")
            addProperty("auto", false)
            addProperty("item", "HYPERION")
            addProperty("rod", false)
        }
        ev("death", 11_500) { addProperty("id", 2) }
        ev("slot", 11_800) {
            addProperty("slot", 0)
            addProperty("item", "ROD_OF_THE_SEA")
            addProperty("rod", true)
        }
        val p = learn()
        assertEquals(1, p.habits.getValue("Sea Leech").fought, "the clicks went to the only creature still around")
        assertEquals(listOf(300), p.afterKillMs)
        assertEquals(0, p.habits.getValue("Squid").killed, "a world change is not a kill")
    }

    @Test
    fun `no after-kill time when the bobber stayed out or a bite came first`() {
        ev("session", 0) { addProperty("v", 1) }
        rodClick(100)
        ev("cast", 150) { addProperty("auto", false) }
        spawn(1000, 1, "Squid")
        rodHit(1200, 1)
        ev("death", 1500) { addProperty("id", 1) }
        // the bobber was out the whole fight: reeling it in is not a way back
        rodClick(1900)
        ev("reel", 1950) { addProperty("auto", false) }
        spawn(5000, 2, "Squid")
        rodHit(5200, 2)
        ev("death", 5500) { addProperty("id", 2) }
        // the next bite came before your move
        ev("bite", 5800)
        rodClick(6000)
        val p = learn()
        assertTrue(p.afterKillMs.isEmpty(), "got ${p.afterKillMs}")
    }

    @Test
    fun `an area attack on a pile counts for every creature it killed`() {
        ev("session", 0) { addProperty("v", 1) }
        for (i in 1 .. 3) spawn(1000L * i, i, "Sea Walker")
        for (t in listOf(89_700L, 90_000L)) ev("click", t) {
            addProperty("b", "R")
            addProperty("auto", false)
            addProperty("item", "HYPERION")
            addProperty("rod", false)
        }
        for (i in 1 .. 3) ev("death", 90_200) { addProperty("id", i) }
        ev("end", 100_000)
        val p = learn()
        val habit = p.habits.getValue("Sea Walker")
        assertEquals(3, habit.fought)
        assertEquals(0, habit.leftAlone, "piled up on purpose, not left alone")
        assertEquals(KillAction.WEAPON, habit.suggestion()?.action)
    }

    /** Your left click on creature [id] with [item], hitting its box [dist] blocks away at height share [ay]. */
    private fun hit(t: Long, id: Int, dist: Double, ay: Double, item: String = "ROD_OF_THE_SEA") = ev("click", t) {
        addProperty("b", "L")
        addProperty("auto", false)
        addProperty("item", item)
        addProperty("rod", item == "ROD_OF_THE_SEA")
        addProperty("target", id)
        addProperty("dist", dist)
        addProperty("ay", ay)
    }

    @Test
    fun `a creature falling through a still view gives no turn and no reaction`() {
        ev("session", 0) { addProperty("v", 1) }
        spawn(1000, 1, "Frog Man")
        // looking down at the water while the creature drops from above your head into the crosshair
        for (i in 0 until 8) rot(1000L + i * 50, 30f, 88f, 1, cx = 0.15, cy = 67.0 - i * 0.4, cz = 0.1)
        hit(1400, 1, 0.0, 0.5)
        ev("death", 1600) { addProperty("id", 1) }
        val p = learn()
        assertTrue(p.turns.isEmpty())
        assertTrue(p.spawnReactionMs.isEmpty(), "the view never moved: nothing to react with")
        assertEquals(1, p.noTurnFights)
    }

    @Test
    fun `hits from inside the box or on its top or bottom face say nothing about your aim`() {
        ev("session", 0) { addProperty("v", 1) }
        spawn(1000, 1, "Grinch")
        hit(1500, 1, 0.0, 0.5)
        hit(1700, 1, 1.2, 1.0)
        hit(1900, 1, 1.4, 0.0)
        hit(2100, 1, 1.5, 0.6)
        ev("death", 2200) { addProperty("id", 1) }
        val p = learn()
        assertEquals(listOf(0.6f), p.aimHeights)
        assertEquals(listOf(1.5f), p.meleeDistances)
    }

    @Test
    fun `a yaw swing while looking straight down is no turn, nor one to a creature right below you`() {
        ev("session", 0) { addProperty("v", 1) }
        // a 64 degree yaw change at pitch 86-88 is about 4.5 degrees of real view change
        spawn(1000, 1, "Frozen Steve")
        for (i in 0 until 6) rot(1000L + i * 50, 30f - i * 12.8f, 86f + i * 0.4f, 1, cx = 0.2, cy = 63.0, cz = 0.0)
        hit(1300, 1, 0.3, 0.5)
        ev("death", 1400) { addProperty("id", 1) }
        // a real turn from looking down to looking up at a creature 0.2 m off: no direction to learn
        spawn(5000, 2, "Frozen Steve")
        rot(5000, 0f, 88f, 2, cx = 0.2, cy = 66.0, cz = 0.0)
        rot(5050, 0f, 88f, 2, cx = 0.2, cy = 66.0, cz = 0.0)
        for (i in 1 .. 6) rot(5050L + i * 50, 0f, 88f - i * 25f, 2, cx = 0.2, cy = 66.0, cz = 0.0)
        hit(5400, 2, 0.4, 0.5)
        ev("death", 5500) { addProperty("id", 2) }
        val p = learn()
        assertTrue(p.turns.isEmpty(), "${p.turns.map { it.angle }}")
        assertTrue(p.spawnReactionMs.isEmpty())
        assertEquals(1, p.noTurnFights)
    }

    @Test
    fun `a movement that stops short of the creature is not the turn, the one after it is`() {
        ev("session", 0) { addProperty("v", 1) }
        spawn(1000, 1, "Sea Walker")
        val yaws = listOf(0f, 0f, 0f, - 10f, - 20f, - 20f, - 20f, - 20f, - 45f, - 75f, - 88f, - 90f, - 90f)
        yaws.forEachIndexed { i, yaw -> rot(1000L + i * 50, yaw, 0f, 1) }
        hit(1650, 1, 2.5, 0.5)
        ev("death", 1700) { addProperty("id", 1) }
        val p = learn()
        val turn = p.turns.single()
        assertTrue(abs(turn.angle - 70f) < 0.5f, "from where the view paused: ${turn.angle}")
        assertEquals(200, turn.ms)
        assertEquals(4, turn.progress.size, "${turn.progress.toList()}")
        assertEquals(listOf(350), p.spawnReactionMs)
    }

    private fun slot(t: Long, item: String) = ev("slot", t) {
        addProperty("slot", if (item == "ROD_OF_THE_SEA") 0 else 1)
        addProperty("item", item)
        addProperty("rod", item == "ROD_OF_THE_SEA")
    }

    private fun loadoutTrip(t: Long, slot: Int) {
        ev("cmd", t) { addProperty("cmd", "loadout") }
        ev("menu", t + 200) { addProperty("title", "(1/3) Loadouts") }
        ev("menuClick", t + 800) {
            addProperty("title", "(1/3) Loadouts")
            addProperty("slot", slot)
        }
        ev("menuClose", t + 1250)
    }

    @Test
    fun `a weapon fight in another loadout gives the swap, loadout and restore timings`() {
        ev("session", 0) { addProperty("v", 1) }
        slot(10, "ROD_OF_THE_SEA")
        rodClick(3100)
        ev("cast", 3250) { addProperty("auto", false) }
        catchAt(4990, "Yeti")
        spawn(5320, 1, "Yeti")
        slot(6320, "DARK_CLAYMORE")
        // Loadout 8, then three swings
        loadoutTrip(6970, 33)
        for (i in 0 until 3) hit(8400L + i * 200, 1, 1.5, 0.5, "DARK_CLAYMORE")
        ev("death", 9000) { addProperty("id", 1) }
        // back to Loadout 9, the rod, and fishing
        loadoutTrip(9600, 34)
        slot(11_000, "ROD_OF_THE_SEA")
        rodClick(11_250)
        ev("cast", 11_400) { addProperty("auto", false) }
        val p = learn()
        assertEquals(listOf(1000), p.swapReactionMs, "from the spawn to the weapon")
        assertEquals(listOf(650), p.weaponToLoadoutMs)
        assertEquals(listOf(600), p.deathToRestoreMs)
        assertEquals(listOf(150), p.menuCloseToRodMs)
        assertEquals(listOf(180), p.menuCloseToClickMs, "the fight's menu closed to the first swing; the one after the death is no fight's")
        assertEquals(listOf(250), p.rodToCastMs, "the first cast came more than 3 s after taking the rod")
        assertEquals(listOf(200, 200), p.clickLeftWeaponMs)
        assertEquals(mapOf(9 to 1), p.fishingLoadouts, "Loadout 8 was for the fight")
        assertEquals(9, p.topFishingLoadout)
        val rule = assertNotNull(p.habits.getValue("Yeti").suggestion())
        assertEquals(KillAction.WEAPON, rule.action)
        assertEquals("DARK_CLAYMORE", rule.weaponId)
        assertEquals(8, rule.loadout)
    }

    @Test
    fun `the first click after a fight's loadout menu is yours only without another item taken first`() {
        ev("session", 0) { addProperty("v", 1) }
        spawn(1000, 1, "Yeti")
        slot(1500, "DARK_CLAYMORE")
        loadoutTrip(2000, 33)
        // a reel with the rod is no attack, the swing after it is
        rodClick(3400)
        hit(3500, 1, 1.5, 0.5, "DARK_CLAYMORE")
        spawn(5000, 2, "Yeti")
        loadoutTrip(6000, 34)
        // the menu closed at 7250, then another item first: the click is timed by that swap
        slot(7400, "FLAMING_FLAY")
        click(7600, "R", "FLAMING_FLAY", rod = true, near = 2)
        // a menu closed with no creature of yours alive
        ev("death", 8000) { addProperty("id", 1) }
        ev("death", 8000) { addProperty("id", 2) }
        loadoutTrip(9000, 34)
        hit(10_400, 3, 1.5, 0.5, "DARK_CLAYMORE")
        assertEquals(listOf(250), learn().menuCloseToClickMs)
    }

    /** Your left click with [item] while creature 1 is [nearDist] m from your eye, hitting [hit] (entity: that creature). */
    private fun swing(t: Long, item: String, hit: String, nearDist: Double = 2.0) = ev("click", t) {
        addProperty("b", "L")
        addProperty("auto", false)
        addProperty("item", item)
        addProperty("rod", item == "ROD_OF_THE_SEA")
        addProperty("hit", hit)
        if (hit == "entity") addProperty("target", 1)
        addProperty("near", 1)
        addProperty("nearDist", nearDist)
        addProperty("err", 10.0)
    }

    @Test
    fun `your misses next to your creatures are counted by rod and weapon`() {
        ev("session", 0) { addProperty("v", 1) }
        fishWithRod(100)
        spawn(5000, 1, "Yeti")
        var t = 6000L
        // 40 rod swings within 4 m, 6 at a block and 4 at air
        repeat(40) { i -> swing(t + i * 200L, "ROD_OF_THE_SEA", if (i < 6) "block" else if (i < 10) "air" else "entity") }
        t += 10_000
        // farther than 4 m, and with a tool: none of the killer's swings
        repeat(10) { i -> swing(t + i * 200L, "ROD_OF_THE_SEA", "air", nearDist = 4.5) }
        repeat(10) { i -> swing(t + 3000 + i * 200L, "HOTSPOT_RADAR", "air") }
        t += 6000
        // 12 claymore swings, 3 missed: too few for a share of their own
        repeat(12) { i -> swing(t + i * 200L, "DARK_CLAYMORE", if (i < 3) "air" else "entity") }
        val p = learn()
        assertEquals(40, p.rodMeleeClicks)
        assertEquals(10, p.rodMeleeMisses)
        assertEquals(0.25, p.missShare(rod = true), 1e-9)
        assertEquals(12, p.weaponMeleeClicks)
        assertEquals(3, p.weaponMeleeMisses)
        assertEquals(Profile.WEAPON_MISS, p.missShare(rod = false), "the reference recordings' share below 30 clicks")
    }

    @Test
    fun `a profile without miss counts, or with broken ones, misses like the reference recordings`() {
        val gson = com.google.gson.Gson()
        for (json in listOf("{}", """{"learner":6}""", """{"rodMeleeClicks":40,"rodMeleeMisses":50,"weaponMeleeClicks":40,"weaponMeleeMisses":-1}""")) {
            val p = gson.fromJson(json, Profile::class.java)
            assertEquals(Profile.ROD_MISS, p.missShare(rod = true), json)
            assertEquals(Profile.WEAPON_MISS, p.missShare(rod = false), json)
        }
        assertEquals(0.1, gson.fromJson("""{"weaponMeleeClicks":50,"weaponMeleeMisses":5}""", Profile::class.java).missShare(rod = false), 1e-9)
        assertTrue(BehaviorProfile.LEARNER >= 7, "a profile learned before the misses is learned again")
    }

    @Test
    fun `a swing at one creature that kills another with it is a sweep kill of that one`() {
        ev("session", 0) { addProperty("v", 1) }
        spawn(1000, 1, "Yeti")
        spawn(1010, 2, "Yeti")
        hit(2000, 1, 1.5, 0.5, "DARK_CLAYMORE")
        ev("death", 2100) { addProperty("id", 1) }
        ev("death", 2150) { addProperty("id", 2) }
        // a swing at someone else's creature (not yours) sweeping yours along is not counted
        spawn(10_000, 3, "Sea Walker")
        hit(11_000, 99, 1.5, 0.5, "DARK_CLAYMORE")
        ev("death", 11_150) { addProperty("id", 3) }
        val p = learn()
        val yeti = p.habits.getValue("Yeti")
        assertEquals(2, yeti.killed)
        assertEquals(1, yeti.sweepKills)
        assertEquals(0, yeti.diedUnfought)
        assertEquals(2, yeti.weapons["DARK_CLAYMORE"])
        assertEquals(KillAction.WEAPON, yeti.suggestion()?.action)
        val walker = p.habits.getValue("Sea Walker")
        assertEquals(0, walker.sweepKills)
        assertEquals(1, walker.diedUnfought)
    }

    @Test
    fun `few samples are drawn from their own range, too few give the default range`() {
        val samples = listOf(500, 510, 900)
        val few = List(500) { Humanize.ms(samples, 300, 400) }
        assertTrue(few.all { it in 450L .. 990L }, "${few.min()}..${few.max()}")
        assertTrue(few.any { it in 600L .. 750L }, "anywhere in their range, not only near a sample")
        repeat(200) {
            assertTrue(Humanize.ms(listOf(500, 700), 300, 400) in 300L .. 400L)
            // enough samples: one of them, from 400 ms on with a little lognormal jitter, drawn again outside 150..1200 (never clamped)
            assertTrue(Humanize.ms(List(8) { 600 }, 300, 400) in 150L .. 1200L)
            assertEquals(300L, Humanize.ms(List(8) { 300 }, 300, 400), "a short sample is on the tick grid already: as it is")
        }
        val six = List(10_000) { Humanize.ms(List(8) { 600 }, 300, 400) }.sorted()
        assertEquals(600.0, six[six.size / 2].toDouble(), 15.0, "centred on the sample")
        assertTrue(six.count { it in 540L .. 660L } > six.size * 0.6, "mostly within 10 % of it")
        // with enough samples for the caller: one of them, not anywhere in their range
        val many = List(5000) { Humanize.ms(samples, 300, 400, minSamples = 3) }
        assertTrue(many.count { it in 680L .. 740L } < many.size / 50, "${many.count { it in 680L .. 740L }} between the samples")
    }

    @Test
    fun `files that are not recordings are skipped`() {
        lines += "not json"
        lines += """{"e":"cast","t":5}"""
        val p = learn()
        assertEquals(0, p.sessions)
        assertFalse(p.hasData)
    }

    @Test
    fun `a recording from the recorder-only jar is learned only when asked for`() {
        ev("session", 0) {
            addProperty("v", 1)
            addProperty("mod", "recorder-1.0.0")
            addProperty("build", FishingRecorder.RECORDER_BUILD)
            addProperty("autoFishing", false)
        }
        catchAt(10_000, "Squid")
        // given explicitly (tests, looking at a friend's file): learned like any other
        val p = learn()
        assertEquals(1, p.sessions)
        assertEquals(listOf(200), p.biteReactionMs)
        // the folder relearn of your own profile leaves it out
        val skipped = learn(skipRecorderBuild = true)
        assertEquals(0, skipped.sessions)
        assertFalse(skipped.hasData)
    }

    @Test
    fun `your own recordings are learned when recorder-only ones are skipped`() {
        ev("session", 0) { addProperty("v", 1) }
        catchAt(10_000, "Squid")
        val p = learn(skipRecorderBuild = true)
        assertEquals(1, p.sessions)
        assertEquals(listOf(200), p.biteReactionMs)
    }

    /** A creature line at [t]: yours unless [own] is false, [hp] of [max] at [x], [y], [z]; [parent] marks a child. */
    private fun creature(t: Long, id: Int, name: String, x: Double, y: Double = 64.0, z: Double = 0.0, max: Double = 1000.0, own: Boolean = true, parent: String? = null) =
        ev("spawn", t) {
            addProperty("id", id)
            addProperty("name", name)
            addProperty("own", own)
            addProperty("hp", max)
            addProperty("max", max)
            addProperty("x", x)
            addProperty("y", y)
            addProperty("z", z)
            parent?.let { addProperty("parent", it) }
        }

    private fun hp(t: Long, id: Int, hp: Double) = ev("hp", t) {
        addProperty("id", id)
        addProperty("hp", hp)
    }

    private fun death(t: Long, id: Int, cause: String = "mob") = ev("death", t) {
        addProperty("id", id)
        addProperty("cause", cause)
    }

    /** Your view from (0, 64, 0) at [t] with your creatures [cs] (id -> x, y, z, all 1.8 tall). */
    private fun view(t: Long, yaw: Float, pitch: Float, cs: Map<Int, Triple<Double, Double, Double>>) = ev("rot", t) {
        addProperty("yaw", yaw)
        addProperty("pitch", pitch)
        addProperty("x", 0.0)
        addProperty("y", 64.0)
        addProperty("z", 0.0)
        addProperty("keys", 0)
        if (cs.isNotEmpty()) add("c", JsonArray().apply {
            cs.forEach { (id, p) -> add(JsonArray().apply { add(id); add(p.first); add(p.second); add(p.third); add(1.8); add(0.6) }) }
        })
    }

    /** Views every 50 ms from [from] to [to]: looking at +x (yaw -90), creatures standing still. */
    private fun views(from: Long, to: Long, cs: Map<Int, Triple<Double, Double, Double>>, yaw: Float = - 90f, pitch: Float = 0f) {
        var t = from
        while (t <= to) {
            view(t, yaw, pitch, cs)
            t += 50
        }
    }

    private fun click(t: Long, b: String, item: String, rod: Boolean = false, hold: Boolean = false, target: Int? = null, near: Int? = null, err: Double = 5.0, cast: Boolean = false) =
        ev("click", t) {
            addProperty("b", b)
            if (hold) addProperty("hold", true)
            addProperty("auto", false)
            addProperty("item", item)
            addProperty("rod", rod)
            target?.let { addProperty("target", it) }
            near?.let {
                addProperty("near", it)
                addProperty("err", err)
            }
            if (cast) addProperty("cast", true)
        }

    private fun flay(t: Long, hold: Boolean = false, near: Int? = null) = click(t, "R", "FLAMING_FLAY", rod = true, hold = hold, near = near)

    /** Three casts of yours with the Rod of the Sea, marked by the recorder: what you fish with. */
    private fun fishWithRod(from: Long) {
        for (i in 0 until 3) {
            click(from + i * 1000L, "R", "ROD_OF_THE_SEA", rod = true, cast = true)
            ev("cast", from + i * 1000L + 150) { addProperty("auto", false) }
        }
    }

    private fun at(x: Double, y: Double = 64.0, z: Double = 0.0) = Triple(x, y, z)

    @Test
    fun `the flay is a held weapon, not a fishing rod, and its clicks are no reels or casts`() {
        ev("session", 0) { addProperty("v", 1) }
        fishWithRod(100)
        creature(5000, 1, "Squid", 6.0)
        views(5000, 7000, mapOf(1 to at(6.0)))
        // the Flay taken and pressed right after a bite, and held at reel lines: neither a reel nor a cast
        ev("slot", 5100) {
            addProperty("slot", 3)
            addProperty("item", "FLAMING_FLAY")
            addProperty("rod", true)
        }
        ev("bite", 5150)
        flay(5300, near = 1)
        ev("reel", 5320) { addProperty("auto", false) }
        for (i in 1 .. 5) flay(5300L + i * 200, hold = true, near = 1)
        for (i in 0 .. 5) hp(5400L + i * 200, 1, 900.0 - i * 100)
        ev("reel", 6350) { addProperty("auto", false) }
        death(6500, 1)
        ev("end", 7000)
        val p = learn()

        assertEquals(setOf("ROD_OF_THE_SEA"), p.fishingRods)
        assertTrue(p.biteReactionMs.isEmpty(), "a Flay press is no reaction to a bite: ${p.biteReactionMs}")
        assertTrue(p.rodToCastMs.isEmpty(), "taking the Flay and pressing it is no cast: ${p.rodToCastMs}")
        val flay = p.weaponStyles.getValue("FLAMING_FLAY")
        assertEquals(ClickStyle.HOLD, flay.style)
        assertEquals(6, flay.uses)
        assertEquals(6, flay.effective)
        assertEquals(6, flay.reach.size)
        assertTrue(flay.reach.all { abs(it - 6f) < 0.01f }, "${flay.reach}")
        assertTrue(flay.dy.all { abs(it) < 0.01f })
        assertTrue(flay.aimErr.all { it < 10f }, "looking at it: ${flay.aimErr}")
        assertEquals(listOf(1100), flay.holdMs, "the press to the last repeat, plus half a repeat")
        assertTrue(WeaponPlanner.isWeapon(p, "FLAMING_FLAY"))
        assertFalse(WeaponPlanner.isFishingRod(p, "FLAMING_FLAY", isRodItem = true))
        val squid = p.habits.getValue("Squid")
        assertEquals(mapOf("FLAMING_FLAY" to 1), squid.killsBy)
        assertEquals(mapOf("mid/level" to mapOf("FLAMING_FLAY" to 1)), squid.choices)
        assertEquals(mapOf("FLAMING_FLAY" to 1), squid.weapons, "once per fight, however many repeats")
        assertEquals(1, squid.right, "one hold is one right click")
        assertEquals("WATER", squid.group)
        assertEquals(1000.0, squid.maxHp)
        val rule = assertNotNull(squid.suggestion())
        assertEquals(KillAction.WEAPON, rule.action)
        assertEquals("FLAMING_FLAY", rule.weaponId)
        assertEquals(AttackMode.RIGHT, rule.attack)
    }

    @Test
    fun `older recordings give a cast to the last right click shortly before it`() {
        ev("session", 0) { addProperty("v", 1) }
        for (t in listOf(1000L, 3000L, 5000L)) {
            rodClick(t)
            ev("cast", t + 150) { addProperty("auto", false) }
        }
        // another item's click right before your rod click: the cast is the rod's
        click(6950, "R", "AQUA_STAFF")
        rodClick(7000)
        ev("cast", 7150) { addProperty("auto", false) }
        // a rod-like item whose casts came too long after its clicks: none of them are its
        for (t in listOf(9000L, 11_000L, 13_000L)) {
            click(t, "R", "WATER_ROD", rod = true)
            ev("cast", t + 500) { addProperty("auto", false) }
        }
        val p = learn()
        assertEquals(setOf("ROD_OF_THE_SEA"), p.fishingRods)
    }

    @Test
    fun `with no item casting often enough every fishing-rod item but a rod-type weapon fishes`() {
        ev("session", 0) { addProperty("v", 1) }
        rodClick(1000)
        ev("cast", 1150) { addProperty("auto", false) }
        flay(3000)
        ev("slot", 4000) {
            addProperty("slot", 4)
            addProperty("item", "GRAPPLING_HOOK")
            addProperty("rod", true)
        }
        val p = learn()
        assertEquals(setOf("ROD_OF_THE_SEA"), p.fishingRods)
    }

    @Test
    fun `a click hurt the creature under the crosshair, else your nearest, else the closest one that dropped within 400 ms`() {
        ev("session", 0) { addProperty("v", 1) }
        creature(500, 1, "Sea Walker", 3.0)
        creature(500, 2, "Sea Walker", 6.0)
        creature(500, 3, "Sea Walker", 9.0)
        views(500, 5000, mapOf(1 to at(3.0), 2 to at(6.0), 3 to at(9.0)))
        // the crosshair on 3: it counts even though 1 dropped first
        click(1000, "R", "HYPERION", target = 3, near = 1)
        hp(1100, 1, 900.0)
        hp(1200, 3, 900.0)
        // your nearest (the recorder's near) dropped
        click(2000, "R", "HYPERION", near = 2)
        hp(2100, 1, 800.0)
        hp(2200, 2, 900.0)
        // the near one did not drop: the closest of those that did
        click(3000, "R", "HYPERION", near = 3)
        hp(3300, 2, 800.0)
        hp(3350, 1, 700.0)
        // a drop 500 ms later was no effect of the click
        click(4000, "R", "HYPERION", near = 1)
        hp(4500, 1, 600.0)
        ev("end", 5000)
        val p = learn()
        val s = p.weaponStyles.getValue("HYPERION")
        assertEquals(4, s.uses)
        assertEquals(3, s.effective)
        assertEquals(1, s.onTarget)
        assertEquals(listOf(9f, 6f, 3f), s.reach.map { Math.round(it * 10) / 10f })
        assertEquals(ClickStyle.RIGHT, s.style)
    }

    @Test
    fun `a creature your recast's bobber killed counts only as a bobber kill`() {
        ev("session", 0) { addProperty("v", 1) }
        catchAt(9670, "Frozen Steve")
        spawn(10_000, 1, "Frozen Steve")
        holdClick(10_001)
        ev("cast", 10_150) { addProperty("auto", false) }
        ev("death", 10_200) { addProperty("id", 1) }
        val p = learn()
        val steve = p.habits.getValue("Frozen Steve")
        assertEquals(1, steve.bobberKills)
        assertEquals(mapOf(WeaponPlanner.BOBBER to 1), steve.killsBy)
        assertTrue(steve.choices.isEmpty())
        assertTrue(p.choices.isEmpty())
        assertTrue(p.groupChoices.isEmpty())
    }

    @Test
    fun `a fight's choice is what killed it, in the situation of your first click with it`() {
        ev("session", 0) { addProperty("v", 1) }
        fishWithRod(100)
        // a Flay press at 8 blocks that does nothing; it walks up and the rod kills it
        creature(1000, 1, "Sea Leech", 8.0)
        views(1000, 1950, mapOf(1 to at(8.0)))
        flay(1500, near = 1)
        views(2000, 3000, mapOf(1 to at(2.0)))
        click(2500, "L", "ROD_OF_THE_SEA", rod = true, target = 1)
        hp(2600, 1, 500.0)
        click(2800, "L", "ROD_OF_THE_SEA", rod = true, target = 1)
        death(2900, 1)
        // never killed: the option with the most effective presses, held repeats not counted
        creature(5000, 2, "Sea Leech", 8.0)
        views(5000, 6000, mapOf(2 to at(8.0)))
        flay(5200, near = 2)
        for (i in 1 .. 4) flay(5200L + i * 200, hold = true, near = 2)
        for (i in 0 .. 4) hp(5300L + i * 200, 2, 900.0 - i * 50)
        views(6050, 8000, mapOf(2 to at(2.0)))
        click(6500, "L", "ROD_OF_THE_SEA", rod = true, target = 2)
        hp(6600, 2, 500.0)
        click(7000, "L", "ROD_OF_THE_SEA", rod = true, target = 2)
        hp(7100, 2, 400.0)
        ev("world", 8000)
        // two Hyperion presses from 7 blocks that do nothing, then the one that kills it once it came up to you: chosen
        // where you first pressed it, not where it first worked
        creature(9000, 3, "Sea Walker", 7.0)
        views(9000, 9950, mapOf(3 to at(7.0)))
        click(9200, "R", "HYPERION", near = 3)
        click(9600, "R", "HYPERION", near = 3)
        views(10_000, 11_000, mapOf(3 to at(2.0)))
        click(10_500, "R", "HYPERION", near = 3)
        death(10_600, 3)
        val p = learn()
        val leech = p.habits.getValue("Sea Leech")
        assertEquals(mapOf(WeaponPlanner.ROD to 1), leech.killsBy)
        assertEquals(mapOf("near/level" to mapOf(WeaponPlanner.ROD to 2)), leech.choices)
        val walker = p.habits.getValue("Sea Walker")
        assertEquals(mapOf("HYPERION" to 1), walker.killsBy)
        assertEquals(mapOf("far/level" to mapOf("HYPERION" to 1)), walker.choices)
        assertEquals(mapOf("near/level" to mapOf(WeaponPlanner.ROD to 2), "far/level" to mapOf("HYPERION" to 1)), p.choices)
        assertEquals(mapOf("WATER" to mapOf("near/level" to mapOf(WeaponPlanner.ROD to 2), "far/level" to mapOf("HYPERION" to 1))), p.groupChoices)
    }

    @Test
    fun `a rod hit's share of max hp ends at your next click and skips hits a weapon may have helped`() {
        ev("session", 0) { addProperty("v", 1) }
        creature(500, 1, "Banshee", 2.0)
        views(500, 5000, mapOf(1 to at(2.0)))
        click(1000, "L", "ROD_OF_THE_SEA", rod = true, target = 1)
        hp(1100, 1, 800.0)
        // your next click 100 ms later ends the window before the drop
        click(2000, "L", "ROD_OF_THE_SEA", rod = true, target = 1)
        rodClick(2100)
        hp(2300, 1, 700.0)
        // a Hyperion click 300 ms before: no sample
        click(2700, "R", "HYPERION")
        click(3000, "L", "ROD_OF_THE_SEA", rod = true, target = 1)
        hp(3100, 1, 650.0)
        // a mobility item clicked around it does not hurt anything
        click(4000, "L", "ROD_OF_THE_SEA", rod = true, target = 1)
        hp(4100, 1, 550.0)
        click(4200, "R", "ASPECT_OF_THE_VOID")
        ev("end", 5000)
        val p = learn()
        assertEquals(listOf(0.2f, 0f, 0.1f), p.habits.getValue("Banshee").rodHitShare.map { Math.round(it * 100) / 100f })
    }

    /** An own Bayou Sludge at (5, 64, 0) dying at [death] (the last view of it there), [killedWith] clicked on it 300 ms before. */
    private fun sludgeDies(id: Int, death: Long, killedWith: String? = null) {
        creature(death - 3000, id, "Bayou Sludge", 5.0, max = 20_000.0)
        views(death - 3000, death - 350, mapOf(id to at(5.0)))
        killedWith?.let { click(death - 300, "R", it, target = id) }
        views(death - 300, death - 50, mapOf(id to at(5.0)))
        death(death, id)
        views(death, death + 2000, emptyMap())
    }

    @Test
    fun `creatures you cannot catch coming up right where yours died are its children`() {
        ev("session", 0) { addProperty("v", 1) }
        sludgeDies(1, 10_000)
        var id = 100
        /** A Sludgling-like spawn that dies to a weapon click on it 300 ms later. */
        fun candidate(t: Long, x: Double, name: String = "Bayou Sludgling", max: Double = 5000.0) {
            val cid = id ++
            creature(t, cid, name, x, max = max, own = false)
            click(t + 300, "R", "HYPERION", target = cid)
            death(t + 400, cid)
        }
        candidate(9800, 6.0)        // 200 ms before the death was seen
        candidate(10_300, 5.5)
        candidate(10_700, 11.5)     // 6.5 blocks away
        candidate(10_750, 5.0)      // a fourth for one death
        candidate(10_900, 5.0)      // too late
        candidate(10_200, 12.5)     // too far
        candidate(10_100, 5.0, "Sea Walker", 1500.0)    // can be caught
        candidate(10_100, 5.0, "36,424", 36_424.0)      // a number, no name
        candidate(10_100, 5.0, "Bayou Sludgling", 0.0)  // no health read
        // children after one death could have come up there by chance: a second one makes the Sludge their parent
        sludgeDies(2, 20_000)
        candidate(20_300, 5.5)
        val p = learn()
        val child = p.habits.getValue("Bayou Sludgling")
        assertEquals("Bayou Sludge", child.parent)
        assertEquals("BACKWATER_BAYOU", child.group)
        assertEquals(mapOf("HYPERION" to 4), child.killsBy, "3 children at most for one death")
        assertEquals(0, child.seen, "a child is no catch")
        assertEquals(5000.0, child.maxHp)
        assertNull(p.habits["Sea Walker"]?.killsBy?.takeIf { it.isNotEmpty() })
        assertNull(p.habits["36,424"]?.killsBy?.takeIf { it.isNotEmpty() })
    }

    /** The Hyperion learned as an area attack on your Magma Slug, which dies at 3000: it hurts while you look away (yaw 90, the slug at +x). */
    private fun areaHyperionKillsMagmaSlug() {
        creature(500, 1, "Magma Slug", 2.0, max = 500_000.0)
        views(500, 2950, mapOf(1 to at(2.0)), yaw = 90f)
        for (i in 0 until 6) {
            click(1000L + i * 300, "R", "HYPERION", near = 1, err = 175.0)
            hp(1100L + i * 300, 1, 400_000.0 - i * 50_000)
        }
        death(3000, 1)
        views(3000, 6000, emptyMap(), yaw = 90f)
    }

    @Test
    fun `a child found offline died to an area weapon clicked without a target`() {
        ev("session", 0) { addProperty("v", 1) }
        areaHyperionKillsMagmaSlug()
        creature(3100, 50, "Baby Magma Slug", 2.5, max = 100_000.0, own = false)
        click(3500, "R", "HYPERION")
        death(3700, 50)
        // an item that is no weapon of yours clicked without a target killed nothing
        creature(3150, 51, "Baby Magma Slug", 2.0, max = 100_000.0, own = false)
        click(4500, "R", "SOME_WAND")
        death(4600, 51)
        val p = learn()
        assertTrue(WeaponPlanner.isWeapon(p, "HYPERION"), "${p.weaponStyles["HYPERION"]?.aimErr}")
        assertTrue(assertNotNull(WeaponPlanner.plan(p, "HYPERION")).aimDeg >= 90.0)
        val slug = p.habits.getValue("Baby Magma Slug")
        assertEquals("", slug.parent, "children after one death of it are no evidence yet")
        assertEquals(mapOf("HYPERION" to 1), slug.killsBy)
        assertEquals(mapOf("near/level" to mapOf("HYPERION" to 1)), slug.choices)
        // still fought where its parent was caught: the Magma Slug's fight and the child's
        assertEquals<Map<String, Map<String, Int>>?>(mapOf("near/level" to mapOf("HYPERION" to 2)), p.groupChoices["LAVA_CRIMSON_ISLE"])
    }

    @Test
    fun `a child found offline and killed long after it came up gives its killer but no situation`() {
        ev("session", 0) { addProperty("v", 1) }
        areaHyperionKillsMagmaSlug()
        creature(3100, 50, "Baby Magma Slug", 2.5, max = 100_000.0, own = false)
        click(5000, "R", "HYPERION")
        death(5200, 50)
        // it sank; 20 s later you had dropped 25 blocks after it and killed it there: where it came up, seen from down
        // there, would be a kill from far below it
        creature(3150, 51, "Baby Magma Slug", 2.0, max = 100_000.0, own = false)
        ev("rot", 23_150) {
            addProperty("yaw", 90f)
            addProperty("pitch", 0f)
            addProperty("x", 0.0)
            addProperty("y", 39.0)
            addProperty("z", 0.0)
            addProperty("keys", 0)
        }
        click(23_200, "R", "HYPERION")
        death(23_300, 51)
        val p = learn()
        val slug = p.habits.getValue("Baby Magma Slug")
        assertEquals(mapOf("HYPERION" to 2), slug.killsBy)
        assertEquals(mapOf("near/level" to mapOf("HYPERION" to 1)), slug.choices, "only the one killed 1.9 s after it came up")
        assertNull(p.choices["near/above"])
    }

    @Test
    fun `children are not catches and feed no spawn timings`() {
        ev("session", 0) { addProperty("v", 1) }
        fishWithRod(100)
        ev("slot", 5000) {
            addProperty("slot", 2)
            addProperty("item", "ROD_OF_THE_SEA")
            addProperty("rod", true)
        }
        sludgeDies(1, 10_000, killedWith = "HYPERION")
        // found offline: hit with the rod soon after it came up
        creature(10_200, 60, "Bayou Sludgling", 5.5, max = 5000.0, own = false)
        click(10_700, "L", "ROD_OF_THE_SEA", rod = true, target = 60)
        // the recorder's own claim, then a swap to a weapon for it
        creature(10_250, 61, "Bayou Sludgling", 5.5, max = 5000.0, parent = "Bayou Sludge")
        ev("slot", 10_800) {
            addProperty("slot", 1)
            addProperty("item", "HYPERION")
            addProperty("rod", false)
        }
        click(11_000, "R", "HYPERION", target = 61)
        hp(11_100, 61, 2000.0)
        death(11_200, 61)
        death(11_300, 60)
        val p = learn()
        val child = p.habits.getValue("Bayou Sludgling")
        assertEquals(0, child.seen)
        assertEquals(0, child.fought)
        assertEquals(0, child.killed)
        assertEquals(mapOf("HYPERION" to 1), child.killsBy, "the recorded child; the derived one's last click was 600 ms before")
        assertTrue(p.firstHitMs.isEmpty(), "${p.firstHitMs}")
        assertTrue(p.swapReactionMs.isEmpty(), "${p.swapReactionMs}")
        assertTrue(p.spawnReactionMs.isEmpty())
        assertEquals(1, p.kills, "only the Sludge")
    }

    /** Your [name] at ([x], 64, 0) for 3 s, dying at [death] (the last view of it there). */
    private fun ownDies(id: Int, name: String, death: Long, x: Double = 5.0, max: Double = 20_000.0) {
        creature(death - 3000, id, name, x, max = max)
        views(death - 3000, death - 50, mapOf(id to at(x)))
        death(death, id)
    }

    /** Learns from one recording per block, each starting from no lines. */
    private fun learnAll(vararg recordings: () -> Unit): Profile {
        val files = recordings.map { record ->
            lines.clear()
            record()
            File.createTempFile("lbq-rec", ".jsonl").apply {
                deleteOnExit()
                writeText(lines.joinToString("\n"))
            }
        }
        return BehaviorLearner.learn(files)
    }

    @Test
    fun `a creature is a parent once its children came up after 2 and a quarter of its deaths, over all recordings`() {
        val p = learnAll({
            ev("session", 0) { addProperty("v", 1) }
            for (i in 0 until 9) ownDies(i + 1, "Frog Man", 10_000L * (i + 1), max = 2500.0)
            // a creature nobody catches came up next to 2 of 9 dying Frog Men: someone else's, by chance
            creature(10_300, 101, "Marsh Wisp", 5.5, max = 800.0, own = false)
            creature(20_300, 102, "Marsh Wisp", 5.5, max = 800.0, own = false)
            // Bayou Sludglings after 1 of 2 Sludge deaths here...
            ownDies(201, "Bayou Sludge", 110_000)
            creature(110_200, 202, "Bayou Sludgling", 5.0, max = 5000.0, own = false)
            ownDies(203, "Bayou Sludge", 120_000)
        }, {
            ev("session", 0) { addProperty("v", 1) }
            // ... and after the only one in the next recording: 2 of 3
            ownDies(1, "Bayou Sludge", 10_000)
            creature(10_200, 2, "Bayou Sludgling", 5.0, max = 5000.0, own = false)
            // the recorder named the Magma Slug the parent of a Baby Magma Slug at both of its deaths
            ownDies(10, "Magma Slug", 30_000, max = 500_000.0)
            creature(30_100, 11, "Baby Magma Slug", 5.0, max = 100_000.0, parent = "Magma Slug")
            ownDies(12, "Magma Slug", 40_000, max = 500_000.0)
            creature(39_900, 13, "Baby Magma Slug", 5.0, max = 100_000.0, parent = "Magma Slug")
            // two Baby Magma Slugs of yours dying right as another baby came up next to each: no baby splits off a baby
            ownDies(20, "Baby Magma Slug", 50_000, max = 100_000.0)
            creature(50_000, 21, "Baby Magma Slug", 5.0, max = 100_000.0, own = false)
            ownDies(22, "Baby Magma Slug", 60_000, max = 100_000.0)
            creature(60_050, 23, "Baby Magma Slug", 5.0, max = 100_000.0, own = false)
        })
        assertEquals("", p.habits.getValue("Marsh Wisp").parent, "2 of 9 deaths")
        assertTrue(p.habits.values.none { it.parent == "Frog Man" }, "the killer would hold Auto Fishing after every Frog Man death")
        val sludgling = p.habits.getValue("Bayou Sludgling")
        assertEquals("Bayou Sludge", sludgling.parent)
        assertEquals("BACKWATER_BAYOU", sludgling.group)
        val baby = p.habits.getValue("Baby Magma Slug")
        assertEquals("Magma Slug", baby.parent)
        assertEquals("LAVA_CRIMSON_ISLE", baby.group)
    }

    @Test
    fun `a corrupted catch an older recording could not name is no child`() {
        ev("session", 0) { addProperty("v", 1) }
        for (i in 0 until 2) {
            val t = 10_000L * (i + 1)
            ownDies(i + 1, "Frog Man", t, max = 2500.0)
            // the 3.4.0 tracker logged your Corrupted catches as someone else's, glyphs glued to the name
            creature(t + 699, 10 + i, "aCorrupted Grincha", 5.5, max = 3.0, own = false)
            creature(t + 100, 20 + i, "aCorrupted Bayou Sludglinga", 5.5, max = 5000.0, own = false)
            // a name nobody catches at the same spot still is a child
            creature(t + 200, 30 + i, "Bayou Sludgling", 5.5, max = 5000.0, own = false)
        }
        val p = learn()
        assertNull(p.habits["aCorrupted Grincha"], "a Corrupted Grinch, which you catch")
        assertNull(p.habits["aCorrupted Bayou Sludglinga"], "a glyph glued to the name")
        assertEquals("Frog Man", p.habits.getValue("Bayou Sludgling").parent)
    }

    @Test
    fun `a weapon suggestion clicks the way you clicked your weapons, rod hits left out`() {
        // Inkling in the Bayou recording: 14 rod hits and 13 Flay or Hyperion presses, the two weapons in 6 fights each
        val inkling = Habit(
            fought = 40, killed = 40, bobberKills = 17, left = 14, right = 13, rodClicks = 14,
            weapons = linkedMapOf("HYPERION" to 6, "FLAMING_FLAY" to 6), killsBy = linkedMapOf(WeaponPlanner.BOBBER to 17, "HYPERION" to 4, "FLAMING_FLAY" to 7)
        )
        val rule = assertNotNull(inkling.suggestion())
        assertEquals(KillAction.WEAPON, rule.action)
        assertEquals("FLAMING_FLAY", rule.weaponId, "a tie goes to the weapon that killed it more often")
        assertEquals(AttackMode.RIGHT, rule.attack)
        // sword swings with a few rod hits mixed in stay left clicks
        val swung = Habit(fought = 10, killed = 10, left = 12, right = 1, rodClicks = 4, weapons = linkedMapOf("DARK_CLAYMORE" to 8))
        assertEquals(AttackMode.LEFT, swung.suggestion()?.attack)
    }

    @Test
    fun `weapons are counted once per fight`() {
        ev("session", 0) { addProperty("v", 1) }
        for (f in 0 until 2) {
            val t = 10_000L * (f + 1)
            spawn(t, f + 1, "Sea Leech")
            for (i in 0 until 5) click(t + 500 + i * 300, "R", "HYPERION", near = f + 1, err = 3.0)
            ev("death", t + 2500) { addProperty("id", f + 1) }
        }
        val p = learn()
        val leech = p.habits.getValue("Sea Leech")
        assertEquals(mapOf("HYPERION" to 2), leech.weapons)
        assertEquals(10, leech.right, "presses are still counted one by one")
    }

    @Test
    fun `the hotbar line is read`() {
        ev("session", 0) { addProperty("v", 1) }
        ev("hotbar", 10) {
            add("items", JsonArray().apply { listOf("ASPECT_OF_THE_VOID", "HYPERION", "ROD_OF_THE_SEA", "FLAMING_FLAY", "", "", "", "", "SKYBLOCK_MENU").forEach(::add) })
        }
        catchAt(10_000, "Squid")
        ev("hotbar", 20_000) { add("items", JsonArray().apply { add("HYPERION"); add(5) }) }
        ev("hotbar", 30_000) { addProperty("items", "broken") }
        val p = learn()
        assertEquals(1, p.sessions)
        assertEquals(listOf(200), p.biteReactionMs)
        assertEquals(setOf("ASPECT_OF_THE_VOID", "HYPERION", "ROD_OF_THE_SEA", "FLAMING_FLAY", "SKYBLOCK_MENU"), p.hotbarItems.keys, "item ids only")
        assertEquals(1, p.hotbarItems["HYPERION"], "once per recording")
    }

    /** Yaw lines 50 ms apart (pitch 0), turning by [steps] degrees one tick after the other, starting at [t0]. */
    private fun yawLines(steps: List<Double>, t0: Long = 0L, tick0: Long = - 1L): List<ViewMotion.Line> {
        var yaw = 0.0
        return steps.mapIndexed { i, d ->
            yaw += d
            ViewMotion.Line(t0 + i * 50L, yaw, 0.0, if (tick0 >= 0) tick0 + i else - 1L)
        }
    }

    @Test
    fun `a turn is one movement with its amplitude, duration, peak time and end speed`() {
        val run = ViewMotion.runs(yawLines(listOf(0.0, 0.0, 0.0, 2.0, 8.0, 14.0, 14.0, 8.0, 2.0, 0.0, 0.0, 0.0))).single()
        val move = ViewMotion.segment(run).moves.single()
        assertFalse(move.cut)
        assertEquals(48.0, move.amp, 0.01)
        assertEquals(300.0, move.dur, 0.01, "6 moving ticks")
        assertEquals(280.0, move.peak, 0.01)
        assertEquals(125.0 / 300.0, move.tpk, 1e-9, "the first of the two fastest ticks, at its middle")
        assertEquals(40.0 / 280.0, move.endShare, 1e-9)
        assertEquals(1, move.strokes.size)
        assertEquals(1, move.nsub)
        assertNull(move.valley)
        assertEquals(0.0, move.over, 1e-6)
        assertTrue(move.halfLag.isNaN(), "a yaw-only turn has no axis lag")
    }

    @Test
    fun `one slow tick is a valley between two strokes, two still ticks end the movement`() {
        val seg = ViewMotion.segment(ViewMotion.runs(yawLines(listOf(0.0, 0.0, 0.0, 2.0, 8.0, 14.0, 8.0, 2.0, 0.3, 2.0, 6.0, 2.0, 0.0, 0.0, 0.0))).single())
        val move = seg.moves.single()
        assertEquals(44.3, move.amp, 0.01)
        assertEquals(2, move.nsub)
        assertEquals(listOf(34.3, 10.0), move.strokes.map { it.amp.roundToTenth() })
        assertEquals(listOf(275.0, 125.0), move.strokes.map { it.dur }, "half the valley tick off each side of the split")
        val valley = assertNotNull(move.valley)
        assertEquals(34.3 / 44.3, valley.along, 1e-6)
        assertEquals(0.0, valley.cross, 1e-6)
        assertEquals(50.0, valley.pauseMs, "the valley tick itself was slow")
        // the same strokes with two still ticks between them are two movements
        val two = ViewMotion.segment(ViewMotion.runs(yawLines(listOf(0.0, 0.0, 2.0, 8.0, 14.0, 8.0, 2.0, 0.0, 0.0, 2.0, 6.0, 2.0, 0.0, 0.0))).single())
        assertEquals(listOf(34.0, 10.0), two.moves.map { it.amp.roundToTenth() })
        assertTrue(two.moves.all { it.strokes.size == 1 })
    }

    private fun Double.roundToTenth() = Math.round(this * 10) / 10.0

    @Test
    fun `tick counters make a short tick no speed spike and fill the ticks a sparse recorder left out`() {
        // a 30 degree step in an 11 ms tick: 2727 deg/s by the wall clock, no hand; one tick by the counter
        val short = listOf(ViewMotion.Line(0, 0.0, 0.0), ViewMotion.Line(50, 0.0, 0.0), ViewMotion.Line(61, 30.0, 0.0), ViewMotion.Line(111, 30.0, 0.0), ViewMotion.Line(161, 30.0, 0.0))
        assertEquals(listOf(2, 3), ViewMotion.runs(short).map { it.size }, "without counters the artefact splits the run")
        val counted = short.mapIndexed { i, l -> ViewMotion.Line(l.t, l.yaw, l.pitch, 100L + i) }
        val run = ViewMotion.runs(counted).single()
        assertTrue(run.drop(1).all { it.dt == 50.0 })
        assertEquals(600.0, ViewMotion.segment(run).moves.single().peak, 1e-6)

        // idle lines: written when the view changed (and a heartbeat); the change was in the last tick before a line
        val sparse = listOf(ViewMotion.Line(0, 10.0, 0.0, 500), ViewMotion.Line(1000, 10.0, 0.0, 520), ViewMotion.Line(1300, 10.3, 0.0, 526))
        val idle = ViewMotion.runs(sparse).single()
        assertEquals(27, idle.size, "ticks 500..526")
        assertEquals(26, idle.count { it.yaw == 10.0 })
        val seg = ViewMotion.segment(idle)
        assertEquals(6.0, seg.speed.last(), 1e-6, "0.3 degrees in its last tick")
        assertTrue(seg.moves.isEmpty())

        // a teleport line, a counter gap of more than 2 s and a counter going back each start a new run
        val broken = listOf(
            ViewMotion.Line(0, 0.0, 0.0, 1), ViewMotion.Line(50, 0.0, 0.0, 2), ViewMotion.Line(100, 90.0, 0.0, 3, tp = true), ViewMotion.Line(150, 90.0, 0.0, 4),
            ViewMotion.Line(5000, 90.0, 0.0, 100), ViewMotion.Line(5050, 90.0, 0.0, 101), ViewMotion.Line(5100, 90.0, 0.0, 5), ViewMotion.Line(5150, 90.0, 0.0, 6)
        )
        assertEquals(listOf(2, 2, 2, 2), ViewMotion.runs(broken).map { it.size })
    }

    @Test
    fun `a wrapped yaw is the short way round, and a yaw swing looking straight down is a small movement`() {
        // yaw from 179 to -179 is 2 degrees, not 358
        val wrap = ViewMotion.runs(listOf(ViewMotion.Line(0, 179.0, 0.0), ViewMotion.Line(50, 179.0, 0.0), ViewMotion.Line(100, - 179.0, 0.0), ViewMotion.Line(150, - 179.0, 0.0)))
        assertEquals(2.0, ViewMotion.segment(wrap.single()).moves.single().amp, 1e-6)
        assertEquals(2.0, ViewMotion.angle(179.0, 0.0, - 179.0, 0.0), 1e-9)
        assertEquals(1.414, ViewMotion.angle(0.0, 89.0, 90.0, 89.0), 0.005, "90 degrees of yaw at pitch 89")
    }

    /**
     * [n] fights, 92 view lines each, 50 ms apart: 30 still lines (one of them nudged by 0.15°), then a creature appears 10 m
     * to your left; 450 ms later you turn 90° to it in 6 ticks; it dies 2 s after it appeared, and 250 ms after its death
     * you turn back in 6 ticks to 3° short of where you started (the next fight starts from there). Its line entry is there
     * while it lives.
     */
    /**
     * [n] fights 90 degrees off your view, each turned to after 500 ms; fight k turns back 300 ms after its death when
     * [back] says so, and the next one's creature comes up 2.3 s (2.6 s after a turn back) plus [pauseMs] after it.
     */
    private fun turnFights(n: Int, back: (Int) -> Boolean = { true }, pauseMs: (Int) -> Long = { 0L }) {
        ev("session", 0) { addProperty("v", 1) }
        var t = 1000L
        var yaw = 0.0
        var pitch = 0.0
        for (k in 0 until n) {
            val id = 100 + k
            val start = yaw
            val side = Math.toRadians(start - 90)
            val spot = Triple(- 10 * Math.sin(side), 64.0, 10 * Math.cos(side))
            // looking at half its height from your eyes: 0.72 m down over 10 m
            val down = Math.toDegrees(Math.atan2(0.72, 10.0))
            var spawnAt = Long.MAX_VALUE
            var deathAt = Long.MAX_VALUE
            fun line(dYaw: Double = 0.0, dPitch: Double = 0.0) {
                yaw += dYaw
                pitch += dPitch
                view(t, yaw.toFloat(), pitch.toFloat(), if (t in spawnAt until deathAt) mapOf(id to spot) else emptyMap())
                t += 50
            }
            for (i in 0 until 30) if (i == 15) line(0.15) else if (i == 16) line(- 0.15) else line()
            spawnAt = t
            spawn(t, id, "Sea Walker")
            repeat(10) { line() }
            for (f in listOf(4, 16, 25, 25, 16, 4)) line(- 90.0 * f / 90, down * f / 90)
            while (t < spawnAt + 2000) line()
            deathAt = t
            ev("death", t) { addProperty("id", id) }
            repeat(6) { line() }
            if (back(k)) for (f in listOf(4, 15, 24, 24, 15, 5)) line(87.0 * f / 87, - down * f / 87)
            repeat(10 + (pauseMs(k) / 50).toInt()) { line() }
        }
    }

    @Test
    fun `the learner measures your movements, rest nudges and still share`() {
        turnFights(40)
        val m = learn().motor
        assertEquals(80, m.moves, "40 turns and 40 turns back")
        assertEquals(80, m.strokes)
        assertEquals(listOf(0, 0, 0, 80, 0), m.binMoves)
        assertEquals(0.0, m.multiShare[3], "single strokes only")
        assertEquals(0.05, m.multiP[3], 1e-9, "no multi-stroke movements of yours: as rare as planning allows")
        assertEquals(Motor().multiP[2], m.multiP[2], "a bin without movements keeps its default")
        // 300 ms for 90 and 87 degrees, against the reference sub-stroke law's 320 and 317
        assertEquals((300.0 / 320 + 300.0 / 317.35) / 2, m.strokeK, 0.001)
        assertEquals(70 * m.strokeK, m.lawA, 1e-9)
        assertEquals(50 * m.strokeK, m.lawB, 1e-9)
        assertEquals(0.0, m.slowShare)
        assertEquals(0.01, m.slowP, 1e-9)
        // the first fight's 30 lines before its creature are no fight ticks; 6 ticks of each movement and two nudges
        // in each later still stretch changed the view
        assertEquals(92 * 40 - 1 - 29, m.fightTicks)
        assertEquals(1.0 - (12 * 40 + 2 * 39) / 3650.0, m.stillShare, 1e-9)
        assertEquals(92 * 40 - 1 - 12 * 40, m.restTicks)
        assertEquals(80.0 / 3199, m.restChanged, 1e-9, "a nudge and the step back in each still stretch")
        assertEquals(0.5, m.restFollow, 1e-9, "the step back comes right after the nudge")
        assertEquals(1.0, m.restYaw, 1e-9)
        assertEquals(0.015 * m.restChanged / 0.0246, m.restStart, 1e-9)
        assertEquals(80, m.restHandAxes, "one yaw step each, looking level")
        assertEquals(0, m.restPoleAxes)
        assertEquals(Motor().restSteps, m.restSteps, "too few nudges: the reference hand's sizes")
    }

    @Test
    fun `the learner measures when you turn toward a creature and back after the fight`() {
        turnFights(10)
        val p = learn()
        val all = p.policy.all
        assertEquals(10, all.creatures, "90 degrees off at +300 ms")
        assertEquals(10, all.turned)
        assertEquals(1.0, all.rate)
        assertEquals(10, all.spawnLatency.n, "no weapon swap: timed from the spawn")
        assertEquals(450.0, all.spawnLatency.p50, 1e-6, "the turn starts at the last still tick")
        assertEquals(0, all.swapLatency.n, "the reference value stays")
        assertTrue(all.stopErr.p50 < 2.0, "${all.stopErr}")
        assertEquals(10, p.policy.turnFights)
        assertEquals(10, p.policy.backFights)
        assertEquals(10, p.policy.backBeyond)
        assertEquals(10, p.policy.backTurned)
        assertEquals(1.0, p.policy.backRate)
        assertEquals(250.0, p.policy.backLatency.p50, 1e-6, "from the death to the last still tick before the turn back")
        assertEquals(3.0, p.policy.backRest.p50, 0.05)
        assertEquals("none", p.policy.classes.keys.single(), "no clicks")
    }

    @Test
    fun `the turn back rate leaves out fights the next one cut short`() {
        // 10 turned back, 4 without one but the next creature 2.3 s after the death, 4 without one and nothing for 6 s
        turnFights(18, back = { it < 10 }, pauseMs = { if (it >= 14) 4000L else 0L })
        val p = learn().policy
        assertEquals(18, p.backBeyond)
        assertEquals(10, p.backTurned)
        assertEquals(4, p.backInterrupted)
        assertEquals(4, p.backNone)
        // the killer's own next fight cuts its turn back short as well: counting those as none would do it twice
        assertEquals(10.0 / 14, p.backRate, 1e-9)
        assertEquals(10.0 / 14, ViewPolicy.Learned.of(p, null).backRate, 1e-9)
        // no cast with a view in these recordings: every fight counts as started at the fishing view
        assertEquals(18, p.backHome)
        assertEquals(10, p.backHomeTurned)
        assertEquals(4, p.backHomeInterrupted)
    }

    @Test
    fun `the turn back rate is your share over the fights that started at your fishing view`() {
        val rules = BehaviorLearner.BackRules
        // within 8 degrees of the view of your last cast: started at the fishing view; without a cast with a view, it counts
        assertTrue(rules.fished(10.0, 30.0, 15.0 to 33.0))
        assertFalse(rules.fished(10.0, 30.0, 40.0 to 30.0))
        assertTrue(rules.fished(10.0, 30.0, null))
        // 38 of 51 turned back and 2 cut short; of the 47 that started at the fishing view, 35 turned back and 1 cut short
        assertEquals(35.0 / 46, rules.rate(38, 51, 2, 35, 47, 1, 8)!!, 1e-9)
        // too few of those: all of them
        assertEquals(38.0 / 49, rules.rate(38, 51, 2, 5, 6, 0, 8)!!, 1e-9)
        assertNull(rules.rate(3, 5, 0, 3, 5, 0, 8))
    }

    /**
     * Fights with one creature of yours and no clicks, 10 m to your right while you look along +z: [looked] of them turned
     * to 2.5 s after the spawn, the move stopping a quarter of the way short, and turned back from 600 ms after that move
     * ended (death at 4 s); [ignored] never turned to (death at 3 s); [overhead] right below you while you look up.
     */
    /** [between]: a 6 degree move of the pitch alone 200 ms after each look's rest, before its return. */
    private fun lookFights(looked: Int, ignored: Int, overhead: Int, between: Boolean = false) {
        ev("session", 0) { addProperty("v", 1) }
        var t = 1000L
        var id = 300
        fun fight(turn: Boolean, below: Boolean) {
            id ++
            val spot = if (below) at(0.2, 58.0) else at(10.0)
            var pitch = if (below) - 70f else 0f
            val spawnAt = t + 500
            val deathAt = spawnAt + if (turn) 4000 else 3000
            var yaw = 0.0
            fun line(dYaw: Double = 0.0, dPitch: Float = 0f) {
                yaw += dYaw
                pitch += dPitch
                view(t, yaw.toFloat(), pitch, if (t in spawnAt until deathAt) mapOf(id to spot) else emptyMap())
                t += 50
            }
            while (t < spawnAt) line()
            spawn(spawnAt, id, "Sea Walker")
            if (turn) {
                while (t <= spawnAt + 2500) line()
                for (f in listOf(4, 16, 25, 25, 16, 4)) line(- 67.5 * f / 90)
                if (between) {
                    repeat(4) { line() }
                    repeat(3) { line(dPitch = 2f) }
                }
                while (t <= spawnAt + 3400) line()
                for (f in listOf(4, 16, 25, 25, 16, 4)) line(67.5 * f / 90)
            }
            while (t < deathAt) line()
            death(deathAt, id)
            repeat(10) { line() }
        }
        repeat(looked) { fight(turn = true, below = false) }
        repeat(ignored) { fight(turn = false, below = false) }
        repeat(overhead) { fight(turn = false, below = true) }
    }

    @Test
    fun `the learner counts your looks by age, where they stop and your return, overhead creatures apart`() {
        lookFights(looked = 8, ignored = 4, overhead = 2)
        val p = learn()
        val l = p.policy.looks
        assertEquals(14, p.policy.all.creatures)
        assertEquals(12, l.all.creatures, "the two right below you apart")
        assertEquals(8, l.all.turned)
        assertEquals(2, l.overhead)
        assertEquals(0, l.overheadTurned)
        assertEquals(listOf(0, 8, 0, 0, 0, 0), l.all.events, "first turns 2.5 s after the spawn")
        // at risk from 300 ms: 1.7 s in the first bin for every one, then 0.5 s to the turn or 1 s to the death
        assertEquals(12 * 1.7, l.all.exposureS[0], 1e-9)
        assertEquals(8 * 0.5 + 4 * 1.0, l.all.exposureS[1], 1e-9)
        assertEquals(listOf(0.0, 0.0, 0.0, 0.0), l.all.exposureS.drop(2))
        assertEquals(0, l.all.freeAtFirst, "no clicks: no weapon plan")
        assertEquals(l.all.events, l.classes.getValue("none").events)
        assertEquals(8, l.stopShare.n)
        assertEquals(0.25, l.stopShare.p50, 0.01, "a quarter of the way short")
        assertEquals(8, l.backLooks, "no clicks: no plan needs a turn")
        assertEquals(8, l.backTurned)
        assertEquals(1.0, l.backRate)
        assertEquals(600.0, l.backLatency.p50, 1e-9, "from the look's end to the last still line before the return")
        assertEquals(600.0, l.backGap.p50, 1e-9, "nothing in between: the gap is the latency")
        // 12 creatures are too few for your own table over all classes, enough for the class's: shrunk toward the reference
        val table = ViewPolicy.Looks.Table.of(l)
        assertEquals(ViewPolicy.Looks.Table.DEFAULT.rate(null, 2500), table.rate(null, 2500), 1e-9)
        assertEquals((8 + 20 * 12.0 / 423.0) / (8.0 + 20), table.rate("none", 2500), 1e-9)
        assertEquals(1.0, table.backRate, "8 looks are enough for your own return rate")
    }

    @Test
    fun `the way back after a look is timed from the view's last rest, a move of yours in between included`() {
        lookFights(looked = 8, ignored = 0, overhead = 0, between = true)
        val l = learn().policy.looks
        assertEquals(8, l.backTurned)
        assertEquals(600.0, l.backLatency.p50, 1e-9, "from the look's end")
        // the look rested at its last moving line, the pitch move 200 ms later rested 150 ms after that: 250 ms before the
        // return's first moving line
        assertEquals(250.0, l.backGap.p50, 1e-9, "from the move in between")
        assertEquals(8, l.backGap.n)
        assertEquals(252.0, ViewPolicy.Looks.Table.of(l).backGapP50, "eight equal gaps have no spread: the reference's gap")
    }

    @Test
    fun `a profile without looks, with null ones or a broken table reads the reference looks without throwing`() {
        val gson = com.google.gson.Gson()
        val d = ViewPolicy.Looks.Table.DEFAULT
        for (json in listOf(
            """{"learner":5}""",
            """{"policy":{"looks":null}}""",
            """{"policy":{"looks":{"all":null,"classes":null,"stopShare":null,"backLatency":null,"backGap":null}}}""",
            """{"policy":{"looks":{"all":{"creatures":400,"turned":100,"events":[1,2,3],"exposureS":[1.0,1.0,1.0,1.0,1.0,1.0]},"classes":{"X:L":null}}}}""",
            """{"policy":{"looks":{"all":{"creatures":400,"turned":100,"events":null,"exposureS":[1.0,-1.0,1.0,1.0,1.0,1.0]},"backLooks":50,"backRate":7.0}}}""",
        )) {
            val p = gson.fromJson(json, Profile::class.java)
            val t = ViewPolicy.Looks.Table.of(p.policy.looks)
            for (age in BehaviorProfile.Looks.AGE_BINS) assertEquals(d.rate(null, age), t.rate("X:L", age), 1e-9, json)
            assertEquals(d.first(null), t.first("X:L"), json)
            assertEquals(d.stop.toList(), t.stop.toList(), json)
            assertEquals(d.backRate, t.backRate, json)
            assertEquals(d.backGapP50, t.backGapP50, json)
        }
        assertEquals(356, gson.fromJson("""{"learner":5}""", Profile::class.java).policy.looks.all.creatures, "the reference recordings")
        assertTrue(BehaviorProfile.LEARNER > 5, "a profile learned before the looks is learned again")
    }

    @Test
    fun `timing streaks are counted in order, within a recording`() {
        ev("session", 0) { addProperty("v", 1) }
        // bite reactions: mostly 200 ms, slow ones (above 2.5 times the median) in pairs
        val reactions = List(5) { listOf(200, 200, 200, 700, 700) }.flatten() + List(15) { 200 }
        var t = 1000L
        for (r in reactions) {
            ev("bite", t)
            rodClick(t + r)
            t += 5000
        }
        val p = learn()
        assertEquals(reactions, p.biteReactionMs, "kept in order")
        val streak = assertNotNull(p.timing.streaks["biteReactionMs"])
        assertEquals(40, streak.n)
        assertEquals(500.0, streak.threshold, 1e-9)
        assertEquals(0.25, streak.slowShare, 1e-9)
        assertEquals(10, streak.slowPairs)
        assertEquals(0.5, streak.stay, 1e-9, "every first slow one is followed by another")
        assertEquals(0.25 * 0.5 / 0.75, streak.enter, 1e-9)
        // too few samples: the reference values
        assertNull(p.timing.streaks["catchRecastMs"])
        assertEquals(0.42, p.timing.streak("catchRecastMs")?.stay)
        assertNull(p.timing.streak("menuOpenToClickMs"))
    }

    @Test
    fun `a bot trace is never learned`() {
        ev("session", 0) {
            addProperty("v", 2)
            addProperty("bot", true)
        }
        catchAt(10_000, "Squid")
        assertEquals(0, learn().sessions)
    }

    @Test
    fun `a profile without the new fields reads them as defaults`() {
        val gson = com.google.gson.Gson()
        val old = gson.fromJson("""{"learner":3,"sessions":2,"biteReactionMs":[200]}""", Profile::class.java)
        assertEquals(Motor(), old.motor)
        assertEquals(70.0, old.motor.lawA)
        assertEquals(0.78, old.policy.backRate)
        assertEquals(0.75, old.timing.meleeRho)
        val partial = gson.fromJson("""{"motor":{"lawA":80.0,"moves":12},"policy":{"backRate":0.5}}""", Profile::class.java)
        assertEquals(80.0, partial.motor.lawA)
        assertEquals(12, partial.motor.moves)
        assertEquals(50.0, partial.motor.lawB, "a field the file does not have keeps its default")
        assertEquals(listOf(0.50, 0.45, 0.50, 0.50, 0.55), partial.motor.multiP)
        assertEquals(MotorParams.REST_STEPS, partial.motor.restSteps)
        assertEquals(MotorParams().restPoleP50, partial.motor.restPoleP50)
        // a profile from before the ways back were learned plans them like the reference recordings
        assertEquals(0.91, partial.motor.backFirstMu)
        assertEquals(0.68, partial.motor.returnFirstMu)
        assertEquals(0, partial.motor.backFirstN)
        assertEquals(0, partial.motor.returnFirstN)
        assertEquals(0.68, HumanAim.returnParamsOf(partial.motor).firstMean)
        assertEquals(0.91, HumanAim.turnBackParamsOf(partial.motor).firstMean)
        // a broken list reads as the reference hand's sizes
        val broken = gson.fromJson("""{"motor":{"restSteps":null,"restPoleSigma":-1.0}}""", Profile::class.java)
        assertEquals(MotorParams.REST_STEPS, HumanAim.paramsOf(broken.motor).restSteps)
        assertEquals(MotorParams().restPoleSigma, HumanAim.paramsOf(broken.motor).restPoleSigma)
        assertEquals(listOf(0.3, 0.7), HumanAim.paramsOf(Motor(restSteps = mutableListOf(0.3, 0.7))).restSteps)
        assertEquals(15.0, partial.policy.all.stopErr.p50)
        assertEquals(gson.toJson(partial), gson.toJson(gson.fromJson(gson.toJson(partial), Profile::class.java)), "round trip")
        assertEquals(listOf(- 1, 0, 0, 1, 2, 3, 4, 4), listOf(5.9, 6.0, 14.9, 15.0, 30.0, 60.0, 100.0, 180.0).map { Motor().bin(it) })
    }

    @Test
    fun `the automatic relearn runs only while the largest recording fits 8 times over in the free heap`() {
        val heap = BehaviorProfile.RelearnHeap
        val mb = 1_000_000L
        assertTrue(heap.fits(7 * mb, 1_000 * mb), "a 41-minute recording in a game with 1 GB free")
        assertTrue(heap.fits(0L, 0L), "no recording")
        assertTrue(heap.fits(30 * mb, 240 * mb))
        assertFalse(heap.fits(30 * mb + 1, 240 * mb), "a 3-hour recording with 240 MB free")
        assertFalse(heap.fits(1L, - 5L), "a heap reported fuller than its maximum")
        assertFalse(heap.fits(Long.MAX_VALUE / 4, Long.MAX_VALUE), "no overflow")
    }

    @Test
    fun `rot lines read ahead give the session their JSON would`() {
        fun parse(s: String) = com.google.gson.JsonParser.parseString(s).asJsonObject
        rot(100, - 90f, 3f, 7)
        rot(150, - 85.5f, 4.25f, 7, cx = 11.0)
        ev("rot", 200) {
            addProperty("yaw", 12.5)
            addProperty("pitch", - 3.0)
            addProperty("tk", 4L)
            addProperty("tp", true)
        }
        ev("click", 210) {
            addProperty("item", "ROD")
            addProperty("b", "L")
            addProperty("err", 2.5)
        }
        ev("cast", 220) {
            addProperty("yaw", 1.0)
            addProperty("pitch", 2.0)
        }
        val all = lines.map(::parse)
        val (rots, others) = all.partition { it.get("e").asString == "rot" }
        val fromJson = GlanceMeasure.read(all, setOf("ROD"), { false })
        val ahead = GlanceMeasure.read(others, setOf("ROD"), { false }, parsed = rots.map(GlanceMeasure::line))
        fun GlanceMeasure.Line.dump() = listOf(t, yaw, pitch, x, y, z, keys, tick, tp, creatures.mapValues { it.value.toList() })
        assertEquals(fromJson.lines.map { it.dump() }, ahead.lines.map { it.dump() })
        assertEquals(listOf(4L, true), ahead.lines[2].let { listOf(it.tick, it.tp) })
        assertEquals(listOf(11.0, 64.0, 0.0, 1.8, - 1.0), ahead.lines[1].creatures.getValue(7).toList())
        assertEquals(fromJson.clicks.map { it.t }, ahead.clicks.map { it.t })
        assertEquals(fromJson.casts.map { it.t }, ahead.casts.map { it.t })
    }
}
