package com.laybalt.features.impl.visual

import com.laybalt.config.types.ColorSetting
import com.laybalt.config.types.DropdownSetting
import com.laybalt.config.types.SliderSetting
import com.laybalt.config.types.ToggleSetting
import com.laybalt.event.impl.MainThreadPacketReceivedEvent
import com.laybalt.event.impl.WorldChangeEvent
import com.laybalt.features.Feature
import com.laybalt.ui.render.ItemTextures
import com.laybalt.utils.ThreadUtils
import com.laybalt.utils.items.ItemUtils.skyblockId
import com.laybalt.utils.render.Render2D.drawString
import com.laybalt.utils.uppercaseFirst
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket
import java.awt.Color

object ArrowPoison: Feature("Shows on screen the amount of poison arrows you have in inventory") {
    private val layout by DropdownSetting("Layout", 0, listOf("Vertical", "Horizontal")).withDescription("Stack the poison types in a column or in a row.").section("Layout")
    private val showIcons by ToggleSetting("Show Icons", true).withDescription("Draws the dye icon of each poison type.")
    private val showNames by ToggleSetting("Show Names", true).withDescription("Writes \"Twilight\" / \"Toxic\" in front of the count.")
    private val hideEmpty by ToggleSetting("Hide Empty Types", true).withDescription("Only lists the poison types you actually carry.")

    private val lowWarning by ToggleSetting("Low Warning", true).withDescription("Colours the count when you are running out.").section("Warning")
    private val lowThreshold by SliderSetting("Low Below", 32, 1, 256, 1).withDescription("Counts below this use the low colour.").showIf { lowWarning.value }
    private val lowColor by ColorSetting("Low Color", Color(240, 80, 90), false).withDescription("Colour of a count that is running low.").showIf { lowWarning.value }

    private val twilightColor by ColorSetting("Twilight Color", Color(205, 120, 255), false).withDescription("Colour of the Twilight label.").section("Colors")
    private val toxicColor by ColorSetting("Toxic Color", Color(120, 230, 110), false).withDescription("Colour of the Toxic label.")
    private val countColor by ColorSetting("Count Color", HudWidgetKit.defaultText, false).withDescription("Colour of the counts.")
    private val textShadow by ToggleSetting("Text Shadow", true).withDescription("Draws the text with a shadow.")

    private val bgMode by DropdownSetting("Background", 0, HudWidgetKit.BACKGROUND_MODES).withDescription("Panel drawn behind the whole widget.").section("Panel")
    private val bgColor by ColorSetting("Background Color", HudWidgetKit.defaultBackground).withDescription("Colour of the panel behind the widget.").showIf { bgMode.value != 0 }
    private val accentColor by ColorSetting("Accent Color", HudWidgetKit.defaultAccent).withDescription("Colour of the panel outline, accent bar or glass hairline.").showIf { bgMode.value >= 2 }

    override fun init() {
        hudElement(name, shouldDraw = { PoisonArrow.entries.any { it.count > 0 } }) { ctx, example -> draw(ctx, example) } defaults {
            x = 5f
            y = 385f
        }

        register<MainThreadPacketReceivedEvent.Post> {
            val packet = event.packet as? ClientboundContainerSetSlotPacket ?: return@register
            if (packet.containerId != 0) return@register
            val arrow = PoisonArrow.entries.find { it.sbid == packet.item.skyblockId } ?: return@register
            arrow.count = player.inventory.nonEquipmentItems.sumOf {
                it.takeIf { it.skyblockId == arrow.sbid }?.count ?: 0
            }
        }

        ThreadUtils.loop(1000) {
            if (enabled && mc.player != null) mc.execute {
                PoisonArrow.entries.forEach { arrow ->
                    // NoammAddons 647e57e7: the player can be gone by the time this runs on the client thread
                    arrow.count = mc.player?.inventory?.nonEquipmentItems?.sumOf {
                        it.takeIf { it.skyblockId == arrow.sbid }?.count ?: 0
                    } ?: arrow.count
                }
            }
        }

        register<WorldChangeEvent> { PoisonArrow.reset() }
    }

    private fun draw(ctx: GuiGraphicsExtractor, example: Boolean): Pair<Number, Number> {
        val font = mc.font
        val arrows = PoisonArrow.entries.filter { example || ! hideEmpty.value || it.count > 0 }
        if (arrows.isEmpty()) return 16 to 16
        val counts = arrows.associateWith { if (example && it.count == 0) it.exampleCount else it.count }

        val icon = if (showIcons.value) 16 else 0
        fun label(a: PoisonArrow) = if (showNames.value) "${a.displayName}: " else ""
        fun entryWidth(a: PoisonArrow) = icon + (if (icon > 0) 2 else 0) + font.width(label(a)) + font.width(counts.getValue(a).toString())

        val vertical = layout.value == 0
        val rowH = if (icon > 0) 16 else 10
        val gap = 6
        val contentW = if (vertical) arrows.maxOf(::entryWidth) else arrows.sumOf(::entryWidth) + gap * (arrows.size - 1)
        val contentH = if (vertical) arrows.size * rowH else rowH

        val pad = HudWidgetKit.padding(bgMode.value)
        val left = HudWidgetKit.leftPadding(bgMode.value)
        val totalW = contentW + left + pad
        val totalH = contentH + pad * 2
        HudWidgetKit.drawBackground(ctx, totalW, totalH, bgMode.value, bgColor.value, accentColor.value)

        var x = left
        var y = pad
        for (arrow in arrows) {
            val count = counts.getValue(arrow)
            var tx = x
            if (icon > 0) {
                ItemTextures.draw(ctx, arrow.texture, x.toFloat(), y.toFloat())
                tx += icon + 2
            }
            val textY = y + (rowH - 8) / 2
            val name = label(arrow)
            if (name.isNotEmpty()) {
                ctx.drawString(name, tx, textY, if (arrow == PoisonArrow.TWILIGHT) twilightColor.value else toxicColor.value, shadow = textShadow.value)
                tx += font.width(name)
            }
            val low = lowWarning.value && count < lowThreshold.value
            ctx.drawString(count.toString(), tx, textY, if (low) lowColor.value else countColor.value, shadow = textShadow.value)

            if (vertical) y += rowH else x += entryWidth(arrow) + gap
        }
        return totalW to totalH
    }

    private enum class PoisonArrow(textureName: String, val sbid: String, val exampleCount: Int) {
        TWILIGHT("purple_dye", "TWILIGHT_ARROW_POISON", 128),
        TOXIC("lime_dye", "TOXIC_ARROW_POISON", 24);

        /** Drawn from the texture, so the HUD editor preview also works before a world is loaded. */
        val texture = ItemTextures.item(textureName)
        val displayName = name.lowercase().uppercaseFirst()
        var count = 0

        companion object {
            fun reset() = entries.forEach { it.count = 0 }
        }
    }
}
