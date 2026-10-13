package com.laybalt.config

import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * Defaults that changed after configs had been saved with the old value. Every setting is saved, so a new default alone
 * never reaches an existing config; each change here is applied once per config file, which then lists its id under
 * [KEY]. A config created after the change only records it. Older jars ignore the list, so no config version bump (which
 * they could not read) is needed.
 */
class OneShotDefaults {
    private val changes = LinkedHashMap<String, () -> Unit>()

    /** Ids applied to (or not needed by) the config loaded last. */
    val applied = LinkedHashSet<String>()

    fun register(id: String, change: () -> Unit) {
        changes[id] = change
    }

    /**
     * Applies the changes the config [root] has not had yet and returns their ids; with no config yet ([root] null)
     * every change counts as applied, the defaults being the new ones already.
     */
    fun apply(root: JsonObject?, onError: (String, Throwable) -> Unit = { _, _ -> }): List<String> {
        applied.clear()
        if (root == null) {
            applied += changes.keys
            return emptyList()
        }
        root.getAsJsonArray(KEY)?.forEach { e -> runCatching { applied += e.asString } }
        val done = ArrayList<String>()
        for ((id, change) in changes) {
            if (id in applied) continue
            runCatching(change).onFailure { onError(id, it) }
            applied += id
            done += id
        }
        return done
    }

    /** The list for the saved config. */
    fun toJson(): JsonArray = JsonArray().apply { applied.forEach { add(it) } }

    companion object {
        const val KEY = "appliedDefaults"
    }
}
