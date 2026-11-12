package com.laybalt.features.impl.visual

import com.laybalt.config.types.ColorSetting
import com.laybalt.config.types.DropdownSetting
import com.laybalt.config.types.SliderSetting
import com.laybalt.config.types.ToggleSetting
import com.laybalt.event.impl.MainThreadPacketReceivedEvent
import com.laybalt.event.impl.WorldChangeEvent
import com.laybalt.features.Feature
import com.laybalt.utils.ChatUtils.removeFormatting
import com.laybalt.utils.location.LocationUtils
import com.laybalt.utils.render.Render2D.drawString
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.Hud
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.core.Holder
import net.minecraft.network.protocol.game.ClientboundTabListPacket
import net.minecraft.world.effect.MobEffect
import net.minecraft.world.effect.MobEffects
import java.awt.Color

object EffectsHud: Feature("Lists your active potion effects with their remaining time. Handles long SkyBlock (God Potion) durations.", name = "Effects HUD") {
    private val showIcons by ToggleSetting("Show Icons", true).withDescription("Draws the effect icon in front of each line.").section("Content")
    private val showLevel by ToggleSetting("Show Level", true).withDescription("Adds the effect level in roman numerals, e.g. Speed II.")
    private val compact by ToggleSetting("Compact", false).withDescription("Hides the effect names and only shows icon, level and time.")
    private val sortMode by DropdownSetting("Sort By", 0, listOf("Time Left", "Name", "Beneficial First", "None")).withDescription("Order of the effect lines.")
    private val maxLines by SliderSetting("Max Lines", 12, 1, 30, 1).withDescription("Most effect lines shown at once; the rest are summarised as \"+N more\".")

    private val hideInfinite by ToggleSetting("Hide Infinite", false).withDescription("Hides effects without an end time.").section("SkyBlock")
    private val hideLongerThan by SliderSetting("Hide Longer Than", 0, 0, 720, 10, "m").withDescription("Hides effects lasting longer than this many minutes (0 = show all). Useful to hide God Potion effects.")
    private val godPotion by ToggleSetting("God Potion Timer", true).withDescription("In SkyBlock, shows the God Potion time read from the tab list footer.")
    private val skyblockCount by ToggleSetting("SkyBlock Effect Count", false).withDescription("In SkyBlock, shows how many SkyBlock effects the tab list footer reports.")

    private val blinkEnding by ToggleSetting("Blink When Ending", true).withDescription("Blinks the time of effects that are about to run out.").section("Colors")
    private val endingSeconds by SliderSetting("Ending Threshold", 10, 3, 60, 1, "s").withDescription("Seconds left at which an effect counts as ending.").showIf { blinkEnding.value }
    private val effectColors by ToggleSetting("Effect Colors", true).withDescription("Colours each effect name with the effect's own colour.")
    private val nameColor by ColorSetting("Name Color", HudWidgetKit.defaultText, false).withDescription("Colour of the effect names when Effect Colors is off.").showIf { ! effectColors.value }
    private val timeColor by ColorSetting("Time Color", HudWidgetKit.defaultMuted, false).withDescription("Colour of the remaining time.")
    private val endingColor by ColorSetting("Ending Color", Color(240, 90, 100), false).withDescription("Colour of the time while an effect is ending.").showIf { blinkEnding.value }
    private val godPotionColor by ColorSetting("God Potion Color", Color(255, 110, 110), false).withDescription("Colour of the God Potion line.").showIf { godPotion.value }
    private val textShadow by ToggleSetting("Text Shadow", true).withDescription("Draws the text with a shadow.")

    private val bgMode by DropdownSetting("Background", 3, HudWidgetKit.BACKGROUND_MODES).withDescription("Panel drawn behind the whole widget.").section("Panel")
    private val bgColor by ColorSetting("Background Color", HudWidgetKit.defaultBackground).withDescription("Colour of the panel behind the widget.").showIf { bgMode.value != 0 }
    private val accentColor by ColorSetting("Accent Color", HudWidgetKit.defaultAccent).withDescription("Colour of the panel outline, accent bar or glass hairline.").showIf { bgMode.value >= 2 }

    private val godPotionRegex = Regex("You have a God Potion active! (.+)")
    private val effectCountRegex = Regex("You have (\\d+) active effects?")
    private var godPotionTime: String? = null
    private var skyblockEffects: Int? = null

    private class Row(val icon: Holder<MobEffect>?, val name: String, val level: String, val color: Color, val time: String, val ending: Boolean) {
        fun label(compact: Boolean) = if (compact) level else if (level.isEmpty()) name else "$name $level"
    }

    override fun init() {
        hudElement("EffectsHud", shouldDraw = { mc.player != null }) { ctx, example -> draw(ctx, example) } defaults {
            x = 140f
            y = 84f
        }

        register<MainThreadPacketReceivedEvent.Post> {
            val packet = event.packet as? ClientboundTabListPacket ?: return@register
            if (! LocationUtils.inSkyblock) {
                godPotionTime = null
                skyblockEffects = null
                return@register
            }
            val lines = packet.footer.string.removeFormatting().lines().map(String::trim)
            godPotionTime = lines.firstNotNullOfOrNull { godPotionRegex.find(it)?.groupValues?.get(1)?.trim() }
            skyblockEffects = lines.firstNotNullOfOrNull { effectCountRegex.find(it)?.groupValues?.get(1)?.toIntOrNull() }
        }

        register<WorldChangeEvent> {
            godPotionTime = null
            skyblockEffects = null
        }
    }

    private fun liveRows(): List<Row> {
        val player = mc.player ?: return emptyList()
        val limitTicks = hideLongerThan.value * 1200L
        val effects = player.activeEffects.filter { inst ->
            if (inst.isInfiniteDuration) ! hideInfinite.value
            else limitTicks <= 0 || inst.duration <= limitTicks
        }
        val sorted = when (sortMode.value) {
            0 -> effects.sortedBy { if (it.isInfiniteDuration) Int.MAX_VALUE else it.duration }
            1 -> effects.sortedBy { it.effect.value().displayName.string }
            2 -> effects.sortedWith(compareByDescending<net.minecraft.world.effect.MobEffectInstance> { it.effect.value().isBeneficial }.thenBy { it.duration })
            else -> effects
        }
        return sorted.map { inst ->
            val effect = inst.effect.value()
            val level = if (showLevel.value && inst.amplifier > 0) HudWidgetKit.roman(inst.amplifier + 1) else ""
            val time = if (inst.isInfiniteDuration) "∞" else HudWidgetKit.formatDuration(inst.duration * 50L)
            val ending = ! inst.isInfiniteDuration && inst.duration <= endingSeconds.value * 20
            Row(inst.effect, effect.displayName.string, level, Color(effect.color), time, ending)
        }
    }

    private val exampleRows by lazy {
        listOf(
            Row(MobEffects.SPEED, "Speed", "II", Color(MobEffects.SPEED.value().color), "3:12", false),
            Row(MobEffects.STRENGTH, "Strength", "", Color(MobEffects.STRENGTH.value().color), "1h 02m 10s", false),
            Row(MobEffects.NIGHT_VISION, "Night Vision", "", Color(MobEffects.NIGHT_VISION.value().color), "0:07", true)
        )
    }

    private fun draw(ctx: GuiGraphicsExtractor, example: Boolean): Pair<Number, Number> {
        var rows = liveRows()
        if (example && rows.isEmpty()) rows = exampleRows

        val extra = mutableListOf<Pair<String, Color>>()
        if (godPotion.value) (godPotionTime ?: if (example) "4d 13h" else null)?.let { extra += "God Potion  $it" to godPotionColor.value }
        if (skyblockCount.value) (skyblockEffects ?: if (example) 23 else null)?.let { extra += "$it SkyBlock effects" to timeColor.value }

        val overflow = (rows.size - maxLines.value).coerceAtLeast(0)
        if (overflow > 0) rows = rows.take(maxLines.value)
        if (rows.isEmpty() && extra.isEmpty()) return 60 to 10

        val font = mc.font
        val pad = HudWidgetKit.padding(bgMode.value)
        val left = HudWidgetKit.leftPadding(bgMode.value)
        val lineH = 11
        val iconW = if (showIcons.value) 11 else 0
        val shownNames = rows.map { it.label(compact.value) }

        var contentW = 0
        rows.forEachIndexed { i, row ->
            val nameW = if (shownNames[i].isEmpty()) 0 else font.width(shownNames[i]) + 4
            contentW = maxOf(contentW, iconW + nameW + font.width(row.time))
        }
        extra.forEach { contentW = maxOf(contentW, font.width(it.first)) }
        val moreText = if (overflow > 0) "+$overflow more" else null
        moreText?.let { contentW = maxOf(contentW, font.width(it)) }

        val lines = extra.size + rows.size + (if (moreText != null) 1 else 0)
        val contentH = lines * lineH - 2
        val totalW = contentW + left + pad
        val totalH = contentH + pad * 2
        HudWidgetKit.drawBackground(ctx, totalW, totalH, bgMode.value, bgColor.value, accentColor.value)

        val blinkOn = (System.currentTimeMillis() / 400) % 2 == 0L
        var y = pad
        for ((text, color) in extra) {
            ctx.drawString(text, left, y + 1, color, shadow = textShadow.value)
            y += lineH
        }
        rows.forEachIndexed { i, row ->
            var x = left
            if (showIcons.value && row.icon != null) {
                ctx.blitSprite(RenderPipelines.GUI_TEXTURED, Hud.getMobEffectSprite(row.icon), x, y, 9, 9)
            }
            x += iconW
            val name = shownNames[i]
            if (name.isNotEmpty()) {
                ctx.drawString(name, x, y + 1, if (effectColors.value) row.color else nameColor.value, shadow = textShadow.value)
                x += font.width(name) + 4
            }
            val timeCol = if (row.ending && blinkEnding.value) (if (blinkOn) endingColor.value else timeColor.value) else timeColor.value
            ctx.drawString(row.time, x, y + 1, timeCol, shadow = textShadow.value)
            y += lineH
        }
        moreText?.let { ctx.drawString(it, left, y + 1, timeColor.value, shadow = textShadow.value) }

        return totalW to totalH
    }
}
