package network

import com.laybalt.utils.network.data.HypixelProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** A made-up answer shaped like Odin's stats server's `/get/<uuid>` (the Hypixel API v2 profiles): extra "best" and "total" keys included. */
class HypixelProfileTest {
    private val uuid = "0123456789abcdef0123456789abcdef"

    private fun answer(selectedSecrets: Int = 60000) = """
        {"success": true, "profiles": [
          {"profile_id": "a", "cute_name": "Apple", "selected": false, "members": {"$uuid": {"dungeons": {"secrets": 5}}}},
          {"profile_id": "b", "cute_name": "Banana", "selected": true, "members": {"$uuid": {
            "dungeons": {
              "secrets": $selectedSecrets,
              "dungeon_types": {
                "catacombs": {"experience": 2900000000.5, "tier_completions": {"0": 3, "7": 100, "total": 103},
                  "fastest_time_s_plus": {"5": 150000, "7": 260000, "best": 150000}},
                "master_catacombs": {"tier_completions": {"6": 20, "7": 400, "total": 420},
                  "fastest_time_s_plus": {"6": 180000, "7": 279000, "best": 180000}}
              },
              "player_classes": {"healer": {"experience": 1000.0}, "mage": {"experience": 250000000.0}}
            },
            "inventory": {"inv_armor": {"type": 0, "data": "ARMOR"}, "bag_contents": {"talisman_bag": {"type": 0, "data": "TALISMANS"}}},
            "nether_island_player_data": {"abiphone": {"active_contacts": ["dean", "elle"]}},
            "rift": {"access": {"consumed_prism": true}},
            "accessory_bag_storage": {"selected_power": "silky"},
            "item_data": {"favorite_arrow": "ARMORSHRED_ARROW"},
            "pets_data": {"pets": [{"type": "ENDER_DRAGON", "tier": "LEGENDARY", "active": true}, {"type": "BEE", "tier": "COMMON", "active": false}]},
            "player_stats": {"kills": {"watcher_summon_undead": 1000, "master_watcher_summon_undead": 234, "watcher_summon_skeleton": 7, "zombie": 5}}
          }}}
        ]}
    """.trimIndent()

    @Test
    fun `the selected profile's dungeon stats as Auto Kick and the join stats read them`() {
        val stats = HypixelProfile.dungeonStats(answer(), uuid)
        val dungeons = stats.dungeons
        assertEquals(60000, dungeons.secrets)
        assertEquals(mapOf("6" to 180000L, "7" to 279000L), dungeons.masterCatacombs.fastestTimeSPlus, "milliseconds per floor, no 'best'")
        assertEquals(mapOf("5" to 150000L, "7" to 260000L), dungeons.catacombs.fastestTimeSPlus)
        assertEquals(mapOf("0" to 3, "7" to 100), dungeons.catacombs.tierCompletions, "no 'total'")
        assertEquals(520, dungeons.totalRuns, "floors 1-7 of both modes, no Entrance")
        assertEquals(2900000000.5, dungeons.catacombsExperience)
        assertEquals(mapOf("healer" to 1000.0, "mage" to 250000000.0), dungeons.playerClasses)
        assertEquals("TALISMANS", stats.talismanBagData)
        assertEquals("ARMOR", stats.armorData)
        assertEquals(listOf("dean", "elle"), stats.abiphoneContacts)
        assertTrue(stats.consumedRiftPrism)
        assertEquals("silky", stats.selectedPower)
        assertEquals("ARMORSHRED_ARROW", stats.favoriteArrow)
        assertEquals("ENDER_DRAGON", stats.activePet?.type)
        assertEquals(listOf("ENDER_DRAGON"), stats.pets.map { it.type }, "legendary or mythic, one per type")
        assertEquals(1234, stats.bloodMobsKilled)
    }

    @Test
    fun `a member without dungeons or with the Inventory API off reads as empty, not as an error`() {
        val stats = HypixelProfile.dungeonStats("""{"success": true, "profiles": [{"selected": true, "members": {"$uuid": {}}}]}""", uuid)
        assertEquals(0, stats.dungeons.secrets)
        assertTrue(stats.dungeons.masterCatacombs.fastestTimeSPlus.isEmpty(), "Auto Kick then reads 'No S+'")
        assertEquals("", stats.talismanBagData, "Auto Kick skips the MP check")
    }

    @Test
    fun `a server error or no profile fails the lookup`() {
        assertFailsWith<IllegalStateException> { HypixelProfile.dungeonStats("""{"success": false, "cause": "Invalid API key"}""", uuid) }
        assertFailsWith<IllegalStateException> { HypixelProfile.dungeonStats("""{"success": true, "profiles": []}""", uuid) }
        assertFailsWith<IllegalStateException> { HypixelProfile.dungeonStats("""{"success": true, "profiles": [{"selected": true, "members": {}}]}""", uuid) }
    }
}
