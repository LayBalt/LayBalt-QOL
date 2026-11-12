package fishing

import com.laybalt.features.impl.fishing.SeaCreatureTracker
import com.laybalt.features.impl.fishing.SeaCreatures
import com.laybalt.utils.items.ItemRarity
import kotlin.test.*

class SeaCreaturesTest {
    @Test
    fun `bundled table loads every group`() {
        assertEquals(21, SeaCreatures.groups.size)
        assertEquals(90, SeaCreatures.all.size)
        val thunder = assertNotNull(SeaCreatures.byName("thunder"))
        assertTrue(thunder.rare)
        assertEquals(ItemRarity.MYTHIC, thunder.rarity)
        assertEquals("Crimson Isle", thunder.group.label)
    }

    @Test
    fun `catch messages and old names resolve`() {
        assertEquals("Sea Walker", SeaCreatures.byCatchMessage("You caught a Sea Walker.")?.name)
        assertEquals("Squid", SeaCreatures.byCatchMessage("  A Squid appeared. ")?.name)
        assertNull(SeaCreatures.byCatchMessage("You caught a Sea Walker"))
        assertEquals("The Loch Emperor", SeaCreatures.byName("The Sea Emperor")?.name)
        assertEquals("Plhlegblast", SeaCreatures.byName("Phlhlegblast")?.name)
    }

    @Test
    fun `nametag names match the longest whole creature name`() {
        assertEquals("Sea Walker", SeaCreatures.match("Corrupted Sea Walker")?.name)
        assertEquals("Baby Magma Slug", SeaCreatures.match("Baby Magma Slug")?.name)
        assertEquals("Magma Slug", SeaCreatures.match("Corrupted Magma Slug")?.name)
        assertEquals("Water Worm", SeaCreatures.match("Water Worm")?.name)
        assertEquals("Poisoned Water Worm", SeaCreatures.match("Poisoned Water Worm")?.name)
        assertNull(SeaCreatures.match("Sea Walkers"), "whole words only")
        assertNull(SeaCreatures.match("Zombie"))
    }

    @Test
    fun `nametags are cleaned of level, type icons, health and markers`() {
        assertEquals("Sea Walker", SeaCreatureTracker.cleanName("§8[§7Lv20§8] §c Sea Walker §a1,500§f/§a1,500§c❤"))
        assertEquals("Corrupted Sea Guardian", SeaCreatureTracker.cleanName("[Lv10]   Corrupted Sea Guardian ᛤ 4,250/5,000"))
        assertEquals("Lord Jawbus", SeaCreatureTracker.cleanName("☠ [Lv600] Lord Jawbus 75M/100M❤"))
        assertEquals("Squid", SeaCreatureTracker.cleanName("[Lv1] Squid 120❤"))
    }
}
