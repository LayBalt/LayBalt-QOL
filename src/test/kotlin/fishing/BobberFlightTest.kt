package fishing

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.laybalt.features.impl.fishing.BobberFlight
import com.laybalt.features.impl.fishing.BobberFlight.Blocks
import com.laybalt.features.impl.fishing.BobberFlight.Landing
import com.laybalt.features.impl.fishing.BobberFlight.Medium
import com.laybalt.features.impl.fishing.BobberFlight.Medium.AIR
import com.laybalt.features.impl.fishing.BobberFlight.Medium.LAVA
import com.laybalt.features.impl.fishing.BobberFlight.Medium.SOLID
import com.laybalt.features.impl.fishing.BobberFlight.Medium.WATER
import com.laybalt.features.impl.fishing.BobberFlight.Verdict
import com.laybalt.features.impl.fishing.TimingModel
import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.*

/**
 * The 1.8.9 bobber flight Hypixel's server runs (bobber SPEC §8): small worlds with the landings of the reference flight
 * (±0.01 blocks), the decisions of [BobberFlight.judge], the launch noise it leaves out, and (env-gated) the Bayou
 * recording's casts against flat water.
 */
class BobberFlightTest {
    /** Open water with its surface at 63 and you on a one-block stone pillar at (0, 63, 0). */
    private val openWater = Blocks { x, y, z -> if (x == 0 && z == 0 && y == 63) SOLID else if (y <= 62) WATER else AIR }

    /** A pond x -8..8, z 1..13 (surface 63) in ground whose top is 63. */
    private fun pond(x: Int, y: Int, z: Int) = if (y > 62) AIR else if (x in - 8 .. 8 && z in 1 .. 13) WATER else SOLID

    private fun fly(blocks: Blocks, feetX: Double, feetY: Double, feetZ: Double, yaw: Double, pitch: Double, sneaking: Boolean = false) =
        BobberFlight.fly(feetX, feetY, feetZ, yaw, pitch, sneaking, blocks)

    private fun assertLanding(medium: Medium, x: Double, z: Double, l: Landing, what: String, y: Double? = null) {
        assertEquals(medium, l.medium, what)
        assertEquals(x, l.x, 0.01, "$what: x")
        assertEquals(z, l.z, 0.01, "$what: z")
        y?.let { assertEquals(it, l.y, 0.01, "$what: y") }
    }

    @Test
    fun `a cast into open water from a pillar slides on to where your bobbers floated`() {
        val expected = listOf(- 30.0 to 16.306, - 10.0 to 17.251, 0.0 to 16.129, 15.0 to 12.737, 30.0 to 9.975, 45.0 to 7.756, 60.0 to 5.262)
        val contact = listOf(36, 21, 14, 6, 3, 2, 1)
        expected.forEachIndexed { i, (pitch, z) ->
            val l = fly(openWater, 0.5, 64.0, 0.5, 0.0, pitch)
            assertLanding(WATER, 0.340, z, l, "pitch $pitch")
            assertEquals(63.0, l.surface, 1e-9, "pitch $pitch: surface")
            assertEquals(contact[i], l.contactTick, "pitch $pitch: first tick in the water")
        }
        assertEquals(62.906, fly(openWater, 0.5, 64.0, 0.5, 0.0, - 30.0).y, 0.01, "floating just under the surface")
        // casting at your feet lands on the pillar
        assertLanding(SOLID, 0.340, 0.895, fly(openWater, 0.5, 64.0, 0.5, 0.0, 75.0), "pitch 75", y = 64.0)
        assertLanding(SOLID, 0.340, 0.503, fly(openWater, 0.5, 64.0, 0.5, 0.0, 89.9), "pitch 89.9", y = 64.0)
        assertTrue(fly(openWater, 0.5, 64.0, 0.5, 0.0, 75.0).surface.isNaN())
        // the hook spawns 0.16 to the right of the view, whichever way you look
        assertLanding(WATER, - 6.756, 0.340, fly(openWater, 0.5, 64.0, 0.5, 90.0, 45.0), "yaw 90")
        assertLanding(WATER, 0.340, 7.756, fly(openWater, 0.5, 64.0, 0.5, 360.0, 45.0), "yaw 360")
        assertLanding(WATER, 0.340, 7.756, fly(openWater, 0.5, 64.0, 0.5, - 720.0, 45.0), "yaw -720")
        // sneaking lowers the spawn by 0.08: only the first tick in the water could tell
        val sneaking = fly(openWater, 0.5, 64.0, 0.5, 0.0, 45.0, sneaking = true)
        assertLanding(WATER, 0.340, 7.756, sneaking, "sneaking")
        assertEquals(2, sneaking.contactTick)
    }

    @Test
    fun `from a higher bank the cast reaches farther`() {
        val bank = Blocks { x, y, z -> if (x == 0 && z == 0 && y <= 64) SOLID else if (y <= 62) WATER else AIR }
        listOf(0.0 to 16.820, 15.0 to 13.639, 30.0 to 10.516, 45.0 to 8.236, 60.0 to 5.631).forEach { (pitch, z) ->
            assertLanding(WATER, 0.340, z, fly(bank, 0.5, 65.0, 0.5, 0.0, pitch), "pitch $pitch")
        }
    }

    @Test
    fun `standing in a pool the bobber comes up to its surface`() {
        val pool = Blocks { _, y, _ -> if (y <= 64) SOLID else if (y <= 67) WATER else AIR }
        val expected = listOf(Triple(90.0, 10.361, 10.420), Triple(80.0, 10.225, 10.656), Triple(60.0, 9.806, 11.382), Triple(45.0, 9.576, 11.781), Triple(0.0, 6.001, 17.973))
        for ((pitch, x, z) in expected) {
            val l = fly(pool, 10.5, 65.0, 10.5, 30.0, pitch)
            assertLanding(WATER, x, z, l, "pitch $pitch")
            assertEquals(68.0, l.surface, 1e-9)
            assertEquals(0, l.contactTick, "the eye is under the surface")
        }
        assertEquals(67.893, fly(pool, 10.5, 65.0, 10.5, 30.0, 90.0).y, 0.01)
    }

    @Test
    fun `a narrow channel is reached only by a flat cast`() {
        val channel = Blocks { _, y, z -> if (y > 62) AIR else if (z in 4 .. 6) WATER else SOLID }
        assertLanding(WATER, 0.340, 6.875, fly(channel, 0.5, 63.0, 0.5, 0.0, 20.0), "pitch 20")
        listOf(30.0 to 3.006, 40.0 to 2.025, 50.0 to 1.650, 60.0 to 1.332, 70.0 to 1.023).forEach { (pitch, z) ->
            assertLanding(SOLID, 0.340, z, fly(channel, 0.5, 63.0, 0.5, 0.0, pitch), "pitch $pitch", y = 63.0)
        }
    }

    @Test
    fun `a wall stops the hook its box's half width in front of it`() {
        val wall = Blocks { x, y, z -> if (x == 0 && z == 0 && y == 63 || z >= 6 && y in 60 .. 70) SOLID else if (y <= 62) WATER else AIR }
        for (pitch in listOf(0.0, 10.0, 20.0, 45.0)) assertLanding(WATER, 0.340, 5.875, fly(wall, 0.5, 64.0, 0.5, 0.0, pitch), "pitch $pitch")
    }

    @Test
    fun `the recorded lava spot ends against its wall where your reels did`() {
        // reels there: z -520.10 to -520.12 for every pitch from 11 to 25
        val lava = Blocks { _, y, z -> if (z >= - 520 && y >= 130) SOLID else if (y <= 142) LAVA else AIR }
        val expected = listOf(11.6 to - 520.125, 15.8 to - 520.125, 19.4 to - 520.255, 24.8 to - 520.125)
        for ((pitch, z) in expected) {
            val l = fly(lava, - 479.83, 143.0, - 527.30, 0.6, pitch)
            assertLanding(LAVA, - 480.066, z, l, "pitch $pitch")
            assertEquals(143.0, l.surface, 1e-9)
        }
    }

    @Test
    fun `a solid on the water stops the hook in front of it, or the hook passes under it`() {
        fun lily(px: Int, pz: Int) = Blocks { x, y, z -> if (x == 0 && z == 0 && y == 63 || x == px && z == pz && y == 63) SOLID else if (y <= 62) WATER else AIR }
        assertLanding(WATER, 0.340, 1.875, fly(lily(0, 2), 0.5, 64.0, 0.5, 0.0, 45.0), "where it comes down")
        assertLanding(WATER, 0.340, 7.756, fly(lily(0, 3), 0.5, 64.0, 0.5, 0.0, 45.0), "dived under it")
        assertLanding(WATER, 0.340, 7.756, fly(lily(0, 7), 0.5, 64.0, 0.5, 0.0, 45.0), "under it in the slide", y = 62.750)
    }

    @Test
    fun `the server drops a hook beyond 32 blocks`() {
        val deep = Blocks { _, y, _ -> if (y <= 0) WATER else AIR }
        assertLanding(AIR, 0.340, 16.733, fly(deep, 0.5, 64.0, 0.5, 0.0, - 30.0), "pitch -30", y = 36.020)
        assertLanding(AIR, 0.340, 0.532, fly(deep, 0.5, 64.0, 0.5, 0.0, 89.9), "pitch 89.9", y = 31.641)
    }

    @Test
    fun `a view casts like home only when its flight, its fan and the surface around its landing hold home's water`() {
        val world = Blocks(::pond)
        fun judge(yaw: Double, pitch: Double, homeYaw: Double = 0.0, homePitch: Double = 45.0, blocks: Blocks = world) =
            BobberFlight.judge(0.5, 63.0, 0.5, false, homeYaw, homePitch, yaw, pitch, blocks)
        assertLanding(WATER, 0.340, 7.234, fly(world, 0.5, 63.0, 0.5, 0.0, 45.0), "home")
        val same = listOf(12.0 to 45.0, - 30.0 to 50.0, 40.0 to 45.0, 0.0 to 20.0, 0.0 to 10.0, 60.0 to 30.0, 55.0 to 30.0, 50.0 to 30.0, 0.0 to 65.0)
        for ((yaw, pitch) in same) assertEquals(Verdict.SAME, judge(yaw, pitch), "view $yaw/$pitch")
        // lands 0.4 m before the far bank: a cast a little long would not
        assertEquals(Verdict.LONG, judge(0.0, 5.0))
        assertLanding(WATER, 0.340, 13.605, fly(world, 0.5, 63.0, 0.5, 0.0, 5.0), "pitch 5")
        // lands 1.9 m past the near bank: a creature in the line would stop it on the bank
        assertEquals(Verdict.SHORT, judge(0.0, 70.0))
        assertLanding(WATER, 0.340, 2.857, fly(world, 0.5, 63.0, 0.5, 0.0, 70.0), "pitch 70")
        assertEquals(Verdict.LONG, judge(64.0, 30.0))
        assertEquals(Verdict.LONG, judge(- 66.0, 30.0))
        for ((yaw, pitch) in listOf(0.0 to - 10.0, 180.0 to 45.0, 0.0 to 80.0, 0.0 to 89.0, - 90.0 to 45.0, 90.0 to 10.0)) {
            assertEquals(Verdict.MEAN, judge(yaw, pitch), "view $yaw/$pitch lands on the ground")
        }
        assertEquals(Verdict.UNDECIDED, judge(0.0, 45.0, homeYaw = 180.0), "home's cast lands on the ground")
        assertNull(BobberFlight.sameFluid(0.5, 63.0, 0.5, false, 180.0, 45.0, 0.0, 45.0, world))
        assertEquals(true, BobberFlight.sameFluid(0.5, 63.0, 0.5, false, 0.0, 45.0, 12.0, 45.0, world))
        assertEquals(false, BobberFlight.sameFluid(0.5, 63.0, 0.5, false, 0.0, 45.0, 0.0, 70.0, world))

        // half the pond lava: home's water is not the lava next to it
        val mixed = Blocks { x, y, z -> pond(x, y, z).let { if (it == WATER && x < 0) LAVA else it } }
        assertEquals(Verdict.MEAN, judge(40.0, 45.0, blocks = mixed))
        assertLanding(LAVA, - 3.951, 5.556, fly(mixed, 0.5, 63.0, 0.5, 40.0, 45.0), "into the lava half")

        // a second pool two blocks higher is other water
        val raised = Blocks { x, y, z -> if (x in - 20 .. - 12 && z in 1 .. 13) (if (y > 64) AIR else if (y >= 62) WATER else SOLID) else pond(x, y, z) }
        assertEquals(Verdict.MEAN, judge(75.0, - 20.0, blocks = raised))
        val high = fly(raised, 0.5, 63.0, 0.5, 75.0, - 20.0)
        assertLanding(WATER, - 14.843, 4.445, high, "into the raised pool")
        assertEquals(65.0, high.surface, 1e-9)
    }

    @Test
    fun `the yaw fan keeps a view whose neighbours land on a tongue of land`() {
        val tongue = Blocks { x, y, z -> if (x == 0 && z == 0 && y == 63) SOLID else if (y > 62) AIR else if (x >= 1 && z in 2 .. 5) SOLID else WATER }
        fun judge(yaw: Double) = BobberFlight.judge(0.5, 64.0, 0.5, false, 0.0, 45.0, yaw, 45.0, tongue)
        assertEquals(Verdict.SAME, judge(- 8.0))
        assertEquals(Verdict.FAN, judge(- 10.0))
        assertLanding(WATER, 0.875, 6.065, fly(tongue, 0.5, 64.0, 0.5, - 10.0, 45.0), "yaw -10")
        assertEquals(Verdict.MEAN, judge(- 15.0))
        assertLanding(SOLID, 0.973, 2.883, fly(tongue, 0.5, 64.0, 0.5, - 15.0, 45.0), "yaw -15")
    }

    @Test
    fun `the launch noise the mean flight leaves out spreads landings by less than a third of a block`() {
        val rng = java.util.Random(7)
        val mean = fly(openWater, 0.5, 64.0, 0.5, 0.0, 45.0)
        val d = List(2000) {
            val noise = DoubleArray(3) { rng.nextGaussian() * BobberFlight.LAUNCH_NOISE }
            val l = BobberFlight.fly(0.5, 64.0, 0.5, 0.0, 45.0, false, openWater, noise)
            assertEquals(WATER, l.medium)
            hypot(l.x - mean.x, l.z - mean.z)
        }.sorted()
        assertTrue(d[1900] < 0.30, "p95 ${d[1900]}")
        assertTrue(d.last() < 0.45, "max ${d.last()}")
    }

    @Test
    fun `an undershoot is kept while it lands like home, else drawn under the largest that does, never home itself`() {
        // the bound the draw gets, handed back as the draw
        val bound = { b: Double -> b }
        assertEquals(4.0, BobberFlight.undershoot(4.0, { true }, bound))
        assertNull(BobberFlight.undershoot(4.0, { null }, bound))
        // lands up to 3°: the bisection finds 3° from below to a 64th of the way
        val three = BobberFlight.undershoot(4.0, { it < 3.0 }, bound)!!
        assertTrue(three in 3.0 - 4.0 / 64 .. 3.0, "bound $three")
        // a narrow pool where only 0.8° short still lands
        val narrow = BobberFlight.undershoot(20.0, { it <= 0.8 }, bound)!!
        assertTrue(narrow in 0.8 - 20.0 / 64 .. 0.8, "bound $narrow")
        // nothing lands: drawn within the launch noise of home, or under the undershoot itself when that is closer
        assertEquals(BobberFlight.NEAR_HOME, BobberFlight.undershoot(4.0, { false }, bound))
        assertEquals(0.3, BobberFlight.undershoot(0.3, { false }, bound))
        // the caller's draw under the bound: never past it, never 0, never piled at one value
        val rng = Random(3)
        val shorts = List(20_000) { BobberFlight.undershoot(12.0, { it <= 2.0 }) { b -> TimingModel.lognormal(5.0, 1.0, rng, b) }!! }
        assertTrue(shorts.all { it > 0.0 && it <= 2.0 }, "${shorts.min()}..${shorts.max()}")
        assertTrue(shorts.groupingBy { it }.eachCount().values.max() == 1, "a value repeated")
        assertTrue(shorts.count { it >= 0.99 * shorts.max() } < shorts.size * 0.03, "piled at the bound")
        val onHome = List(20_000) { BobberFlight.undershoot(12.0, { false }) { b -> TimingModel.lognormal(5.0, 1.0, rng, b) }!! }
        assertTrue(onHome.all { it > 0.0 && it <= BobberFlight.NEAR_HOME }, "${onHome.min()}..${onHome.max()}")
        // a bisection over the same cached world: a handful of flights, not one per hundredth of a degree
        var asked = 0
        BobberFlight.undershoot(12.0, { asked++; it <= 5.0 }, bound)
        assertEquals(1 + BobberFlight.BISECT_STEPS, asked)
    }

    /**
     * The Backwater Bayou session (`LBQ_RECORDING_BAYOU`, skipped without it): every cast from above the water that got a
     * bite with no creature hooked, flown in flat water at the height its reel shows, lands where the reel was (SPEC §8.2;
     * measured: error p50 0.18, p75 0.46 m; reach per 15° of pitch within 1.6 %). The 26.2 client's constants reach only
     * 0.46-0.78 of it.
     */
    @Test
    fun `the Bayou casts land where your reels were`() {
        val path = System.getenv("LBQ_RECORDING_BAYOU")
        assumeTrue("LBQ_RECORDING_BAYOU is not set", ! path.isNullOrBlank())
        val file = File(path)
        assumeTrue("no recording at $path", file.isFile)
        val events = file.readLines().filter { it.isNotBlank() && "\"e\":\"rot\"" !in it }
            .mapNotNull { runCatching { JsonParser.parseString(it).asJsonObject }.getOrNull() }
            .sortedBy { it.num("t") ?: 0.0 }

        class Row(val pitch: Double, val medium: Medium, val err: Double, val across: Double, val reach: Double, val sim: Double)
        val rows = ArrayList<Row>()
        var item: String? = null
        var cast: JsonObject? = null
        var bite = false
        var creature = false
        var castItem: String? = null
        for (e in events) {
            when (e.str("e")) {
                "slot" -> item = e.str("item")
                "click" -> e.str("item")?.takeIf { it.isNotEmpty() }?.let { item = it }
                "cast" -> {
                    cast = e.takeIf { it.num("px") != null }
                    bite = false
                    creature = false
                    castItem = item
                }
                "bite" -> bite = true
                "hooked" -> if (e.has("id") && ! e.get("id").isJsonNull) creature = true
                "reel" -> {
                    val c = cast ?: continue
                    cast = null
                    val rx = e.num("x") ?: continue
                    if (! bite || creature || castItem == "HELLFIRE_ROD") continue
                    val ry = e.num("y")!!
                    val rz = e.num("z")!!
                    val surface = floor(ry) + 1
                    val px = c.num("px")!!
                    val py = c.num("py")!!
                    val pz = c.num("pz")!!
                    if (py + BobberFlight.EYE - surface <= 0.3) continue
                    val reach = hypot(rx - px, rz - pz)
                    if (reach > 40) continue
                    val yaw = c.num("yaw")!!
                    val pitch = c.num("pitch")!!
                    val flat = Blocks { _, y, _ -> if (y + 1 <= surface) WATER else AIR }
                    val l = BobberFlight.fly(px, py, pz, yaw, pitch, false, flat)
                    val fx = - sin(Math.toRadians(yaw))
                    val fz = cos(Math.toRadians(yaw))
                    rows += Row(pitch, l.medium, hypot(l.x - rx, l.z - rz), (rx - l.x) * fz - (rz - l.z) * fx, reach, hypot(l.x - px, l.z - pz))
                }
            }
        }
        val err = rows.map { it.err }
        println("bayou casts ${rows.size}: error p50 %.3f p75 %.3f p90 %.3f, across p50 %.3f".format(q(err, 0.5), q(err, 0.75), q(err, 0.9), q(rows.map { it.across }, 0.5)))
        assertTrue(rows.size >= 200, "casts from above the water ${rows.size}")
        assertTrue(rows.all { it.medium == WATER }, "every cast lands in the water")
        assertTrue(q(err, 0.5) <= 0.30, "error p50 ${q(err, 0.5)}")
        assertTrue(q(err, 0.75) <= 0.70, "error p75 ${q(err, 0.75)}")
        assertTrue(abs(q(rows.map { it.across }, 0.5)) <= 0.10, "sideways p50 ${q(rows.map { it.across }, 0.5)}")
        var bins = 0
        for (lo in - 90 until 90 step 15) {
            val bin = rows.filter { it.pitch >= lo && it.pitch < lo + 15 }
            if (bin.size < 20) continue
            bins++
            val ratio = q(bin.map { it.sim }, 0.5) / q(bin.map { it.reach }, 0.5)
            println("  pitch $lo..${lo + 15}: n ${bin.size}, simulated / recorded reach %.3f".format(ratio))
            assertTrue(abs(ratio - 1) <= 0.05, "pitch $lo..${lo + 15}: reach ratio $ratio")
        }
        assertTrue(bins >= 3, "pitch bins with 20 casts or more: $bins")
    }

    private fun JsonObject.num(key: String) = get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble

    private fun JsonObject.str(key: String) = get(key)?.takeIf { it.isJsonPrimitive }?.asString

    /** The [p] quantile of [v], interpolated between neighbours. */
    private fun q(v: List<Double>, p: Double): Double {
        val s = v.sorted()
        val i = (s.size - 1) * p
        val lo = floor(i).toInt()
        val hi = minOf(lo + 1, s.size - 1)
        return s[lo] + (s[hi] - s[lo]) * (i - lo)
    }
}
