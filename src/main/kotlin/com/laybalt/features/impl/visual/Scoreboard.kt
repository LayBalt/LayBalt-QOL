package com.laybalt.features.impl.visual

import com.laybalt.config.types.ColorSetting
import com.laybalt.config.types.DropdownSetting
import com.laybalt.config.types.SliderSetting
import com.laybalt.config.types.ToggleSetting
import com.laybalt.features.Feature
import com.laybalt.features.impl.render.NoRender
import com.laybalt.ui.hud.HudElement
import com.laybalt.ui.utils.Resolution
import com.laybalt.utils.ChatUtils.removeFormatting
import com.laybalt.utils.ColorUtils.withAlpha
import com.laybalt.utils.location.LocationUtils
import com.laybalt.utils.render.Render2D.drawRect
import net.minecraft.ChatFormatting
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.numbers.StyledFormat
import net.minecraft.world.scores.DisplaySlot
import net.minecraft.world.scores.PlayerScoreEntry
import net.minecraft.world.scores.PlayerTeam
import kotlin.math.abs

/**
 * Draws the sidebar scoreboard as a movable HUD element in the LayBalt panel style and hides the vanilla one.
 *
 * The lines are read from the live client scoreboard on every frame, exactly like vanilla `Hud.displayScoreboardSidebar`
 * does (Hypixel animates its sidebar by rewriting team prefixes / suffixes of fake players without touching the scores;
 * reading them live needs no packet bookkeeping and costs a few map lookups per frame). Lines are kept as [Component]s and drawn through
 * the vanilla font, so colours, formatting resets and RGB colours look exactly like the vanilla sidebar.
 *
 * Position: [HudElement.x] / [HudElement.y] are the panel's top-left corner like every other HUD element (so the HUD
 * editor's dragging, snapping, frame and name tag work unchanged). When the panel's size changes (lines get longer, the
 * line count changes, the HUD scale or GUI scale changes) the configured anchor edge stays in place, and the panel is
 * always kept inside the HUD canvas.
 *
 * @see com.laybalt.mixin.MixinHudScoreboard
 */
object Scoreboard: Feature("Draws a custom scoreboard instead of the vanilla one.") {
    private val hideServerId by ToggleSetting("Hide Server ID")
        .withDescription("On Hypixel, replaces the first line ('07/10/26 m151AM') with 'Date: 07/10/26', hiding the server id.")
    private val hideFooter by ToggleSetting("Hide Footer")
        .withDescription("Hides the last line with the server address (www.hypixel.net).")
    private val collapseBlank by ToggleSetting("Collapse Empty Lines")
        .withDescription("Merges several empty lines in a row into one.")
    private val showNumbers by ToggleSetting("Show Numbers")
        .withDescription("Shows the score numbers on the right like vanilla, using the server's number format (hidden when the server blanks them).")

    private val showTitle by ToggleSetting("Show Title", true)
        .withDescription("Shows the scoreboard title at the top.").section("Layout")
    private val titleDivider by ToggleSetting("Title Divider", true)
        .withDescription("Draws a thin accent line under the title.").showIf { showTitle.value }
    private val alignment by DropdownSetting("Alignment", 0, listOf("Left", "Center", "Right"))
        .withDescription("Horizontal alignment of the lines inside the panel.")
    private val growDirection by DropdownSetting("Anchor", 1, listOf("Left Edge", "Right Edge"))
        .withDescription("Which edge stays in place when the scoreboard gets wider or narrower. Right Edge suits a scoreboard at the right side of the screen.")
    private val verticalAnchor by DropdownSetting("Vertical Anchor", 1, listOf("Top Edge", "Center", "Bottom Edge"))
        .withDescription("What stays in place when the number of lines changes. Center keeps it vertically centred like the vanilla sidebar.")
    private val matchVanillaSize by ToggleSetting("Match Vanilla Size", true)
        .withDescription("Sizes the scoreboard like the vanilla sidebar at your GUI scale (the HUD scale multiplies it). Off: sized like the other LayBalt HUD elements.")
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

    /** One sidebar row: the team-formatted name and the formatted score (may be empty). */
    private class Line(val text: Component, val number: Component, val blank: Boolean = false)

    /** Vanilla's sidebar order (Hud.SCORE_DISPLAY_ORDER): score descending, then owner name case-insensitively. */
    private val ORDER = compareByDescending<PlayerScoreEntry> { it.value }.thenComparing({ it.owner }, String.CASE_INSENSITIVE_ORDER)

    /** "07/10/26 m151AM" (also "mini12AB", "mega5Z"): the date and the server id on Hypixel's first line. */
    private val SERVER_ID_LINE = Regex("""^\s*(\d{1,2}/\d{1,2}/\d{2,4})\s+[A-Za-z]+\d+[A-Za-z]*\b""")

    /** Placeholder position meaning "not placed yet": the first draw puts the panel at the vanilla sidebar's spot. */
    private const val UNPLACED = 10_000f

    /**
     * Config id of the HUD element. Up to 3.2.1 it was "Scoreboard" with x meaning the panel's RIGHT edge when Anchor is
     * Right Edge, clamped against the GUI-scaled window width instead of the HUD canvas (so e.g. x=854 at a 2560 px wide
     * window with GUI scale 3, well left of the canvas edge). Those saves are migrated by [migrateLegacyHud].
     */
    const val HUD_ID = "ScoreboardPanel"
    const val LEGACY_HUD_ID = "Scoreboard"

    private val exampleTitle: Component = Component.literal("§e§lSKYBLOCK")
    private val example = listOf(
        "§707/10/26 §8m151AM", "", " Spring 4th", " §75:40pm §e☀", " §7⏣ §bVillage",
        "", "Purse: §61,234,567", "Bits: §b9,001", "", "§ewww.hypixel.net"
    ).mapIndexed { i, s -> Line(Component.literal(s), StyledFormat.SIDEBAR_DEFAULT.format(10 - i)) }

    @Suppress("RemoveRedundantQualifierName")
    private val hud = object: HudElement() {
        override val name = HUD_ID
        override val displayName = "Scoreboard"
        override val toggle get() = Scoreboard.enabled
        override val shouldDraw get() = ! NoRender.active(NoRender.scoreboard)

        /** On-canvas size of the previous frame, so the anchored edge can be held when the size changes. */
        private var lastW = 0f
        private var lastH = 0f
        /** HUD canvas size of the previous frame, so a panel at the right / bottom edge or centred follows a resized window. */
        private var lastScreenW = 0f
        private var lastScreenH = 0f

        override fun draw(ctx: GuiGraphicsExtractor, example: Boolean): Pair<Float, Float> {
            val live = readSidebar()
            val title = live?.first ?: if (example) exampleTitle else return 0f to 0f
            val lines = processLines(live?.second ?: Scoreboard.example)
            if (lines.isEmpty() && ! showTitle.value) return 0f to 0f

            val font = mc.font
            val pad = padding.value.toFloat()
            val leftPad = pad + if (bgMode.value == 3) 2f else 0f
            val lineH = font.lineHeight + lineSpacing.value.toFloat()
            val numberGap = font.width(": ").toFloat()

            val numberW = lines.map { if (showNumbers.value) font.width(it.number) else 0 }
            val contentW = maxOf(
                lines.indices.maxOfOrNull { i -> font.width(lines[i].text) + if (numberW[i] > 0) numberGap + numberW[i] else 0f } ?: 0f,
                if (showTitle.value) font.width(title).toFloat() else 0f
            )
            val titleH = if (showTitle.value) font.lineHeight + 3f + if (titleDivider.value) 3f else 0f else 0f
            val linesH = if (lines.isEmpty()) 0f else lines.size * lineH - lineSpacing.value
            val w = contentW + leftPad + pad
            val h = titleH + linesH + pad * 2

            // vanilla-sized: undo the HUD canvas scale so one unit is one GUI-scaled pixel again
            val k = if (matchVanillaSize.value) 1f / Resolution.scale else 1f

            ctx.pose().pushMatrix()
            place(ctx, w * k, h * k)
            ctx.pose().scale(k, k)

            HudWidgetKit.drawBackground(ctx, w, h, bgMode.value, bgColor.value, accentColor.value)

            var y = pad
            if (showTitle.value) {
                text(ctx, title, leftPad + (contentW - font.width(title)) / 2f, y)
                y += font.lineHeight + 2f
                if (titleDivider.value) {
                    ctx.drawRect(leftPad + contentW * 0.15f, y, contentW * 0.7f, 1f, accentColor.value.withAlpha(160))
                    y += 3f
                }
                y += 1f
            }

            lines.forEachIndexed { i, line ->
                val numW = numberW[i]
                val areaW = contentW - if (numW > 0) numberGap + numW else 0f
                val tw = font.width(line.text)
                val tx = when (alignment.value) {
                    1 -> leftPad + (areaW - tw) / 2f
                    2 -> leftPad + areaW - tw
                    else -> leftPad
                }
                text(ctx, line.text, tx, y)
                if (numW > 0) text(ctx, line.number, leftPad + contentW - numW, y)
                y += lineH
            }

            ctx.pose().popMatrix()
            return w * k to h * k
        }

        /**
         * Holds the anchored edges when the panel's on-canvas size changes, puts a never-placed panel where vanilla draws
         * the sidebar (right edge, vertically centred) and keeps it inside the HUD canvas. [x] / [y] stay the top-left
         * corner; the pose (already translated to the old corner) is moved by the correction.
         */
        private fun place(ctx: GuiGraphicsExtractor, localW: Float, localH: Float) {
            val sw = localW * scale
            val sh = localH * scale
            val screenW = Resolution.width
            val screenH = Resolution.height
            var nx = x
            var ny = y

            if (! isDragging) {
                if (x >= UNPLACED || y >= UNPLACED) {
                    nx = screenW - sw - 1f
                    ny = (screenH - sh) / 2f
                }
                else {
                    // like vanilla's sidebar: flush with the right edge / centred / flush with the bottom stays so when the
                    // canvas changes size (window resized, aspect ratio changed); x / y are absolute canvas coordinates
                    if (lastScreenW > 0f && screenW != lastScreenW && x + lastW >= lastScreenW - 1.5f) nx += screenW - lastScreenW
                    if (lastScreenH > 0f && screenH != lastScreenH) {
                        val centred = abs(y + lastH / 2f - lastScreenH / 2f) < 1.5f
                        val bottom = y + lastH >= lastScreenH - 1.5f
                        if (bottom) ny += screenH - lastScreenH else if (centred) ny += (screenH - lastScreenH) / 2f
                    }
                    if (lastW > 0f && growDirection.value == 1) nx += lastW - sw
                    if (lastH > 0f) when (verticalAnchor.value) {
                        1 -> ny += (lastH - sh) / 2f
                        2 -> ny += lastH - sh
                    }
                }
            }
            lastW = sw
            lastH = sh
            lastScreenW = screenW
            lastScreenH = screenH

            if (screenW >= 50f && screenH >= 50f) {
                nx = nx.coerceIn(0f, maxOf(0f, screenW - sw))
                ny = ny.coerceIn(0f, maxOf(0f, screenH - sh))
            }
            if (nx == x && ny == y) return
            ctx.pose().translate((nx - x) / scale, (ny - y) / scale)
            x = nx
            y = ny
        }

        private fun text(ctx: GuiGraphicsExtractor, component: Component, x: Float, y: Float) {
            ctx.pose().pushMatrix()
            ctx.pose().translate(x, y)
            ctx.text(mc.font, component, 0, 0, - 1, textShadow.value)
            ctx.pose().popMatrix()
        }
    }

    @JvmStatic
    fun replacesVanilla() = enabled

    /**
     * Applies a position saved under [LEGACY_HUD_ID] (read after the feature settings). With Anchor = Left Edge the old x / y
     * already were the top-left corner and are kept; with Right Edge the old x was a right edge in the wrong coordinate space,
     * so the panel goes back to the vanilla sidebar spot (right edge, vertically centred). The scale is kept either way.
     */
    fun migrateLegacyHud(x: Float?, y: Float?, scale: Float?) {
        scale?.let { hud.scale = it }
        if (growDirection.value == 1 || x == null || y == null) return
        hud.x = x
        hud.y = y
    }

    override fun init() {
        hudElements.add(hud)
        hud.defaults {
            // placed on the first draw, once the screen and the panel size are known (see place)
            x = UNPLACED
            y = UNPLACED
        }
    }

    /**
     * The sidebar exactly as vanilla 26.2 picks and builds it (Hud.extractScoreboardSidebar / displayScoreboardSidebar):
     * the objective shown in the local player's team-colour slot, else the sidebar slot; non-hidden scores in vanilla order,
     * at most 15; each name formatted with its owner's team prefix / suffix; numbers in the objective's number format.
     */
    private fun readSidebar(): Pair<Component, List<Line>>? {
        val scoreboard = mc.level?.scoreboard ?: return null
        val teamSlot = mc.player?.let { scoreboard.getPlayersTeam(it.scoreboardName) }?.color?.orElse(null)?.displaySlot()
        val objective = teamSlot?.let(scoreboard::getDisplayObjective) ?: scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR) ?: return null
        val numberFormat = objective.numberFormatOrDefault(StyledFormat.SIDEBAR_DEFAULT)
        val lines = scoreboard.listPlayerScores(objective).asSequence().filterNot { it.isHidden }.sortedWith(ORDER).take(15).map { entry ->
            Line(PlayerTeam.formatNameForTeam(scoreboard.getPlayersTeam(entry.owner), entry.ownerName()), entry.formatValue(numberFormat))
        }.toList()
        return objective.displayName to lines
    }

    /** Printable ASCII of a line without formatting codes (Hypixel pads its fake player names with emoji). */
    private fun plain(component: Component) = component.string.removeFormatting().filter { it in ' '..'~' }

    private fun processLines(raw: List<Line>): List<Line> {
        val out = ArrayList<Line>(raw.size)
        raw.forEachIndexed { index, line ->
            val plain = plain(line.text)
            var text = line.text
            if (index == 0 && hideServerId.value && (LocationUtils.onHypixel || raw === example)) SERVER_ID_LINE.find(plain)?.let {
                text = Component.literal("Date: " + it.groupValues[1]).withStyle(ChatFormatting.GRAY)
            }
            if (hideFooter.value && index == raw.lastIndex && plain.replace(" ", "").contains("hypixel.net", true)) return@forEachIndexed
            val blank = plain.isBlank()
            if (collapseBlank.value && blank && out.lastOrNull()?.blank == true) return@forEachIndexed
            out += Line(text, line.number, blank)
        }
        return out
    }
}
