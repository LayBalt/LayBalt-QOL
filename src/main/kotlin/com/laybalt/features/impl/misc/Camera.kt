// Restored from NoammAddons (com.github.noamm9.features.impl.misc.Camera); camera-distance range widened to match
// OdinClient's CameraHelper (https://github.com/skies-starred/OdinClient), BSD-3-Clause, Copyright (c) 2026 Starred
package com.laybalt.features.impl.misc

import com.laybalt.LayBaltQOL
import com.laybalt.config.types.SliderSetting
import com.laybalt.config.types.ToggleSetting
import com.laybalt.event.impl.GameStartEvent
import com.laybalt.event.impl.MainThreadPacketReceivedEvent
import com.laybalt.features.Feature
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket

//#if LEGIT
//$import java.util.*
//#endif

object Camera: Feature(
    description = "Tweaks to the vanilla camera: full bright, custom FOV and distance, camera clip and hiding screen overlays and effects.",
    name = "Camera Helper",
    jsonName = "Camera"
) {
    @JvmField var flashFullFright = false

    @JvmStatic val fullBright by ToggleSetting("Full Bright").withDescription("Renders the world at full brightness regardless of the light level.").onChange {
        flashFullFright = true
    }

    @JvmStatic val noFrontCamera by ToggleSetting("Disable Front Camera").withDescription("Removes the front camera perspective.").section("Camera")
    //#if CHEAT
    @JvmStatic val noCameraClip by ToggleSetting("Camera Clip").withDescription("Allows your camera to clip in walls.").showIf { LayBaltQOL.isCheat }
    @JvmStatic val customCameraDistance by ToggleSetting("Custom Camera Distance").withDescription("Sets the distance of the camera from your player.").showIf { LayBaltQOL.isCheat }
    @JvmStatic val cameraDistance by SliderSetting("Camera Distance", 4, 1, 12, 0.1).withDescription("The distance of the camera from the player in third person (vanilla is 4).").showIf { customCameraDistance.value && LayBaltQOL.isCheat }
    //#else
    //$@JvmStatic val noCameraClip by ToggleSetting("Camera Clip", false).hideIf { true }.jsonName(UUID.randomUUID().toString())
    //$@JvmStatic val customCameraDistance by ToggleSetting("Custom Camera Distance", false).hideIf { true }.jsonName(UUID.randomUUID().toString())
    //$@JvmStatic val cameraDistance by SliderSetting("Camera Distance", 4, 1, 12, 0.1).hideIf { true }.jsonName(UUID.randomUUID().toString())
    //#endif
    private val doubleSneakFix by ToggleSetting("Double Sneak Fix").withDescription("Prevents the server from setting your sneak state")
    @JvmStatic val inputFix by ToggleSetting("Riding Input Delay Fix").withDescription("Fixes high mouse input delay when riding an entity. (MC-206540)")

    @JvmStatic val hideFireOverlay by ToggleSetting("Hide Fire Overlay").withDescription("Hides the fire overlay on your screen.").section("Hide Overlays")
    @JvmStatic val hidePortalOverlay by ToggleSetting("Hide Portal Overlay").withDescription("Hides the portal overlay on your screen when you enter a portal.")
    @JvmStatic val hideWaterOverlay by ToggleSetting("Hide Water Overlay").withDescription("Hides the under water overlay on your screen.")
    @JvmStatic val hideBlockOverlay by ToggleSetting("Hide Block Overlay").withDescription("Hides the block that render on your screen when you are stuck inside a block.")

    @JvmStatic val disableBlindness by ToggleSetting("Disable Blindness").withDescription("Disables the Blindness effect.").section("Hide Effects")
    @JvmStatic val disableNausea by ToggleSetting("Disable Nausea").withDescription("Disables the Nausea effect")

    @JvmStatic val customFOV by ToggleSetting("Custom FOV").withDescription("Overrides the field of view with the value below (works together with zoom mods).").section("Custom FOV")
    @JvmStatic val customFOVSlider by SliderSetting("FOV", 110, 30, 179, 1).withDescription("The field of view used when Custom FOV is enabled.").hideIf { ! customFOV.value }

    override fun init() {
        //#if LEGIT
        //$register<GameStartEvent> {
        //$    noCameraClip.value = false
        //$    customCameraDistance.value = false
        //$}
        //#endif

        register<MainThreadPacketReceivedEvent.Pre> {
            if (! doubleSneakFix.value) return@register
            val packet = event.packet as? ClientboundSetEntityDataPacket ?: return@register
            if (player.id != packet.id) return@register
            packet.packedItems.removeIf { it.id == 6 }
        }
    }
}
