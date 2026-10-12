// Ported from NoammAddons (features/impl/dev/Timer.kt)
package com.laybalt.features.impl.floor7

import com.laybalt.commands.CommandBuilder
import com.laybalt.commands.LbqHelp
import com.laybalt.event.EventBus
import com.laybalt.event.impl.DungeonEvent
import com.laybalt.event.impl.RenderOverlayEvent
import com.laybalt.event.impl.TickEvent
import com.laybalt.features.Feature
import com.laybalt.init.types.ICommandProvider
import com.laybalt.utils.ChatUtils
import com.laybalt.utils.render.Render2D.drawCenteredString
import gg.essential.universal.UResolution
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.decoration.ArmorStand

/**
 * NoammAddons' `/timer`: counts server ticks from Necron's death until the relics spawn, or between
 * `/timer start` and `/timer stop`. Also `/lbq timer`.
 */
object RelicTimer: Feature("Counts server ticks from Necron's death to the relics spawning; /timer start|stop for a manual tick timer."), ICommandProvider {
    private var ticks = 0

    private val ticker = EventBus.listener<TickEvent.Server> { ticks ++ }

    override fun CommandBuilder.command() {
        setName("timer")
        alsoUnderLbq("timer")
        category(LbqHelp.Category.TERMINALS)
        description("Server tick timer: /timer start|stop, no argument shows the count")
        requires("Enable the Relic Timer feature to use /timer.") { enabled }
        runs { ChatUtils.modMessage("Timer ticks: $ticks") }
        literal("start") {
            description("Starts counting server ticks")
            runs { start("cmd") }
        }
        literal("stop") {
            description("Stops the timer and prints the ticks")
            runs { end() }
        }
    }

    override fun init() {
        register<RenderOverlayEvent> {
            if (! ticker.isActive) return@register
            event.context.drawCenteredString(ticks.toString(), UResolution.scaledWidth / 2, UResolution.scaledHeight / 3, scale = 2.5f)
        }

        register<DungeonEvent.NecronDeathEvent> { start("Relics") }

        register<TickEvent.Start> {
            if (! ticker.isActive) return@register
            val world = mc.level ?: return@register
            val relicStands = world.entitiesForRendering().any {
                it is ArmorStand && it.getItemBySlot(EquipmentSlot.HEAD).hoverName.string.contains("Relic")
            }
            if (relicStands) end()
        }
    }

    override fun onDisable() {
        super.onDisable()
        if (ticker.isActive) ticker.unregister()
        ticks = 0
    }

    private fun start(source: String) {
        ticks = 0
        if (! ticker.isActive) ticker.register()
        ChatUtils.modMessage("Timer started ($source)")
    }

    private fun end() {
        if (! ticker.isActive) return ChatUtils.modMessage("The timer is not running.")
        ChatUtils.modMessage("Timer ticks: $ticks")
        ticker.unregister()
        ticks = 0
    }
}
