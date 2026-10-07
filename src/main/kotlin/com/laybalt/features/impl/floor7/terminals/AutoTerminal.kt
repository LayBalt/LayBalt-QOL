package com.laybalt.features.impl.floor7.terminals

import com.mojang.blaze3d.platform.InputConstants
//#if CHEAT

import com.laybalt.config.types.*
import com.laybalt.event.impl.*
import com.laybalt.event.priority.EventPriority
import com.laybalt.features.Feature
import com.laybalt.features.impl.floor7.terminals.TerminalListener.FIRST_CLICK_DELAY
import com.laybalt.features.impl.floor7.terminals.impl.*
import com.laybalt.ui.utils.Resolution
import com.laybalt.utils.*
import com.laybalt.utils.render.Render2D.drawCenteredString
import com.laybalt.utils.render.RenderHelper.width
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper

object AutoTerminal: Feature("Automatically clicks terminals for you.") {
    private val randomDelay by ToggleSetting("Random Delay", true).withDescription("Normal distributed by min and max").section("Settings").jsonName("randomDelay")
    private val autoDelay by SliderSetting("Click Delay", 150, 80, 500, 10, "ms").withDescription("Fixed delay between clicks in milliseconds.").hideIf { randomDelay.value }.jsonName("autoDelay")
    private val minRandomDelay by SliderSetting("Min Random Delay", 120, 50, 500, 10, "ms").withDescription("The minimum possible delay").showIf { randomDelay.value }.jsonName("minRandomDelay")
    private val maxRandomDelay by SliderSetting("Max Random Delay", 150, 80, 500, 10, "ms").withDescription("The maximum possible delay").showIf { randomDelay.value }.jsonName("maxRandomDelay")
    private val clickOrder by DropdownSetting("Click Order", 2, listOf("None", "Random", "Human", "Skizo")).withDescription("Human: Logic pathing. Skizo: Chaotic/Furthest.")
    private val invwalk by ToggleSetting("Fake InvWalk").withDescription("Draws the Term name and progress on screen rather then the solution")

    private val autoNumbers by ToggleSetting("Numbers", true).section("Terminals")
    private val autoColors by ToggleSetting("Colors", true)
    private val autoRubix by ToggleSetting("Rubix", true)
    private val autoRedGreen by ToggleSetting("Red-Green", true)
    private val autoStartWith by ToggleSetting("Start-With", true)

    private var lastClickTime = 0L
    private var lastClickedSlot: Int? = null

    override fun init() {
        hudElement(
            "AutoTerminal - FakeInvWalk",
            centered = { true },
            enabled = { invwalk.value },
            shouldDraw = { TerminalListener.currentHandler?.enabled() == true }
        ) { ctx, e ->
            val handler = if (e) NumberTerminal else TerminalListener.currentHandler !!
            val maxClicks = if (e) NumberTerminal.gridSize.run { first * second } else handler.maxClicks()
            val completed = if (e) 4 else handler.completedClicks()

            val title = "§3In Terminal (${handler.displayName})"
            val progress = "§b[${completed.coerceIn(0, maxClicks)}/$maxClicks]${handler.progressSuffix()}"

            ctx.drawCenteredString(title, 0, 0)
            if (maxClicks != null && maxClicks > 0) ctx.drawCenteredString(progress, 0, - 10f)

            maxOf(title.width(), progress.width()).toFloat() to 20f
        } defaults {
            x = Resolution.width / 2
            y = Resolution.height / 2 - Resolution.height / 10
            scale = 3f
        }

        register<TickEvent.Server> {
            val handler = TerminalListener.currentHandler ?: return@register
            if (handler is MelodyTerminal) return@register
            if (! handler.enabled()) return@register
            if (handler.solution.isEmpty()) return@register
            if (TerminalListener.checkFcDelay()) return@register
            if (System.currentTimeMillis() - lastClickTime < getDelay()) return@register
            lastClickTime = System.currentTimeMillis()

            handler.autoClick()
        }

        register<ContainerEvent.MouseClick>(EventPriority.HIGH) {
            val handler = TerminalListener.currentHandler ?: return@register
            if (handler.enabled()) event.isCanceled = true
        }

        register<ContainerEvent.Keyboard>(EventPriority.HIGH) {
            if (event.key.equalsOneOf(KeyMappingHelper.getBoundKeyOf(mc.options.keyInventory).value, InputConstants.KEY_ESCAPE)) return@register
            val handler = TerminalListener.currentHandler ?: return@register
            if (handler.enabled()) event.isCanceled = true
        }

        register<ScreenEvent.PreRender>(EventPriority.HIGH) {
            if (! invwalk.value) return@register
            val handler = TerminalListener.currentHandler ?: return@register
            if (handler.enabled()) event.isCanceled = true
        }

        register<TerminalEvent.Close> {
            lastClickedSlot = null
            lastClickTime = 0L
        }
    }

    private fun Terminal.autoClick() {
        val rawClick = if (this == NumberTerminal) solution.first()
        else when (clickOrder.value) {
            1 -> solution.random()
            2 -> HumanClickOrder.getBestClick(this)
            3 -> HumanClickOrder.getWorstClick(this)
            else -> solution.first()
        }

        if (lastClickedSlot == rawClick.slotId && this !is RubixTerminal) return

        val finalClick = if (this is RubixTerminal) getClickForSlot(rawClick.slotId) !! else rawClick

        lastClickedSlot = finalClick.slotId
        predict(finalClick)
        finalClick.send()
    }

    private fun Terminal.enabled() = when (this) {
        is NumberTerminal -> autoNumbers.value
        is ColorsTerminal -> autoColors.value
        is MelodyTerminal -> false // Auto Melody is not part of LayBalt QOL
        is RubixTerminal -> autoRubix.value
        is RedGreenTerminal -> autoRedGreen.value
        is StartWithTerminal -> autoStartWith.value
    }

    private fun getDelay() = when {
        TerminalListener.checkFcDelay() -> FIRST_CLICK_DELAY * 50
        randomDelay.value -> {
            val min = minRandomDelay.value.coerceAtLeast(0)
            val max = maxRandomDelay.value.coerceAtLeast(0)
            if (min == max) min else MathUtils.gaussianRandom(minOf(min, max), maxOf(min, max))
        }

        else -> autoDelay.value
    }.coerceAtLeast(0)
}
//#endif
