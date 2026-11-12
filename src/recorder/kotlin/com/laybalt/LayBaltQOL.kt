package com.laybalt

import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import org.slf4j.LoggerFactory

/**
 * The recorder-only jar's stand-in for LayBalt QOL's main object: only the names the shared recorder sources use
 * (the list is in `buildSrc/src/main/kotlin/lbq.recorder.gradle.kts`). The entrypoint is [com.laybalt.recorder.LayBaltRecorder].
 */
object LayBaltQOL {
    const val MOD_ID = "laybaltrecorder"
    const val MOD_NAME = "LayBalt Recorder"
    /** Recordings are saved to `config/LayBaltRecorder/fishing/recordings`. */
    const val CONFIG_DIR = "LayBaltRecorder"

    /** The session line's `mod`: `recorder-<version>`. */
    val MOD_VERSION: String by lazy {
        "recorder-" + FabricLoader.getInstance().getModContainer(MOD_ID).map { it.metadata.version.friendlyString }.orElse("dev")
    }

    val PREFIX: Component by lazy { Component.literal("§b§lLay§f§lB§c§lalt §8Recorder §4>>>§r") }

    @JvmField val logger = LoggerFactory.getLogger(MOD_NAME)
    @JvmStatic val mc: Minecraft get() = Minecraft.getInstance()
    @JvmField var isLoaded = false

    /** LayBalt QOL's debug flags: there are none here, the shared event code only asks. */
    val debugFlags: Set<String> = emptySet()
}
