package config

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.laybalt.config.OneShotDefaults
import kotlin.test.*

/** A changed default reaches configs saved with the old value exactly once (the Water Board recorder, on by default). */
class OneShotDefaultsTest {
    private fun config(vararg applied: String) = JsonObject().apply {
        addProperty("version", 2)
        if (applied.isNotEmpty()) add(OneShotDefaults.KEY, JsonArray().apply { applied.forEach { add(it) } })
    }

    @Test
    fun `a config saved before the change gets it once, then keeps whatever the player chose`() {
        var recorder = false
        val defaults = OneShotDefaults().apply { register("water-board-recorder-on") { recorder = true } }
        assertEquals(listOf("water-board-recorder-on"), defaults.apply(config()))
        assertTrue(recorder)

        // Saved with the id; the player turns it off again: loading that config does not switch it back on.
        val saved = config(*defaults.applied.toTypedArray())
        assertEquals(listOf("water-board-recorder-on"), defaults.toJson().map { it.asString })
        recorder = false
        assertEquals(emptyList(), defaults.apply(saved))
        assertFalse(recorder)
    }

    @Test
    fun `a new install already has the new defaults and only records them`() {
        var calls = 0
        val defaults = OneShotDefaults().apply { register("a") { calls ++ } }
        assertEquals(emptyList(), defaults.apply(null))
        assertEquals(0, calls)
        assertEquals(setOf("a"), defaults.applied)
    }

    @Test
    fun `a change that throws is still recorded, the others are applied`() {
        var b = false
        val defaults = OneShotDefaults().apply {
            register("a") { error("boom") }
            register("b") { b = true }
        }
        val errors = ArrayList<String>()
        assertEquals(listOf("a", "b"), defaults.apply(config()) { id, _ -> errors += id })
        assertEquals(listOf("a"), errors)
        assertTrue(b)
        assertEquals(setOf("a", "b"), defaults.applied)
    }

    @Test
    fun `ids of changes this version does not know are kept`() {
        val defaults = OneShotDefaults().apply { register("a") {} }
        defaults.apply(config("future-change"))
        assertEquals(setOf("future-change", "a"), defaults.applied)
    }
}
