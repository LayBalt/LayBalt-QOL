// Ported from Odin (https://github.com/odtheking/Odin), BSD-3-Clause, Copyright (c) 2025 odtheking
package com.laybalt.features.impl.misc

import com.laybalt.commands.CommandBuilder
import com.laybalt.config.types.ButtonSetting
import com.laybalt.config.types.ColorSetting
import com.laybalt.config.types.DropdownSetting
import com.laybalt.config.types.SliderSetting
import com.laybalt.config.types.ToggleSetting
import com.laybalt.event.impl.ChatMessageEvent
import com.laybalt.event.impl.RenderWorldEvent
import com.laybalt.event.impl.WorldChangeEvent
import com.laybalt.features.Feature
import com.laybalt.init.types.ICommandProvider
import com.laybalt.utils.ChatUtils
import com.laybalt.utils.ColorUtils.withAlpha
import com.laybalt.utils.MathUtils.center
import com.laybalt.utils.render.world.Render3D.renderBoxBounds
import com.laybalt.utils.render.world.Render3D.renderCircle
import com.laybalt.utils.render.world.Render3D.renderString
import com.laybalt.utils.render.world.Render3D.renderTracer
import com.laybalt.utils.render.world.RenderContext
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import gg.essential.universal.ChatColor
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.AABB
import java.awt.Color
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

/**
 * Temporary waypoints: created from "x: _, y: _, z: _" coordinates posted in chat (party chat by default) or with
 * `/lbq waypoint`. Each one is drawn as a beacon (block outline, light beam and a title with the distance) and
 * disappears after [duration] seconds or when the world changes.
 */
object Waypoints: Feature("Renders temporary waypoints from coordinates posted in chat. Commands: /lbq waypoint (alias /lbq wp)."), ICommandProvider {
    private val fromParty by ToggleSetting("From Party Chat", true).section("Sources")
        .withDescription("Adds a waypoint when someone posts \"x: _, y: _, z: _\" coordinates in party chat")
    private val fromAll by ToggleSetting("From All Chat")
        .withDescription("Also adds waypoints from coordinates posted in every other chat (all, guild, private, co-op)")
    private val personalWaypoint by ToggleSetting("Personal Waypoint")
        .withDescription("Also creates a waypoint for coordinates you post yourself")

    private val duration by SliderSetting("Duration", 60, 5, 600, 5, "s").section("Appearance")
        .withDescription("How long a waypoint stays before it disappears, in seconds")
    private val colorMode by DropdownSetting("Color Mode", 0, listOf("Random", "Fixed"))
        .withDescription("Random gives every waypoint a different Minecraft colour (as in Odin); Fixed uses Waypoint Color")
    private val fixedColor by ColorSetting("Waypoint Color", Color(170, 120, 255), false).showIf { colorMode.value == 1 }
        .withDescription("Colour of every waypoint when Color Mode is Fixed")
    private val showBeam by ToggleSetting("Beacon Beam", true)
        .withDescription("Draws a translucent light beam going up from the waypoint so it can be seen from far away")
    private val showDistance by ToggleSetting("Show Distance", true)
        .withDescription("Appends the distance to the waypoint (in blocks) to its title")
    private val scaleWithDistance by ToggleSetting("Scale With Distance", true)
        .withDescription("Makes the title grow with distance so it stays readable; off keeps a fixed size")
    private val groundRing by ToggleSetting("Ground Ring")
        .withDescription("Draws a slowly pulsing ring on the ground around the waypoint")
    private val tracer by ToggleSetting("Tracer")
        .withDescription("Draws a line from your crosshair to every waypoint")
    private val fadeOut by ToggleSetting("Fade Out", true)
        .withDescription("Fades the beam out during the last quarter of the waypoint's lifetime")

    private val removeWhenReached by ToggleSetting("Remove When Reached").section("Behaviour")
        .withDescription("Removes a waypoint once you get close to it")
    private val reachDistance by SliderSetting("Reach Distance", 3, 1, 16, 1, " blocks").showIf { removeWhenReached.value }
        .withDescription("How close you must get to a waypoint for it to be removed")
    private val clearButton by ButtonSetting("Clear Waypoints") { clearWaypoints() }
        .withDescription("Removes every temporary waypoint right now")

    // https://regex101.com/r/8K26A1/1
    private val partyRegex = Regex("^Party > (?:\\[[^]]*?])? ?(\\w{1,16})(?: [ቾ⚒])?: x: (-?\\d+), y: (-?\\d+), z: (-?\\d+).*")

    // https://regex101.com/r/A3aoyL/1
    private val allRegex = Regex("^(?!Party >).*\\s(?:\\[[^]]*?])? ?(\\w{1,16})(?: [ቾ⚒])?: x: (-?\\d+),? y: (-?\\d+),? z: (-?\\d+).*")

    private val colors = listOf(
        ChatColor.GOLD, ChatColor.GREEN, ChatColor.LIGHT_PURPLE, ChatColor.DARK_AQUA,
        ChatColor.YELLOW, ChatColor.DARK_RED, ChatColor.WHITE, ChatColor.DARK_PURPLE, ChatColor.BLUE,
        ChatColor.YELLOW, ChatColor.RED, ChatColor.LIGHT_PURPLE, ChatColor.DARK_GREEN,
    ).map { it.color !! }

    data class Waypoint(
        val name: String,
        val blockPos: BlockPos,
        val color: Color,
        val duration: Long,
        val timeAdded: Long = System.currentTimeMillis()
    )

    private val temporaryWaypoints = CopyOnWriteArrayList<Waypoint>()

    override fun init() {
        register<ChatMessageEvent> {
            val message = event.unformattedText
            val (name, x, y, z) = when {
                fromParty.value && partyRegex.matches(message) -> partyRegex.find(message)?.destructured
                fromAll.value && allRegex.matches(message) -> allRegex.find(message)?.destructured
                else -> null
            } ?: return@register

            if (name == mc.player?.name?.string && ! personalWaypoint.value) return@register

            addTempWaypoint("§6$name", x.toIntOrNull() ?: return@register, y.toIntOrNull() ?: return@register, z.toIntOrNull() ?: return@register)
        }

        register<RenderWorldEvent> {
            val now = System.currentTimeMillis()
            val player = mc.player
            temporaryWaypoints.removeAll {
                now > it.timeAdded + it.duration
                    || (removeWhenReached.value && player != null && player.blockPosition().closerThan(it.blockPos, reachDistance.value.toDouble()))
            }
            for (waypoint in temporaryWaypoints) event.ctx.drawWaypoint(waypoint, now)
        }

        register<WorldChangeEvent> { temporaryWaypoints.clear() }
    }

    private fun RenderContext.drawWaypoint(waypoint: Waypoint, now: Long) {
        val pos = waypoint.blockPos
        val dist = mc.player?.blockPosition()?.distManhattan(pos) ?: return
        val color = waypoint.color

        renderBoxBounds(AABB(pos), color, fill = false, phase = true)

        if (showBeam.value) {
            val left = 1f - (now - waypoint.timeAdded).toFloat() / waypoint.duration
            val alpha = if (fadeOut.value && left < 0.25f) 90 * (left / 0.25f).coerceIn(0f, 1f) else 90f
            val half = 0.125
            if (alpha >= 1f) renderBoxBounds(
                pos.x + 0.5 - half, pos.y + 1.0, pos.z + 0.5 - half,
                pos.x + 0.5 + half, pos.y + 256.0, pos.z + 0.5 + half,
                color, color.withAlpha(alpha.toInt()), outline = false, fill = true
            )
        }

        if (groundRing.value) {
            val pulse = 0.75 + 0.25 * sin(now / 300.0)
            renderCircle(pos.center.add(0.0, - 0.45, 0.0), 1.2 * pulse, color, 2, phase = true)
        }

        if (tracer.value) renderTracer(pos.center, color)

        renderString(
            if (showDistance.value) "${waypoint.name} §f(§3${dist}m§f)" else waypoint.name,
            pos.center.add(0.0, 1.7, 0.0),
            scale = if (scaleWithDistance.value) max(1f, dist * 0.05f) else 2f,
            phase = true
        )
    }

    /** Adds a temporary waypoint (Odin's `Waypoints.addTempWaypoint`); coordinates further than 5000 blocks are refused. */
    fun addTempWaypoint(name: String = "Waypoint", x: Int, y: Int, z: Int, durationMs: Long = duration.value * 1000L) {
        if (listOf(x, y, z).any { abs(it) > 5000 }) return ChatUtils.modMessage("§cWaypoint out of bounds.")
        if (temporaryWaypoints.any { it.blockPos.x == x && it.blockPos.y == y && it.blockPos.z == z }) return ChatUtils.modMessage("§cWaypoint already exists at $x, $y, $z.")
        ChatUtils.modMessage("§aAdded temporary waypoint at §6$x§r, §3$y§r, §d$z§r.")
        val color = if (colorMode.value == 1) fixedColor.value else colors.random()
        temporaryWaypoints.add(Waypoint(name, BlockPos(x, y, z), color, durationMs))
    }

    private fun clearWaypoints() {
        val count = temporaryWaypoints.size
        temporaryWaypoints.clear()
        ChatUtils.modMessage("§aRemoved $count temporary waypoint(s).")
    }

    private fun positionString(): String {
        val pos = mc.player?.blockPosition() ?: BlockPos(0, 0, 0)
        return "x: ${pos.x}, y: ${pos.y}, z: ${pos.z}"
    }

    // region /lbq waypoint

    private fun coordSuggestion(axis: Char): List<String> {
        val pos = mc.player?.blockPosition() ?: return emptyList()
        return listOf(when (axis) { 'x' -> pos.x; 'y' -> pos.y; else -> pos.z }.toString())
    }

    /** `<x> <y> <z>` integer arguments with "current position" suggestions; [last] runs on the z node. */
    private fun CommandBuilder.xyz(last: CommandBuilder.() -> Unit) =
        argument("x", IntegerArgumentType.integer()) {
            suggests { coordSuggestion('x') }
            argument("y", IntegerArgumentType.integer()) {
                suggests { coordSuggestion('y') }
                argument("z", IntegerArgumentType.integer()) {
                    suggests { coordSuggestion('z') }
                    last()
                }
            }
        }

    private fun CommandBuilder.waypointCommand(literalName: String) = literal(literalName) {
        description("Temporary waypoints: /lbq $literalName <add|here|share|list|clear>")
        runs { ChatUtils.modMessage("§bUsage: /lbq $literalName add [<x> <y> <z> [name]] | here <name> | share [<x> <y> <z>] | list | clear") }

        literal("add") {
            runs {
                val pos = mc.player?.blockPosition() ?: return@runs
                addTempWaypoint("Waypoint", pos.x, pos.y, pos.z)
            }
            xyz {
                runs { ctx ->
                    addTempWaypoint("Waypoint", IntegerArgumentType.getInteger(ctx, "x"), IntegerArgumentType.getInteger(ctx, "y"), IntegerArgumentType.getInteger(ctx, "z"))
                }
                argument("name", StringArgumentType.greedyString()) {
                    runs { ctx ->
                        addTempWaypoint(
                            StringArgumentType.getString(ctx, "name"),
                            IntegerArgumentType.getInteger(ctx, "x"), IntegerArgumentType.getInteger(ctx, "y"), IntegerArgumentType.getInteger(ctx, "z")
                        )
                    }
                }
            }
        }

        literal("here") {
            argument("name", StringArgumentType.greedyString()) {
                runs { ctx ->
                    val pos = mc.player?.blockPosition() ?: return@runs
                    addTempWaypoint(StringArgumentType.getString(ctx, "name"), pos.x, pos.y, pos.z)
                }
            }
        }

        literal("share") {
            runs { ChatUtils.sendMessage(positionString()) }
            xyz {
                runs { ctx ->
                    ChatUtils.sendMessage("x: ${IntegerArgumentType.getInteger(ctx, "x")}, y: ${IntegerArgumentType.getInteger(ctx, "y")}, z: ${IntegerArgumentType.getInteger(ctx, "z")}")
                }
            }
        }

        literal("list") {
            runs {
                if (temporaryWaypoints.isEmpty()) return@runs ChatUtils.modMessage("There are no temporary waypoints.")
                val now = System.currentTimeMillis()
                val lines = temporaryWaypoints.withIndex().joinToString("\n") { (i, it) ->
                    val left = ((it.timeAdded + it.duration - now) / 1000).coerceAtLeast(0)
                    "§7${i + 1}. §r${it.name}§r §f${it.blockPos.x}, ${it.blockPos.y}, ${it.blockPos.z} §8(${left}s left)"
                }
                ChatUtils.modMessage("Temporary waypoints:\n$lines")
            }
        }

        literal("clear") { runs { clearWaypoints() } }
    }

    override fun CommandBuilder.command() {
        setName("lbq", "laybalt")
        waypointCommand("waypoint")
        waypointCommand("wp")
    }

    // endregion
}
