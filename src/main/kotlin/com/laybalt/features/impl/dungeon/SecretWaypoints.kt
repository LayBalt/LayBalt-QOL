package com.laybalt.features.impl.dungeon

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.laybalt.LayBaltQOL
import com.laybalt.LayBaltQOL.CONFIG_DIR
import com.laybalt.commands.CommandBuilder
import com.laybalt.config.types.ColorSetting
import com.laybalt.config.types.DropdownSetting
import com.laybalt.config.types.SliderSetting
import com.laybalt.config.types.ToggleSetting
import com.laybalt.event.impl.*
import com.laybalt.features.Feature
import com.laybalt.init.types.ICommandProvider
import com.laybalt.utils.ChatUtils
import com.laybalt.utils.ColorUtils.withAlpha
import com.laybalt.utils.FileHandler
import com.laybalt.utils.GsonUtils
import com.laybalt.utils.PlayerUtils
import com.laybalt.utils.WorldUtils
import com.laybalt.utils.dungeons.enums.SecretType
import com.laybalt.utils.dungeons.map.core.RoomState
import com.laybalt.utils.dungeons.map.core.UniqueRoom
import com.laybalt.utils.dungeons.map.handlers.ScoreCalculation
import com.laybalt.utils.dungeons.map.utils.ScanUtils
import com.laybalt.utils.location.LocationUtils
import com.laybalt.utils.render.RenderHelper
import com.laybalt.utils.render.world.Render3D.renderBlock
import com.laybalt.utils.render.world.Render3D.renderBoxBounds
import com.laybalt.utils.render.world.Render3D.renderString
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import net.minecraft.core.BlockPos
import net.minecraft.network.protocol.game.ClientboundBlockEventPacket
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import java.awt.Color
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Per-room secret waypoints built on LayBaltQOL's own room detection (room name, rotation and corner from the dungeon scanner).
 *
 * Data sources, merged per room when its rotation is found:
 *  - the bundled `rooms-modern.json` secret coordinates (chest, item, bat, wither essence, redstone key),
 *  - the user's dataset `config/LayBaltQOL/data/secretWaypoints.json`, recorded in game with `/lsw add`.
 *
 * Behaviour modelled on Skyblocker's dungeon secret waypoints; written from a behavioural spec, no Skyblocker code or data is used.
 */
object SecretWaypoints: Feature(
    description = "Shows waypoints for every secret of the room you are in (chests, items, bats, wither essence, redstone keys, plus any entrance/superboom/stonk/lever/... waypoints you record with /lsw add). Found secrets are hidden automatically.",
    name = "Secret Waypoints"
), ICommandProvider {
    private val renderType by DropdownSetting("Render Type", 0, listOf("Waypoint (beam)", "Outlined Waypoint", "Highlight", "Outlined Highlight", "Outline")).section("Rendering")
        .withDescription("Waypoint: filled box with a beam to the sky. Outlined Waypoint: the same with an outline. Highlight: filled box only. Outlined Highlight: filled box with outline. Outline: outline only.")
    private val phase by ToggleSetting("See Through Walls", true)
        .withDescription("Renders the waypoints, beams and labels through blocks.")
    private val opacity by SliderSetting("Opacity", 50, 0, 100, 1, "%")
        .withDescription("Opacity of the filled boxes and beams.").hideIf { renderType.value == 4 }
    private val lineWidth by SliderSetting("Line Width", 2.5f, 1f, 10f, 0.1f)
        .withDescription("Width of the outline.").showIf { renderType.value.let { it == 1 || it == 3 || it == 4 } }
    private val adaptiveBoxSize by ToggleSetting("Adaptive Box Size", false)
        .withDescription("Wither essence and redstone key waypoints use the real block shape (the skull) instead of a full block.")
    private val showNames by ToggleSetting("Show Names", true).section("Labels")
        .withDescription("Shows the waypoint name (for example \"1 - Chest\") above each waypoint.")
    private val showDistance by ToggleSetting("Show Distance", true)
        .withDescription("Shows the distance to each waypoint in blocks under its name.")
    private val textScale by SliderSetting("Text Scale", 1f, 0.5f, 3f, 0.1f)
        .withDescription("Size of the waypoint labels. Labels further than 10 blocks away are scaled up so they stay readable.")
        .showIf { showNames.value || showDistance.value }

    private val hideWhenAllFound by ToggleSetting("Hide When All Found", true).section("Found Detection")
        .withDescription("Hides every waypoint of the room once the action bar shows all of its secrets found (for example 5/5 Secrets).")
    private val chestConfirm by ToggleSetting("Wait For Chest Open", true)
        .withDescription("Chest secrets are only marked found when the chest actually opens (lid animation, \"already searched\" message or a mimic). Turn off to hide them on the first click, even if the chest was locked.")
    private val foundMessage by ToggleSetting("Found Message", false)
        .withDescription("Prints a chat message every time a secret waypoint is marked found.")

    private val entranceToggle by ToggleSetting("Entrance Waypoints", true).section("Categories")
        .withDescription("Shows waypoints marking the way into a secret.")
    private val entranceColor by ColorSetting("Entrance Color", Category.ENTRANCE.defaultColor, false)
        .withDescription("Color of entrance waypoints.").showIf { entranceToggle.value }
    private val superboomToggle by ToggleSetting("Superboom Waypoints", true)
        .withDescription("Shows walls that need a Superboom TNT.")
    private val superboomColor by ColorSetting("Superboom Color", Category.SUPERBOOM.defaultColor, false)
        .withDescription("Color of superboom waypoints.").showIf { superboomToggle.value }
    private val chestToggle by ToggleSetting("Chest Waypoints", true)
        .withDescription("Shows secret chests.")
    private val chestColor by ColorSetting("Chest Color", Category.CHEST.defaultColor, false)
        .withDescription("Color of chest waypoints.").showIf { chestToggle.value }
    private val itemToggle by ToggleSetting("Item Waypoints", true)
        .withDescription("Shows secret items lying on the ground.")
    private val itemColor by ColorSetting("Item Color", Category.ITEM.defaultColor, false)
        .withDescription("Color of item waypoints.").showIf { itemToggle.value }
    private val batToggle by ToggleSetting("Bat Waypoints", true)
        .withDescription("Shows where secret bats spawn.")
    private val batColor by ColorSetting("Bat Color", Category.BAT.defaultColor, false)
        .withDescription("Color of bat waypoints.").showIf { batToggle.value }
    private val witherToggle by ToggleSetting("Wither Essence Waypoints", true)
        .withDescription("Shows wither essence skulls.")
    private val witherColor by ColorSetting("Wither Essence Color", Category.WITHER.defaultColor, false)
        .withDescription("Color of wither essence waypoints.").showIf { witherToggle.value }
    private val keyToggle by ToggleSetting("Redstone Key Waypoints", true)
        .withDescription("Shows redstone keys and where to place them.")
    private val keyColor by ColorSetting("Redstone Key Color", Category.KEY.defaultColor, false)
        .withDescription("Color of redstone key waypoints.").showIf { keyToggle.value }
    private val leverToggle by ToggleSetting("Lever Waypoints", true)
        .withDescription("Shows levers that open secrets.")
    private val leverColor by ColorSetting("Lever Color", Category.LEVER.defaultColor, false)
        .withDescription("Color of lever waypoints.").showIf { leverToggle.value }
    private val fairySoulToggle by ToggleSetting("Fairy Soul Waypoints", true)
        .withDescription("Shows fairy souls inside dungeon rooms.")
    private val fairySoulColor by ColorSetting("Fairy Soul Color", Category.FAIRYSOUL.defaultColor, false)
        .withDescription("Color of fairy soul waypoints.").showIf { fairySoulToggle.value }
    private val stonkToggle by ToggleSetting("Stonk Waypoints", true)
        .withDescription("Shows blocks to mine through with a Stonk.")
    private val stonkColor by ColorSetting("Stonk Color", Category.STONK.defaultColor, false)
        .withDescription("Color of stonk waypoints.").showIf { stonkToggle.value }
    private val aotvToggle by ToggleSetting("AOTV Waypoints", true)
        .withDescription("Shows spots to teleport to with an Aspect of the Void.")
    private val aotvColor by ColorSetting("AOTV Color", Category.AOTV.defaultColor, false)
        .withDescription("Color of AOTV waypoints.").showIf { aotvToggle.value }
    private val pearlToggle by ToggleSetting("Pearl Waypoints", true)
        .withDescription("Shows spots to throw an ender pearl from or to.")
    private val pearlColor by ColorSetting("Pearl Color", Category.PEARL.defaultColor, false)
        .withDescription("Color of pearl waypoints.").showIf { pearlToggle.value }
    private val princeToggle by ToggleSetting("Prince Waypoints", true)
        .withDescription("Shows where the Prince spawns. Hidden once the Prince has been killed.")
    private val princeColor by ColorSetting("Prince Color", Category.PRINCE.defaultColor, false)
        .withDescription("Color of prince waypoints.").showIf { princeToggle.value }
    private val otherToggle by ToggleSetting("Other Waypoints", true)
        .withDescription("Shows waypoints with the 'default' category.")
    private val otherColor by ColorSetting("Other Color", Category.DEFAULT.defaultColor, false)
        .withDescription("Color of waypoints with the 'default' category.").showIf { otherToggle.value }

    enum class Category(val key: String, val label: String, val defaultColor: Color) {
        ENTRANCE("entrance", "Entrance", Color(0, 255, 0)),
        SUPERBOOM("superboom", "Superboom", Color(255, 0, 0)),
        CHEST("chest", "Chest", Color(2, 213, 250)),
        ITEM("item", "Item", Color(2, 64, 250)),
        BAT("bat", "Bat", Color(142, 66, 0)),
        WITHER("wither", "Wither Essence", Color(30, 30, 30)),
        KEY("key", "Redstone Key", Color(200, 30, 30)),
        LEVER("lever", "Lever", Color(250, 217, 2)),
        FAIRYSOUL("fairysoul", "Fairy Soul", Color(255, 85, 255)),
        STONK("stonk", "Stonk", Color(146, 52, 235)),
        AOTV("aotv", "AOTV", Color(252, 98, 3)),
        PEARL("pearl", "Pearl", Color(57, 117, 125)),
        PRINCE("prince", "Prince", Color(133, 21, 13)),
        DEFAULT("default", "Waypoint", Color(190, 255, 252));

        /** Chest, wither essence and redstone key secrets are found by interacting with their block. */
        val needsInteraction get() = this == CHEST || this == WITHER || this == KEY

        companion object {
            private val aliases = mapOf(
                "redstonekey" to KEY, "redstone_key" to KEY, "wither_essence" to WITHER, "essence" to WITHER,
                "fairy_soul" to FAIRYSOUL, "soul" to FAIRYSOUL, "other" to DEFAULT
            )

            fun parse(raw: String?): Category? {
                val key = raw?.trim()?.lowercase() ?: return null
                return entries.find { it.key == key } ?: aliases[key]
            }
        }
    }

    /** One entry of the user dataset, in room-relative coordinates (same frame as rooms-modern.json). */
    data class Entry(val index: Int, val category: String, val name: String?, val x: Int, val y: Int, val z: Int, val extra: JsonObject? = null) {
        val relPos get() = BlockPos(x, y, z)
    }

    class SecretWaypoint(val index: Int, val category: Category, val name: String, val relPos: BlockPos, val pos: BlockPos, val custom: Boolean) {
        @Volatile var found = false
    }

    private val dataFile = File("config/$CONFIG_DIR/data/secretWaypoints.json")
    private val fileHandler by lazy { FileHandler(dataFile) }
    private val userData = ConcurrentHashMap<String, CopyOnWriteArrayList<Entry>>()

    /** Parts of the user file that could not be parsed; written back unchanged on save so hand edits are never lost. */
    private val unparsedEntries = ConcurrentHashMap<String, JsonArray>()
    private val unparsedValues = ConcurrentHashMap<String, JsonElement>()
    private var unreadableFile = false

    private val roomWaypoints = ConcurrentHashMap<UniqueRoom, CopyOnWriteArrayList<SecretWaypoint>>()
    private val pendingChestChecks = ConcurrentHashMap<BlockPos, Int>()
    private var lastChestClick: BlockPos? = null
    private var lastChestClickTime = 0L
    private var roomEnteredAt = 0L

    private val indexRegex = Regex("^(\\d+)")
    private val actionBarSecretsRegex = Regex("(\\d{1,2})/(\\d{1,2}) Secrets")
    private const val CHEST_ALREADY_SEARCHED = "This chest has already been searched!"

    override fun init() {
        loadUserData()

        register<DungeonEvent.RoomEvent.RotationFound> {
            buildRoom(event.room, event.corner, event.rotation)
        }

        register<DungeonEvent.RoomEvent.onEnter> {
            roomEnteredAt = System.currentTimeMillis()
        }

        register<DungeonEvent.RoomEvent.onStateChange> {
            if (event.newState == RoomState.GREEN) markAll(event.room, true)
        }

        register<DungeonEvent.SecretEvent> {
            val pos = event.pos
            when (event.type) {
                SecretType.CHEST -> onChestClicked(pos)
                SecretType.WITHER_ESSENCE -> markInteraction(pos, groupIndex = true)
                SecretType.REDSTONE_KEY -> markInteraction(pos, groupIndex = false)
                SecretType.ITEM -> markNearest(pos, Category.ITEM)
                SecretType.BAT -> markNearest(pos, Category.BAT)
                SecretType.LEVER -> markLever(pos)
            }
        }

        register<PacketEvent.Sent> {
            val packet = event.packet as? ServerboundUseItemOnPacket ?: return@register
            if (! LocationUtils.inDungeon || LocationUtils.inBoss) return@register
            val pos = packet.hitResult.blockPos
            when (WorldUtils.getBlockAt(pos)) {
                Blocks.CHEST, Blocks.TRAPPED_CHEST -> onChestClicked(pos)
                Blocks.REDSTONE_BLOCK -> markInteraction(pos.above(), groupIndex = false)
                Blocks.PLAYER_HEAD, Blocks.PLAYER_WALL_HEAD -> markInteraction(pos, groupIndex = true)
                Blocks.LEVER -> markLever(pos)
                else -> {}
            }
        }

        register<MainThreadPacketReceivedEvent.Post> {
            val packet = event.packet as? ClientboundBlockEventPacket ?: return@register
            if (! LocationUtils.inDungeon || LocationUtils.inBoss) return@register
            if (packet.b0 != 1 || packet.b1 <= 0) return@register
            if (packet.block != Blocks.CHEST && packet.block != Blocks.TRAPPED_CHEST) return@register
            markInteraction(packet.pos, groupIndex = true)
        }

        register<ChatMessageEvent> {
            if (event.unformattedText != CHEST_ALREADY_SEARCHED) return@register
            val pos = lastChestClick ?: return@register
            if (System.currentTimeMillis() - lastChestClickTime > 1000) return@register
            markInteraction(pos, groupIndex = true)
        }

        register<ActionBarMessageEvent> {
            if (! hideWhenAllFound.value || ! LocationUtils.inDungeon || LocationUtils.inBoss) return@register
            val match = actionBarSecretsRegex.find(event.unformattedText) ?: return@register
            val found = match.groupValues[1].toInt()
            val total = match.groupValues[2].toInt()
            if (total <= 0 || found < total) return@register
            // the action bar lags behind when walking into another room, so only trust it after a short while in the room
            if (System.currentTimeMillis() - roomEnteredAt < 2500) return@register
            val room = ScanUtils.currentRoom ?: return@register
            if (room.data.secrets != total) return@register
            markAll(room, true)
        }

        register<TickEvent.End> {
            if (pendingChestChecks.isEmpty()) return@register
            val iterator = pendingChestChecks.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.value > 1) {
                    entry.setValue(entry.value - 1)
                    continue
                }
                iterator.remove()
                // the chest turned into air: it was a mimic (or got broken), the secret counts as done
                if (WorldUtils.getBlockAt(entry.key) == Blocks.AIR) markInteraction(entry.key, groupIndex = true)
            }
        }

        register<RenderWorldEvent> {
            if (! LocationUtils.inDungeon || LocationUtils.inBoss) return@register
            val room = ScanUtils.currentRoom ?: return@register
            if (room.mainRoom.state == RoomState.GREEN) return@register
            val waypoints = waypointsFor(room) ?: return@register
            if (waypoints.isEmpty()) return@register

            val type = renderType.value
            val fill = type != 4
            val outline = type == 1 || type == 3 || type == 4
            val beam = type == 0 || type == 1
            val alpha = (opacity.value * 2.55).roundToInt()
            val eye = event.ctx.camera.pos

            for (wp in waypoints) {
                if (! shouldRender(wp)) continue
                val color = wp.category.color()
                val fillColor = color.withAlpha(alpha)
                val pos = wp.pos

                if (adaptiveBoxSize.value && (wp.category == Category.WITHER || wp.category == Category.KEY)) {
                    event.ctx.renderBlock(pos, color, fillColor, outline, fill, phase.value, lineWidth.value)
                }
                else event.ctx.renderBoxBounds(
                    pos.x.toDouble(), pos.y.toDouble(), pos.z.toDouble(),
                    pos.x + 1.0, pos.y + 1.0, pos.z + 1.0,
                    color, fillColor, outline, fill, phase.value, lineWidth.value
                )

                if (beam) event.ctx.renderBoxBounds(
                    pos.x + 0.375, pos.y + 1.0, pos.z + 0.375,
                    pos.x + 0.625, BEAM_TOP, pos.z + 0.625,
                    color, color.withAlpha(alpha / 2), outline = false, fill = true, phase = phase.value
                )

                if (showNames.value || showDistance.value) {
                    val center = Vec3.atCenterOf(pos)
                    val dist = center.distanceTo(eye)
                    val text = buildString {
                        if (showNames.value) append(wp.name)
                        if (showDistance.value) {
                            if (isNotEmpty()) append('\n')
                            append("&e${dist.roundToInt()}m")
                        }
                    }
                    val scale = max(dist / 10.0, 1.0) * textScale.value
                    event.ctx.renderString(text, center.add(0.0, 1.0, 0.0), color, scale, phase.value)
                }
            }
        }

        register<WorldChangeEvent> {
            roomWaypoints.clear()
            pendingChestChecks.clear()
            lastChestClick = null
        }
    }

    private const val BEAM_TOP = 300.0

    private fun shouldRender(wp: SecretWaypoint): Boolean {
        if (wp.found) return false
        if (! wp.category.enabled()) return false
        if (wp.category == Category.PRINCE && ScoreCalculation.princeKilled) return false
        // bundled redstone key positions are where the key lies: once it is picked up the skull is gone
        if (wp.category == Category.KEY && ! wp.custom) {
            val block = WorldUtils.getBlockAt(wp.pos)
            if (block != Blocks.PLAYER_HEAD && block != Blocks.PLAYER_WALL_HEAD) return false
        }
        return true
    }

    private fun Category.enabled() = when (this) {
        Category.ENTRANCE -> entranceToggle
        Category.SUPERBOOM -> superboomToggle
        Category.CHEST -> chestToggle
        Category.ITEM -> itemToggle
        Category.BAT -> batToggle
        Category.WITHER -> witherToggle
        Category.KEY -> keyToggle
        Category.LEVER -> leverToggle
        Category.FAIRYSOUL -> fairySoulToggle
        Category.STONK -> stonkToggle
        Category.AOTV -> aotvToggle
        Category.PEARL -> pearlToggle
        Category.PRINCE -> princeToggle
        Category.DEFAULT -> otherToggle
    }.value

    private fun Category.color(): Color = when (this) {
        Category.ENTRANCE -> entranceColor
        Category.SUPERBOOM -> superboomColor
        Category.CHEST -> chestColor
        Category.ITEM -> itemColor
        Category.BAT -> batColor
        Category.WITHER -> witherColor
        Category.KEY -> keyColor
        Category.LEVER -> leverColor
        Category.FAIRYSOUL -> fairySoulColor
        Category.STONK -> stonkColor
        Category.AOTV -> aotvColor
        Category.PEARL -> pearlColor
        Category.PRINCE -> princeColor
        Category.DEFAULT -> otherColor
    }.value

    // ---------------------------------------------------------------- building

    private fun waypointsFor(room: UniqueRoom): List<SecretWaypoint>? {
        roomWaypoints[room]?.let { return it }
        val corner = room.clayPos ?: return null
        val rotation = room.rotation ?: return null
        return buildRoom(room, corner, rotation)
    }

    private fun buildRoom(room: UniqueRoom, corner: BlockPos, rotation: Int): List<SecretWaypoint> {
        val old = roomWaypoints[room]
        val list = CopyOnWriteArrayList<SecretWaypoint>()
        val custom = userData[room.name].orEmpty()
        val customPositions = custom.mapTo(HashSet()) { it.relPos }

        fun add(index: Int, category: Category, name: String, rel: BlockPos, isCustom: Boolean) {
            val abs = ScanUtils.getRealCoord(rel, corner, 360 - rotation)
            list.add(SecretWaypoint(index, category, name, rel, abs, isCustom).also { wp ->
                wp.found = old?.any { it.relPos == rel && it.category == category && it.found } == true
            })
        }

        val coords = room.data.secretCoords
        fun addBundled(positions: List<BlockPos>, category: Category) = positions.forEach {
            // a user entry at the same block replaces the bundled one (it can carry an index and a name)
            if (it !in customPositions) add(0, category, category.label, it, false)
        }
        addBundled(coords.chest, Category.CHEST)
        addBundled(coords.item, Category.ITEM)
        addBundled(coords.bat, Category.BAT)
        addBundled(coords.wither, Category.WITHER)
        addBundled(coords.redstoneKey, Category.KEY)

        for (entry in custom) {
            val category = Category.parse(entry.category) ?: Category.DEFAULT
            add(entry.index, category, displayName(entry.index, category, entry.name), entry.relPos, true)
        }

        roomWaypoints[room] = list
        return list
    }

    private fun displayName(index: Int, category: Category, name: String?) = when {
        ! name.isNullOrBlank() -> name
        index > 0 -> "$index - ${category.label}"
        else -> category.label
    }

    private fun rebuildRooms(roomName: String) {
        for (room in roomWaypoints.keys.toList()) {
            if (room.name != roomName) continue
            val corner = room.clayPos ?: continue
            val rotation = room.rotation ?: continue
            buildRoom(room, corner, rotation)
        }
    }

    // ---------------------------------------------------------------- found detection

    private fun roomAt(pos: BlockPos): UniqueRoom? = ScanUtils.getRoomFromPos(Vec3.atCenterOf(pos)) ?: ScanUtils.currentRoom

    private fun onChestClicked(pos: BlockPos) {
        lastChestClick = pos
        lastChestClickTime = System.currentTimeMillis()
        pendingChestChecks[pos.immutable()] = 5
        if (! chestConfirm.value) markInteraction(pos, groupIndex = true)
    }

    /** Marks the interaction waypoint (chest / wither essence / redstone key) at [pos] as found. */
    private fun markInteraction(pos: BlockPos, groupIndex: Boolean) {
        val room = roomAt(pos) ?: return
        val waypoints = waypointsFor(room) ?: return
        val wp = waypoints.find { it.pos == pos && it.category.needsInteraction && ! it.found }
            ?: waypoints.find { it.pos == pos && ! it.found }
            ?: return
        // redstone keys only hide themselves: the pick-up and the place spot share an index
        if (groupIndex && wp.category != Category.KEY) markIndex(room, wp) else setFound(wp)
    }

    private fun markNearest(pos: BlockPos, category: Category) {
        val room = roomAt(pos) ?: return
        val waypoints = waypointsFor(room) ?: return
        val wp = waypoints.asSequence()
            .filter { it.category == category && ! it.found }
            .map { it to it.pos.distSqr(pos) }
            .filter { it.second <= 16.0 * 16.0 }
            .minByOrNull { it.second }?.first ?: return
        markIndex(room, wp)
    }

    private fun markLever(pos: BlockPos) {
        val room = roomAt(pos) ?: return
        waypointsFor(room)?.forEach { if (it.category == Category.LEVER && it.pos == pos) setFound(it) }
    }

    /** Marks every waypoint that shares [wp]'s secret index as found (index 0 means "no group": only [wp]). */
    private fun markIndex(room: UniqueRoom, wp: SecretWaypoint) {
        if (wp.index <= 0) return setFound(wp)
        waypointsFor(room)?.forEach { if (it.index == wp.index) setFound(it) }
    }

    private fun setFound(wp: SecretWaypoint) {
        if (wp.found) return
        wp.found = true
        if (foundMessage.value) ChatUtils.modMessage("&7Secret waypoint found: &f${wp.name}")
    }

    private fun markAll(room: UniqueRoom, found: Boolean) {
        roomWaypoints[room]?.forEach { it.found = found }
    }

    // ---------------------------------------------------------------- user dataset

    private val knownEntryKeys = setOf("index", "category", "name", "x", "y", "z")
    private var lastSkipped = 0

    /** Loads the user dataset. Returns the number of entries loaded; [lastSkipped] holds the number of unparsable ones. */
    private fun loadUserData(): Int {
        userData.clear()
        unparsedEntries.clear()
        unparsedValues.clear()
        unreadableFile = false
        lastSkipped = 0
        val raw = runCatching { fileHandler.read() }.getOrNull()?.takeUnless { it.isBlank() } ?: return 0
        val root = runCatching { JsonParser.parseString(raw) }.getOrNull() as? JsonObject ?: run {
            unreadableFile = true
            LayBaltQOL.logger.warn("secretWaypoints.json is not a JSON object; it is ignored and will be backed up before the next save")
            return 0
        }

        var count = 0
        for ((roomName, value) in root.entrySet()) {
            val array = value as? JsonArray
            if (array == null) {
                unparsedValues[roomName] = value
                lastSkipped ++
                continue
            }
            val list = CopyOnWriteArrayList<Entry>()
            val skipped = JsonArray()
            for (element in array) {
                val entry = parseEntry(element)
                if (entry != null) {
                    list.add(entry)
                    count ++
                }
                else skipped.add(element)
            }
            if (list.isNotEmpty()) userData[roomName] = list
            if (! skipped.isEmpty) {
                unparsedEntries[roomName] = skipped
                lastSkipped += skipped.size()
            }
        }
        if (lastSkipped > 0) LayBaltQOL.logger.warn("secretWaypoints.json: $lastSkipped entr(y/ies) could not be parsed; they are kept in the file but not shown")
        return count
    }

    private fun parseEntry(element: JsonElement): Entry? = runCatching {
        val obj = element as? JsonObject ?: return null
        val x = obj.get("x")?.asInt ?: return null
        val y = obj.get("y")?.asInt ?: return null
        val z = obj.get("z")?.asInt ?: return null
        val name = obj.get("name")?.takeIf { it.isJsonPrimitive }?.asString
        val index = obj.get("index")?.asInt ?: name?.let { indexRegex.find(it)?.value?.toIntOrNull() } ?: 0
        val category = obj.get("category")?.asString ?: Category.DEFAULT.key
        val extra = JsonObject().apply { obj.entrySet().filter { it.key !in knownEntryKeys }.forEach { add(it.key, it.value) } }
        Entry(index, category, name, x, y, z, extra.takeUnless { it.isEmpty })
    }.getOrNull()

    private fun saveUserData() {
        if (unreadableFile) {
            unreadableFile = false
            val backup = File(dataFile.parentFile, "${dataFile.name}.invalid")
            runCatching { dataFile.copyTo(backup, overwrite = true) }.onSuccess {
                ChatUtils.modMessage("&esecretWaypoints.json was not valid JSON; the old file was saved as ${backup.name}.")
            }
        }
        val root = JsonObject()
        for (roomName in (userData.keys + unparsedEntries.keys).sorted()) {
            val list = userData[roomName].orEmpty()
            val skipped = unparsedEntries[roomName]
            if (list.isEmpty() && (skipped == null || skipped.isEmpty)) continue
            root.add(roomName, JsonArray().apply {
                for (entry in list) add(JsonObject().apply {
                    entry.extra?.entrySet()?.forEach { add(it.key, it.value) }
                    addProperty("index", entry.index)
                    addProperty("category", entry.category)
                    entry.name?.let { addProperty("name", it) }
                    addProperty("x", entry.x)
                    addProperty("y", entry.y)
                    addProperty("z", entry.z)
                })
                // entries that could not be parsed are written back untouched
                skipped?.forEach { add(it) }
            })
        }
        for ((key, value) in unparsedValues) root.add(if (root.has(key)) "$key (unparsed)" else key, value)
        runCatching { fileHandler.write(GsonUtils.gson.toJson(root)) }.onFailure {
            ChatUtils.modMessage("&cCould not save secretWaypoints.json: ${it.message}")
        }
    }

    // ---------------------------------------------------------------- commands

    private data class RoomInfo(val room: UniqueRoom, val corner: BlockPos, val rotation: Int) {
        fun relative(pos: BlockPos): BlockPos = ScanUtils.getRelativeCoord(pos, corner, 360 - rotation)
    }

    private fun currentRoomInfo(): RoomInfo? {
        if (! LocationUtils.inDungeon || LocationUtils.inBoss) {
            ChatUtils.modMessage("&cYou must be in a dungeon room (not the boss) to use this.")
            return null
        }
        val room = ScanUtils.currentRoom ?: run {
            ChatUtils.modMessage("&cYou are not inside a scanned dungeon room.")
            return null
        }
        val corner = room.clayPos
        val rotation = room.rotation
        if (corner == null || rotation == null) {
            ChatUtils.modMessage("&cThe rotation of ${room.name} has not been found yet, try again in a moment.")
            return null
        }
        return RoomInfo(room, corner, rotation)
    }

    /** The block the player is looking at, up to 20 blocks away. */
    private fun targetBlock(): BlockPos? {
        val hit = runCatching { player.pick(20.0, RenderHelper.partialTicks, false) }.getOrNull()
        if (hit is BlockHitResult && hit.type == HitResult.Type.BLOCK) return hit.blockPos
        return PlayerUtils.getSelectionBlock()
    }

    private fun addWaypoint(categoryRaw: String, name: String?) {
        val category = Category.parse(categoryRaw) ?: return ChatUtils.modMessage(
            "&cUnknown category '$categoryRaw'. Use one of: ${Category.entries.joinToString(", ") { it.key }}"
        )
        val info = currentRoomInfo() ?: return
        val target = targetBlock() ?: return ChatUtils.modMessage("&cLook at a block first.")
        val rel = info.relative(target)
        val cleanName = name?.trim()?.takeIf { it.isNotEmpty() }
        val index = cleanName?.let { indexRegex.find(it)?.value?.toIntOrNull() } ?: 0
        val entry = Entry(index, category.key, cleanName, rel.x, rel.y, rel.z)

        val list = userData.getOrPut(info.room.name) { CopyOnWriteArrayList() }
        val replaced = list.removeIf { it.relPos == rel }
        list.add(entry)
        saveUserData()
        rebuildRooms(info.room.name)

        val label = displayName(index, category, cleanName)
        ChatUtils.modMessage(
            "&a${if (replaced) "Updated" else "Added"} &f$label &a(${category.key}) in &f${info.room.name}&a at relative &f${rel.x} ${rel.y} ${rel.z}"
        )
    }

    private fun removeWaypoint() {
        val info = currentRoomInfo() ?: return
        val target = targetBlock() ?: return ChatUtils.modMessage("&cLook at a block first.")
        val rel = info.relative(target)
        val list = userData[info.room.name]
        if (list == null || ! list.removeIf { it.relPos == rel }) {
            val bundled = roomWaypoints[info.room]?.any { it.pos == target && ! it.custom } == true
            return ChatUtils.modMessage(
                if (bundled) "&cThat waypoint comes from the bundled room data and cannot be removed. Turn off its category or record a replacement with /lsw add."
                else "&cNo recorded waypoint at that block."
            )
        }
        if (list.isEmpty()) userData.remove(info.room.name)
        saveUserData()
        rebuildRooms(info.room.name)
        ChatUtils.modMessage("&aRemoved the recorded waypoint at relative &f${rel.x} ${rel.y} ${rel.z}")
    }

    private fun setIndexState(index: Int?, found: Boolean) {
        val info = currentRoomInfo() ?: return
        val waypoints = waypointsFor(info.room) ?: return
        if (index == null) {
            waypoints.forEach { it.found = found }
            return ChatUtils.modMessage("&aMarked every waypoint in ${info.room.name} as ${if (found) "found" else "missing"}.")
        }
        val matching = waypoints.filter { it.index == index }
        if (matching.isEmpty()) return ChatUtils.modMessage("&cNo waypoint with secret index $index in ${info.room.name}.")
        matching.forEach { it.found = found }
        ChatUtils.modMessage("&aMarked secret #$index (${matching.size} waypoint(s)) as ${if (found) "found" else "missing"}.")
    }

    private fun indexSuggestions(): List<String> {
        val room = ScanUtils.currentRoom ?: return emptyList()
        val fromData = (1 .. room.data.secrets.coerceAtLeast(0)).map { it.toString() }
        val fromWaypoints = roomWaypoints[room].orEmpty().map { it.index }.filter { it > 0 }.map { it.toString() }
        return (fromData + fromWaypoints).distinct()
    }

    override fun CommandBuilder.command() {
        setName("lsw", "secretwaypoints")
        description("Secret Waypoints: record and manage dungeon secret waypoints.")
        requires("Enable the Secret Waypoints feature to use this command.") { enabled }
        runs {
            ChatUtils.modMessage(
                "&bSecret Waypoints commands:\n" +
                    "&e/lsw add <category> [name] &7- record the block you look at (a leading number in the name is the secret index, e.g. \"2 - Stonk\")\n" +
                    "&e/lsw remove &7- delete the recorded waypoint at the block you look at\n" +
                    "&e/lsw found <index> &7/ &e/lsw missing <index|all> &7- mark a secret as found or missing\n" +
                    "&e/lsw list &7- list the waypoints of this room\n" +
                    "&e/lsw relpos &7/ &e/lsw reltarget &7- print relative coordinates\n" +
                    "&e/lsw reload &7- reload config/$CONFIG_DIR/data/secretWaypoints.json\n" +
                    "&7Categories: ${Category.entries.joinToString(", ") { it.key }}"
            )
        }

        literal("add") {
            description("Record a secret waypoint at the block you look at, in room-relative coordinates.")
            argument("category", StringArgumentType.word()) {
                suggests { Category.entries.map { it.key } }
                runs { ctx -> addWaypoint(StringArgumentType.getString(ctx, "category"), null) }
                argument("name", StringArgumentType.greedyString()) {
                    runs { ctx -> addWaypoint(StringArgumentType.getString(ctx, "category"), StringArgumentType.getString(ctx, "name")) }
                }
            }
        }

        literal("remove") {
            description("Remove the recorded secret waypoint at the block you look at.")
            runs { removeWaypoint() }
        }

        literal("found") {
            description("Mark every waypoint of a secret index as found.")
            argument("index", IntegerArgumentType.integer(0)) {
                suggests { indexSuggestions() }
                runs { ctx -> setIndexState(IntegerArgumentType.getInteger(ctx, "index"), true) }
            }
        }

        literal("missing") {
            description("Mark every waypoint of a secret index (or all) as missing again.")
            literal("all") { runs { setIndexState(null, false) } }
            argument("index", IntegerArgumentType.integer(0)) {
                suggests { indexSuggestions() }
                runs { ctx -> setIndexState(IntegerArgumentType.getInteger(ctx, "index"), false) }
            }
        }

        literal("list") {
            description("List the secret waypoints of the current room.")
            runs {
                val info = currentRoomInfo() ?: return@runs
                val waypoints = waypointsFor(info.room).orEmpty()
                if (waypoints.isEmpty()) return@runs ChatUtils.modMessage("&7No secret waypoints for ${info.room.name}.")
                ChatUtils.modMessage("&b${info.room.name} &7(rotation ${info.rotation}, ${waypoints.size} waypoints):")
                waypoints.sortedWith(compareBy({ it.index }, { it.category.ordinal })).forEach {
                    val state = if (it.found) "&a[found]" else "&c[missing]"
                    val source = if (it.custom) "&drecorded" else "&8bundled"
                    ChatUtils.modMessage("$state &f${it.name} &7(${it.category.key}, #${it.index}) &7at ${it.relPos.x} ${it.relPos.y} ${it.relPos.z} $source")
                }
            }
        }

        literal("relpos") {
            description("Print your position relative to the current room's corner.")
            runs {
                val info = currentRoomInfo() ?: return@runs
                val rel = info.relative(player.blockPosition())
                ChatUtils.modMessage("&bRoom: &f${info.room.name}&b, rotation: &f${info.rotation}&b, relative: &f${rel.x} ${rel.y} ${rel.z}")
            }
        }

        literal("reltarget") {
            description("Print the looked-at block relative to the current room's corner.")
            runs {
                val info = currentRoomInfo() ?: return@runs
                val target = targetBlock() ?: return@runs ChatUtils.modMessage("&cLook at a block first.")
                val rel = info.relative(target)
                ChatUtils.modMessage("&bRoom: &f${info.room.name}&b, rotation: &f${info.rotation}&b, relative: &f${rel.x} ${rel.y} ${rel.z}")
            }
        }

        literal("reload") {
            description("Reload the user secret waypoint file.")
            runs {
                val count = loadUserData()
                for (room in roomWaypoints.keys.toList()) {
                    val corner = room.clayPos ?: continue
                    val rotation = room.rotation ?: continue
                    buildRoom(room, corner, rotation)
                }
                ChatUtils.modMessage("&aLoaded $count recorded secret waypoint(s) from secretWaypoints.json.")
                if (lastSkipped > 0) ChatUtils.modMessage("&e$lastSkipped entr(y/ies) in secretWaypoints.json could not be parsed (missing or non-numeric x/y/z?). They are kept in the file but not shown.")
            }
        }
    }
}
