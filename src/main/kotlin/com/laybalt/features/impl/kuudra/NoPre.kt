// Ported from Odin (https://github.com/odtheking/Odin), BSD-3-Clause, Copyright (c) 2025 odtheking
package com.laybalt.features.impl.kuudra

import com.laybalt.config.types.ToggleSetting
import com.laybalt.event.impl.ChatMessageEvent
import com.laybalt.event.impl.WorldChangeEvent
import com.laybalt.features.Feature
import com.laybalt.utils.ChatUtils
import com.laybalt.utils.kuudra.KuudraUtils
import com.laybalt.utils.kuudra.Supply
import net.minecraft.world.phys.Vec3

object NoPre: Feature("Tells your party when the supply of your pre-spot (or its second supply) is missing in Kuudra.", "Pre-Spot Alert") {
    private val showCratePriority by ToggleSetting("Show Crate Priority", false)
        .withDescription("When a party member reports a missing supply, shows a title and a chat message telling you which supply to go for next, based on your pre-spot.")
    private val advanced by ToggleSetting("Advanced Mode", false)
        .withDescription("Uses the harder (faster) crate priority in some situations, e.g. pulling both Square and X Cannon.").showIf { showCratePriority.value }

    private val partyRegex = Regex("^Party > (\\[[^]]*?])? ?(\\w{1,16}): No ?(Triangle|X|Equals|Slash|xCannon|Square|Shop)!$")
    private const val PRE_LINE = "[NPC] Elle: Head over to the main platform, I will join you when I get a bite!"
    private const val START_LINE = "[NPC] Elle: Not again!"

    private var preSpot = Supply.None

    override fun init() {
        register<ChatMessageEvent> {
            if (! KuudraUtils.inKuudra) return@register
            val message = event.unformattedText

            when {
                message == PRE_LINE -> {
                    val playerLocation = mc.player?.blockPosition() ?: return@register
                    preSpot = when {
                        Supply.Triangle.pickUpSpot.closerThan(playerLocation, 15.0) -> Supply.Triangle
                        Supply.X.pickUpSpot.closerThan(playerLocation, 30.0) -> Supply.X
                        Supply.Equals.pickUpSpot.closerThan(playerLocation, 15.0) -> Supply.Equals
                        Supply.Slash.pickUpSpot.closerThan(playerLocation, 15.0) -> Supply.Slash
                        else -> Supply.None
                    }
                    ChatUtils.modMessage(if (preSpot == Supply.None) "§cDidn't register your pre-spot because you didn't get there in time." else "Pre-spot: ${preSpot.name}")
                }

                message == START_LINE -> {
                    if (preSpot == Supply.None) return@register
                    var second = false
                    var pre = false
                    KuudraUtils.giantZombies.forEach { supply ->
                        val supplyLoc = Vec3(supply.x, 76.0, supply.z)
                        when {
                            preSpot.pickUpSpot.closerToCenterThan(supplyLoc, 18.0) -> pre = true
                            preSpot == Supply.Triangle && Supply.Shop.pickUpSpot.closerToCenterThan(supplyLoc, 18.0) -> second = true
                            preSpot == Supply.X && Supply.xCannon.pickUpSpot.closerToCenterThan(supplyLoc, 16.0) -> second = true
                            preSpot == Supply.Slash && Supply.Square.pickUpSpot.closerToCenterThan(supplyLoc, 20.0) -> second = true
                        }
                    }
                    if (second && pre) return@register
                    val msg = when {
                        ! pre -> "No ${preSpot.name}!"
                        else -> when (preSpot) {
                            Supply.Triangle -> "No Shop!"
                            Supply.X -> "No xCannon!"
                            Supply.Slash -> "No Square!"
                            else -> return@register
                        }
                    }
                    ChatUtils.sendCommand("pc $msg")
                }

                partyRegex.matches(message) -> {
                    if (! showCratePriority.value) return@register
                    val missing = partyRegex.find(message)?.groupValues?.lastOrNull()?.let { name -> Supply.entries.find { it.name == name } } ?: return@register
                    val priority = cratePriority(missing).ifEmpty { return@register }
                    ChatUtils.showTitle(priority)
                    ChatUtils.modMessage("Crate Priority: $priority")
                }
            }
        }

        register<WorldChangeEvent> { preSpot = Supply.None }
    }

    private fun cratePriority(missing: Supply): String = when (missing) {
        Supply.Shop -> when (preSpot) {
            Supply.Triangle, Supply.X -> "Go X Cannon"
            Supply.Equals, Supply.Slash -> "Go Square, place on Shop"
            else -> ""
        }

        Supply.Triangle -> when (preSpot) {
            Supply.Triangle -> if (advanced.value) "Pull Square and X Cannon. Next: collect Shop" else "Pull Square. Next: collect Shop"
            Supply.X -> "Go X Cannon"
            Supply.Equals -> if (advanced.value) "Go Shop" else "Go X Cannon"
            Supply.Slash -> "Go Square, place on Triangle"
            else -> ""
        }

        Supply.Equals -> when (preSpot) {
            Supply.Triangle -> if (advanced.value) "Go Shop" else "Go X Cannon"
            Supply.X -> "Go X Cannon"
            Supply.Equals -> if (advanced.value) "Pull Square and X Cannon. Next: collect Shop" else "Pull Square. Next: collect Shop"
            Supply.Slash -> "Go Square, place on Equals"
            else -> ""
        }

        Supply.Slash -> when (preSpot) {
            Supply.Triangle -> "Go Square, place on Slash"
            Supply.X -> "Go X Cannon"
            Supply.Equals -> if (advanced.value) "Go Shop" else "Go X Cannon"
            Supply.Slash -> if (advanced.value) "Pull Square and X Cannon. Next: collect Shop" else "Pull Square. Next: collect Shop"
            else -> ""
        }

        Supply.Square -> when (preSpot) {
            Supply.Triangle, Supply.Equals -> "Go Shop"
            Supply.X, Supply.Slash -> "Go X Cannon"
            else -> ""
        }

        Supply.xCannon -> when (preSpot) {
            Supply.Triangle, Supply.Equals -> "Go Shop"
            Supply.Slash, Supply.X -> "Go Square, place on X Cannon"
            else -> ""
        }

        Supply.X -> when (preSpot) {
            Supply.Triangle -> "Go X Cannon"
            Supply.X -> if (advanced.value) "Pull Square and X Cannon. Next: collect Shop" else "Pull Square. Next: collect Shop"
            Supply.Equals -> if (advanced.value) "Go Shop" else "Go X Cannon"
            Supply.Slash -> "Go Square, place on X"
            else -> ""
        }

        else -> ""
    }
}
