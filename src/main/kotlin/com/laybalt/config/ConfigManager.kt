package com.laybalt.config

import com.laybalt.LayBaltQOL
import com.laybalt.features.FeatureManager
import com.laybalt.features.impl.dev.ClickGui
import com.laybalt.features.impl.visual.Scoreboard
import com.laybalt.init.types.ISelfInit
import com.laybalt.ui.Palette
import com.laybalt.utils.*
import com.laybalt.utils.GsonUtils.gsonArray
import com.laybalt.utils.GsonUtils.gsonObject
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.fabricmc.loader.api.FabricLoader
import java.io.File
import java.util.concurrent.*

object ConfigManager: ISelfInit {
    private val configRegex = Regex("^[A-Za-z0-9_-]{1,32}$")
    private val configPath = FabricLoader.getInstance().configDir.resolve(LayBaltQOL.CONFIG_DIR)
    private val configsDir = configPath.resolve("configs").toFile()
    private val defaultConfigFile = File(configPath.toFile(), "config.json")
    private val selectedConfig = PogObject<String>("currentConfig", "default")
    private var configFile = FileHandler(getConfigs()[selectedConfig.get()] ?: defaultConfigFile)
    private const val VERSION = 2

    /** Set once the config was read (or there was none yet), so a failed load is never overwritten by the autosave. */
    @Volatile private var loaded = false

    /** Defaults that changed after configs had been saved with the old value, applied once per config ([OneShotDefaults]). */
    private val oneShotDefaults = OneShotDefaults()

    /** Registers a one-shot default change (call from a feature's init, which runs before [load]). */
    fun oneShotDefault(id: String, change: () -> Unit) = oneShotDefaults.register(id, change)

    /** Applies the one-shot defaults [root] has not had yet; true when something was applied. */
    private fun applyOneShotDefaults(root: JsonObject?): Boolean {
        val done = oneShotDefaults.apply(root) { id, e -> LayBaltQOL.logger.warn("[ConfigManager] could not apply the new default $id", e) }
        done.forEach { LayBaltQOL.logger.info("[ConfigManager] applied the new default $it") }
        return done.isNotEmpty()
    }

    /** NoammAddons 0616ab4f: settings changed outside the GUI (map/list settings) are saved every 5 minutes and on exit. */
    override fun init() {
        ThreadUtils.addShutdownHook { if (loaded) save() }
        ThreadUtils.setTimeout(TimeUnit.MINUTES.toMillis(5)) {
            ThreadUtils.loop(TimeUnit.MINUTES.toMillis(5)) { if (loaded) save() }
        }
    }

    fun getConfigs(): Map<String, File> {
        val named = configsDir.listFiles()
            ?.filter { it.name.endsWith(".json") && configRegex.matches(it.nameWithoutExtension) }
            ?.associateBy { it.nameWithoutExtension } ?: emptyMap()
        return named + ("default" to defaultConfigFile)
    }

    fun createConfig(configName: String): Boolean {
        val newFile = run {
            if (! configRegex.matches(configName)) return@run null
            val file = File(configsDir, "$configName.json").canonicalFile
            if (file.parentFile != configsDir.canonicalFile) return@run null
            return@run file
        } ?: run {
            ChatUtils.modMessage("&cInvalid config name \"$configName\". Use 1-32 characters: A-Z, 0-9, _ or -.")
            return false
        }

        if (configName in getConfigs().keys) {
            ChatUtils.modMessage("&cThere is already a config named \"$configName\".")
            return false
        }

        configsDir.mkdirs()
        configFile.file.copyTo(newFile)
        configFile = FileHandler(newFile)
        selectedConfig.set(configName)
        ChatUtils.modMessage("&aSuccessfully created config \"$configName\".")
        return true
    }

    fun changeConfig(configName: String) {
        if (! configRegex.matches(configName)) return ChatUtils.modMessage("&cInvalid config name \"$configName\".")
        val newConfigFile = getConfigs()[configName] ?: return ChatUtils.modMessage("&cNo config named \"$configName\" was found.")
        if (! newConfigFile.exists()) return ChatUtils.modMessage("&cNo config file found for \"$configName\".")
        configFile = FileHandler(newConfigFile)
        selectedConfig.set(configName)
        load()
        ChatUtils.modMessage("&aSuccessfully loaded config \"$configName\".")
    }

    fun deleteConfig(configName: String): Boolean {
        if (configName == "default") {
            ChatUtils.modMessage("&cYou cannot delete the default config.")
            return false
        }

        if (! configRegex.matches(configName)) {
            ChatUtils.modMessage("&cInvalid config name \"$configName\".")
            return false
        }

        val file = getConfigs()[configName] ?: run {
            ChatUtils.modMessage("&cNo config found with the name \"$configName\".")
            return false
        }

        file.delete()
        ChatUtils.modMessage("&aSuccessfully deleted the config \"$configName\".")
        if (configName == selectedConfig.get()) changeConfig("default")
        return true
    }

    fun load() {
        val fileContent = configFile.read().takeUnless(String::isEmpty) ?: return run {
            applyOneShotDefaults(null)
            loaded = true
        }
        val root = JsonParser.parseString(fileContent).asJsonObject
        val originalVersion = root.get("version")?.asInt ?: 0

        val migrated = ConfigMigrator.migrate(root, originalVersion)
        if (migrated.get("version")?.asInt != VERSION) error("[ConfigManager] unsupported config version $originalVersion")

        if (originalVersion != VERSION) {
            configFile.write(GsonUtils.gson.toJson(migrated))
            LayBaltQOL.logger.info("[ConfigManager] migrated config from version $originalVersion to $VERSION")
        }

        read(migrated)
        loaded = true
        if (applyOneShotDefaults(migrated)) save()
    }

    fun save() = configFile.write(GsonUtils.gson.toJson(gsonObject {
        addProperty("version", VERSION)
        add(OneShotDefaults.KEY, oneShotDefaults.toJson())
        add("config", gsonArray {
            for (feature in FeatureManager.features) add(gsonObject {
                addProperty("name", feature.jsonName)
                addProperty("enabled", feature.enabled)
                add("configSettings", gsonObject {
                    for (setting in feature.configSettings) {
                        if (setting !is Savable) continue
                        add(setting.jsonName, setting.write())
                    }
                })
            })
        })
        add("hud", gsonArray {
            for (hud in FeatureManager.hudElements) add(gsonObject {
                addProperty("name", hud.name)
                addProperty("x", hud.x)
                addProperty("y", hud.y)
                addProperty("scale", hud.scale)
            })
        })
    }))

    /** Old default positions of the LayBalt HUD widgets, which overlapped each other in the top-left corner. */
    private val relocatedDefaults = mapOf(
        "SessionTimer" to (5f to 45f),
        "Keystrokes" to (5f to 60f),
        "ArmorHud" to (5f to 160f),
        "EffectsHud" to (80f to 60f),
        "SpeedHud" to (130f to 5f),
        "PerformanceGraph" to (240f to 5f),
        // these two had no default and sat at the generic (20, 20), on top of Coords HUD
        "WarpCooldown" to (20f to 20f),
        "Arrow Poison" to (20f to 20f)
    )

    private fun read(root: JsonObject) {
        root.getAsJsonArray("config").map { it.asJsonObject }.forEach { featureElement ->
            val feature = FeatureManager.getFeatureByName(featureElement.get("name")?.asString ?: return@forEach) ?: return@forEach
            // WIP features stay disabled whatever the file says (toggle() would refuse and notify anyway).
            if (! feature.wip && featureElement.get("enabled")?.asBoolean != feature.enabled) feature.toggle()
            // Unknown features/settings (e.g. from a NoammAddons config) are skipped; a value of the wrong shape only logs.
            featureElement.getAsJsonObject("configSettings")?.entrySet()?.forEach { (name, value) ->
                runCatching { (feature.getSettingByName(name) as? Savable)?.read(value) }
                    .onFailure { LayBaltQOL.logger.warn("[ConfigManager] could not read ${feature.jsonName} / $name = $value: ${it.message}") }
            }
        }

        // Users who saved the old NA default accent (light blue) get the new LayBalt violet default.
        if (ClickGui.accentColor.value.rgb == Palette.legacyAccent.rgb) ClickGui.accentColor.value = Palette.defaultAccent

        root.getAsJsonArray("hud")?.forEach { hudElement ->
            val hudObj = hudElement.asJsonObject
            val hudName = hudObj.get("name")?.asString ?: return@forEach
            // 3.2.1 and older saved the scoreboard with a different meaning of x (see Scoreboard.HUD_ID)
            if (hudName == Scoreboard.LEGACY_HUD_ID && FeatureManager.getHudByName(Scoreboard.HUD_ID) != null) {
                if (root.getAsJsonArray("hud").none { it.asJsonObject.get("name")?.asString == Scoreboard.HUD_ID })
                    runCatching { Scoreboard.migrateLegacyHud(hudObj.get("x")?.asFloat, hudObj.get("y")?.asFloat, hudObj.get("scale")?.asFloat) }
                return@forEach
            }
            val hud = FeatureManager.getHudByName(hudName) ?: return@forEach
            val sx = hudObj.get("x")?.asFloat
            val sy = hudObj.get("y")?.asFloat
            // Widgets still at a superseded default (they overlapped) move to the new default instead.
            if (sx != null && sy != null && relocatedDefaults[hud.name] == (sx to sy) && (hudObj.get("scale")?.asFloat ?: 1f) == 1f) return@forEach
            hudObj.get("x")?.asFloat?.let { hud.x = it }
            hudObj.get("y")?.asFloat?.let { hud.y = it }
            hudObj.get("scale")?.asFloat?.let { hud.scale = it }
        }
    }
}