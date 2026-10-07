package com.laybalt.features.impl.render

import com.laybalt.config.types.SliderSetting
import com.laybalt.config.types.ToggleSetting
import com.laybalt.event.impl.TickEvent
import com.laybalt.features.Feature
import net.minecraft.client.CameraType
import kotlin.math.exp

/**
 * Third-person camera that passes through blocks, with an adjustable distance that eases in
 * when you switch out of first person.
 *
 * @see com.laybalt.mixin.MixinCameraNoCameraClip
 * @see com.laybalt.mixin.MixinOptionsNoCameraClip
 */
object NoCameraClip: Feature("Lets the third-person camera pass through blocks and sets how far it sits from you. Do not combine with the camera clip, distance and front-camera options of Camera Helper.") {
    @JvmStatic val distance by SliderSetting("Distance", 3.0f, 1.0f, 20.0f, 0.1f)
        .withDescription("Distance of the third-person camera from your player in blocks. Vanilla uses 4")

    private val smoothZoom by ToggleSetting("Smooth Zoom", true)
        .withDescription("Slides the camera out from your head to the chosen distance when you enter third person")

    @JvmStatic val antiFront by ToggleSetting("Anti Front", false)
        .withDescription("Skips the front-facing third-person view when cycling perspectives with F5")

    private const val ZOOM_SPEED = 10f
    /** A gap longer than this between two camera updates means the camera was in first person in between. */
    private const val RESET_GAP_SECONDS = 0.3f

    private var animatedDistance = 1f
    private var lastUpdateNanos = 0L

    override fun init() {
        register<TickEvent.End> {
            if (! antiFront.value) return@register
            if (mc.options.cameraType == CameraType.THIRD_PERSON_FRONT) mc.options.cameraType = CameraType.FIRST_PERSON
        }
    }

    /**
     * Returns the camera distance to use this frame. Only called while the camera is detached (third person).
     */
    @JvmStatic
    fun cameraDistance(vanilla: Float): Float {
        if (! enabled) return vanilla
        val target = distance.value.toFloat()
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

    @JvmStatic
    fun filterCameraType(type: CameraType): CameraType =
        if (enabled && antiFront.value && type == CameraType.THIRD_PERSON_FRONT) CameraType.FIRST_PERSON else type
}
