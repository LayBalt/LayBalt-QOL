package com.laybalt.init

import com.laybalt.LayBaltQOL.mc
import com.laybalt.init.types.ICustomMenu
import com.laybalt.utils.catch
import net.fabricmc.loader.api.FabricLoader

object ModCompatibility {
    val customMenus = mutableListOf<ICustomMenu>()

    @JvmStatic fun isModLoaded(modid: String) = FabricLoader.getInstance().isModLoaded(modid)
    @JvmStatic fun isCustomMenuActive() = customMenus.any(ICustomMenu::isActive)

    fun disableBlockstateCulling() = catch {
        if (! isModLoaded("moreculling")) return@catch
        val main = Class.forName("ca.fxco.moreculling.MoreCulling")
        val config = main.getDeclaredField("CONFIG").get(null)

        val blockStateCulling = config?.javaClass?.getDeclaredField("useBlockStateCulling")
        blockStateCulling?.isAccessible = true
        blockStateCulling?.setBoolean(config, false)
        mc.levelExtractor.allChanged()
    }

    const val bobby_chunk = "de.johni0702.minecraft.bobby.FakeChunk"
    val bobbyManagesWorlds by lazy {
        runCatching {
            if (! isModLoaded("bobby")) return@runCatching false
            val bobby = Class.forName("de.johni0702.minecraft.bobby.Bobby").getMethod("getInstance").invoke(null)
            val config = bobby.javaClass.getMethod("getConfig").invoke(bobby)
            config.javaClass.getMethod("isDynamicMultiWorld").invoke(config) as Boolean
        }.getOrDefault(false)
    }
}