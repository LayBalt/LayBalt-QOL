package com.laybalt.config.migrators

import com.laybalt.config.ConfigMigrator
import com.laybalt.init.types.ISelfInit
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive

/**
 * Version 2: the Dungeon Map "Door ESP" section (Box Wither Doors) became the separate Door ESP feature
 * (NoammAddons 20e5baf1). The old settings are moved over so users keep their wither/blood door boxes:
 *
 * | Dungeon Map (v1)      | Door ESP (v2)                                   |
 * |-----------------------|-------------------------------------------------|
 * | Box Wither Doors      | feature enabled (with Dungeon Map on) + "Blood rush doors" |
 * | Highlight All Doors   | Highlight All Doors                             |
 * | Highlight Mode        | Mode                                            |
 * | No Key Color / Has Key Color | same keys                                |
 *
 * The old keys are always removed from Dungeon Map; a Door ESP entry is only created when Box Wither Doors was on
 * and the config has no Door ESP entry yet.
 */
object ConfigMigratorV1ToV2: ConfigMigrator(1, 2), ISelfInit {
    override fun init() = register(this)

    private const val MAP = "Dungeon Map"
    private const val DOOR_ESP = "Door ESP"
    private val moved = mapOf(
        "Highlight All Doors" to "Highlight All Doors",
        "Highlight Mode" to "Mode",
        "No Key Color " to "No Key Color ",
        "Has Key Color " to "Has Key Color ",
    )

    override fun migrate(root: JsonObject): JsonObject {
        root.addProperty("version", 2)
        val config = root.getAsJsonArray("config") ?: return root
        val features = config.mapNotNull { it as? JsonObject }

        val map = features.firstOrNull { it.get("name")?.asString == MAP } ?: return root
        val mapSettings = map.getAsJsonObject("configSettings") ?: return root
        val boxDoors = mapSettings.remove("Box Wither Doors") ?: return root
        val oldValues = moved.keys.associateWith { mapSettings.remove(it) }

        // Only users who had the boxes on get a Door ESP entry; everyone else starts from the Door ESP defaults.
        val wasOn = runCatching { boxDoors.asBoolean }.getOrDefault(false)
        if (! wasOn || features.any { it.get("name")?.asString == DOOR_ESP }) return root

        val mapOn = runCatching { map.get("enabled")?.asBoolean }.getOrNull() ?: false
        val settings = JsonObject().apply {
            add("Blood rush doors", JsonPrimitive(true))
            for ((old, new) in moved) oldValues[old]?.let { add(new, it) }
        }
        config.add(JsonObject().apply {
            addProperty("name", DOOR_ESP)
            addProperty("enabled", mapOn)
            add("configSettings", settings)
        })
        return root
    }
}
