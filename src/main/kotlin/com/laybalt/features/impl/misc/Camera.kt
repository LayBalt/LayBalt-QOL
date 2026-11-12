// Restored from NoammAddons (com.github.noamm9.features.impl.misc.Camera); camera-distance range widened to match
// OdinClient's CameraHelper (https://github.com/skies-starred/OdinClient), BSD-3-Clause, Copyright (c) 2026 Starred.
// Smooth Zoom re-implemented after ThunderHack-Recode's NoCameraClip.
package com.laybalt.features.impl.misc

import com.laybalt.LayBaltQOL
import com.laybalt.config.types.SliderSetting
import com.laybalt.config.types.ToggleSetting
import com.laybalt.event.impl.GameStartEvent
import com.laybalt.event.impl.MainThreadPacketReceivedEvent
import com.laybalt.features.Feature
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket
import net.minecraft.world.entity.Entity
import kotlin.math.exp

//#if LEGIT
//$import java.util.*
//#endif

/**
 * Camera distance / clip / smooth zoom / front camera, screen overlays and effects.
 * Brightness and field of view are owned by Render > Fullbright and Render > FOV.
 *
 * @see com.laybalt.mixin.MixinCamera (camera distance + camera clip)
 * @see com.laybalt.mixin.MixinOptions (front camera)
 */
object Camera: Feature(
    description = "Tweaks to the vanilla camera: camera distance, smooth zoom, camera clip, no front camera and hiding screen overlays and effects. Brightness and FOV are in Render > Fullbright / FOV.",
    name = "Camera Helper",
    jsonName = "Camera"
) {
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
    private val smoothZoom by ToggleSetting("Smooth Zoom").withDescription("Slides the camera out from your head to its distance when you enter third person.")
    private val doubleSneakFix by ToggleSetting("Double Sneak Fix").withDescription("Prevents the server from setting your sneak state")
    @JvmStatic val inputFix by ToggleSetting("Riding Input Delay Fix").withDescription("Fixes high mouse input delay when riding an entity. (MC-206540)")

    @JvmStatic val hideFireOverlay by ToggleSetting("Hide Fire Overlay").withDescription("Hides the fire overlay on your screen.").section("Hide Overlays")
    @JvmStatic val hidePortalOverlay by ToggleSetting("Hide Portal Overlay").withDescription("Hides the portal overlay on your screen when you enter a portal.")
    @JvmStatic val hideWaterOverlay by ToggleSetting("Hide Water Overlay").withDescription("Hides the under water overlay on your screen.")
    @JvmStatic val hideBlockOverlay by ToggleSetting("Hide Block Overlay").withDescription("Hides the block that render on your screen when you are stuck inside a block.")

    @JvmStatic val disableBlindness by ToggleSetting("Disable Blindness").withDescription("Disables the Blindness effect.").section("Hide Effects")
    @JvmStatic val disableNausea by ToggleSetting("Disable Nausea").withDescription("Disables the Nausea effect")

    private const val ZOOM_SPEED = 10f
    /** A gap longer than this between two camera updates means the camera was in first person in between. */
    private const val RESET_GAP_SECONDS = 0.3f

    private var animatedDistance = 1f
    private var lastUpdateNanos = 0L

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

    /**
     * Third-person camera distance for this frame (the argument of Camera.getMaxZoom). Only called while the
     * camera is detached; [vanilla] is the scaled CAMERA_DISTANCE attribute.
     */
    @JvmStatic
    fun thirdPersonDistance(vanilla: Float): Float {
        if (! enabled) return vanilla
        //#if CHEAT
        val target = if (customCameraDistance.value) cameraDistance.value.toFloat() else vanilla
        //#else
        //$val target = vanilla
        //#endif
        if (! smoothZoom.value) return target

        val now = System.nanoTime()
        var delta = (now - lastUpdateNanos) / 1_000_000_000f
        if (lastUpdateNanos == 0L || delta > RESET_GAP_SECONDS) {
            animatedDistance = 1f
            delta = 0f
        }
        lastUpdateNanos = now

        animatedDistance += (target - animatedDistance) * (1f - exp(- ZOOM_SPEED * delta))
        return animatedDistance
    }

    /**
     * NoammAddons e80ddc84: the below-name (score) line of your own player is only extracted within [distance] blocks
     * of the camera, so a custom camera distance hid it. Extends that range by the camera distance for your own player.
     *
     * @see com.laybalt.mixin.MixinLivingEntityRenderer
     */
    @JvmStatic
    fun belowNameDistanceHook(entity: Entity, distance: Double): Double {
        if (! enabled) return distance
        if (! customCameraDistance.value) return distance
        if (entity !== mc.player) return distance
        return distance + cameraDistance.value.toDouble()
    }
}
