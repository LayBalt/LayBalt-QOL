package com.laybalt.features.impl.render

import com.laybalt.LayBaltQOL
import com.laybalt.config.types.ButtonSetting
import com.laybalt.config.types.ColorSetting
import com.laybalt.config.types.DropdownSetting
import com.laybalt.config.types.SliderSetting
import com.laybalt.config.types.ToggleSetting
import com.laybalt.event.impl.RenderWorldEvent
import com.laybalt.event.impl.TickEvent
import com.laybalt.event.impl.WorldChangeEvent
import com.laybalt.features.Feature
import com.laybalt.features.impl.render.effects.EffectColors
import com.laybalt.features.impl.render.effects.EffectPlane
import com.laybalt.features.impl.render.effects.EffectShapes
import com.laybalt.utils.ChatUtils
import com.laybalt.utils.render.world.EffectBatcher
import com.laybalt.utils.render.world.RenderContext
import com.mojang.blaze3d.platform.NativeImage
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.resources.Identifier
import net.minecraft.util.LightCoordsUtil
import org.joml.Vector3f
import java.awt.Color
import kotlin.io.path.isRegularFile
import kotlin.io.path.readBytes
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * A spinning, expanding and fading circle on the ground wherever a player jumps.
 * Default and Portal are drawn procedurally; Custom uses a PNG the user drops into the config folder.
 */
object JumpCircle: Feature("Draws a spinning, expanding circle on the ground where a player jumps.") {
    private const val DEFAULT = 0
    private const val PORTAL = 1
    private const val CUSTOM = 2

    private val mode by DropdownSetting("Mode", DEFAULT, listOf("Default", "Portal", "Custom"))
        .withDescription("Default: a soft glowing ring. Portal: a swirling vortex. Custom: your own image, loaded from circle.png in the mod's config folder (a chat message tells you the exact path)")
        .onChange { if (it == CUSTOM) customState = TextureState.UNLOADED }
    private val easeOut by ToggleSetting("Ease Out", true)
        .withDescription("The circle grows to full size and then shrinks away again (5 s). Off: it grows slowly and fades out over 6 s")
    private val rotateSpeed by SliderSetting("Rotate Speed", 2f, 0.5f, 5f, 0.1f)
        .withDescription("How many turns the circle makes over its life")
    private val circleScale by SliderSetting("Circle Scale", 1f, 0.5f, 5f, 0.1f)
        .withDescription("Radius of the circle at full size, in blocks")
    private val onlySelf by ToggleSetting("Only Self")
        .withDescription("Only draws circles for your own jumps")
    private val throughWalls by ToggleSetting("Through Walls", true)
        .withDescription("Draws circles on top of blocks so they stay visible behind walls. Not available for Custom images").hideIf { mode.value == CUSTOM }
    private val colorMode by DropdownSetting("Color Mode", 1, listOf("Custom", "Rainbow"))
        .withDescription("Rainbow tints the four corners of the circle with an animated rainbow; Custom uses the colour below").section("Colors")
    private val color by ColorSetting("Color", Color(160, 110, 255))
        .withDescription("Circle colour in Custom colour mode").showIf { colorMode.value == 0 }
    private val reloadImage by ButtonSetting("Reload Image") { customState = TextureState.UNLOADED }
        .withDescription("Loads circle.png again after you changed it").showIf { mode.value == CUSTOM }

    private class Circle(val x: Double, val y: Double, val z: Double) {
        val time = System.currentTimeMillis()
    }

    private val circles = ArrayList<Circle>()

    /** Player id -> Y of the ground they last stood on. */
    private val grounded = HashMap<Int, Double>()

    private enum class TextureState { UNLOADED, LOADED, MISSING }

    private var customState = TextureState.UNLOADED
    private val customId = Identifier.fromNamespaceAndPath(LayBaltQOL.MOD_ID, "jump_circle/custom")
    private val customFile get() = FabricLoader.getInstance().configDir.resolve(LayBaltQOL.CONFIG_DIR).resolve("circle.png")

    private val flatU = Vector3f()
    private val flatV = Vector3f()

    override fun init() {
        register<WorldChangeEvent> {
            circles.clear()
            grounded.clear()
        }

        register<TickEvent.End> {
            val level = mc.level ?: return@register
            val self = mc.player ?: return@register
            val players = if (onlySelf.value) listOf(self) else level.players()
            val seen = HashSet<Int>()

            for (player in players) {
                val id = player.id
                seen += id
                if (player.onGround()) {
                    grounded[id] = player.y
                    continue
                }
                val groundY = grounded.remove(id) ?: continue
                // Only real jumps: walking off an edge drops straight down instead.
                if (player.y <= player.yo) continue
                circles += Circle(player.x, groundY + 0.01, player.z)
                if (circles.size > 100) circles.removeAt(0)
            }
            grounded.keys.retainAll(seen)
        }

        register<RenderWorldEvent> {
            if (circles.isEmpty()) return@register
            val now = System.currentTimeMillis()
            val life = if (easeOut.value) 5000L else 6000L
            circles.removeIf { now - it.time >= life }
            if (circles.isEmpty()) return@register

            val ctx = event.ctx
            if (mode.value == CUSTOM) {
                if (ensureCustomTexture()) renderCustom(ctx, now, life)
                return@register
            }

            val plane = EffectPlane(ctx)
            plane.batch = EffectBatcher.batch(additive = true, seeThrough = throughWalls.value)

            for (circle in circles) {
                val (radius, angle, alpha) = animation(now - circle.time, life) ?: continue
                flatU.set(cos(angle), 0f, sin(angle))
                flatV.set(- sin(angle), 0f, cos(angle))
                plane.place(circle.x, circle.y, circle.z, flatU, flatV, radius)
                applyColor(plane, alpha)
                if (mode.value == PORTAL) EffectShapes.portal(plane, 0f)
                else {
                    EffectShapes.band(plane, 0f, 0.08f, 0.55f, 0.18f, 40)
                    EffectShapes.softRing(plane, 0.55f, 0.85f, 1f, 1f, 64)
                }
            }
            EffectBatcher.flush(ctx.collector)
        }
    }

    override fun onDisable() {
        super.onDisable()
        circles.clear()
        grounded.clear()
    }

    /** Radius (blocks), rotation (radians) and alpha for a circle [elapsed] ms old, or null when invisible. */
    private fun animation(elapsed: Long, life: Long): Triple<Float, Float, Float>? {
        val t = elapsed * (if (easeOut.value) 2f else 1f) / 5000f
        val size = (1f - (1f - t).pow(4)).coerceIn(0f, 1f)
        val radius = circleScale.value * size
        if (radius <= 0.001f) return null
        val progress = (elapsed.toFloat() / life).coerceIn(0f, 1f)
        val spin = 1f - (1f - progress).pow(4)
        val angle = Math.toRadians(spin * rotateSpeed.value * 360.0).toFloat()
        val alpha = (1f - elapsed / 6000f).coerceIn(0f, 1f)
        return Triple(radius, angle, alpha)
    }

    private fun corners(): Array<Color> =
        if (colorMode.value == 1) arrayOf(EffectColors.sync(270), EffectColors.sync(0), EffectColors.sync(180), EffectColors.sync(90))
        else Array(4) { color.value }

    private fun applyColor(plane: EffectPlane, alpha: Float) {
        val c = corners()
        plane.gradient(c[0], c[1], c[2], c[3], alpha)
    }

    /** Loads circle.png once; tells the user where to put it when it is missing or broken. */
    private fun ensureCustomTexture(): Boolean {
        when (customState) {
            TextureState.LOADED -> return true
            TextureState.MISSING -> return false
            TextureState.UNLOADED -> Unit
        }
        val file = customFile
        if (! file.isRegularFile()) {
            customState = TextureState.MISSING
            ChatUtils.modMessage("&eJump Circle: put a PNG image named &fcircle.png&e here, then press Reload Image: &f${file.toAbsolutePath()}")
            return false
        }
        return try {
            val image = NativeImage.read(file.readBytes().inputStream())
            mc.textureManager.release(customId)
            mc.textureManager.register(customId, DynamicTexture({ "LayBalt jump circle" }, image))
            customState = TextureState.LOADED
            true
        }
        catch (e: Exception) {
            customState = TextureState.MISSING
            LayBaltQOL.logger.warn("Failed to load jump circle image", e)
            ChatUtils.modMessage("&cJump Circle: could not read &f${file.toAbsolutePath()}&c (must be a PNG image)")
            false
        }
    }

    /** Custom image: a flat textured quad per circle (depth-tested, translucent). */
    private fun renderCustom(ctx: RenderContext, now: Long, life: Long) {
        data class Quad(val x: Float, val y: Float, val z: Float, val u: Vector3f, val v: Vector3f, val colors: IntArray)

        val cam = ctx.camera.pos
        val quads = circles.mapNotNull { circle ->
            val (radius, angle, alpha) = animation(now - circle.time, life) ?: return@mapNotNull null
            val c = corners()
            val colors = IntArray(4) { i -> Color(c[i].red, c[i].green, c[i].blue, (c[i].alpha * alpha).toInt().coerceIn(0, 255)).rgb }
            Quad(
                (circle.x - cam.x).toFloat(), (circle.y - cam.y).toFloat(), (circle.z - cam.z).toFloat(),
                Vector3f(cos(angle), 0f, sin(angle)).mul(radius), Vector3f(- sin(angle), 0f, cos(angle)).mul(radius), colors
            )
        }
        if (quads.isEmpty()) return

        ctx.collector.submitCustomGeometry(ctx.matrixStack, RenderTypes.entityTranslucentEmissive(customId)) { pose, buffer ->
            for (q in quads) {
                fun vertex(su: Float, sv: Float, color: Int, tu: Float, tv: Float) {
                    buffer.addVertex(pose, q.x + q.u.x * su + q.v.x * sv, q.y, q.z + q.u.z * su + q.v.z * sv)
                        .setColor(color)
                        .setUv(tu, tv)
                        .setOverlay(OverlayTexture.NO_OVERLAY)
                        .setUv2(LightCoordsUtil.FULL_BRIGHT, LightCoordsUtil.FULL_BRIGHT)
                        .setNormal(pose, 0f, 1f, 0f)
                }
                // Both windings so the image is visible from above and below.
                vertex(- 1f, - 1f, q.colors[0], 0f, 0f)
                vertex(1f, - 1f, q.colors[1], 1f, 0f)
                vertex(1f, 1f, q.colors[2], 1f, 1f)
                vertex(- 1f, 1f, q.colors[3], 0f, 1f)

                vertex(- 1f, 1f, q.colors[3], 0f, 1f)
                vertex(1f, 1f, q.colors[2], 1f, 1f)
                vertex(1f, - 1f, q.colors[1], 1f, 0f)
                vertex(- 1f, - 1f, q.colors[0], 0f, 0f)
            }
        }
    }
}
