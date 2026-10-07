package com.laybalt.features.impl.visual

import com.laybalt.config.types.ColorSetting
import com.laybalt.config.types.DropdownSetting
import com.laybalt.config.types.SliderSetting
import com.laybalt.config.types.ToggleSetting
import com.laybalt.event.impl.MainThreadPacketReceivedEvent
import com.laybalt.event.impl.WorldChangeEvent
import com.laybalt.features.Feature
import com.laybalt.ui.hud.HudElement
import com.laybalt.utils.ChatUtils.formattedText
import com.laybalt.utils.ChatUtils.removeFormatting
import com.laybalt.utils.ColorUtils.withAlpha
import com.laybalt.utils.location.LocationUtils
import com.laybalt.utils.render.Render2D.drawRect
import com.laybalt.utils.render.Render2D.drawString
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.*
import net.minecraft.world.scores.DisplaySlot
import net.minecraft.world.scores.Objective
import net.minecraft.world.scores.PlayerTeam
import net.minecraft.world.scores.Scoreboard as MCScoreboard

/**
 * Draws the sidebar scoreboard as a movable HUD element in the LayBalt panel style and hides the vanilla one.
 * Lines are rebuilt only when a scoreboard / team packet arrives (or once a second as a safety net).
 *
 * @see com.laybalt.mixin.MixinHudScoreboard
 */
object Scoreboard: Feature("Draws a custom scoreboard instead of the vanilla one.") {
    private val hideServerId by ToggleSetting("Hide Server ID")
        .withDescription("Hides the server id (like 'm151AM') next to the date on the first SkyBlock line.")
    private val hideFooter by ToggleSetting("Hide Footer")
        .withDescription("Hides the last line with the server address (www.hypixel.net).")
    private val collapseBlank by ToggleSetting("Collapse Empty Lines")
        .withDescription("Merges several empty lines in a row into one.")
    private val showNumbers by ToggleSetting("Show Numbers")
        .withDescription("Shows the red score numbers on the right like vanilla.")

    private val showTitle by ToggleSetting("Show Title", true)
        .withDescription("Shows the scoreboard title at the top.").section("Layout")
    private val titleDivider by ToggleSetting("Title Divider", true)
        .withDescription("Draws a thin accent line under the title.").showIf { showTitle.value }
    private val alignment by DropdownSetting("Alignment", 0, listOf("Left", "Center", "Right"))
        .withDescription("Horizontal alignment of the lines inside the panel.")
    private val growDirection by DropdownSetting("Anchor", 1, listOf("Left Edge", "Right Edge"))
        .withDescription("Which edge stays in place when the scoreboard gets wider. Right Edge suits a scoreboard at the right side of the screen.")
    private val lineSpacing by SliderSetting("Line Spacing", 1, 0, 6, 1, "px")
        .withDescription("Extra space between lines.")
    private val padding by SliderSetting("Padding", 5, 2, 12, 1, "px")
        .withDescription("Space between the panel edge and the text.")
    private val textShadow by ToggleSetting("Text Shadow", true)
        .withDescription("Draws the text with a shadow.")

    private val bgMode by DropdownSetting("Background", 4, HudWidgetKit.BACKGROUND_MODES)
        .withDescription("Panel drawn behind the scoreboard.").section("Panel")
    private val bgColor by ColorSetting("Background Color", HudWidgetKit.defaultBackground.withAlpha(170))
        .withDescription("Colour of the panel behind the scoreboard.").showIf { bgMode.value != 0 }
    private val accentColor by ColorSetting("Accent Color", HudWidgetKit.defaultAccent)
        .withDescription("Colour of the panel outline, accent bar, glass hairline and title divider.")

    private var needsUpdate = true
    private var lastUpdate = 0L
    private val cachedLines = mutableListOf<Pair<String, String>>()
    private var cachedTitle = ""

    private val example = listOf(
        "§707/10/26 §8m151AM" to "", "" to "", " Spring 4th" to "", " §75:40pm §e☀" to "", " §7⏣ §bVillage" to "",
        "" to "", "Purse: §61,234,567" to "", "Bits: §b9,001" to "", "" to "", "§ewww.hypixel.net" to ""
    )

    @Suppress("RemoveRedundantQualifierName")
    private val hud = object: HudElement() {
        override val name = "Scoreboard"
        override val toggle get() = Scoreboard.enabled

        private val rightAnchored get() = growDirection.value == 1
        private fun left() = if (rightAnchored) x - width * scale else x

        override fun draw(ctx: GuiGraphicsExtractor, example: Boolean): Pair<Float, Float> {
            val lines: List<Pair<String, String>>
            val title: String
            val objective = mc.level?.scoreboard?.getDisplayObjective(DisplaySlot.SIDEBAR)
            if (objective != null) {
                if (needsUpdate || System.currentTimeMillis() - lastUpdate > 1000) updateCache(mc.level !!.scoreboard, objective)
                lines = cachedLines
                title = cachedTitle
            }
            else if (example) {
                lines = processLines(Scoreboard.example)
                title = "§e§lSKYBLOCK"
            }
            else return 0f to 0f
            if (lines.isEmpty() && ! showTitle.value) return 0f to 0f

            val font = mc.font
            val pad = padding.value.toFloat()
            val leftPad = pad + if (bgMode.value == 3) 2f else 0f
            val lineH = font.lineHeight + lineSpacing.value.toFloat()
            val numberGap = 8f

            val leftW = lines.maxOfOrNull { font.width(it.first) } ?: 0
            val rightW = if (showNumbers.value) lines.maxOfOrNull { font.width(it.second) } ?: 0 else 0
            val contentW = maxOf(
                leftW + if (rightW > 0) numberGap + rightW else 0f,
                if (showTitle.value) font.width(title).toFloat() else 0f
            )
            val titleH = if (showTitle.value) font.lineHeight + 3f + if (titleDivider.value) 3f else 0f else 0f
            val w = contentW + leftPad + pad
            val h = titleH + lines.size * lineH - lineSpacing.value + pad * 2

            ctx.pose().pushMatrix()
            if (rightAnchored) ctx.pose().translate(- w, 0f)

            HudWidgetKit.drawBackground(ctx, w, h, bgMode.value, bgColor.value, accentColor.value)

            var y = pad
            if (showTitle.value) {
                ctx.drawString(title, leftPad + (contentW - font.width(title)) / 2f, y, shadow = textShadow.value)
                y += font.lineHeight + 2f
                if (titleDivider.value) {
                    ctx.drawRect(leftPad + contentW * 0.15f, y, contentW * 0.7f, 1f, accentColor.value.withAlpha(160))
                    y += 3f
                }
                y += 1f
            }

            val textAreaW = contentW - if (rightW > 0) numberGap + rightW else 0f
            for ((text, number) in lines) {
                val tw = font.width(text)
                val tx = when (alignment.value) {
                    1 -> leftPad + (textAreaW - tw) / 2f
                    2 -> leftPad + textAreaW - tw
                    else -> leftPad
                }
                ctx.drawString(text, tx, y, shadow = textShadow.value)
                if (rightW > 0 && number.isNotEmpty()) ctx.drawString(number, leftPad + contentW - font.width(number), y, shadow = textShadow.value)
                y += lineH
            }

            ctx.pose().popMatrix()
            return w to h
        }

        override fun isHovered(mx: Int, my: Int): Boolean {
            if (width == 0f) return false
            val l = left()
            return mx >= l && mx <= l + width * scale && my >= y && my <= y + height * scale
        }

        override fun drawBackground(ctx: GuiGraphicsExtractor, mx: Int, my: Int) {
            if (width == 0f) return
            val l = left()
            val w = width * scale
            val h = height * scale
            val border = if (isDragging || isHovered(mx, my)) accentColor.value else accentColor.value.withAlpha(60)
            ctx.drawRect(l, y, w, h, HudWidgetKit.defaultBackground)
            ctx.drawRect(l, y, w, 1f, border)
            ctx.drawRect(l, y + h - 1f, w, 1f, border)
        }
    }

    @JvmStatic
    fun replacesVanilla() = enabled

    override fun init() {
        hudElements.add(hud)
        hud.defaults {
            // right edge of the screen, roughly where vanilla draws the sidebar
            x = runCatching { mc.window.guiScaledWidth - 3f }.getOrDefault(470f)
            y = runCatching { mc.window.guiScaledHeight / 3f }.getOrDefault(140f)
        }

        register<WorldChangeEvent> { needsUpdate = true }

        register<MainThreadPacketReceivedEvent.Post> {
            if (
                event.packet is ClientboundSetScorePacket || event.packet is ClientboundSetObjectivePacket ||
                event.packet is ClientboundSetDisplayObjectivePacket || event.packet is ClientboundResetScorePacket ||
                event.packet is ClientboundSetPlayerTeamPacket
            ) {
                needsUpdate = true
            }
        }
    }

    private fun updateCache(scoreboard: MCScoreboard, objective: Objective) {
        cachedTitle = objective.displayName.formattedText
        val raw = scoreboard.listPlayerScores(objective).sortedByDescending { it.value }.take(15).map { score ->
            val name = score.ownerName().string
            val team = scoreboard.getPlayersTeam(name)
            PlayerTeam.formatNameForTeam(team, Component.literal(name)).formattedText to "§c${score.value}"
        }
        cachedLines.clear()
        cachedLines.addAll(processLines(raw))
        needsUpdate = false
        lastUpdate = System.currentTimeMillis()
    }

    private fun processLines(raw: List<Pair<String, String>>): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        raw.forEachIndexed { index, (text, number) ->
            var line = text
            val plain = line.removeFormatting().trim()
            if (index == 0 && hideServerId.value && LocationUtils.inSkyblock || index == 0 && hideServerId.value && raw === example) {
                line = line.substringBefore(" §8")
            }
            if (hideFooter.value && index == raw.lastIndex && plain.contains("hypixel.net", true)) return@forEachIndexed
            if (collapseBlank.value && plain.isEmpty() && out.lastOrNull()?.first?.removeFormatting()?.isBlank() == true) return@forEachIndexed
            out += line to number
        }
        return out
    }
}
