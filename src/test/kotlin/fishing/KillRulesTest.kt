package fishing

import com.google.gson.Gson
import com.laybalt.features.impl.fishing.KillRules.AttackMode
import com.laybalt.features.impl.fishing.KillRules.KillAction
import com.laybalt.features.impl.fishing.KillRules.RulesData
import kotlin.test.*

/** The rules file's defaults and its one-time migration to the Learned default (no file is read or written here). */
class KillRulesTest {
    private fun read(json: String) = Gson().fromJson(json, RulesData::class.java)

    @Test
    fun `a new rules file defaults to Learned`() {
        val d = RulesData()
        assertEquals(KillAction.LEARNED, d.defaultAction)
        assertEquals(KillAction.STOP, d.defaultRareAction)
        assertTrue(d.sanitize(), "a new file is saved with its version")
        assertEquals(KillAction.LEARNED, d.defaultAction)
        assertEquals(RulesData.VERSION, d.version)
        assertFalse(d.sanitize(), "migrated once")
    }

    @Test
    fun `an older file's Rod default becomes Learned once`() {
        // the installed file from before Learned existed
        val d = read("""{"rules":{"Squid":{"action":"ROD","loadout":0,"weaponId":"","weaponName":"","attack":"RIGHT"}},"defaultAction":"ROD","defaultRareAction":"STOP","fishingLoadout":0}""")
        assertEquals(0, d.version, "a missing version keeps the constructor default")
        assertTrue(d.sanitize())
        assertEquals(KillAction.LEARNED, d.defaultAction)
        assertEquals(KillAction.STOP, d.defaultRareAction)
        assertEquals(2, d.version)
        // a creature's own rule stays as it is
        assertEquals(KillAction.ROD, d.rules.getValue("Squid").action)
        assertEquals(AttackMode.RIGHT, d.rules.getValue("Squid").attack)

        // Rod picked again afterwards stays
        d.defaultAction = KillAction.ROD
        assertFalse(d.sanitize())
        assertEquals(KillAction.ROD, d.defaultAction)
        val again = read(Gson().toJson(d))
        assertFalse(again.sanitize())
        assertEquals(KillAction.ROD, again.defaultAction)
    }

    @Test
    fun `other defaults are kept by the migration`() {
        val d = read("""{"rules":{},"defaultAction":"IGNORE","defaultRareAction":"ROD","fishingLoadout":40}""")
        assertTrue(d.sanitize())
        assertEquals(KillAction.IGNORE, d.defaultAction)
        assertEquals(KillAction.ROD, d.defaultRareAction, "only the default for other creatures moves to Learned")
        assertEquals(36, d.fishingLoadout)
        assertEquals(2, d.version)
    }

    @Test
    fun `unknown or missing values get their defaults back`() {
        val d = read("""{"rules":{"Squid":{"action":"BOGUS","weaponId":null}},"defaultAction":"BOGUS","defaultRareAction":null,"version":2}""")
        assertFalse(d.sanitize())
        assertEquals(KillAction.LEARNED, d.defaultAction)
        assertEquals(KillAction.STOP, d.defaultRareAction)
        assertEquals(KillAction.ROD, d.rules.getValue("Squid").action)
        assertEquals("", d.rules.getValue("Squid").weaponId)
        assertEquals(AttackMode.AUTO, d.rules.getValue("Squid").attack)
    }
}
