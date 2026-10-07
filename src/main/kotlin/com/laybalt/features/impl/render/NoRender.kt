package com.laybalt.features.impl.render

import com.laybalt.config.types.ToggleSetting
import com.laybalt.event.impl.CheckEntityRenderEvent
import com.laybalt.event.impl.TickEvent
import com.laybalt.features.Feature
import com.laybalt.ui.notification.NotificationManager
import net.minecraft.client.particle.CampfireSmokeParticle
import net.minecraft.client.particle.ElderGuardianParticle
import net.minecraft.client.particle.FireworkParticles
import net.minecraft.client.particle.HugeExplosionParticle
import net.minecraft.client.particle.HugeExplosionSeedParticle
import net.minecraft.client.particle.Particle
import net.minecraft.client.particle.TerrainParticle
import net.minecraft.client.renderer.entity.state.AvatarRenderState
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.boss.enderdragon.EndCrystal
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.entity.projectile.arrow.AbstractArrow
import net.minecraft.world.entity.projectile.arrow.ThrownTrident
import net.minecraft.world.entity.projectile.throwableitemprojectile.AbstractThrownPotion
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEgg
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownExperienceBottle

/**
 * One switchboard of independent "hide X" toggles.
 *
 * Deliberately NOT here (already covered elsewhere, do not duplicate):
 * fire / water / in-block / portal overlays, nausea, blindness, lava fog and the damage tilt live in Camera Helper,
 * the hand swing animation lives in Animations.
 *
 * Hooks: [CheckEntityRenderEvent] and [TickEvent.End] here, plus
 * @see com.laybalt.mixin.MixinParticleEngineNoRender
 * @see com.laybalt.mixin.MixinHudNoRender
 * @see com.laybalt.mixin.MixinScreenNoRender
 * @see com.laybalt.mixin.MixinEntityRenderDispatcherNoRender
 * @see com.laybalt.mixin.MixinHumanoidArmorLayerNoRender
 * @see com.laybalt.mixin.MixinLivingEntityRendererNoRender
 * @see com.laybalt.mixin.MixinSpawnerRendererNoRender
 * @see com.laybalt.mixin.MixinAbstractSignRendererNoRender
 * @see com.laybalt.mixin.MixinWeatherEffectRendererNoRender
 * @see com.laybalt.mixin.MixinDarknessFogEnvironmentNoRender
 * @see com.laybalt.mixin.MixinLightmapFullbright (darkness light-map pulse)
 * @see com.laybalt.mixin.MixinAtmosphericFogEnvironmentWorldVisuals
 * @see com.laybalt.mixin.MixinFogRendererWorldVisuals
 */
object NoRender: Feature(
    "Hides visual clutter: chosen entities, particles, HUD elements, fog, weather and more. " +
        "Fire, water, block and portal overlays, nausea, blindness, lava fog and the damage tilt are in Camera Helper; " +
        "the hand swing animation is in Animations."
) {
    // ---------------------------------------------------------------- Entities
    private val arrows by ToggleSetting("Arrows")
        .withDescription("Hides arrows, both flying ones and ones stuck in blocks or mobs").section("Entities")
    private val potions by ToggleSetting("Thrown Potions")
        .withDescription("Hides thrown splash and lingering potions")
    private val xpBottles by ToggleSetting("XP Bottles")
        .withDescription("Hides thrown bottles o' enchanting")
    private val eggs by ToggleSetting("Eggs")
        .withDescription("Hides thrown eggs")
    private val items by ToggleSetting("Dropped Items")
        .withDescription("Hides item entities lying on the ground")
    private val crystals by ToggleSetting("End Crystals")
        .withDescription("Hides end crystals")
    private val armorStands by ToggleSetting("Armor Stands")
        .withDescription("Hides armor stands. On SkyBlock most holograms and mob name tags are armor stands, so those disappear as well")
    @JvmStatic val armor by ToggleSetting("Armor")
        .withDescription("Hides armor worn by players and mobs")
    @JvmStatic val entityFire by ToggleSetting("Entity Fire", true)
        .withDescription("Hides the flames drawn on burning entities")
    @JvmStatic val spawnerEntity by ToggleSetting("Spawner Mob")
        .withDescription("Hides the small spinning mob inside monster spawners")
    private val translucentPlayers by ToggleSetting("Translucent Players")
        .withDescription("Makes other players see-through while they stand right next to you, so they do not block your view. Worn armor and capes stay opaque")

    // ---------------------------------------------------------------- Auto clean
    private val autoClean by ToggleSetting("Auto Clean")
        .withDescription("Deletes piles of arrows, dropped items, XP bottles and thrown potions on your client when there are too many (more than 64 arrows, 16 items, 16 XP bottles or 8 potions). Other players are not affected")
        .section("Auto Clean")
    private val cleanNotification by ToggleSetting("Clean Notification", true)
        .withDescription("Shows a notification whenever Auto Clean removes entities").showIf { autoClean.value }

    // ---------------------------------------------------------------- Particles
    private val elderGuardian by ToggleSetting("Elder Guardian")
        .withDescription("Hides the elder guardian curse face that pops up on the screen").section("Particles")
    private val explosions by ToggleSetting("Explosions")
        .withDescription("Hides large explosion particles")
    private val campfire by ToggleSetting("Campfire Smoke")
        .withDescription("Hides the smoke columns rising from campfires")
    private val blockBreak by ToggleSetting("Block Break", true)
        .withDescription("Hides the block fragments that fly off when blocks are hit, broken or walked on")
    private val fireworks by ToggleSetting("Fireworks")
        .withDescription("Hides firework sparks and flashes")

    // ---------------------------------------------------------------- HUD
    @JvmStatic val menuBackground by ToggleSetting("Menu Background", true)
        .withDescription("Removes the dark tint and blur behind menus and inventories while you are in a world").section("HUD")
    @JvmStatic val vignette by ToggleSetting("Vignette", true)
        .withDescription("Removes the dark vignette around the screen edges")
    @JvmStatic val bossBar by ToggleSetting("Boss Bar")
        .withDescription("Hides the boss health bars at the top of the screen")
    @JvmStatic val scoreboard by ToggleSetting("Scoreboard")
        .withDescription("Hides the scoreboard sidebar")
    @JvmStatic val heldItemName by ToggleSetting("Held Item Name")
        .withDescription("Hides the item name shown above the hotbar when you switch items")
    @JvmStatic val titles by ToggleSetting("Titles")
        .withDescription("Hides big title and subtitle texts. Only the drawing is skipped, features that read titles keep working")

    // ---------------------------------------------------------------- World
    @JvmStatic val fog by ToggleSetting("Fog")
        .withDescription("Removes the distance fog of the open world. Water, lava, powder snow, blindness and darkness fog are kept").section("World")
    @JvmStatic val darkness by ToggleSetting("Darkness")
        .withDescription("Removes the darkness effect: the pulsing dark screen and the fog it adds")
    @JvmStatic val weather by ToggleSetting("Weather")
        .withDescription("Hides falling rain and snow")
    @JvmStatic val signText by ToggleSetting("Sign Text")
        .withDescription("Hides the text written on signs")

    /** Set by the atmospheric fog hook each frame so the render-distance fog is only lifted in the open world. */
    @JvmField var atmosphericFogApplied = false

    private const val CLEAN_INTERVAL = 10
    private var cleanTicks = 0

    override fun init() {
        register<CheckEntityRenderEvent> {
            if (shouldHide(event.entity)) event.isCanceled = true
        }

        register<TickEvent.End> {
            if (! autoClean.value) return@register
            if (++ cleanTicks < CLEAN_INTERVAL) return@register
            cleanTicks = 0
            autoCleanEntities()
        }
    }

    /** True when the feature is on and [setting] is checked; used by the mixins. */
    @JvmStatic
    fun active(setting: ToggleSetting) = enabled && setting.value

    private fun shouldHide(entity: Entity) = when (entity) {
        is ThrownTrident -> false
        is AbstractArrow -> arrows.value
        is AbstractThrownPotion -> potions.value
        is ThrownExperienceBottle -> xpBottles.value
        is ThrownEgg -> eggs.value
        is ItemEntity -> items.value
        is EndCrystal -> crystals.value
        is ArmorStand -> armorStands.value
        else -> false
    }

    @JvmStatic
    fun shouldHideParticle(particle: Particle): Boolean {
        if (! enabled) return false
        return when (particle) {
            is ElderGuardianParticle -> elderGuardian.value
            is HugeExplosionParticle, is HugeExplosionSeedParticle -> explosions.value
            is CampfireSmokeParticle -> campfire.value
            is TerrainParticle -> blockBreak.value
            is FireworkParticles.OverlayParticle -> fireworks.value
            // the spark particle class is package-private, so match it by its enclosing class
            else -> fireworks.value && particle.javaClass.enclosingClass == FireworkParticles::class.java
        }
    }

    /**
     * Alpha (0..1) for a living entity's body model. Other players fade out the closer they are
     * to the camera and are fully opaque from about 1.6 blocks away.
     */
    @JvmStatic
    fun bodyAlpha(state: LivingEntityRenderState): Float {
        if (! enabled || ! translucentPlayers.value) return 1f
        if (state !is AvatarRenderState) return 1f
        if (state.id == mc.player?.id) return 1f
        return (state.distanceToCameraSq / 3.0 + 0.2).toFloat().coerceIn(0f, 1f)
    }

    private fun autoCleanEntities() {
        val level = mc.level ?: return
        val arrowList = mutableListOf<Entity>()
        val itemList = mutableListOf<Entity>()
        val bottleList = mutableListOf<Entity>()
        val potionList = mutableListOf<Entity>()

        for (entity in level.entitiesForRendering()) when (entity) {
            is ThrownTrident -> Unit
            is AbstractArrow -> arrowList += entity
            is ItemEntity -> itemList += entity
            is ThrownExperienceBottle -> bottleList += entity
            is AbstractThrownPotion -> potionList += entity
        }

        val removed = listOfNotNull(
            purge(arrowList, 64, "arrows"),
            purge(itemList, 16, "items"),
            purge(bottleList, 16, "XP bottles"),
            purge(potionList, 8, "potions")
        )

        if (removed.isNotEmpty() && cleanNotification.value) {
            NotificationManager.push(name, "Removed " + removed.joinToString(", "))
        }
    }

    private fun purge(entities: List<Entity>, limit: Int, label: String): String? {
        if (entities.size <= limit) return null
        entities.forEach { it.remove(Entity.RemovalReason.DISCARDED) }
        return "${entities.size} $label"
    }
}
