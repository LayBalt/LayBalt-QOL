package com.laybalt.config

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.laybalt.LayBaltQOL
import com.laybalt.features.Feature
import com.laybalt.features.FeatureManager
import com.laybalt.ui.notification.NotificationManager
import com.laybalt.utils.GsonUtils
import net.fabricmc.loader.api.FabricLoader
import java.io.File

/**
 * One-time import of the pre-merge LayBalt QOL config (`config/LBQConfig.json`, YACL, keys = field names of the old
 * `LBQConfigData`) and of the old Custom ESP list (`config/CustomESP.txt`) into the matching new settings.
 *
 * - Runs once after [ConfigManager.load] (called from `ClassGraphInitializer`).
 * - Targets are looked up by name at runtime (feature json name / name / class name and setting json name / name,
 *   compared case- and punctuation-insensitively), so this file has no compile-time dependency on any feature.
 * - Every old key is applied at most once: handled keys are stored in `config/<mod>/legacyConfigImport.json`.
 *   A key whose target feature/setting does not exist in this build stays pending and is retried on the next launch;
 *   once every key is handled (or the old file is absent/malformed) the marker is set to done and nothing runs again.
 * - Never throws: a missing or malformed file only logs a warning.
 * - Deliberately NOT imported: the click GUI accent colour and sorting, the old Auto Fishing on/off switch (Auto Fishing
 *   never auto-starts), WIP feature switches, the deleted auto clickers and the Auto Melody delay (its meaning changed).
 */
object LegacyConfigImport {
    private val configDir get() = FabricLoader.getInstance().configDir
    private val legacyConfigFile get() = configDir.resolve("LBQConfig.json").toFile()
    private val legacyEspFile get() = configDir.resolve("CustomESP.txt").toFile()
    private val markerFile get() = configDir.resolve(LayBaltQOL.CONFIG_DIR).resolve("legacyConfigImport.json").toFile()

    private const val ESP_LIST_KEY = "CustomESP.txt"

    private val FISHING = listOf("Auto Fishing")
    private val AUTO_PATH = listOf("Auto Path")
    private val MELODY = listOf("Auto Harp", "Auto Melody")
    private val EXPERIMENTS = listOf("Auto Experiments", "Auto Enchanting", "Auto Experiment")
    private val ESP = listOf("Custom ESP")
    private val VAMPIRE = listOf("Vampire Helper", "Vampire Slayer Helper")

    /** [settings] == null: the old key is the feature's on/off switch. */
    private class Mapping(val key: String, val features: List<String>, val settings: List<String>?)

    private val mappings = listOf(
        // Auto Fishing (fishingSwitch is intentionally skipped)
        Mapping("fishingReelDelayNumber", FISHING, listOf("Reel Delay")),
        Mapping("fishingCastDelayNumber", FISHING, listOf("Cast Delay")),
        Mapping("fishingStopMoveHead", FISHING, listOf("Lock Mouse")),
        Mapping("fishingShakeHead", FISHING, listOf("Shake Head")),

        // Auto Mob Killer: the old slot/delay settings are replaced by the Sea Creature Rules and recorded behaviour (3.4.0)

        // Auto Path (WIP; same route order as the old selector)
        Mapping("largeSelector", AUTO_PATH, listOf("Route")),

        // Auto Melody / Auto Harp
        Mapping("melodySwitch", MELODY, null),

        // Auto Enchanting / Auto Experiments
        Mapping("autoExperimentSwitch", EXPERIMENTS, null),
        Mapping("autoExperimentSlider", EXPERIMENTS, listOf("Click Delay")),
        Mapping("autoExperimentSerumSlider", EXPERIMENTS, listOf("Serums Consumed", "Serum Count", "Serums")),

        // Custom ESP
        Mapping("customESP", ESP, null),
        Mapping("customESPColor", ESP, listOf("ESP Color", "Color")),
        Mapping("customESPLineWidth", ESP, listOf("Line Width")),
        Mapping("customESPThroughWalls", ESP, listOf("Through Walls")),
        Mapping("customESPPlayers", ESP, listOf("Players")),
        Mapping("customESPMobs", ESP, listOf("Mobs")),
        Mapping("customESPItems", ESP, listOf("Items")),

        // Vampire Slayer Helper (WIP; settings only)
        Mapping("vampireSlayerSwordSlot", VAMPIRE, listOf("Sword Slot")),
        Mapping("vampireSlayerHealingMelonSlot", VAMPIRE, listOf("Healing Melon Slot", "Melon Slot")),
        Mapping("vampireSlayerHolyIceSlot", VAMPIRE, listOf("Holy Ice Slot", "Ice Slot")),
        Mapping("vampireSlayerAutoJump", VAMPIRE, listOf("Auto Jump")),
        Mapping("vampireSlayerAutoSneak", VAMPIRE, listOf("Auto Sneak")),
        Mapping("vampireSlayerAutoClickUp", VAMPIRE, listOf("Auto Click Up")),
        Mapping("vampireSlayerAutoClickDown", VAMPIRE, listOf("Auto Click Down")),
        Mapping("vampireSlayerAutoHeal", VAMPIRE, listOf("Auto Heal")),
        Mapping("vampireSlayerAutoTwinClaws", VAMPIRE, listOf("Auto Twinclaws", "Auto Twin Claws")),
    )

    private val allKeys = mappings.map { it.key } + ESP_LIST_KEY

    fun run() {
        try {
            runImport()
        }
        catch (e: Throwable) {
            LayBaltQOL.logger.warn("[LegacyConfigImport] import failed, skipping", e)
        }
    }

    private fun runImport() {
        val marker = readMarker()
        if (marker.done) return

        val handled = marker.handled
        val oldConfig = readLegacyConfig()
        val oldEspList = readLegacyEspList()

        if (oldConfig == null && oldEspList == null) {
            writeMarker(Marker(true, handled))
            return
        }

        var imported = 0

        if (oldConfig != null) for (mapping in mappings) {
            if (mapping.key in handled) continue
            val value = oldConfig.get(mapping.key)
            if (value == null || value.isJsonNull) {
                handled.add(mapping.key)
                continue
            }

            val feature = findFeature(mapping.features) ?: continue
            if (mapping.settings == null) {
                if (importSwitch(feature, value)) imported ++
                handled.add(mapping.key)
                continue
            }

            val setting = findSetting(feature, mapping.settings) ?: continue
            runCatching { (setting as Savable).read(value) }
                .onSuccess { imported ++ }
                .onFailure { LayBaltQOL.logger.warn("[LegacyConfigImport] could not import ${mapping.key} = $value: ${it.message}") }
            handled.add(mapping.key)
        }
        else mappings.forEach { handled.add(it.key) }

        if (ESP_LIST_KEY !in handled) {
            if (oldEspList.isNullOrEmpty()) handled.add(ESP_LIST_KEY)
            else findFeature(ESP)?.let { esp ->
                if (importEspList(esp, oldEspList)) imported ++
                handled.add(ESP_LIST_KEY)
            }
        }

        if (imported > 0) {
            ConfigManager.save()
            LayBaltQOL.logger.info("[LegacyConfigImport] imported $imported settings from the old LayBalt QOL config")
            NotificationManager.push(LayBaltQOL.MOD_NAME, "Imported $imported settings from the old LayBalt QOL config.", 6000L)
        }

        val pending = allKeys.filterNot(handled::contains)
        if (pending.isNotEmpty()) LayBaltQOL.logger.info("[LegacyConfigImport] no target yet for: ${pending.joinToString()}")
        writeMarker(Marker(pending.isEmpty(), handled))
    }

    /** Enables the feature when the old switch was on. WIP features are never enabled; "off" needs no change. */
    private fun importSwitch(feature: Feature, value: JsonElement): Boolean {
        val wanted = runCatching { value.asBoolean }.getOrNull() ?: return false
        if (! wanted || feature.wip || feature.enabled) return false
        feature.toggle()
        return feature.enabled
    }

    /**
     * The old ESP list holds entity type ids (`minecraft:zombie`). They are merged into the new list as `type:<id>` entries,
     * which Custom ESP always matches by entity type whatever its "Match By" mode is (that mode is left on its default).
     */
    private fun importEspList(feature: Feature, entries: List<String>): Boolean {
        val listSetting = findSetting(feature, listOf("Entries", "ESP List", "List")) ?: return false
        val merged = LinkedHashSet<String>()
        runCatching { (listSetting as Savable).write().asJsonArray.forEach { merged.add(it.asString) } }
        entries.forEach { merged.add(if (it.startsWith("type:")) it else "type:$it") }

        val ok = runCatching { (listSetting as Savable).read(JsonArray().apply { merged.forEach(::add) }) }
            .onFailure { LayBaltQOL.logger.warn("[LegacyConfigImport] could not import the Custom ESP list: ${it.message}") }
            .isSuccess
        return ok
    }

    private fun findFeature(names: List<String>): Feature? {
        val wanted = names.map(::normalize)
        return FeatureManager.features.firstOrNull { feature ->
            normalize(feature.jsonName) in wanted || normalize(feature.name) in wanted || normalize(feature::class.simpleName.orEmpty()) in wanted
        }
    }

    private fun findSetting(feature: Feature, names: List<String>): ConfigHolder<*>? {
        val wanted = names.map(::normalize)
        return feature.configSettings.firstOrNull { it is Savable && (normalize(it.jsonName) in wanted || normalize(it.name) in wanted) }
    }

    private fun normalize(s: String) = s.lowercase().filter(Char::isLetterOrDigit)

    private fun readLegacyConfig(): JsonObject? {
        val file = legacyConfigFile
        if (! file.isFile) return null
        return runCatching { JsonParser.parseString(file.readText()).asJsonObject }
            .onFailure { LayBaltQOL.logger.warn("[LegacyConfigImport] ${file.name} is malformed, skipping it: ${it.message}") }
            .getOrNull()
    }

    private fun readLegacyEspList(): List<String>? {
        val file = legacyEspFile
        if (! file.isFile) return null
        return runCatching { file.readLines().map(String::trim).filter { it.isNotEmpty() && ! it.startsWith("#") }.distinct() }
            .onFailure { LayBaltQOL.logger.warn("[LegacyConfigImport] could not read ${file.name}: ${it.message}") }
            .getOrNull()
    }

    private class Marker(val done: Boolean, val handled: MutableSet<String>)

    private fun readMarker(): Marker {
        val file = markerFile
        if (! file.isFile) return Marker(false, mutableSetOf())
        return runCatching {
            val obj = JsonParser.parseString(file.readText()).asJsonObject
            val handled = obj.getAsJsonArray("handled")?.mapTo(mutableSetOf()) { it.asString } ?: mutableSetOf()
            Marker(obj.get("done")?.asBoolean == true, handled)
        }.getOrElse { Marker(false, mutableSetOf()) }
    }

    private fun writeMarker(marker: Marker) {
        runCatching {
            val file: File = markerFile
            file.parentFile?.mkdirs()
            file.writeText(GsonUtils.gson.toJson(JsonObject().apply {
                addProperty("done", marker.done)
                add("handled", JsonArray().apply { marker.handled.sorted().forEach(::add) })
            }))
        }.onFailure { LayBaltQOL.logger.warn("[LegacyConfigImport] could not write the import marker: ${it.message}") }
    }
}
