// Partially ported from Odin (https://github.com/odtheking/Odin), BSD-3-Clause, Copyright (c) 2025 odtheking
package com.laybalt.features.impl.floor7

import com.laybalt.config.types.DropdownSetting
import com.laybalt.config.types.TextInputSetting
import com.laybalt.config.types.ToggleSetting
import com.laybalt.event.impl.*
import com.laybalt.features.Feature
import com.laybalt.utils.ChatUtils
import com.laybalt.utils.ChatUtils.unformattedText
import com.laybalt.utils.dungeons.DungeonListener
import com.laybalt.utils.location.LocationUtils
import com.laybalt.utils.render.Render2D.drawString
import com.laybalt.utils.render.RenderHelper.width
import gg.essential.universal.UMinecraft
import net.minecraft.network.protocol.game.*
import net.minecraft.world.item.Items

object MelodyAlert: Feature("Tells your party when you open Melody and how far you are, and shows your teammates' Melody progress.") {
    private val msg by TextInputSetting("Melody Message", "I ❤ Melody").withDescription("Party message sent when you open the Melody terminal (leave empty to send no open message).")
    private val sendProgress by ToggleSetting("Send Progress", true).withDescription("Also tells the party each time you complete a row of Melody.")
    private val mode by DropdownSetting("Progress Mode", 0, listOf("1/3", "33%", "Melody 33% (Odin)")).withDescription("Format of the progress message: your message + 1/3, your message + 33%, or Odin's \"Melody 33%\".").showIf { sendProgress.value }
    private val sendCoords by ToggleSetting("Send Coords").withDescription("Sends your coordinates to the party when you open Melody, so teammates know which Melody you are on.")

    private val progressHud by ToggleSetting("Progress HUD").withDescription("Shows the Melody progress of teammates (read from their party messages: LayBalt, Odin and most other mods' formats).").section("Progress HUD")
    private val showPlayer by DropdownSetting("Show Player", 2, listOf("None", "Class", "Name", "Class & Name")).withDescription("How each teammate is labelled in the Progress HUD.").showIf { progressHud.value }

    // Clay of the second and third row: it turns lime once the row above is done (Melody has 3 rows).
    private val progressSlots = intArrayOf(25, 34)
    private val odinProgress = arrayOf("Melody 33%", "Melody 67%")
    private var isMelodyOpen = false
    private var currentStage = - 1

    private data class MelodyState(val name: String, var progress: Int)

    private val partyRegex = Regex("^Party > (?:\\[[^]]+] )?(\\w{1,16}): (.+)$")
    private val melodyStates = linkedMapOf<String, MelodyState>()

    override fun init() {
        hudElement(
            "Melody Progress",
            enabled = { progressHud.value },
            shouldDraw = { LocationUtils.F7Phase == 3 && melodyStates.isNotEmpty() }
        ) { ctx, example ->
            val states = if (example) listOf(MelodyState(mc.user.name, 2)) else melodyStates.values.toList()
            var width = 0
            states.forEachIndexed { index, state ->
                val line = formatLine(state)
                ctx.drawString(line, 0, index * 10)
                width = maxOf(width, line.width())
            }
            width.toFloat() to (states.size * 10f)
        }

        register<MainThreadPacketReceivedEvent.Post> {
            if (msg.value.isBlank() && ! sendCoords.value && ! (sendProgress.value && mode.value == 2)) return@register
            if (LocationUtils.F7Phase != 3) return@register
            val packet = event.packet as? ClientboundOpenScreenPacket ?: return@register
            if (packet.title.unformattedText != "Click the button on time!") return@register

            if (msg.value.isNotBlank()) ChatUtils.sendPartyMessage(msg.value)
            if (sendCoords.value) player.blockPosition().let { ChatUtils.sendPartyMessage("x: ${it.x}, y: ${it.y}, z: ${it.z}") }
            isMelodyOpen = true
            currentStage = - 1
        }

        register<MainThreadPacketReceivedEvent.Post> {
            if (! isMelodyOpen) return@register
            if (event.packet !is ClientboundContainerClosePacket) return@register
            isMelodyOpen = false
            currentStage = - 1
        }

        register<PacketEvent.Sent> {
            if (! isMelodyOpen) return@register
            if (event.packet !is ServerboundContainerClosePacket) return@register
            isMelodyOpen = false
            currentStage = - 1
        }

        register<TickEvent.Start> {
            if (! isMelodyOpen) return@register
            if (UMinecraft.currentScreenObj == null) {
                isMelodyOpen = false
                return@register
            }

            if (currentStage == progressSlots.lastIndex) return@register
            for (i in progressSlots.indices) {
                if (i <= currentStage) continue

                if (player.containerMenu.getSlot(progressSlots[i]).item.`is`(Items.DYED_TERRACOTTA.lime())) {
                    if (sendProgress.value && (mode.value == 2 || msg.value.isNotBlank())) ChatUtils.sendPartyMessage(
                        when (mode.value) {
                            0 -> "${msg.value} ${i + 1}/3"
                            1 -> "${msg.value} ${(i + 1) * 33}%"
                            else -> odinProgress[i]
                        }
                    )
                    currentStage = i
                }
            }
        }

        register<ChatMessageEvent> {
            val text = event.unformattedText
            if (text == "The gate has been destroyed!" || text == "The Core entrance is opening!") {
                melodyStates.clear()
                return@register
            }
            if (! progressHud.value || LocationUtils.F7Phase != 3) return@register

            val (name, message) = partyRegex.find(text)?.destructured ?: return@register
            if (! message.contains("melody", true)) return@register
            val progress = MelodyDisplay.melodyProgress(message) ?: 0
            val state = melodyStates.getOrPut(name) { MelodyState(name, progress) }
            state.progress = maxOf(state.progress, progress)
        }

        register<WorldChangeEvent> {
            melodyStates.clear()
            isMelodyOpen = false
            currentStage = - 1
        }
    }

    private fun formatLine(state: MelodyState): String {
        val squares = (1 .. 3).joinToString("") { if (it <= state.progress) "§a■" else "§f■" }
        val clazz = DungeonListener.dungeonTeammates.find { it.name == state.name }?.clazz
        val classColor = clazz?.code ?: "&7"
        val className = clazz?.name ?: "Unknown"
        val label = when (showPlayer.value) {
            1 -> "$classColor$className"
            2 -> "&6${state.name}"
            3 -> "&6${state.name} &8($classColor$className&8)"
            else -> ""
        }
        return "$squares §7${state.progress}/3 $label".trim()
    }
}
