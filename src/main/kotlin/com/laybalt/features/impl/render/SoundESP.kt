package com.laybalt.features.impl.render

import com.laybalt.config.types.ColorSetting
import com.laybalt.config.types.SliderSetting
import com.laybalt.config.types.ToggleSetting
import com.laybalt.event.impl.RenderOverlayEvent
import com.laybalt.event.impl.TickEvent
import com.laybalt.event.impl.WorldChangeEvent
import com.laybalt.features.Feature
import com.laybalt.utils.render.Render2D.drawRect
import com.laybalt.utils.render.WorldToScreen
import net.minecraft.ChatFormatting
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.network.chat.Component
import java.awt.Color
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Shows a short-lived label at the position of every sound played near you
 * (fed by `MixinSoundManagerSoundESP`).
 */
object SoundESP: Feature("Shows the name of every sound played around you as a fading label where it came from.") {
    private val scale by SliderSetting("Scale", 1f, 0.1f, 10f, 0.1f)
        .withDescription("Size of the labels")
    private val color by ColorSetting("Color", Color(0, 0, 0, 0x80))
        .withDescription("Background colour of the labels")
    private val textColor by ColorSetting("Text Color", Color.WHITE, false)
        .withDescription("Colour of the sound names")
    private val duration by SliderSetting("Duration", 60, 10, 200, 5, " ticks")
        .withDescription("How long a label stays visible (20 ticks = 1 second). It fades out towards the end")
    private val range by SliderSetting("Range", 32, 4, 128, 4, " blocks")
        .withDescription("Sounds farther away than this are not shown")
    private val merge by ToggleSetting("Merge Repeats", true)
        .withDescription("A sound repeating at the same spot refreshes its label instead of stacking new ones on top")

    private class Label(val name: String, val x: Double, val y: Double, val z: Double, var ticks: Int, val total: Int)

    private val pending = ConcurrentLinkedQueue<Label>()
    private val labels = ArrayList<Label>()

    @JvmStatic
    fun onSound(instance: SoundInstance) {
        if (instance.isRelative) return
        val player = mc.player ?: return
        val x = instance.x
        val y = instance.y
        val z = instance.z
        val max = range.value.toDouble()
        if (player.distanceToSqr(x, y, z) > max * max) return
        if (pending.size > 256) return
        val total = duration.value
        pending.add(Label(prettyName(instance.identifier.path), x, y, z, total, total))
    }

    private fun prettyName(path: String): String {
        var name = path
        for (prefix in arrayOf("block.", "entity.", "item.", "ambient.")) if (name.startsWith(prefix)) {
            name = name.removePrefix(prefix)
            break
        }
        return name.replace('.', ' ').replace('_', ' ')
    }

    override fun init() {
        register<WorldChangeEvent> {
            pending.clear()
            labels.clear()
        }

        register<TickEvent.End> {
            labels.removeAll { -- it.ticks <= 0 }
            while (true) {
                val label = pending.poll() ?: break
                if (merge.value) labels.removeAll {
                    it.name == label.name && (it.x - label.x) * (it.x - label.x) + (it.y - label.y) * (it.y - label.y) + (it.z - label.z) * (it.z - label.z) < 0.25
                }
                labels.add(label)
            }
            while (labels.size > 200) labels.removeAt(0)
        }

        register<RenderOverlayEvent> {
            if (labels.isEmpty()) return@register
            val gui = event.context
            val proj = WorldToScreen.begin(gui) ?: return@register
            val s = scale.value
            val bg = color.value
            val fg = textColor.value

            for (label in labels) {
                val p = proj.project(label.x, label.y, label.z) ?: continue
                if (p.depth >= 1f || ! proj.onScreen(p, 50f)) continue

                val life = label.ticks.toFloat() / label.total
                val inv = 1f - life
                val alpha = (1f - inv * inv * inv).coerceIn(0f, 1f)
                if (alpha <= 0.02f) continue

                val text = Component.literal(label.name).withStyle(ChatFormatting.BOLD)
                val w = mc.font.width(text) + 4f
                val h = 11f
                val cy = p.y - 13f

                gui.pose().pushMatrix()
                gui.pose().translate(p.x, cy)
                gui.pose().scale(s, s)
                gui.drawRect(- w / 2f, - h / 2f, w, h, Color(bg.red, bg.green, bg.blue, (bg.alpha * alpha).toInt().coerceIn(0, 255)))
                val textAlpha = (255 * alpha).toInt().coerceIn(4, 255)
                gui.text(mc.font, text, (- w / 2f + 2f).toInt(), (- h / 2f + 2f).toInt(), (fg.rgb and 0xFFFFFF) or (textAlpha shl 24), false)
                gui.pose().popMatrix()
            }
        }
    }

    override fun onDisable() {
        super.onDisable()
        pending.clear()
        labels.clear()
    }
}
