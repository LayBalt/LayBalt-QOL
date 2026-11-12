package config

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.laybalt.config.types.RangeSliderSetting
import com.laybalt.features.impl.floor7.terminals.AutoTerminal
import kotlin.test.*

/** NoammAddons 5dff2334: Click Delay / First Click Protection as range sliders, and the carry-over of the old values. */
class RangeSliderSettingTest {
    private fun delay() = RangeSliderSetting("Click Delay", 120 to 150, 50, 500, 5, "ms")

    @Test
    fun `saves and reads both ends`() {
        val setting = delay()
        setting.value = 100 to 200
        assertEquals(JsonParser.parseString("[100,200]"), setting.write())
        val restored = delay()
        restored.read(setting.write())
        assertEquals(100 to 200, restored.value)
    }

    @Test
    fun `reads ends in order, snapped and clamped`() {
        val setting = delay()
        setting.read(JsonParser.parseString("[203, 98]"))
        assertEquals(100 to 205, setting.value)
        setting.read(JsonParser.parseString("[10, 900]"))
        assertEquals(50 to 500, setting.value)
    }

    @Test
    fun `a single number from the old plain slider is a fixed range`() {
        val setting = RangeSliderSetting("First Click Protection", 350 to 350, 0, 800, 10, "ms")
        setting.read(JsonPrimitive(420))
        assertEquals(420 to 420, setting.value)
        assertEquals(420, setting.random())
    }

    @Test
    fun `random stays between the ends`() {
        val setting = delay()
        setting.value = 120 to 150
        repeat(5_000) { assertTrue(setting.random() in 120 .. 150) }
        setting.value = 150 to 150
        assertEquals(150, setting.random())
    }

    @Test
    fun `typed ranges`() {
        val setting = delay()
        assertEquals(120 to 150, setting.parse("120-150"))
        assertEquals(120 to 150, setting.parse(" 150 - 120 "))
        assertEquals(120 to 150, setting.parse("120 150"))
        assertEquals(120 to 150, setting.parse("120..150"))
        assertEquals(120 to 150, setting.parse("120,150"))
        assertEquals(200 to 200, setting.parse("200"))
        assertEquals(50 to 500, setting.parse("0-9000"))
        assertNull(setting.parse("abc"))
        assertNull(setting.parse("120-"))
        assertEquals("120 - 150ms", setting.display(120 to 150))
        assertEquals("150ms", setting.display(150 to 150))
    }

    private fun legacy(json: String) = JsonParser.parseString(json).asJsonObject.also(AutoTerminal.LegacyDelays::migrate)

    @Test
    fun `old random delay becomes the range`() {
        val settings = legacy("""{"randomDelay":true,"autoDelay":200,"minRandomDelay":100,"maxRandomDelay":180,"Click Order":2}""")
        assertEquals(JsonParser.parseString("[100,180]"), settings.get("Click Delay"))
        assertFalse(settings.has("randomDelay") || settings.has("autoDelay") || settings.has("minRandomDelay") || settings.has("maxRandomDelay"))
        assertEquals(2, settings.get("Click Order").asInt)
    }

    @Test
    fun `old fixed delay becomes a range with equal ends`() {
        assertEquals(JsonParser.parseString("[200,200]"), legacy("""{"randomDelay":false,"autoDelay":200,"minRandomDelay":100,"maxRandomDelay":180}""").get("Click Delay"))
    }

    @Test
    fun `missing old keys count as the old defaults, swapped min and max are ordered`() {
        assertEquals(JsonParser.parseString("[120,150]"), legacy("""{"autoDelay":300}""").get("Click Delay"))
        assertEquals(JsonParser.parseString("[150,150]"), legacy("""{"randomDelay":false}""").get("Click Delay"))
        assertEquals(JsonParser.parseString("[90,160]"), legacy("""{"minRandomDelay":160,"maxRandomDelay":90}""").get("Click Delay"))
    }

    @Test
    fun `a saved range wins and a config without old keys is left alone`() {
        assertEquals(JsonParser.parseString("[80,90]"), legacy("""{"Click Delay":[80,90],"randomDelay":true,"minRandomDelay":100}""").get("Click Delay"))
        val untouched = JsonObject().apply { addProperty("Click Order", 1) }
        AutoTerminal.LegacyDelays.migrate(untouched)
        assertEquals(JsonParser.parseString("""{"Click Order":1}"""), untouched)
    }

    @Test
    fun `the migrated range reads into the setting`() {
        val setting = delay()
        setting.read(legacy("""{"randomDelay":true,"minRandomDelay":120,"maxRandomDelay":150}""").get("Click Delay"))
        assertEquals(120 to 150, setting.value)
    }
}
