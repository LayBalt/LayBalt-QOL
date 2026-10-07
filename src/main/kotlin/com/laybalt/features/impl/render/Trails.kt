package com.laybalt.features.impl.render

import com.laybalt.config.types.ColorSetting
import com.laybalt.config.types.DropdownSetting
import com.laybalt.config.types.SliderSetting
import com.laybalt.config.types.ToggleSetting
import com.laybalt.event.impl.EntityUnloadEvent
import com.laybalt.event.impl.RenderWorldEvent
import com.laybalt.event.impl.TickEvent
import com.laybalt.event.impl.WorldChangeEvent
import com.laybalt.features.Feature
import com.laybalt.features.impl.render.effects.EffectColors
import com.laybalt.features.impl.render.effects.EffectParticle
import com.laybalt.features.impl.render.effects.EffectPlane
import com.laybalt.features.impl.render.effects.EffectShapes
import com.laybalt.utils.render.RenderHelper
import com.laybalt.utils.render.RenderHelper.renderX
import com.laybalt.utils.render.RenderHelper.renderY
import com.laybalt.utils.render.RenderHelper.renderZ
import com.laybalt.utils.render.world.EffectBatcher
import com.laybalt.utils.render.world.RenderBatcher
import com.laybalt.utils.render.world.RenderContext
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.projectile.Projectile
import net.minecraft.world.entity.projectile.arrow.AbstractArrow
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownExperienceBottle
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import java.awt.Color
import java.util.*
import kotlin.random.Random

/**
 * Motion trails behind players (ribbon, pride-flag ribbon, glowing tail or sprite particles), sprite particles
 * behind ender pearls and arrows, and predicted flight paths of pearls, arrows and XP bottles.
 */
object Trails: Feature("Draws trails behind moving players and flying pearls and arrows, or shows where flying projectiles will land.") {
    private const val TRAIL = 0
    private const val PARTICLES = 1
    private const val CUTE = 2
    private const val TAIL = 3
    private const val PLAYER_NONE = 4
    private const val PROJECTILE_NONE = 2

    private const val SEGMENT_TICKS = 10
    private const val MAX_PARTICLES = 3000

    private val players by DropdownSetting("Players", PARTICLES, listOf("Trail", "Particles", "Cute", "Tail", "None"))
        .withDescription("Trail: a fading ribbon behind moving players. Particles: glowing sprites fall off their body. Cute: a striped pastel ribbon. Tail: a comet-like glowing tail. None: nothing for players")
    private val onlySelf by ToggleSetting("Only Self")
        .withDescription("Only draws the player effect for yourself").hideIf { players.value == PLAYER_NONE }
    private val pearls by DropdownSetting("Pearls", PARTICLES, listOf("Trail", "Particles", "None"))
        .withDescription("Trail: shows the predicted flight path of thrown ender pearls. Particles: glowing sprites behind them")
    private val arrows by DropdownSetting("Arrows", PARTICLES, listOf("Trail", "Particles", "None"))
        .withDescription("Trail: shows the predicted flight path of flying arrows. Particles: glowing sprites behind them while they fly")
    private val xp by ToggleSetting("Xp")
        .withDescription("Shows the predicted flight path of thrown bottles o' enchanting")

    private val trailColor by ColorSetting("Trail Color", Color(0x8800FF00.toInt(), true))
        .withDescription("Colour of the Trail ribbon and the Tail in Custom colour mode. Its alpha sets how see-through the ribbon, Cute ribbon and Tail are in every mode")
        .showIf { players.value in listOf(TRAIL, CUTE, TAIL) }.section("Ribbon")
    private val down by SliderSetting("Down", 0.5f, 0f, 2f, 0.05f)
        .withDescription("Height of the ribbon's lower edge above the player's feet").showIf { players.value == TRAIL }
    private val height by SliderSetting("Height", 1.3f, 0.1f, 2f, 0.05f)
        .withDescription("Height of the ribbon").showIf { players.value == TRAIL }

    private val sprite by DropdownSetting("Mode", 0, listOf("Stars", "Hearts", "Bloom"))
        .withDescription("Shape of the particles").showIf { anyParticles() }.section("Particles")
    private val physics by DropdownSetting("Physics", 0, listOf("Fall", "Fly"))
        .withDescription("Fall: particles slowly sink and bounce off blocks. Fly: they float freely").showIf { anyParticles() }
    private val scale by SliderSetting("Scale", 3, 1, 10, 1)
        .withDescription("Size of the particles").showIf { anyParticles() }
    private val amount by SliderSetting("Amount", 2, 1, 5, 1)
        .withDescription("Particles spawned per tick behind a moving player (pearls and arrows always spawn 5)").showIf { anyParticles() }
    private val speed by SliderSetting("Speed", 2, 1, 20, 1)
        .withDescription("How fast particles drift away").showIf { anyParticles() }
    private val lifeTime by SliderSetting("Life Time", 2, 1, 10, 1, "s")
        .withDescription("How long particles stay").showIf { anyParticles() }

    private val colorMode by DropdownSetting("Color Mode", 1, listOf("Custom", "Rainbow"))
        .withDescription("Rainbow: ribbons, particles and flight paths cycle through the rainbow. Custom: uses Trail Color and Particle Color").section("Colors")
    private val particleColor by ColorSetting("Particle Color", Color(0x50, 0xB4, 0xB4))
        .withDescription("Colour of particles and predicted flight paths in Custom colour mode").showIf { colorMode.value == 0 }

    private fun anyParticles() = players.value == PARTICLES || pearls.value == PARTICLES || arrows.value == PARTICLES

    private class Segment(val x: Double, val y: Double, val z: Double, val color: Color) {
        var ticks = SEGMENT_TICKS
        var prevTicks = SEGMENT_TICKS
        fun animation(pt: Float) = (prevTicks + (ticks - prevTicks) * pt) / SEGMENT_TICKS
    }

    /** Player -> points they passed (oldest first). The first point of a fresh trail is its start position. */
    private val trails = WeakHashMap<Entity, ArrayDeque<Segment>>()
    private val particles = ArrayList<EffectParticle>()
    /** Projectile -> predicted path (tick positions), rebuilt every tick. */
    private val paths = HashMap<Entity, List<Vec3>>()

    override fun init() {
        register<WorldChangeEvent> { clear() }
        register<EntityUnloadEvent> {
            trails.remove(event.entity)
            paths.remove(event.entity)
        }

        register<TickEvent.End> {
            val level = mc.level ?: return@register
            val self = mc.player ?: return@register

            ageTrails()
            tickParticles(level)
            paths.clear()

            if (players.value != PLAYER_NONE) {
                val targets: List<Player> = if (onlySelf.value) listOf(self) else level.players()
                for (player in targets) {
                    if (player.x == player.xo && player.z == player.zo) continue
                    if (player.isInvisible && player !== self) continue
                    if (players.value == PARTICLES) {
                        repeat(amount.value) { spawnParticle(player.x, Random.nextDouble(player.y, player.y + player.bbHeight), player.z) }
                        continue
                    }
                    val deque = trails.getOrPut(player) { ArrayDeque() }
                    val color = if (colorMode.value == 1) EffectColors.sync(0) else trailColor.value
                    if (deque.isEmpty()) deque.addLast(Segment(player.xo, player.yo, player.zo, color))
                    deque.addLast(Segment(player.x, player.y, player.z, color))
                }
            }

            for (entity in level.entitiesForRendering()) {
                val setting = when (entity) {
                    is ThrownEnderpearl -> pearls.value
                    is AbstractArrow -> arrows.value
                    is ThrownExperienceBottle -> if (xp.value) TRAIL else PROJECTILE_NONE
                    else -> continue
                }
                val moving = entity.y != entity.yo || entity.x != entity.xo || entity.z != entity.zo
                when (setting) {
                    PARTICLES -> if (entity !is AbstractArrow || moving) repeat(5) { spawnParticle(entity.x, entity.y, entity.z) }
                    TRAIL -> if (moving) paths[entity] = predict(level, entity)
                }
            }
        }

        register<RenderWorldEvent> {
            val ctx = event.ctx
            val pt = RenderHelper.partialTicks
            if (trails.isNotEmpty()) when (players.value) {
                TRAIL -> renderRibbons(ctx, pt, cute = false)
                CUTE -> renderRibbons(ctx, pt, cute = true)
                TAIL -> renderTails(ctx, pt)
            }
            if (particles.isNotEmpty()) renderParticles(ctx, pt)
            if (paths.isNotEmpty()) renderPaths(ctx, pt)
            EffectBatcher.flush(ctx.collector)
        }
    }

    override fun onDisable() {
        super.onDisable()
        clear()
    }

    private fun clear() {
        trails.clear()
        particles.clear()
        paths.clear()
    }

    // ------------------------------------------------------------------------------------------------ simulation

    private fun ageTrails() {
        val iterator = trails.values.iterator()
        while (iterator.hasNext()) {
            val deque = iterator.next()
            for (segment in deque) {
                segment.prevTicks = segment.ticks
                segment.ticks--
            }
            while (deque.isNotEmpty() && deque.first().ticks <= 0) deque.removeFirst()
            if (deque.isEmpty()) iterator.remove()
        }
        if (players.value == PARTICLES || players.value == PLAYER_NONE) trails.clear()
    }

    private fun tickParticles(level: ClientLevel) {
        val now = System.currentTimeMillis()
        particles.removeIf { it.expired(now) }
        val fall = physics.value == 0
        val radius = scale.value / 10.0
        for (p in particles) p.tick(level, 0.0005, radius, fall)
    }

    private fun spawnParticle(x: Double, y: Double, z: Double) {
        if (particles.size >= MAX_PARTICLES) particles.removeAt(0)
        val s = speed.value / 200.0
        particles += EffectParticle(
            x, y, z,
            Random.nextDouble(- s, s), Random.nextDouble(- s, s), Random.nextDouble(- s, s),
            lifeTime.value * 1000L, particleColor.value,
            roll = Random.nextFloat() * 180f,
            rollSpeed = 10f + Random.nextFloat() * 50f
        )
    }

    /** Steps the projectile's flight forward until it hits a block or an entity (max 300 ticks). */
    private fun predict(level: ClientLevel, entity: Entity): List<Vec3> {
        val gravity = if (entity is AbstractArrow) 0.05 else 0.03
        val owner = (entity as? Projectile)?.owner
        val points = ArrayList<Vec3>()
        var pos = entity.position()
        var vel = entity.deltaMovement
        points += pos
        val minY = level.minY - 1.0
        val mutable = BlockPos.MutableBlockPos()

        for (i in 0 until 300) {
            val next = pos.add(vel)
            val blockHit = level.clip(ClipContext(pos, next, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, entity))
            var end = if (blockHit.type != HitResult.Type.MISS) blockHit.location else null

            val sweep = AABB(pos, next).inflate(0.3)
            for (target in level.getEntities(entity, sweep) { it is LivingEntity && it !== owner && it.isAlive && ! it.isSpectator }) {
                val hit = target.boundingBox.inflate(0.3).clip(pos, next).orElse(null) ?: continue
                if (end == null || hit.distanceToSqr(pos) < end.distanceToSqr(pos)) end = hit
            }

            if (end != null) {
                points += end
                break
            }
            points += next
            pos = next

            val inWater = ! level.getFluidState(mutable.set(pos.x, pos.y, pos.z)).isEmpty
            vel = vel.scale(if (inWater) 0.8 else 0.99).add(0.0, - gravity, 0.0)
            if (pos.y <= minY) break
        }
        return points
    }

    // ------------------------------------------------------------------------------------------------ rendering

    private val cuteColors = arrayOf(
        Color(69, 221, 255), Color(248, 139, 160), Color(255, 255, 255), Color(248, 139, 160), Color(69, 221, 255)
    )

    private class RibbonPoint(val x: Double, val y: Double, val z: Double, val alpha: Float, val color: Color)

    private fun ribbonPoints(entity: Entity, deque: ArrayDeque<Segment>, pt: Float): List<RibbonPoint> {
        val baseAlpha = trailColor.value.alpha / 255f
        val list = ArrayList<RibbonPoint>(deque.size + 1)
        for (segment in deque) list += RibbonPoint(segment.x, segment.y, segment.z, baseAlpha * segment.animation(pt).coerceIn(0f, 1f), segment.color)
        val head = deque.last()
        list += RibbonPoint(entity.renderX, entity.renderY, entity.renderZ, baseAlpha, head.color)
        return list
    }

    private fun renderRibbons(ctx: RenderContext, pt: Float, cute: Boolean) {
        val plane = EffectPlane(ctx)
        val batch = EffectBatcher.batch(additive = false, seeThrough = false)
        val cam = ctx.camera.pos
        val pose = plane.pose

        for ((entity, deque) in trails) {
            if (deque.isEmpty() || entity.isRemoved) continue
            val points = ribbonPoints(entity, deque, pt)

            val strips: List<Triple<Double, Double, Color?>> =
                if (cute) {
                    val h = entity.bbHeight / 5.0
                    List(5) { Triple(it * h, (it + 1) * h, cuteColors[it]) }
                }
                else listOf(Triple(down.value.toDouble(), (down.value + height.value).toDouble(), null))

            for ((bottom, top, fixed) in strips) {
                for (i in 0 until points.size - 1) {
                    val a = points[i]
                    val b = points[i + 1]
                    val ca = fixed ?: a.color
                    val cb = fixed ?: b.color
                    val ax = (a.x - cam.x).toFloat()
                    val az = (a.z - cam.z).toFloat()
                    val bx = (b.x - cam.x).toFloat()
                    val bz = (b.z - cam.z).toFloat()
                    batch.vertex(pose, ax, (a.y + bottom - cam.y).toFloat(), az, ca.red / 255f, ca.green / 255f, ca.blue / 255f, a.alpha)
                    batch.vertex(pose, ax, (a.y + top - cam.y).toFloat(), az, ca.red / 255f, ca.green / 255f, ca.blue / 255f, a.alpha)
                    batch.vertex(pose, bx, (b.y + top - cam.y).toFloat(), bz, cb.red / 255f, cb.green / 255f, cb.blue / 255f, b.alpha)
                    batch.vertex(pose, bx, (b.y + bottom - cam.y).toFloat(), bz, cb.red / 255f, cb.green / 255f, cb.blue / 255f, b.alpha)
                }
            }
        }
    }

    private fun renderTails(ctx: RenderContext, pt: Float) {
        val plane = EffectPlane(ctx)
        plane.batch = EffectBatcher.batch(additive = true, seeThrough = false)
        val white = Color.WHITE

        for ((entity, deque) in trails) {
            if (deque.isEmpty() || entity.isRemoved) continue
            val points = ribbonPoints(entity, deque, pt)
            val size = points.size
            for ((i, p) in points.withIndex()) {
                val toWhite = (1f - i.toFloat() / size).let { it * it }
                val c = EffectColors.lerp(p.color, white, toWhite)
                plane.billboard(p.x, p.y + 0.9, p.z, 0.3f).color(Color(c.red, c.green, c.blue), p.alpha)
                EffectShapes.glow(plane, 1f, 1f, 16)
            }
        }
    }

    private fun renderParticles(ctx: RenderContext, pt: Float) {
        val plane = EffectPlane(ctx)
        plane.batch = EffectBatcher.batch(additive = true, seeThrough = false)
        val shape = when (sprite.value) {
            1 -> EffectShapes.Sprite.HEART
            2 -> EffectShapes.Sprite.BLOOM
            else -> EffectShapes.Sprite.STAR
        }
        val half = scale.value / 20f
        val now = System.currentTimeMillis()
        val rainbow = colorMode.value == 1

        for (p in particles) {
            val age = p.age(now)
            val c = if (rainbow) EffectColors.hue(age) else p.color
            plane.billboard(p.ix(pt), p.iy(pt), p.iz(pt), half, p.rollAt(now)).color(c, 1f - age)
            EffectShapes.sprite(plane, shape)
        }
    }

    private fun renderPaths(ctx: RenderContext, pt: Float) {
        val cam = ctx.camera.pos
        ctx.matrixStack.pushPose()
        ctx.matrixStack.translate(cam.reverse())
        val pose = ctx.uMatrixStack()
        val batch = RenderBatcher.lineBatch(false)
        val rainbow = colorMode.value == 1
        val custom = particleColor.value

        for ((entity, path) in paths) {
            if (path.size < 2 || entity.isRemoved) continue
            for (i in 1 until path.size) {
                val from = if (i == 1) Vec3(entity.renderX, entity.renderY, entity.renderZ) else path[i - 1]
                val to = path[i]
                if (from == to) continue
                val c = if (rainbow) EffectColors.sync(i * 5) else custom
                val alpha = (i / 8f).coerceIn(0f, 1f) * c.alpha / 255f
                batch.line(
                    pose,
                    from.x.toFloat(), from.y.toFloat(), from.z.toFloat(),
                    to.x.toFloat(), to.y.toFloat(), to.z.toFloat(),
                    c.red / 255f, c.green / 255f, c.blue / 255f, alpha, 2f
                )
            }
        }
        ctx.matrixStack.popPose()
    }
}
