// Partially ported from Odin (https://github.com/odtheking/Odin), BSD-3-Clause, Copyright (c) 2025 odtheking
package com.laybalt.features.impl.floor7.terminals

import com.mojang.blaze3d.platform.InputConstants
import com.laybalt.LayBaltQOL
import com.laybalt.config.types.*
import com.laybalt.event.impl.*
import com.laybalt.features.Feature
import com.laybalt.features.impl.floor7.terminals.impl.*
import com.laybalt.init.types.ICustomMenu
import com.laybalt.mixin.IAbstractContainerScreen
import com.laybalt.ui.utils.Resolution
import com.laybalt.utils.ColorUtils.chromaColor
import com.laybalt.utils.ColorUtils.isVisable
import com.laybalt.utils.ColorUtils.withAlpha
import com.laybalt.utils.equalsOneOf
import com.laybalt.utils.render.Render2D.drawBorder
import com.laybalt.utils.render.Render2D.drawCenteredString
import com.laybalt.utils.render.Render2D.drawFloatingRect
import com.laybalt.utils.render.Render2D.drawHorizontalGradient
import com.laybalt.utils.render.Render2D.drawRect
import com.laybalt.utils.render.Render2D.drawVerticalGradient
import gg.essential.universal.UGraphics
import gg.essential.universal.USound
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper
import net.minecraft.client.Options
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.protocol.game.ClientboundSoundPacket
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.entity.player.Inventory
import java.awt.Color
import kotlin.math.ceil

object TerminalSolver: Feature("Renders solutions for Floor 7 terminals."), ICustomMenu {
    private val redGreenTerm by ToggleSetting("Red-Green", true).section("Toggles")
    private val colorsTerm by ToggleSetting("Colors", true)
    private val startsWithTerm by ToggleSetting("Start-With", true)
    private val rubixTerm by ToggleSetting("Rubix", true)
    private val numbersTerm by ToggleSetting("Numbers", true)
    private val melodyTerm by ToggleSetting("Melody", true)

    private val soundsEnabled by ToggleSetting("Terminal Sounds", true).withDescription("Replaces Hypixel's terminal click pling with your own click sound.").section("Sounds")
    private val clickSound = createSoundSettings("Click Sound", SoundEvents.NOTE_BLOCK_PLING.value()) { soundsEnabled.value }
    private val completeSounds by ToggleSetting("Complete Sounds").withDescription("Plays a separate sound when your last click completes a terminal.")
    private val completeSound = createSoundSettings("Completion Sound", SoundEvents.EXPERIENCE_ORB_PICKUP) { completeSounds.value }.also { renameSoundSettings("Completion Sound") }
    private val gatePling by ToggleSetting("Gate & Core Pling", true).withDescription("Plays the pling sound on \"The gate has been destroyed!\" and \"The Core entrance is opening!\" while you are in a terminal (that pling is otherwise lost to the sound replacement).").showIf { soundsEnabled.value }

    private val renderMode by DropdownSetting("Render Mode", 0, listOf("Custom GUI", "Vanilla Overlay", "Recolored Vanilla")).withDescription("Custom GUI: the solver draws its own compact grid. Vanilla Overlay: the normal chest GUI with the solution slots coloured. Recolored Vanilla: the normal chest GUI with every terminal item hidden behind the background and only the solution coloured.").section("Settings - UI")
    private val vanillaTermSize by SliderSetting("Vanilla Term Size", 3, 0, 6, 1).withDescription("GUI scale used while a terminal is open in the vanilla render modes. 0 keeps your normal GUI scale, 6 = Auto.").showIf { renderMode.value != 0 }
    private val scale by SliderSetting("Custom Menu's Scale", 1f, 0.1f, 2f, 0.01f).showIf { renderMode.value == 0 }
    private val slotGap by SliderSetting("Slot Gap", 0f, 0f, 8f, 0.5f, "px").showIf { renderMode.value == 0 }
    private val padding by SliderSetting("Padding", 2f, 0f, 12f, 0.5f, "px").showIf { renderMode.value == 0 }
    private val slotStyle by DropdownSetting("Slot Style", 0, listOf("Rect", "Bordered-Rect", "Button"))
    private val backgroundColor by ColorSetting("Background Color", Color(0, 0, 0, 100)).withDescription("Background of the custom GUI, or the fill drawn over every terminal slot in Recolored Vanilla mode.").hideIf { renderMode.value == 1 }
    private val borderColor by ColorSetting("Border Color", Color(255, 255, 255)).showIf { renderMode.value == 0 }
    // NoammAddons ad0bb0bb
    private val rainbowBorder by ToggleSetting("Rainbow Border").withDescription("Animate a rainbow gradient around the terminal menu. Opacity follows Border Color.").showIf { renderMode.value == 0 }
    private val rainbowBorderSpeed by SliderSetting("Rainbow Border Speed", 1.0, 0.1, 3.0, 0.1).withDescription("How fast the rainbow moves around the border.").showIf { renderMode.value == 0 && rainbowBorder.value }
    private val titleColor by ColorSetting("Title Text Color", Color.WHITE).showIf { renderMode.value == 0 }
    private val overlayTextColor by ColorSetting("Overlay Text Color", Color.WHITE)
    private val solutionColor by ColorSetting("Solution Color", Color(0, 255, 0, 130))

    val clientPrediction by ToggleSetting("Client Prediction", true).withDescription("Updates the solution as soon as you click instead of waiting for Hypixel to confirm the click.").section("Solver Functionality")
    val breakTimeout by SliderSetting("Break Timeout", 500, 300, 800, 50, "ms").withDescription("How long should the solver wait for a click to be confirmed by hypixel")
    val rubixMode by DropdownSetting("Rubix Mode", 0, listOf("Fewest Clicks", "Left Clicks Only")).withDescription("Fewest Clicks mixes in right clicks to need the fewest clicks overall, Left Clicks Only never asks for a right click.")
    private val hotbarKeys by ToggleSetting("Hotbar Key Clicks", true).withDescription("Pressing a hotbar key (1-9) clicks the hovered terminal slot, like the drop key.")
    // NoammAddons 7d9bfe7d / 757f38a1: held keys repeat faster than this, which floods the server with clicks.
    private val maxCps by SliderSetting("Max CPS", 15, 1, 20, 1, " CPS").withDescription("How many drop (and hotbar) key presses can click per second; held keys repeat faster than this.")

    // NoammAddons 5dff2334: a range; each terminal you open blocks clicks for a random time in it (both ends equal = fixed).
    // Same json name as the old single-value slider, whose saved value reads as that fixed time.
    val firstClickProt by RangeSliderSetting("First Click Protection", 350 to 350, 0, 800, 10, "ms").withDescription("Clicks are blocked for this long after a terminal opens, to avoid getting kicked for clicking too early (recommended: 500 minus your ping). Set two different ends to make it a random time between them for each terminal (normally distributed); Auto Terminal's first click then comes at a varying time.").section("First Click Protection")
    val lagProtection by ToggleSetting("Account For Server Lag", true).withDescription("Also block clicks until the server has ticked a few times since the terminal opened, so a lagging server cannot make your first click too early.")
    val fcDelay by SliderSetting("Lag Protection Ticks", 7, 0, 16, 1, " ticks").withDescription("Server ticks to wait after a terminal opens (1 tick = 50ms, Melody adds 3).").showIf { lagProtection.value }.jsonName("FC delay, (In server ticks)")

    val positiveColor by ColorSetting("Rubix: Positive (+)", Color(0, 114, 255, 130)).section("Rubix").showIf { rubixTerm.value }
    val negativeColor by ColorSetting("Rubix: Negative (-)", Color(205, 0, 0, 130)).showIf { rubixTerm.value }

    val showNumbers by ToggleSetting("Show Numbers").section("Numbers").showIf { numbersTerm.value }
    val firstColor by ColorSetting("1st Click Color", Color(0, 255, 0, 130)).showIf { numbersTerm.value }
    val secondColor by ColorSetting("2nd Click Color", Color(255, 255, 120, 130)).showIf { numbersTerm.value }
    val thirdColor by ColorSetting("3rd Click Color", Color(200, 0, 0, 130)).showIf { numbersTerm.value }
    val fourthColor by ColorSetting("4th Click Color", Color(110, 0, 0, 130)).withDescription("Colour of the fourth pane to click.").showIf { numbersTerm.value }

    val columnColor by ColorSetting("Melody: Column", Color(255, 0, 255, 130)).section("Melody").showIf { melodyTerm.value }
    val indicatorColor by ColorSetting("Melody: Indicator", Color(255, 116, 0, 130)).showIf { melodyTerm.value }
    val wrongColor by ColorSetting("Melody: Wrong", Color(255, 0, 0, 130)).showIf { melodyTerm.value }

    private val p3sim get() = mc.connection?.serverData?.ip?.contains("p3sim", ignoreCase = true) == true

    /** True when the solver replaces the terminal click sounds (the simulator then stays silent itself). */
    val replacesSounds get() = enabled && soundsEnabled.value

    private val gateRegex = Regex("^The gate has been destroyed!$")
    private val coreRegex = Regex("^The Core entrance is opening!$")

    private var cachedMinCol: Int? = null
    private var cachedMinRow: Int? = null
    private var hoveredSlot: Int? = null
    private var lastSoundTime = 0L
    private var lastKeyClickTime = 0L
    private var completionPlayed = false

    override fun init() {
        register<TerminalEvent.Open> {
            cachedMinCol = null
            cachedMinRow = null
            hoveredSlot = null
            completionPlayed = false
            if (renderMode.value != 0 && vanillaTermSize.value != 0) mc.resizeGui()
        }

        register<TerminalEvent.Close> {
            if (renderMode.value != 0 && vanillaTermSize.value != 0) mc.execute { mc.resizeGui() }
        }

        register<ScreenEvent.PreRender> {
            if (renderMode.value != 0) return@register
            if (! TerminalListener.inTerm) return@register
            val handler = TerminalListener.currentHandler ?: return@register
            if (! handler.enabled()) return@register
            event.isCanceled = true

            Resolution.push(event.context)

            val uiScale = 3f * scale.value
            val screenWidth = Resolution.width / uiScale
            val screenHeight = Resolution.height / uiScale
            val (gridWidth, gridHeight) = handler.gridSize
            val gap = slotGap.value
            val pad = padding.value
            val slotSize = 16f

            val width = gridWidth * slotSize + (gridWidth - 1) * gap
            val height = gridHeight * slotSize + (gridHeight - 1) * gap
            val offsetX = screenWidth / 2f - width / 2f
            val offsetY = screenHeight / 2f - height / 2f
            val mx = (Resolution.getMouseX() / uiScale) - offsetX
            val my = (Resolution.getMouseY() / uiScale) - offsetY

            event.context.pose().pushMatrix()
            event.context.pose().scale(uiScale, uiScale)
            event.context.pose().translate(offsetX, offsetY)

            if (titleColor.value.isVisable()) event.context.drawCenteredString(handler.displayName, width / 2f, - 15f - pad, color = titleColor.value, scale = 1.2f)
            if (backgroundColor.value.isVisable()) event.context.drawRect(- pad, - pad, width + pad * 2, height + pad * 2, backgroundColor.value)
            if (borderColor.value.isVisable()) {
                if (rainbowBorder.value) drawRainbowBorder(event.context, - pad, - pad, width + pad * 2, height + pad * 2)
                else event.context.drawBorder(- pad, - pad, width + pad * 2, height + pad * 2, borderColor.value)
            }

            val solutionSlots = handler.solution.map { it.slotId }
            val minCol = cachedMinCol ?: if (solutionSlots.isEmpty()) 0 else solutionSlots.minOf { it % 9 }.also { cachedMinCol = it }
            val minRow = cachedMinRow ?: if (solutionSlots.isEmpty()) 0 else solutionSlots.minOf { it / 9 }.also { cachedMinRow = it }

            fun slotPos(slot: Int): Pair<Float, Float> {
                val col = slot % 9 - minCol
                val row = slot / 9 - minRow
                val x = col * (slotSize + gap)
                val y = row * (slotSize + gap)
                return x to y
            }

            hoveredSlot = run {
                if (mx < 0 || my < 0 || mx > width && my > height) return@run null

                val cell = slotSize + gap
                val col = (mx / cell).toInt() + minCol
                val row = (my / cell).toInt() + minRow

                return@run row * 9 + col
            }

            handler.solution.forEachIndexed { index, click ->
                val slot = click.slotId
                val (slotX, slotY) = slotPos(slot)

                handler.renderSlot(event.context, slotX, slotY, index, click, solutionColor.value)

                val item = TerminalListener.currentItems[slot]
                if (item != null && "terminal" in LayBaltQOL.debugFlags) {
                    event.context.item(item, slotX.toInt(), slotY.toInt())
                    event.context.itemDecorations(mc.font, item, slotX.toInt(), slotY.toInt())
                }
            }

            handler.render(event.context, solutionColor.value, ::slotPos, mx, my, ::hoveredSlot)

            event.context.pose().popMatrix()
            Resolution.pop(event.context)
        }

        // Vanilla render modes: colour the real chest slots instead of drawing a custom GUI.
        register<ContainerEvent.Render.Slot.Pre> {
            if (renderMode.value == 0 || ! TerminalListener.inTerm) return@register
            val handler = TerminalListener.currentHandler ?: return@register
            if (! handler.enabled()) return@register
            val slot = event.slot
            if (slot.container is Inventory || slot.index !in 0 until handler.slotCount) return@register

            val recolored = renderMode.value == 2
            if (recolored && backgroundColor.value.isVisable()) event.context.drawRect(slot.x - 1, slot.y - 1, 18, 18, backgroundColor.value)
            val drawn = handler.renderVanillaSlot(event.context, slot.index, slot.x, slot.y, solutionColor.value)
            if (drawn || recolored) event.isCanceled = true
        }

        register<ContainerEvent.Render.Tooltip> {
            if (renderMode.value == 0 || ! TerminalListener.inTerm) return@register
            if (TerminalListener.currentHandler?.enabled() != true) return@register
            event.isCanceled = true
        }

        register<ContainerEvent.MouseClick> {
            val handler = TerminalListener.currentHandler ?: return@register
            if (! handler.enabled()) return@register
            event.isCanceled = true
            handler.click(event.screen)
        }

        register<ContainerEvent.Keyboard> {
            if (event.key.equalsOneOf(KeyMappingHelper.getBoundKeyOf(mc.options.keyInventory).value, InputConstants.KEY_ESCAPE)) return@register
            val handler = TerminalListener.currentHandler ?: return@register
            if (! handler.enabled()) return@register
            event.isCanceled = true

            val isDropKey = event.key == KeyMappingHelper.getBoundKeyOf(mc.options.keyDrop).value
            val isHotbarKey = hotbarKeys.value && mc.options.keyHotbarSlots.any { KeyMappingHelper.getBoundKeyOf(it).value == event.key }
            if (! isDropKey && ! isHotbarKey) return@register
            if (System.currentTimeMillis() - lastKeyClickTime < 1000 / maxCps.value.coerceAtLeast(1)) return@register
            lastKeyClickTime = System.currentTimeMillis()
            handler.click(event.screen)
        }

        register<MainThreadPacketReceivedEvent.Pre> {
            if (! soundsEnabled.value) return@register
            if (! TerminalListener.inTerm) return@register
            val packet = event.packet as? ClientboundSoundPacket ?: return@register
            if (p3sim) {
                if (packet.sound.value() != SoundEvents.EXPERIENCE_ORB_PICKUP) return@register
                if (packet.volume != 1f || packet.pitch != 1f) return@register
            }
            else {
                if (packet.sound.value() != SoundEvents.NOTE_BLOCK_PLING.value()) return@register
                if (packet.volume != 8f || packet.pitch != 4.047619f) return@register
            }

            event.isCanceled = true
        }

        register<PacketEvent.Sent> {
            if (! TerminalListener.inTerm || TerminalListener.simScreen != null) return@register
            val packet = event.packet as? ServerboundContainerClickPacket ?: return@register
            playClickSound(TerminalListener.currentHandler, packet.slotNum().toInt())
        }

        register<TerminalActionEvent.Click> {
            if (event.simulated) playClickSound(event.handler, event.click.slotId)
        }

        register<TerminalActionEvent.Solve> {
            // Fallback when the completing click could not be detected (e.g. Client Prediction off).
            if (event.simulated || ! completeSounds.value || completionPlayed) return@register
            completionPlayed = true
            completeSound.invoke()
        }

        register<ChatMessageEvent> {
            if (! soundsEnabled.value || ! gatePling.value || ! TerminalListener.inTerm) return@register
            val text = event.unformattedText
            if (gateRegex.matches(text) || coreRegex.matches(text)) USound.playSoundStatic(SoundEvents.NOTE_BLOCK_PLING.value(), 1f, 1f)
        }
    }

    private fun playClickSound(handler: Terminal?, slot: Int) {
        val completes = when (handler) {
            null -> false
            is MelodyTerminal -> slot == MelodyTerminal.claySlots.last()
            else -> handler.solution.isEmpty()
        }

        if (completes && completeSounds.value && ! completionPlayed) {
            completionPlayed = true
            completeSound.invoke()
            return
        }

        if (! soundsEnabled.value) return
        if (System.currentTimeMillis() - lastSoundTime <= 2) return
        lastSoundTime = System.currentTimeMillis()
        clickSound.invoke()
    }

    /** Gives the extra sound settings created by [createSoundSettings] unique config keys (they all share "Volume"/"Pitch"/...). */
    private fun renameSoundSettings(prefix: String) {
        configSettings.toList().takeLast(4).filter { it.name != prefix }.forEach { it.jsonName = "$prefix ${it.name}" }
    }

    /** GUI scale forced while a terminal is open in a vanilla render mode, or -1 for none. Used by MixinMinecraftTerminalScale. */
    @JvmStatic
    fun guiScaleOverride(): Int {
        if (! enabled || renderMode.value == 0 || vanillaTermSize.value == 0) return - 1
        if (! TerminalListener.inTerm || TerminalListener.currentHandler?.enabled() != true) return - 1
        return if (vanillaTermSize.value >= 6) Options.AUTO_GUI_SCALE else vanillaTermSize.value
    }

    /** NoammAddons ad0bb0bb: a 1px border of 4px gradient segments whose hue runs around the perimeter. */
    private fun drawRainbowBorder(ctx: GuiGraphicsExtractor, x: Float, y: Float, width: Float, height: Float) {
        val time = System.currentTimeMillis() * rainbowBorderSpeed.value
        val perimeter = 2f * (width + height)
        val alpha = borderColor.value.alpha

        fun colorAt(distance: Float) = chromaColor(time, period = 5000.0, offset = distance / perimeter).withAlpha(alpha)

        fun edge(ex: Float, ey: Float, length: Float, start: Float, direction: Float, horizontal: Boolean) {
            repeat(ceil(length / 4f).toInt()) { index ->
                val offset = index * 4f
                val size = minOf(4f, length - offset)
                val first = colorAt(start + direction * offset)
                val second = colorAt(start + direction * (offset + size))

                if (horizontal) ctx.drawHorizontalGradient(ex + offset, ey, size, 1f, first, second)
                else ctx.drawVerticalGradient(ex, ey + offset, 1f, size, first, second)
            }
        }

        edge(x, y, width, 0f, 1f, true)
        edge(x + width - 1f, y + 1f, height - 2f, width + 1f, 1f, false)
        edge(x, y + height - 1f, width, 2f * width + height, - 1f, true)
        edge(x, y + 1f, height - 2f, perimeter - 1f, - 1f, false)
    }

    fun spanFor(count: Int) = count * 16f + (count - 1) * slotGap.value

    fun drawSlot(ctx: GuiGraphicsExtractor, x: Number, y: Number, color: Color, w: Number = 16, h: Number = 16) {
        when (slotStyle.value) {
            0 -> ctx.drawRect(x, y, w, h, color)
            1 -> {
                ctx.drawRect(x, y, w, h, color.withAlpha(40))
                ctx.drawBorder(x, y, w, h, color)
            }

            2 -> ctx.drawFloatingRect(x, y, w, h, color.darker())
        }
    }

    fun drawCenteredText(ctx: GuiGraphicsExtractor, text: String, slotX: Number, slotY: Number) {
        val centerX = slotX.toFloat() + 8f
        val centerY = slotY.toFloat() + 8f - UGraphics.getFontHeight() / 2
        ctx.drawCenteredString(text, centerX, centerY, color = overlayTextColor.value)
    }

    private fun Terminal.click(screen: AbstractContainerScreen<*>) {
        if (TerminalListener.checkFcDelay()) return
        val click = if (renderMode.value == 0) hoveredSlot?.let(::getClickForSlot)
        else (screen as IAbstractContainerScreen).hoveredSlot
            ?.takeIf { it.container !is Inventory }
            ?.let { getVanillaClick(it.index) }

        click ?: return
        if (clientPrediction.value) predict(click)
        click.send(clientPrediction.value)
    }

    private fun Terminal.enabled() = when (this) {
        is NumberTerminal -> numbersTerm.value
        is ColorsTerminal -> colorsTerm.value
        is MelodyTerminal -> melodyTerm.value
        is RubixTerminal -> rubixTerm.value
        is RedGreenTerminal -> redGreenTerm.value
        is StartWithTerminal -> startsWithTerm.value
    }

    override fun isActive() = TerminalListener.inTerm && renderMode.value == 0
}
