package com.laybalt.features.impl.render

import com.laybalt.config.types.ColorSetting
import com.laybalt.config.types.DropdownSetting
import com.laybalt.config.types.SliderSetting
import com.laybalt.config.types.ToggleSetting
import com.laybalt.event.impl.MainThreadPacketReceivedEvent
import com.laybalt.event.impl.PacketEvent
import com.laybalt.event.impl.RenderOverlayEvent
import com.laybalt.event.impl.TickEvent
import com.laybalt.event.impl.WorldChangeEvent
import com.laybalt.features.Feature
import com.laybalt.utils.ColorUtils.withAlpha
import com.laybalt.utils.render.Render2D.drawAnnularSegment
import com.laybalt.utils.render.Render2D.drawLine
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket
import net.minecraft.network.protocol.game.ClientboundGameEventPacket
import net.minecraft.network.protocol.game.ClientboundHurtAnimationPacket
import net.minecraft.network.protocol.game.ClientboundSoundPacket
import net.minecraft.network.protocol.game.ServerboundAttackPacket
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.entity.LivingEntity
import java.awt.Color
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Hit marker at the crosshair.
 *
 * Melee: an attack packet you send marks the target as pending; the marker appears once the server confirms the hit
 * (damage event or hurt animation for that entity) or immediately when "Require Confirmation" is off.
 * Ranged: a damage event whose cause is you but whose direct source is not (arrows, other projectiles), the
 * "arrow hit" game event, or the arrow-hit ding sound the server plays to the shooter.
 * Kill: an entity you hit recently dies (death event or health reaching 0).
 */
object HitIndicator: Feature("Shows a hit marker at your crosshair when your melee attacks or arrows hit, with a separate colour for kills.") {
    private const val X_MARK = 0
    private const val PLUS = 1
    private const val RING = 2
    private const val BRACKETS = 3
    private const val BURST = 4

    private const val PENDING_TIMEOUT_MS = 750L
    private const val KILL_WINDOW_MS = 1500L

    private val shape by DropdownSetting("Shape", X_MARK, listOf("X Marker", "Plus", "Ring", "Brackets", "Burst"))
        .withDescription("X Marker: four diagonal ticks. Plus: four straight ticks. Ring: a circle. Brackets: four corner brackets. Burst: dots flying outwards.")
    private val size by SliderSetting("Size", 5.0, 1.0, 20.0, 0.5)
        .withDescription("Length of the marker lines (or the ring and burst radius), in GUI pixels.")
    private val gap by SliderSetting("Gap", 3.0, 0.0, 15.0, 0.5)
        .withDescription("Empty space between the crosshair centre and the marker.")
    private val thickness by SliderSetting("Thickness", 1.5, 0.5, 5.0, 0.25)
        .withDescription("Line thickness of the marker.")
    private val duration by SliderSetting("Duration", 400, 100, 1500, 25, " ms")
        .withDescription("How long the marker stays on screen. It fades out over this time.")
    private val pop by ToggleSetting("Pop Animation", true)
        .withDescription("The marker starts slightly bigger and snaps into place, which makes hits feel punchier.")

    private val color by ColorSetting("Color", Color(255, 255, 255))
        .withDescription("Marker colour for melee hits.")
        .section("Colors")
    private val outline by ToggleSetting("Outline", true)
        .withDescription("Draws a dark outline behind the marker so it stays visible on bright backgrounds.")
    private val outlineColor by ColorSetting("Outline Color", Color(0, 0, 0, 140))
        .withDescription("Colour of the outline.")
        .showIf { outline.value }

    private val requireConfirm by ToggleSetting("Require Confirmation", true)
        .withDescription("Only shows a melee marker after the server confirms the target took damage. When off, every attack you send shows a marker, even if it did no damage.")
        .section("Hits")
    private val arrowHits by ToggleSetting("Arrow Hits", true)
        .withDescription("Also shows a marker when your arrows or other projectiles hit.")
    private val arrowColor by ColorSetting("Arrow Color", Color(255, 210, 80))
        .withDescription("Marker colour for projectile hits.")
        .showIf { arrowHits.value }
    private val killMarker by ToggleSetting("Kill Marker", true)
        .withDescription("Shows a bigger marker in another colour when an entity you hit dies.")
    private val killColor by ColorSetting("Kill Color", Color(255, 60, 60))
        .withDescription("Marker colour for kills.")
        .showIf { killMarker.value }

    private class Marker(val time: Long, val color: Color, val scale: Float)

    private var marker: Marker? = null
    private val pending = LinkedHashMap<Int, Long>()
    private val recentHits = LinkedHashMap<Int, Long>()

    override fun init() {
        register<WorldChangeEvent> { reset() }

        register<PacketEvent.Sent> {
            val packet = event.packet as? ServerboundAttackPacket ?: return@register
            val id = packet.entityId
            mc.execute { onAttack(id) }
        }

        register<MainThreadPacketReceivedEvent.Post> {
            val selfId = mc.player?.id ?: return@register
            when (val packet = event.packet) {
                is ClientboundDamageEventPacket -> {
                    val target = packet.entityId()
                    if (target == selfId) return@register
                    if (confirm(target)) return@register
                    if (arrowHits.value && packet.sourceCauseId() == selfId && packet.sourceDirectId() != selfId) {
                        show(target, arrowColor.value)
                    }
                }
                is ClientboundHurtAnimationPacket -> if (packet.id() != selfId) confirm(packet.id())
                is ClientboundGameEventPacket -> if (arrowHits.value && packet.event == ClientboundGameEventPacket.PLAY_ARROW_HIT_SOUND) showRanged()
                is ClientboundSoundPacket -> if (arrowHits.value && packet.sound.value() == SoundEvents.ARROW_HIT_PLAYER) showRanged()
                is ClientboundEntityEventPacket -> {
                    if (packet.eventId.toInt() != 3) return@register // EntityEvent.DEATH
                    val entity = mc.level?.let { packet.getEntity(it) } ?: return@register
                    onDeath(entity.id)
                }
            }
        }

        register<TickEvent.End> {
            val now = System.currentTimeMillis()
            pending.values.removeIf { now - it > PENDING_TIMEOUT_MS }
            recentHits.values.removeIf { now - it > KILL_WINDOW_MS }
            if (! killMarker.value || recentHits.isEmpty()) return@register
            val level = mc.level ?: return@register
            val dead = recentHits.keys.filter { id -> (level.getEntity(id) as? LivingEntity)?.isDeadOrDying == true }
            dead.forEach(::onDeath)
        }

        register<RenderOverlayEvent> { render(event.context) }
    }

    override fun onDisable() {
        super.onDisable()
        reset()
    }

    private fun reset() {
        marker = null
        pending.clear()
        recentHits.clear()
    }

    private fun onAttack(id: Int) {
        if (! enabled) return
        if (requireConfirm.value) pending[id] = System.currentTimeMillis()
        else show(id, color.value)
    }

    /** Shows the melee marker if [id] was attacked recently. */
    private fun confirm(id: Int): Boolean {
        val time = pending.remove(id) ?: return false
        if (System.currentTimeMillis() - time > PENDING_TIMEOUT_MS) return false
        show(id, color.value)
        return true
    }

    private fun showRanged() {
        // The game event and the ding sound carry no target, and usually come together with a damage event.
        val current = marker
        if (current != null && System.currentTimeMillis() - current.time < 100) return
        show(null, arrowColor.value)
    }

    private fun show(target: Int?, c: Color) {
        val now = System.currentTimeMillis()
        if (target != null) recentHits[target] = now
        marker = Marker(now, c, 1f)
    }

    private fun onDeath(id: Int) {
        if (recentHits.remove(id) == null || ! killMarker.value) return
        marker = Marker(System.currentTimeMillis(), killColor.value, 1.5f)
    }

    private fun render(ctx: GuiGraphicsExtractor) {
        val m = marker ?: return
        val age = (System.currentTimeMillis() - m.time).toFloat()
        val life = duration.value.toFloat()
        if (age >= life) {
            marker = null
            return
        }
        val t = age / life
        val alpha = if (t < 0.35f) 1f else 1f - (t - 0.35f) / 0.65f
        val popScale = if (pop.value) 1f + 0.5f * (1f - (age / 90f).coerceIn(0f, 1f)) else 1f
        val scale = m.scale * popScale

        val cx = ((ctx.guiWidth() - 15) / 2) + 7.5f
        val cy = ((ctx.guiHeight() - 15) / 2) + 7.5f

        if (outline.value) {
            val oc = outlineColor.value
            drawShape(ctx, cx, cy, scale, t, oc.withAlpha(oc.alpha / 255f * alpha), 1f)
        }
        val c = m.color
        drawShape(ctx, cx, cy, scale, t, c.withAlpha(c.alpha / 255f * alpha), 0f)
    }

    private fun drawShape(ctx: GuiGraphicsExtractor, cx: Float, cy: Float, scale: Float, progress: Float, c: Color, grow: Float) {
        val len = size.value.toFloat() * scale
        val g = gap.value.toFloat() * scale
        val th = thickness.value.toFloat()

        fun line(x1: Float, y1: Float, x2: Float, y2: Float) {
            val dx = x2 - x1
            val dy = y2 - y1
            val d = sqrt(dx * dx + dy * dy).takeIf { it > 0f } ?: return
            val ex = dx / d * grow
            val ey = dy / d * grow
            ctx.drawLine(x1 - ex, y1 - ey, x2 + ex, y2 + ey, c, th + grow * 2)
        }

        when (shape.value) {
            X_MARK -> {
                val k = (1.0 / sqrt(2.0)).toFloat()
                for ((sx, sy) in DIAGONALS) line(cx + sx * g * k, cy + sy * g * k, cx + sx * (g + len) * k, cy + sy * (g + len) * k)
            }
            PLUS -> {
                line(cx, cy - g, cx, cy - g - len)
                line(cx, cy + g, cx, cy + g + len)
                line(cx - g, cy, cx - g - len, cy)
                line(cx + g, cy, cx + g + len, cy)
            }
            RING -> {
                val r = g + len / 2f
                val ht = th / 2f
                ctx.drawAnnularSegment(cx, cy, (r - ht - grow).coerceAtLeast(0f), r + ht + grow, 0.0, 2 * PI, c)
            }
            BRACKETS -> {
                val h = g + len / 2f
                val arm = len / 2f
                for ((sx, sy) in DIAGONALS) {
                    val x = cx + sx * h
                    val y = cy + sy * h
                    line(x, y, x - sx * arm, y)
                    line(x, y, x, y - sy * arm)
                }
            }
            BURST -> {
                val r = g + len * (0.3f + 0.7f * progress)
                val dot = th + 0.5f
                for (i in 0 until 8) {
                    val a = i * PI / 4 + PI / 8
                    val x = cx + (cos(a) * r).toFloat()
                    val y = cy + (sin(a) * r).toFloat()
                    line(x - dot / 2f, y, x + dot / 2f, y)
                }
            }
        }
    }

    private val DIAGONALS = listOf(1f to 1f, - 1f to 1f, 1f to - 1f, - 1f to - 1f)
}
