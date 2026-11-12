// Ported from Odin's CataCommand (https://github.com/odtheking/Odin), BSD-3-Clause, Copyright (c) 2025 odtheking
package com.laybalt.commands.impl

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.laybalt.LayBaltQOL
import com.laybalt.LayBaltQOL.mc
import com.laybalt.commands.CommandBuilder
import com.laybalt.commands.LbqHelp
import com.laybalt.commands.LbqHelp.Category
import com.laybalt.features.impl.dungeon.SecretsCounter
import com.laybalt.init.types.ICommandProvider
import com.laybalt.utils.ChatUtils
import com.laybalt.utils.ChatUtils.formattedText
import com.laybalt.utils.NumbersUtils
import com.laybalt.utils.NumbersUtils.toFixed
import com.laybalt.utils.PartyUtils
import com.laybalt.utils.dungeons.enums.DungeonClass
import com.laybalt.utils.items.ItemUtils.lore
import com.laybalt.utils.items.ItemUtils.skyblockId
import com.laybalt.utils.kuudra.KuudraUtils
import com.laybalt.utils.network.ApiUtils
import com.laybalt.utils.network.ProfileUtils
import com.laybalt.utils.network.WebUtils
import com.mojang.brigadier.arguments.StringArgumentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.network.chat.MutableComponent
import net.minecraft.world.item.ItemStack

/**
 * `/lbq cata [player]`: the dungeon stats card of Odin's `/cata`. The SkyBlock profile comes from the stats server set in
 * Secrets Counter > API URL (Odin's public API by default, `<url>/get/<uuid>`); with no URL set the command explains that.
 */
object CataCommand: ICommandProvider {
    private val classes = listOf(DungeonClass.Archer, DungeonClass.Berserk, DungeonClass.Healer, DungeonClass.Mage, DungeonClass.Tank)

    override fun CommandBuilder.command() {
        setName(*LbqHelp.ROOTS.toTypedArray())
        literal("cata") {
            category(Category.PARTY)
            helpUsage("cata [player]")
            description("Shows a player's dungeon stats: level, secrets, classes, floor times, armour, missing items")
            runs { lookup(mc.user.name) }
            argument("player", StringArgumentType.word()) {
                suggests { PartyUtils.members.toList() }
                runs { lookup(StringArgumentType.getString(it, "player")) }
            }
        }
    }

    private fun lookup(name: String) {
        val base = SecretsCounter.apiBaseUrl
        if (base.isEmpty()) return ChatUtils.modMessage("§c/lbq cata needs a stats server. Set one in Secrets Counter > API URL (Odin's is https://hypixel.odtheking.com/).")
        ChatUtils.modMessage("§aFetching dungeon stats for §6$name§a...")
        LayBaltQOL.scope.launch(Dispatchers.IO) {
            runCatching { fetch(base, name) }.onFailure {
                LayBaltQOL.logger.warn("[CataCommand] lookup of $name failed", it)
                ChatUtils.modMessage("§cCould not load the stats of §6$name§c: ${it.message ?: it::class.simpleName}")
            }
        }
    }

    private suspend fun fetch(base: String, name: String) {
        val mojang = ProfileUtils.getUUIDbyName(name).getOrElse { throw IllegalStateException("player not found") }
        val body = WebUtils.getAs<String>("$base/get/${mojang.uuid}").getOrElse { throw IllegalStateException("the stats server did not answer (${it.message?.take(80)})") }
        val root = JsonParser.parseString(body).asJsonObject
        (root.str("cause") ?: root.str("error"))?.let { throw IllegalStateException(it) }

        val profiles = root.arr("profiles")?.mapNotNull { it as? JsonObject }.orEmpty()
        val profile = profiles.firstOrNull { it.bool("selected") } ?: profiles.firstOrNull()
        // the members are keyed by the undashed UUID, while names seen in the tab list are cached with dashes
        val member = profile?.obj("members")?.obj(mojang.uuid.replace("-", ""))
            ?: return ChatUtils.modMessage("§cNo SkyBlock profile found for §6${mojang.name}§c!")
        ChatUtils.chat(card(mojang.name, member))
    }

    // region card

    private fun card(name: String, member: JsonObject): Component {
        val dungeons = member.obj("dungeons") ?: JsonObject()
        val types = dungeons.obj("dungeon_types")
        val cata = types?.obj("catacombs") ?: JsonObject()
        val master = types?.obj("master_catacombs") ?: JsonObject()
        val inventory = member.obj("inventory")

        val out = Component.literal("§d§m${" ".repeat(11)}§r §b$name §d§m${" ".repeat(11)}§r\n")
        out.append(levelLine(member, dungeons, cata, master))
        out.append(classLine(dungeons))
        out.append(floorLine(member, cata, master, inventory))

        val armor = inventory?.items("inv_armor").orEmpty()
        if (armor.size >= 4) out.append(armorLine(armor))
        missingItems(member, inventory).takeIf { it.isNotEmpty() }?.let { out.append(missingLine(it)) }
        return out.append(Component.literal("§d§m${" ".repeat(27)}§r"))
    }

    private fun levelLine(member: JsonObject, dungeons: JsonObject, cata: JsonObject, master: JsonObject): Component {
        val cataXp = cata.num("experience")
        val secrets = dungeons.num("secrets").toLong()
        val totalRuns = (1 .. 7).sumOf { cata.obj("tier_completions").num("$it").toInt() + master.obj("tier_completions").num("$it").toInt() }
        val average = if (totalRuns > 0) secrets.toDouble() / totalRuns else 0.0
        val watcherKills = cata.obj("watcher_kills")?.entrySet()?.sumOf { it.value.asDouble }?.toLong() ?: 0L
        val kills = member.obj("player_stats")?.obj("kills")
        val bloodMobs = (kills.num("watcher_summon_undead") + kills.num("master_watcher_summon_undead")).toLong()

        return Component.literal("§7Cata: §e${ApiUtils.getCatacombsLevelExact(cataXp).toFixed(2)}")
            .hover("§7Catacombs Level\n§7XP: §b${NumbersUtils.formatComma(cataXp.toLong())}")
            .append(Component.literal(" §8| §7Secrets: §e${NumbersUtils.formatComma(secrets)} §8(§b${average.toFixed(1)}§8)")
                .hover("§7Total Secrets: §e${NumbersUtils.formatComma(secrets)}\n§7Total Runs: §b$totalRuns\n§7Average: §a${average.toFixed(2)}"))
            .append(Component.literal(" §8| §7Blood: §c${NumbersUtils.formatComma(watcherKills)}")
                .hover("§7Total Watcher Kills: §c${NumbersUtils.formatComma(watcherKills)}\n§7Blood Mobs Killed: §5${NumbersUtils.formatComma(bloodMobs)}"))
            .append(Component.literal("\n"))
    }

    private fun classLine(dungeons: JsonObject): Component {
        val playerClasses = dungeons.obj("player_classes")
        val line = Component.literal("§7Classes: ")
        var totalXp = 0.0
        val levels = classes.mapIndexed { i, clazz ->
            val xp = playerClasses?.obj(clazz.name.lowercase()).num("experience")
            totalXp += xp
            val level = ApiUtils.getCatacombsLevelExact(xp)
            line.append(Component.literal("${clazz.code}${level.toFixed(2)}").hover("${clazz.code}${clazz.name} Level\n§7XP: §b${NumbersUtils.formatComma(xp.toLong())}"))
            if (i < classes.lastIndex) line.append(Component.literal("§8/"))
            level
        }
        line.append(Component.literal(" §8(§7Avg: §a${levels.average().toFixed(1)}§8)").hover("§7Class Average\n§7Total Class XP: §b${NumbersUtils.formatComma(totalXp.toLong())}"))
        return line.append(Component.literal("\n"))
    }

    private fun floorHover(type: JsonObject, title: String, prefix: String): String = buildString {
        append(title)
        for (floor in 1 .. 7) {
            val sPlus = type.obj("fastest_time_s_plus").num("$floor").toLong()
            val best = type.obj("fastest_time").num("$floor").toLong()
            val comps = type.obj("tier_completions").num("$floor").toInt()
            val time = when {
                sPlus > 0 -> "§a${KuudraUtils.formatTime(sPlus)}"
                best > 0 -> "§7${KuudraUtils.formatTime(best)}"
                else -> "§8None"
            }
            append("\n$prefix$floor: $time §8(§b$comps§8)")
        }
    }

    private fun floorLine(member: JsonObject, cata: JsonObject, master: JsonObject, inventory: JsonObject?): Component {
        val bag = member.obj("accessory_bag_storage")
        val tunings = bag?.obj("tuning")?.obj("slot_0")?.entrySet()?.associate { it.key to it.value.asInt }.orEmpty()
        val contacts = member.obj("nether_island_player_data")?.obj("abiphone")?.arr("active_contacts")?.size() ?: 0
        val prism = member.obj("rift")?.obj("access")?.bool("consumed_prism") == true
        val talismans = inventory?.obj("bag_contents")?.let { decode(it, "talisman_bag") }.orEmpty()
        val magicalPower = ApiUtils.getMagicalPower(talismans, contacts, prism).takeUnless { it == 0 } ?: (tunings.values.sum() * 10)
        val tuningText = tunings.entries.joinToString("") { "\n§7- §e${it.key.replace('_', ' ').replaceFirstChar(Char::uppercase)}§7: ${it.value}" }

        return Component.literal("§7Floors: ")
            .append(Component.literal("§6Normal").hover(floorHover(cata, "§6§lNormal Floors", "§eF")))
            .append(Component.literal(" §8| "))
            .append(Component.literal("§cMaster").hover(floorHover(master, "§c§lMaster Floors", "§cM")))
            .append(Component.literal(" §8| "))
            .append(Component.literal("§7MP: §d${NumbersUtils.formatComma(magicalPower)}").hover("§bTunings$tuningText"))
            .append(Component.literal("\n"))
    }

    private fun armorLine(armor: List<ItemStack>): Component {
        val line = Component.literal("§7Armor: ")
        val slots = listOf("⛑" to armor[3], "🛡" to armor[2], "👖" to armor[1], "👢" to armor[0])
        slots.forEachIndexed { i, (icon, stack) ->
            val hover = if (stack.isEmpty) "§8Empty Slot" else (listOf(stack.hoverName.formattedText) + stack.lore).joinToString("\n")
            line.append(Component.literal(icon).hover(hover))
            if (i < slots.lastIndex) line.append(Component.literal(" §8| "))
        }
        return line.append(Component.literal("\n"))
    }

    private fun missingItems(member: JsonObject, inventory: JsonObject?): List<Pair<String, String>> {
        val ids = buildList {
            inventory?.let { inv ->
                addAll(inv.items("inv_contents"))
                addAll(inv.items("ender_chest_contents"))
                inv.obj("backpack_contents")?.let { packs -> packs.keySet().forEach { addAll(decode(packs, it)) } }
            }
        }.map { it.skyblockId }
        fun has(vararg wanted: String) = ids.any { id -> wanted.any { it in id } }
        val pets = member.obj("pets_data")?.arr("pets")?.mapNotNull { (it as? JsonObject)?.str("type") }.orEmpty()

        return buildList {
            if (! has("HYPERION", "ASTRAEA", "SCYLLA", "VALKYRIE")) add("Blade" to "§5Wither Blade")
            if (! has("TERMINATOR")) add("Terminator" to "§cTerm")
            if (pets.none { it.equals("GOLDEN_DRAGON", true) }) add("Golden Dragon" to "§6GDrag")
            if (pets.none { it.equals("ENDER_DRAGON", true) }) add("Ender Dragon" to "§5EDrag")
        }
    }

    private fun missingLine(missing: List<Pair<String, String>>): Component {
        val line = Component.literal("§7Missing: ")
        missing.forEachIndexed { i, (name, short) ->
            line.append(Component.literal("§c✖ $short").hover("§cMissing $name"))
            if (i < missing.lastIndex) line.append(Component.literal(" §8| "))
        }
        return line.append(Component.literal("\n"))
    }

    // endregion

    // region json helpers (the Hypixel profile has optional fields everywhere)

    private fun MutableComponent.hover(text: String): MutableComponent = withStyle { it.withHoverEvent(HoverEvent.ShowText(Component.literal(text))) }
    private fun JsonObject.obj(key: String): JsonObject? = get(key)?.takeIf(JsonElement::isJsonObject)?.asJsonObject
    private fun JsonObject.arr(key: String) = get(key)?.takeIf(JsonElement::isJsonArray)?.asJsonArray
    private fun JsonObject.str(key: String): String? = get(key)?.takeIf(JsonElement::isJsonPrimitive)?.asString
    private fun JsonObject.bool(key: String) = get(key)?.takeIf(JsonElement::isJsonPrimitive)?.asBoolean == true
    private fun JsonObject?.num(key: String): Double = this?.get(key)?.takeIf(JsonElement::isJsonPrimitive)?.let { runCatching { it.asDouble }.getOrNull() } ?: 0.0

    /** Items of an inventory section such as `inv_armor`: `{ "type": 0, "data": "<base64 gzip nbt>" }`. */
    private fun JsonObject.items(key: String): List<ItemStack> = decode(this, key)
    private fun decode(parent: JsonObject, key: String): List<ItemStack> = parent.obj(key)?.str("data")?.let(ApiUtils::decodeBase64ItemList).orEmpty()

    // endregion
}
