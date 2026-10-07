// Ported from Odin (https://github.com/odtheking/Odin), BSD-3-Clause, Copyright (c) 2025 odtheking
package com.laybalt.features.impl.floor7

import com.laybalt.config.types.ColorSetting
import com.laybalt.config.types.DropdownSetting
import com.laybalt.config.types.ToggleSetting
import com.laybalt.event.impl.ChatMessageEvent
import com.laybalt.event.impl.RenderWorldEvent
import com.laybalt.event.impl.TickEvent
import com.laybalt.event.impl.WorldChangeEvent
import com.laybalt.features.Feature
import com.laybalt.utils.ChatUtils.removeFormatting
import com.laybalt.utils.ColorUtils.withAlpha
import com.laybalt.utils.location.LocationUtils
import com.laybalt.utils.render.Render2D.drawString
import com.laybalt.utils.render.RenderHelper.width
import com.laybalt.utils.render.world.Render3D.renderBoxBounds
import com.laybalt.utils.render.world.Render3D.renderString
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.phys.AABB
import java.awt.Color

object InactiveWaypoints: Feature("Shows inactive terminals, devices and levers in the Goldor phase of Floor 7.") {
    private val show by DropdownSetting("Show", 0, listOf("All", "Terms & Levers", "Terminals", "Devices", "Levers"))
        .withDescription("Which inactive waypoints to show")
    private val style by DropdownSetting("Style", 0, listOf("Box, Text & Beacon", "Box & Text", "Box", "Text"))
        .withDescription("How the inactive waypoints are drawn")
    private val color by ColorSetting("Waypoint color", Color(255, 255, 85))
        .withDescription("Colour of the boxes and beams")
    private val throughWalls by ToggleSetting("Through Walls", true)
        .withDescription("Draws the waypoints through walls")
    private val hideDefault by ToggleSetting("Hide Default", true)
        .withDescription("Hides Hypixel's floating \"Inactive\" names above the waypoints")
    private val termInfo by ToggleSetting("Term Info HUD")
        .withDescription("Shows a HUD with levers, terminals, device and gate progress of the current section")

    private enum class Kind(val label: String?) {
        Terminal("Terminal"), Device("Device"), Lever("Lever"), Other(null);

        val shown get() = when (this) {
            Terminal -> show.value in setOf(0, 1, 2)
            Device -> show.value in setOf(0, 3)
            Lever -> show.value in setOf(0, 1, 4)
            Other -> false
        }

        companion object {
            fun of(name: String): Kind? = when (name) {
                "Inactive Terminal" -> Terminal
                "Inactive" -> Device
                "Not Activated" -> Lever
                else -> if (listOf("inactive", "not activated", "click here").any { name.contains(it, ignoreCase = true) }) Other else null
            }
        }
    }

    private val inactive = HashMap<ArmorStand, Kind>()
    private var firstInSection = false
    private var shouldRender = false
    private var isComplete = false
    private var lastCompleted = 0
    private var device = false
    private var terminals = 0
    private var gate = false
    private var section = 1
    private var levers = 0

    private val completedRegex = Regex("^(.{1,16}) (activated|completed) a (terminal|lever|device)! \\((\\d)/(\\d)\\)$")
    private val goldorRegex = Regex("^\\[BOSS] Goldor: Who dares trespass into my domain\\?$")
    private val coreOpeningRegex = Regex("^The Core entrance is opening!$")
    private val gateRegex = Regex("^The gate has been destroyed!$")

    override fun init() {
        hudElement("InactiveWaypointsTermInfo", enabled = { termInfo.value }) { ctx, example ->
            if (! example && ! (LocationUtils.inBoss && shouldRender)) return@hudElement 0 to 0
            val lines = listOf(
                "§6Levers ${if (levers == 2) "§a" else "§c"}$levers§8/§a2",
                "§6Terms ${if ((section == 2 && terminals == 5) || (section != 2 && terminals == 4)) "§a" else "§c"}$terminals§8/§a${if (section == 2) 5 else 4}",
                "§6Device ${if (device) "§a✔" else "§c✘"}",
                "§6Gate ${if (gate) "§a✔" else "§c✘"}"
            )
            lines.forEachIndexed { i, line -> ctx.drawString(line, 0, i * 9) }
            lines.maxOf { it.width() } to 36
        }

        register<TickEvent.Start> {
            if (LocationUtils.F7Phase != 3) {
                if (inactive.isNotEmpty()) inactive.clear()
                return@register
            }
            val level = mc.level ?: return@register
            val seen = HashSet<ArmorStand>()
            for (entity in level.entitiesForRendering()) {
                val stand = entity as? ArmorStand ?: continue
                if (! stand.hasCustomName()) continue
                val kind = Kind.of(stand.name.string.removeFormatting()) ?: continue
                inactive[stand] = kind
                seen.add(stand)
            }
            inactive.keys.retainAll(seen)
        }

        register<ChatMessageEvent> {
            if (! LocationUtils.inBoss) return@register
            val message = event.unformattedText

            completedRegex.matchEntire(message)?.let {
                val completed = (it.groupValues[4].toIntOrNull() ?: 0).also { n -> if (n == 1) firstInSection = true }
                if (completed == (it.groupValues[5].toIntOrNull() ?: 0)) {
                    if (gate) newSection() else isComplete = true
                    return@register
                }
                when (it.groupValues[3]) {
                    "lever" -> levers ++
                    "terminal" -> terminals ++
                    "device" -> if (! firstInSection || lastCompleted != completed) device = true
                }
                lastCompleted = completed
                return@register
            }

            when {
                gateRegex.matches(message) -> {
                    gate = true
                    if (isComplete) newSection()
                }

                goldorRegex.matches(message) -> {
                    shouldRender = true
                    resetState()
                }

                coreOpeningRegex.matches(message) -> {
                    inactive.clear()
                    shouldRender = false
                    resetState()
                }
            }
        }

        register<WorldChangeEvent> {
            inactive.clear()
            shouldRender = false
            resetState()
        }

        register<RenderWorldEvent> {
            if (inactive.isEmpty() || LocationUtils.F7Phase != 3) return@register
            val box = style.value in setOf(0, 1, 2)
            val text = style.value in setOf(0, 1, 3)
            val beacon = style.value == 0
            val c = color.value

            for ((stand, kind) in inactive) {
                stand.isCustomNameVisible = ! hideDefault.value
                val label = kind.label?.takeIf { kind.shown } ?: continue
                val pos = stand.position()

                if (box) event.ctx.renderBoxBounds(AABB(pos.x - 0.5, pos.y, pos.z - 0.5, pos.x + 0.5, pos.y + 1.0, pos.z + 0.5), c, fill = false, phase = throughWalls.value)
                if (text) event.ctx.renderString(label, pos.add(0.0, 2.0, 0.0), scale = 1.5f, phase = throughWalls.value)
                if (beacon) event.ctx.renderBoxBounds(
                    pos.x - 0.125, pos.y + 1.0, pos.z - 0.125, pos.x + 0.125, pos.y + 256.0, pos.z + 0.125,
                    c, c.withAlpha(90), outline = false, fill = true, phase = false
                )
            }
        }
    }

    override fun onDisable() {
        // give Hypixel's own names back
        inactive.keys.forEach { it.isCustomNameVisible = true }
        super.onDisable()
    }

    private fun resetState() {
        firstInSection = false
        lastCompleted = 0
        isComplete = false
        device = false
        terminals = 0
        gate = false
        section = 1
        levers = 0
    }

    private fun newSection() {
        firstInSection = false
        isComplete = false
        device = false
        terminals = 0
        gate = false
        levers = 0
        section ++
    }
}
