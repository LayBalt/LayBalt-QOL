package com.laybalt.utils

import com.laybalt.LayBaltQOL
import com.laybalt.LayBaltQOL.MOD_ID
import com.laybalt.config.PogObject
import com.laybalt.event.EventBus
import com.laybalt.event.impl.*
import com.laybalt.init.types.ISelfInit
import com.laybalt.utils.ChatUtils.formattedText
import com.laybalt.utils.ChatUtils.removeFormatting
import com.laybalt.utils.ChatUtils.unformattedText
import com.laybalt.utils.items.ItemRarity
import com.laybalt.utils.items.ItemUtils.customData
import com.laybalt.utils.items.ItemUtils.getSkullTexture
import com.laybalt.utils.items.ItemUtils.lore
import com.laybalt.utils.location.LocationUtils
import com.google.common.collect.ImmutableMultimap
import com.google.gson.JsonObject
import com.mojang.authlib.GameProfile
import com.mojang.authlib.properties.Property
import com.mojang.authlib.properties.PropertyMap
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.ResolvableProfile
import java.util.*
import java.util.concurrent.*
import kotlin.jvm.optionals.getOrNull

object PetUtils: ISelfInit {
    private val summonPattern = Regex("""^§aYou (summoned|despawned) your (.+?)(?: ✦)?§a!$""")
    private val autopetPattern = Regex("""^§cAutopet §eequipped your §7\[Lvl (\d+)] (.+?)§e! §a§lVIEW RULE$""")
    private val levelUpPattern = Regex("^Your (.+?) leveled up to level (\\d+)!$")
    private val petMenuPattern = Regex("""^(?:\(\d+/\d+\) )?Pets(?: \(\d+/\d+\))?$""")
    private val petItemNamePattern = Regex("""^(?:(?:§[0-9a-fk-or])?⭐\s*)?(?:§[0-9a-f])?\[Lvl\s+(\d+)](?: §8\[.*?])?\s+(.+)""")
    private val colorCodePattern = Regex("§[0-9a-f]")
    private val loadoutsPetRegex = Regex("\\[Lvl (\\d+)] (.+)$")
    private val loadoutSlots = setOf(14, 15, 16, 23, 24, 25, 32, 33, 34, 41, 42, 43)
    private var loadoutId: Int? = null

    private const val cache_version = 1
    private val petCache = PogObject("petsCache", PetCache(cache_version))
    private var clickedPet: Pet? = null

    private val headCache = ConcurrentHashMap<String, ItemStack>()
    private val steveHead by lazy { ItemStack(Items.PLAYER_HEAD) }

    val knownPets get() = petCache.get().known
    var currentPet: Pet?
        private set(value) = petCache.get()::active.set(value)
        get() = petCache.get().active

    override fun init() {
        val reset = fun() {
            petCache.set(PetCache(cache_version))
            petCache.save()
            LayBaltQOL.logger.info("petcache.json was reset.")
        }

        catch {
            val cache = petCache.get()
            if (cache.version != cache_version || ! cache.isValid()) reset()
        } ?: reset()

        EventBus.register<ChatMessageEvent> {
            summonPattern.matchEntire(event.formattedText)?.destructured?.let { (action, name) ->
                val pet = if (action == "summoned") {
                    val rarity = event.component.petRarity(name)
                    clickedPet
                        ?.takeIf { it.matches(name) }
                        ?.merge(Pet(name.removeFormatting(), rarity = rarity))
                        ?: resolvePet(name, rarity = rarity)
                }
                else null

                clickedPet = null
                changePet(pet, if (pet == null) PetEvent.Cause.DESPAWN else PetEvent.Cause.SUMMON)
                return@register
            }

            autopetPattern.matchEntire(event.formattedText)?.destructured?.let { (level, nameStr) ->
                val level = level.toIntOrNull()
                // NoammAddons 1667d63a: a skinned pet's name ends with " ✦", which never matched a cached pet
                val name = nameStr.remove(Pet.levelRegex).removePrefix("§8").substringBefore(" ✦")
                val hasCustomSkin = nameStr.contains("✦")
                val rarity = ItemRarity.byBaseColor(name.take(2))
                val customSkinRarity = if (hasCustomSkin) customSkinRarity(name) else null

                // NoammAddons ab758668: the rule's hover text names the held item, which tells same-name pets apart
                val petItem = run {
                    val lore = (event.component.style.hoverEvent as? HoverEvent.ShowText)?.value ?: return@run null
                    val i = lore.siblings.indexOfFirst { it.string == "Held Item: " }.takeIf { it != - 1 } ?: return@run null
                    lore.siblings.getOrNull(i + 1)?.string?.trim()
                }

                clickedPet = null
                changePet(resolvePet(name, level, rarity, hasCustomSkin, petItem, customSkinRarity), PetEvent.Cause.AUTOPET)
                return@register
            }

            levelUpPattern.matchEntire(event.unformattedText)?.destructured?.let { (name, level) ->
                levelUpPet(name, level.toIntOrNull() ?: return@let)
            }
        }

        EventBus.register<ContainerFullyOpenedEvent> {
            if (! LocationUtils.inSkyblock) return@register
            if (! petMenuPattern.matches(event.title.unformattedText)) return@register
            // NoammAddons b35d2dab: scan on the main thread so PetEvent is never posted from a worker
            var equipped: Pet? = null
            var found = 0

            for (stack in event.items.values) {
                val pet = stack.pet() ?: continue
                cachePet(pet)
                found ++

                ChatUtils.debug("pet", pet.name)

                if (equipped == null && stack.lore.any { it.removeFormatting().contains("Click to despawn!", ignoreCase = true) }) {
                    equipped = pet
                }
            }

            ChatUtils.debug("pet", "scanned $found pets from menu")

            equipped?.let {
                if (equipped.uuid == null) return@let
                if (clickedPet?.uuid == equipped.uuid) return@let

                val current = currentPet

                when {
                    current == null -> changePet(equipped, PetEvent.Cause.MENU)
                    current.uuid == null && current.matches(equipped.name) -> updatePet(current.merge(equipped))
                    current.uuid != equipped.uuid -> changePet(equipped, PetEvent.Cause.MENU)
                    else -> updatePet(current.merge(equipped))
                }
            }

            ThreadUtils.async { petCache.save() }
        }

        EventBus.register<ContainerEvent.SlotClick> {
            if (event.button != 0 && event.button != 1) return@register
            if (event.clickType != ContainerInput.PICKUP) return@register
            if (event.slotId !in 10 .. 43 || event.slotId % 9 !in 1 .. 7) return@register
            if (! petMenuPattern.matches(event.screen.title.unformattedText)) return@register
            val stack = event.screen.menu.items.getOrNull(event.slotId) ?: return@register
            val pet = stack.pet()
            if (event.button == 0) clickedPet = pet
            else {
                // NoammAddons b35d2dab: a right click turns the pet into an item, so forget it
                val uuid = pet?.uuid ?: return@register
                knownPets.remove(uuid)
                if (clickedPet?.uuid == uuid) clickedPet = null
                val current = currentPet ?: return@register
                if (current.uuid == uuid || current.uuid == null && stack.lore.any { it.removeFormatting().contains("Click to despawn!", ignoreCase = true) }) {
                    changePet(null, PetEvent.Cause.MENU)
                }
            }
        }

        EventBus.register<ContainerEvent.SlotClick> {
            if (event.button != 0) return@register
            if (event.clickType != ContainerInput.PICKUP) return@register
            if (event.slotId !in loadoutSlots) return@register
            if (! event.screen.title.unformattedText.endsWith(") Loadouts")) return@register
            loadoutId = event.screen.menu.containerId
            event.screen.menu.items.getOrNull(event.slotId)?.lore?.find { it.startsWith("§7Pet: ") }?.let {
                val match = loadoutsPetRegex.find(it)?.destructured ?: return@let
                val level = match.component1().toIntOrNull()
                var name = match.component2()
                val hasCustomSkin = name.contains("✦")
                name = name.remove(Pet.levelRegex).removePrefix("§8")
                val rarity = ItemRarity.byBaseColor(name.take(2))
                val customSkinRarity = if (hasCustomSkin) customSkinRarity(name) else null

                clickedPet = null
                changePet(resolvePet(name, level, rarity, hasCustomSkin, customSkinRarity = customSkinRarity), PetEvent.Cause.MENU)
            }
        }

        // NoammAddons 3a2777b5: slot 21 of the Loadouts menu holds the equipped pet with its full petInfo,
        // so opening the menu or switching loadouts resyncs the active pet
        EventBus.register<MainThreadPacketReceivedEvent.Pre> {
            val packet = event.packet as? ClientboundOpenScreenPacket ?: return@register
            if (! packet.title.unformattedText.endsWith(") Loadouts")) return@register
            loadoutId = packet.containerId
        }

        EventBus.register<MainThreadPacketReceivedEvent.Pre> {
            val packet = event.packet as? ClientboundContainerSetSlotPacket ?: return@register
            if (packet.containerId != loadoutId) return@register
            if (packet.slot != 21) return@register
            val pet = packet.item.pet() ?: return@register
            if (pet.uuid == null) return@register

            ChatUtils.debug("pet", "updated from slot 21: ${pet.name}")

            val current = currentPet

            when {
                current == null -> changePet(pet, PetEvent.Cause.MENU)
                current.uuid == null && current.matches(pet.name) -> updatePet(current.merge(pet))
                current.uuid != pet.uuid -> changePet(pet, PetEvent.Cause.MENU)
                else -> updatePet(current.merge(pet))
            }

            loadoutId = null
        }

        EventBus.register<WorldChangeEvent> { clickedPet = null }
    }

    fun ItemStack.pet(): Pet? {
        if (isEmpty) return null

        val tag = customData
        val rawPetInfo = tag.getString("petInfo").getOrNull()?.takeIf(String::isNotEmpty) ?: return null
        val petInfo = catch { GsonUtils.gson.fromJson(rawPetInfo, JsonObject::class.java) } ?: return null

        val rarity = ItemRarity.fromName(catch { petInfo.get("tier").asString }).takeUnless { it == ItemRarity.NONE }
        val itemName = hoverName.formattedText
        val displayMatch = petItemNamePattern.matchEntire(itemName)

        var formattedName = displayMatch?.groupValues?.getOrNull(2)?.trim()?.takeIf(String::isNotEmpty)
        if (formattedName == null) formattedName = run {
            val color = rarity?.baseColor?.toString() ?: return@run null
            val name = catch { petInfo.get("type").asString }?.replace('_', ' ')?.lowercase()?.split(' ')?.joinToString(" ") { it.uppercaseFirst() } ?: return@run null
            return@run "$color$name"
        }

        val heldItem = catch {
            val sbid = petInfo.get("heldItem").asString?.takeUnless { it.isBlank() || it == "null" || it == "NONE" }
            sbid?.lowercase()?.split("_")?.joinToString(" ") { it.uppercaseFirst() }?.removePrefix("Pet Item")?.trim()
        }

        return Pet(
            name = formattedName?.removeFormatting()?.remove("⭐", "✦")?.trim() ?: return null,
            level = displayMatch?.groupValues?.getOrNull(1)?.toIntOrNull(),
            rarity = rarity,
            uuid = tag.getString("uuid").getOrNull() ?: catch { petInfo.get("uuid").asString },
            heldItem = heldItem,
            headSkin = getSkullTexture(this),
            hasCustomSkin = catch { petInfo.get("skin").asString?.takeUnless { it.isBlank() || it == "null" || it == "NONE" } } != null,
            customSkinRarity = run {
                val i = itemName.indexOf('✦').takeUnless { it == - 1 } ?: return@run null
                val code = "§" + itemName.substring(i - 3).take(3).trim().substringAfter("§")
                ItemRarity.byBaseColor(code)
            }
        )
    }

    // NoammAddons 1667d63a: the rarity colour written before the ✦ of a skinned pet's name
    private fun customSkinRarity(name: String): ItemRarity? {
        val i = name.indexOf('✦').takeUnless { it == - 1 } ?: return null
        val code = "§" + name.substring(i - 3).take(3).trim().substringAfter("§")
        return ItemRarity.byBaseColor(code)
    }

    private fun resolvePet(
        name: String,
        level: Int? = null,
        rarity: ItemRarity? = null,
        hasCustomSkin: Boolean = false,
        heldItem: String? = null,
        customSkinRarity: ItemRarity? = null
    ): Pet {
        val base = knownPets.values.find { it.matches(name, rarity, hasCustomSkin, heldItem, customSkinRarity) }

        return Pet(
            name = (base?.name ?: name).removeFormatting(),
            level = level ?: base?.level,
            rarity = rarity ?: base?.rarity,
            uuid = base?.uuid,
            heldItem = heldItem ?: base?.heldItem,
            headSkin = base?.headSkin,
            hasCustomSkin = base?.hasCustomSkin ?: hasCustomSkin,
            customSkinRarity = base?.customSkinRarity ?: customSkinRarity,
        )
    }

    private fun changePet(pet: Pet?, cause: PetEvent.Cause) {
        currentPet = pet
        pet?.let(::cachePet)
        EventBus.post(PetEvent.Change(pet, cause))
    }

    private fun updatePet(pet: Pet) {
        if (currentPet == pet) return
        currentPet = pet
        cachePet(pet)
    }

    private fun levelUpPet(name: String, level: Int) {
        val current = currentPet?.takeIf { it.matches(name) } ?: return
        if (current.level == level) return

        val pet = current.copy(level = level)
        updatePet(pet)
        EventBus.post(PetEvent.LevelUp(pet))
    }

    private fun cachePet(pet: Pet) {
        val uuid = pet.uuid ?: return
        knownPets[uuid] = knownPets[uuid]?.merge(pet) ?: pet
    }

    private fun Pet.merge(other: Pet) = Pet(
        name = other.name.removeFormatting(),
        level = other.level ?: level,
        rarity = other.rarity ?: rarity,
        uuid = other.uuid ?: uuid,
        heldItem = other.heldItem ?: heldItem,
        headSkin = other.headSkin ?: headSkin,
        hasCustomSkin = other.hasCustomSkin || hasCustomSkin,
        customSkinRarity = other.customSkinRarity ?: customSkinRarity,
    )

    private fun Component.petRarity(name: String): ItemRarity? {
        val formatted = formattedText
        val plain = Pet.cleanName(name)
        val index = formatted.indexOf(plain, ignoreCase = true)
        if (index < 0) return null

        val color = colorCodePattern.findAll(formatted.substring(0, index)).lastOrNull()?.value ?: return null

        return ItemRarity.byBaseColor(color)
    }

    private data class PetCache(
        val version: Int,
        var active: Pet? = null,
        val known: ConcurrentHashMap<String, Pet> = ConcurrentHashMap()
    ) {
        @Suppress("SENSELESS_COMPARISON")
        fun isValid() = (known.values + active).filterNotNull().all { it.name != null }
    }

    data class Pet(
        val name: String,
        val level: Int? = null,
        val rarity: ItemRarity? = null,
        val uuid: String? = null,
        val heldItem: String? = null,
        val headSkin: String? = null,
        val hasCustomSkin: Boolean = false,
        val customSkinRarity: ItemRarity? = null,
    ) {
        fun formattedName(withLevel: Boolean) = buildString {
            if (withLevel && level != null) append("§7[Lvl $level] ")
            if (rarity != null) append(rarity.baseColor.toString())
            append(cleanName(name))
            if (hasCustomSkin) append(" ${customSkinRarity?.baseColor?.toString().orEmpty()}✦")
        }

        fun matches(
            name: String,
            rarity: ItemRarity? = null,
            hasCustomSkin: Boolean = false,
            heldItem: String? = null,
            customSkinRarity: ItemRarity? = null
        ): Boolean {
            val withName = normalizeName(this.name) == normalizeName(name)
            val withRarity = rarity == null || rarity.baseColor == this.rarity?.baseColor
            val withSkin = if (hasCustomSkin) this.hasCustomSkin else true
            val withItem = heldItem == null || heldItem.trim().equals(this.heldItem?.trim(), ignoreCase = true)
            val withSkinRarity = customSkinRarity == null || customSkinRarity == this.customSkinRarity

            return withName && withRarity && withSkin && withItem && withSkinRarity
        }

        fun head(): ItemStack {
            val skin = headSkin ?: return steveHead

            return headCache.getOrPut(skin) {
                ItemStack(Items.PLAYER_HEAD).apply {
                    val properties = PropertyMap(ImmutableMultimap.of("textures", Property("textures", skin)))
                    val profile = GameProfile(UUID.nameUUIDFromBytes("$MOD_ID:$name".toByteArray()), this::class.simpleName, properties)
                    set(DataComponents.PROFILE, ResolvableProfile.createResolved(profile))
                }
            }
        }

        companion object {
            val levelRegex = Regex("""\[.+]\s*""")

            fun cleanName(str: String) = str
                .removeFormatting().trim()
                .remove(levelRegex)
                .remove("⭐", "✦")
                .trim()

            fun normalizeName(str: String) = cleanName(str).lowercase()
        }
    }
}