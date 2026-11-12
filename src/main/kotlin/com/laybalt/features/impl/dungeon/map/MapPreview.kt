package com.laybalt.features.impl.dungeon.map

import com.laybalt.LayBaltQOL.mc
import com.laybalt.utils.GsonUtils
import com.laybalt.utils.dungeons.DungeonPlayer
import com.laybalt.utils.dungeons.enums.DungeonClass
import com.laybalt.utils.dungeons.map.core.*
import com.laybalt.utils.dungeons.map.handlers.DungeonScanner
import com.laybalt.utils.dungeons.map.utils.MapUtils
import com.laybalt.utils.dungeons.map.utils.ScanUtils
import com.laybalt.utils.equalsOneOf
import net.minecraft.client.resources.DefaultPlayerSkin
import java.io.Reader
import java.util.*
import kotlin.math.abs

/**
 * Sample dungeon drawn by the Dungeon Map HUD element in the HUD editor while you are not in a dungeon
 * (NoammAddons 961ca630 / 73177614). The layout lives in `/assets/lbqmod/map-preview.json`.
 */
object MapPreview {
    const val CONNECTOR_SIZE = 4

    private var built: Built? = null
    private var builtCheater = false
    private val current: Built
        get() {
            val cheater = MapConfig.revealHidden
            built?.takeIf { builtCheater == cheater }?.let { return it }
            builtCheater = cheater

            val json = MapPreview::class.java.getResourceAsStream("/assets/lbqmod/map-preview.json")
                ?.reader()?.use(Reader::readText) ?: error("Could not find map preview file")

            return build(GsonUtils.decode(json)).also { built = it }
        }

    val tiles get() = current.tiles
    val rooms get() = current.rooms
    val players get() = current.players
    val predictions get() = current.predictions

    private fun build(layout: Layout): Built {
        val tiles = Array<Tile>(121) { Unknown(0, 0) }
        val rooms = mutableListOf<UniqueRoom>()
        val predictions = mutableMapOf<Int, List<RoomType>>()

        // UniqueRoom's init adds to the live crypt/secret totals; the preview must not change them
        val crypts = DungeonScanner.cryptCount
        val secrets = DungeonScanner.secretCount

        for (entry in layout.rooms) {
            val data = ScanUtils.getRoomData(entry.name) ?: RoomData.createUnknown(entry.type)
            var unique: UniqueRoom? = null

            for ((arrX, arrY) in visibleTiles(entry, layout.doors)) {
                val tile = RoomTile(worldX(arrX), worldZ(arrY), data).apply {
                    isSeparator = arrX % 2 == 1 || arrY % 2 == 1
                    state = displayState(entry.state)
                }

                unique?.addTile(arrX, arrY, tile) ?: run {
                    unique = UniqueRoom(arrX, arrY, tile).also { it.foundSecrets = entry.foundSecrets }
                }

                tile.uniqueRoom = unique
                tiles[arrY * 11 + arrX] = tile
            }

            unique?.let(rooms::add)

            val predicted = entry.predicted ?: continue
            val (arrX, arrY) = entry.tiles.singleOrNull() ?: continue
            predictions[arrY * 11 + arrX] = predicted
        }

        for (door in layout.doors) {
            tiles[door.y * 11 + door.x] = DoorTile(worldX(door.x), worldZ(door.y), door.type, tiles).apply {
                opened = door.opened
                state = displayState(door.state)
            }
        }

        DungeonScanner.cryptCount = crypts
        DungeonScanner.secretCount = secrets

        val players = layout.players.mapIndexed { i, player ->
            val name = if (i == 0) mc.user.name else player.clazz.name
            createPlayer(name, player.clazz, player.col, player.row, player.yaw, UUID(0, i.toLong()).takeIf { i != 0 })
        }

        return Built(tiles, rooms, players, predictions)
    }

    private fun createPlayer(name: String, clazz: DungeonClass, col: Float, row: Float, yaw: Float, uuid: UUID?): DungeonPlayer {
        val player = uuid?.let { DungeonPlayer(name, clazz, 50, DefaultPlayerSkin.get(it).body.texturePath()) } ?: DungeonPlayer(name, clazz, 50)
        return player.apply {
            // same origin as the translated room grid (MapUtils.renderStartX/Y), so the heads sit in the middle of their rooms
            mapX = MapUtils.renderStartX + col * (MapUtils.mapRoomSize + CONNECTOR_SIZE) + MapUtils.mapRoomSize / 2f
            mapZ = MapUtils.renderStartY + row * (MapUtils.mapRoomSize + CONNECTOR_SIZE) + MapUtils.mapRoomSize / 2f
            this.yaw = yaw
        }
    }

    private fun visibleTiles(entry: RoomEntry, doors: List<DoorEntry>): List<List<Int>> {
        if (builtCheater || ! entry.state.equalsOneOf(RoomState.UNOPENED, RoomState.UNDISCOVERED)) return entry.tiles
        return entry.tiles.filter { (arrX, arrY) -> doors.any { abs(it.x - arrX) + abs(it.y - arrY) == 1 } }
    }

    private fun displayState(state: RoomState): RoomState {
        if (! state.equalsOneOf(RoomState.UNOPENED, RoomState.UNDISCOVERED)) return state
        return if (builtCheater) RoomState.UNDISCOVERED else RoomState.UNOPENED
    }

    private fun worldX(arrX: Int) = DungeonScanner.startX + arrX * (DungeonScanner.roomSize shr 1)
    private fun worldZ(arrY: Int) = DungeonScanner.startZ + arrY * (DungeonScanner.roomSize shr 1)

    private class Built(val tiles: Array<Tile>, val rooms: Collection<UniqueRoom>, val players: List<DungeonPlayer>, val predictions: Map<Int, List<RoomType>>)
    private data class Layout(val rooms: List<RoomEntry>, val doors: List<DoorEntry>, val players: List<PlayerEntry>)
    private data class RoomEntry(val name: String, val type: RoomType, val state: RoomState, val foundSecrets: Int, val tiles: List<List<Int>>, val predicted: List<RoomType>?)
    private data class DoorEntry(val x: Int, val y: Int, val type: DoorType, val state: RoomState, val opened: Boolean)
    private data class PlayerEntry(val clazz: DungeonClass, val col: Float, val row: Float, val yaw: Float)
}
