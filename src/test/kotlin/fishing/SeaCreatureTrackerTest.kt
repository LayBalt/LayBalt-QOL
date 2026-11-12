package fishing

import com.laybalt.features.impl.fishing.SeaCreatureTracker
import com.laybalt.features.impl.fishing.SeaCreatureTracker.ClaimRules
import com.laybalt.features.impl.fishing.SeaCreatureTracker.HookState
import com.laybalt.features.impl.fishing.SeaCreatures
import net.minecraft.network.chat.Component
import net.minecraft.world.phys.Vec3
import kotlin.math.hypot
import kotlin.test.*

/** The tracker's pure parts, with numbers from a 33-minute hotspot recording. */
class SeaCreatureTrackerTest {
    private fun obfuscated(text: String) = Component.literal(text).withStyle { it.withObfuscated(true) }

    @Test
    fun `obfuscated parts are not part of the visible name`() {
        val name = Component.empty()
            .append(obfuscated("a"))
            .append(Component.literal("Corrupted Frozen Steve"))
            .append(obfuscated("a"))
            .append(Component.literal(" 124/150❤"))
        val text = SeaCreatureTracker.visibleText(name)
        assertEquals("Corrupted Frozen Steve 124/150❤", text)
        assertEquals("Corrupted Frozen Steve", SeaCreatureTracker.cleanName(text))
    }

    @Test
    fun `legacy obfuscated runs end at a colour or reset`() {
        val text = SeaCreatureTracker.visibleText(Component.literal("§5§ka§r§5Corrupted Frozen Steve§5§ka§r §a124§f/§a150§c❤"))
        assertEquals("Corrupted Frozen Steve 124/150❤", text)
        assertEquals("Corrupted Frozen Steve", SeaCreatureTracker.cleanName(text))
        assertEquals("Sea Walker", SeaCreatureTracker.visibleText(Component.literal("§ka§lb§aSea Walker§k")))
        // §r goes back to the part's own style: an obfuscated part stays hidden
        assertEquals("", SeaCreatureTracker.visibleText(obfuscated("ab§rcd")))
        assertEquals("cd", SeaCreatureTracker.visibleText(obfuscated("ab§ecd")))
    }

    @Test
    fun `corrupted variants name their base creature`() {
        val read = SeaCreatureTracker.readName("Corrupted Frozen Steve 124/150❤")
        assertEquals("Frozen Steve", read.creature?.name)
        assertTrue(read.corrupted)
        assertEquals("Corrupted Frozen Steve", read.name)

        // plain text keeps the glyphs glued to the name, as the recording logged it
        for ((raw, base) in listOf("aCorrupted Frog Mana" to "Frog Man", "aCorrupted Frozen Stevea 124/150❤" to "Frozen Steve", "aCorrupted Grincha" to "Grinch")) {
            val glued = SeaCreatureTracker.readName(raw)
            assertEquals(base, glued.creature?.name, raw)
            assertTrue(glued.corrupted, raw)
            assertEquals("Corrupted $base", glued.name)
        }

        val plain = SeaCreatureTracker.readName("[Lv1] Squid 120❤")
        assertEquals("Squid", plain.creature?.name)
        assertFalse(plain.corrupted)
        assertEquals("Squid", plain.name)
        // only a Corrupted name may lose a glued letter
        assertNull(SeaCreatureTracker.readName("Sea Walkers").creature)
        assertEquals("Sea Walkers", SeaCreatureTracker.readName("Sea Walkers").name)
    }

    @Test
    fun `own nametags come up in a column above the bobber`() {
        // own: 0.04 m sideways (p95), at most 0.45 m, 0.81-5.34 m above
        assertTrue(ClaimRules.aboveBobber(0.04, 0.81, 0.0))
        assertTrue(ClaimRules.aboveBobber(0.3, 5.34, 0.3))
        assertTrue(ClaimRules.aboveBobber(0.0, - 0.9, 0.0))
        // the Manta Ray 4.52 m away in 3D, straight above
        assertTrue(ClaimRules.aboveBobber(0.1, 4.52, 0.0))
        // neighbours' creatures 1.13-2.39 m sideways
        assertFalse(ClaimRules.aboveBobber(1.13, 2.0, 0.0))
        assertFalse(ClaimRules.aboveBobber(1.69, 0.5, 0.0))
        assertFalse(ClaimRules.aboveBobber(0.0, 6.6, 0.0))
        assertFalse(ClaimRules.aboveBobber(0.0, - 1.1, 0.0))
        // claims by place alone are narrower
        assertTrue(ClaimRules.aboveBobber(0.9, 1.0, 0.0))
        assertFalse(ClaimRules.aboveBobber(0.9, 1.0, 0.0, ClaimRules.EARLY_RADIUS))
    }

    @Test
    fun `a catch comes up at the bobber still out or at a recent reel`() {
        val bobber = Vec3(0.0, 60.0, 0.0)
        val reel = Vec3(3.0, 60.0, 0.0)
        // the message comes before the client sees its reel: the bobber still out, plus a reel just before
        assertEquals(listOf(bobber, reel), ClaimRules.catchSpots(true, bobber, reel, 900, 1500))
        assertEquals(listOf(bobber), ClaimRules.catchSpots(true, bobber, reel, 1600, 1500))
        assertEquals(listOf(reel), ClaimRules.catchSpots(false, reel, reel, 100, 1500))
        assertEquals(emptyList<Vec3>(), ClaimRules.catchSpots(false, reel, reel, 2000, 1500))
        assertEquals(emptyList<Vec3>(), ClaimRules.catchSpots(true, null, null, 0, 1500))

        val spots = listOf(bobber, reel)
        val own = Vec3(0.05, 62.0, 0.0)
        val atReel = Vec3(3.2, 61.0, 0.1)
        assertTrue(ClaimRules.inColumn(own, spots))
        assertTrue(ClaimRules.inColumn(atReel, spots))
        assertFalse(ClaimRules.inColumn(Vec3(1.5, 61.0, 0.0), spots))
        assertFalse(ClaimRules.inColumn(own, emptyList()))
        assertEquals(0.05, ClaimRules.offset(own, spots), 1e-9)
        assertEquals(hypot(0.2, 0.1), ClaimRules.offset(atReel, spots), 1e-9)
    }

    @Test
    fun `claims before the message are limited`() {
        // own nametags came 6-117 ms after their message; a neighbour's came 448 ms before one
        assertTrue(ClaimRules.lateClaim(120, dead = false, inColumn = true))
        assertTrue(ClaimRules.lateClaim(300, dead = false, inColumn = true))
        assertFalse(ClaimRules.lateClaim(448, dead = false, inColumn = true))
        assertFalse(ClaimRules.lateClaim(50, dead = false, inColumn = false))
        // a dead neighbour creature must not take a double hook slot
        assertFalse(ClaimRules.lateClaim(50, dead = true, inColumn = true))

        val steve = assertNotNull(SeaCreatures.byName("Frozen Steve"))
        val slug = assertNotNull(SeaCreatures.byName("Baby Magma Slug"))
        // a creature with a catch message is only ever claimed by it
        assertFalse(ClaimRules.earlyClaim(steve, waiting = false, atSpot = true))
        assertTrue(ClaimRules.earlyClaim(slug, waiting = false, atSpot = true))
        assertFalse(ClaimRules.earlyClaim(slug, waiting = true, atSpot = true))
        assertFalse(ClaimRules.earlyClaim(slug, waiting = false, atSpot = false))
        assertFalse(ClaimRules.earlyClaim(null, waiting = false, atSpot = true))
    }

    @Test
    fun `a nametag that comes up where your creature just died split off it`() {
        // Bayou Sludglings: up to 800 ms after their Sludge died and 7 blocks from it, or a little before its death was seen
        assertTrue(ClaimRules.isChild(652, 6.3))
        assertTrue(ClaimRules.isChild(0, 7.0))
        assertTrue(ClaimRules.isChild(- 200, 3.0))
        assertTrue(ClaimRules.isChild(- 300, 0.5))
        assertTrue(ClaimRules.isChild(800, 0.5))
        assertFalse(ClaimRules.isChild(900, 2.0))
        assertFalse(ClaimRules.isChild(- 400, 1.0))
        assertFalse(ClaimRules.isChild(100, 7.5))
    }

    @Test
    fun `only a creature that cannot be caught can be a child`() {
        val walker = assertNotNull(SeaCreatures.byName("Sea Walker"))
        val slug = assertNotNull(SeaCreatures.byName("Baby Magma Slug"))
        assertFalse(ClaimRules.childName("Sea Walker", walker, 1500.0), "it has a catch message")
        assertFalse(ClaimRules.childName("Bayou Sludgling", null, 0.0), "no health read")
        assertFalse(ClaimRules.childName("36,424", null, 36424.0), "a number, not a name")
        assertFalse(ClaimRules.childName("1.5", null, 10.0))
        assertTrue(ClaimRules.childName("Bayou Sludgling", null, 5000.0))
        assertTrue(ClaimRules.childName("Baby Magma Slug", slug, 3000.0))
    }

    @Test
    fun `a child's parent is the nearest death in time, then in place`() {
        // (ms since that death, distance to where it died)
        val deaths = listOf(500L to 1.0, - 100L to 6.0, 150L to 2.0, - 100L to 3.0, 50L to 8.0, 1000L to 0.1)
        val parent = ClaimRules.parentOf(deaths, { it.first }, { it.second })
        assertEquals(- 100L to 3.0, parent)
        // before or after the death is the same: 100 ms either way, the nearer one wins
        assertEquals(100L to 2.0, ClaimRules.parentOf(listOf(- 100L to 3.0, 100L to 2.0), { it.first }, { it.second }))
        assertEquals(500L to 1.0, ClaimRules.parentOf(listOf(500L to 1.0, 50L to 8.0), { it.first }, { it.second }))
        assertNull(ClaimRules.parentOf(listOf(50L to 8.0, 1000L to 0.1), { it.first }, { it.second }))
        assertNull(ClaimRules.parentOf(emptyList<Pair<Long, Double>>(), { it.first }, { it.second }))
    }

    @Test
    fun `only your creature that died and is no child has children`() {
        assertTrue(ClaimRules.canParent(own = true, parent = "", deathCause = "mob"))
        assertTrue(ClaimRules.canParent(own = true, parent = "", deathCause = "stand"))
        assertTrue(ClaimRules.canParent(own = true, parent = "", deathCause = "hp0"))
        assertFalse(ClaimRules.canParent(own = true, parent = "", deathCause = "gone"), "out of view, not dead")
        assertFalse(ClaimRules.canParent(own = true, parent = "", deathCause = ""), "still alive")
        assertFalse(ClaimRules.canParent(own = false, parent = "", deathCause = "mob"), "someone else's")
        // Sludglings and Baby Magma Slugs never split
        assertFalse(ClaimRules.canParent(own = true, parent = "Bayou Sludge", deathCause = "mob"))
        assertFalse(ClaimRules.canParent(own = true, parent = "", deathCause = "mob", early = true), "a Baby Magma Slug claimed at the bobber")
    }

    @Test
    fun `a death claims a nametag that came up just before it, unless it is a catch or a child already`() {
        assertTrue(ClaimRules.childClaimable(own = false, early = false, parent = "", dead = false), "someone else's so far")
        // a Baby Magma Slug right above the bobber a tick before its Magma Slug's death was seen: a child, not a catch
        assertTrue(ClaimRules.childClaimable(own = true, early = true, parent = "", dead = false))
        assertFalse(ClaimRules.childClaimable(own = true, early = false, parent = "", dead = false), "your catch")
        assertFalse(ClaimRules.childClaimable(own = true, early = false, parent = "Magma Slug", dead = false), "it has its parent")
        assertFalse(ClaimRules.childClaimable(own = false, early = false, parent = "", dead = true))
        assertFalse(ClaimRules.childClaimable(own = true, early = true, parent = "", dead = true))
    }

    /** One of your creatures' deaths a new nametag is measured against, as the tracker keeps it. */
    private class Death(val name: String, val ms: Long, val distance: Double, val own: Boolean = true, val parent: String = "", val cause: String = "mob")

    /** The parent the tracker picks for a new nametag among [deaths] (its `deaths` filter, then [ClaimRules.parentOf]). */
    private fun parentOf(vararg deaths: Death) =
        ClaimRules.parentOf(deaths.filter { ClaimRules.canParent(it.own, it.parent, it.cause) }, { it.ms }, { it.distance })?.name

    @Test
    fun `a child gets the parent that dies in its tick and never a sibling`() {
        // Bayou recording, t=792523: Sludgling 248569 came up in the tick its Sludge died, 399 ms and 1.8 m after a Trash
        // Gobbler of yours died; once that tick's deaths are seen first, the Sludge (0 ms) wins
        assertEquals("Bayou Sludge", parentOf(Death("Trash Gobbler", 399, 1.82), Death("Bayou Sludge", 0, 0.27)))
        // t=771624: a Dumpster Diver 551 ms before
        assertEquals("Bayou Sludge", parentOf(Death("Dumpster Diver", 551, 2.23), Death("Bayou Sludge", 0, 0.41)))
        // without that death it would have been taken for the Gobbler's
        assertEquals("Trash Gobbler", parentOf(Death("Trash Gobbler", 399, 1.82)))
        // t=1262776: Baby Magma Slugs right above the bobber in the tick their Magma Slug died are its children (the
        // early claim is only for a nametag without a parent)
        assertEquals("Magma Slug", parentOf(Death("Magma Slug", 0, 1.74)))

        // t=793175: Sludgling 248567, 652 ms after the Sludge died and 451 ms after its sibling 248569 (a child of yours)
        assertEquals("Bayou Sludge", parentOf(Death("Bayou Sludge", 652, 1.17), Death("Bayou Sludgling", 451, 0.97, parent = "Bayou Sludge")))
        assertNull(parentOf(Death("Bayou Sludgling", 451, 0.97, parent = "Bayou Sludge")))
        // nor someone else's, nor one that went out of view
        assertNull(parentOf(Death("Bayou Sludge", 100, 1.0, own = false), Death("Bayou Sludge", 50, 1.0, cause = "gone")))
    }

    @Test
    fun `the hook state sees casts, reels and recasts between ticks`() {
        val hook = HookState()
        val a = Vec3(1.0, 60.0, 1.0)
        val b = Vec3(2.0, 60.0, 2.0)
        assertEquals(HookState.Change.NONE, hook.update(null, null, 0))
        assertEquals(HookState.Change.CAST, hook.update(10, a, 100))
        assertTrue(hook.out)
        assertEquals(100, hook.castAt)
        assertEquals(HookState.Change.NONE, hook.update(10, b, 150))
        assertEquals(b, hook.bobberPos)

        assertEquals(HookState.Change.REEL, hook.update(null, null, 200))
        assertFalse(hook.out)
        assertEquals(200, hook.reelAt)
        assertEquals(b, hook.reelPos)
        assertEquals(b, hook.bobberPos, "the last bobber spot is kept after the reel")
        assertEquals(HookState.Change.NONE, hook.update(null, null, 250))

        assertEquals(HookState.Change.CAST, hook.update(11, a, 400))
        // reeled and cast again between two ticks: a new hook id
        assertEquals(HookState.Change.RECAST, hook.update(12, b, 450))
        assertTrue(hook.out)
        assertEquals(450, hook.reelAt)
        assertEquals(450, hook.castAt)
        assertEquals(a, hook.reelPos)
        assertEquals(b, hook.bobberPos)
        assertEquals(12, hook.id)

        hook.reset()
        assertFalse(hook.out)
        assertNull(hook.reelPos)
        assertNull(hook.bobberPos)
        assertEquals(HookState.Change.CAST, hook.update(12, b, 500))
    }
}
