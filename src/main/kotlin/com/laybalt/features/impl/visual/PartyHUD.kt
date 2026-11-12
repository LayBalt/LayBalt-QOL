package com.laybalt.features.impl.visual

import com.laybalt.config.types.ColorSetting
import com.laybalt.config.types.DropdownSetting
import com.laybalt.config.types.ToggleSetting
import com.laybalt.event.impl.PartyEvent
import com.laybalt.features.Feature
import com.laybalt.ui.utils.Resolution
import com.laybalt.utils.PartyUtils
import com.laybalt.utils.PartyUtils.OnlineState
import com.laybalt.utils.PartyUtils.PartyMember
import com.laybalt.utils.PartyUtils.PartyRole
import com.laybalt.utils.location.LocationUtils
import com.laybalt.utils.render.Render2D.drawString
import com.laybalt.utils.render.RenderHelper.width
import net.minecraft.client.gui.GuiGraphicsExtractor

/**
 * NoammAddons 5bf76bf9 (Party HUD) and 385fc5fc (the lines are rebuilt on [PartyEvent.Update] instead of every frame).
 * Drawn only while [PartyUtils] knows you are in a party; the LayBalt panel/shadow settings were added on our side.
 */
object PartyHUD: Feature("Displays your current party members, their roles and online state on screen.") {
    private val showInvites by ToggleSetting("Show Pending Invites", true).withDescription("Lists players you invited who have not joined yet.").section("Content").onChange { updatePartyLines() }
    private val textShadow by ToggleSetting("Text Shadow", true).withDescription("Draws the text with a shadow.").section("Display")

    private val bgMode by DropdownSetting("Background", 0, HudWidgetKit.BACKGROUND_MODES).withDescription("Panel drawn behind the whole widget.").section("Panel")
    private val bgColor by ColorSetting("Background Color", HudWidgetKit.defaultBackground).withDescription("Colour of the panel behind the widget.").showIf { bgMode.value != 0 }
    private val accentColor by ColorSetting("Accent Color", HudWidgetKit.defaultAccent).withDescription("Colour of the panel outline, accent bar or glass hairline.").showIf { bgMode.value >= 2 }

    private const val LINE_HEIGHT = 9
    private const val HEADER_GAP = 2

    private data class Row(val icon: String, val iconX: Int, val text: String, val textX: Int)

    @Volatile private var partyLines = emptyList<Pair<String, String>>()
    private var cachedLines: List<Pair<String, String>>? = null
    private var rows = emptyList<Row>()
    private var layoutWidth = 0

    private val exampleMembers = listOf(
        PartyMember("Noamm", PartyRole.LEADER, OnlineState.ONLINE, "MVP++", "§6[MVP§c++§6]"),
        PartyMember("jcnlk", PartyRole.MOD, OnlineState.ONLINE, "MVP+", "§b[MVP§c+§b]"),
        PartyMember("Alex", PartyRole.MEMBER, OnlineState.ONLINE),
        PartyMember("Steve", PartyRole.MEMBER, OnlineState.OFFLINE, "VIP", "§a[VIP]"),
    )
    private val exampleInvites = setOf("verybritish")

    override fun init() {
        register<PartyEvent.Update> { updatePartyLines() }

        hudElement("PartyHUD", shouldDraw = { LocationUtils.onHypixel && PartyUtils.isInParty }) { ctx, example -> draw(ctx, example) } defaults {
            x = Resolution.width - 140f
            y = 5f
        }
    }

    override fun onEnable() {
        super.onEnable()
        updatePartyLines()
    }

    private fun updatePartyLines() {
        partyLines = buildLines(PartyUtils.partyMembers, PartyUtils.pendingInvites)
    }

    private fun draw(ctx: GuiGraphicsExtractor, example: Boolean): Pair<Number, Number> {
        val lines = if (example) buildLines(exampleMembers, exampleInvites) else partyLines
        if (lines != cachedLines) updateLayout(lines)

        val pad = HudWidgetKit.padding(bgMode.value)
        val left = HudWidgetKit.leftPadding(bgMode.value)
        val contentH = if (rows.isEmpty()) 0 else rows.size * LINE_HEIGHT + HEADER_GAP - 1
        val totalW = layoutWidth + left + pad
        val totalH = contentH + pad * 2
        HudWidgetKit.drawBackground(ctx, totalW, totalH, bgMode.value, bgColor.value, accentColor.value)

        rows.forEachIndexed { i, row ->
            val y = pad + i * LINE_HEIGHT + if (i > 0) HEADER_GAP else 0
            if (row.icon.isNotEmpty()) ctx.drawString(row.icon, left + row.iconX, y, shadow = textShadow.value)
            ctx.drawString(row.text, left + row.textX, y, shadow = textShadow.value)
        }

        return totalW to totalH
    }

    private fun updateLayout(lines: List<Pair<String, String>>) {
        val iconWidth = lines.maxOfOrNull { it.first.width() } ?: 0
        val columnX = if (iconWidth > 0) iconWidth + 3 else 0

        rows = lines.map { (icon, text) ->
            if (icon.isEmpty()) Row(icon, 0, text, 0)
            else Row(icon, (iconWidth - icon.width()) / 2, text, columnX)
        }
        layoutWidth = rows.maxOfOrNull { it.textX + it.text.width() } ?: 0
        cachedLines = lines
    }

    private fun buildLines(members: List<PartyMember>, invites: Set<String>) = buildList {
        add("" to "&d&lParty &7(${members.size})")
        members.sortedBy { it.role.ordinal }.forEach { add(it.format()) }
        if (showInvites.value) invites.forEach { add("&8»" to "&7$it &8(invited)") }
    }

    private fun PartyMember.format(): Pair<String, String> {
        val role = when (role) {
            PartyRole.LEADER -> "&6👑"
            PartyRole.MOD -> "&e♦"
            PartyRole.MEMBER -> "&7•"
        }
        val name = rankPrefix?.let { "$it ${this.name}" } ?: "&7${this.name}"
        val state = if (isOnline) "&a●" else "&c●"
        return role to "$name $state"
    }
}
