package com.laybalt.features.impl.dev

import com.laybalt.LayBaltQOL
import com.laybalt.config.types.ButtonSetting
import com.laybalt.config.types.ToggleSetting
import com.laybalt.features.Feature
import com.laybalt.features.impl.dev.cosmetics.CosmeticData
import com.laybalt.features.impl.dev.cosmetics.badges.BadgeManager
import com.laybalt.features.impl.dev.cosmetics.badges.BadgeText
import com.laybalt.features.impl.dev.text.TextReplacer
import com.laybalt.ui.notification.NotificationManager
import com.laybalt.utils.ChatUtils
import com.laybalt.utils.MathUtils.vec
import com.laybalt.utils.NumbersUtils
import com.laybalt.utils.network.ProfileUtils
import com.laybalt.utils.network.WebUtils
import com.mojang.authlib.GameProfile
import com.mojang.blaze3d.vertex.PoseStack
import kotlinx.coroutines.*
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey
import net.minecraft.client.player.AbstractClientPlayer
import net.minecraft.client.renderer.entity.state.AvatarRenderState
import net.minecraft.world.entity.Avatar
import java.util.*
import java.util.concurrent.*
import kotlin.math.abs

object Cosmetics: Feature(toggled = true) {
    @JvmStatic val customNames by ToggleSetting("Show Custom Names", true)
    @JvmStatic val showBadges by ToggleSetting("Show Badges", true)
    @JvmStatic val loreNames by ToggleSetting("Show Name in Lore", true).showIf { customNames.value }

    val customSizes by ToggleSetting("Show Custom Sizes", true)
    val showHalo by ToggleSetting("Show Halos", true)
    val showDragonWings by ToggleSetting("Show Dragon Wings", true)
    val reload by ButtonSetting("Reload Cosmetics") {
        if (System.currentTimeMillis() - lastReload >= 15_000) init()
        else NotificationManager.push("Cosmetics", "Please wait another ${NumbersUtils.formatTime(150_000 - (System.currentTimeMillis() - lastReload))} before reloading again.")
    }

    private lateinit var cosmeticPeople: Map<UUID, CosmeticData>
    private val profileNames = ConcurrentHashMap<UUID, String>()
    private var lastReload = System.currentTimeMillis()

    override fun init() {
        scope.launch(Dispatchers.IO) {
            lastReload = System.currentTimeMillis()
            LayBaltQOL.logger.info("fetching cosmeticPeople")

            launch { BadgeManager.load() }
            WebUtils.getAs<Map<String, CosmeticData>>("https://api.noamm.org/cosmeticPeople.json").onSuccess { data ->
                TextReplacer.ready = false
                cosmeticPeople = data.mapKeys { UUID.fromString(it.key) }
                BadgeText.init(cosmeticPeople)

                coroutineScope {
                    val customNames = HashMap<String, String>()
                    val jobs = cosmeticPeople.filter { it.value.hasCustomName }.map { (uuid, cosmetic) ->
                        async {
                            val resolvedName = profileNames[uuid] ?: ProfileUtils.getNameByUUID(uuid).map { it.name }.getOrNull() ?: return@async
                            profileNames[uuid] = resolvedName
                            customNames[resolvedName] = cosmetic.name
                        }
                    }

                    jobs.awaitAll()
                    TextReplacer.init(customNames)
                }
            }.onFailure { cause ->
                LayBaltQOL.logger.error("Failed to load cosmetic people", cause)
                ChatUtils.modMessage("&cFailed to load cosmetic people: ${cause.message}")
            }.onSuccess {
                LayBaltQOL.logger.info("loaded all ${cosmeticPeople.size} cosmetic people")
            }
        }
    }

    @JvmStatic
    fun extractRenderStateHook(avatar: Avatar, state: AvatarRenderState) {
        if (! enabled) return
        if (avatar !is AbstractClientPlayer) return
        state.setData(GAME_PROFILE_KEY, avatar.gameProfile)
        state.setData(SNEAKING_KEY, avatar.isCrouching)
    }

    @JvmStatic
    fun scaleHook(state: AvatarRenderState, poseStack: PoseStack) {
        val gameProfile = state.getData(GAME_PROFILE_KEY) ?: return
        val data = cosmeticDataFor(gameProfile.id) ?: return

        if (customSizes.value && data.hasCustomSize) {
            if (data.sizeY < 0) poseStack.translate(0f, data.sizeY * 2f, 0f)
            poseStack.scale(data.sizeX, data.sizeY, data.sizeZ)
        }

        state.nameTagAttachment?.let { pos ->
            var scaleY = 1f
            var offset = 0f

            if (customSizes.value && data.hasCustomSize) scaleY = abs(data.sizeY)
            if (showHalo.value && data.hasHalo) offset += 0.15f

            state.nameTagAttachment = vec(pos.x, (pos.y + 0.15) * scaleY + offset, pos.z)
        }
    }

    fun cosmeticDataFor(uuid: UUID) = if (::cosmeticPeople.isInitialized) cosmeticPeople[uuid] else null
    val GAME_PROFILE_KEY = RenderStateDataKey.create<GameProfile> { "${LayBaltQOL.MOD_ID}:game_profile" }
    val SNEAKING_KEY = RenderStateDataKey.create<Boolean> { "${LayBaltQOL.MOD_ID}:sneaking" }
}