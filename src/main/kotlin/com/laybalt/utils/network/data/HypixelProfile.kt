package com.laybalt.utils.network.data

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * [DungeonStats] (the shape NoammAddons' own stats server answered with) built from a Hypixel SkyBlock profiles answer, as
 * Odin's public stats server returns it at `<url>/get/<uuid>` (the raw Hypixel API v2 profiles, also read by /lbq cata):
 * Party Finder's Auto Kick and join stats read it. Pure, so it is unit tested.
 */
object HypixelProfile {
    /** The [uuid]'s member of the selected profile (else the first) in [body]; throws with the server's cause, or without a profile. */
    fun dungeonStats(body: String, uuid: String): DungeonStats {
        val root = JsonParser.parseString(body).asJsonObject
        (root.str("cause") ?: root.str("error"))?.let { error(it) }
        val profiles = root.arr("profiles")?.mapNotNull { it as? JsonObject }.orEmpty()
        val profile = profiles.firstOrNull { it.bool("selected") } ?: profiles.firstOrNull() ?: error("no SkyBlock profile")
        val member = profile.obj("members")?.obj(uuid.replace("-", "")) ?: error("no SkyBlock profile")
        return of(member)
    }

    /** A profile member's dungeon stats; anything the API leaves out (an API setting off, never played) reads as empty. */
    fun of(member: JsonObject): DungeonStats {
        val dungeons = member.obj("dungeons") ?: JsonObject()
        val types = dungeons.obj("dungeon_types")
        val cata = types?.obj("catacombs") ?: JsonObject()
        val master = types?.obj("master_catacombs") ?: JsonObject()
        val inventory = member.obj("inventory")
        val pets = member.obj("pets_data")?.arr("pets")?.mapNotNull { it as? JsonObject }.orEmpty()
        val catacombs = tierData(cata)
        val masterCatacombs = tierData(master)

        return DungeonStats(
            favoriteArrow = member.obj("item_data")?.str("favorite_arrow").orEmpty(),
            selectedPower = member.obj("accessory_bag_storage")?.str("selected_power").orEmpty(),
            abiphoneContacts = member.obj("nether_island_player_data")?.obj("abiphone")?.arr("active_contacts")
                ?.mapNotNull { it.takeIf(JsonElement::isJsonPrimitive)?.asString }.orEmpty(),
            consumedRiftPrism = member.obj("rift")?.obj("access")?.bool("consumed_prism") == true,
            // the blood room's undead, normal and master mode (as /lbq cata counts them)
            bloodMobsKilled = member.obj("player_stats")?.obj("kills").let { (it.number("watcher_summon_undead") + it.number("master_watcher_summon_undead")).toInt() },
            dungeons = DungeonStats.DungeonData(
                // floors 1-7 of both modes, Entrance left out (as /lbq cata counts the runs its secret average divides by)
                totalRuns = (1 .. 7).sumOf { (catacombs.tierCompletions["$it"] ?: 0) + (masterCatacombs.tierCompletions["$it"] ?: 0) },
                secrets = dungeons.number("secrets").toInt(),
                playerClasses = dungeons.obj("player_classes")?.entrySet()
                    ?.associate { (name, data) -> name to (data as? JsonObject).number("experience") }.orEmpty(),
                catacombsExperience = cata.number("experience"),
                catacombs = catacombs,
                masterCatacombs = masterCatacombs,
            ),
            activePet = pets.firstOrNull { it.bool("active") }?.let(::pet),
            // the join stats print them on one line: one per type, legendary or mythic only (a pets menu holds dozens)
            pets = pets.map(::pet).filter { it.tier == "LEGENDARY" || it.tier == "MYTHIC" }.distinctBy { it.type },
            armorData = inventory?.obj("inv_armor")?.str("data").orEmpty(),
            // blank while the Inventory API is off (Auto Kick then skips the Magical Power check)
            talismanBagData = inventory?.obj("bag_contents")?.obj("talisman_bag")?.str("data").orEmpty(),
        )
    }

    private fun tierData(type: JsonObject) = DungeonStats.TierData(
        tierCompletions = floors(type.obj("tier_completions")).mapValues { it.value.toInt() },
        fastestTimeSPlus = floors(type.obj("fastest_time_s_plus")).mapValues { it.value.toLong() },
    )

    /** The floor keys ("0" = Entrance .. "7") of a per-floor object; Hypixel adds a "total" key to some of them. */
    private fun floors(obj: JsonObject?): Map<String, Double> =
        obj?.entrySet()?.filter { it.key.isNotEmpty() && it.key.all(Char::isDigit) }?.associate { it.key to it.value.number() }.orEmpty()

    private fun pet(obj: JsonObject) = DungeonStats.PetSummary(obj.str("type").orEmpty(), obj.str("tier").orEmpty())

    private fun JsonObject.obj(key: String): JsonObject? = get(key)?.takeIf(JsonElement::isJsonObject)?.asJsonObject
    private fun JsonObject.arr(key: String) = get(key)?.takeIf(JsonElement::isJsonArray)?.asJsonArray
    private fun JsonObject.str(key: String): String? = get(key)?.takeIf(JsonElement::isJsonPrimitive)?.asString
    private fun JsonObject.bool(key: String) = get(key)?.takeIf(JsonElement::isJsonPrimitive)?.let { runCatching { it.asBoolean }.getOrNull() } == true
    private fun JsonObject?.number(key: String): Double = this?.get(key)?.number() ?: 0.0
    private fun JsonElement.number(): Double = takeIf(JsonElement::isJsonPrimitive)?.let { runCatching { it.asDouble }.getOrNull() } ?: 0.0
}
