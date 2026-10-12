package com.laybalt.features.impl.dungeon

import com.laybalt.config.types.*
import com.laybalt.event.impl.*
import com.laybalt.features.Feature
import com.laybalt.features.impl.dungeon.map.DungeonMap
import com.laybalt.features.impl.dungeon.map.MapConfig
import com.laybalt.utils.ColorUtils
import com.laybalt.utils.ColorUtils.withAlpha
import com.laybalt.utils.dungeons.DungeonListener
import com.laybalt.utils.dungeons.map.core.*
import com.laybalt.utils.dungeons.map.handlers.DungeonScanner
import com.laybalt.utils.dungeons.map.handlers.DungeonTree
import com.laybalt.utils.dungeons.map.utils.ScanUtils
import com.laybalt.utils.equalsOneOf
import com.laybalt.utils.location.LocationUtils
import com.laybalt.utils.render.RenderHelper.width
import com.laybalt.utils.render.world.Render3D.renderBoxBounds
import com.laybalt.utils.render.world.Render3D.renderString
import net.minecraft.ChatFormatting
import java.awt.Color

/**
 * Highlights dungeon doors during the clear (NoammAddons Door ESP).
 *
 * - Blood rush doors: unopened wither and blood doors, coloured by whether the team holds a key.
 *   This replaces the old "Box Wither Doors" option of the Dungeon Map (moved by ConfigMigratorV1ToV2).
 * - Normal doors: the doors of the room you are in, optionally with the name and secrets of the room behind them.
 *
 * Without the Cheater Map, doors of undiscovered rooms stay hidden (except the fairy-room door).
 */
object DoorESP: Feature(
    name = "Door ESP",
    jsonName = "Door ESP",
    description = "Highlights the doors of the dungeon during the clear: blood rush doors and the doors of your current room."
) {
    private val brDoors by ToggleSetting("Blood rush doors")
        .withDescription("Boxes unopened wither and blood doors, green when the team has a key and red when it does not.")
    private val highlightAllDoors by ToggleSetting("Highlight All Doors")
        .withDescription("Highlights every unopened door instead of only the next door after the run starts.")
        .showIf { brDoors.value && MapConfig.dungeonMapCheater.value }
    private val normalDoors by ToggleSetting("Normal doors")
        .withDescription("Boxes the doors of the room you are standing in.")

    private val normalDoorColor by ColorSetting("Normal Door", Color.WHITE.withAlpha(50)).showIf { normalDoors.value }.section("Colors")
        .withDescription("Colour of the doors of your current room.")
    private val doorNoKeyColor by ColorSetting("No Key Color ", Color.RED.withAlpha(50)).showIf { brDoors.value }
        .withDescription("Colour of a blood rush door while nobody has its key.")
    private val doorKeyColor by ColorSetting("Has Key Color ", Color.GREEN.withAlpha(50)).showIf { brDoors.value }
        .withDescription("Colour of a blood rush door once the key has been picked up.")
    private val roomName by ToggleSetting("Room Name").section("Extra")
        .withDescription("Shows the name of the room the door leads to (normal doors only).")
    private val roomSecrets by ToggleSetting("Room Secrets")
        .withDescription("Shows the found / total secrets of the room the door leads to (normal doors only).")

    private val mode by DropdownSetting("Mode", 0, listOf("Outline", "Fill", "Filled Outline")).section("Options")
        .withDescription("How the door boxes are drawn.")
    private val lineWidth by SliderSetting("Line Width", 2.5, 1, 10, 0.1).hideIf { mode.value == 1 }
        .withDescription("Width of the box outline.")
    private val phase by ToggleSetting("Phase", true)
        .withDescription("Draws the boxes, room names and secrets through walls.")

    private const val MAX_TEXT_WIDTH = 2.8f // width in blocks
    private const val TEXT_REF = 0.025f

    /** Wither/blood doors you walked through: they are drawn as normal doors afterwards. */
    private val passedDoors = mutableSetOf<DoorTile>()

    override fun init() {
        register<WorldChangeEvent> { passedDoors.clear() }

        register<DungeonEvent.RoomEvent.onEnter> {
            val lastRoom = ScanUtils.lastKnownRoom ?: return@register

            DungeonScanner.doorTiles.find { door ->
                val rooms = door.roomTiles.mapNotNull(RoomTile::uniqueRoom)
                rooms.size == 2 && lastRoom in rooms && event.room in rooms
            }?.let(passedDoors::add)
        }

        register<RenderWorldEvent> {
            if (! brDoors.value && ! normalDoors.value) return@register
            if (! LocationUtils.inDungeon || LocationUtils.inBoss) return@register
            val currentRoom = ScanUtils.currentRoom
            if (! brDoors.value && currentRoom == null) return@register

            val hideUndiscovered = DungeonMap.enabled && ! MapConfig.dungeonMapCheater.value
            val hideBrDoors = ! MapConfig.dungeonMapCheater.value || (DungeonListener.dungeonStarted && ! highlightAllDoors.value)
            val showText = roomName.value || roomSecrets.value
            val outline = mode.value != 1
            val fill = mode.value != 0

            for (tile in DungeonScanner.doorTiles) {
                if (tile.type == DoorType.ENTRANCE) continue

                if (! tile.opened && tile.type.equalsOneOf(DoorType.BLOOD, DoorType.WITHER)) {
                    if (! brDoors.value) continue
                    if (hideBrDoors && tile.state == RoomState.UNDISCOVERED && ! DungeonTree.isFairy(tile)) continue

                    event.ctx.renderBoxBounds(
                        tile.aabb,
                        (if (tile.type.keys > 0) doorKeyColor else doorNoKeyColor).value,
                        outline = outline,
                        fill = fill,
                        phase = phase.value,
                        lineWidth = lineWidth.value
                    )

                    continue
                }

                if (! normalDoors.value || currentRoom == null) continue
                if (hideUndiscovered && tile.state == RoomState.UNDISCOVERED && ! DungeonTree.isFairy(tile)) continue

                val tiles = tile.roomTiles.mapNotNull(RoomTile::uniqueRoom)
                if (currentRoom !in tiles) continue
                if (tile.type.equalsOneOf(DoorType.BLOOD, DoorType.WITHER) && tile !in passedDoors) continue

                event.ctx.renderBoxBounds(
                    tile.aabb,
                    normalDoorColor.value,
                    outline = outline,
                    fill = fill,
                    phase = phase.value,
                    lineWidth = lineWidth.value
                )

                if (! showText) continue
                val nextRoom = tiles.find { it != currentRoom } ?: continue

                var width = 0
                val text = buildString {
                    if (roomName.value) {
                        val color = when (nextRoom.mainRoom.state) {
                            RoomState.GREEN -> ChatFormatting.GREEN
                            RoomState.FAILED -> ChatFormatting.RED
                            else -> ChatFormatting.WHITE
                        }.toString()

                        val str = color + nextRoom.name
                        width = maxOf(width, str.width())
                        append(str)
                    }

                    if (roomSecrets.value && nextRoom.data.secrets > 0) {
                        val color = ColorUtils.colorCodeByPercent(nextRoom.foundSecrets, nextRoom.data.secrets)
                        val str = "$color${nextRoom.foundSecrets}&f/&a${nextRoom.data.secrets}"
                        width = maxOf(width, str.width())
                        append("\n$str")
                    }
                }.ifEmpty { continue }

                val scale = minOf(2f, MAX_TEXT_WIDTH / (width * TEXT_REF))
                val textPhase = phase.value && run {
                    if (! hideUndiscovered) return@run true
                    if (! nextRoom.mainRoom.state.equalsOneOf(RoomState.UNDISCOVERED, RoomState.UNOPENED)) return@run true
                    DungeonTree.isFairy(tile)
                }

                event.ctx.renderString(text, tile.aabb.center, scale = scale, phase = textPhase)
            }
        }
    }
}
