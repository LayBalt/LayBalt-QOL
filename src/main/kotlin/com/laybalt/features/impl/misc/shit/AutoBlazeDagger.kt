package com.laybalt.features.impl.misc.shit

//#if CHEAT

import com.laybalt.config.types.SliderSetting
import com.laybalt.event.impl.*
import com.laybalt.event.priority.EventPriority
import com.laybalt.features.Feature
import com.laybalt.utils.ChatUtils.unformattedText
import com.laybalt.utils.PlayerUtils
import com.laybalt.utils.ThreadUtils
import com.laybalt.utils.items.ItemUtils.skyblockId
import com.laybalt.utils.location.LocationUtils
import com.laybalt.utils.location.WorldType
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.phys.EntityHitResult

object AutoBlazeDagger: Feature("Automatically swaps to the correct dagger for blaze!") {
    private val swapDelay by SliderSetting("Swap Delay", 100, 0, 300, 1).withDescription("Delay to swap in miliseconds")
    private var lastSwap = System.currentTimeMillis()
    private var clicked = false

    override fun init() {
        register<TickEvent.Start> {
            if (LocationUtils.world != WorldType.CrimsonIsle) return@register
            val hit = mc.hitResult as? EntityHitResult ?: return@register

            val armorStands = level.entitiesForRendering().filter {
                it is ArmorStand && it.distanceTo(hit.entity) < 5
            }

            armorStands.forEach { armor ->
                val targetShield = HellionShield.fromText(armor.displayName.unformattedText) ?: return@forEach
                val dagger = Dagger.entries.find { targetShield in it.shields } ?: return@forEach
                trySwap(dagger, targetShield)
                return@register
            }
        }

        register<MainThreadPacketReceivedEvent.Pre>(EventPriority.HIGH) {
            val packet = event.packet as? ClientboundSetSubtitleTextPacket ?: return@register
            Dagger.updateActiveFromTitle(packet.text.unformattedText) ?: return@register
            clicked = false
        }

        register<LayBaltDebugFlagEvent.Add> {
            if (event.flag != "dagger") return@register
            val dagger = Dagger.fromStack(player.mainHandItem) ?: return@register

            ThreadUtils.scheduledTask(4) {
                trySwap(dagger.other(), dagger.activeShield().other())
            }
            event.cancel()
        }
    }

    private fun trySwap(dagger: Dagger, targetShield: HellionShield) {
        if (System.currentTimeMillis() - lastSwap < swapDelay.value) return
        if (clicked) return
        val heldStack = PlayerUtils.getHotbarSlot(player.inventory.selectedSlot) ?: return
        val currentDagger = Dagger.fromStack(heldStack) ?: return

        for (i in 0 .. 8) {
            val stack = PlayerUtils.getHotbarSlot(i) ?: continue
            if (stack.skyblockId !in dagger.skyblockIds) continue
            if (player.inventory.selectedSlot == i) continue
            player.inventory.selectedSlot = i
            lastSwap = System.currentTimeMillis()
            return
        }

        if (currentDagger == dagger && dagger.activeShield() != targetShield) {
            PlayerUtils.rightClick()
            dagger.activeShield().active = false
            dagger.activeShield().other().active = true
            lastSwap = System.currentTimeMillis()
            clicked = true
            return
        }
    }
}
//#endif