package com.laybalt.recorder

import com.laybalt.LayBaltQOL
import com.laybalt.event.EventBus
import com.laybalt.event.impl.ShutdownEvent
import com.laybalt.event.impl.TickEvent
import com.laybalt.event.impl.WorldChangeEvent
import com.laybalt.features.impl.fishing.FishingRecorder
import com.laybalt.features.impl.fishing.SeaCreatureTracker
import com.laybalt.utils.ChatUtils
import com.mojang.blaze3d.platform.InputConstants
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.ClientCommands
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLevelEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.client.KeyMapping
import net.minecraft.resources.Identifier
import net.minecraft.util.Util

/**
 * LayBalt Recorder: only the Fishing Recorder of LayBalt QOL, for other players to record their own fishing.
 * `/lbrec` (or the "Start/Stop Recording" key in Controls, unbound by default) starts and stops a recording,
 * `/lbrec folder` opens the folder with the recordings. It records only: it never clicks, moves or sends anything.
 */
object LayBaltRecorder: ClientModInitializer {
    private val toggleKey = KeyMapping(
        "key.${LayBaltQOL.MOD_ID}.toggle",
        InputConstants.Type.KEYSYM,
        InputConstants.UNKNOWN.value,
        KeyMapping.Category.register(Identifier.fromNamespaceAndPath(LayBaltQOL.MOD_ID, "main"))
    )

    override fun onInitializeClient() {
        KeyMappingHelper.registerKeyMapping(toggleKey)

        // the game events the shared recorder sources listen to (LayBalt QOL posts them the same way)
        ClientTickEvents.START_CLIENT_TICK.register { mc -> if (mc.level != null) EventBus.post(TickEvent.Start) }
        ClientLevelEvents.AFTER_CLIENT_LEVEL_CHANGE.register { _, _ -> EventBus.post(WorldChangeEvent) }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> EventBus.post(WorldChangeEvent) }
        ClientLifecycleEvents.CLIENT_STOPPING.register { EventBus.post(ShutdownEvent) }

        // the key works in game only (not while a screen is open), like LayBalt QOL's toggle key
        ClientTickEvents.END_CLIENT_TICK.register { mc ->
            while (toggleKey.consumeClick()) if (mc.gui.screen() == null) FishingRecorder.toggle()
        }

        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            dispatcher.register(
                ClientCommands.literal("lbrec")
                    .executes { FishingRecorder.toggle(); 1 }
                    .then(ClientCommands.literal("folder").executes {
                        Util.getPlatform().openFile(FishingRecorder.recordingsDir.apply { mkdirs() })
                        ChatUtils.modMessage("§7Your recordings: §f${FishingRecorder.recordingsDir.absolutePath}")
                        1
                    })
            )
        }

        // the tracker's listeners first: the recorder reads what it has seen this tick
        SeaCreatureTracker.init()
        FishingRecorder.initialize()
        LayBaltQOL.isLoaded = true
    }
}
