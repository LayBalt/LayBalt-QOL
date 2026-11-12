// Cheater Map options ported from OdinClient (https://github.com/skies-starred/OdinClient), BSD-3-Clause, Copyright (c) 2026 Starred
package com.laybalt.features.impl.dungeon.map

import com.laybalt.config.ConfigHolder
import com.laybalt.config.SettingProvider
import com.laybalt.config.types.*
import java.awt.Color

//#if LEGIT
//$import java.util.*
//#endif

object MapConfig: SettingProvider {
    override val configSettings = mutableSetOf<ConfigHolder<*>>()

    val mapEnabled by ToggleSetting("Map Enabled", true).section("Map")

    //#if CHEAT
    val dungeonMapCheater by ToggleSetting("Cheater Map", false)
        .withDescription("Reveals the whole dungeon layout on the map, scanned from the loaded world, before the rooms are opened.")
    val cheaterShowRooms by ToggleSetting("Show Hidden Rooms", true)
        .withDescription("Draws rooms and doors that are not revealed on the vanilla map yet.").showIf { dungeonMapCheater.value }
    val cheaterDarken by ToggleSetting("Darken Hidden Tiles", true)
        .withDescription("Darkens hidden rooms and the doors leading into them so you can tell them apart from discovered ones.").showIf { dungeonMapCheater.value && cheaterShowRooms.value }
    val cheaterDarkenFactor by SliderSetting("Darken Factor", 0.6, 0.0, 1.0, 0.1)
        .withDescription("Brightness kept by hidden tiles: 1 keeps the normal colour, 0 makes them black.").showIf { dungeonMapCheater.value && cheaterShowRooms.value && cheaterDarken.value }
    val cheaterShowNames by ToggleSetting("Show Hidden Names", true)
        .withDescription("Shows the room name / secrets / checkmark text for hidden rooms and rooms you have not entered yet.").showIf { dungeonMapCheater.value && cheaterShowRooms.value }
    //#else
    //$val dungeonMapCheater by ToggleSetting("Legit", false).hideIf { true }.jsonName(UUID.randomUUID().toString())
    //$val cheaterShowRooms by ToggleSetting("Legit Rooms", false).hideIf { true }.jsonName(UUID.randomUUID().toString())
    //$val cheaterDarken by ToggleSetting("Legit Darken", false).hideIf { true }.jsonName(UUID.randomUUID().toString())
    //$val cheaterDarkenFactor by SliderSetting("Legit Darken Factor", 0.6, 0.0, 1.0, 0.1).hideIf { true }.jsonName(UUID.randomUUID().toString())
    //$val cheaterShowNames by ToggleSetting("Legit Names", false).hideIf { true }.jsonName(UUID.randomUUID().toString())
    //#endif

    /** Cheater map with hidden rooms revealed. */
    val revealHidden get() = dungeonMapCheater.value && cheaterShowRooms.value

    val mapExtraInfo by ToggleSetting("Show Extra Info Under Map", false)
    val mapHideInBoss by ToggleSetting("Hide In Boss", false)
    val printPlayersClearInfo by ToggleSetting("Player Clear Info", false)
    val playerNames by DropdownSetting("Show Player Names", 0, listOf("Off", "Holding Leap", "Always"))
    val mapVanillaMarker by ToggleSetting("Vanilla Head Marker", false)

    val textScale by SliderSetting("Map Text Scale", 1f, 0.4f, 1.5f, 0.1).section("Size")
    val checkmarkSize by SliderSetting("Map Checkmark Scale", 1f, 0.3f, 1.5f, 0.1f)
    val doorHeightSize by SliderSetting("Door Width", 6, 6, 10, 1)
        .withDescription("Width of the doorways between rooms on the map, in map pixels (6 is the vanilla size).")
    val playerHeadScale by SliderSetting("Player Heads Scale", 1f, 0.3f, 2f, 0.1f)
    val playerHeadBorderSize by SliderSetting("Head Border Size", 1, 0, 2, 1)
        .withDescription("Thickness of the border (Head Border colour or class colour) around player heads, in pixels; 0 hides it.")
    val playerNameScale by SliderSetting("Player Name Scale", 0.5f, 0.3f, 1.5f, 0.1f)

    val mapBackground by ColorSetting("Map Background Color", Color(255, 255, 255, 50), true)
    val mapBorderColor by ColorSetting("Map Border Color", Color(255, 255, 255), true)
    val mapBorderWidth by SliderSetting("Border Thickness", 1, 1, 5, 1)

    val dungeonMapCheckmarkStyle by DropdownSetting("Checkmark Style", 0, listOf("Checkmarks", "Secrets", "Room Name", "Room Name + Secrets", "Secrets + Puzzle Name")).section("Rooms")
    val centerStyle by ToggleSetting("Center Checkmark/Text", true).jsonName("Center Checkmark")
    val hideBloodroomOvelay by ToggleSetting("Hide text/checkmark of Blood room", false)
    val hideQuestionCheckmarks by ToggleSetting("Hide Unknown Room Checkmark", false).showIf { dungeonMapCheckmarkStyle.value == 0 }
    val limitRoomNameSize by ToggleSetting("Limit Room Name Size", true).showIf { dungeonMapCheckmarkStyle.value in 2 .. 4 }
    val predictRooms by ToggleSetting("Predict Unopened 1x1s", true).showIf { ! revealHidden }
        .withDescription("Colours unopened 1x1 rooms you have seen a door into with the puzzle, trap or miniboss type they must be, worked out from the dungeon layout. Two or three darker colours mean it is one of those types.")

    //#if CHEAT
    val mimicEsp by ToggleSetting("Mimic ESP")
    val mimicEspColor by ColorSetting("Mimic ESP Color", Color(255, 0, 0, 50), true).showIf { mimicEsp.value }
    val highlightMimicRoom by ToggleSetting("Highlight Mimic Room", true)
    //#endif

    val mapPlayerHeadColor by ColorSetting("Head Border", Color(0, 0, 0), true).section("Colors")
    val mapVanillaMarkerColor by ColorSetting("Vanilla Head Marker", Color(0, 255, 0), true).jsonName("Vanilla Head Marker Color")
    val mapPlayerHeadColorClassBased by ToggleSetting("Head Border Class Base", false)
    val mapPlayerNameClassColorBased by ToggleSetting("Player Names Class Base", false)

    //#if CHEAT
    val colorMimic by ColorSetting("Mimic Room", Color(255, 0, 0), true).showIf { highlightMimicRoom.value }
    //#endif

    val colorUnopened by ColorSetting("Unopened Room", Color(65, 65, 65), true)
    val colorBlood by ColorSetting("Blood Room", Color(255, 0, 0), true)
    val colorFairy by ColorSetting("Fairy Room", Color(242, 127, 165), true)
    val colorRare by ColorSetting("Rare Room", Color(178, 178, 178), true)
    val colorMiniboss by ColorSetting("Miniboss Room", Color(229, 229, 51), true)
    val colorPuzzle by ColorSetting("Puzzle Room", Color(178, 76, 216), true)
    val colorTrap by ColorSetting("Trap Room", Color(216, 127, 51), true)
    val colorRoom by ColorSetting("Normal Room", Color(114, 67, 27), true)
    val colorEntrance by ColorSetting("Entrance Room", Color(0, 124, 0), true)

    val colorUnopenedDoor by ColorSetting("Unopened Door", colorUnopened.value, true)
    val colorBloodDoor by ColorSetting("Blood Door", colorBlood.value, true)
    val colorWitherDoor by ColorSetting("Wither Door", Color(16, 16, 16), true)
    val colorRoomDoor by ColorSetting("Normal Door", colorRoom.value, true)
    val colorOpenWitherDoor by ColorSetting("Opened Wither Door", colorRoom.value, true)
    val colorEntranceDoor by ColorSetting("Entrance Door", colorEntrance.value, true)
    // The "Door ESP" section (Box Wither Doors) moved to the Door ESP feature; ConfigMigratorV1ToV2 carries the settings over.
}