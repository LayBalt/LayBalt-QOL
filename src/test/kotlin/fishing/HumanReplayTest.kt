package fishing

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.laybalt.features.impl.fishing.AutoMobKiller.FightRules
import com.laybalt.features.impl.fishing.BehaviorLearner
import com.laybalt.features.impl.fishing.BehaviorProfile
import com.laybalt.features.impl.fishing.BehaviorProfile.Dist
import com.laybalt.features.impl.fishing.BehaviorProfile.Motor
import com.laybalt.features.impl.fishing.BehaviorProfile.Profile
import com.laybalt.features.impl.fishing.ClickStyle
import com.laybalt.features.impl.fishing.CrossGap
import com.laybalt.features.impl.fishing.GlanceMeasure
import com.laybalt.features.impl.fishing.GlancePolicy
import com.laybalt.features.impl.fishing.HumanAim
import com.laybalt.features.impl.fishing.HumanAim.Owner
import com.laybalt.features.impl.fishing.Humanize
import com.laybalt.features.impl.fishing.KillRules.AttackMode
import com.laybalt.features.impl.fishing.KillRules.KillAction
import com.laybalt.features.impl.fishing.MotorModel
import com.laybalt.features.impl.fishing.MotorModel.View
import com.laybalt.features.impl.fishing.MotorParams
import com.laybalt.features.impl.fishing.TimingModel
import com.laybalt.features.impl.fishing.TimingModel.Action
import com.laybalt.features.impl.fishing.TimingModel.StreakState
import com.laybalt.features.impl.fishing.ViewMotion
import com.laybalt.features.impl.fishing.ViewPolicy
import com.laybalt.features.impl.fishing.ViewPolicy.Decision
import com.laybalt.features.impl.fishing.ViewPolicy.Need
import com.laybalt.features.impl.fishing.ViewPolicy.TurnBack
import com.laybalt.features.impl.fishing.WeaponPlanner
import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * DESIGN-HUMAN §7 with its amendments: the replay that has to pass before any game test (`LBQ_RECORDINGS_DIR`, all four
 * recordings).
 *
 * The killer's view code (ViewPolicy, the motor through HumanAim, TimingModel) fights your own creatures from your own
 * fights: their tracks, your position, your view when each stretch of fights began, and your main weapon per creature,
 * with plans, tolerances, latencies and your hand learned from the same files. It runs at the recorded ticks with 5
 * frames each, turns back to your fishing view after the fight and writes what it did as a recording of its own: your
 * lines with its view in them and its own swaps. [BehaviorLearner] then measures that recording and yours with the same
 * code (the segmentation, the turn and turn-back analysis), so your side is never a hard-coded number. Several seeds
 * are replayed, so the bot's side rests on enough moves.
 */
class HumanReplayTest {
    @Test
    fun `motor, replayed on your fights the bot's moves measure like yours`() {
        val r = replay()
        val u = r.userMotion
        val b = r.botMotion
        val t = Table("motor (user vs bot over ${r.seeds}, %.1f fight minutes each; replay and learning %.0f s)".format(u.minutes, r.seconds))
        val seeds = r.perSeed.map { it.motion }

        val uFight = 1 - r.user.motor.stillShare
        val bFight = 1 - r.bot.motor.stillShare
        t.row("fight ticks with a view change", pct(uFight), pct(bFight), "bot <= 15 %", bFight <= 0.15)
        t.row("  in fights with a turn", pct(r.user.policy.changedTurned), pct(r.bot.policy.changedTurned), "bot <= 1.5 x user",
            r.bot.policy.changedTurned <= 1.5 * r.user.policy.changedTurned)
        t.row("  in fights without", pct(r.user.policy.changedOther), pct(r.bot.policy.changedOther), "bot <= 1.5 x user",
            r.bot.policy.changedOther <= 1.5 * r.user.policy.changedOther)

        // All your moves count against the ceiling. Half of them come with walking (2.2 a minute), warps and menus (0.8),
        // tools and new fishing views (0.25), none of which the bot does: its view stays put while your lines walk on. So
        // the band is held on the rest, both sides' moves classified by the learner ([GlanceMeasure]), over all minutes
        // and over the minutes in place in a fight or the wait after its kill.
        val uRate = u.m6.size / u.minutes
        val bRate = b.m6.size / b.minutes
        t.row("moves >= 6 deg per minute, all", f2(uRate), f2(bRate), "<= 1.5 x user", bRate <= 1.5 * uRate)
        val ug = r.userKinds
        val bg = r.botKinds
        val comparable = GlanceMeasure.Kind.entries - NOT_BOT.toSet()
        val uc = ug.rate(ug.all, ug.minutes, comparable)
        val bc = bg.rate(bg.all, bg.minutes, comparable)
        t.row("  without walking, warps, menus, tools, new fishing views", f2(uc), f2(bc), "0.67-1.5 x user", bc in 0.67 * uc .. 1.5 * uc)
        val uInFight = ug.rate(ug.fight, ug.fightMinutes, comparable)
        val bInFight = bg.rate(bg.fight, bg.fightMinutes, comparable)
        t.row("  of those, per fight minute in place (%.1f / %.1f min)".format(ug.fightMinutes, bg.fightMinutes), f2(uInFight), f2(bInFight), "0.67-1.5 x user",
            bInFight in 0.67 * uInFight .. 1.5 * uInFight)
        for (kind in GlanceMeasure.Kind.entries) {
            if ((ug.fight[kind] ?: 0) + (bg.fight[kind] ?: 0) == 0) continue
            t.row("    ${kind.name.lowercase()}", f2(ug.rate(ug.fight, ug.fightMinutes, listOf(kind))), f2(bg.rate(bg.fight, bg.fightMinutes, listOf(kind))), "report", true)
        }
        val uo = ug.rate(ug.all, ug.minutes, OTHER_LOOKS)
        val bo = bg.rate(bg.all, bg.minutes, OTHER_LOOKS)
        t.row("  other looks per minute (away, back, back to the fight)", f2(uo), f2(bo), "0.6-1.6 x user", bo in 0.6 * uo .. 1.6 * uo)
        // Per fight type the floor stays a report: your Claymore and Flay fights carry your swings across the pile (sweep
        // above) and the turns that bring your kill, which the replay's kill times cut off.
        for (type in r.fightTypes.keys.sorted()) {
            val (uf, bf) = r.fightTypes.getValue(type)
            if (uf.fights < MIN_FIGHTS) continue
            val um = uf.moves.toDouble() / uf.fights
            val bm = bf.moves.toDouble() / bf.fights
            t.row("  moves per fight, $type (${uf.fights} fights)", f2(um), f2(bm), "<= 1.5 x user (0.67 x: report)", bm <= 1.5 * um)
            val (uc, bc) = r.fightComparable[type] ?: continue
            t.row("    without walking, warps, menus, tools, new fishing views", f2(uc.moves.toDouble() / max(1, uc.fights)), f2(bc.moves.toDouble() / max(1, bc.fights)), "report", true)
        }

        for (bin in Motor.BINS.indices) {
            val up = u.m6.filter { binOf(it.amp) == bin }.map { it.peak }
            val bp = b.m6.filter { binOf(it.amp) == bin }.map { it.peak }
            val name = "peak ${binName(bin)} (n ${up.size} / ${bp.size})"
            if (bp.size < MIN_BIN) {
                t.row(name, "p25-p75 %.0f-%.0f".format(q(up, 0.25), q(up, 0.75)), "too few", "", true)
                continue
            }
            val lo = q(up, 0.25) * (2 - PEAK_MARGIN)
            val hi = q(up, 0.75) * PEAK_MARGIN
            t.row("$name p50", "%.0f (%.0f-%.0f)".format(q(up, 0.5), q(up, 0.25), q(up, 0.75)), "%.0f".format(q(bp, 0.5)), "in %.0f-%.0f".format(lo, hi), q(bp, 0.5) in lo .. hi)
            // the band's 5 % margin against seed noise (amendments §7) holds here too: your max in a bin is one move of about
            // a hundred (your 60-100 deg max, 1008, is below your 30-60 deg one, 1120), and the bot's p99 swings by 5 % with the seed
            val max = up.maxOrNull() ?: 0.0
            t.row("$name p99 / max", "max %.0f".format(max), "p99 %.0f".format(q(bp, 0.99)), "bot p99 <= user max (+5 %)", q(bp, 0.99) <= max * PEAK_MARGIN)
            t.spread(seeds.map { m -> q(m.m6.filter { binOf(it.amp) == bin }.map { it.peak }, 0.99) }) { "%.0f".format(it) }
        }
        t.row("fastest tick, great-circle / mouse", "%.0f / %.0f".format(u.maxGc, u.maxMouse), "%.0f / %.0f".format(b.maxGc, b.maxMouse),
            "<= 1679 / 1685", b.maxGc <= 1679.0 && b.maxMouse <= 1685.0)

        val law = { amp: Double -> max(1.0, r.user.motor.moveLawA + r.user.motor.moveLawB * log2(1 + amp / 6.0)) }
        val ul = u.m6.filter { it.dur > 0 }.map { ln(it.dur / law(it.amp)) }
        val bl = b.m6.filter { it.dur > 0 }.map { ln(it.dur / law(it.amp)) }
        t.row("ln(duration / your law) mean", f2(ul.average()), f2(bl.average()), "user +-0.1", abs(bl.average() - ul.average()) <= 0.1)
        t.row("ln(duration / your law) sd", f2(sd(ul)), f2(sd(bl)), "user +-0.08", abs(sd(bl) - sd(ul)) <= 0.08)
        val uSlow = ul.count { it > ln(2.0) }.toDouble() / ul.size
        val bSlow = bl.count { it > ln(2.0) }.toDouble() / bl.size
        t.row("slower than 2 x the law", pct(uSlow), pct(bSlow), "5-13 %", bSlow in 0.05 .. 0.13)
        t.spread(seeds.map { m -> share(m.m6.filter { it.dur > 0 }) { ln(it.dur / law(it.amp)) > ln(2.0) } }) { pct(it) }

        val ue = u.all.filter { it.amp >= 30 }.map { it.endShare }
        val be = b.all.filter { it.amp >= 30 }.map { it.endShare }
        t.row("moves >= 30 deg: last tick / peak p50 (n ${ue.size} / ${be.size})", f2(q(ue, 0.5)), f2(q(be, 0.5)), "<= 0.3", q(be, 0.5) <= 0.3)
        t.row("  ending above half the peak", pct(share(ue) { it > 0.5 }), pct(share(be) { it > 0.5 }), "<= 10 %", share(be) { it > 0.5 } <= 0.10)

        val u20 = u.all.filter { it.amp >= 20 }
        val b20 = b.all.filter { it.amp >= 20 }
        t.row("overshoot > 1 deg (moves >= 20 deg, n ${u20.size} / ${b20.size})", pct(share(u20) { it.over > 1 }), pct(share(b20) { it.over > 1 }), "8-20 %",
            share(b20) { it.over > 1 } in 0.08 .. 0.20)
        t.spread(seeds.map { m -> share(m.all.filter { it.amp >= 20 }) { it.over > 1 } }) { pct(it) }
        val um = r.user.motor
        val bmo = r.bot.motor
        t.row("  over 3 deg / size p50 (share of the move)", "%s / %s".format(pct(um.overshoot3Share), f2(um.overshootSizeP50)), "%s / %s".format(pct(bmo.overshoot3Share), f2(bmo.overshootSizeP50)), "report", true)
        t.row("  first stroke's share of two-stroke moves p10 / p50 / p90", "%s / %s / %s".format(f2(um.firstShareP10), f2(um.firstShareP50), f2(um.firstShareP90)),
            "%s / %s / %s".format(f2(bmo.firstShareP10), f2(bmo.firstShareP50), f2(bmo.firstShareP90)), "report", true)
        // where the overshoots are, by what the learner found the moves did
        for (kind in GlanceMeasure.Kind.entries) {
            val (un, uo) = ug.over20[kind] ?: (0 to 0)
            val (bn, bo) = bg.over20[kind] ?: (0 to 0)
            if (un + bn == 0) continue
            t.row("    ${kind.name.lowercase()} (n $un / $bn)", pct(uo.toDouble() / un), pct(bo.toDouble() / bn), "report", true)
        }
        // your turns toward a creature and back from a fight against your looks around and their way back, each with a
        // first stroke of its own ([HumanAim.paramsOf], [HumanAim.returnParamsOf]), as the learner finds them on both sides
        for ((group, kinds) in HAND_GROUPS) {
            fun over(k: Kinds) = kinds.sumOf { k.over20[it]?.second ?: 0 } to kinds.sumOf { k.over20[it]?.first ?: 0 }
            val (uo, un) = over(ug)
            val (bo, bn) = over(bg)
            t.row("  $group: overshoot > 1 deg (n $un / $bn)", pct(uo.toDouble() / un), pct(bo.toDouble() / bn), "report", true)
            val uf = kinds.flatMap { ug.first20[it].orEmpty() }
            val bf = kinds.flatMap { bg.first20[it].orEmpty() }
            t.row("    first stroke's share p50 / p90 (n ${uf.size} / ${bf.size})", "%s / %s".format(f2(q(uf, 0.5)), f2(q(uf, 0.9))), "%s / %s".format(f2(q(bf, 0.5)), f2(q(bf, 0.9))),
                "report", true)
        }
        // your ways back's first shares by kind, and their hands as estimated together ([BehaviorLearner.WaysBack.estimates])
        run {
            val back = ug.first20[GlanceMeasure.Kind.TURN_BACK].orEmpty().sorted()
            val looks = (ug.first20[GlanceMeasure.Kind.RETURN].orEmpty() + ug.first20[GlanceMeasure.Kind.BACK_TO_FIGHT].orEmpty()).sorted()
            val (eb, el) = BehaviorLearner.WaysBack.estimates(listOf(back, looks))
            t.row("  your ways back's first shares: turn backs / from a look", back.joinToString(" ") { f2(it) } + " / " + looks.joinToString(" ") { f2(it) },
                "estimated %s / %s, planned %s / %s".format(f2(eb), f2(el), f2(r.user.motor.backFirstMu), f2(r.user.motor.returnFirstMu)), "report", true)
        }
        // the bot's by the move that started them, the hand each was planned with
        for (kind in HAND_KINDS) {
            val (n, o) = r.hands[kind] ?: continue
            t.row("    bot, $kind (n $n)", "", pct(o.toDouble() / n), "report", true)
            // what the learner found them to be, with their own overshoot: the hand of a move and its kind on your side
            r.handKinds[kind]?.let { ks ->
                t.row("      as the learner finds them", "", ks.entries.sortedByDescending { it.value.first }.joinToString(", ") { (k, v) ->
                    "${k.name.lowercase()} ${v.first} (${pct(v.second.toDouble() / v.first)})"
                }, "report", true)
            }
        }

        for (bin in Motor.BINS.indices) {
            val um = u.m6.filter { binOf(it.amp) == bin }
            val bm = b.m6.filter { binOf(it.amp) == bin }
            if (bm.size < MIN_BIN) continue
            val us = share(um) { it.strokes.size >= 2 }
            val bs = share(bm) { it.strokes.size >= 2 }
            t.row("multi-stroke ${binName(bin)}", pct(us), pct(bs), "user +-12 points", abs(bs - us) <= 0.12)
        }
        val u60 = share(u.m6.filter { it.amp >= 60 }) { it.strokes.size >= 2 }
        val b60 = share(b.m6.filter { it.amp >= 60 }) { it.strokes.size >= 2 }
        t.row("multi-stroke >= 60 deg", pct(u60), pct(b60), "30-60 %", b60 in 0.30 .. 0.60)
        t.spread(seeds.map { m -> share(m.m6.filter { it.amp >= 60 }) { it.strokes.size >= 2 } }) { pct(it) }
        val uv = u20.mapNotNull { it.valley }
        val bv = b20.mapNotNull { it.valley }
        t.row("  strokes with no still tick between (>= 20 deg)", pct(share(uv) { it.pauseMs == 0.0 }), pct(share(bv) { it.pauseMs == 0.0 }), "report", true)

        val ut = u20.map { it.tpk }
        val bt = b20.map { it.tpk }
        t.row("time to peak p10 (>= 20 deg)", f2(q(ut, 0.1)), f2(q(bt, 0.1)), "user +-0.08", abs(q(bt, 0.1) - q(ut, 0.1)) <= 0.08)
        t.row("time to peak p90 (>= 20 deg)", f2(q(ut, 0.9)), f2(q(bt, 0.9)), "user +-0.08", abs(q(bt, 0.9) - q(ut, 0.9)) <= 0.08)

        val ulag = u.all.map { abs(it.halfLag) }.filter { it.isFinite() }
        val blag = b.all.map { abs(it.halfLag) }.filter { it.isFinite() }
        t.row("axis lag p50 (n ${ulag.size} / ${blag.size})", ms(q(ulag, 0.5)), ms(q(blag, 0.5)), "15-50 ms", q(blag, 0.5) in 15.0 .. 50.0)
        t.row("axis lag p90", ms(q(ulag, 0.9)), ms(q(blag, 0.9)), "120-330 ms", q(blag, 0.9) in 120.0 .. 330.0)

        val us = u20.filter { abs(it.dYaw) > 5 && abs(it.dPitch) > 5 }.map { it.mStraight }
        val bs = b20.filter { abs(it.dYaw) > 5 && abs(it.dPitch) > 5 }.map { it.mStraight }
        t.row("straightness p10 (n ${us.size} / ${bs.size})", f2(q(us, 0.1)), f2(q(bs, 0.1)), "0.70-0.88", q(bs, 0.1) in 0.70 .. 0.88)

        // Half of your gaps (200 ms or less) are inside those sequences of looks. The bot's are its motor's strokes split by
        // two still ticks, its corrections (a reaction after the move before, §2.6, at your 352 ms) and its turn backs after
        // the kill; 150-300 ms would need corrections faster than any reaction, so the overall p50 is a report.
        t.row("gap between separate moves p50 (n ${u.gaps.size} / ${b.gaps.size})", ms(q(u.gaps, 0.5)), ms(q(b.gaps, 0.5)), "150-300 ms (report)", true)
        // the same gaps by what the learner finds the later move to be, on both sides alike (the bot's own log below has no
        // counterpart of yours): which of the bot's moves come later after the one before than yours of that kind
        for (kind in (r.userKindGaps.keys + r.botKindGaps.keys).sorted()) {
            val ug = r.userKindGaps[kind].orEmpty()
            val bg = r.botKindGaps[kind].orEmpty()
            t.row("  before a move the learner finds $kind (n ${ug.size} / ${bg.size}) p50 / p90", "${ms(q(ug, 0.5))} / ${ms(q(ug, 0.9))}",
                "${ms(q(bg, 0.5))} / ${ms(q(bg, 0.9))}", "report", true)
        }
        for ((kind, gaps) in r.botGaps.entries.sortedBy { it.key }) t.row("  bot, before $kind (n ${gaps.size})", "", ms(q(gaps, 0.5)), "report", true)
        val uRest = u.restChanged.toDouble() / u.restTicks
        val bRest = b.restChanged.toDouble() / b.restTicks
        t.row("rest ticks with a change", pct(uRest), pct(bRest), "1.5-3.5 %", bRest in 0.015 .. 0.035)
        t.spread(seeds.map { m -> m.restChanged.toDouble() / m.restTicks }) { pct(it) }
        // a nudge past 5 steps turns the view faster than a rest tick may (15 deg/s) and is a movement of its own; your larger
        // ones are yaw near the pole, little view for many steps
        fun steps(m: Motion) = listOf(1 .. 1, 2 .. 2, 3 .. 5, 6 .. 8, 9 .. Int.MAX_VALUE).joinToString(" / ") { k -> pct(share(m.restSteps) { it in k }) }
        t.row("  steps per changed axis, 1 / 2 / 3-5 / 6-8 / more", steps(u), steps(b), "report", true)
        t.row("  one-tick movements under 2 deg per 1000 rest ticks", f2(1000.0 * u.nudgeMoves / u.restTicks), f2(1000.0 * b.nudgeMoves / b.restTicks), "report", true)
        t.done()
    }

    @Test
    fun `motor alone, one planned move measures as one movement`() {
        val r = replay()
        val params = HumanAim.paramsOf(r.user.motor)
        val rng = Random(SEED)
        var planned = 0
        var segments = 0
        val moves = ArrayList<ViewMotion.Move>()
        // your own moves' start and end views, planned again by the motor alone with your hand
        for (m in r.userMotion.m6) {
            val port = GridPort(m.y0, m.p0)
            var now = 0.0
            val aim = HumanAim(port, { now }, rng)
            aim.params = params
            val lines = ArrayList<ViewMotion.Line>()
            fun line() = port.view().let { lines += ViewMotion.Line(now.roundToLong(), round2(it.yaw), round2(it.pitch)) }
            repeat(3) {
                line()
                now += TICK
            }
            aim.start(m.y1, m.p1, Owner.KILLER)
            var ticks = 0
            while ((aim.moving || ticks < 3) && ticks < 400) {
                for (f in 1 .. FRAMES) {
                    now += TICK / FRAMES
                    aim.frame()
                }
                aim.tick(rest = false, anchor = null)
                line()
                if (! aim.moving) ticks++
            }
            planned++
            val segs = ViewMotion.runs(lines).flatMap { ViewMotion.segment(it).moves }
            segments += segs.size
            moves += segs
        }
        val t = Table("motor alone, your ${r.userMotion.m6.size} moves of 6 deg or more planned again")
        val per = segments.toDouble() / planned
        t.row("movements per planned move", "", f2(per), "<= 1.2", per <= 1.2)
        // the hand alone, without the policy ending its moves: how often it goes past where it comes to rest
        val u20 = r.userMotion.all.filter { it.amp >= 20 }
        val b20 = moves.filter { it.amp >= 20 }
        t.row("overshoot > 1 deg (moves >= 20 deg, n ${u20.size} / ${b20.size})", pct(share(u20) { it.over > 1 }), pct(share(b20) { it.over > 1 }), "report", true)
        t.done()
    }

    @Test
    fun `policy, the bot turns about as rarely and as late as you, and turns back like you`() {
        val r = replay()
        val u = r.user.policy
        val b = r.bot.policy
        val t = Table("policy (user vs bot over ${r.seeds})")
        t.row("turned toward", "%d of %d (%s)".format(u.all.turned, u.all.creatures, pct(u.all.rate)),
            "%d of %d (%s)".format(b.all.turned, b.all.creatures, pct(b.all.rate)), "report", true)
        // The bot turns where the plan needs it (§2.2) and looks where no plan does (turns/SPEC §3.3), at your rate per second
        // alive. A class you turned toward 5 or more times among 15 or more creatures is held within 0.5-1.5 x your rate.
        // The replay keeps your kill times and fights the oldest creature first (the killer reacts to the oldest and then
        // fights the nearest of its group): your turns that came to kill a creature,
        // and creatures it never makes its target in time, are out of its reach. So the floor is asserted only where the
        // bot made at least half your rate of your creatures its target with 600 ms left (overhead ones left out, no look
        // goes there); elsewhere it is a report. The ceilings hold everywhere.
        //  - Flay click: 18 of your 21 creatures come up next to another of yours (12 next to another Flay creature: the
        //    pile of a catch, a 38 s Magma Slug) and live p50 1.7 s. You turned toward them p50 0.85 s after the spawn,
        //    mostly in one move toward the pile; the bot looks at a creature it does not fight at your rate per second
        //    alive, and with your kill times that look mostly ends after the kill.
        //  - Claymore: 16 of your 18 creatures are right above you. The killer hits them without a turn and looks never go
        //    there; your 3 turns toward them came 1.4-3.4 s after the spawn, once your swings had brought the view up.
        for ((cls, us) in u.classes.entries.sortedByDescending { it.value.creatures }) {
            val bs = b.classes[cls] ?: continue
            if (us.creatures < MIN_CLASS) continue
            val (tn, tc) = r.targetable[cls] ?: (0 to 0)
            val reach = if (tc > 0) tn.toDouble() / tc else 0.0
            val band = us.creatures >= MIN_BAND && us.turned >= MIN_TURNS
            val floor = band && reach >= us.rate / 2
            val criterion = "<= user + 10 points" + when {
                floor -> ", 0.5-1.5 x user"
                band -> ", <= 1.5 x user (0.5 x: report)"
                else -> " (0.5 x: report)"
            }
            t.row("  $cls (${us.creatures} / ${bs.creatures}, ${pct(reach)} its target in time)", pct(us.rate), pct(bs.rate), criterion,
                bs.rate <= us.rate + 0.10 && (! band || bs.rate <= 1.5 * us.rate) && (! floor || bs.rate >= us.rate / 2))
            if (band) t.spread(r.perSeed.map { s -> s.bot.policy.classes[cls]?.rate ?: Double.NaN }) { pct(it) }
        }
        t.row("  all classes", pct(u.all.rate), pct(b.all.rate), ">= 0.75 x user", b.all.rate >= 0.75 * u.all.rate)
        val looks = r.looks.filter { it.kind == "look" }
        val returns = r.looks.count { it.kind == "return" }
        t.row("looks at creatures no plan needed (n ${looks.size}), returns", "", "%d (%s)".format(returns, pct(returns.toDouble() / max(1, looks.size))), "report", true)
        t.row("  error at the look's start / what it left p50", "", "%s / %s".format(deg(q(looks.map { it.error }, 0.5)), deg(q(looks.map { it.endError }.filter { it.isFinite() }, 0.5))), "report", true)
        t.row("  its creature's age p50", "", ms(q(looks.map { it.ageMs.toDouble() }, 0.5)), "report", true)
        val aimed = r.looks.filter { it.need == Need.MELEE || it.need == Need.RANGED }
        t.row("  goal keeping the target within its tolerance (n ${aimed.size} with a target needing aim)", "", pct(share(aimed) { it.goalKept }), "all", aimed.all { it.goalKept })
        val ended = aimed.mapNotNull { it.endKept }
        t.row("    still within it when the move came to rest (n ${ended.size})", "", pct(share(ended) { it }), "report", true)
        dist(t, "turns toward a creature: error left / error at the start p50", u.looks.stopShare, b.looks.stopShare, "report", null) { f2(it) }
        val ul = u.looks
        val bl = b.looks
        t.row("first turns no plan needed, turned back within 5 s", "%d of %d (%s)".format(ul.backTurned, ul.backLooks, pct(ul.backTurned.toDouble() / max(1, ul.backLooks))),
            "%d of %d (%s)".format(bl.backTurned, bl.backLooks, pct(bl.backTurned.toDouble() / max(1, bl.backLooks))), "report", true)
        dist(t, "  latency p50", ul.backLatency, bl.backLatency, "report", null) { ms(it) }
        // your way back came one of your gaps after the view last stopped (the look's own rest, or a move of yours after it),
        // whatever its latency from the look; the bot's looks have no moves in between, so their latency is that gap
        val gapBand = medianBand(ul.backGap)
        dist(t, "  gap from the view's last rest p50", ul.backGap, bl.backGap, "%.0f-%.0f ms (your p50's spread)".format(gapBand.start, gapBand.endInclusive), gapBand) { ms(it) }
        dist(t, "first turn after a swap, latency p50", u.all.swapLatency, b.all.swapLatency, "150-600 ms", 150.0 .. 600.0) { ms(it) }
        t.spread(r.perSeed.map { s -> s.bot.policy.all.swapLatency.p50 }) { ms(it) }
        // the bot's own first turns after a swap by what the view did at the swap, and when what turned then came to rest
        for ((seen, xs) in r.swapTurns.groupBy { it.first }.entries.sortedByDescending { it.value.size }) {
            val rests = xs.map { it.third }.filter { it != Long.MIN_VALUE }.map { it.toDouble() }
            t.row("  bot's own, the view $seen at the swap (n ${xs.size}): from the swap p10 / p50 / p90", "",
                listOf(0.1, 0.5, 0.9).joinToString(" / ") { ms(q(xs.map { x -> x.second.toDouble() }, it)) } + ", rest after the swap p50 ${ms(q(rests, 0.5))} (n ${rests.size})",
                "report", true)
        }
        dist(t, "first turn after a spawn, latency p50", u.all.spawnLatency, b.all.spawnLatency, "report", null) { ms(it) }
        dist(t, "first turn, error left p50", u.all.stopErr, b.all.stopErr, "8-25 deg", 8.0 .. 25.0) { deg(it) }
        // where a turn onto a melee creature came to rest on its box: yours somewhere beside its centre line, none of them
        // on it (a hand ending every move on the aim point would put most of the single-stroke ones there)
        val uLand = r.userLandings
        val bLand = r.landings
        t.row("melee turns that came to rest on the box (n ${uLand.size} / ${bLand.size}), beside its centre line p50 (half-widths)", f2(q(uLand, 0.5)), f2(q(bLand, 0.5)),
            "report", true)
        t.row("  within 0.05 of the centre line", pct(share(uLand) { it < 0.05 }), pct(share(bLand) { it < 0.05 }), "<= 10 %", share(bLand) { it < 0.05 } <= 0.10)

        // The learner calls a correction any later move in the fight that brings the view closer to a creature, and times
        // it from whatever move came before: your frequent looks make that short, the bot's few moves (and its first turns
        // to creatures that come up later in the fight) make it long. The bot's own corrections are asserted.
        dist(t, "corrections as the learner finds them, error at their start p50", u.all.corrStart, b.all.corrStart, "report", null) { deg(it) }
        dist(t, "  gap after the move before p50", u.all.corrGap, b.all.corrGap, "report", null) { ms(it) }
        dist(t, "  error at their end p50", u.all.corrEnd, b.all.corrEnd, "report", null) { deg(it) }
        // the bot's own corrections on the learner's measure of yours: its moves of 6 degrees or more, the error at the move's
        // start to the creature it closed on most
        val corr = r.corrections
        val corrStart = corr.map { it.first }.filter { ! it.isNaN() }
        t.row("corrections the policy started (n ${corr.size}), error at their start p50 (n ${corrStart.size} of 6 deg or more)", deg(u.all.corrStart.p50), deg(q(corrStart, 0.5)), "35-80 deg",
            q(corrStart, 0.5) in 35.0 .. 80.0)
        t.row("  gap after the move before p50", ms(u.all.corrGap.p50), ms(q(corr.map { it.second }, 0.5)), "200-600 ms", q(corr.map { it.second }, 0.5) in 200.0 .. 600.0)
        // a death with another creature of yours left: a move still turning goes on until the hand's reaction to the death,
        // or until the next target (a click gap later) brings it to rest at its first update, whichever comes first
        val ds = r.deathStops
        t.row("moves turning at a death with another creature left (n ${ds.size}), at rest after it p50 / p90", "",
            "%s / %s (by the reaction %s)".format(ms(q(ds.map { it.first.toDouble() }, 0.5)), ms(q(ds.map { it.first.toDouble() }, 0.9)), pct(share(ds) { it.second })),
            "report", true)

        val uBack = u.backTurned.toDouble() / max(1, u.backBeyond)
        val bBack = b.backTurned.toDouble() / max(1, b.backBeyond)
        t.row("fights ending > 8 deg from their start view", "%d of %d".format(u.backBeyond, u.backFights), "%d of %d".format(b.backBeyond, b.backFights), "report", true)
        // A fight that started away from the fishing view (a look still out or on its way back at the catch, a turn back
        // the next catch cut short) has no view of its own to turn back to: a quarter of the bot's fights ending away against
        // 4 of your 51. The replay lets a cast go out only once nothing holds it (a look's cast guard, the fight until its
        // turn back) and has the bot know of a catch's creature only after that, but its catches still come at your times,
        // so a catch during a look the bot started while you did not comes anyway. Over all fights this rate is a report;
        // the killer's rate is learned on the fights that started at your fishing view, and there the two compare like
        // for like (judged below).
        t.row("  turned back within 3 s", pct(uBack), pct(bBack), "report (below judged)", true)
        t.spread(r.perSeed.map { s -> s.bot.policy.backTurned.toDouble() / max(1, s.bot.policy.backBeyond) }) { pct(it) }
        fun home(p: BehaviorProfile.Policy) = p.backHomeTurned.toDouble() / max(1, p.backHome)
        t.row("    of those that started at the fishing view (n ${u.backHome} / ${b.backHome})", pct(home(u)), pct(home(b)), "60-90 %",
            b.backHome < MIN_SAMPLES || home(b) in 0.60 .. 0.90)
        t.spread(r.perSeed.map { s -> home(s.bot.policy) }) { pct(it) }
        dist(t, "  turn back latency p50", u.backLatency, b.backLatency, "300-650 ms", 300.0 .. 650.0) { ms(it) }
        // your wait after the kill and your turn back were timed from the last death: a fight whose last creature died while
        // the hand still went to the next one after the death before is over at that death, never at the end of that gap
        t.row("    fights found over after their last death (the turn back and the rod timed late)", "", "${r.lateKills.size}" +
            if (r.lateKills.isEmpty()) "" else ", by p50 ${ms(q(r.lateKills.map { it.toDouble() }, 0.5))}", "none", r.lateKills.isEmpty())
        // a creature already there when another died, its item in hand: your first click at it came a gap of your clicks
        // after the death with your turn inside it (AutoMobKiller's olderStart), not that gap and a whole turn after it
        fun clicks(xs: List<Pair<Long, Boolean>>) = listOf(0.1, 0.5, 0.9).joinToString(" / ") { ms(q(xs.map { x -> x.first.toDouble() }, it)) }
        t.row("first click at a creature already there after a death, no swap, p10 / p50 / p90 (n ${r.userOlderClicks.size} / ${r.olderClicks.size})",
            clicks(r.userOlderClicks), clicks(r.olderClicks), "report", true)
        t.row("  the rod's left clicks (n ${r.userOlderClicks.count { it.second }} / ${r.olderClicks.count { it.second }})",
            clicks(r.userOlderClicks.filter { it.second }), clicks(r.olderClicks.filter { it.second }), "report", true)
        // what the learner takes for the bots' first turns after a swap and turn backs, by the bot's move that started it
        for ((key, xs) in r.picks.groupBy { it.first + ": " + it.second }.entries.sortedBy { it.key })
            t.row("    learner's ${key} (n ${xs.size}) p10 / p50 / p90", "", listOf(0.1, 0.5, 0.9).joinToString(" / ") { ms(q(xs.map { x -> x.third.toDouble() }, it)) }, "report", true)
        // the bot's own turn backs planned right away, by what went on at the death
        for ((seen, xs) in r.backTurns.groupBy { it.first }.entries.sortedByDescending { it.value.size })
            t.row("    bot's own, $seen at the death (n ${xs.size}): from it p10 / p50 / p90", "", listOf(0.1, 0.5, 0.9).joinToString(" / ") { ms(q(xs.map { x -> x.second.toDouble() }, it)) },
                "report", true)
        dist(t, "  error at the next cast p50", u.backCastErr, b.backCastErr, "3-10 deg", 3.0 .. 10.0) { deg(it) }
        dist(t, "  error the turn back left p50", u.backRest, b.backRest, "report", null) { deg(it) }

        // The killer's first rod hit on a creature its recast comes first for: your hit after a missed bobber from that very
        // recast (measured from it, your switch to the left button inside), else your first hit after a spawn from it alone
        // (REACT weighs a pending recast against that hit, whichever is due first goes); a rod click of Auto Fishing's after the
        // hit's clock started (a reel of the new bobber) adds your switch to a click already due ([CrossGap]). A switch drawn
        // on top of the recast's own hit made p50 600 -> 726 ms. The replay's Auto Fishing recasts when you did: a catch whose
        // recast you made after the hit drawn from the spawn (you fought first) is hit first, as you hit every one of your 47
        // rod creatures with no recast within 3 s before recasting (p10 / p50 / p90 203 / 504 / 771 ms after the spawn),
        // never at the end of a wait of a fixed length; a catch with none pending (Auto Fishing off, a catch on a reel with
        // no bite) is hit from the spawn alone.
        fun qs(xs: List<Double>) = listOf(0.1, 0.5, 0.9).joinToString(" / ") { ms(q(xs, it)) }
        val fromRecast = r.rodHits.filter { it.first == HIT_RECAST }.map { it.second.toDouble() }
        val fromSpawn = r.rodHits.filter { it.first == HIT_SPAWN }.map { it.second.toDouble() }
        val beforePending = r.rodHits.filter { it.first == HIT_PENDING }.map { it.second.toDouble() }
        val um = r.user.bobberMissMs.map { it.toDouble() }
        val uf = r.user.firstHitMs.map { it.toDouble() }
        t.row("first rod hit after the recast p10 / p50 / p90 (n ${um.size} / ${fromRecast.size})", qs(um), qs(fromRecast), "p50 user +-15 %",
            fromRecast.size < MIN_SAMPLES || um.size < MIN_SAMPLES || abs(q(fromRecast, 0.5) - q(um, 0.5)) <= 0.15 * q(um, 0.5))
        t.row("first rod hit after a spawn, no recast pending p10 / p50 / p90 (n ${uf.size} / ${fromSpawn.size})", qs(uf), qs(fromSpawn), "report", true)
        t.row("  before a recast still pending, due after it (yours came later) p10 / p50 / p90 (n ${uf.size} / ${beforePending.size})", qs(uf), qs(beforePending), "report", true)
        t.done()
    }

    /**
     * looks/SPEC §4.3: the bot looks around on your in-place ticks, in your phases (a fight that needs aim only before
     * its own first turn), and both sides are measured by the learner ([GlanceMeasure]) with your keys and positions in
     * the bot's lines, so a move of the bot while you walked is locomotion on both sides. Moves of 6° or more are compared
     * without what the bot never does (walking and teleporting, menus and world changes, tools, new fishing views).
     */
    @Test
    fun `looks around, the bot looks away and back about as often and as far as you`() {
        val r = replay()
        val u = r.user.glances
        val b = r.bot.glances
        val t = Table("looks around (user vs bot over ${r.seeds})")
        fun rate(g: BehaviorProfile.Glances, p: GlancePolicy.Phase) = g.counts[p.ordinal] / g.minutes[p.ordinal]
        fun rateRow(name: String, p: GlancePolicy.Phase, lo: Double?, hi: Double?) {
            val ur = rate(u, p)
            val br = rate(b, p)
            val n = "%s (n %d / %d, %.1f / %.1f min)".format(name, u.counts[p.ordinal], b.counts[p.ordinal], u.minutes[p.ordinal], b.minutes[p.ordinal])
            if (lo == null || hi == null) t.row(n, f2(ur), f2(br), "report", true)
            else t.row(n, f2(ur), f2(br), "%.1f-%.1f x user".format(lo, hi), br in lo * ur .. hi * ur)
        }
        rateRow("outward looks / in-place min, waiting", GlancePolicy.Phase.WAITING, 0.6, 1.6)
        t.spread(r.perSeed.map { s -> rate(s.bot.glances, GlancePolicy.Phase.WAITING) }) { f2(it) }
        rateRow("  after the kill", GlancePolicy.Phase.AFTER_KILL, 0.5, 2.0)
        t.spread(r.perSeed.map { s -> rate(s.bot.glances, GlancePolicy.Phase.AFTER_KILL) }) { f2(it) }
        rateRow("  bobber/area fights", GlancePolicy.Phase.FIGHT_FREE, null, null)
        rateRow("  aimed fights", GlancePolicy.Phase.FIGHT_AIM, null, null)
        t.row("    aimed fights, from home", f2(u.homeCounts[2] / u.minutes[2]), f2(b.homeCounts[2] / b.minutes[2]), "report", true)
        val uBack = u.homeReturned.toDouble() / max(1, u.homeRisk)
        val bBack = b.homeReturned.toDouble() / max(1, b.homeRisk)
        t.row("looks from home back within 10 s (n ${u.homeRisk} / ${b.homeRisk})", pct(uBack), pct(bBack), "75-100 %", bBack in 0.75 .. 1.0)
        // you went on looking away through a cast of yours in some of your dwells (the reference recordings: 6 of 25, of
        // 1.9-12.3 s); the bot never does, a cast that comes due brings its look back first (the cast guard holds the
        // cast): its dwells are judged against yours that no cast came during, all of them are reported
        val uDwell = q(r.userDwells.map { it.toDouble() }, 0.5)
        val bDwell = q(r.glances.dwells.map { it.toDouble() }, 0.5)
        t.row("dwell p50, from home, no cast during it (n ${r.userDwells.size} / ${r.glances.dwells.size})", ms(uDwell), ms(bDwell), "0.6-1.6 x user",
            bDwell in 0.6 * uDwell .. 1.6 * uDwell)
        t.spread(r.perSeed.map { s -> q(s.glances.dwells.map { it.toDouble() }, 0.5) }) { ms(it) }
        t.row("  all of them (n ${u.dwellMs.size} / ${b.dwellMs.size})", ms(q(u.dwellMs.map { it.toDouble() }, 0.5)), ms(q(b.dwellMs.map { it.toDouble() }, 0.5)), "report", true)
        val uAmp = q(u.amp.map { it.toDouble() }, 0.5)
        val bAmp = q(b.amp.map { it.toDouble() }, 0.5)
        t.row("look amplitude p50", deg(uAmp), deg(bAmp), "0.7-1.4 x user", bAmp in 0.7 * uAmp .. 1.4 * uAmp)
        fun pitchOnly(g: BehaviorProfile.Glances) = share(g.amp.indices.toList()) { abs(g.dYaw[it]) < GlanceMeasure.PITCH_ONLY_SHARE * g.amp[it] }
        t.row("pitch-only share of looks", pct(pitchOnly(u)), pct(pitchOnly(b)), "user +-15 points", abs(pitchOnly(b) - pitchOnly(u)) <= 0.15)
        t.row("multi-stroke share of looks", pct(u.multiShare), pct(b.multiShare), "<= 25 %", b.multiShare <= 0.25)
        t.row("look dur/law p50", f2(u.lawP50), f2(b.lawP50), "0.75-1.1", b.lawP50 in 0.75 .. 1.1)
        t.row("overshoot > 1 deg of looks (>= 20 deg)", pct(u.overShare), pct(b.overShare), "<= 8 %", b.overShare <= 0.08)
        val uErr = q(u.returnErr.map { it.toDouble() }, 0.5)
        val bErr = q(b.returnErr.map { it.toDouble() }, 0.5)
        t.row("return error p50 (n ${u.returnErr.size} / ${b.returnErr.size})", deg(uErr), deg(bErr), "2-8 deg", bErr in 2.0 .. 8.0)
        t.row("chained looks", "%d (%s)".format(u.chained, pct(u.chained.toDouble() / max(1, u.looks))), "%d (%s)".format(b.chained, pct(b.chained.toDouble() / max(1, b.looks))), "report", true)

        fun comparable(g: BehaviorProfile.Glances) = (g.kinds.values.sum() - listOf("locomotion", "artefact", "tool", "new_view").sumOf { g.kinds[it] ?: 0 }) / g.rotMinutes
        val uc = comparable(u)
        val bc = comparable(b)
        t.row("moves >= 6 deg / min, comparable (no walking, warps, menus, tools, new fishing views)", f2(uc), f2(bc), "0.6-1.5 x user", bc in 0.6 * uc .. 1.5 * uc)
        t.row("moves >= 6 deg / min, raw", f2(u.kinds.values.sum() / u.rotMinutes), f2(b.kinds.values.sum() / b.rotMinutes), "report", true)
        for (kind in GlanceMeasure.Kind.entries) {
            val k = kind.name.lowercase()
            t.row("  $k", f2((u.kinds[k] ?: 0) / u.rotMinutes), f2((b.kinds[k] ?: 0) / b.rotMinutes), "report", true)
        }
        val g = r.glances
        t.row("episodes started where none may (of ${g.episodes})", "", "${g.forbidden}", "0", g.forbidden == 0)
        // the bot cannot put your cast off: a cast that met a look off the water is the one the cast guard holds, until
        // the look is back; one that never went out would be a cast from off the water
        t.row("casts that went out with a look off the water", "", "${g.castsStuck}", "0", g.castsStuck == 0)
        val waits = g.castDelays.map { it.toDouble() }
        // A maximum only grows with the replays pooled (2150-2551 ms over 20 replays, 3099 ms in one seed of 9). The p99
        // of the 400 or so held casts, with their 4 longest waits past it, holds still as replays are added (per seed
        // 1856-2252 ms) and still judges the long tail, which the p95 (about 1450 ms) leaves out.
        t.row("  casts the guard held for a look (n ${waits.size}), wait p50 / p99", "", "%s / %s".format(ms(q(waits, 0.5)), ms(q(waits, 0.99))),
            "p99 <= 3000 ms", waits.isEmpty() || q(waits, 0.99) <= MAX_CAST_WAIT_MS)
        t.spread(r.perSeed.map { s -> q(s.glances.castDelays.map { it.toDouble() }, 0.99) }) { ms(it) }
        t.row("    p95 / the longest", "", "%s / %s".format(ms(q(waits, 0.95)), ms(waits.maxOrNull() ?: 0.0)), "report", true)
        t.spread(r.perSeed.map { s -> s.glances.castDelays.maxOrNull()?.toDouble() ?: 0.0 }) { ms(it) }
        // A held cast goes out once its look is back: the look's own move over and your hand's reaction to a bite with it,
        // the return started (two ticks), and the return's planned move ended (two ticks: the one that sees it over, and a
        // last step the hard cap held back). A guard that holds a cast past its own ceiling is stuck, whatever the p99
        // says; the least time any held cast had left is reported with it.
        val slack = g.castSlack.minOrNull()
        t.row("    held casts that went out past the end of their look's return (+ 2 ticks)", "", "${g.castSlack.count { it < 0 }} (least left %s)".format(ms(slack?.toDouble() ?: Double.NaN)),
            "0", g.castSlack.none { it < 0 })
        // the held cast then goes out a wait of yours after the way back came to rest (Auto Fishing's HeldUse); yours, due
        // while your view moved (restcast2.js): recasts after a catch 101-2153 ms after the rest (52, p50 202, p90 444),
        // other casts 98-2998 ms (39, p50 350, p90 2359), none sooner
        fun restRow(name: String, user: String, xs: List<Long>) = t.row("  $name (n ${xs.size}), the view's rest to the cast p50 / p90 / least", user,
            if (xs.isEmpty()) "-" else "%s / %s / %s".format(ms(q(xs.map { it.toDouble() }, 0.5)), ms(q(xs.map { it.toDouble() }, 0.9)), ms(xs.min().toDouble())), "none within 95 ms",
            xs.none { it < ViewPolicy.MIN_GAP_MS })
        restRow("recasts held back", "202 ms / 444 ms / 101 ms (52)", g.restRecasts)
        restRow("other casts held back", "350 ms / 2359 ms / 98 ms (39)", g.restCasts)
        t.row("home moved by the bot", "", if (g.homeMoved) "yes" else "never", "never", ! g.homeMoved)
        t.done()
    }

    @Test
    fun `timing, the sampler keeps your waits, their streaks and your rhythm`() {
        val r = replay()
        val p = r.user
        val t = Table("timing (your samples vs $TIMING_DRAWS draws; families with $MIN_TIMING or more samples)")
        val rng = Random(SEED)
        // the states the way Auto Fishing and the killer hand them over
        fun stay(family: String) = p.timing.streaks[family]?.takeIf { it.slowPairs >= MIN_SAMPLES }?.stay
        val families = listOf(
            Family("bite reaction", Action.BITE, p.biteReactionMs, null, "biteReactionMs"),
            Family("catch recast", Action.CATCH_RECAST, p.catchRecastMs, StreakState(stay("catchRecastMs")), "catchRecastMs"),
            Family("after kill", Action.AFTER_KILL, p.afterKillMs, StreakState(), "afterKillMs"),
            Family("first rod hit", Action.FIRST_HIT, p.firstHitMs, StreakState(), "firstHitMs"),
            Family("rod to cast", Action.ROD_TO_CAST, p.rodToCastMs, StreakState(stay("rodToCastMs")), "rodToCastMs"),
        )
        for (f in families) {
            if (f.samples.size < MIN_TIMING) {
                t.row("${f.name} (n ${f.samples.size})", "too few", "", "", true)
                continue
            }
            val draws = List(TIMING_DRAWS) { TimingModel.draw(f.action, f.samples, f.state, rng).toInt() }
            // your side as the learner counts it, within each recording (a pooled list would add the recordings' differences)
            val streak = p.timing.streaks.getValue(f.family)
            val bs = serial(draws, streak.threshold)
            val ks = tickKs(f.samples, draws)
            t.row("${f.name} (n ${f.samples.size}) tick KS", "", "%.3f".format(ks), "<= 0.06", ks <= 0.06)
            t.row("  slow after slow (${streak.slowPairs} slow pairs)", if (streak.slowPairs < MIN_SAMPLES) "too few" else pct(streak.stay), pct(bs.stay),
                "user +-12 points", streak.slowPairs < MIN_PAIRS || abs(bs.stay - streak.stay) <= 0.12)
            t.row("  lag-1 of ln", f2(streak.lag1), f2(bs.lag1), "user +-0.1", abs(bs.lag1 - streak.lag1) <= 0.1)
        }
        // rod melee gaps: drawn with the rhythm the killer keeps, measured within runs of hits as the learner measures yours
        val gaps = p.clickLeftRodMs
        if (gaps.size >= MIN_TIMING) {
            val rhythm = StreakState(FightRules.learnedRho(p.timing))
            val runs = List(TIMING_DRAWS / MELEE_RUN) { List(MELEE_RUN) { TimingModel.draw(Action.MELEE_GAP_ROD, gaps, rhythm, rng).toInt() } }
            val ks = tickKs(gaps, runs.flatten())
            t.row("rod melee gap (n ${gaps.size}) tick KS", "", "%.3f".format(ks), "<= 0.06", ks <= 0.06)
            t.row("  lag-1 of ln within runs (${p.timing.meleeGaps} gaps, rho %.2f)".format(p.timing.meleeRho), f2(p.timing.meleeLag1), f2(lag1(runs)), "user +-0.1",
                abs(lag1(runs) - p.timing.meleeLag1) <= 0.1)
        }

        // the wrapper never loops forever, whatever the profile holds
        val outside = List(40) { 50_000 + it }
        val v = Humanize.ms(outside, 100, 200)
        t.row("Humanize.ms with every sample outside its range", "", "$v ms", "terminates", v > 0)
        t.done()
    }

    // ---- the replay ----

    private class Family(val name: String, val action: Action, val samples: List<Int>, val state: StreakState?, val family: String)

    /** A move the killer started: when, what for (`first` turn to a creature, `correction`, turn `back`, a `look` or its `return`) and the error it started at. */
    private class Start(val t: Long, val kind: String, val error: Double)

    /** A no-swap fight's first click on [target] pending: the death, when it is due, the reaction drawn for a turn timed inside its wait (0: none), a rod's left click; [first]: the fight's first tick. */
    private class OlderClick(val target: Int, val deathAt: Long, val clickAt: Long, val entry: Long, val rodLeft: Boolean) {
        var first = true
    }

    /**
     * A look of the bot ([ViewPolicy.Looks]) or its return: when, at which creature ([id], -1 for a return), its age and
     * error then, the target it had to keep, that target's need and whether the goal kept it. Once the look came to rest:
     * the error left to its creature ([endError], NaN when that died) and whether the target was still within its
     * tolerance ([endKept], null when the target changed or needed no aim).
     */
    private class Look(val t: Long, val kind: String, val id: Int, val ageMs: Long, val error: Double, val target: Int, val need: Need, val goalKept: Boolean) {
        var endError = Double.NaN
        var endKept: Boolean? = null
    }

    private class Mob(val id: Int, val x: Double, val y: Double, val z: Double, val h: Double, val w: Double)

    /** A `rot` line: its index in the file, time, your view and position, your creatures alive. */
    private class Rot(val index: Int, val t: Long, val yaw: Double, val pitch: Double, val x: Double, val y: Double, val z: Double, val c: List<Mob>)

    /**
     * A recording as the replay needs it: every line, the rot lines, your creatures' spawns and deaths, and your main
     * attack per creature (`item:button`, `h` for a held key, `none` without clicks) the way the learner finds it.
     */
    private class Recording(
        val file: File, val lines: List<JsonObject>, val rots: List<Rot>, val spawns: Map<Int, Long>, val deaths: Map<Int, Long>,
        val classes: Map<Int, String>, val slotOf: Map<String, Int>, val rodFlag: Map<String, Boolean>,
        /** Your casts and bites, in order; where your creatures died (half their height), by time. */
        val casts: LongArray, val bites: LongArray, val deathSpots: List<Pair<Long, DoubleArray>>,
        /** Your creatures' names, and those another split off as it died. */
        val names: Map<Int, String>, val children: Set<Int>,
    ) {
        fun classOf(id: Int) = classes[id] ?: "none"

        /** Your next cast after [t] ([Long.MAX_VALUE]: none). */
        fun nextCast(t: Long) = casts.getOrNull(lastAtOrBefore(casts, t) + 1) ?: Long.MAX_VALUE

        /** A bite came since your last cast at [t]: the reel and the recast are on their way. */
        fun bitten(t: Long): Boolean {
            val b = lastAtOrBefore(bites, t)
            return b >= 0 && bites[b] > (casts.getOrNull(lastAtOrBefore(casts, t)) ?: Long.MIN_VALUE)
        }

        /** Your fights: your creatures' lifetimes merged where they overlap, with the class of the first creature. */
        val fights: List<Triple<Long, Long, String>> by lazy {
            val out = ArrayList<Triple<Long, Long, String>>()
            for ((id, s) in spawns.entries.sortedBy { it.value }) {
                val d = deaths[id] ?: continue
                val last = out.lastOrNull()
                if (last != null && s <= last.second) out[out.size - 1] = Triple(last.first, max(last.second, d), last.third)
                else out += Triple(s, d, classOf(id))
            }
            out
        }
    }

    /** A view that keeps changes on the mouse grid like the game's fixRot. */
    private class GridPort(var yaw: Double, var pitch: Double) : HumanAim.Port {
        override val grid = ViewMotion.GRID_DEG
        override fun view() = View(yaw, pitch)

        override fun set(yaw: Double, pitch: Double): View {
            val dy = yaw - this.yaw
            val dp = pitch - this.pitch
            this.yaw += dy - dy % grid
            this.pitch = (this.pitch + dp - dp % grid).coerceIn(- 90.0, 90.0)
            return view()
        }
    }

    private enum class Phase { IDLE, REACT, SWAP, FIGHT, AFTER_KILL, TURN_BACK }

    /**
     * The killer on one recording, the way AutoMobKiller drives the policy and the view: the reaction to the spawn (a swap
     * reaction for a weapon), the swap, the policy every tick of the fight, the wait after the kill, the rod back and the
     * turn back to your fishing view. Its fishing view is yours at the start of each stretch of fights (the recordings
     * have no lines between fights; Auto Fishing casts where you did).
     */
    private class Bot(val rec: Recording, val profile: Profile, seed: Long, val timeline: GlanceMeasure.Result) {
        val rng = Random(seed)
        /** Its looks around, with draws of their own: the fights' draws stay the ones without them. */
        val glances = GlancePolicy(Random(seed * 7919 + 13))
        /**
         * Episodes of looks around it started and those that started where none may; how long each of your casts that met a
         * look off the water waited for it, those still waiting when the stretch ended, and whether home ever moved.
         */
        var glanceEpisodes = 0
        var glanceForbidden = 0
        val castDelays = ArrayList<Long>()
        var castsStuck = 0
        var homeMoved = false
        private var castIdx = 0
        /** Your cast waiting for the bot's look to come back, since then (-1: none); the last tick lived. */
        private var castWait = - 1L
        private var lastTick = 0L
        /**
         * The guard's ceiling for the cast it holds ([castWait]): its look's return has to start by [holdReturnDue] (the
         * look's own move over and your hand's reaction to a bite with it, plus two ticks), and the cast goes out two ticks
         * after that return's planned end at the latest (the tick that sees it over, and one for a last step the hard cap
         * held back). How long before its ceiling each held cast went out (negative: past it, a stuck guard).
         */
        private var holdReturnDue = 0L
        private var holdCeiling = Long.MAX_VALUE
        val castSlack = ArrayList<Long>()
        /** The planned end of the move of the looks around in flight (its look or its return). */
        private var glanceMoveEnd = 0L
        /**
         * A cast the look held, or one due a moment after the view's last rest, goes out a wait of yours after that rest
         * (Auto Fishing's [com.laybalt.features.impl.fishing.HeldUse]), drawn on a stream of its own; how long after the
         * rest each one went out, recasts after a catch and other casts.
         */
        private val heldCast = com.laybalt.features.impl.fishing.HeldUse()
        private val castRng = Random(seed * 67_867_967 + 53)
        val restRecasts = ArrayList<Long>()
        val restCasts = ArrayList<Long>()
        /** The tick the bot's fight last stopped holding Auto Fishing: a cast it held goes out a wait of yours after that at the earliest, as before. */
        private var killerFreeAt = Long.MIN_VALUE / 2
        private var killerHeld = false
        /**
         * The use key a hold plan holds (AutoMobKiller's holdUse: a hold of your length once the view came within the plan's
         * aim and your reaction to that, one of your gaps after it; up a reaction after the view no longer keeps it), with
         * draws of its own: held through a kill, it goes up your reaction to the death later ([HandLag]), the rod comes back
         * a moment after that, and the turn back follows the key-up, not the death.
         */
        private val holdRng = Random(seed * 122_949_829 + 59)
        private var holding = false
        private var holdEnd = 0L
        private var nextHoldAt = 0L
        private var entryAt = Long.MAX_VALUE
        private var keepOffAt = Long.MAX_VALUE
        /** When the held key last went up ([Long.MIN_VALUE]: never), and when the key held through a death goes up ([Long.MAX_VALUE]: none). */
        private var keyUpAt = Long.MIN_VALUE
        private var deathKeyUpAt = Long.MAX_VALUE
        /**
         * Your casts that came while the bot's fight or a look held Auto Fishing: in the game the cast goes out once nothing
         * holds it any more, from the view then ([castViews], your cast's time -> that view; your bites and catches still
         * come at theirs). The fight holds it while it turned the view or fought with a weapon, until its turn back.
         */
        private val castsHeld = ArrayList<Long>()
        val castViews = HashMap<Long, View>()
        /** When the last cast the bot held went out: the creatures of a later catch of yours are reacted to from then at the earliest. */
        private var castOutAt = Long.MIN_VALUE
        /** The fight turned the view (a move or a look) or fought with a weapon: it holds Auto Fishing until its turn back is over. */
        private var turned = false
        private var weaponFight = false
        var now = 0.0
        val port = GridPort(0.0, 0.0)
        val aim = HumanAim(port, { now }, rng)
        val params: MotorParams = HumanAim.paramsOf(profile.motor)
        /** The hand for a look that has to keep the plan's target in view, like the killer's ([HumanAim.keepParamsOf]). */
        val keep: MotorParams = HumanAim.keepParamsOf(profile.motor)
        val policy = ViewPolicy(rng)
        val looks = ViewPolicy.Looks(rng).also { it.table = ViewPolicy.Looks.Table.of(profile.policy.looks) }
        val afterKillStreak = StreakState()
        /** The hand's reactions to a death or a new creature, with draws of their own: the fights' draws stay the ones without them. */
        val lagRng = Random(seed * 104_729 + 17)
        /** A move still turning at a death or a new creature comes to rest then, as AutoMobKiller's HandLag brings it (MAX: none). */
        var stopAt = Long.MAX_VALUE
        /** Where a move comes to rest on the box or how far a look-down goes, with draws of their own: the fights' draws stay the ones without them. */
        val landRng = Random(seed * 7_368_787 + 29)
        /** A turn back's undershoot drawn again below the fallback, and the looks' reaction to a bite, with draws of their own. */
        val backRng = Random(seed * 15_485_863 + 31)
        val reactRng = Random(seed * 32_452_843 + 37)
        /** Creature id -> how long one right above or below you is waited for to fall into the crosshair (AutoMobKiller's, drawn per creature), with draws of their own. */
        private val fallWaits = HashMap<Int, Long>()
        private val fallRng = Random(seed * 49_979_687 + 41)
        /** The gap from a death to the next creature of the group (AutoMobKiller's SWAP), with draws of its own. */
        private val swapRng = Random(seed * 86_028_121 + 43)
        /**
         * When the hand was freed for creatures already there (the creature a reaction was for is gone, a death ended the
         * fight) and its reaction to them then (AutoMobKiller's freeHand, [Action.DEATH_TO_SWAP]; [Long.MAX_VALUE] while the
         * use key held through it is down: from its key-up then), with draws of their own; whether REACT is for such a
         * creature, and whether its class takes another item (else the plan's own gap, AutoMobKiller's olderReact).
         */
        private var freedAt = Long.MIN_VALUE
        private var freedReactAt = Long.MIN_VALUE
        private val freeRng = Random(seed * 179_424_673 + 61)
        private var reactOlder = false
        private var reactSwaps = true
        /**
         * The no-swap fight on such a creature (AutoMobKiller's olderStart): its first click due then ([Long.MIN_VALUE]: none),
         * the reaction to the view getting there drawn for a turn timed inside that wait (0: none drawn), the fight's first
         * turn at its start ([firstTurnAt] for [firstTurnFor]); the click pending once it started ([olderClick]) and the bot's
         * ms from the death to it, and whether it was a rod's left click ([olderClicks]).
         */
        private var olderClickAt = Long.MIN_VALUE
        private var olderEntry = 0L
        private var firstTurnAt = Long.MIN_VALUE
        private var firstTurnFor = - 1
        private var olderClick: OlderClick? = null
        val olderClicks = ArrayList<Pair<Long, Boolean>>()
        /**
         * A move of the fight still turning at a death with another creature left: that death's time (MIN: none); and for
         * each such move, how long after the death it was brought to rest and whether the hand's reaction did it (else the
         * next target's first update, ViewPolicy's stopOld).
         */
        private var deathTurn = Long.MIN_VALUE
        val deathStops = ArrayList<Pair<Long, Boolean>>()
        /**
         * The killer's first rod hit on your creatures its recast comes first for (AutoMobKiller's firstRodHitAt, pickTarget
         * and handFree): one of your first hits after a missed bobber from Auto Fishing's recast after the catch (yours around
         * the spawn), else one of your first hits after a spawn from it alone (drawn again only past REACT's wait for a
         * pending recast); then your switch after Auto Fishing's last rod click (your casts and reels) when that came after the hit's
         * own clock started ([CrossGap]), with draws of their own. Per creature: its path ([HIT_RECAST], [HIT_SPAWN], or
         * [HIT_PENDING]: a recast pending, your own after the bite due after the hit drawn from the spawn, which went first)
         * and how long after the recast or the spawn the hit was due.
         */
        private val hitRng = Random(seed * 236_887_691 + 71)
        private val hitCross = CrossGap()
        private val hitDone = HashSet<Int>()
        val rodHits = ArrayList<Pair<String, Long>>()
        private val rodUses: LongArray by lazy {
            rec.lines.filter { (it.str("e") == "cast" || it.str("e") == "reel") && it.get("auto")?.asBoolean != true }.map { it.long("t") }.sorted().toLongArray()
        }

        private fun firstRodHit(id: Int, t: Long) {
            if (! rodFought(id) || ! recastFirst(id) || ! hitDone.add(id)) return
            val spawn = spawnOf(id, t)
            // Auto Fishing's recast after the catch is yours, when you made it (the replay's Auto Fishing recasts when you did)
            val recast = rec.casts.getOrNull(lastAtOrBefore(rec.casts, spawn - RECAST_WAIT_MS - 1) + 1)?.takeIf { rec.bitten(it - 1) }
            val p = profile
            val firstHit = { TimingModel.draw(Action.FIRST_HIT, p.firstHitMs, null, hitRng) }
            // REACT weighs a recast still to come (a bite since your last cast: its reel and recast are on their way) against
            // your first hit after a spawn, drawn from it alone in its first tick: whichever is due first goes
            // (AutoMobKiller's waitsForRecast); one out by then is the recast's, with none pending the hit is that one
            val lead = if (recast != null && recast <= spawn + TICK.toLong()) null else FightRules.firstRodHit(spawn, null, spawn + TICK.toLong(), { error("no recast") }, firstHit, firstHit)
            val taken = recast?.takeIf { lead == null || it <= lead }
            val gate = if (taken != null) FightRules.firstRodHit(spawn, taken, Long.MIN_VALUE, { TimingModel.draw(Action.BOBBER_MISS, p.bobberMissMs, null, hitRng) },
                { error("a recast came") }) { error("a recast came") } else lead!!
            val used = rodUses.getOrNull(lastAtOrBefore(rodUses, gate)) ?: Long.MIN_VALUE
            val from = FightRules.firstClickFrom(spawn, taken, Long.MIN_VALUE, Long.MIN_VALUE)
            val hit = maxOf(gate, hitCross.notBefore(used, from, gate) { TimingModel.draw(Action.CROSS_R_TO_L, emptyList(), null, hitRng) })
            rodHits += (if (taken != null) HIT_RECAST else if (recast != null || rec.bitten(spawn)) HIT_PENDING else HIT_SPAWN) to (hit - (taken ?: spawn))
        }

        /** Your last bite the looks saw, and when a look out at it may come back. */
        private var biteSeen = - 1
        private var glanceReactAt = Long.MIN_VALUE
        val rod = profile.fishingRods.firstOrNull() ?: WeaponPlanner.ROD
        var home = View(0.0, 0.0)
        var held = rod
        var phase = Phase.IDLE
        var nextAt = 0L
        var reactId = - 1
        var target = - 1
        var swapAt = 0L
        var acted = false
        var back: TurnBack? = null
        var movePitchOnly = false
        val firstSeen = HashMap<Int, Long>()
        val aimShare = HashMap<Int, Double>()
        /** The moves it started, in order. */
        val starts = ArrayList<Start>()
        /** Every move it started, its looks around and their returns too: when, and its [HAND_KINDS] entry (the hand it planned with). */
        val hands = ArrayList<Pair<Long, String>>()
        /** Its looks and their returns, in order; the one in flight. */
        val lookLog = ArrayList<Look>()
        private var lookOn: Look? = null
        /** Creature id -> when it first became the target. */
        val targetSince = HashMap<Int, Long>()

        /** The bot's view at every rot line (rounded like the recorder's), and its swaps (time, item). */
        val views = arrayOfNulls<View>(rec.rots.size)
        val slots = ArrayList<Pair<Long, String>>()

        fun run(): Bot {
            val rots = rec.rots
            for ((k, r) in rots.withIndex()) {
                if (k == 0 || r.t - rots[k - 1].t > RESET_GAP_MS || r.t <= rots[k - 1].t) reset(r)
                tick(r.t, r)
                views[k] = port.view().let { View(round2(it.yaw), round2(it.pitch)) }
                val next = rots.getOrNull(k + 1) ?: continue
                if (next.t - r.t > RESET_GAP_MS || next.t <= r.t) continue
                // ticks the recorder left out (a hitch) are lived through with what it saw last
                var t = r.t
                while (next.t - t > TICK * 1.5) {
                    frames(t, t + TICK.toLong())
                    t += TICK.toLong()
                    tick(t, r)
                }
                frames(t, next.t)
            }
            return this
        }

        /** A new stretch of fights: the bot looks where you did, your fishing view. */
        private fun reset(r: Rot) {
            finish()
            // a cast still waiting when your lines stop is cut off by the recording, unless it waited long enough to tell
            if (castWait >= 0 && lastTick - castWait > MAX_CAST_WAIT_MS) castsStuck++
            castWait = - 1L
            castsHeld.clear()
            heldCast.clear()
            letGo(r.t, gap = false)
            aim.abort()
            glances.drop()
            port.yaw = r.yaw
            port.pitch = r.pitch
            home = View(r.yaw, r.pitch)
            aim.home = home
            if (held != rod) {
                held = rod
                slots += r.t to rod
            }
        }

        private fun frames(from: Long, to: Long) {
            for (f in 1 .. FRAMES) {
                now = from + (to - from) * f / FRAMES.toDouble()
                aim.frame()
            }
        }

        private fun tick(t: Long, r: Rot) {
            now = t.toDouble()
            val ids = r.c.mapTo(HashSet()) { it.id }
            if (lineIds.any { it !in ids }) leftAt = t
            lineIds = ids
            // rest nudges: a look's dwell around where it ended, the killer's around its last move in a fight, Shake Head's
            // around your fishing view otherwise
            val anchor = if (glances.out) glances.endView else if (phase != Phase.IDLE) aim.restAnchor
            else home.takeIf { h -> port.view().let { MotorModel.angle(it.yaw, it.pitch, h.yaw, h.pitch) <= HumanAim.IDLE_ANCHOR_DEG } }
            aim.tick(rest = true, anchor = anchor)
            if (t >= stopAt) {
                stopAt = Long.MAX_VALUE
                deathStopped(t, reaction = true)
                aim.stopFight()
            }
            if (t >= deathKeyUpAt) {
                letGo(t, gap = true)
                // your item swap after such a kill (or for a new creature) came a moment after the key went up; for one that
                // was there before the death REACT times it from the key-up alone (AutoMobKiller's lagTick)
                if (phase == Phase.AFTER_KILL || phase == Phase.REACT && ! reactOlder)
                    nextAt = max(nextAt, TimingModel.dueAt(t, TimingModel.draw(Action.KEY_UP_TO_SWAP, emptyList(), null, holdRng)))
            }
            // looks go out only while a target is fought: between targets a look comes to rest (its return may follow at the
            // next one), after the last death the turn back owns the view
            // (one still turning at a death or a new creature comes to rest with the hand's reaction to it)
            val lagging = stopAt != Long.MAX_VALUE
            if (phase == Phase.REACT || phase == Phase.SWAP) {
                if (! lagging) aim.stop(Owner.LOOK)
            }
            else if (phase != Phase.FIGHT) {
                if (lookOn != null && ! aim.moving(Owner.LOOK)) lookOn = null
                looks.hold()
                if (! lagging) aim.stop(Owner.LOOK)
            }
            killer(t, r)
            glance(t, r)
            // the end of the view's last moving tick, where your waits after a move ran from (Auto Fishing's restSeenAt);
            // long ago while a move still turns the view
            val turning = aim.turning
            val held = killerHolds()
            if (killerHeld && ! held) killerFreeAt = t
            killerHeld = held
            val turnEnd = if (turning) Long.MIN_VALUE / 2 else turnEndAt(t)
            val restSeenAt = max(turnEnd, killerFreeAt)
            val restId = if (turning) Long.MIN_VALUE / 2 else if (killerFreeAt > turnEnd) killerFreeAt else aim.turns
            // a move's slow tail holds a cast only where it would not land like one from your fishing view (Auto
            // Fishing's tailHolds; no world here: within the fallback's degrees of it)
            val tailHolds = com.laybalt.features.impl.fishing.AutoFishing.CastCheck.tailHolds(aim.moving && ! turning) {
                listOfNotNull(port.view(), aim.restingAt).all { v -> FightRules.landsInFluid(null, MotorModel.angle(v.yaw, v.pitch, home.yaw, home.pitch)) }
            }
            // Auto Fishing casts when you did, unless something holds it: a look keeping the bot's view off the water (the
            // cast guard: the cast waits for the look to come back, and one still waiting at the end of a stretch never
            // went out), or the bot's fight until its turn back. A held cast goes out from the view once nothing holds it.
            while (castIdx < rec.casts.size && rec.casts[castIdx] <= t) {
                val c = rec.casts[castIdx++]
                if (c <= t - TICK) continue
                if (castWait < 0 && lookHolds()) {
                    castWait = c
                    // the look's own move goes on, and your hand reacts to a bite first; a return in flight is the one
                    val lookEnd = if (glances.state == GlancePolicy.State.OUT) glanceMoveEnd else t
                    holdReturnDue = max(max(lookEnd, glanceReactAt), t) + 2 * TICK.toLong()
                    holdCeiling = if (glances.state == GlancePolicy.State.BACK) glanceMoveEnd + 2 * TICK.toLong() else holdReturnDue
                }
                if (lookHolds() || killerHolds() || tailHolds || castsHeld.isNotEmpty() || heldCast.owed) castsHeld += c
                // due a moment after the view's last moving tick: it waits like one held (Auto Fishing's HeldUse)
                else if (! turning && t - restSeenAt < ViewPolicy.MIN_GAP_MS) castsHeld += c
            }
            if (castWait >= 0 && ! lookHolds()) {
                castDelays += t - castWait
                castSlack += holdCeiling - t
                castWait = - 1L
            }
            // a cast the look holds goes out a wait of yours after the look's way back came to rest, as does one due a
            // moment after the view's last rest (Auto Fishing's HeldUse); the fight holding Auto Fishing forgets what it
            // owed, its cast comes after the fight as before (a moment after the view's last rest at the earliest)
            if (castsHeld.isNotEmpty() && (lookHolds() || tailHolds)) heldCast.hold(t, true)
            if (killerHolds()) heldCast.clear()
            if (castsHeld.isNotEmpty() && ! lookHolds() && ! killerHolds() && ! tailHolds) {
                val rest = if (turning) Long.MIN_VALUE / 2 else restSeenAt
                val recast = rec.bitten(castsHeld.first() - 1)
                val owed = heldCast.owed
                if (heldCast.go(t, true, rest, ViewPolicy.MIN_GAP_MS, restId) { TimingModel.draw(if (recast) Action.REST_TO_RECAST else Action.REST_TO_CAST, emptyList(), null, castRng) }) {
                    // from the view's last moving tick, as your rests are timed
                    if (owed && rest > Long.MIN_VALUE / 2) (if (recast) restRecasts else restCasts) += t - rest
                    val v = port.view().let { View(round2(it.yaw), round2(it.pitch)) }
                    for (c in castsHeld) castViews[c] = v
                    castsHeld.clear()
                    castOutAt = t
                }
            }
            if (aim.home !== home) homeMoved = true
            lastTick = t
        }

        /**
         * The bot's fight holds Auto Fishing (AutoMobKiller's holdsAutoFishing): a move of it turns the view, or it turned
         * the view or fought with a weapon and its turn back is not over yet (a fight that began meanwhile goes on holding);
         * not the turn back's slow tail to rest ([FightRules.backTail]), where Auto Fishing's own tail rule takes over.
         */
        private fun killerHolds() = ! FightRules.backTail(phase == Phase.TURN_BACK, back?.done == true, aim.movingInFight(), aim.turning) &&
            (aim.movingInFight() || (turned || weaponFight) && phase != Phase.IDLE)

        /**
         * The bot knows of your creature [c]: a catch's creature only once its cast went out (none of the bot's casts held,
         * [castsHeld]), as a catch comes after the cast in the game; one another split off as it died at once.
         */
        private fun seen(c: Mob) = castsHeld.isEmpty() || c.id in rec.children

        /** A look keeps the view off the water: Auto Fishing's cast waits ([com.laybalt.features.impl.fishing.AutoFishing.CastCheck.glanceHolds], no world to tell). */
        private fun lookHolds(): Boolean {
            val v = port.view()
            return com.laybalt.features.impl.fishing.AutoFishing.CastCheck.glanceHolds(glances.out, aim.moving(Owner.GLANCE) && aim.turning, MotorModel.angle(v.yaw, v.pitch, home.yaw, home.pitch)) { null }
        }

        /**
         * One tick of the looks around (looks/SPEC §4.3): in your phase at that time, on your in-place ticks only, with the
         * bot's own gates (its policy's first turn, its turn back, its moves); a look must be back by your next cast, which
         * the bot cannot put off the way Auto Fishing's cast guard does, and a bite brings it back once the hand reacted to
         * it, as Auto Fishing has it.
         */
        private fun glance(t: Long, r: Rot) {
            val userPhase = timeline.phaseAt(t)
            val place = timeline.inPlaceAt(t)
            val b = back
            // a bite while a look keeps the view away: it starts back once the hand reacted to the bite
            val bite = lastAtOrBefore(rec.bites, t)
            if (bite != biteSeen) {
                biteSeen = bite
                // (a bite while the bot still holds your cast back came before its own cast went out: none for it)
                if (bite >= 0 && rec.bitten(t) && glances.out && castWait < 0) glanceReactAt = rec.bites[bite] + TimingModel.draw(Action.GLANCE_REACTION, emptyList(), null, reactRng)
            }
            // after a bite Auto Fishing knows when it casts again: a look comes back in time for it, and a cast held back
            // goes out once its wait after the way back is over
            var window = if (rec.bitten(t)) (rec.nextCast(t) - t).toDouble() else Double.POSITIVE_INFINITY
            if (heldCast.owed) window = min(window, ((heldCast.goesAt ?: t) - t).toDouble())
            when (phase) {
                Phase.REACT, Phase.SWAP -> if (userPhase == GlancePolicy.Phase.FIGHT_AIM) window = (nextAt - t).toDouble()
                Phase.FIGHT -> window = policy.freeForMs(t)
                Phase.AFTER_KILL, Phase.TURN_BACK -> if (b != null && ! b.done) window = if (b.started) 0.0 else (b.at - t).toDouble()
                Phase.IDLE -> Unit
            }
            val forbidden = ! place || aim.movingInFight() || phase == Phase.FIGHT && policy.freeForMs(t) <= 0.0 || b != null && b.started && ! b.done
            val before = glances.state
            val d = aim.glance(glances, t, userPhase.takeIf { place }, profile.motor, profile.glances, max(0.0, window), sinceDeath(t), deadSpot(t, r),
                castWait >= 0, b != null && ! b.started && ! b.done, glanceReactAt)
            if (d == GlancePolicy.Decision.LOOK && before == GlancePolicy.State.REST) {
                glanceEpisodes++
                if (forbidden) glanceForbidden++
            }
            if (d == GlancePolicy.Decision.LOOK || d == GlancePolicy.Decision.RETURN) {
                hands += t to if (d == GlancePolicy.Decision.LOOK) HAND_KINDS[5] else HAND_KINDS[6]
                glanceMoveEnd = t + ceil(aim.plannedMs).toLong()
                // the held cast's return: it goes out a tick after the return's planned end, from when it was due at the latest
                if (d == GlancePolicy.Decision.RETURN && castWait >= 0) holdCeiling = min(t, holdReturnDue) + ceil(aim.plannedMs).toLong() + 2 * TICK.toLong()
            }
        }

        private fun sinceDeath(t: Long): Double {
            val i = rec.deathSpots.indexOfLast { it.first <= t }
            return if (i < 0) Double.POSITIVE_INFINITY else (t - rec.deathSpots[i].first).toDouble()
        }

        /** The view on where your creature died 1.5 s ago or less, within 8 blocks. */
        private fun deadSpot(t: Long, r: Rot): View? {
            val (d, at) = rec.deathSpots.lastOrNull { it.first <= t } ?: return null
            val dx = at[0] - r.x
            val dy = at[1] - r.y - EYE
            val dz = at[2] - r.z
            if (t - d > 1500 || sqrt(dx * dx + dy * dy + dz * dz) > 8.0) return null
            return viewOn(dx, dy, dz)
        }

        private fun spawnOf(id: Int, t: Long) = rec.spawns[id] ?: firstSeen.getOrPut(id) { t }

        private fun weapon(cls: String): Boolean {
            val item = cls.substringBefore(':')
            return cls != "none" && item != "?" && item !in profile.fishingRods
        }

        private fun killer(t: Long, r: Rot) {
            // a fight starts with a creature it knows of; once fought, the fight goes on with all of yours
            val known = r.c.filter { seen(it) }
            val oldest = (if (phase == Phase.FIGHT || phase == Phase.REACT || phase == Phase.SWAP) r.c else known).minByOrNull { spawnOf(it.id, t) }
            when (phase) {
                Phase.IDLE -> if (oldest != null) {
                    aim.clearRestAnchor()
                    policy.reset()
                    looks.reset()
                    swapAt = 0L
                    back = null
                    acted = false
                    turned = false
                    weaponFight = false
                    react(oldest, t, r)
                }
                Phase.REACT -> when {
                    oldest == null -> afterKill(t)
                    oldest.id != reactId -> {
                        // the creature the reaction was for is gone: one already there is reacted to from now, as the killer does
                        if (r.c.none { it.id == reactId }) freeHand(t)
                        react(oldest, t, r)
                    }
                    // one already there with the use key held through that moment: from its key-up, one of your waits to a
                    // swap, or with its item in hand already the plan's own gap (AutoMobKiller's REACT)
                    reactOlder && nextAt == Long.MAX_VALUE -> {
                        if (holding) {
                            if (deathKeyUpAt != Long.MAX_VALUE) return
                            letGo(t, gap = true)
                        }
                        val from = max(keyUpAt, freedAt)
                        if (reactSwaps && freedReactAt == Long.MAX_VALUE)
                            freedReactAt = TimingModel.dueAt(from, TimingModel.draw(Action.KEY_UP_TO_SWAP, emptyList(), null, freeRng))
                        nextAt = olderStart(oldest, FightRules.olderReact(reactSwaps, false, freedReactAt, from, t) { nextCreatureGap(rec.classOf(oldest.id), freeRng) }, from, t, r)
                    }
                    // a use key still held from the fight before goes up first, the swap a moment after it (AutoMobKiller's REACT)
                    t >= nextAt && ! (holding && deathKeyUpAt != Long.MAX_VALUE) -> {
                        // the fight starts: a move still turning from before has come to rest by now
                        if (stopAt != Long.MAX_VALUE) {
                            stopAt = Long.MAX_VALUE
                            aim.stopFight()
                        }
                        target = oldest.id
                        targetSince.putIfAbsent(target, t)
                        firstRodHit(target, t)
                        swapFor(rec.classOf(oldest.id), t)
                        phase = Phase.FIGHT
                        // with no swap, its first click a gap of the plan after the death and its turn from now (olderStart)
                        if (olderClickAt != Long.MIN_VALUE) {
                            firstTurnAt = t
                            firstTurnFor = target
                            olderClick = OlderClick(target, freedAt, olderClickAt, olderEntry, rodFought(target) && rec.classOf(target).endsWith(":L"))
                            olderClickAt = Long.MIN_VALUE
                        }
                        fight(t, r, oldest)
                    }
                }
                // the next creature of the group a gap after the death, fought from that tick on (AutoMobKiller's SWAP and
                // pickTarget); none left by then: the wait after the kill
                Phase.SWAP -> {
                    // the group's last one died in the gap: over at that death, as AutoMobKiller's SWAP finds it; one of
                    // another group left is reacted to from it (AFTER_KILL), never after the gap
                    val next = r.c.filter { itemOf(rec.classOf(it.id)) == groupItem }.minByOrNull { spawnOf(it.id, t) } ?: return afterKill(t)
                    if (t < nextAt) return
                    target = next.id
                    targetSince.putIfAbsent(target, t)
                    firstRodHit(target, t)
                    swapFor(rec.classOf(next.id), t)
                    phase = Phase.FIGHT
                    fight(t, r, oldest)
                }
                Phase.FIGHT -> fight(t, r, oldest)
                Phase.AFTER_KILL -> {
                    // a creature of yours left is reacted to at once, as AutoMobKiller's AFTER_KILL does: one split off the
                    // one killed or a new catch's from its spawn, one there already from the death (freeHand)
                    if (oldest != null) return react(oldest, t, r)
                    driveBack(t)
                    if (t < nextAt) return
                    // the use key held through the kill goes up first, the rod a moment after it
                    if (holding) return
                    rodBack(t)
                }
                Phase.TURN_BACK -> {
                    if (oldest != null) return react(oldest, t, r)
                    val b = back ?: return finish()
                    driveBack(t)
                    if (b.done && ! aim.movingInFight()) finish()
                }
            }
        }

        private fun react(first: Mob, t: Long, r: Rot) {
            back = null
            reactId = first.id
            phase = Phase.REACT
            val wait = if (weapon(rec.classOf(first.id))) draw(Action.SWAP_REACTION, profile.swapReactionMs) else draw(Action.SPAWN_REACTION, profile.spawnReactionMs)
            // a creature of a catch whose cast the bot held came up for it once that cast was out; one already there when the
            // hand was freed for it is reacted to from then (AutoMobKiller's react)
            val anchor = max(spawnOf(first.id, t), if (first.id in rec.children) Long.MIN_VALUE else castOutAt)
            val cls = rec.classOf(first.id)
            reactOlder = FightRules.olderThanFree(anchor, freedAt)
            reactSwaps = itemOf(cls) != held
            olderClick = null
            olderClickAt = Long.MIN_VALUE
            // one already there: your swap to it after that death, or with its item in hand already the plan's own gap from it
            // (from the key-up instead while the use key held through it is still down)
            nextAt = if (! reactOlder) TimingModel.dueAt(anchor, wait)
            else max(keyUpAt, freedAt).let { from -> olderStart(first, FightRules.olderReact(reactSwaps, freedReactAt == Long.MAX_VALUE, freedReactAt, from, t) { nextCreatureGap(cls, freeRng) }, from, t, r) }
            // a move still turning comes to rest after the reaction to the new creature, before the fight starts (AutoMobKiller's react)
            if (aim.movingInFight())
                stopAt = min(stopAt, TimingModel.dueAt(t, TimingModel.draw(Action.SPAWN_REACTION, profile.spawnReactionMs, null, lagRng, (nextAt - t).toDouble().takeIf { it > 0.0 })))
        }

        private fun draw(action: Action, samples: List<Int>, state: StreakState? = null) = TimingModel.draw(action, samples, state, rng)

        /** The item a creature's class fights with: its weapon, else the rod. */
        private fun itemOf(cls: String) = if (weapon(cls)) cls.substringBefore(':') else rod

        /** The item the creature's class fights with in hand: a weapon swapped to, the rod back for a rod class. */
        private fun swapFor(cls: String, t: Long) {
            val item = itemOf(cls)
            if (item == held) return
            held = item
            if (item != rod) weaponFight = true
            swapAt = t
            slots += t to item
            acted = true
            swapSeen = if (aim.moving) "${aim.owner?.name?.lowercase()}${if (aim.stopping) " braking" else ""}" else "still"
            swapRests = aim.rests
            swapTurnDone = false
        }

        /** What the view did at the last swap ([swapSeen]), the driver's rests then, and whether its first turn was logged. */
        private var swapSeen = ""
        private var swapRests = 0
        private var swapTurnDone = true
        /** Its first turns after a swap: what the view did at the swap, ms from it, ms from the swap to the rest before the turn (MIN: none after it). */
        val swapTurns = ArrayList<Triple<String, Long, Long>>()
        /** Its turn backs: what went on at the death (`none`, `move`, `key`, both), ms from it to the start. */
        val backTurns = ArrayList<Pair<String, Long>>()
        private var backSeen = ""
        private var backFrom = 0L

        /** The item the group of the target that died last was fought with ([itemOf]): SWAP's next target is one of it. */
        private var groupItem = ""

        private fun fight(t: Long, r: Rot, oldest: Mob?) {
            val c = r.c.firstOrNull { it.id == target }
            if (c == null) {
                // the target died: its move goes on until the hand's reaction to the death (AutoMobKiller's HandLag), and
                // the next one is fought a click gap later (AutoMobKiller's SWAP), its first update bringing a move still
                // turning to rest (ViewPolicy's stopOld) if the reaction did not yet
                olderClick = null
                stopAt = TimingModel.dueAt(t, TimingModel.draw(Action.DEATH_REACTION, emptyList(), null, lagRng))
                if (holding) deathKeyUpAt = TimingModel.dueAt(t, TimingModel.draw(Action.DEATH_REACTION, emptyList(), null, holdRng))
                entryAt = Long.MAX_VALUE
                // after the last one of its group (fought with the same item, AutoMobKiller's groupTargets) the wait after
                // the kill, from this death (your wait after a parent's when it was one): one of another group left is
                // reacted to from it (AFTER_KILL), as the killer does
                val dead = rec.classOf(target)
                groupItem = itemOf(dead)
                if (oldest == null || r.c.none { itemOf(rec.classOf(it.id)) == groupItem }) return afterKill(t, rec.names[target] in parentNames)
                deathTurn = if (aim.movingInFight() && ! aim.stopping) t else Long.MIN_VALUE
                target = - 1
                phase = Phase.SWAP
                nextAt = TimingModel.dueAt(t, nextCreatureGap(dead))
                return
            }
            steer(t, r, c)
        }

        /** A move of the fight is brought to rest at [t], by the hand's [reaction] or the next target's first update: counted when it still turned since a death. */
        private fun deathStopped(t: Long, reaction: Boolean) {
            if (deathTurn != Long.MIN_VALUE && aim.movingInFight() && ! aim.stopping) deathStops += (t - deathTurn) to reaction
            deathTurn = Long.MIN_VALUE
        }

        /**
         * AutoMobKiller's gap from a death to the next creature of its group (nextTargetGap): one of your gaps between the
         * clicks the dead one's plan ([cls]) made, a weapon's single presses or holds with their learned gaps, else your
         * melee gaps with the item in hand, a rod or a weapon (pooled in old profiles), none moving a rhythm on. The same gap
         * starts the fight on a creature there already when the hand was freed for it with its item in hand (olderReact).
         */
        private fun nextCreatureGap(cls: String, rng: Random = swapRng): Long {
            val p = profile
            val item = cls.substringBefore(':')
            val button = cls.substringAfter(':', "")
            val gaps = p.weaponStyles[item]?.gapMs.orEmpty()
            if (item !in p.fishingRods && item != "?" && cls != "none") when (button) {
                "R" -> return TimingModel.draw(Action.PRESS_GAP, if (gaps.size >= FightRules.MIN_SAMPLES) gaps else p.clickRightMs, null, rng)
                "Rh" -> return TimingModel.draw(Action.HOLD_GAP, gaps, null, rng)
            }
            val rodInHand = held == rod || held in p.fishingRods
            val samples = if (p.clickLeftRodMs.isEmpty() && p.clickLeftWeaponMs.isEmpty()) p.clickLeftMs else if (rodInHand) p.clickLeftRodMs else p.clickLeftWeaponMs
            return TimingModel.draw(if (rodInHand) Action.MELEE_GAP_ROD else Action.MELEE_GAP_WEAPON, samples, null, rng)
        }

        /** What the plan of a creature of class [cls] needs the view for, as [steer] finds it. */
        private fun needOf(cls: String): Need {
            val item = cls.substringBefore(':')
            val button = cls.substringAfter(':', "")
            return when {
                cls == "none" || item == "?" || (button != "L" && item in profile.fishingRods) -> Need.NONE
                button == "L" -> Need.MELEE
                item in WeaponPlanner.TELEPORT_BLADES -> Need.PITCH
                else -> WeaponPlanner.plan(profile, item)?.let { if (it.aimDeg >= 90.0) Need.NONE else Need.RANGED } ?: Need.RANGED
            }
        }

        /** Your creature [id] is fought with the rod: your attacks on it were the rod's, or you had none. */
        private fun rodFought(id: Int): Boolean {
            val cls = rec.classOf(id)
            val item = cls.substringBefore(':')
            return cls == "none" || item == "?" || item in profile.fishingRods
        }

        private val recastFirsts = HashMap<Int, Boolean>()

        /**
         * The killer's Learned rules recast first for your creature [id] (AutoMobKiller's recastFirst): none that another
         * split off, and you recast through catches or the bobber killed most of its kind.
         */
        private fun recastFirst(id: Int) = recastFirsts.getOrPut(id) {
            id !in rec.children && (profile.recastThroughCatches || profile.habits[rec.names[id]]?.suggestion()?.let { it.action == KillAction.ROD && it.attack == AttackMode.RIGHT } == true)
        }

        /**
         * The class the killer's looks know a creature [id] it does not fight now by (AutoMobKiller's lookClass): the rod's
         * right click for a rod creature its recast comes first for, the rod's melee for any other rod creature, else
         * your class for it (the weapon its plan would take).
         */
        private fun lookClass(id: Int) = if (! rodFought(id)) rec.classOf(id) else FightRules.turnClass(rod, if (recastFirst(id)) ClickStyle.RIGHT else ClickStyle.LEFT)

        /**
         * What a look at your creature [id] that is not the target finds its plan needs (AutoMobKiller's glanceNeed): a
         * rod creature its recast comes first for is the bobber's until a reaction before its first hit, which the killer
         * draws only for REACT's creature (the oldest) and for each target (the nearest of its group) once it is one. The
         * replay's REACT creature and target are both the oldest, so a creature that is not the target never has one
         * here; at runtime the oldest may be REACT's while the nearest is fought, and only that one has. Any other one
         * what its class needs.
         */
        private fun glanceNeed(id: Int) = if (rodFought(id) && recastFirst(id)) Need.NONE else if (rodFought(id)) Need.MELEE else needOf(rec.classOf(id))

        /** No look at your creature [id] before its recast's bobber had its chance (AutoMobKiller's bobberChanceAt), 0 when its recast does not come first. */
        private fun bobberChanceAt(id: Int, t: Long): Long {
            if (! recastFirst(id)) return 0L
            val spawn = spawnOf(id, t)
            // Auto Fishing's recast after the catch: your first cast around the spawn, once it went out
            val recast = rec.casts.getOrNull(lastAtOrBefore(rec.casts, spawn - RECAST_WAIT_MS - 1) + 1)?.takeIf { it <= spawn + FightRules.RECAST_PENDING_MS && it <= t }
            return (recast ?: spawn) + BOBBER_LOOK_MS
        }

        private fun steer(t: Long, r: Rot, c: Mob) {
            val cls = rec.classOf(c.id)
            val item = cls.substringBefore(':')
            val button = cls.substringAfter(':', "")
            val v = port.view()
            // a move's slow tail to rest after its last moving tick is over, as AutoMobKiller's steer has it
            val turning = aim.turning
            val moving = aim.moving(Owner.KILLER) && turning
            val dx = c.x - r.x
            val dz = c.z - r.z
            val height = if (c.h > 0) c.h else NO_MOB_HEIGHT
            val eye = r.y + EYE
            val need: Need
            var aimView = v
            var pitchOnly = false
            var error = 0.0
            var tolerance = 0.0
            var onTarget = false
            var lookDown = false
            var accept: () -> Double = { 0.0 }
            var shortShare = 1.0 .. 1.0
            var land: ((ViewPolicy.Landing) -> View)? = null
            var landing: () -> ViewPolicy.Landing = { ViewPolicy.Landing.CENTRE }
            var restPitch: () -> Double = { Double.NaN }
            // the height of the point the plan aims at above your eye (null: no point, a look-down)
            var pointDy: Double? = null
            when {
                // the bobber killed it, or a fishing rod's right clicks did (recast first): no aim needed
                cls == "none" || item == "?" || (button != "L" && item in profile.fishingRods) -> need = Need.NONE
                button == "L" -> {
                    val share = aimShare.getOrPut(c.id) { aimHeight() }
                    val (a, po) = HumanAim.aimView(v.yaw, v.pitch, dx, c.y + height * share - eye, dz, if (moving) movePitchOnly else null)
                    need = Need.MELEE
                    pointDy = c.y + height * share - eye
                    aimView = a
                    pitchOnly = po
                    error = MotorModel.angle(v.yaw, v.pitch, a.yaw, a.pitch)
                    tolerance = boxHalf(r, c)
                    onTarget = error <= tolerance
                    // a move comes to rest on a point of the box drawn per move, as AutoMobKiller's landPoint
                    val width = if (c.w > 0) c.w else NO_MOB_WIDTH
                    land = { l ->
                        val o = FightRules.landOffset(dx, dz, width, height, l)
                        HumanAim.aimView(v.yaw, v.pitch, dx + o[0], c.y + o[1] - eye, dz + o[2], if (moving) movePitchOnly else null).first
                    }
                    landing = { ViewPolicy.Landing(ViewPolicy.landingSide(landRng), FightRules.aimHeight(profile.aimHeights, landRng)) }
                }
                item in WeaponPlanner.TELEPORT_BLADES -> {
                    val pitch = (WeaponPlanner.plan(profile, item)?.viewPitch?.takeUnless { it.isNaN() } ?: FightRules.BLADE_VIEW_PITCH).toDouble()
                    need = Need.PITCH
                    aimView = View(v.yaw, pitch)
                    pitchOnly = true
                    error = max(0.0, pitch - v.pitch)
                    tolerance = FightRules.PITCH_SLACK.toDouble()
                    lookDown = true
                    val pitches = profile.weaponStyles[item]?.pitch.orEmpty()
                    restPitch = { FightRules.lookDownPitch(pitches, pitch.toFloat(), landRng) }
                }
                else -> {
                    val plan = WeaponPlanner.plan(profile, item)
                    val share = if (plan != null) LEARNED_AIM_HEIGHT else aimShare.getOrPut(c.id) { aimHeight() }
                    val (a, po) = HumanAim.aimView(v.yaw, v.pitch, dx, c.y + height * share - eye, dz, if (moving) movePitchOnly else null)
                    aimView = a
                    pitchOnly = po
                    pointDy = c.y + height * share - eye
                    error = directionError(v, dx, c.y + height * share - eye, dz)
                    if (plan != null) {
                        need = if (plan.aimDeg >= 90.0) Need.NONE else Need.RANGED
                        tolerance = plan.aimDeg
                        val errs = profile.weaponStyles[item]?.aimErr.orEmpty()
                        accept = { ViewPolicy.rangedAcceptance(errs, plan.aimDeg, rng) }
                    }
                    else {
                        need = Need.RANGED
                        tolerance = Double.NaN
                        accept = { FightRules.explicitAcceptance(profile.rightClickErr, rng) }
                        shortShare = 0.5 .. 0.9
                    }
                }
            }
            if (need != Need.NONE) acted = true
            val spawn = spawnOf(c.id, t)
            val (anchorAt, swapped) = if (swapAt >= spawn && swapAt > 0L) swapAt to true else spawn to false
            val falls = need == Need.MELEE && t - spawn <= fallWaits.getOrPut(c.id) { TimingModel.draw(Action.FALL_WAIT, emptyList(), null, fallRng) } && hypot(dx, dz) < FALL_RADIUS
            policy.learned = ViewPolicy.Learned.of(profile.policy, cls)
            val s = ViewPolicy.Situation(
                t, c.id, need, v, aimView, error, tolerance, onTarget, moving && ! aim.stopping, aim.strokes, anchorAt, swapped, falls,
                GIVE_UP_MS - FIRST_TURN_MARGIN_MS, accept, shortShare, aim.moving(Owner.LOOK) && turning, land = land, landing = landing, lookDown = restPitch,
                braking = moving && aim.stopping, restedAt = turnEndAt(t), viewMoving = turning, restId = aim.turns,
                lead = { g -> aim.leadMs(g.yaw, g.pitch, params, lookDown) }, notBefore = if (c.id == firstTurnFor) firstTurnAt else Long.MIN_VALUE,
            )
            val decision = policy.update(s)
            olderClick?.takeIf { it.target == c.id }?.let { o ->
                // the first click goes once the view is where the plan clicks from, your reaction to it getting there later
                // (none for a view there all along), not before its gap after the death (AutoMobKiller's BoxEntry, nextClickAt)
                // (a plan that never clicks at it, the bobber's or an area weapon's, has no first click of its own to compare)
                if (need == Need.NONE) olderClick = null
                else if (onTarget || error <= (if (tolerance.isNaN()) policy.acceptance else tolerance)) {
                    val entry = if (o.first) 0L else if (o.entry > 0L) o.entry else TimingModel.draw(FightRules.entryAction(need), emptyList(), null, freeRng)
                    olderClicks += (max(o.clickAt, t + entry) - o.deathAt) to o.rodLeft
                    olderClick = null
                }
                else o.first = false
            }
            if (decision == Decision.START) starts += Start(t, if (policy.moves <= 1) "first" else "correction", error)
            if (decision == Decision.START && policy.moves <= 1 && swapped && ! swapTurnDone) {
                swapTurnDone = true
                swapTurns += Triple(swapSeen, t - swapAt, if (aim.rests != swapRests) restedAt(t) - swapAt else Long.MIN_VALUE)
            }
            when (decision) {
                Decision.START -> policy.goal?.let { g ->
                    aim.params = params
                    if (aim.start(g.yaw, g.pitch, Owner.KILLER, lookDown)) {
                        movePitchOnly = pitchOnly
                        stopAt = Long.MAX_VALUE
                        deathTurn = Long.MIN_VALUE
                        turned = true
                        hands += t to if (policy.moves <= 1) HAND_KINDS[0] else HAND_KINDS[1]
                    }
                }
                Decision.RETARGET -> policy.goal?.let { aim.retarget(it.yaw, it.pitch, policy.force) }
                Decision.END -> aim.endAfterStroke()
                Decision.STOP -> {
                    deathStopped(t, reaction = false)
                    aim.stop(Owner.KILLER)
                }
                Decision.NONE -> Unit
            }
            look(t, r, c, need, pointDy, tolerance, decision == Decision.START)
            if (need == Need.RANGED && button == "Rh") holdTick(t, item, error <= (if (tolerance.isNaN()) policy.acceptance else tolerance))
        }

        /**
         * One tick of a hold plan's use key ([holding]) at [item], the view [within] the plan's aim: AutoMobKiller's holdUse
         * (a hold of your length once your reaction to the view coming within its aim passed and your gap after the last
         * one; up at its length, or your reaction after the view no longer keeps it).
         */
        private fun holdTick(t: Long, item: String, within: Boolean) {
            val style = profile.weaponStyles[item]
            entryAt = if (! within) Long.MAX_VALUE else if (entryAt == Long.MAX_VALUE) TimingModel.dueAt(t, TimingModel.draw(Action.AIM_ENTRY_PRESS, emptyList(), null, holdRng)) else entryAt
            if (holding) {
                if (deathKeyUpAt != Long.MAX_VALUE) return
                if (within) keepOffAt = Long.MAX_VALUE
                else if (keepOffAt == Long.MAX_VALUE) keepOffAt = TimingModel.dueAt(t, TimingModel.draw(Action.DEATH_REACTION, emptyList(), null, holdRng))
                if (t >= holdEnd || t >= keepOffAt) letGo(t, gap = true, item = item)
                return
            }
            if (t < nextHoldAt || ! within || t < entryAt) return
            holding = true
            keepOffAt = Long.MAX_VALUE
            holdEnd = t + TimingModel.draw(Action.HOLD, style?.holdMs.orEmpty(), null, holdRng) - TimingModel.TICK_MS / 2
        }

        /** The held use key goes up at [t] (nothing when none is held), the next hold one of your gaps with [item] later when [gap]. */
        private fun letGo(t: Long, gap: Boolean, item: String? = null) {
            deathKeyUpAt = Long.MAX_VALUE
            keepOffAt = Long.MAX_VALUE
            if (! holding) return
            holding = false
            keyUpAt = t
            if (gap) nextHoldAt = TimingModel.dueAt(t, TimingModel.draw(Action.HOLD_GAP, item?.let { profile.weaponStyles[it]?.gapMs }.orEmpty(), null, holdRng))
        }

        /**
         * The looks of this tick of [c]'s fight, the way AutoMobKiller drives them: after the policy (its start in this
         * tick, its wait, its move or a look-down keep them quiet), at every creature of yours, the others known by the
         * killer's class for them, after their bobber's chance and only where their plan makes no turn to them once they
         * are the target: no melee, nor in a rod fight a rod creature past its bobber's chance ([lookClass],
         * [bobberChanceAt], [glanceNeed], [FightRules.planTurns]), with a goal that keeps [c] well within [tolerance] of the plan's point [pointDy]
         * above your eye (NaN: the explicit acceptance, at most its hold's). [c]'s own bobber's chance is not waited for:
         * the replay fights it from your reaction to its spawn on, without the recast's first hit (REACT's wait for the
         * recast is not replayed).
         */
        private fun look(t: Long, r: Rot, c: Mob, need: Need, pointDy: Double?, tolerance: Double, started: Boolean) {
            val v = port.view()
            val dx = c.x - r.x
            val dz = c.z - r.z
            val full = if (tolerance.isNaN()) min(policy.acceptance, FightRules.KEEP_HOLD_DEG.toDouble()) else tolerance
            val within = { g: View -> need == Need.NONE || pointDy != null && directionError(g, dx, pointDy, dz) <= full }
            val keeps = { g: View -> need == Need.NONE || pointDy != null && directionError(g, dx, pointDy, dz) <= ViewPolicy.Looks.KEEP_SHARE * full }
            lookEnded(r, c, need, within)
            val creatures = r.c.map { m ->
                val h = if (m.h > 0) m.h else NO_MOB_HEIGHT
                val to = viewOn(m.x - r.x, m.y + h * TURN_AIM_SHARE - r.y - EYE, m.z - r.z)
                val error = MotorModel.angle(v.yaw, v.pitch, to.yaw, to.pitch)
                val overhead = hypot(m.x - r.x, m.z - r.z) < BehaviorProfile.Looks.OVERHEAD_SIDE && error > BehaviorProfile.Looks.OVERHEAD_DEG
                val other = m.id != c.id
                val chance = bobberChanceAt(m.id, t)
                ViewPolicy.Looks.Creature(m.id, if (other) lookClass(m.id) else rec.classOf(m.id), t - spawnOf(m.id, t), error, to, overhead, chance,
                    planTurns = other && FightRules.planTurns(glanceNeed(m.id), rodFought(m.id) && recastFirst(m.id), t >= chance, rodFought(c.id) && need == Need.MELEE))
            }
            val quiet = need == Need.PITCH || started || policy.waiting || aim.moving(Owner.KILLER)
            // a look over at its last moving tick, its way back starting its lead before its moment with its own hand, as
            // AutoMobKiller's driveLooks has it
            val backLead = { g: View -> aim.leadMs(g.yaw, g.pitch, HumanAim.wayBackParamsOf(profile.motor, HumanAim.lookBackKind(v, g, creatures.map { it.aim }), need != Need.NONE)) }
            val scene = ViewPolicy.Looks.Scene(t, v, creatures, c.id, need, policy.free, policy.foundFree, keeps, quiet, aim.moving(Owner.LOOK) && aim.turning, aim.bigTurnedAt(t), backLead, aim.turnedAt(t))
            when (looks.update(scene)) {
                Decision.START -> {
                    val g = looks.goal!!
                    val kind = if (looks.kind == ViewPolicy.Looks.Kind.RETURN) "return" else "look"
                    val id = if (kind == "look") looks.lookedAt else - 1
                    val of = creatures.firstOrNull { it.id == id }
                    // the killer's hand for it: a target that needs aim is kept in view all the way; its way back with the hand
                    // of your moves of the kind the learner finds it to be ([HumanAim.wayBackParamsOf], [HumanAim.lookBackKind])
                    val back = if (kind == "return") HumanAim.lookBackKind(v, g, creatures.map { it.aim }) else null
                    aim.params = if (back != null) HumanAim.wayBackParamsOf(profile.motor, back, need != Need.NONE) else if (need == Need.NONE) params else keep
                    if (! aim.start(g.yaw, g.pitch, Owner.LOOK)) return looks.failed()
                    stopAt = Long.MAX_VALUE
                    deathTurn = Long.MIN_VALUE
                    acted = true
                    turned = true
                    hands += t to if (kind == "look") HAND_KINDS[3] else HAND_KINDS[4]
                    starts += Start(t, kind, of?.error ?: MotorModel.angle(v.yaw, v.pitch, g.yaw, g.pitch))
                    val l = Look(t, kind, id, of?.ageMs ?: - 1L, of?.error ?: Double.NaN, c.id, need, keeps(g))
                    lookLog += l
                    lookOn = l
                }
                Decision.STOP -> {
                    if (aim.moving(Owner.LOOK)) deathStopped(t, reaction = false)
                    aim.stop(Owner.LOOK)
                }
                else -> Unit
            }
        }

        /** The look in flight came to rest: what it left of its creature's error, and whether the target it had to keep still is [within] its tolerance. */
        private fun lookEnded(r: Rot, c: Mob, need: Need, within: (View) -> Boolean) {
            val l = lookOn ?: return
            if (aim.moving(Owner.LOOK)) return
            lookOn = null
            val v = port.view()
            r.c.firstOrNull { it.id == l.id }?.let { m ->
                val h = if (m.h > 0) m.h else NO_MOB_HEIGHT
                l.endError = directionError(v, m.x - r.x, m.y + h * TURN_AIM_SHARE - r.y - EYE, m.z - r.z)
            }
            if (c.id == l.target && (need == Need.MELEE || need == Need.RANGED)) l.endKept = within(v)
        }

        private fun viewOn(dx: Double, dy: Double, dz: Double) = View(Math.toDegrees(atan2(- dx, dz)), Math.toDegrees(- atan2(dy, hypot(dx, dz))))

        private fun aimHeight() = FightRules.aimHeight(profile.aimHeights, rng)

        /** Names of the creatures others split off as they die (AutoMobKiller's parentNames). */
        private val parentNames by lazy { profile.habits.values.mapNotNullTo(HashSet()) { h -> h.parent.takeIf { it.isNotEmpty() } } }

        /**
         * AutoMobKiller's olderStart: with no swap for [c] (there already when the hand was freed) the fight's first click is
         * due [at], a gap of its plan after [from] (the death or the key-up), and a turn its plan makes starts inside that wait
         * (FightRules.olderTurnAt, never before the hand's reaction to [from]); with a swap [at] as it is.
         */
        private fun olderStart(c: Mob, at: Long, from: Long, t: Long, r: Rot): Long {
            if (reactSwaps || at == Long.MAX_VALUE) return at
            olderClickAt = at
            olderEntry = 0L
            val need = needOf(rec.classOf(c.id))
            return FightRules.olderTurnAt(at, from, turnMsTo(c, need, t, r), { TimingModel.draw(FightRules.entryAction(need), emptyList(), null, freeRng).also { olderEntry = it } }) {
                TimingModel.draw(Action.DEATH_REACTION, emptyList(), null, freeRng)
            }
        }

        /** About how long the first turn toward [c] takes with the move its plan ([need]) makes, as AutoMobKiller's planTurnMs; 0 with none. */
        private fun turnMsTo(c: Mob, need: Need, t: Long, r: Rot): Long {
            val v = port.view()
            val dx = c.x - r.x
            val dz = c.z - r.z
            val height = if (c.h > 0) c.h else NO_MOB_HEIGHT
            val eye = r.y + EYE
            val item = rec.classOf(c.id).substringBefore(':')
            return when (need) {
                Need.NONE -> 0L
                Need.MELEE -> {
                    val (a, _) = HumanAim.aimView(v.yaw, v.pitch, dx, c.y + height * aimShare.getOrPut(c.id) { aimHeight() } - eye, dz)
                    val falls = t - spawnOf(c.id, t) <= fallWaits.getOrPut(c.id) { TimingModel.draw(Action.FALL_WAIT, emptyList(), null, fallRng) } && hypot(dx, dz) < FALL_RADIUS
                    FightRules.turnMs(MotorModel.angle(v.yaw, v.pitch, a.yaw, a.pitch), boxHalf(r, c), falls, MotorModel.typicalMs(v.yaw, v.pitch, a.yaw, a.pitch, params))
                }
                Need.PITCH -> {
                    val pitch = WeaponPlanner.plan(profile, item)?.viewPitch?.takeUnless { it.isNaN() } ?: FightRules.BLADE_VIEW_PITCH
                    FightRules.lookDownMs(v.pitch.toFloat(), pitch) { MotorModel.typicalMs(v.yaw, v.pitch, v.yaw, pitch.toDouble(), params, lookDown = true) }
                }
                Need.RANGED -> {
                    val plan = WeaponPlanner.plan(profile, item)
                    val share = if (plan != null) LEARNED_AIM_HEIGHT else aimShare.getOrPut(c.id) { aimHeight() }
                    val (a, _) = HumanAim.aimView(v.yaw, v.pitch, dx, c.y + height * share - eye, dz)
                    FightRules.turnMs(directionError(v, dx, c.y + height * share - eye, dz), FightRules.rangedTolerance(plan?.aimDeg, profile.rightClickErr), false,
                        MotorModel.typicalMs(v.yaw, v.pitch, a.yaw, a.pitch, params))
                }
            }
        }

        /** The hand is free at [t] for creatures already there (AutoMobKiller's freeHand): its reaction to them comes from now. */
        private fun freeHand(t: Long) {
            freedAt = t
            // with the use key held through it, from its key-up instead (drawn once it went up, REACT): never both
            freedReactAt = if (holding) Long.MAX_VALUE else TimingModel.dueAt(t, TimingModel.draw(Action.DEATH_TO_SWAP, emptyList(), null, freeRng))
        }

        private fun afterKill(t: Long, parent: Boolean = false) {
            freeHand(t)
            // a look around out at the death is brought back by the turn back, as the killer does
            if (! acted && ! glances.out) return finish()
            phase = Phase.AFTER_KILL
            nextAt = TimingModel.dueAt(t, if (parent) draw(Action.PARENT_AFTER_KILL, emptyList()) else draw(Action.AFTER_KILL, profile.afterKillMs, afterKillStreak))
            // from where the move in flight at the death (a look around's too) comes to rest, like the killer
            val v = aim.restingAt ?: port.view()
            val distance = HumanAim.distance(v.yaw.toFloat(), v.pitch.toFloat(), home.yaw.toFloat(), home.pitch.toFloat()).toDouble()
            // no world in the replay: the bobber flight cannot tell, the killer's fallback decides; only the move in flight at
            // the death goes on past it, as the killer tells its turn back
            back = ViewPolicy.turnBack(t, distance, FightRules.landsInFluid(null, distance), ViewPolicy.Learned.of(profile.policy, null), rng,
                if (aim.turning) aim.started else ViewPolicy.TurnBack.NO_MOVE)
            if (t - leftAt > TICK && t - leftAt <= ViewPolicy.BACK_WINDOW_MS) lateKills += t - leftAt
            backSeen = listOfNotNull(if (aim.turning) "move ${aim.owner?.name?.lowercase()}" else null, if (holding) "key" else null).joinToString(", ").ifEmpty { "none" } +
                if (glances.out) ", look around out" else ""
            backFrom = t
        }

        /** When one of your creatures last left the lines (died); fights the bot found over only this long after that (none should be). */
        private var leftAt = Long.MIN_VALUE / 2
        private var lineIds: Set<Int> = emptySet()
        val lateKills = ArrayList<Long>()

        private fun rodBack(t: Long) {
            if (held != rod) {
                held = rod
                slots += t to rod
            }
            val b = back
            if (b == null || b.done) return finish()
            phase = Phase.TURN_BACK
            driveBack(t)
        }

        /**
         * When the bot's view last came to rest, as the killer reads it off its driver ([HumanAim.restedAt]): in the game the
         * tick's clock is read at its start and the driver's at the call, a few ms later (and on a coarse clock), so the read
         * here runs the driver's clock that much past the tick's, drawn per read on a stream of its own. A wait drawn per
         * rest that went by the rest's time as read, not by which rest it is, would be drawn again on most ticks.
         */
        private fun restedAt(t: Long): Long {
            val keep = now
            now = t + readRng.nextDouble() * READ_LAG_MS
            val r = aim.restedAt(t)
            now = keep
            return r
        }

        /** The end of the view's last moving tick ([HumanAim.turnEndAt]) as the killer reads it, the driver's clock that much past the tick's ([restedAt]). */
        private fun turnEndAt(t: Long): Long {
            val keep = now
            now = t + readRng.nextDouble() * READ_LAG_MS
            val r = aim.turnEndAt(t)
            now = keep
            return r
        }
        private val readRng = Random(seed * 198_491_317 + 67)

        private fun driveBack(t: Long) {
            val b = back ?: return
            // a look around out or in its dwell is waited for, a move still turning the view comes to rest and the use key held
            // through the kill goes up, as the killer waits
            if (glances.state == GlancePolicy.State.OUT || glances.state == GlancePolicy.State.DWELL && t < glances.dwellEnd) return
            val v = port.view()
            val h = HumanAim.homeGoal(v.yaw, home)
            // a move's slow tail to rest holds it no more, and it starts its lead before its moment, as the killer's
            val backParams = HumanAim.turnBackParamsOf(profile.motor)
            // no world: past the fallback the undershoot is drawn again below it, from a stream of its own, once for this
            // rest: the lead is planned on the goal that starts, as the killer's (TurnBack.short)
            val learned = ViewPolicy.Learned.of(profile.policy, null)
            val short = { b.short(aim.started) { FightRules.backShort(b.shortBy, { ViewPolicy.backUndershoot(learned, backRng, it) }) { null } } }
            val lead = { MotorModel.towards(v.yaw, v.pitch, h.yaw, h.pitch, short()).let { g -> aim.leadMs(g.yaw, g.pitch, backParams) } }
            if (b.update(t, aim.turning, turnEndAt(t), holding, keyUpAt, aim.started, lead) != Decision.START) return
            val g = MotorModel.towards(v.yaw, v.pitch, h.yaw, h.pitch, short())
            // the hand of your turn backs, like the killer's ([HumanAim.turnBackParamsOf])
            aim.params = backParams
            starts += Start(t, "back", MotorModel.angle(v.yaw, v.pitch, h.yaw, h.pitch))
            if (aim.start(g.yaw, g.pitch, Owner.KILLER)) {
                b.begun(t, aim.plannedMs)
                stopAt = Long.MAX_VALUE
                hands += t to HAND_KINDS[2]
                if (b.kind == TurnBack.Kind.NOW) backTurns += backSeen to t - backFrom
            }
        }

        private fun finish() {
            aim.stopFight()
            stopAt = Long.MAX_VALUE
            looks.hold()
            back = null
            policy.reset()
            swapAt = 0L
            phase = Phase.IDLE
            target = - 1
            acted = false
            turned = false
            weaponFight = false
            letGo(now.toLong(), gap = false)
            nextHoldAt = 0L
            entryAt = Long.MAX_VALUE
        }

        /** The bot's view at [t]: at the last rot line at or before it (its view rests between fights), null before the first. */
        fun viewAt(t: Long): View? {
            var lo = 0
            var hi = rec.rots.size - 1
            var found = - 1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                if (rec.rots[mid].t <= t) {
                    found = mid
                    lo = mid + 1
                }
                else hi = mid - 1
            }
            return if (found < 0) null else views[found]
        }

        /**
         * The view the bot cast your cast at [t] from: where nothing held it any more ([castViews]), else its view then;
         * null (yours) between its stretches of fights, where the bot has no view (the next stretch starts from yours).
         */
        private fun castView(t: Long): View? {
            castViews[t]?.let { return it }
            val k = lastAtOrBefore(rotTimes, t)
            val next = rec.rots.getOrNull(k + 1) ?: return null
            return if (k >= 0 && next.t - rec.rots[k].t <= RESET_GAP_MS && next.t > rec.rots[k].t) views[k] else null
        }

        private val rotTimes by lazy { LongArray(rec.rots.size) { rec.rots[it].t } }

        /** The recording with the bot's view in its rot, cast and reel lines and its own swaps instead of yours; its lines. */
        fun write(out: File): List<JsonObject> {
            val rotOf = HashMap<Int, Int>()
            rec.rots.forEachIndexed { k, r -> rotOf[r.index] = k }
            var s = 0
            val written = ArrayList<JsonObject>(rec.lines.size + slots.size)
            out.bufferedWriter().use { w ->
                fun line(o: JsonObject) {
                    written += o
                    w.write(o.toString())
                    w.newLine()
                }
                fun slot(p: Pair<Long, String>) {
                    val o = JsonObject()
                    o.addProperty("e", "slot")
                    o.addProperty("t", p.first)
                    o.addProperty("slot", rec.slotOf[p.second] ?: 0)
                    o.addProperty("item", p.second)
                    o.addProperty("rod", rec.rodFlag[p.second] ?: (p.second in profile.fishingRods))
                    line(o)
                }
                for ((i, o) in rec.lines.withIndex()) {
                    val e = o.str("e")
                    val t = o.long("t")
                    if (e != "session") while (s < slots.size && slots[s].first <= t) slot(slots[s++])
                    val v = when (e) {
                        "slot" -> continue
                        "rot" -> views[rotOf.getValue(i)]
                        "cast" -> castView(t)
                        "reel" -> viewAt(t)
                        else -> null
                    }
                    if (v == null) line(o)
                    else {
                        val c = o.deepCopy()
                        c.addProperty("yaw", v.yaw)
                        c.addProperty("pitch", v.pitch)
                        line(c)
                    }
                }
                while (s < slots.size) slot(slots[s++])
            }
            return written
        }
    }

    /** One side's movements over all its recordings, measured with the learner's segmentation. */
    private class Motion(val minutes: Double) {
        val all = ArrayList<ViewMotion.Move>()
        val m6 get() = all.filter { it.amp >= 6.0 }
        val gaps = ArrayList<Double>()
        var restTicks = 0
        var restChanged = 0
        /** Grid steps of each axis a rest tick changed; one-tick movements under 2°, the nudges too fast for a rest tick. */
        val restSteps = ArrayList<Int>()
        var nudgeMoves = 0
        var maxGc = 0.0
        var maxMouse = 0.0

        companion object {
            /** [parts] as one, over their minutes together. */
            fun of(parts: List<Motion>) = Motion(parts.sumOf { it.minutes }).also { m ->
                for (p in parts) {
                    m.all += p.all
                    m.gaps += p.gaps
                    m.restTicks += p.restTicks
                    m.restChanged += p.restChanged
                    m.restSteps += p.restSteps
                    m.nudgeMoves += p.nudgeMoves
                    m.maxGc = max(m.maxGc, p.maxGc)
                    m.maxMouse = max(m.maxMouse, p.maxMouse)
                }
            }
        }
    }

    private class FightCount {
        var fights = 0
        var moves = 0
    }

    /**
     * One side's moves of 6° or more by what the learner found they did ([GlanceMeasure.Kind]): over all its lines, and
     * those that started in place in a fight or the wait after its kill, with the minutes of each.
     */
    private class Kinds {
        val all = HashMap<GlanceMeasure.Kind, Int>()
        val fight = HashMap<GlanceMeasure.Kind, Int>()
        /** Of the moves of 20 degrees or more by kind: all of them, and those that went more than 1 degree past their end. */
        val over20 = HashMap<GlanceMeasure.Kind, Pair<Int, Int>>()
        /** Of the two-stroke moves of 20 degrees or more by kind: the first stroke's share of the way. */
        val first20 = HashMap<GlanceMeasure.Kind, MutableList<Double>>()
        var minutes = 0.0
        var fightMinutes = 0.0

        fun add(r: GlanceMeasure.Result) {
            minutes += r.rotMinutes
            fightMinutes += FIGHT_PHASES.sumOf { r.minutes[it.ordinal] }
            for (m in r.moves) {
                all.merge(m.kind, 1, Int::plus)
                if (m.inPlace && m.phase in FIGHT_PHASES) fight.merge(m.kind, 1, Int::plus)
                if (m.move.amp >= 20) over20.merge(m.kind, 1 to (if (m.move.over > 1) 1 else 0)) { a, b -> a.first + b.first to a.second + b.second }
                if (m.move.amp >= 20 && m.move.strokes.size == 2) first20.getOrPut(m.kind, ::ArrayList) += m.move.strokes[0].amp / m.move.amp
            }
        }

        fun add(k: Kinds) {
            minutes += k.minutes
            fightMinutes += k.fightMinutes
            k.all.forEach { (kind, n) -> all.merge(kind, n, Int::plus) }
            k.fight.forEach { (kind, n) -> fight.merge(kind, n, Int::plus) }
            k.over20.forEach { (kind, n) -> over20.merge(kind, n) { a, b -> a.first + b.first to a.second + b.second } }
            k.first20.forEach { (kind, xs) -> first20.getOrPut(kind, ::ArrayList) += xs }
        }

        /** Moves of [kinds] in [counts] per minute of [per]. */
        fun rate(counts: Map<GlanceMeasure.Kind, Int>, per: Double, kinds: Collection<GlanceMeasure.Kind>) = kinds.sumOf { counts[it] ?: 0 } / per
    }

    /**
     * What one replay of one recording left once its bot is gone: its seed's group and file, its movements and moves by
     * kind, its moves per fight by type, its gaps by what started the later move, its corrections, its looks at
     * creatures, per class your counted creatures it made its target in time, and its looks around.
     */
    private class Run(
        val group: Int, val file: File, val motion: Motion, val kinds: Kinds, val types: Map<String, FightCount>,
        val gaps: Map<String, List<Double>>, val corrections: List<Pair<Double, Double>>, val looks: List<Look>,
        val targetable: Map<String, Pair<Int, Int>>, val glances: GlanceCount,
        /** Where its turns onto your melee creatures came to rest on the box ([landings]). */
        val landings: List<Double>,
        /** Its movements of 20° or more by the move that started them ([HAND_KINDS]): all of them, and those more than 1° past their end. */
        val hands: Map<String, Pair<Int, Int>>,
        /** The same by what the learner found they did ([GlanceMeasure.Kind]) as well. */
        val handKinds: Map<String, Map<GlanceMeasure.Kind, Pair<Int, Int>>>,
        /** Its moves still turning at a death with another creature left: ms to their rest after it, by the hand's reaction or not ([Bot.deathStops]). */
        val deathStops: List<Pair<Long, Boolean>>,
        /** Its first rod hits: by the path they took ([Bot.rodHits]), ms after the recast or the spawn. */
        val rodHits: List<Pair<String, Long>> = emptyList(),
        /** Its gaps between separate moves by what the learner found the later one did ([kindGaps]). */
        val kindGaps: Map<String, List<Double>> = emptyMap(),
        /** Its first turns after a swap and its turn backs, from its own log ([Bot.swapTurns], [Bot.backTurns]). */
        val swapTurns: List<Triple<String, Long, Long>> = emptyList(),
        val backTurns: List<Pair<String, Long>> = emptyList(),
        /** Its moves per fight by type ([types]) without what it never does ([NOT_BOT]), as the learner classifies them. */
        val comparable: Map<String, FightCount> = emptyMap(),
        /** The learner's first turns after a swap and turn backs on its lines, with its move that started each ([learnerPicks]). */
        val picks: List<Triple<String, String, Long>> = emptyList(),
        /** Its fights found over only this long after their last death ([Bot.lateKills]). */
        val lateKills: List<Long> = emptyList(),
        /** Its first clicks at a creature already there after a death with no swap ([Bot.olderClicks]). */
        val olderClicks: List<Pair<Long, Boolean>> = emptyList(),
    )

    /** One side of the bot over a set of replays: its learned profile, movements, moves by kind and looks around. */
    private class Side(val bot: Profile, val motion: Motion, val kinds: Kinds, val glances: GlanceCount)

    /**
     * Both sides measured: the learned profiles, movements, moves per fight by its first creature's class, moves of 6° or
     * more by what the learner found they did ([Kinds]), and from the bot's own log its gaps by what started the later move
     * and its corrections (error at their start, gap after the move before). [pooled] is the bot over every seed, the side
     * judged; [perSeed] the same for each seed alone, for the spread of the rows near an edge of their band.
     */
    private class Replay(
        val user: Profile, val userMotion: Motion, val pooled: Side, val perSeed: List<Side>,
        val fightTypes: Map<String, Pair<FightCount, FightCount>>, val userKinds: Kinds,
        val botGaps: Map<String, List<Double>>, val corrections: List<Pair<Double, Double>>,
        /** The bots' looks and returns; per class your counted creatures it made its target with 600 ms or more left (not overhead), and all of them, over the seeds. */
        val looks: List<Look>, val targetable: Map<String, Pair<Int, Int>>,
        /** Your dwells before a look's return from your fishing view that none of your casts came during ([castFreeDwells]). */
        val userDwells: List<Long>,
        /** How long the replay and its learning took, in seconds. */
        val seconds: Double,
        /** Where your turns onto your melee creatures came to rest on the box, and the bots' ([landings]). */
        val userLandings: List<Double>, val landings: List<Double>,
        /** The bots' movements of 20° or more by the move that started them, and those past their end by more than 1° ([Run.hands]). */
        val hands: Map<String, Pair<Int, Int>>,
        /** The same by what the learner found they did as well ([Run.handKinds]). */
        val handKinds: Map<String, Map<GlanceMeasure.Kind, Pair<Int, Int>>>,
        /** The bots' moves still turning at a death with another creature left ([Run.deathStops]). */
        val deathStops: List<Pair<Long, Boolean>>,
        /** The bots' first rod hits ([Run.rodHits]). */
        val rodHits: List<Pair<String, Long>> = emptyList(),
        /** Your gaps between separate moves and the bots', by what the learner found the later one did ([kindGaps]). */
        val userKindGaps: Map<String, List<Double>> = emptyMap(), val botKindGaps: Map<String, List<Double>> = emptyMap(),
        /** The bots' first turns after a swap and turn backs, from their own logs ([Run.swapTurns], [Run.backTurns]). */
        val swapTurns: List<Triple<String, Long, Long>> = emptyList(),
        val backTurns: List<Pair<String, Long>> = emptyList(),
        /** Moves per fight by type ([fightTypes]) without what the bot never does ([NOT_BOT]), yours and the bots'. */
        val fightComparable: Map<String, Pair<FightCount, FightCount>> = emptyMap(),
        /** The learner's first turns after a swap and turn backs on the bots' lines ([Run.picks]). */
        val picks: List<Triple<String, String, Long>> = emptyList(),
        /** The bots' fights found over after their last death ([Run.lateKills]). */
        val lateKills: List<Long> = emptyList(),
        /** Your first clicks at a creature already there after a death with no swap ([olderClicks]) and the bots' ([Run.olderClicks]). */
        val userOlderClicks: List<Pair<Long, Boolean>> = emptyList(), val olderClicks: List<Pair<Long, Boolean>> = emptyList(),
    ) {
        val bot get() = pooled.bot
        val botMotion get() = pooled.motion
        val botKinds get() = pooled.kinds
        val glances get() = pooled.glances
        /** Seeds, and replays of every recording per seed. */
        val seeds get() = "$SEED_GROUPS seeds x $REPLAYS replays"
    }

    /**
     * The bots' looks around: episodes, those started where none may, the waits of your casts for one to come back (and
     * those never over), whether home ever moved, and the dwells before a return from home that no cast came due during.
     */
    private class GlanceCount(
        val episodes: Int, val forbidden: Int, val castDelays: List<Long>, val castsStuck: Int, val homeMoved: Boolean, val dwells: List<Long>,
        /** How long before its ceiling each held cast went out ([Bot.castSlack]). */
        val castSlack: List<Long>,
        /** How long after the view's rest each cast held back went out, recasts after a catch and other casts ([Bot.restRecasts], [Bot.restCasts]). */
        val restRecasts: List<Long> = emptyList(),
        val restCasts: List<Long> = emptyList(),
    ) {
        companion object {
            fun of(parts: List<GlanceCount>) =
                GlanceCount(parts.sumOf { it.episodes }, parts.sumOf { it.forbidden }, parts.flatMap { it.castDelays }, parts.sumOf { it.castsStuck }, parts.any { it.homeMoved },
                    parts.flatMap { it.dwells }, parts.flatMap { it.castSlack }, parts.flatMap { it.restRecasts }, parts.flatMap { it.restCasts })
        }
    }

    // ---- measuring ----

    private class Table(val title: String) {
        private val rows = ArrayList<List<String>>()
        private val failed = ArrayList<String>()

        fun row(name: String, user: String, bot: String, criterion: String, ok: Boolean) {
            rows += listOf(name, user, bot, criterion, if (ok) "ok" else "FAIL")
            if (! ok) failed += "$name: user $user, bot $bot, wanted $criterion"
        }

        /**
         * The row above near an edge of its band: its bot value for each seed alone ([perSeed], formatted with [fmt]),
         * lowest to highest. A report only: the pooled value above is the one judged.
         */
        fun spread(perSeed: List<Double>, fmt: (Double) -> String) {
            val s = perSeed.filter { it.isFinite() }.sorted()
            rows += listOf("    per seed", "", s.joinToString(" / ") { fmt(it) }, "report (pooled judged)", "ok")
        }

        fun done() {
            val w = IntArray(5) { c -> rows.maxOfOrNull { it[c].length } ?: 0 }
            val head = listOf("metric", "user", "bot", "criterion", "")
            val text = buildString {
                appendLine("== $title")
                appendLine(head.indices.joinToString(" | ") { head[it].padEnd(max(w[it], head[it].length)) })
                for (r in rows) appendLine(r.indices.joinToString(" | ") { r[it].padEnd(max(w[it], head[it].length)) })
            }
            println(text)
            assertTrue(failed.isEmpty(), "${failed.size} criteria failed:\n" + failed.joinToString("\n") + "\n\n" + text)
        }
    }

    private class Serial(val stay: Double, val pairs: Int, val lag1: Double)

    private companion object {
        const val SEED = 20261107L
        /**
         * Seeds the bot is judged over, pooled, each replaying every recording [REPLAYS] times with draws of its own: the
         * bot's side rests on enough moves and fights that no single seed decides a row near an edge of its band (each
         * seed's own value is printed under those rows).
         */
        const val SEED_GROUPS = 4
        const val REPLAYS = 5
        /** A seed's draws start this far from the one before. */
        const val GROUP_STRIDE = 1_000_000L
        /** Replays and learning run side by side on this many threads at most (the test JVM's heap holds this many replays at once). */
        val THREADS = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
        const val TICK = 50.0
        const val FRAMES = 5
        /** Rot lines further apart than this belong to separate stretches of fights (the recorder writes a 3 s tail). */
        const val RESET_GAP_MS = 3000L
        const val EYE = 1.62
        const val NO_MOB_HEIGHT = 1.8
        /** The width of a creature your oldest recordings did not record it for. */
        const val NO_MOB_WIDTH = 0.6
        const val LEARNED_AIM_HEIGHT = 0.6
        const val FALL_RADIUS = 1.0
        /** The learner's turn is measured to the creature's point at this share of its height. */
        const val TURN_AIM_SHARE = 0.5
        /** AutoMobKiller's: a catch recast this long before a spawn is still its own, and a recast's bobber has its chance for this long. */
        const val RECAST_WAIT_MS = 600L
        const val BOBBER_LOOK_MS = 600L
        /** The paths of the killer's first rod hit ([Bot.rodHits]): from the recast, from the spawn with none pending, from the spawn before a pending one due later. */
        const val HIT_RECAST = "recast"
        const val HIT_SPAWN = "spawn"
        const val HIT_PENDING = "pending"
        /** Give Up After's default, and the margin the first turn keeps before it. */
        const val GIVE_UP_MS = 20_000.0
        const val FIRST_TURN_MARGIN_MS = 2000.0
        const val MIN_BIN = 15
        /** The peak band's margin against seed noise (amendments §7: p25 × 0.95 .. p75 × 1.05). */
        const val PEAK_MARGIN = 1.05
        const val MIN_FIGHTS = 20
        /** A fight and the wait after its kill, as the learner's looks around tell them. */
        val FIGHT_PHASES = listOf(GlancePolicy.Phase.FIGHT_FREE, GlancePolicy.Phase.FIGHT_AIM, GlancePolicy.Phase.AFTER_KILL)
        /** What the bot never does: walking and teleporting (menus and world changes too), tools, new fishing views. */
        val NOT_BOT = listOf(GlanceMeasure.Kind.LOCOMOTION, GlanceMeasure.Kind.ARTEFACT, GlanceMeasure.Kind.TOOL, GlanceMeasure.Kind.NEW_VIEW)
        /** Looks no plan needs and their way back: away and back, and back to the fight after one. */
        val OTHER_LOOKS = listOf(GlanceMeasure.Kind.LOOK, GlanceMeasure.Kind.RETURN, GlanceMeasure.Kind.BACK_TO_FIGHT)
        /** The bot's moves by what started them: the killer's first turns, its corrections and turn backs, its looks at creatures and their returns, looks around and their returns. */
        val HAND_KINDS = listOf("first turns", "corrections", "turn backs", "looks at creatures", "their returns", "looks around", "returns of looks around")
        /**
         * Moves of the hands, as the learner finds them: turns toward a creature and back from a fight, looks around and
         * their way back; and every way back (to where a turn, a look or the fight started), whose first stroke is learned
         * on its own ([BehaviorLearner.WaysBack]).
         */
        val HAND_GROUPS = listOf(
            "aims and turn backs" to listOf(GlanceMeasure.Kind.AIM, GlanceMeasure.Kind.TURN_BACK),
            "looks and their way back" to listOf(GlanceMeasure.Kind.LOOK, GlanceMeasure.Kind.RETURN, GlanceMeasure.Kind.BACK_TO_FIGHT, GlanceMeasure.Kind.NEW_VIEW),
            "ways back (turn backs, returns, back to the fight)" to BehaviorLearner.WaysBack.KINDS.toList(),
            "  turn backs (their own hand)" to BehaviorLearner.WaysBack.TURN_BACKS.toList(),
            "  ways back from a look (returns, back to the fight: their own hand)" to BehaviorLearner.WaysBack.FROM_LOOKS.toList(),
        )
        const val MIN_CLASS = 10
        /** A creature the bot made its target this long before its death or earlier had time for a turn and the click after it. */
        const val TARGET_LEFT_MS = 600L
        /** A class's turn rate is held within 0.5-1.5 x yours from this many of your creatures and this many turns of yours on. */
        const val MIN_BAND = 15
        const val MIN_TURNS = 5
        /** A cast waits this long at most for a look around to come back (all but 1 % of them), and one still waiting this long never went out. */
        const val MAX_CAST_WAIT_MS = 3000.0
        const val MIN_SAMPLES = 8
        const val MIN_TIMING = 50
        /** A slow-after-slow rate from fewer slow waits followed by another says nothing. */
        const val MIN_PAIRS = 20
        const val TIMING_DRAWS = 20_000
        /** The driver's clock is read this much past the tick's at the most (a coarse wall clock: 15.6 ms on Windows). */
        const val READ_LAG_MS = 16.0
        /** Rod melee gaps per run of hits in the drawn rhythm (yours: 173 gaps in runs of 2 or more). */
        const val MELEE_RUN = 6

        private var cached: Replay? = null

        fun replay(): Replay {
            val path = System.getenv("LBQ_RECORDINGS_DIR")
            assumeTrue("LBQ_RECORDINGS_DIR is not set", ! path.isNullOrBlank())
            val files = File(path).listFiles { f -> f.extension == "jsonl" }?.sortedBy { it.name }.orEmpty()
            assumeTrue("no recordings in $path", files.isNotEmpty())
            cached?.let { return it }
            val started = System.nanoTime()
            val user = BehaviorLearner.learn(files)
            val recs = files.map { read(it) }
            // your phases and in-place ticks, as the learner measured your looks around
            val weapons = user.weaponStyles.keys.filterTo(HashSet()) { it !in user.fishingRods && it !in WeaponPlanner.MOBILITY && it !in WeaponPlanner.NOT_WEAPONS }
            val sessions = recs.map { GlanceMeasure.read(it.lines, user.fishingRods, { id -> id in weapons }) }
            val area = GlanceMeasure.areaClasses(sessions.flatMap { it.clicks })
            val timelines = sessions.map { s ->
                GlanceMeasure.measure(GlanceMeasure.Session(s.lines, s.casts, s.reels, s.lives, s.others, s.menus, s.worlds, s.slots, s.clicks, s.rods, s.isWeapon, area))
            }
            val userKinds = Kinds()
            timelines.forEach { userKinds.add(it) }
            val minutes = recs.sumOf { fightMs(it) } / 60_000.0
            val userMotion = Motion(minutes)
            recs.forEach { measure(userMotion, it.rots.map { r -> ViewMotion.Line(r.t, r.yaw, r.pitch) }) }
            val userTypes = HashMap<String, FightCount>()
            for (rec in recs) countTypes(userTypes, rec, movesOf(rec.rots.map { r -> ViewMotion.Line(r.t, r.yaw, r.pitch) }), user)
            val userComparable = HashMap<String, FightCount>()
            for ((k, rec) in recs.withIndex()) countTypes(userComparable, rec, timelines[k].moves.filter { it.kind !in NOT_BOT }.map { it.move }, user)
            val counted = recs.map { counted(it) }
            // the caches every bot reads, filled once for your profile before the bots run side by side
            HumanAim.paramsOf(user.motor)
            HumanAim.returnParamsOf(user.motor)
            HumanAim.glanceParamsOf(user.motor, user.glances)
            GlancePolicy.Table.of(user.glances)
            ViewPolicy.Looks.Table.of(user.policy.looks)
            // LBQ_REPLAY_KEEP: a folder the bots' recordings are kept in, to look at them
            val keepDir = System.getenv("LBQ_REPLAY_KEEP")?.takeIf { it.isNotBlank() }?.let { File(it).apply { mkdirs() } }
            val tmp = keepDir ?: Files.createTempDirectory("lbq-replay").toFile()
            val pool = Executors.newFixedThreadPool(THREADS)
            try {
                val jobs = ArrayList<Future<Run>>()
                for (g in 0 until SEED_GROUPS) for (j in 0 until REPLAYS) for ((k, rec) in recs.withIndex()) {
                    val out = File(tmp, "seed$g-$j-${rec.file.name}")
                    jobs += pool.submit(Callable { runOf(g, rec, Bot(rec, user, SEED + g * GROUP_STRIDE + j * 100 + k, timelines[k]).run(), out, user, weapons, area, counted[k]) })
                }
                val runs = jobs.map { it.get() }
                // the bots learned with your learner: over every seed (judged), and each seed alone (its spread)
                val pooledBot = pool.submit(Callable { BehaviorLearner.learn(runs.map { it.file }) })
                val seedBots = (0 until SEED_GROUPS).map { g -> pool.submit(Callable { BehaviorLearner.learn(runs.filter { it.group == g }.map { it.file }) }) }
                fun side(rs: List<Run>, bot: Profile) =
                    Side(bot, Motion.of(rs.map { it.motion }), Kinds().also { k -> rs.forEach { k.add(it.kinds) } }, GlanceCount.of(rs.map { it.glances }))
                val pooled = side(runs, pooledBot.get())
                val perSeed = (0 until SEED_GROUPS).map { g -> side(runs.filter { it.group == g }, seedBots[g].get()) }
                // moves of 6 degrees or more per fight, by the class of the fight's first creature
                val types = HashMap<String, Pair<FightCount, FightCount>>()
                for ((type, c) in userTypes) types[type] = c to FightCount()
                for (r in runs) for ((type, c) in r.types) {
                    val b = types.getOrPut(type) { FightCount() to FightCount() }.second
                    b.fights += c.fights
                    b.moves += c.moves
                }
                // the same without what the bot never does, as the learner classifies both sides' moves
                val comparable = HashMap<String, Pair<FightCount, FightCount>>()
                for ((type, c) in userComparable) comparable[type] = c to FightCount()
                for (r in runs) for ((type, c) in r.comparable) {
                    val b = comparable.getOrPut(type) { FightCount() to FightCount() }.second
                    b.fights += c.fights
                    b.moves += c.moves
                }
                val botGaps = HashMap<String, MutableList<Double>>()
                for (r in runs) for ((kind, gaps) in r.gaps) botGaps.getOrPut(kind, ::ArrayList) += gaps
                val targetable = HashMap<String, Pair<Int, Int>>()
                for (r in runs) for ((cls, n) in r.targetable) targetable.merge(cls, n) { a, c -> a.first + c.first to a.second + c.second }
                val userDwells = timelines.indices.flatMap { k -> castFreeDwells(timelines[k], sessions[k]) }
                val userLandings = recs.flatMap { rec -> landings(rec, rec.rots.map { r -> ViewMotion.Line(r.t, r.yaw, r.pitch) }) }
                val hands = HashMap<String, Pair<Int, Int>>()
                for (r in runs) for ((kind, n) in r.hands) hands.merge(kind, n) { a, b -> a.first + b.first to a.second + b.second }
                val handKinds = HashMap<String, HashMap<GlanceMeasure.Kind, Pair<Int, Int>>>()
                for (r in runs) for ((hand, ks) in r.handKinds) for ((kind, n) in ks)
                    handKinds.getOrPut(hand, ::HashMap).merge(kind, n) { a, b -> a.first + b.first to a.second + b.second }
                val userKindGaps = HashMap<String, MutableList<Double>>()
                for ((k, rec) in recs.withIndex()) for ((kind, gs) in kindGaps(rec.rots.map { r -> ViewMotion.Line(r.t, r.yaw, r.pitch) }, timelines[k]))
                    userKindGaps.getOrPut(kind, ::ArrayList) += gs
                val botKindGaps = HashMap<String, MutableList<Double>>()
                for (r in runs) for ((kind, gs) in r.kindGaps) botKindGaps.getOrPut(kind, ::ArrayList) += gs
                return Replay(user, userMotion, pooled, perSeed, types, userKinds, botGaps, runs.flatMap { it.corrections }, runs.flatMap { it.looks }, targetable,
                    userDwells, (System.nanoTime() - started) / 1e9, userLandings, runs.flatMap { it.landings }, hands, handKinds,
                    runs.flatMap { it.deathStops }, runs.flatMap { it.rodHits }, userKindGaps, botKindGaps, runs.flatMap { it.swapTurns }, runs.flatMap { it.backTurns }, comparable, runs.flatMap { it.picks }, runs.flatMap { it.lateKills }, recs.flatMap { olderClicks(it) }, runs.flatMap { it.olderClicks }).also { cached = it }
            }
            finally {
                pool.shutdownNow()
                if (keepDir == null) tmp.deleteRecursively()
            }
        }

        /**
         * One replay's measures, taken as soon as its [bot] ran (only its recording [out] is kept, for the learner): its
         * moves as the learner classifies yours (with your phases and area weapons), its movements, its moves per fight by
         * type, its gaps and corrections from its own log, and your [counted] creatures it made its target in time.
         */
        fun runOf(group: Int, rec: Recording, bot: Bot, out: File, user: Profile, weapons: Set<String>, area: Set<String>, counted: List<Triple<Int, String, Boolean>>): Run {
            val s = GlanceMeasure.read(bot.write(out), user.fishingRods, { id -> id in weapons })
            val measured = GlanceMeasure.measure(GlanceMeasure.Session(s.lines, s.casts, s.reels, s.lives, s.others, s.menus, s.worlds, s.slots, s.clicks, s.rods, s.isWeapon, area))
            val kinds = Kinds().also { it.add(measured) }
            val lines = rec.rots.mapIndexed { k, r -> bot.views[k]!!.let { v -> ViewMotion.Line(r.t, v.yaw, v.pitch) } }
            val motion = Motion(fightMs(rec) / 60_000.0)
            measure(motion, lines)
            val types = HashMap<String, FightCount>()
            countTypes(types, rec, movesOf(lines), user)
            val comparable = HashMap<String, FightCount>()
            countTypes(comparable, rec, measured.moves.filter { it.kind !in NOT_BOT }.map { it.move }, user)
            val gaps = HashMap<String, MutableList<Double>>()
            val corrections = ArrayList<Pair<Double, Double>>()
            val moves = ArrayList<ViewMotion.Move>()
            for (run in ViewMotion.runs(lines)) {
                var prev: ViewMotion.Move? = null
                for (mv in ViewMotion.segment(run).moves) {
                    if (mv.cut) continue
                    moves += mv
                    val a = prev
                    prev = mv
                    if (a == null || a.amp < 6 || mv.amp < 3 || mv.t0 - a.t1 > 3000) continue
                    // a start between the two moves began the later one; none: the later one is a stroke of the same move
                    val st = bot.starts.lastOrNull { it.t <= mv.t0 + TICK && it.t >= a.t1 - 2 * TICK }
                    gaps.getOrPut(when (st?.kind) { null -> "a stroke of the same move"; "back" -> "a turn back"; "first" -> "a first turn"; "look" -> "a look"; "return" -> "a look's return"; else -> "a correction" }, ::ArrayList) +=
                        (mv.t0 - a.t1).toDouble()
                }
            }
            // the error at each one's start as the learner measures yours: on the move it started, one of 6 degrees or more as
            // yours were, at that move's start to the creature it brought the view closest to (NaN otherwise; its gap counts)
            val rotAt = HashMap<Long, Int>()
            rec.rots.forEachIndexed { k, r -> rotAt[r.t] = k }
            for (st in bot.starts) {
                if (st.kind != "correction") continue
                val prev = moves.lastOrNull { it.amp >= 3 && it.t1 <= st.t } ?: continue
                val mv = moves.firstOrNull { it.t0 >= st.t - TICK && it.t0 <= st.t + 3 * TICK }?.takeIf { it.amp >= 6 }
                corrections += (mv?.let { correctionStart(rec, bot.views, rotAt, it) } ?: Double.NaN) to (st.t - prev.t1).toDouble()
            }
            // a move started in the tick of its line before the first one that moved, or the tick after (frames in between)
            val hands = HashMap<String, Pair<Int, Int>>()
            fun handOf(mv: ViewMotion.Move) = bot.hands.lastOrNull { it.first in mv.t0 - 2 * TICK.toLong() .. mv.t0 + TICK.toLong() }?.second
            for (mv in moves) {
                if (mv.amp < 20.0) continue
                val kind = handOf(mv) ?: continue
                hands.merge(kind, 1 to (if (mv.over > 1) 1 else 0)) { a, b -> a.first + b.first to a.second + b.second }
            }
            val handKinds = HashMap<String, HashMap<GlanceMeasure.Kind, Pair<Int, Int>>>()
            for (m in measured.moves) {
                if (m.move.amp < 20.0) continue
                val hand = handOf(m.move) ?: continue
                handKinds.getOrPut(hand, ::HashMap).merge(m.kind, 1 to (if (m.move.over > 1) 1 else 0)) { a, b -> a.first + b.first to a.second + b.second }
            }
            // your counted creatures (the learner's: alive 1 s or more, 15 degrees or more off 300-600 ms after the
            // spawn) the bot made its target with 600 ms or more left, creatures right overhead left out (no look goes there)
            val targetable = HashMap<String, Pair<Int, Int>>()
            for ((id, cls, overhead) in counted) {
                val since = bot.targetSince[id]
                val ok = ! overhead && since != null && rec.deaths.getValue(id) - since >= TARGET_LEFT_MS
                targetable.merge(cls, (if (ok) 1 else 0) to 1) { a, c -> a.first + c.first to a.second + c.second }
            }
            return Run(group, out, motion, kinds, types, gaps, corrections, bot.lookLog, targetable,
                GlanceCount(bot.glanceEpisodes, bot.glanceForbidden, bot.castDelays, bot.castsStuck, bot.homeMoved, castFreeDwells(measured, s), bot.castSlack, bot.restRecasts, bot.restCasts),
                landings(rec, lines), hands,
                handKinds, bot.deathStops, bot.rodHits, kindGaps(lines, measured), bot.swapTurns, bot.backTurns, comparable, learnerPicks(rec, lines, bot.hands, bot.slots), bot.lateKills, bot.olderClicks)
        }

        /**
         * The moves the learner takes for the first turn after a swap and for the turn back on [lines] (the bot's view on
         * [rec]'s ticks), the way BehaviorLearner.learnViews finds them, each with the bot's own move that started it
         * ([hands], the [HAND_KINDS] entry; `none` without one): (what, kind, ms from the swap or the last death).
         */
        fun learnerPicks(rec: Recording, lines: List<ViewMotion.Line>, hands: List<Pair<Long, String>>, slots: List<Pair<Long, String>>): List<Triple<String, String, Long>> {
            val out = ArrayList<Triple<String, String, Long>>()
            val times = rec.rots.map { it.t }.toLongArray()
            val mvs = movesOf(lines)
            fun at(t: Long) = max(0, lastAtOrBefore(times, t))
            fun err(k: Int, m: Mob) = rec.rots[k].let { r -> directionError(View(lines[k].yaw, lines[k].pitch), m.x - r.x, m.y + (if (m.h > 0) m.h else NO_MOB_HEIGHT) * TURN_AIM_SHARE - r.y - EYE, m.z - r.z) }
            fun home(y: Double, p: Double, gy: Double, gp: Double) = if (abs(gp) >= 89.5) abs(p - gp) else MotorModel.angle(y, p, gy, gp)
            fun kindAt(t0: Long) = hands.lastOrNull { it.first in t0 - 2 * TICK.toLong() .. t0 + TICK.toLong() }?.second ?: "none"
            val spans = ArrayList<Triple<Long, Long, MutableList<Int>>>()
            for ((id, s) in rec.spawns.entries.sortedBy { it.value }) {
                val d = rec.deaths[id] ?: continue
                val last = spans.lastOrNull()
                if (last != null && s <= last.second) {
                    spans[spans.size - 1] = Triple(last.first, max(last.second, d), last.third)
                    last.third += id
                }
                else spans += Triple(s, d, mutableListOf(id))
            }
            for ((s0, s1, ids) in spans) {
                if (s1 - s0 in 1000L .. 120_000L) {
                    val found = mvs.firstOrNull { m ->
                        if (m.t0 < s0 || m.t0 > s1 || m.amp < 6) return@firstOrNull false
                        val a = at(m.t0)
                        val b = at(m.t1)
                        ids.any { id ->
                            val ca = rec.rots[a].c.firstOrNull { it.id == id } ?: return@any false
                            val cb = rec.rots[b].c.firstOrNull { it.id == id } ?: return@any false
                            val ea = err(a, ca)
                            ea >= 15.0 && err(b, cb) <= 0.6 * ea
                        }
                    }
                    if (found != null) {
                        val target = ids.first { id ->
                            val ca = rec.rots[at(found.t0)].c.firstOrNull { it.id == id } ?: return@first false
                            val cb = rec.rots[at(found.t1)].c.firstOrNull { it.id == id } ?: return@first false
                            val ea = err(at(found.t0), ca)
                            ea >= 15.0 && err(at(found.t1), cb) <= 0.6 * ea
                        }
                        val spawn = rec.spawns.getValue(target)
                        slots.firstOrNull { it.first >= spawn - 200 && it.first <= found.t0 + 100 }?.let { sw ->
                            val kind = kindAt(found.t0)
                            out += Triple("swap", kind, found.t0 - sw.first)
                            // a later move taken: why the bot's own first turn after that swap was none of the learner's
                            if (kind != HAND_KINDS[0]) {
                                val own = hands.firstOrNull { it.first >= sw.first && it.second == HAND_KINDS[0] }
                                val mv = own?.let { o -> mvs.firstOrNull { it.t0 in o.first - 2 * TICK.toLong() .. o.first + TICK.toLong() } }
                                val why = when {
                                    own == null || own.first > found.t0 -> "no first turn of its own before"
                                    mv == null -> "its first turn no move of its own (merged or under 15 deg/s)"
                                    mv.amp < 6 -> "its first turn under 6 deg"
                                    else -> {
                                        val a = at(mv.t0)
                                        val b = at(mv.t1)
                                        val ea = ids.mapNotNull { id -> rec.rots[a].c.firstOrNull { it.id == id }?.let { err(a, it) } }.maxOrNull() ?: 0.0
                                        val ratio = ids.mapNotNull { id ->
                                            val ca = rec.rots[a].c.firstOrNull { it.id == id } ?: return@mapNotNull null
                                            val cb = rec.rots[b].c.firstOrNull { it.id == id } ?: return@mapNotNull null
                                            err(b, cb) / max(1e-9, err(a, ca))
                                        }.minOrNull() ?: Double.NaN
                                        if (ea < 15.0) "its first turn from under 15 deg" else "its first turn left %.1f of the error".format(ratio)
                                    }
                                }
                                out += Triple("swap's own first turn not taken", why, own?.let { found.t0 - it.first } ?: - 1L)
                            }
                        }
                    }
                }
                if (s1 - s0 > 120_000L) continue
                val a = at(s0)
                val b = at(s1)
                // the bot's own turn back after this fight, and what became of it in the learner's measure
                val own = hands.firstOrNull { it.second == HAND_KINDS[2] && it.first >= s1 - 50 && it.first <= s1 + 3000 }?.first
                fun ownRow(what: String) { if (own != null) out += Triple("own turn back", what, own - s1) }
                if (abs(rec.rots[a].t - s0) > 200 || abs(rec.rots[b].t - s1) > 200) {
                    ownRow("no lines at the fight's ends")
                    continue
                }
                val e = home(lines[b].yaw, lines[b].pitch, lines[a].yaw, lines[a].pitch)
                if (e <= 8.0) {
                    ownRow("its fight ended within 8 deg of its start view")
                    continue
                }
                val back = mvs.firstOrNull { it.t0 >= s1 - 50 && it.t0 <= s1 + 3000 && home(it.y1, it.p1, lines[a].yaw, lines[a].pitch) <= e * 0.5 }
                if (back == null) {
                    ownRow("none within half the way to its start view")
                    continue
                }
                out += Triple("back", kindAt(back.t0), back.t0 - s1)
                ownRow(if (own != null && abs(back.t0 - own) <= TICK.toLong()) "taken" else "another move taken")
            }
            return out
        }

        /**
         * The gaps between separate moves the "gap between separate moves" row counts ([measure]: the earlier move 6° or more,
         * the later 3° or more, at most 3 s apart, within a run of lines), by what the learner found the later one did
         * ([GlanceMeasure.Kind], `small` for one under 6°, which it does not classify).
         */
        fun kindGaps(lines: List<ViewMotion.Line>, measured: GlanceMeasure.Result): Map<String, List<Double>> {
            val kindAt = measured.moves.associate { it.move.t0 to it.kind.name.lowercase() }
            val out = HashMap<String, MutableList<Double>>()
            for (run in ViewMotion.runs(lines)) {
                var prev: ViewMotion.Move? = null
                for (mv in ViewMotion.segment(run).moves) {
                    if (mv.cut) continue
                    val a = prev
                    prev = mv
                    if (a == null || a.amp < 6 || mv.amp < 3 || mv.t0 - a.t1 > 3000) continue
                    out.getOrPut(kindAt[mv.t0] ?: if (mv.amp < 6) "small" else "unclassed", ::ArrayList) += (mv.t0 - a.t1).toDouble()
                }
            }
            return out
        }

        /**
         * Where the moves of 6° or more in [lines] (yours, or a bot's on [rec]'s ticks) that turned toward one of your melee
         * creatures (class `:L`, leaving at most 0.6 of the error to its half height: the learner's turn) came to rest when
         * that was on its box: how far beside its centre line, in shares of the half-width the box shows the eye
         * (AutoMobKiller's landOffset), unsigned. On the box: the view ray passes the creature's centre within that
         * half-width and its height; creatures right above or below the eye and views 60° or more beside them are none.
         */
        /**
         * Your first clicks at a creature of yours already there (up 100 ms or more before) after another of yours died while
         * you fought it (a click of yours 1.5 s or less before), with no slot change before them, within 3 s: ms from the
         * death, and whether it was a rod's left click (humanize/r10/t/deathnext.js, no swap); a rod's right click there (a cast) is none.
         */
        fun olderClicks(rec: Recording): List<Pair<Long, Boolean>> {
            val out = ArrayList<Pair<Long, Boolean>>()
            val own = HashSet<Int>()
            val alive = HashMap<Int, Long>()
            var lastClick = Long.MIN_VALUE / 2
            val lines = rec.lines
            for ((i, e) in lines.withIndex()) {
                val type = e.str("e")
                val t = e.long("t")
                val id = e.int("id")
                when (type) {
                    "spawn" -> if (e.get("own")?.asBoolean == true) { own += id; alive[id] = t }
                    "own" -> if (e.get("own")?.asBoolean == true) { own += id; alive[id] = t } else { own -= id; alive.remove(id) }
                    "click" -> if (e.get("auto")?.asBoolean != true) lastClick = t
                }
                if (type != "death" || id !in own) continue
                alive.remove(id)
                val older = alive.filterValues { it < t - 100 }.keys
                if (older.isEmpty() || t - lastClick > 1500) continue
                for (j in i + 1 until lines.size) {
                    val c = lines[j]
                    if (c.long("t") > t + 3000) break
                    if (c.str("e") == "slot") break
                    if (c.str("e") != "click" || c.get("auto")?.asBoolean == true) continue
                    val at = if (c.has("target")) c.int("target") else if (c.has("near") && c.dbl("err") < 25) c.int("near") else null
                    if (at == null || at !in older) continue
                    val item = c.str("item")
                    val rod = item.contains("ROD") && ! item.contains("FLAY")
                    // a rod's right click is a cast at it, the bobber's (no click of the killer's own plan)
                    if (! rod || c.str("b") == "L") out += (c.long("t") - t) to rod
                    break
                }
            }
            return out
        }

        fun landings(rec: Recording, lines: List<ViewMotion.Line>): List<Double> {
            val out = ArrayList<Double>()
            val times = rec.rots.map { it.t }.toLongArray()
            fun height(m: Mob) = if (m.h > 0) m.h else NO_MOB_HEIGHT
            for (mv in movesOf(lines)) {
                if (mv.amp < 6.0) continue
                val a = rec.rots.getOrNull(lastAtOrBefore(times, mv.t0)) ?: continue
                val b = rec.rots.getOrNull(lastAtOrBefore(times, mv.t1)) ?: continue
                val va = View(mv.y0, mv.p0)
                val vb = View(mv.y1, mv.p1)
                var best: Mob? = null
                var bestErr = Double.MAX_VALUE
                for (m in b.c) {
                    if (! rec.classOf(m.id).endsWith(":L")) continue
                    val ma = a.c.firstOrNull { it.id == m.id } ?: continue
                    val ea = directionError(va, ma.x - a.x, ma.y + height(ma) * TURN_AIM_SHARE - a.y - EYE, ma.z - a.z)
                    val eb = directionError(vb, m.x - b.x, m.y + height(m) * TURN_AIM_SHARE - b.y - EYE, m.z - b.z)
                    if (ea < 6.0 || eb > 0.6 * ea || eb >= bestErr) continue
                    best = m
                    bestErr = eb
                }
                val m = best ?: continue
                val dx = m.x - b.x
                val dz = m.z - b.z
                val flat = hypot(dx, dz)
                val half = (if (m.w > 0) m.w else NO_MOB_WIDTH) / 2 * (abs(dx) + abs(dz)) / max(flat, 1e-9)
                if (flat < half + 0.2) continue
                val beside = MotorModel.wrap(vb.yaw - Math.toDegrees(atan2(- dx, dz)))
                if (abs(beside) >= 60.0) continue
                val side = flat * tan(Math.toRadians(beside)) / half
                val up = (b.y + EYE - tan(Math.toRadians(vb.pitch)) * flat / cos(Math.toRadians(beside)) - m.y) / height(m)
                if (abs(side) <= 1.0 && up in 0.0 .. 1.0) out += abs(side)
            }
            return out
        }

        /**
         * The dwells before a look's return from your fishing view (as the learner measures them) that no cast of the
         * [session] came during: one you made while still looking away, or one of the bot's that came due (its recording
         * keeps your cast times) and brought its look back at once.
         */
        fun castFreeDwells(measured: GlanceMeasure.Result, session: GlanceMeasure.Session): List<Long> = measured.looks.filter { l ->
            val end = l.t0 + l.dur.toLong()
            l.dwellMs >= 0 && session.casts.none { it.t > end && it.t < end + l.dwellMs }
        }.map { it.dwellMs }

        /** Adds [rec]'s fights and the [moves] of 6 degrees or more in them to [types], by the class of each fight's first creature. */
        fun countTypes(types: MutableMap<String, FightCount>, rec: Recording, moves: List<ViewMotion.Move>, user: Profile) {
            val fights = rec.fights
            for (f in fights) types.getOrPut(typeOf(f.third, user), ::FightCount).fights++
            for (m in moves) {
                if (m.amp < 6.0) continue
                val f = fights.lastOrNull { it.first <= m.t0 && m.t0 <= it.second + RESET_GAP_MS } ?: continue
                types.getValue(typeOf(f.third, user)).moves++
            }
        }

        /** Your creatures in [rec] the learner counts for a turn: id, class, right overhead 300-600 ms after the spawn. */
        fun counted(rec: Recording): List<Triple<Int, String, Boolean>> {
            val out = ArrayList<Triple<Int, String, Boolean>>()
            val times = rec.rots.map { it.t }.toLongArray()
            for ((id, s) in rec.spawns) {
                val d = rec.deaths[id] ?: continue
                if (d - s < 1000) continue
                var i = lastAtOrBefore(times, s + 299) + 1
                var found: Pair<Rot, Mob>? = null
                while (i < rec.rots.size && rec.rots[i].t <= s + 600 && found == null) {
                    rec.rots[i].c.firstOrNull { it.id == id }?.let { found = rec.rots[i] to it }
                    i++
                }
                val (r, m) = found ?: continue
                val h = if (m.h > 0) m.h else NO_MOB_HEIGHT
                val error = directionError(View(r.yaw, r.pitch), m.x - r.x, m.y + h * TURN_AIM_SHARE - r.y - EYE, m.z - r.z)
                if (error < 15.0) continue
                out += Triple(id, rec.classOf(id), hypot(m.x - r.x, m.z - r.z) < BehaviorProfile.Looks.OVERHEAD_SIDE && error > BehaviorProfile.Looks.OVERHEAD_DEG)
            }
            return out
        }

        /** Rod melee, the rod's bobber (no clicks or rod right clicks), or the weapon and its button. */
        fun typeOf(cls: String, p: Profile): String {
            val item = cls.substringBefore(':')
            return when {
                cls == "none" || item == "?" -> "bobber"
                item in p.fishingRods -> if (cls.endsWith(":L")) "rod melee" else "bobber"
                else -> cls
            }
        }

        fun fightMs(rec: Recording): Long {
            var ms = 0L
            for (k in 1 until rec.rots.size) {
                val d = rec.rots[k].t - rec.rots[k - 1].t
                if (d in 1 .. ViewMotion.MAX_GAP_MS) ms += d
            }
            return ms
        }

        fun movesOf(lines: List<ViewMotion.Line>) = ViewMotion.runs(lines).flatMap { ViewMotion.segment(it).moves }.filter { ! it.cut }

        /** Adds one recording's view to [m]: movements, gaps between them, rest ticks, the fastest tick. */
        fun measure(m: Motion, lines: List<ViewMotion.Line>) {
            for (run in ViewMotion.runs(lines)) {
                val seg = ViewMotion.segment(run)
                var prev: ViewMotion.Move? = null
                for (mv in seg.moves) {
                    if (mv.cut) continue
                    m.all += mv
                    if (mv.first == mv.last && mv.amp < 2.0) m.nudgeMoves++
                    prev?.let { a -> (mv.t0 - a.t1).takeIf { a.amp >= 6 && mv.amp >= 3 && it <= 3000 }?.let { m.gaps += it.toDouble() } }
                    prev = mv
                }
                for (i in 1 until run.size) {
                    val dt = run[i].dt
                    if (dt >= 20.0) {
                        m.maxGc = max(m.maxGc, seg.speed[i])
                        m.maxMouse = max(m.maxMouse, hypot(seg.dYaw[i], seg.dPitch[i]) / dt * 1000)
                    }
                    if (seg.inMove[i]) continue
                    m.restTicks++
                    if (abs(seg.dYaw[i]) > ViewMotion.STILL_DEG || abs(seg.dPitch[i]) > ViewMotion.STILL_DEG) m.restChanged++
                    for (d in doubleArrayOf(seg.dYaw[i], seg.dPitch[i])) if (abs(d) > ViewMotion.STILL_DEG) m.restSteps += (abs(d) / ViewMotion.GRID_DEG).roundToInt()
                }
            }
        }

        fun read(file: File): Recording {
            val lines = ArrayList<JsonObject>()
            file.forEachLine { l -> if (l.isNotBlank()) runCatching { JsonParser.parseString(l).asJsonObject }.getOrNull()?.let { lines += it } }
            val rots = ArrayList<Rot>()
            val spawns = HashMap<Int, Long>()
            val deaths = HashMap<Int, Long>()
            val clicks = ArrayList<JsonObject>()
            val slotOf = HashMap<String, Int>()
            val rodFlag = HashMap<String, Boolean>()
            val names = HashMap<Int, String>()
            val children = HashSet<Int>()
            for ((i, o) in lines.withIndex()) when (o.str("e")) {
                "rot" -> rots += Rot(i, o.long("t"), o.dbl("yaw"), o.dbl("pitch"), o.dbl("x"), o.dbl("y"), o.dbl("z"),
                    o.get("c")?.asJsonArray?.map { e ->
                        val a = e.asJsonArray
                        Mob(a[0].asInt, a[1].asDouble, a[2].asDouble, a[3].asDouble, if (a.size() > 4) a[4].asDouble else - 1.0, if (a.size() > 5) a[5].asDouble else - 1.0)
                    }.orEmpty())
                "spawn" -> if (o.get("own")?.asBoolean == true) {
                    spawns.putIfAbsent(o.int("id"), o.long("t"))
                    names.putIfAbsent(o.int("id"), o.str("name"))
                    if (o.str("parent").isNotEmpty()) children += o.int("id")
                }
                "death" -> deaths.putIfAbsent(o.int("id"), o.long("t"))
                "click", "slot" -> {
                    val item = o.str("item")
                    if (item.isNotEmpty()) {
                        o.get("slot")?.let { slotOf[item] = it.asInt }
                        rodFlag[item] = o.get("rod")?.asBoolean == true
                    }
                    if (o.str("e") == "click" && o.get("cast")?.asBoolean != true && o.get("auto")?.asBoolean != true) clicks += o
                }
            }
            // the learner's class of a creature: your most frequent attack (not a cast) while it was alive and in the line
            val times = rots.map { it.t }.toLongArray()
            val counts = HashMap<Int, LinkedHashMap<String, Int>>()
            for (k in clicks.sortedBy { it.long("t") }) {
                val t = k.long("t")
                val r = rots.getOrNull(lastAtOrBefore(times, t)) ?: continue
                for (m in r.c) {
                    val s = spawns[m.id] ?: continue
                    val d = deaths[m.id] ?: continue
                    if (t < s || t > d) continue
                    val cls = k.str("item").ifEmpty { "?" } + ":" + k.str("b") + if (k.get("hold")?.asBoolean == true) "h" else ""
                    counts.getOrPut(m.id, ::LinkedHashMap).merge(cls, 1, Int::plus)
                }
            }
            val casts = lines.filter { it.str("e") == "cast" }.map { it.long("t") }.sorted().toLongArray()
            val bites = lines.filter { it.str("e") == "bite" }.map { it.long("t") }.sorted().toLongArray()
            // where each creature of yours was last seen before it died
            val spots = deaths.entries.filter { it.key in spawns }.mapNotNull { (id, d) ->
                var i = lastAtOrBefore(times, d - 1)
                while (i >= 0 && rots[i].t >= d - 1000 && rots[i].c.none { it.id == id }) i--
                val m = rots.getOrNull(i)?.c?.firstOrNull { it.id == id } ?: return@mapNotNull null
                d to doubleArrayOf(m.x, m.y + (if (m.h > 0) m.h else NO_MOB_HEIGHT) * TURN_AIM_SHARE, m.z)
            }.sortedBy { it.first }
            return Recording(file, lines, rots, spawns, deaths, counts.mapValues { (_, m) -> m.maxBy { it.value }.key }, slotOf, rodFlag, casts, bites, spots, names, children)
        }

        fun lastAtOrBefore(times: LongArray, t: Long): Int {
            var lo = 0
            var hi = times.size - 1
            var found = - 1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                if (times[mid] <= t) {
                    found = mid
                    lo = mid + 1
                }
                else hi = mid - 1
            }
            return found
        }

        /** Angular half-size of [c]'s box from your eye, at least the killer's minimum. */
        fun boxHalf(r: Rot, c: Mob): Double {
            val size = if (c.h > 0) max(c.h, c.w) else NO_MOB_HEIGHT
            val d = max(0.1, sqrt((c.x - r.x) * (c.x - r.x) + (c.y + size / 2 - r.y - EYE).let { it * it } + (c.z - r.z) * (c.z - r.z)))
            return max(FightRules.MIN_BOX_DEG, Math.toDegrees(atan2(size / 2, d)))
        }

        /** Degrees between the view [v] and the direction [dx], [dy], [dz] from the eye. */
        /**
         * The error at the start of [mv], a move of the bot's [views] on [rec]'s ticks ([rotAt]: a tick's line), to the
         * creature of yours it brought the view closest to: the learner's measure of your corrections (the tick before the
         * move's first moving one, to half the creature's height, the creature it closed on most). NaN when it closed on none.
         */
        fun correctionStart(rec: Recording, views: Array<View?>, rotAt: Map<Long, Int>, mv: ViewMotion.Move): Double {
            val k0 = rotAt[mv.t0] ?: return Double.NaN
            val k1 = rotAt[mv.t1] ?: return Double.NaN
            fun error(k: Int, id: Int): Double? {
                val r = rec.rots[k]
                val m = r.c.firstOrNull { it.id == id } ?: return null
                val h = if (m.h > 0) m.h else NO_MOB_HEIGHT
                return directionError(views[k] ?: return null, m.x - r.x, m.y + h * TURN_AIM_SHARE - r.y - EYE, m.z - r.z)
            }
            var start = Double.NaN
            var gain = 0.0
            for (m in rec.rots[k0].c) {
                val a = error(k0, m.id) ?: continue
                val b = error(k1, m.id) ?: continue
                if (b < a && (start.isNaN() || a - b > gain)) {
                    start = a
                    gain = a - b
                }
            }
            return start
        }

        fun directionError(v: View, dx: Double, dy: Double, dz: Double): Double {
            val yaw = Math.toDegrees(atan2(- dx, dz))
            val pitch = Math.toDegrees(- atan2(dy, hypot(dx, dz)))
            return MotorModel.angle(v.yaw, v.pitch, yaw, pitch)
        }

        fun dist(t: Table, name: String, u: Dist, b: Dist, criterion: String, range: ClosedFloatingPointRange<Double>?, fmt: (Double) -> String) {
            val learned = b.n >= MIN_SAMPLES
            val ok = range == null || ! learned || b.p50 in range
            t.row("$name (n ${u.n} / ${b.n})", fmt(u.p50), if (learned) fmt(b.p50) else "too few", criterion, ok)
        }

        /** Slow after slow (above [thr]) with the slow waits it is counted on, and lag-1 of ln, over [xs] in order. */
        fun serial(xs: List<Int>, thr: Double): Serial {
            var pairs = 0
            var both = 0
            for (i in 1 until xs.size) {
                if (xs[i - 1] <= thr) continue
                pairs++
                if (xs[i] > thr) both++
            }
            return Serial(if (pairs == 0) Double.NaN else both.toDouble() / pairs, pairs, lag1(listOf(xs)))
        }

        /** Lag-1 correlation of ln within each run. */
        fun lag1(runs: List<List<Int>>): Double {
            val xs = ArrayList<Double>()
            val ys = ArrayList<Double>()
            for (r in runs) for (i in 1 until r.size) {
                xs += ln(max(1, r[i - 1]).toDouble())
                ys += ln(max(1, r[i]).toDouble())
            }
            val mx = xs.average()
            val my = ys.average()
            var sxy = 0.0
            var sxx = 0.0
            var syy = 0.0
            for (i in xs.indices) {
                sxy += (xs[i] - mx) * (ys[i] - my)
                sxx += (xs[i] - mx) * (xs[i] - mx)
                syy += (ys[i] - my) * (ys[i] - my)
            }
            return if (sxx <= 0.0 || syy <= 0.0) 0.0 else sxy / sqrt(sxx * syy)
        }

        /** Kolmogorov-Smirnov distance between the nearest-tick counts of two sets of waits. */
        fun tickKs(a: List<Int>, b: List<Int>): Double {
            val x = a.map { Math.round(it / TICK) }.sorted()
            val y = b.map { Math.round(it / TICK) }.sorted()
            var i = 0
            var j = 0
            var d = 0.0
            while (i < x.size && j < y.size) {
                val v = min(x[i], y[j])
                while (i < x.size && x[i] <= v) i++
                while (j < y.size && y[j] <= v) j++
                d = max(d, abs(i.toDouble() / x.size - j.toDouble() / y.size))
            }
            return d
        }

        fun q(xs: Collection<Double>, p: Double) = ViewMotion.quantile(xs, p)

        /**
         * Where the p50 of your [d] could lie for its spread and count: ±1.96 standard errors of a lognormal's median
         * (1.2533 σ / √n in ln), widened by [margin] each way against the bot's seed noise.
         */
        fun medianBand(d: Dist, margin: Double = PEAK_MARGIN): ClosedFloatingPointRange<Double> {
            val half = 1.96 * 1.2533 * d.lnSigma / sqrt(max(1, d.n).toDouble())
            return d.p50 * exp(- half) * (2 - margin) .. d.p50 * exp(half) * margin
        }

        fun sd(xs: List<Double>): Double {
            if (xs.size < 2) return 0.0
            val m = xs.average()
            return sqrt(xs.sumOf { (it - m) * (it - m) } / (xs.size - 1))
        }

        fun <T> share(xs: List<T>, f: (T) -> Boolean) = if (xs.isEmpty()) Double.NaN else xs.count(f).toDouble() / xs.size

        fun round2(v: Double) = Math.round(v * 100.0) / 100.0

        fun binOf(amp: Double) = Motor.BINS.indexOfLast { amp >= it }

        fun binName(bin: Int) = Motor.BINS[bin].toInt().toString() + (Motor.BINS.getOrNull(bin + 1)?.let { "-${it.toInt()}" } ?: "+") + " deg"

        fun pct(v: Double) = if (v.isNaN()) "-" else "%.1f %%".format(100 * v)

        fun f2(v: Double) = if (v.isNaN()) "-" else "%.2f".format(v)

        fun ms(v: Double) = if (v.isNaN()) "-" else "%.0f ms".format(v)

        fun deg(v: Double) = if (v.isNaN()) "-" else "%.1f deg".format(v)

        fun JsonObject.str(key: String) = get(key)?.takeIf { it.isJsonPrimitive }?.asString ?: ""

        fun JsonObject.long(key: String) = get(key)?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L

        fun JsonObject.int(key: String) = get(key)?.takeIf { it.isJsonPrimitive }?.asInt ?: 0

        fun JsonObject.dbl(key: String) = get(key)?.takeIf { it.isJsonPrimitive }?.asDouble ?: 0.0
    }
}
