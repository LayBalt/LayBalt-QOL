package com.laybalt.features.impl.dungeon

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.laybalt.features.impl.dungeon.SecretWaypoints.Category
import com.laybalt.features.impl.dungeon.SecretWaypoints.Entry
import com.laybalt.utils.dungeons.map.core.RoomData
import com.laybalt.utils.dungeons.map.core.RoomShape
import com.laybalt.utils.dungeons.map.utils.ScanUtils
import java.io.File
import java.util.zip.ZipFile

/**
 * Reads a secret waypoint dataset that the user supplies from their own disk, for [SecretWaypoints]' `/lsw import`.
 * Nothing is bundled or downloaded: LayBaltQOL only ships the chest/item/bat/essence/key coordinates of `rooms-modern.json`.
 *
 * Accepted input (a single file, a folder searched recursively, or a .zip/.jar archive):
 *  - per-room documents: `{ "info": { "name": "<room>" }, "secrets": [ { "secretName": "1 - Chest", "category": "chest", "x": 1, "y": 2, "z": 3 } ] }`
 *    (the layout used by several dungeon mods; the leading number of `secretName` is the secret index),
 *  - LayBaltQOL's own `secretWaypoints.json` layout: `{ "<room>": [ { "index": 1, "category": "chest", "name": "1 - Chest", "x": 1, "y": 2, "z": 3 } ] }`.
 *
 * Room names differ between data sets and some rooms were recorded from another corner, so every per-room document is
 * matched against the known rooms by comparing its chest / item / essence / key coordinates with ours in each possible
 * rotation; the room name is only the fallback.
 */
internal object SecretWaypointImport {
    class Result(
        val rooms: Map<String, List<Entry>>,
        val documents: Int,
        val ignored: Int,
        val remapped: List<String>,
        val unmatched: List<String>,
        val errors: List<String>
    )

    private class RoomDoc(val name: String, val entries: List<Entry>, val perRoomLayout: Boolean)
    private data class Key(val category: Category, val x: Int, val y: Int, val z: Int)
    private data class Candidate(val room: RoomData, val rotation: Int, val hits: Int)

    private const val MAX_DOCUMENT_BYTES = 2L * 1024 * 1024
    private val indexRegex = Regex("^(\\d+)")
    private val trailingCountRegex = Regex("-\\d+(-(high|low))?$")
    private val matchCategories = setOf(Category.CHEST, Category.ITEM, Category.WITHER, Category.KEY)

    fun read(source: File): Result {
        val docs = mutableListOf<RoomDoc>()
        val errors = mutableListOf<String>()
        var documents = 0
        var ignored = 0

        fun accept(text: String, fileName: String, strict: Boolean) {
            val parsed = runCatching { parseDocument(text, fileName) }.getOrNull()
            if (parsed.isNullOrEmpty()) {
                ignored ++
                if (strict) errors += "$fileName: not a secret waypoint file"
                return
            }
            documents ++
            docs += parsed
        }

        when {
            source.isDirectory -> source.walkTopDown()
                .filter { it.isFile && it.extension.equals("json", true) && it.length() <= MAX_DOCUMENT_BYTES }
                .sortedBy { it.path }
                .forEach { file -> runCatching { accept(file.readText(), file.name, false) }.onFailure { errors += "${file.name}: ${it.message}" } }

            source.extension.lowercase() in setOf("zip", "jar") -> ZipFile(source).use { zip ->
                zip.entries().asSequence()
                    .filter { ! it.isDirectory && it.name.endsWith(".json", true) && it.size in 0 .. MAX_DOCUMENT_BYTES }
                    .sortedBy { it.name }
                    .forEach { entry ->
                        runCatching { zip.getInputStream(entry).use { accept(it.readBytes().decodeToString(), entry.name.substringAfterLast('/'), false) } }
                            .onFailure { errors += "${entry.name}: ${it.message}" }
                    }
            }

            else -> accept(source.readText(), source.name, true)
        }

        val rooms = LinkedHashMap<String, MutableList<Entry>>()
        val remapped = mutableListOf<String>()
        val unmatched = mutableListOf<String>()

        for (doc in docs) {
            val (target, entries) = resolve(doc, remapped, unmatched)
            val list = rooms.getOrPut(target) { mutableListOf() }
            for (entry in entries) {
                if (list.none { it.relPos == entry.relPos && it.category == entry.category }) list += entry
            }
        }
        rooms.entries.removeIf { it.value.isEmpty() }
        return Result(rooms, documents, ignored, remapped, unmatched.distinct(), errors)
    }

    // ---------------------------------------------------------------- parsing

    private fun parseDocument(text: String, fileName: String): List<RoomDoc>? {
        val root = JsonParser.parseString(text) as? JsonObject ?: return null

        val secrets = root.get("secrets")
        if (secrets is JsonArray) {
            val name = (root.get("info") as? JsonObject)?.get("name")?.takeIf { it.isJsonPrimitive }?.asString
                ?: nameFromFile(fileName)
            val entries = secrets.mapNotNull { parseEntry(it) }
            return listOf(RoomDoc(name, entries, true))
        }

        // LayBaltQOL layout: every value is an array of entries
        if (root.size() == 0 || root.entrySet().any { it.value !is JsonArray }) return null
        val docs = root.entrySet().map { (room, value) -> RoomDoc(room, (value as JsonArray).mapNotNull { parseEntry(it) }, false) }
        return docs.takeIf { list -> list.any { it.entries.isNotEmpty() } }
    }

    private fun parseEntry(element: JsonElement): Entry? = runCatching {
        val obj = element as? JsonObject ?: return null
        val x = obj.get("x")?.asInt ?: return null
        val y = obj.get("y")?.asInt ?: return null
        val z = obj.get("z")?.asInt ?: return null
        val name = (obj.get("name") ?: obj.get("secretName"))?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.takeIf { it.isNotEmpty() }
        val index = obj.get("index")?.asInt ?: name?.let { indexRegex.find(it)?.value?.toIntOrNull() } ?: 0
        val category = Category.parse(obj.get("category")?.asString) ?: Category.DEFAULT
        Entry(index, category.key, name, x, y, z, JsonObject().apply { addProperty(SecretWaypoints.IMPORTED_FLAG, true) })
    }.getOrNull()

    /** "dino-dig-site-4.json" -> "Dino Dig Site". */
    private fun nameFromFile(fileName: String) = fileName.substringBeforeLast('.')
        .replace(trailingCountRegex, "")
        .split('-', '_', ' ').filter { it.isNotEmpty() }
        .joinToString(" ") { part -> part.replaceFirstChar { it.uppercase() } }

    // ---------------------------------------------------------------- room matching

    private fun normalize(name: String) = name.lowercase().filter { it.isLetterOrDigit() }

    private fun extent(shape: RoomShape?): Pair<Int, Int>? = when (shape) {
        RoomShape.S1x1 -> 30 to 30
        RoomShape.S2x1 -> 62 to 30
        RoomShape.S3x1 -> 94 to 30
        RoomShape.S4x1 -> 126 to 30
        RoomShape.S2x2, RoomShape.SL -> 62 to 62
        else -> null
    }

    /** Maps a room-relative x/z recorded from another corner back to our corner. [w]/[h] = relative extent of the room. */
    private fun rotate(x: Int, z: Int, rotation: Int, w: Int, h: Int): Pair<Int, Int> = when (rotation) {
        90 -> (w - z) to x
        180 -> (w - x) to (h - z)
        270 -> z to (w - x)
        else -> x to z
    }

    private fun keysOf(room: RoomData): Set<Key> {
        val coords = room.secretCoords
        val keys = HashSet<Key>()
        coords.chest.forEach { keys += Key(Category.CHEST, it.x, it.y, it.z) }
        coords.item.forEach { keys += Key(Category.ITEM, it.x, it.y, it.z) }
        coords.wither.forEach { keys += Key(Category.WITHER, it.x, it.y, it.z) }
        coords.redstoneKey.forEach { keys += Key(Category.KEY, it.x, it.y, it.z) }
        return keys
    }

    private fun resolve(doc: RoomDoc, remapped: MutableList<String>, unmatched: MutableList<String>): Pair<String, List<Entry>> {
        val rooms = ScanUtils.allRooms
        val named = rooms.find { it.name == doc.name } ?: rooms.find { normalize(it.name) == normalize(doc.name) }

        // our own layout is already in our frame and uses our names
        if (! doc.perRoomLayout && named != null) return named.name to doc.entries

        val probes = doc.entries.mapNotNull { e -> Category.parse(e.category)?.takeIf { it in matchCategories }?.let { e to it } }
        val candidates = mutableListOf<Candidate>()
        var namedBest: Candidate? = named?.let { Candidate(it, 0, 0) }

        if (probes.isNotEmpty()) for (room in rooms) {
            val (w, h) = extent(room.shape) ?: continue
            val keys = keysOf(room)
            if (keys.isEmpty()) continue
            val rotations = if (w == h) listOf(0, 90, 180, 270) else listOf(0, 180)
            for (rotation in rotations) {
                val hits = probes.count { (e, category) ->
                    val (x, z) = rotate(e.x, e.z, rotation, w, h)
                    Key(category, x, e.y, z) in keys
                }
                if (hits == 0) continue
                if (room === named && hits > (namedBest?.hits ?: 0)) namedBest = Candidate(room, rotation, hits)
                // one shared block is only trusted when it is everything both sides have
                if (hits >= 2 || hits == minOf(probes.size, keys.size)) candidates += Candidate(room, rotation, hits)
            }
        }

        val top = candidates.maxOfOrNull { it.hits }?.let { max -> candidates.filter { it.hits == max } }.orEmpty()
        val pick = when {
            namedBest != null && (top.isEmpty() || namedBest.hits >= top.first().hits) -> namedBest
            top.size == 1 -> top.first()
            else -> namedBest
        }

        if (pick == null) {
            unmatched += doc.name
            return doc.name to doc.entries
        }

        val (w, h) = extent(pick.room.shape) ?: (0 to 0)
        if (pick.room.name != doc.name || pick.rotation != 0) {
            remapped += "${doc.name} -> ${pick.room.name}" + if (pick.rotation != 0) " (rotated ${pick.rotation})" else ""
        }
        if (pick.rotation == 0) return pick.room.name to doc.entries
        return pick.room.name to doc.entries.map { e ->
            val (x, z) = rotate(e.x, e.z, pick.rotation, w, h)
            e.copy(x = x, z = z)
        }
    }
}
