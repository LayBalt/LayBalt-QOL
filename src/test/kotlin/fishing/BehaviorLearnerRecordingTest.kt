package fishing

import com.laybalt.features.impl.fishing.BehaviorLearner
import com.laybalt.features.impl.fishing.BehaviorProfile
import com.laybalt.features.impl.fishing.BehaviorProfile.Profile
import com.laybalt.features.impl.fishing.BehaviorProfile.WeaponStyle
import com.laybalt.features.impl.fishing.ClickStyle
import com.laybalt.features.impl.fishing.FishingRecorder
import com.laybalt.features.impl.fishing.ViewPolicy
import com.laybalt.features.impl.fishing.WeaponPlanner
import com.laybalt.features.impl.fishing.KillRules.AttackMode
import com.laybalt.features.impl.fishing.KillRules.KillAction
import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.math.abs
import kotlin.test.*

/**
 * Learns from a real recording: the path comes from the `LBQ_RECORDING` environment variable (skipped without it).
 * The expectations are those of the 33-minute Jerry Water hotspot session the bobber-kill and recast rules come from:
 * most creatures died to the post-catch recast, Grinch to rod hits, the big ones to a Dark Claymore (Yeti in Loadout 8).
 */
class BehaviorLearnerRecordingTest {
    @Test
    fun `the hotspot recording is learned with recast kills and no posture artefacts`() {
        val path = System.getenv("LBQ_RECORDING")
        assumeTrue("LBQ_RECORDING is not set", ! path.isNullOrBlank())
        val file = File(path)
        assumeTrue("no recording at $path", file.isFile)
        val p = BehaviorLearner.learn(listOf(file))
        println(summary(p))
        println(weapons(p))

        assertTrue(p.kills > 550, "kills ${p.kills}")
        assertTrue(p.catchRecastMs.size >= 500, "catch recasts ${p.catchRecastMs.size}")
        assertTrue(median(p.catchRecastMs) in 195 .. 205, "catch recast median ${median(p.catchRecastMs)}")
        assertTrue(p.recastThroughCatches)
        assertEquals(BehaviorProfile.LEARNER, p.learner)
        // the posture artefacts of the first learner (it learned 176 still-view reactions, 169 turns, nearly all flat,
        // and 424 aim samples at a distance of 0 from this recording): standing under the bobber, most fights need no turn
        assertTrue(p.spawnReactionMs.size < 20, "still-view spawn reactions ${p.spawnReactionMs.size}")
        assertTrue(p.turns.size < 30, "turns ${p.turns.size}")
        assertTrue(p.turns.all { it.progress.isNotEmpty() && it.progress.max() >= 0.8f }, "flat turns")
        assertTrue(p.noTurnFights > 150, "fights without a turn ${p.noTurnFights}")
        assertTrue(p.meleeDistances.none { it <= 0f }, "melee distances of 0")
        assertTrue(p.aimHeights.size < 100, "aim samples ${p.aimHeights.size}")
        // rod hits only: claymore swings after a swap came 1-3 s after the recast
        assertTrue(median(p.bobberMissMs) in 450 .. 800, "bobber miss median ${median(p.bobberMissMs)}")
        assertTrue(median(p.firstHitMs) < 900, "first rod hit median ${median(p.firstHitMs)}")

        val grinch = assertNotNull(p.habits.getValue("Grinch").suggestion())
        assertEquals(KillAction.ROD, grinch.action)
        assertEquals(AttackMode.AUTO, grinch.attack, "Grinch is meleed, not killed by the bobber")
        val steve = assertNotNull(p.habits.getValue("Frozen Steve").suggestion())
        assertEquals(KillAction.ROD, steve.action)
        assertEquals(AttackMode.RIGHT, steve.attack, "recast first")
        val nutcracker = assertNotNull(p.habits.getValue("Nutcracker").suggestion())
        assertEquals(KillAction.WEAPON, nutcracker.action)
        assertEquals("DARK_CLAYMORE", nutcracker.weaponId)
        assertEquals(0, nutcracker.loadout)
        val yeti = assertNotNull(p.habits.getValue("Yeti").suggestion())
        assertEquals(KillAction.WEAPON, yeti.action)
        assertEquals(8, yeti.loadout)

        // item roles: the rod casts, the claymore is a weapon well above the hotspot's high chance of a drop anyway
        assertTrue("ROD_OF_THE_SEA" in p.fishingRods, "fishing rods ${p.fishingRods}")
        assertFalse("DARK_CLAYMORE" in p.fishingRods)
        assertTrue(WeaponPlanner.isWeapon(p, "DARK_CLAYMORE"), "claymore ${p.weaponStyles["DARK_CLAYMORE"]?.let(::style)}")

        // nothing splits off a creature at the hotspot: the 3.4.0 tracker's `aCorrupted Grincha`, a Grinch you caught 699 ms
        // after a Frog Man died next to it, is no child, and the Frog Man no parent to hold Auto Fishing after
        assertNull(p.habits["aCorrupted Grincha"])
        assertEquals(emptyMap<String, String>(), p.habits.filterValues { it.parent.isNotEmpty() }.mapValues { it.value.parent }, "parents")
    }

    /**
     * The 41-minute Backwater Bayou and Crimson Isle lava session (path in `LBQ_RECORDING_BAYOU`, skipped without it):
     * the rod within 3 blocks, the Flaming Flay held from farther away, the Hyperion as an area attack close up, and
     * Bayou Sludglings split off Bayou Sludges.
     */
    @Test
    fun `the bayou recording learns the flay, the hyperion and the sludglings`() {
        val path = System.getenv("LBQ_RECORDING_BAYOU")
        assumeTrue("LBQ_RECORDING_BAYOU is not set", ! path.isNullOrBlank())
        val file = File(path)
        assumeTrue("no recording at $path", file.isFile)
        val p = BehaviorLearner.learn(listOf(file))
        println(summary(p))
        println(weapons(p))

        assertTrue("ROD_OF_THE_SEA" in p.fishingRods && "HELLFIRE_ROD" in p.fishingRods, "fishing rods ${p.fishingRods}")
        assertFalse("FLAMING_FLAY" in p.fishingRods)
        assertFalse("HYPERION" in p.fishingRods)

        val flay = p.weaponStyles.getValue("FLAMING_FLAY")
        assertEquals(ClickStyle.HOLD, flay.style)
        assertTrue(quantile(flay.reach, 0.9) >= 8f, "flay reach p90 ${quantile(flay.reach, 0.9)}")
        assertTrue(WeaponPlanner.isWeapon(p, "FLAMING_FLAY"), style(flay))
        assertTrue(WeaponPlanner.isWeapon(p, "HYPERION"), style(p.weaponStyles.getValue("HYPERION")))
        assertFalse(WeaponPlanner.isWeapon(p, "ASPECT_OF_THE_VOID"))
        val flayPlan = assertNotNull(WeaponPlanner.plan(p, "FLAMING_FLAY"))
        assertTrue(flayPlan.reach >= 9.5, "$flayPlan")
        val hyperion = assertNotNull(WeaponPlanner.plan(p, "HYPERION"))
        assertTrue(hyperion.aimDeg >= 90.0, "$hyperion")
        assertTrue(hyperion.viewPitch >= 85f, "$hyperion")

        val sludgling = assertNotNull(p.habits["Bayou Sludgling"], "no Bayou Sludgling habit")
        assertEquals("Bayou Sludge", sludgling.parent)
        assertEquals(p.habits.getValue("Bayou Sludge").group, sludgling.group)
        // Bayou Sludglings after 12 of 24 Sludge deaths, Baby Magma Slugs after 18 of 30 Magma Slug deaths; 3 babies that
        // came up as your own babies died do not make a baby its own parent
        assertEquals(mapOf("Bayou Sludgling" to "Bayou Sludge", "Baby Magma Slug" to "Magma Slug"), p.habits.filterValues { it.parent.isNotEmpty() }.mapValues { it.value.parent })
        // Baby Magma Slugs that sank and were killed 11-58 s later are no Flay kills from 25 blocks above
        assertNull(p.habits.getValue("Baby Magma Slug").choices["vfar/above"], "${p.habits.getValue("Baby Magma Slug").choices}")

        val available = setOf(WeaponPlanner.ROD, "FLAMING_FLAY", "HYPERION")
        fun choose(name: String, hd: Double, dy: Double): String? {
            val h = p.habits[name]
            return WeaponPlanner.choose(p, name, h?.group.orEmpty(), h?.maxHp ?: 0.0, hd, dy, available)?.option
        }
        assertEquals(WeaponPlanner.ROD, choose("Trash Gobbler", 2.0, 0.0))
        assertEquals("FLAMING_FLAY", choose("Manta Ray", 8.0, 0.0))
        assertEquals("FLAMING_FLAY", choose("Lava Leech", 8.0, - 2.0))
        assertEquals("FLAMING_FLAY", choose("Alligator", 2.0, 0.0))
        assertEquals("HYPERION", choose("Baby Magma Slug", 1.0, 0.0))
        assertEquals("HYPERION", choose("Moogma", 2.0, 0.0))
        assertNotEquals(WeaponPlanner.ROD, choose("Bayou Sludgling", 2.0, 1.0))
        for (name in listOf("Fire Eel", "Moogma", "Trash Gobbler")) assertNull(choose(name, 5.0, - 25.0), "$name 25 blocks below")
        // the rod takes more than 8 hits on a Snapping Turtle (10 % a hit) and you fought it with the Flay: no rod even
        // near, and the rules window says so
        assertNotEquals(WeaponPlanner.ROD, choose("Snapping Turtle", 2.0, 0.0))
        val turtle = p.habits.getValue("Snapping Turtle")
        assertFalse(WeaponPlanner.summary(p, "Snapping Turtle", turtle.group).startsWith("Rod"), WeaponPlanner.summary(p, "Snapping Turtle", turtle.group))
        // Inkling's rod hits are no clicks of the weapon it suggests
        val inkling = assertNotNull(p.habits.getValue("Inkling").suggestion())
        if (inkling.action == KillAction.WEAPON) assertEquals(AttackMode.RIGHT, inkling.attack, "${inkling.weaponId}")
    }

    /** Both recordings at once, as the folder relearn of your profile reads them (skipped without both paths). */
    @Test
    fun `both recordings together know only the bayou's children`() {
        val files = listOf(System.getenv("LBQ_RECORDING"), System.getenv("LBQ_RECORDING_BAYOU")).map { path ->
            assumeTrue("LBQ_RECORDING and LBQ_RECORDING_BAYOU are not both set", ! path.isNullOrBlank())
            File(path).also { assumeTrue("no recording at $path", it.isFile) }
        }
        val p = BehaviorLearner.learn(files)
        assertEquals(mapOf("Bayou Sludgling" to "Bayou Sludge", "Baby Magma Slug" to "Magma Slug"), p.habits.filterValues { it.parent.isNotEmpty() }.mapValues { it.value.parent })
        assertNull(p.habits["aCorrupted Grincha"])
    }

    /**
     * A recording made with the recorder-only jar (someone else's, path in `LBQ_RECORDER_RECORDING`, skipped without it):
     * it says so in its session line, is learned when given explicitly, and is left out of the folder relearn of your
     * own profile. Prints what it would teach.
     */
    @Test
    fun `a recording from the recorder-only jar is learned only when given explicitly`() {
        val path = System.getenv("LBQ_RECORDER_RECORDING")
        assumeTrue("LBQ_RECORDER_RECORDING is not set", ! path.isNullOrBlank())
        val file = File(path)
        assumeTrue("no recording at $path", file.isFile)
        val session = com.google.gson.JsonParser.parseString(file.useLines { it.first() }).asJsonObject
        assertEquals("session", session["e"].asString)
        assertEquals(FishingRecorder.RECORDER_BUILD, session["build"]?.asString, "session line $session")
        assertTrue(session["mod"].asString.startsWith("recorder-"), "session line $session")

        val p = BehaviorLearner.learn(listOf(file))
        println(summary(p))
        assertEquals(1, p.sessions)
        assertTrue(p.hasData)
        assertEquals(0, BehaviorLearner.learn(listOf(file), skipRecorderBuild = true).sessions)
    }

    /** The recordings in `LBQ_RECORDINGS_DIR` (skipped without it), in name order. */
    private fun recordingsDir(): List<File> {
        val path = System.getenv("LBQ_RECORDINGS_DIR")
        assumeTrue("LBQ_RECORDINGS_DIR is not set", ! path.isNullOrBlank())
        val files = File(path).listFiles { f -> f.extension == "jsonl" }?.sortedBy { it.name }.orEmpty()
        assumeTrue("no recordings in $path", files.isNotEmpty())
        return files
    }

    private fun near(expected: Double, actual: Double, tolerance: Double, what: String) =
        assertTrue(abs(actual - expected) <= tolerance, "$what: %.4f, expected %.4f ± %.4f".format(actual, expected, tolerance))

    /**
     * The four reference recordings (3 Nov Jerry hotspot, 5 Nov Bayou and lava, two 7 Nov 3.5.0 sessions; about 100 min) in
     * `LBQ_RECORDINGS_DIR`: the learned motor, turn and turn-back statistics are the ones the motor analysis measured on
     * them (humanize/critic.md and the amendments' reference column, from segment.js, p1_kill.js, p3_aimfights.js and
     * back2.js), and the planning parameters come back as their defaults.
     */
    @Test
    fun `the reference recordings give the measured motor, turn and timing statistics`() {
        val files = recordingsDir()
        assumeTrue("the four reference recordings", files.size == 4)
        val p = BehaviorLearner.learn(files)
        val m = p.motor
        println(m)
        println(policy(p))
        println(p.timing.streaks.entries.joinToString("\n") { (k, s) -> "$k: n=${s.n} thr=%.0f pi=%.3f stay=%.2f (%d) enter=%.3f lag1=%.2f".format(s.threshold, s.slowShare, s.stay, s.slowPairs, s.enter, s.lag1) })
        println("melee gaps=${p.timing.meleeGaps} lag1=%.2f rho=%.2f".format(p.timing.meleeLag1, p.timing.meleeRho))

        // movements of 6 degrees or more, not cut by a recording's end: 561 (881 of any size, 2 artefacts left out)
        near(561.0, m.moves.toDouble(), 3.0, "moves")
        // 871 sub-strokes, 848 of them of 2 degrees or more (the ones the stroke law is fitted to)
        near(848.0, m.strokes.toDouble(), 10.0, "sub-strokes")
        listOf(140, 129, 117, 114, 61).forEachIndexed { i, ref -> near(ref.toDouble(), m.binMoves[i].toDouble(), 3.0, "moves in bin $i") }
        // whole movements: dur = 34 + 111 log2(1 + A/6), ln-sd 0.50, 8.6 % slower than twice that, 4.8 % faster than half
        near(34.0, m.moveLawA, 10.0, "move law a")
        near(111.0, m.moveLawB, 6.0, "move law b")
        near(0.50, m.moveSigma, 0.02, "move ln-sd")
        near(0.086, m.slowShare, 0.01, "slow share")
        near(0.048, m.fastShare, 0.01, "fast share")
        // sub-strokes: 88 + 58 log2(1 + A/6), ln-sd 0.40
        near(88.0, m.strokeLawA, 6.0, "stroke law a")
        near(58.0, m.strokeLawB, 4.0, "stroke law b")
        near(1.0, m.strokeK, 0.03, "stroke k")
        near(0.40, m.strokeSigma, 0.02, "stroke ln-sd")
        // multi-stroke share per bin: 20 / 29 / 37 / 46 / 55 %
        listOf(0.20, 0.29, 0.37, 0.46, 0.55).forEachIndexed { i, ref -> near(ref, m.multiShare[i], 0.02, "multi-stroke share, bin $i") }
        // overshoot past 1 degree in 13.3 % of movements of 20 degrees or more (9.0 % past 3), by p50 9.4 % of the way
        near(0.133, m.overshootShare, 0.01, "overshoot > 1")
        near(0.090, m.overshoot3Share, 0.01, "overshoot > 3")
        near(0.094, m.overshootSizeP50, 0.01, "overshoot size")
        near(0.49, m.tpkP50, 0.02, "time to peak p50")
        near(0.19, m.tpkP10, 0.03, "time to peak p10")
        near(0.74, m.tpkP90, 0.03, "time to peak p90")
        near(0.78, m.firstShareP50, 0.03, "first stroke share p50")
        // your ways back of 20 deg or more in two strokes: 8 turn backs (first share p50 0.75, p90 0.87) and 5 from a look,
        // returns and turns back to the fight (p50 0.51, p90 0.88)
        assertEquals(8, m.backFirstN, "turn backs")
        near(0.751, m.backFirstP50, 0.005, "turn backs' first stroke share p50")
        near(0.87, m.backFirstP90, 0.01, "turn backs' first stroke share p90")
        assertEquals(5, m.returnFirstN, "ways back from a look")
        near(0.511, m.returnFirstP50, 0.005, "ways back from a look, first stroke share p50")
        near(0.88, m.returnFirstP90, 0.01, "ways back from a look, first stroke share p90")
        near(0.58, m.overlapShare, 0.04, "overlapping strokes")
        near(0.07, m.crossP50, 0.02, "cross-track error p50")
        near(33.0, m.lagP50Ms, 5.0, "axis lag p50")
        near(225.0, m.lagP90Ms, 30.0, "axis lag p90")
        near(0.77, m.straightP10, 0.03, "straightness p10")
        near(0.98, m.straightP50, 0.01, "straightness p50")
        // peaks: p99 1208 / 1394 deg/s, his ceiling 1679 / 1685 once the warp and the 11 ms tick are left out
        near(1208.0, m.gcP99, 40.0, "great-circle peak p99")
        near(1394.0, m.mouseP99, 60.0, "mouse peak p99")
        assertTrue(m.gcMax <= 1680.0 && m.mouseMax <= 1686.0, "no artefact left: ${m.gcMax} / ${m.mouseMax}")
        near(200.0, m.gapP50Ms, 20.0, "gap between movements p50")
        listOf(79.0, 159.0, 313.0, 485.0, 774.0).forEachIndexed { i, ref -> near(ref, m.binPeakP50[i], ref * 0.05, "peak p50, bin $i") }
        // rest: 2.46 % of the ticks outside movements change, 41 % of those right after one that did
        near(0.0246, m.restChanged, 0.002, "rest changed share")
        near(0.41, m.restFollow, 0.04, "rest changes in a row")
        near(0.47, m.restBoth, 0.03, "rest changes on both axes")
        near(0.31, m.restYaw, 0.03, "rest changes on yaw only")
        // still on 95.2 / 90.2 / 95.4 / 90.3 % of fight ticks: 92.5 % over all four
        near(0.925, m.stillShare, 0.005, "still share")

        // the planning parameters: the defaults the motor model was tuned to with these four recordings
        val d = BehaviorProfile.Motor()
        near(d.lawA, m.lawA, 3.0, "planned law a")
        near(d.lawB, m.lawB, 2.0, "planned law b")
        near(d.sigma, m.sigma, 0.02, "planned sigma")
        d.multiP.forEachIndexed { i, ref -> near(ref, m.multiP[i], 0.05, "planned multi-stroke probability, bin $i") }
        near(d.firstMu, m.firstMu, 0.03, "planned first stroke share")
        near(d.backFirstMu, m.backFirstMu, 0.01, "planned first stroke share of a turn back")
        near(d.returnFirstMu, m.returnFirstMu, 0.01, "planned first stroke share of a way back from a look")
        near(d.slowP, m.slowP, 0.015, "planned slow probability")
        near(d.axisLagMs, m.axisLagMs, 3.0, "planned axis lag")
        near(d.tauMean, m.tauMean, 0.02, "planned time to peak")
        near(d.crossSd, m.crossSd, 0.02, "planned cross-track sd")
        near(d.overlapP, m.overlapP, 0.04, "planned overlap")
        near(d.restStart, m.restStart, 0.0015, "planned rest nudge start")
        // the hand's nudges: 2754 axes of 1-14 steps, 44 % of them one step; the yaw's near the pole: 882, median 1, a long tail
        near(2754.0, m.restHandAxes.toDouble(), 30.0, "nudge axes of the hand")
        near(882.0, m.restPoleAxes.toDouble(), 20.0, "yaw nudge axes near the pole")
        d.restSteps.forEachIndexed { i, ref -> near(ref, m.restSteps.getOrElse(i) { 0.0 }, 0.003, "share of $i + 1 steps") }
        near(d.restPoleP50, m.restPoleP50, 0.05, "pole nudge median")
        near(d.restPoleSigma, m.restPoleSigma, 0.05, "pole nudge ln-sd")
        near(d.capGc, m.capGc, 40.0, "great-circle cap")
        near(d.capMouse, m.capMouse, 60.0, "mouse cap")
        near(d.poleFactor, m.poleFactor, 0.2, "pole factor")
        assertEquals(d.lookDownFactor, m.lookDownFactor, "12 look-downs are too few: the default stays")

        // turning toward your creatures: 111 of 452 that were 15 degrees or more off; rod melee 10 %, the rod's recast 27 %,
        // the Flay held 70 %
        val all = p.policy.all
        near(452.0, all.creatures.toDouble(), 10.0, "creatures off by 15 degrees")
        near(111.0, all.turned.toDouble(), 6.0, "turned toward")
        near(0.10, p.policy.classes.getValue("ROD_OF_THE_SEA:L").rate, 0.03, "rod melee turn rate")
        near(0.27, p.policy.classes.getValue("ROD_OF_THE_SEA:R").rate, 0.04, "rod recast turn rate")
        near(0.70, p.policy.classes.getValue("FLAMING_FLAY:Rh").rate, 0.06, "held flay turn rate")
        // fights with a first turn: 36; latency after the swap p50 301 ms, after the spawn (no swap) p50 4125 ms; the
        // turn leaves p50 15 degrees; corrections start at p50 56, end at 16, come 352 ms after the previous movement
        near(36.0, p.policy.turnFights.toDouble(), 3.0, "fights with a turn")
        near(0.162, p.policy.changedTurned, 0.01, "changed ticks in fights with a turn")
        near(0.026, p.policy.changedOther, 0.005, "changed ticks in fights without")
        near(301.0, all.swapLatency.p50, 60.0, "first turn after the swap p50")
        near(4125.0, all.spawnLatency.p50, 800.0, "first turn after the spawn p50")
        near(15.0, all.stopErr.p50, 3.0, "first turn's error left p50")
        near(56.0, all.corrStart.p50, 6.0, "correction start error p50")
        near(16.0, all.corrEnd.p50, 4.0, "correction end error p50")
        near(352.0, all.corrGap.p50, 60.0, "correction gap p50")
        assertTrue(all.slack.n > 20, "slack ${all.slack}")
        // the turn back: 38 of the 51 fights that ended more than 8 degrees off (of the 49 the next fight did not cut short
        // within 3 s), p50 426 ms after the last death, leaving p50 7.5 degrees (5.1 at the next cast)
        near(889.0, p.policy.backFights.toDouble(), 10.0, "fights for the turn back")
        near(51.0, p.policy.backBeyond.toDouble(), 3.0, "fights ending more than 8 degrees off")
        near(38.0, p.policy.backTurned.toDouble(), 3.0, "turned back")
        near(2.0, p.policy.backInterrupted.toDouble(), 2.0, "cut short by the next fight")
        near(0.78, p.policy.backRate, 0.03, "turn back rate")
        near(426.0, p.policy.backLatency.p50, 60.0, "turn back latency p50")
        near(7.5, p.policy.backRest.p50, 1.5, "turn back rest error p50")
        near(5.1, p.policy.backCastErr.p50, 1.5, "error at the next cast p50")

        // timings keep their order: streak and rhythm statistics from them
        for (family in listOf("biteReactionMs", "catchRecastMs", "afterKillMs", "firstHitMs", "rodToCastMs")) {
            val s = assertNotNull(p.timing.streaks[family], family)
            assertTrue(s.n >= 30 && s.slowShare in 0.0 .. 0.5 && s.stay in 0.0 .. 1.0 && s.enter in 0.0 .. 1.0, "$family: ${s.n} ${s.slowShare} ${s.stay} ${s.enter}")
        }
        // slow share and P(slow after slow) as the timing analysis measured them with the learner's definitions: after kill
        // 18.9 % / 35 %, first hit 9.4 % / 36 %, rod to cast 20.6 % / 30 %
        for ((family, ref) in listOf("afterKillMs" to (0.189 to 0.35), "firstHitMs" to (0.094 to 0.36), "rodToCastMs" to (0.206 to 0.30))) {
            val s = p.timing.streaks.getValue(family)
            near(ref.first, s.slowShare, 0.03, "$family slow share")
            near(ref.second, s.stay, 0.1, "$family slow after slow")
        }
        // bite reactions come independently (lag-1 of ln 0.06-0.08)
        near(0.07, p.timing.streaks.getValue("biteReactionMs").lag1, 0.06, "bite reaction lag-1")
        near(0.56, p.timing.meleeLag1, 0.15, "rod melee lag-1")
        assertTrue(p.timing.meleeRho in 0.5 .. 0.95, "melee rho ${p.timing.meleeRho}")

        // misses next to your creatures (miss.js): 130 of 679 rod swings (2 with the Hellfire Rod) hit air or a block, 14 of
        // 106 Dark Claymore swings and 14 of 49 Flay swings
        println("rod swings ${p.rodMeleeClicks} (${p.rodMeleeMisses} missed), weapon swings ${p.weaponMeleeClicks} (${p.weaponMeleeMisses} missed), menu close to click ${p.menuCloseToClickMs}")
        near(677.0, p.rodMeleeClicks.toDouble(), 20.0, "rod swings next to your creatures")
        near(0.19, p.missShare(rod = true), 0.02, "rod miss share")
        near(0.18, p.missShare(rod = false), 0.04, "weapon miss share")
        // a loadout menu closed in a fight to the first click: 5 of them, 102-533 ms (close.js)
        assertTrue(p.menuCloseToClickMs.size in 4 .. 7 && p.menuCloseToClickMs.all { it in 90 .. 600 }, "${p.menuCloseToClickMs}")
    }

    /**
     * The looks (turns/SPEC §1.3-1.4, fit.js, a5_free.js, a6_after.js and a9_stop.js on the four reference recordings): 101
     * of 356 creatures turned toward with the overhead ones (10 of 96) apart, first turns by age bin 23/12/26/17/14/9 over
     * 538/423/487/505/381/708 creature-seconds, 10 of the weapon plans' 53 free creatures looked at within 2 s, looks
     * stopping at p50 0.25 of the error, and a return after 55 of the 70 first turns no plan needed, p50 749 ms later.
     */
    @Test
    fun `the reference recordings give the looks' rates, stops and returns`() {
        val files = recordingsDir()
        assumeTrue("the four reference recordings", files.size == 4)
        val p = BehaviorLearner.learn(files)
        val l = p.policy.looks
        println("looks all ${stats(l.all)}\n" + l.classes.entries.joinToString("\n") { (k, s) -> "  $k ${stats(s)}" } +
            "\noverhead ${l.overheadTurned}/${l.overhead} stop ${l.stopShare} back ${l.backTurned}/${l.backLooks} = %.3f latency ${l.backLatency} gap ${l.backGap}".format(l.backRate))
        assertEquals(356, l.all.creatures, "counted creatures, overhead ones apart")
        assertEquals(101, l.all.turned)
        assertEquals(96, l.overhead, "creatures right above or below you")
        assertEquals(10, l.overheadTurned)
        assertEquals(p.policy.all.creatures, l.all.creatures + l.overhead, "the same creatures the turn rate counts")
        assertEquals(listOf(23, 12, 26, 17, 14, 9), l.all.events)
        listOf(537.9, 423.0, 486.7, 504.8, 380.7, 708.0).forEachIndexed { i, ref -> near(ref, l.all.exposureS[i], 1.0, "creature-seconds at risk, bin $i") }
        assertEquals(53, l.all.freeAtFirst, "weapon plans' creatures within their tolerance at 300 ms")
        assertEquals(10, l.all.freeAtFirstTurned, "of those, looked at within 2 s")
        // the Flay's click 7 of 12, its hold 3 of 24, rod melee 0 of 17
        assertEquals(12 to 7, l.classes.getValue("FLAMING_FLAY:R").let { it.freeAtFirst to it.freeAtFirstTurned })
        assertEquals(24 to 3, l.classes.getValue("FLAMING_FLAY:Rh").let { it.freeAtFirst to it.freeAtFirstTurned })
        assertEquals(0, l.classes.getValue("ROD_OF_THE_SEA:L").freeAtFirstTurned)
        assertEquals(101, l.stopShare.n, "distinct moves toward a counted creature")
        near(0.06, l.stopShare.p10, 0.01, "stop share p10")
        near(0.25, l.stopShare.p50, 0.01, "stop share p50")
        near(0.53, l.stopShare.p90, 0.02, "stop share p90")
        assertEquals(70, l.backLooks, "first turns no plan needed")
        assertEquals(55, l.backTurned, "turned back within 5 s")
        near(0.786, l.backRate, 0.001, "return rate")
        near(151.0, l.backLatency.p10, 15.0, "return latency p10")
        near(749.0, l.backLatency.p50, 30.0, "return latency p50")
        near(3410.0, l.backLatency.p90, 150.0, "return latency p90")
        // the way back came p50 252 ms after the view last stopped, the look's own rest or a move of yours after it
        assertEquals(55, l.backGap.n)
        near(149.0, l.backGap.p10, 15.0, "way back after the last rest p10")
        near(252.0, l.backGap.p50, 15.0, "way back after the last rest p50")
        near(551.0, l.backGap.p90, 40.0, "way back after the last rest p90")

        // the killer's table: about 0.03-0.05 looks a second after the first 2 s, a first-moment look at a Flay click 0.47 of
        // the time, a held Flay 0.14, rod melee 0.04
        val t = ViewPolicy.Looks.Table.of(l)
        listOf(0.043, 0.028, 0.053, 0.034, 0.037, 0.013).forEachIndexed { i, ref -> near(ref, t.rate(null, BehaviorProfile.Looks.AGE_BINS[i]), 0.001, "all-class rate, bin $i") }
        near(0.47, t.first("FLAMING_FLAY:R"), 0.01, "first-moment look, Flay click")
        near(0.14, t.first("FLAMING_FLAY:Rh"), 0.01, "first-moment look, Flay hold")
        near(0.04, t.first("ROD_OF_THE_SEA:L"), 0.01, "first-moment look, rod melee")
        near(0.0, t.rate("ROD_OF_THE_SEA:R", 1000), 0.005, "no look at a bobber creature in its first 2 s")
    }

    /**
     * looks/SPEC §4.2: your looks around in the four reference recordings, against the analysis scripts' numbers (57
     * outward looks, 18 / 1 / 27 / 11 by phase, 22 of 24 looks from home back within 10 s after a dwell of 1250 ms at the
     * median, 2.7 times as many right after a death, 12 % at where your creature died, one stroke more often and a little
     * faster than your other moves), within the spec's tolerances: the Kotlin segmentation and runs are not the scripts' to
     * the line.
     */
    @Test
    fun `the reference recordings give your looks around, their returns and their motion`() {
        val files = recordingsDir()
        assumeTrue("the four reference recordings", files.size == 4)
        val p = BehaviorLearner.learn(files)
        val g = p.glances
        fun q(xs: List<Double>, at: Double) = com.laybalt.features.impl.fishing.ViewMotion.quantile(xs, at)
        println("glances: looks ${g.looks} counts ${g.counts} home ${g.homeCounts} minutes ${g.minutes.map { "%.1f".format(it) }} death ${g.deathCounts} / ${g.deathMinutes.map { "%.2f".format(it) }}")
        println("  home ${g.homeReturned}/${g.homeRisk} away ${g.awayReturned}/${g.awayRisk} chained ${g.chained} dead ${g.deadSpotHits}/${g.deadSpotCandidates} chance %.1f".format(g.deadSpotChance))
        println("  amp p50 %.1f dwell p50 %.0f (n ${g.dwellMs.size}) err p50 %.1f (n ${g.returnErr.size}) multi %.2f / %.2f slow %.2f ln %.2f sd %.2f law p50 %.2f over %.2f".format(
            q(g.amp.map { it.toDouble() }, 0.5), q(g.dwellMs.map { it.toDouble() }, 0.5), q(g.returnErr.map { it.toDouble() }, 0.5),
            g.multiShare, g.multiExpected, g.slowShare, g.lnMean, g.lnSd, g.lawP50, g.overShare))
        println("  rates ${g.rate.map { "%.3f".format(it) }} chainP %.2f returnHome %.2f away %.2f boost %.2f deadSpot %.3f multi %.2f slow %.2f duration %.2f".format(
            g.chainP, g.returnHomeP, g.returnAwayP, g.deathBoost, g.deadSpotP, g.multiScale, g.slowScale, g.durationFactor))
        println("  kinds ${g.kinds} rot minutes %.1f".format(g.rotMinutes))
        fun within(ref: Double, v: Double, share: Double, abs: Double, what: String) =
            assertTrue(kotlin.math.abs(v - ref) <= maxOf(share * ref, abs), "$what: %.3f, reference %.3f".format(v, ref))
        within(57.0, g.looks.toDouble(), 0.20, 0.0, "outward looks")
        listOf(18, 1, 27, 11).forEachIndexed { i, ref -> within(ref.toDouble(), g.counts[i].toDouble(), 0.25, 3.0, "looks in ${BehaviorProfile.Glances.PHASES[i]}") }
        listOf(13, 1, 16, 1).forEachIndexed { i, ref -> within(ref.toDouble(), g.homeCounts[i].toDouble(), 0.25, 3.0, "looks from home in ${BehaviorProfile.Glances.PHASES[i]}") }
        listOf(34.0, 5.0, 33.3, 2.9).forEachIndexed { i, ref -> within(ref, g.minutes[i], 0.10, 0.0, "minutes in place in ${BehaviorProfile.Glances.PHASES[i]}") }
        assertTrue(g.homeReturned.toDouble() / g.homeRisk in 0.8 .. 1.0, "looks from home back within 10 s ${g.homeReturned}/${g.homeRisk}")
        within(1250.0, q(g.dwellMs.map { it.toDouble() }, 0.5), 0.25, 0.0, "dwell p50")
        within(25.0, q(g.amp.map { it.toDouble() }, 0.5), 0.20, 0.0, "amplitude p50")
        within(4.4, q(g.returnErr.map { it.toDouble() }, 0.5), 0.0, 1.5, "return error p50")
        // 15 looks start within 3 s of the one before (the spec's 0.26), but only 8 of them from where it ended: the rest
        // follow a return or another move, a new look rather than a chain
        assertEquals(8, g.chained, "looks chained from the one before")
        within(0.14, g.chainP, 0.0, 0.03, "chain share")
        assertTrue(g.deathBoost in 1.8 .. 3.6, "death boost %.2f".format(g.deathBoost))
        within(0.12, g.deadSpotP, 0.0, 0.06, "dead spot share")
        within(0.42, g.multiScale, 0.0, 0.15, "multi-stroke scale")
        within(0.87, g.durationFactor, 0.0, 0.08, "duration factor")
        // shrunk toward the pooled 0.76 a minute with a quarter minute ([BehaviorProfile.Glances.PRIOR_MIN])
        listOf(0.53, 0.23, 0.51, 3.53).forEachIndexed { i, ref -> within(ref, g.rate[i], 0.30, 0.0, "rate in ${BehaviorProfile.Glances.PHASES[i]}") }
        assertTrue(g.learned)
    }

    private fun stats(s: BehaviorProfile.LookStats) =
        "${s.turned}/${s.creatures} events ${s.events} exposure ${s.exposureS.map { "%.1f".format(it) }} first ${s.freeAtFirstTurned}/${s.freeAtFirst}"

    /** The still share per reference recording: 95.2 / 90.2 / 95.4 / 90.3 % of fight ticks. */
    @Test
    fun `each reference recording has its own still share`() {
        val files = recordingsDir()
        assumeTrue("the four reference recordings", files.size == 4)
        listOf(0.952, 0.902, 0.954, 0.903).zip(files).forEach { (ref, file) ->
            near(ref, BehaviorLearner.learn(listOf(file)).motor.stillShare, 0.005, "still share of ${file.name}")
        }
    }

    private fun policy(p: Profile) = buildString {
        val pol = p.policy
        appendLine("all: creatures=${pol.all.creatures} turned=${pol.all.turned} swap ${pol.all.swapLatency} spawn ${pol.all.spawnLatency} stop ${pol.all.stopErr} corr ${pol.all.corrStart} -> ${pol.all.corrEnd} gap ${pol.all.corrGap} slack ${pol.all.slack}")
        for ((cls, t) in pol.classes) appendLine("  $cls: ${t.turned}/${t.creatures} = %.2f stop ${t.stopErr} slack ${t.slack}".format(t.rate))
        appendLine("turnFights=${pol.turnFights} changed %.3f / %.3f".format(pol.changedTurned, pol.changedOther))
        appendLine("back: fights=${pol.backFights} beyond=${pol.backBeyond} turned=${pol.backTurned} interrupted=${pol.backInterrupted} none=${pol.backNone} rate=%.2f latency ${pol.backLatency} rest ${pol.backRest} cast ${pol.backCastErr} lag ${pol.backCastLag}".format(pol.backRate))
    }

    private fun median(a: List<Int>) = a.sorted().let { if (it.isEmpty()) 0 else it[it.size / 2] }

    private fun quantile(a: List<Float>, q: Double) = a.sorted().let { if (it.isEmpty()) 0f else it[minOf(it.size - 1, (it.size * q).toInt())] }

    private fun style(s: WeaponStyle) = "${s.style} uses=${s.uses} effective=${s.effective} (%.0f%%, chance %.0f%%) L/R/H=${s.left}/${s.right}/${s.held} onTarget=${s.onTarget} "
        .format(100.0 * s.effective / maxOf(1, s.uses), 100.0 * s.chance) +
        "reach p50/p95=%.1f/%.1f dy p5/p95=%.1f/%.1f err p50/p75=%.0f/%.0f pitch p25/p50=%.0f/%.0f holds=${s.holdMs.size} hold p50=${median(s.holdMs)} gaps=${s.gapMs.size} gap p50=${median(s.gapMs)}".format(
            quantile(s.reach, 0.5), quantile(s.reach, 0.95), quantile(s.dy, 0.05), quantile(s.dy, 0.95), quantile(s.aimErr, 0.5), quantile(s.aimErr, 0.75),
            quantile(s.pitch, 0.25), quantile(s.pitch, 0.5)
        )

    private fun weapons(p: Profile) = buildString {
        appendLine("fishingRods=${p.fishingRods} hotbar=${p.hotbarItems}")
        for ((id, s) in p.weaponStyles) appendLine("$id: ${style(s)} weapon=${WeaponPlanner.isWeapon(p, id)} plan=${WeaponPlanner.plan(p, id)}")
        appendLine("choices=${p.choices}")
        for ((name, h) in p.habits) appendLine(
            "$name: group=${h.group} parent=${h.parent} maxHp=${h.maxHp} rodHitShare n=${h.rodHitShare.size} mean=%.3f killsBy=${h.killsBy} choices=${h.choices} -> ${WeaponPlanner.summary(p, name, h.group)}"
                .format(h.rodHitShare.average().takeUnless { it.isNaN() } ?: 0.0)
        )
    }

    private fun summary(p: Profile) = buildString {
        fun list(name: String, a: List<Number>) {
            val s = a.map { it.toDouble() }.sorted()
            fun q(f: Double) = if (s.isEmpty()) "-" else "%.0f".format(s[minOf(s.size - 1, (s.size * f).toInt())])
            appendLine("$name: n=${s.size} p10=${q(0.1)} p50=${q(0.5)} p90=${q(0.9)}" + if (s.size in 1 .. 8) " ${s.map { "%.0f".format(it) }}" else "")
        }
        appendLine("kills=${p.kills} ownCatches=${p.ownCatches} recasts=${p.recasts} holdRecasts=${p.holdRecasts} recastThroughCatches=${p.recastThroughCatches} noTurnFights=${p.noTurnFights} turns=${p.turns.size} fishingLoadouts=${p.fishingLoadouts} topFishingLoadout=${p.topFishingLoadout}")
        list("biteReactionMs", p.biteReactionMs)
        list("castDelayMs", p.castDelayMs)
        list("catchRecastMs", p.catchRecastMs)
        list("bobberDeathMs", p.bobberDeathMs)
        list("bobberMissMs", p.bobberMissMs)
        list("spawnReactionMs", p.spawnReactionMs)
        list("firstHitMs", p.firstHitMs)
        list("clickLeftMs", p.clickLeftMs)
        list("clickLeftRodMs", p.clickLeftRodMs)
        list("clickLeftWeaponMs", p.clickLeftWeaponMs)
        list("afterKillMs", p.afterKillMs)
        list("swapReactionMs", p.swapReactionMs)
        list("weaponToLoadoutMs", p.weaponToLoadoutMs)
        list("deathToRestoreMs", p.deathToRestoreMs)
        list("menuCloseToRodMs", p.menuCloseToRodMs)
        list("rodToCastMs", p.rodToCastMs)
        list("menuOpenToClickMs", p.menuOpenToClickMs)
        list("menuClickToCloseMs", p.menuClickToCloseMs)
        list("aimHeights x100", p.aimHeights.map { it * 100 })
        list("meleeDistances x100", p.meleeDistances.map { it * 100 })
        list("turn angles", p.turns.map { it.angle })
        list("turn ms", p.turns.map { it.ms })
        for ((name, h) in p.habits) appendLine(
            "$name: seen=${h.seen} fought=${h.fought} killed=${h.killed} bobber=${h.bobberKills} sweep=${h.sweepKills} diedUnfought=${h.diedUnfought} " +
                "leftAlone=${h.leftAlone} left=${h.left} rod=${h.rodClicks} weapons=${h.weapons} loadouts=${h.loadouts} -> ${h.suggestion()?.let { "${it.action} ${it.attack} ${it.weaponId} ${it.loadout}" }}"
        )
    }
}
