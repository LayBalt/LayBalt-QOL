// Sea creature table converted from SkyHanni-REPO (https://github.com/hannibal2/SkyHanni-REPO), MIT License, Copyright (c) 2022 hannibal2
package com.laybalt.features.impl.fishing

//#if CHEAT

import com.google.gson.Gson
import com.laybalt.utils.items.ItemRarity
import java.io.Reader

/**
 * Every SkyBlock sea creature with its location group, rarity and catch message, read from the bundled
 * `/assets/lbqmod/sea-creatures.json` (converted from SkyHanni-REPO `constants/SeaCreatures.json`).
 */
object SeaCreatures {
    class Group(val id: String, val label: String, val creatures: List<SeaCreature>)

    class SeaCreature(
        val name: String,
        val rarity: ItemRarity,
        /** Legendary/mythic creatures SkyHanni marks as rare catches (Thunder, Jawbus, Water Hydra...). */
        val rare: Boolean,
        val catchMessage: String?,
        val oldNames: List<String>,
        val groupId: String
    ) {
        val group get() = groupsById.getValue(groupId)
    }

    private class RawFile(val groups: List<RawGroup> = emptyList())
    private class RawGroup(val id: String = "", val label: String = "", val creatures: List<RawCreature> = emptyList())
    private class RawCreature(val name: String = "", val rarity: String = "COMMON", val rare: Boolean = false, val catch: String? = null, val oldNames: List<String>? = null)

    val groups: List<Group>
    val all: List<SeaCreature>
    private val groupsById: Map<String, Group>
    private val byLowerName: Map<String, SeaCreature>
    private val byCatch: Map<String, SeaCreature>
    /** Names (and old names) longest first, so "Baby Magma Slug" wins over "Magma Slug" in [match]. */
    private val namesLongestFirst: List<Pair<String, SeaCreature>>

    init {
        val json = SeaCreatures::class.java.getResourceAsStream("/assets/lbqmod/sea-creatures.json")
            ?.reader()?.use(Reader::readText) ?: error("Could not find sea creature file")
        // plain Gson (the file has no type that needs the mod's adapters), so the recorder-only jar can read it too
        val raw = Gson().fromJson(json, RawFile::class.java)
        groups = raw.groups.map { g ->
            Group(g.id, g.label, g.creatures.map { c ->
                val rarity = ItemRarity.entries.firstOrNull { it.name == c.rarity } ?: ItemRarity.COMMON
                SeaCreature(c.name, rarity, c.rare, c.catch, c.oldNames.orEmpty(), g.id)
            })
        }
        groupsById = groups.associateBy { it.id }
        all = groups.flatMap { it.creatures }
        byLowerName = buildMap {
            all.forEach { sc -> sc.oldNames.forEach { put(it.lowercase(), sc) } }
            all.forEach { put(it.name.lowercase(), it) }
        }
        byCatch = all.mapNotNull { sc -> sc.catchMessage?.let { it to sc } }.toMap()
        namesLongestFirst = byLowerName.entries.map { it.key to it.value }.sortedByDescending { it.first.length }
    }

    fun byName(name: String): SeaCreature? = byLowerName[name.trim().lowercase()]

    /** The creature whose catch message is exactly [line] (unformatted, trimmed). */
    fun byCatchMessage(line: String): SeaCreature? = byCatch[line.trim()]

    /**
     * The creature named in a cleaned nametag such as `Corrupted Sea Walker` or `⚓ Sea Walker`: an exact name first,
     * then the longest known name contained as whole words.
     */
    fun match(cleanName: String): SeaCreature? {
        byName(cleanName)?.let { return it }
        val lower = cleanName.lowercase()
        return namesLongestFirst.firstOrNull { (name, _) ->
            val i = lower.indexOf(name)
            i >= 0 && (i == 0 || ! lower[i - 1].isLetterOrDigit()) && (i + name.length == lower.length || ! lower[i + name.length].isLetterOrDigit())
        }?.second
    }
}

//#endif
