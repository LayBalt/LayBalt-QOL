package com.laybalt.features.impl.render

import com.laybalt.config.types.ColorSetting
import com.laybalt.config.types.SliderSetting
import com.laybalt.config.types.ToggleSetting
import com.laybalt.event.impl.RenderWorldEvent
import com.laybalt.event.impl.WorldChangeEvent
import com.laybalt.features.Feature
import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.model.HumanoidModel
import net.minecraft.client.model.geom.ModelPart
import net.minecraft.client.renderer.entity.state.AvatarRenderState
import net.minecraft.client.renderer.entity.state.HumanoidRenderState
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.Vec3
import com.laybalt.utils.render.world.RenderBatcher
import org.joml.Vector3f
import java.awt.Color

/**
 * Draws the "bones" of player models as lines that follow the animated pose (head, spine, shoulders,
 * hips, arms, legs). Bone positions are taken from the humanoid model parts while the entity renderer
 * submits the entity (`MixinLivingEntityRendererSkeleton`) and drawn in the same frame.
 */
object Skeleton: Feature("Draws the skeleton (head, spine, arms and legs) of players as lines that follow their animation.") {
    private val color by ColorSetting("Color", Color(255, 255, 255, 255))
        .withDescription("Bone colour for players")
    private val friendColor by ColorSetting("Friend Color", Color(0x17, 0xDE, 0x5D, 255))
        .withDescription("Bone colour for members of your party")
    private val thickness by SliderSetting("Thickness", 1.5f, 0.5f, 5f, 0.25f)
        .withDescription("Line width of the bones")
    private val throughWalls by ToggleSetting("Through Walls", true)
        .withDescription("Bones are drawn on top of blocks, so players behind walls are visible")
    private val self by ToggleSetting("Self", false)
        .withDescription("Also draws your own skeleton (visible in third person)")
    private val ignoreNpcs by ToggleSetting("Ignore NPCs", true)
        .withDescription("Skips player-shaped NPCs (fake players with a non-standard UUID)")
    private val mobs by ToggleSetting("Humanoid Mobs", false)
        .withDescription("Also draws zombies, skeletons, piglins and other mobs that use the humanoid body")
    private val mobColor by ColorSetting("Mob Color", Color(0xFF, 0xAA, 0x00, 255))
        .withDescription("Bone colour for humanoid mobs").showIf { mobs.value }

    /** x1,y1,z1,x2,y2,z2 (world space) per bone plus its colour, filled while entities are submitted. */
    private var segments = FloatArray(6 * 256)
    private var colors = IntArray(256)
    private var count = 0
    private const val MAX_SEGMENTS = 8192

    private val tmp = Vector3f()

    /**
     * Only entities submitted for the world view count: the inventory player preview and other GUI
     * entity renders (and shader shadow passes) use another camera state and a GUI-space pose.
     */
    @JvmStatic
    fun isWorldCamera(camera: CameraRenderState) =
        camera === mc.gameRenderer.gameRenderState().levelRenderState.cameraRenderState

    @JvmStatic
    fun shouldDraw(state: HumanoidRenderState): Boolean {
        if (state.isInvisible) return false
        if (state !is AvatarRenderState) return mobs.value
        if (state.isSpectator) return false
        val level = mc.level ?: return false
        val entity = level.getEntity(state.id) as? Player ?: return false
        if (entity === mc.player) return self.value
        if (ignoreNpcs.value && Tracers.isNpc(entity)) return false
        return true
    }

    @JvmStatic
    fun collect(poseStack: PoseStack, model: HumanoidModel<*>, cameraPos: Vec3, state: HumanoidRenderState) {
        val argb = when {
            state !is AvatarRenderState -> mobColor.value.rgb
            (mc.level?.getEntity(state.id) as? Player)?.let(Tracers::isFriend) == true -> friendColor.value.rgb
            else -> color.value.rgb
        }
        val cx = cameraPos.x.toFloat()
        val cy = cameraPos.y.toFloat()
        val cz = cameraPos.z.toFloat()

        // Local points are in model pixels (1/16 block, Y pointing down), relative to each part's pivot.
        val head = joint(poseStack, model.head, 0f, 0f) to joint(poseStack, model.head, 0f, - 8f)
        val neck = joint(poseStack, model.body, 0f, 0f)
        val hips = joint(poseStack, model.body, 0f, 12f)
        val rShoulder = joint(poseStack, model.rightArm, 0f, 0f)
        val lShoulder = joint(poseStack, model.leftArm, 0f, 0f)
        val rHand = joint(poseStack, model.rightArm, 0f, 10f)
        val lHand = joint(poseStack, model.leftArm, 0f, 10f)
        val rHip = joint(poseStack, model.rightLeg, 0f, 0f)
        val lHip = joint(poseStack, model.leftLeg, 0f, 0f)
        val rFoot = joint(poseStack, model.rightLeg, 0f, 12f)
        val lFoot = joint(poseStack, model.leftLeg, 0f, 12f)

        fun bone(a: FloatArray, b: FloatArray) = add(a[0] + cx, a[1] + cy, a[2] + cz, b[0] + cx, b[1] + cy, b[2] + cz, argb)

        bone(head.first, head.second)
        bone(neck, hips)
        bone(rShoulder, lShoulder)
        bone(rHip, lHip)
        bone(rShoulder, rHand)
        bone(lShoulder, lHand)
        bone(rHip, rFoot)
        bone(lHip, lFoot)
    }

    /** Camera-relative position of a point given in the part's local pixel space. */
    private fun joint(poseStack: PoseStack, part: ModelPart, x: Float, y: Float): FloatArray {
        poseStack.pushPose()
        part.translateAndRotate(poseStack)
        poseStack.last().pose().transformPosition(x / 16f, y / 16f, 0f, tmp)
        poseStack.popPose()
        return floatArrayOf(tmp.x, tmp.y, tmp.z)
    }

    private fun add(x1: Float, y1: Float, z1: Float, x2: Float, y2: Float, z2: Float, argb: Int) {
        if (count >= MAX_SEGMENTS) return
        val base = count * 6
        if (base + 6 > segments.size) {
            segments = segments.copyOf(segments.size * 2)
            colors = colors.copyOf(colors.size * 2)
        }
        segments[base] = x1
        segments[base + 1] = y1
        segments[base + 2] = z1
        segments[base + 3] = x2
        segments[base + 4] = y2
        segments[base + 5] = z2
        colors[count] = argb
        count ++
    }

    override fun init() {
        register<WorldChangeEvent> { count = 0 }

        register<RenderWorldEvent> {
            if (count == 0) return@register
            val ctx = event.ctx
            ctx.matrixStack.pushPose()
            ctx.matrixStack.translate(ctx.camera.pos.reverse())
            val pose = ctx.uMatrixStack()
            val batch = RenderBatcher.lineBatch(throughWalls.value)
            val width = thickness.value
            for (i in 0 until count) {
                val b = i * 6
                val argb = colors[i]
                batch.line(
                    pose,
                    segments[b], segments[b + 1], segments[b + 2],
                    segments[b + 3], segments[b + 4], segments[b + 5],
                    ((argb shr 16) and 0xFF) / 255f, ((argb shr 8) and 0xFF) / 255f, (argb and 0xFF) / 255f, ((argb ushr 24) and 0xFF) / 255f,
                    width
                )
            }
            ctx.matrixStack.popPose()
            count = 0
        }
    }

    override fun onDisable() {
        super.onDisable()
        count = 0
    }
}
