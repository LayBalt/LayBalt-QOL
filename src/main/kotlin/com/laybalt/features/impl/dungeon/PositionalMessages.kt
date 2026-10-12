// Ported from Odin (https://github.com/odtheking/Odin), BSD-3-Clause, Copyright (c) 2025 odtheking
package com.laybalt.features.impl.dungeon

import com.laybalt.commands.CommandBuilder
import com.laybalt.config.ConfigManager
import com.laybalt.config.types.ListSetting
import com.laybalt.config.types.SliderSetting
import com.laybalt.config.types.ToggleSetting
import com.laybalt.event.impl.RenderWorldEvent
import com.laybalt.event.impl.TickEvent
import com.laybalt.event.impl.WorldChangeEvent
import com.laybalt.features.Feature
import com.laybalt.init.types.ICommandProvider
import com.laybalt.utils.ChatUtils
import com.laybalt.utils.ThreadUtils
import com.laybalt.utils.location.LocationUtils
import com.laybalt.utils.render.world.Render3D.renderBoxBounds
import com.laybalt.utils.render.world.Render3D.renderCircle
import com.laybalt.utils.render.world.Render3D.renderLine
import com.laybalt.utils.render.world.Render3D.renderString
import com.mojang.brigadier.arguments.BoolArgumentType
import com.mojang.brigadier.arguments.DoubleArgumentType
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import gg.essential.universal.ChatColor
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.awt.Color
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

object PositionalMessages: Feature("Sends a party message when you reach a saved position. Manage the list with /lbq posmsg."), ICommandProvider {
    private val onlyDungeons by ToggleSetting("Only in Dungeons", true)
        .withDescription("Only checks and draws the positions while you are in a dungeon boss room (as in Odin)")
    private val showPositions by ToggleSetting("Show Positions", true)
        .withDescription("Draws a ring (\"at\" messages) or a box (\"in\" messages) at every saved position")
    private val cylinderHeight by SliderSetting("Height", 0.2f, 0.1f, 5f, 0.1f).showIf { showPositions.value }
        .withDescription("Height of the cylinder drawn for \"at\" messages")
    private val displayMessage by ToggleSetting("Show Message", true).showIf { showPositions.value }
        .withDescription("Draws the message text above its position")
    private val messageSize by SliderSetting("Message Size", 1f, 0.1f, 4f, 0.1f).showIf { showPositions.value && displayMessage.value }
        .withDescription("Size of the message text drawn above the position")

    /**
     * One saved message. "at" messages have [distance] (trigger radius around x/y/z); "in" messages have the second
     * corner x2/y2/z2 and trigger inside that box. [dontSend] keeps the position drawn but never sends the message.
     */
    data class PosMessage(
        val x: Double, val y: Double, val z: Double,
        val x2: Double?, val y2: Double?, val z2: Double?,
        val delay: Int, val distance: Double?, val color: Color,
        val message: String?, val dontSend: Boolean
    ) {
        @Transient private var cachedCenter: Vec3? = null
        val center: Vec3
            get() = cachedCenter ?: Vec3((x + (x2 ?: x)) / 2, (y + (y2 ?: y)) / 2, (z + (z2 ?: z)) / 2).also { cachedCenter = it }

        @Transient private var cachedBox: AABB? = null
        val box: AABB?
            get() {
                if (cachedBox == null && x2 != null && y2 != null && z2 != null) cachedBox = AABB(x, y, z, x2, y2, z2)
                return cachedBox
            }

        val radiusSquared: Double? get() = distance?.let { it * it }
    }

    val posMessages by ListSetting("Pos Messages", mutableListOf<PosMessage>())
        .withDescription("Saved positional messages; edit them with /lbq posmsg")
    private val sentMessages = mutableSetOf<PosMessage>()

    private val active get() = ! onlyDungeons.value || LocationUtils.inBoss

    override fun init() {
        register<TickEvent.End> {
            if (! active) return@register
            for (posMessage in posMessages.value.toList()) {
                if (posMessage.dontSend || posMessage in sentMessages) continue
                if (posMessage.x2 != null) handleIn(posMessage) else handleAt(posMessage)
            }
        }

        register<RenderWorldEvent> {
            if (! showPositions.value || ! active) return@register
            for (posMessage in posMessages.value.toList()) {
                val distance = posMessage.distance
                if (distance != null) {
                    val base = Vec3(posMessage.x, posMessage.y, posMessage.z)
                    drawCylinder(event, base, distance, cylinderHeight.value.toDouble(), posMessage.color)
                    if (displayMessage.value) posMessage.message?.let { event.ctx.renderString(it, base.add(0.0, 1.0, 0.0), scale = messageSize.value) }
                }
                else {
                    val box = posMessage.box ?: continue
                    event.ctx.renderBoxBounds(box, posMessage.color, fill = false, phase = false)
                    if (displayMessage.value) posMessage.message?.let { event.ctx.renderString(it, posMessage.center.add(0.0, 1.0, 0.0), scale = messageSize.value) }
                }
            }
        }

        register<WorldChangeEvent> { sentMessages.clear() }
    }

    /** Two rings joined by vertical lines (Odin draws a cylinder). */
    private fun drawCylinder(event: RenderWorldEvent, base: Vec3, radius: Double, height: Double, color: Color) {
        event.ctx.renderCircle(base, radius, color, 2)
        event.ctx.renderCircle(base.add(0.0, height, 0.0), radius, color, 2)
        for (i in 0 until 8) {
            val angle = Math.PI * 2 * i / 8
            val point = base.add(cos(angle) * radius, 0.0, sin(angle) * radius)
            event.ctx.renderLine(point, point.add(0.0, height, 0.0), color, 2)
        }
    }

    private fun handleAt(posMessage: PosMessage) {
        val player = mc.player ?: return
        val radiusSquared = posMessage.radiusSquared ?: return
        if (player.distanceToSqr(posMessage.x, posMessage.y, posMessage.z) > radiusSquared) return
        send(posMessage)
    }

    private fun handleIn(posMessage: PosMessage) {
        val box = posMessage.box ?: return
        val position = mc.player?.position() ?: return
        if (! box.contains(position)) return
        send(posMessage)
    }

    private fun send(posMessage: PosMessage) {
        sentMessages.add(posMessage)
        ThreadUtils.scheduledTask(posMessage.delay) { ChatUtils.sendCommand("pc ${posMessage.message}") }
    }

    // region /lbq posmsg

    private val colors = mapOf(
        "darkblue" to ChatColor.DARK_BLUE, "darkgreen" to ChatColor.DARK_GREEN, "darkaqua" to ChatColor.DARK_AQUA,
        "darkred" to ChatColor.DARK_RED, "darkpurple" to ChatColor.DARK_PURPLE, "gold" to ChatColor.GOLD,
        "gray" to ChatColor.GRAY, "darkgray" to ChatColor.DARK_GRAY, "blue" to ChatColor.BLUE, "green" to ChatColor.GREEN,
        "aqua" to ChatColor.AQUA, "red" to ChatColor.RED, "lightpurple" to ChatColor.LIGHT_PURPLE, "yellow" to ChatColor.YELLOW,
        "white" to ChatColor.WHITE, "black" to ChatColor.BLACK
    )

    private fun colorOf(name: String): Color? = colors[name.lowercase()]?.color

    private fun suggestCoord(axis: Char): List<String> {
        val player = mc.player ?: return emptyList()
        return listOf(floor(when (axis) { 'x' -> player.x; 'y' -> player.y; else -> player.z }).toString())
    }

    private fun save() = runCatching { ConfigManager.save() }

    private fun d(ctx: CommandContext<FabricClientCommandSource>, name: String) = DoubleArgumentType.getDouble(ctx, name)

    private fun add(posMessage: PosMessage, info: String) {
        if (posMessages.value.any { it.message == posMessage.message }) return ChatUtils.modMessage("§cA message with that text already exists.")
        posMessages.value.add(posMessage)
        save()
        ChatUtils.modMessage(info)
    }

    /** x, y, z (and x2, y2, z2) arguments with "current position" suggestions; [last] builds the rest. */
    private fun CommandBuilder.coords(prefix: String, last: CommandBuilder.() -> Unit) =
        argument("${prefix}x", DoubleArgumentType.doubleArg()) {
            suggests { suggestCoord('x') }
            argument("${prefix}y", DoubleArgumentType.doubleArg()) {
                suggests { suggestCoord('y') }
                argument("${prefix}z", DoubleArgumentType.doubleArg()) {
                    suggests { suggestCoord('z') }
                    last()
                }
            }
        }

    private fun CommandBuilder.colorSendMessage(block: (CommandContext<FabricClientCommandSource>, Color, Boolean, String) -> Unit) =
        argument("color", StringArgumentType.word()) {
            suggests { colors.keys }
            argument("dontSend", BoolArgumentType.bool()) {
                suggests { listOf("false", "true") }
                argument("message", StringArgumentType.greedyString()) {
                    runs { ctx ->
                        val colorName = StringArgumentType.getString(ctx, "color")
                        val color = colorOf(colorName) ?: return@runs ChatUtils.modMessage("§cUnknown color $colorName")
                        block(ctx, color, BoolArgumentType.getBool(ctx, "dontSend"), StringArgumentType.getString(ctx, "message"))
                    }
                }
            }
        }

    override fun CommandBuilder.command() {
        setName("lbq", "laybalt")

        literal("posmsg") {
            category(com.laybalt.commands.LbqHelp.Category.PARTY)
            description("Positional Messages: /lbq posmsg <add at|add in|remove|clear|list>")
            runs { ChatUtils.modMessage("§bUsage: /lbq posmsg add at <x> <y> <z> <delay> <distance> <color> <dontSend> <message> | add in <x> <y> <z> <x2> <y2> <z2> <delay> <color> <dontSend> <message> | remove <message> | clear | list") }

            literal("add") {
                literal("at") {
                    coords("") {
                        argument("delay", IntegerArgumentType.integer(0)) {
                            suggests { listOf("0", "20") }
                            argument("distance", DoubleArgumentType.doubleArg(0.0)) {
                                suggests { listOf("1", "2", "3") }
                                colorSendMessage { ctx, color, dontSend, message ->
                                    val (x, y, z) = Triple(d(ctx, "x"), d(ctx, "y"), d(ctx, "z"))
                                    val delay = IntegerArgumentType.getInteger(ctx, "delay")
                                    val distance = d(ctx, "distance")
                                    add(
                                        PosMessage(x, y, z, null, null, null, delay, distance, color, message, dontSend),
                                        "Message \"$message\" added at $x, $y, $z, with ${delay}t delay, triggered up to $distance blocks away."
                                    )
                                }
                            }
                        }
                    }
                }

                literal("in") {
                    coords("") {
                        coords("2") {
                            argument("delay", IntegerArgumentType.integer(0)) {
                                suggests { listOf("0", "20") }
                                colorSendMessage { ctx, color, dontSend, message ->
                                    val (x, y, z) = Triple(d(ctx, "x"), d(ctx, "y"), d(ctx, "z"))
                                    val (x2, y2, z2) = Triple(d(ctx, "2x"), d(ctx, "2y"), d(ctx, "2z"))
                                    val delay = IntegerArgumentType.getInteger(ctx, "delay")
                                    add(
                                        PosMessage(x, y, z, x2, y2, z2, delay, null, color, message, dontSend),
                                        "Message \"$message\" added in $x, $y, $z, $x2, $y2, $z2, with ${delay}t delay."
                                    )
                                }
                            }
                        }
                    }
                }
            }

            literal("remove") {
                argument("message", StringArgumentType.greedyString()) {
                    suggests { posMessages.value.mapNotNull { it.message }.distinct() }
                    runs { ctx ->
                        val message = StringArgumentType.getString(ctx, "message").trim()
                        val removed = posMessages.value.count { it.message.equals(message, true) }
                        if (removed == 0) return@runs ChatUtils.modMessage("§cMessage not found. Available messages: ${posMessages.value.joinToString { "\"${it.message}\"" }}")
                        posMessages.value.removeAll { it.message.equals(message, true) }
                        save()
                        ChatUtils.modMessage("Removed $removed Positional Message(s): \"$message\"")
                    }
                }
            }

            literal("clear") {
                runs {
                    posMessages.value.clear()
                    sentMessages.clear()
                    save()
                    ChatUtils.modMessage("Cleared List")
                }
            }

            literal("list") {
                runs {
                    if (posMessages.value.isEmpty()) return@runs ChatUtils.modMessage("Positional Message list is empty!")
                    val output = posMessages.value.withIndex().joinToString("\n") { (index, it) ->
                        val hex = "#%06X".format(it.color.rgb and 0xFFFFFF)
                        "${index + 1}: ${it.x}, ${it.y}, ${it.z}, ${it.x2}, ${it.y2}, ${it.z2}, ${it.delay}, ${it.distance}, $hex, dontSend=${it.dontSend}, \"${it.message}\""
                    }
                    ChatUtils.modMessage("Positional Message list:\n$output")
                }
            }
        }
    }

    // endregion
}
