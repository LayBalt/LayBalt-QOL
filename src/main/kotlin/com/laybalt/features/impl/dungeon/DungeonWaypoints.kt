package com.laybalt.features.impl.dungeon

import com.laybalt.commands.CommandBuilder
import com.laybalt.config.PogObject
import com.laybalt.config.types.*
import com.laybalt.event.impl.*
import com.laybalt.features.Feature
import com.laybalt.init.types.ICommandProvider
import com.laybalt.ui.gui.DungeonWaypointScreen
import com.laybalt.utils.*
import com.laybalt.utils.ColorUtils.withAlpha
import com.laybalt.utils.dungeons.enums.SecretType
import com.laybalt.utils.dungeons.map.core.RoomState
import com.laybalt.utils.dungeons.map.core.UniqueRoom
import com.laybalt.utils.dungeons.map.utils.ScanUtils
import com.laybalt.utils.location.LocationUtils
import com.laybalt.utils.render.world.Render3D.renderBlock
import com.mojang.brigadier.arguments.StringArgumentType
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.Blocks
import java.awt.Color
import java.util.concurrent.*
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.fabricmc.loader.api.FabricLoader
import java.io.File
import java.util.Base64
import java.util.zip.GZIPInputStream


object DungeonWaypoints: Feature("Add a custom waypoint with /ndw add while looking at a block"), ICommandProvider {
    private val secretWaypoints by ToggleSetting("Secret Waypoints").section("Secret Waypoints")
    private val mode by DropdownSetting("Mode", 0, listOf("Fill", "Outline", "Filled Outline"))
    private val phase by ToggleSetting("See Through Walls", true)
    private val opacity by SliderSetting("Opacity", 40, 0, 100, 1).hideIf { mode.value == 1 }
    private val lineWidth by SliderSetting("Line Width", 1.5f, 1f, 10f, 0.1f).hideIf { mode.value == 0 }

    private val chestColor by ColorSetting("Chest Color", Color.MAGENTA, false).section("Colors")
    private val itemColor by ColorSetting("Item Color", Utils.favoriteColor, false)
    private val batColor by ColorSetting("Bat Color", Color.GREEN, false)
    private val essenceColor by ColorSetting("Essence Color", Color.BLACK, false)
    private val keyColor by ColorSetting("Redstone Key Color", Color.RED, false)

    private val importOdinButton by ButtonSetting("Import Odin Waypoints") { importOdin(null) }.section("Odin Import")
        .withDescription("Adds the waypoints of the packs selected in Odin (config/odin/dungeon-waypoints) to these waypoints, keeping existing ones. Same as /lbq dwp importodin")

    private val storage = PogObject("dungeonWaypoints", mutableMapOf<String, MutableSet<DungeonWaypoint>>())
    private val roomWaypoints = ConcurrentHashMap<UniqueRoom, MutableSet<DungeonWaypoint>>()
    private val doneSecrets = ConcurrentHashMap<UniqueRoom, MutableSet<BlockPos>>()
    private val bossWaypoints = ConcurrentHashMap.newKeySet<DungeonWaypoint>()

    override fun init() {
        register<DungeonEvent.RoomEvent.RotationFound> {
            val stored = storage.get()[event.room.name].orEmpty().map {
                it.copy(pos = ScanUtils.getRealCoord(it.pos, event.corner, 360 - event.rotation))
            }

            roomWaypoints[event.room] = stored.toCollection(ConcurrentHashMap.newKeySet())
        }

        register<DungeonEvent.BossEnterEvent> {
            val (name) = getRoomData() ?: return@register
            storage.get()[name]?.let(bossWaypoints::addAll)
        }

        register<DungeonEvent.SecretEvent> {
            if (! secretWaypoints.value) return@register
            if (event.type == SecretType.LEVER) return@register
            val room = ScanUtils.currentRoom ?: return@register
            val secrets = room.secretCoords[event.type] ?: return@register

            val special = setOf(SecretType.BAT, SecretType.ITEM)
            val target = if (event.type !in special) secrets.find { it == event.pos }
            else {
                val maxDistance = when (event.type) {
                    SecretType.ITEM -> 25
                    SecretType.BAT -> 144
                    else -> Int.MAX_VALUE
                }

                secrets.asSequence()
                    .map { it to it.distSqr(event.pos) }
                    .minByOrNull { it.second }
                    ?.takeIf { it.second <= maxDistance }
                    ?.first
            }

            target?.let { doneSecrets.getOrPut(room) { ConcurrentHashMap.newKeySet() }.add(it) }
        }

        register<RenderWorldEvent> {
            if (! LocationUtils.inDungeon) return@register

            if (LocationUtils.inBoss) {
                for (wp in bossWaypoints) event.ctx.renderBlock(
                    wp.pos, wp.color, outline = wp.outline,
                    fill = wp.filled, phase = wp.phase
                )
                return@register
            }

            val room = ScanUtils.currentRoom ?: return@register
            val currentWaypoints = roomWaypoints[room] ?: emptySet()

            for (wp in currentWaypoints) event.ctx.renderBlock(
                wp.pos, wp.color, outline = wp.outline,
                fill = wp.filled, phase = wp.phase
            )

            if (! secretWaypoints.value) return@register
            if (room.mainRoom.state == RoomState.GREEN) return@register

            val done = doneSecrets[room] ?: emptySet()
            for ((type, positions) in room.secretCoords) for (pos in positions) {
                if (pos in done) continue

                if (type == SecretType.REDSTONE_KEY && WorldUtils.getBlockAt(pos) != Blocks.PLAYER_HEAD) continue
                event.ctx.renderBlock(
                    pos, type.color().withAlpha((opacity.value * 2.55).toInt()),
                    mode.value.equalsOneOf(1, 2),
                    mode.value.equalsOneOf(0, 2),
                    phase = phase.value,
                    lineWidth = lineWidth.value
                )
            }
        }

        register<WorldChangeEvent> {
            roomWaypoints.clear()
            bossWaypoints.clear()
            doneSecrets.clear()
        }
    }

    override fun CommandBuilder.command() {
        setName("ndw")
        requires("Enable the $name Feature to use /ndw.") { enabled }
        runs { ChatUtils.modMessage("&bUsage: /ndw <add|edit|remove|clear>") }

        literal("add") {
            runs {
                val info = getRoomData() ?: return@runs
                val currentWaypoints = info.waypoints() ?: return@runs ChatUtils.modMessage("§cRoom rotation not scanned yet, try again in a moment.")
                val lookingAt = PlayerUtils.getSelectionBlock() ?: return@runs ChatUtils.modMessage("§cYou must be looking at a block!")
                if (currentWaypoints.any { it.pos == lookingAt }) return@runs ChatUtils.modMessage("§cA waypoint already exists here. Use /ndw edit.")

                val relativePos = ScanUtils.getRelativeCoord(lookingAt, info.corner, info.rotation)
                GuiUtils.setScreen(DungeonWaypointScreen(info.name, lookingAt, relativePos))
            }
        }

        literal("edit") {
            runs {
                val info = getRoomData() ?: return@runs
                val currentWaypoints = info.waypoints() ?: return@runs ChatUtils.modMessage("§cRoom rotation not scanned yet, try again in a moment.")
                val lookingAt = PlayerUtils.getSelectionBlock() ?: return@runs ChatUtils.modMessage("§cYou must be looking at a block!")
                val existing = currentWaypoints.find { it.pos == lookingAt } ?: return@runs ChatUtils.modMessage("§cNo waypoint found at that block.")

                val relativePos = ScanUtils.getRelativeCoord(lookingAt, info.corner, info.rotation)
                GuiUtils.setScreen(DungeonWaypointScreen(info.name, lookingAt, relativePos, existing))
            }
        }

        literal("remove") {
            runs {
                val info = getRoomData() ?: return@runs
                val currentWaypoints = info.waypoints() ?: return@runs ChatUtils.modMessage("§cRoom rotation not scanned yet, try again in a moment.")
                val lookingAt = PlayerUtils.getSelectionBlock() ?: return@runs ChatUtils.modMessage("§cYou must be looking at a block!")
                val waypoints = storage.get()

                val closest = currentWaypoints.find { it.pos == lookingAt } ?: return@runs ChatUtils.modMessage("§cNo waypoints found in this room.")

                val relativePosToRemove = ScanUtils.getRelativeCoord(closest.pos, info.corner, info.rotation)
                val roomList = waypoints.getOrDefault(info.name, mutableSetOf())
                if (roomList.removeIf { it.pos == relativePosToRemove }) {
                    waypoints[info.name] = roomList
                    currentWaypoints.removeIf { it.pos == closest.pos }
                    ChatUtils.modMessage("§aWaypoint removed.")
                }
                else ChatUtils.modMessage("§cError syncing config.")
            }
        }

        literal("clear") {
            runs {
                val info = getRoomData() ?: return@runs
                val currentWaypoints = info.waypoints() ?: return@runs ChatUtils.modMessage("§cRoom rotation not scanned yet, try again in a moment.")
                if (currentWaypoints.isEmpty()) return@runs ChatUtils.modMessage("§cNo waypoints set for this room.")
                storage.get().remove(info.name)
                currentWaypoints.clear()
                ChatUtils.modMessage("§aAll waypoints cleared for room: ${info.name}")
            }
        }
    }

    private fun getRoomData(): RoomInfo? {
        val floor = LocationUtils.dungeonFloorNumber ?: run {
            ChatUtils.modMessage("§cYou must be in a dungeon to edit waypoints!")
            return null
        }

        if (LocationUtils.inBoss) return RoomInfo("B$floor", BlockPos.ZERO, 0, null)

        val currentRoom = ScanUtils.currentRoom ?: run {
            ChatUtils.modMessage("§cYou must be in a dungeon room to edit waypoints!")
            return null
        }

        return RoomInfo(
            currentRoom.name,
            currentRoom.clayPos ?: BlockPos.ZERO,
            360 - (currentRoom.rotation ?: 0),
            currentRoom
        )
    }

    fun saveWaypoint(absPos: BlockPos, relPos: BlockPos, roomName: String, color: Color, filled: Boolean, outline: Boolean, phase: Boolean) {
        val newWaypoint = DungeonWaypoint(relPos, color, filled, outline, phase)
        val absWaypoint = newWaypoint.copy(pos = absPos)

        storage.get().compute(roomName) { _, list ->
            val set = list ?: mutableSetOf()
            val replaced = set.removeIf { it.pos == relPos }
            set.add(newWaypoint)

            if (replaced) ChatUtils.modMessage("§e$roomName: Waypoint updated at ${absPos.toShortString()}.")
            else ChatUtils.modMessage("§a$roomName: Waypoint added at ${absPos.toShortString()}.")

            set
        }

        if (LocationUtils.inBoss) {
            bossWaypoints.removeIf { it.pos == absPos }
            bossWaypoints.add(absWaypoint)
            return
        }

        for ((room, set) in roomWaypoints) {
            if (room.name != roomName) continue
            set.removeIf { it.pos == absPos }
            set.add(absWaypoint)
        }
    }

    // region Odin waypoint import

    /**
     * Odin room waypoints use the same clay-corner relative coordinates as these ones (checked for all four rotations),
     * so positions are copied as they are. Odin's size, title and type are not used (the block's own shape is drawn).
     * [source]: null = Odin's selected packs (all packs if none are selected), "all", "clipboard" or a pack name.
     */
    fun importOdin(source: String?) {
        val odinDir = File(FabricLoader.getInstance().configDir.toFile(), "odin")
        val packsDir = File(odinDir, "dungeon-waypoints")
        val packs = packsDir.listFiles { file -> file.extension == "json" }?.associateBy { it.nameWithoutExtension }.orEmpty()
        val fromClipboard = source.equals("clipboard", true)

        val inputs: List<Pair<String, String>> = when {
            fromClipboard -> listOf("clipboard" to decodeOdinString(mc.keyboardHandler.clipboard.trim()).orEmpty())
            source != null && ! source.equals("all", true) -> {
                val file = packs[source] ?: return ChatUtils.modMessage("§cNo Odin waypoint pack \"$source\" in ${packsDir.path}")
                listOf(source to file.readText())
            }

            else -> {
                val selected = if (source == null) odinSelectedPacks(odinDir).filter { it in packs } else emptyList()
                val chosen = selected.ifEmpty { packs.keys.sorted() }.mapNotNull { name -> packs[name]?.let { name to it.readText() } }
                chosen.ifEmpty {
                    val legacy = File(odinDir, "dungeon-waypoint-config.json")
                    if (legacy.isFile) listOf("dungeon-waypoint-config" to legacy.readText()) else emptyList()
                }
            }
        }
        if (inputs.isEmpty() || inputs.all { it.second.isBlank() }) {
            return ChatUtils.modMessage("§cNo Odin dungeon waypoints found (looked in ${if (fromClipboard) "the clipboard" else packsDir.path}).")
        }

        var added = 0
        var skipped = 0
        val rooms = mutableSetOf<String>()
        val data = storage.get()
        for ((name, text) in inputs) {
            val parsed = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()
            if (parsed == null) {
                ChatUtils.modMessage("§cCould not read Odin pack \"$name\".")
                continue
            }
            for ((room, list) in parsed.entrySet()) {
                if (! list.isJsonArray) continue
                for (element in list.asJsonArray) {
                    val waypoint = runCatching { odinWaypoint(element.asJsonObject) }.getOrNull() ?: continue
                    val set = data.getOrPut(room) { mutableSetOf() }
                    if (set.any { it.pos == waypoint.pos }) {
                        skipped ++
                        continue
                    }
                    set.add(waypoint)
                    rooms.add(room)
                    added ++
                }
            }
        }
        storage.save()
        refreshLoadedRooms(rooms)
        val existed = if (skipped > 0) " §7($skipped already existed)" else ""
        ChatUtils.modMessage("§aImported §b$added§a Odin waypoints in §b${rooms.size}§a rooms from ${inputs.joinToString { it.first }}$existed.")
    }

    private fun odinWaypoint(obj: JsonObject): DungeonWaypoint {
        val pos = obj.getAsJsonObject("blockPos")?.let { BlockPos(it.get("x").asInt, it.get("y").asInt, it.get("z").asInt) }
            ?: BlockPos(obj.get("x").asInt, obj.get("y").asInt, obj.get("z").asInt)
        val color = obj.get("color")?.asString?.removePrefix("#")?.let { hex ->
            Color(
                hex.substring(0, 2).toInt(16), hex.substring(2, 4).toInt(16), hex.substring(4, 6).toInt(16),
                if (hex.length >= 8) hex.substring(6, 8).toInt(16) else 255
            )
        } ?: Color.GREEN
        val filled = obj.get("filled")?.asBoolean ?: false
        val depth = obj.get("depth")?.asBoolean ?: false
        return DungeonWaypoint(pos, color, filled, ! filled, ! depth)
    }

    /** Odin's "Selected Waypoint Packs" from config/odin/odin-config.json. */
    private fun odinSelectedPacks(odinDir: File): List<String> = runCatching {
        JsonParser.parseString(File(odinDir, "odin-config.json").readText()).asJsonArray
            .map { it.asJsonObject }.first { it.get("name").asString == "Dungeon Waypoints" }
            .getAsJsonObject("settings").getAsJsonArray("Selected Waypoint Packs").map { it.asString }
    }.getOrDefault(emptyList())

    /** Odin exports packs as plain JSON or as base64 of gzip-compressed JSON. */
    private fun decodeOdinString(input: String): String? = runCatching {
        if (input.startsWith("{")) input
        else GZIPInputStream(Base64.getDecoder().decode(input).inputStream()).bufferedReader().use { it.readText() }
    }.getOrNull()

    /** Re-applies the stored waypoints to rooms that are already loaded (rotation found). */
    private fun refreshLoadedRooms(names: Set<String>) {
        for (room in roomWaypoints.keys.toList()) {
            if (room.name !in names) continue
            val corner = room.clayPos ?: continue
            val rotation = room.rotation ?: continue
            roomWaypoints[room] = storage.get()[room.name].orEmpty()
                .map { it.copy(pos = ScanUtils.getRealCoord(it.pos, corner, 360 - rotation)) }
                .toCollection(ConcurrentHashMap.newKeySet())
        }
    }

    // endregion

    private fun SecretType.color() = when (this) {
        SecretType.REDSTONE_KEY -> keyColor
        SecretType.WITHER_ESSENCE -> essenceColor
        SecretType.CHEST -> chestColor
        SecretType.ITEM -> itemColor
        SecretType.BAT -> batColor
        else -> chestColor
    }.value

    private fun RoomInfo.waypoints() = run { roomWaypoints[room ?: return@run bossWaypoints] }
    data class DungeonWaypoint(val pos: BlockPos, val color: Color, val filled: Boolean, val outline: Boolean, val phase: Boolean)
    private data class RoomInfo(val name: String, val corner: BlockPos, val rotation: Int, val room: UniqueRoom?)
}
/** `/lbq dwp importodin [all|clipboard|<pack>]`: imports Odin dungeon waypoint packs into Dungeon Waypoints. */
object DungeonWaypointsOdinImport: ICommandProvider {
    override fun CommandBuilder.command() {
        setName("lbq", "laybalt")
        literal("dwp") {
            literal("importodin") {
                description("Imports Odin dungeon waypoint packs: /lbq dwp importodin [all|clipboard|<pack>] (default: the packs selected in Odin)")
                runs { DungeonWaypoints.importOdin(null) }
                argument("source", StringArgumentType.greedyString()) {
                    suggests {
                        val dir = File(FabricLoader.getInstance().configDir.toFile(), "odin/dungeon-waypoints")
                        listOf("all", "clipboard") + dir.listFiles { f -> f.extension == "json" }?.map { it.nameWithoutExtension }.orEmpty()
                    }
                    runs { DungeonWaypoints.importOdin(StringArgumentType.getString(it, "source").trim()) }
                }
            }
        }
    }
}
