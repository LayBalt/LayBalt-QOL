@file:Suppress("UNCHECKED_CAST")

package com.laybalt.init

import com.laybalt.LayBaltQOL
import com.laybalt.commands.CommandBuilder
import com.laybalt.commands.LbqHelp
import com.laybalt.config.ConfigManager
import com.laybalt.config.ForeignConfigImport
import com.laybalt.config.LegacyConfigImport
import com.laybalt.event.EventBus.register
import com.laybalt.event.impl.RenderOverlayEvent
import com.laybalt.features.Feature
import com.laybalt.features.FeatureManager
import com.laybalt.init.types.ICommandProvider
import com.laybalt.init.types.ICustomMenu
import com.laybalt.init.types.ISelfInit
import com.laybalt.ui.hud.HudEditorScreen
import com.laybalt.ui.utils.Resolution
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import gg.essential.universal.UMinecraft
import io.github.classgraph.ClassGraph
import io.github.classgraph.ClassInfo
import io.github.classgraph.ScanResult
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import kotlin.system.measureTimeMillis

class ClassGraphInitializer {
    fun initAll() = ClassGraph().enableAllInfo().apply {
        acceptPackages(LayBaltQOL::class.java.`package`.name)
        overrideClassLoaders(Thread.currentThread().contextClassLoader)
    }.scan().use { scan ->
        val time = measureTimeMillis {
            scan.registerSelfInits()
            scan.registerCustomMenus()
            scan.registerCommands()
            scan.registerFeatures()
        }

        LayBaltQOL.logger.info("${this::class.simpleName} initialized in $time ms.")
    }

    private fun ScanResult.registerSelfInits() {
        getClassesImplementing(ISelfInit::class.java).forEach { ci ->
            (ci.getInstance<ISelfInit>()).init()
        }
    }

    private fun ScanResult.registerCustomMenus() {
        getClassesImplementing(ICustomMenu::class.java).forEach { ci ->
            ModCompatibility.customMenus.add(ci.getInstance())
        }
    }

    private fun ScanResult.registerCommands() {
        val providers = getClassesImplementing(ICommandProvider::class.java).map { it.getInstance<ICommandProvider>() }

        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            val help = mutableListOf<CommandBuilder.HelpEntry>()
            providers.forEach { provider ->
                val commandBuilder = CommandBuilder()
                with(provider) { commandBuilder.command() }

                // Several providers register the same /lbq root: brigadier merges their subcommand trees.
                commandBuilder.build().forEach { root ->
                    dispatcher.register(root as LiteralArgumentBuilder<FabricClientCommandSource>)
                }
                help += commandBuilder.lbqHelpEntries()
            }
            LbqHelp.entries = help
        }
    }

    private fun ScanResult.registerFeatures() {
        getSubclasses(Feature::class.java).forEach { classInfo ->
            val feature = classInfo.getInstance<Feature>().apply(Feature::initialize)
            FeatureManager.hudElements.addAll(feature.hudElements)
            FeatureManager.features.add(feature)
        }

        ConfigManager.load()
        LegacyConfigImport.run()
        // NoammAddons files were already copied by the preLaunch entrypoint (ForeignConfigPreLaunch); Odin/OdinClient need the features.
        ForeignConfigImport.runAfterConfigLoad()

        register<RenderOverlayEvent> {
            if (UMinecraft.currentScreenObj is HudEditorScreen) return@register

            Resolution.push(event.context)
            // NoammAddons e7ada2f7: skip disabled elements before evaluating their (possibly costly) shouldDraw
            FeatureManager.hudElements.forEach { if (it.toggle && it.shouldDraw) it.renderElement(event.context, false) }
            Resolution.pop(event.context)
        }
    }

    private fun <T> ClassInfo.getInstance() = loadClass().getDeclaredField("INSTANCE").get(null) as T
}