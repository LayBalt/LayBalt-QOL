package com.laybalt.features.impl.render

import com.laybalt.LayBaltQOL
import com.laybalt.commands.CommandBuilder
import com.laybalt.config.ConfigManager
import com.laybalt.config.types.ButtonSetting
import com.laybalt.config.types.ColorSetting
import com.laybalt.config.types.DropdownSetting
import com.laybalt.config.types.KeybindSetting
import com.laybalt.config.types.ListSetting
import com.laybalt.config.types.SliderSetting
import com.laybalt.config.types.ToggleSetting
import com.laybalt.event.EventBus
import com.laybalt.event.impl.CheckEntityGlowEvent
import com.laybalt.event.impl.EntityUnloadEvent
import com.laybalt.event.impl.KeyboardEvent
import com.laybalt.event.impl.MouseClickEvent
import com.laybalt.event.impl.RenderWorldEvent
import com.laybalt.event.impl.WorldChangeEvent
import com.laybalt.features.Feature
import com.laybalt.init.types.ICommandProvider
import com.laybalt.utils.ChatUtils
import com.laybalt.utils.ChatUtils.removeFormatting
import com.laybalt.utils.ChatUtils.unformattedText
import com.laybalt.utils.render.RenderHelper.renderX
import com.laybalt.utils.render.RenderHelper.renderY
import com.laybalt.utils.render.RenderHelper.renderZ
import com.laybalt.utils.render.world.RenderBatcher
import com.laybalt.utils.render.world.RenderContext
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.blaze3d.platform.InputConstants
import gg.essential.universal.UMinecraft
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.EntityHitResult
import net.minecraft.world.phys.HitResult
import java.awt.Color

/**
 * Re-implementation of the old LayBalt "Custom ESP".
 *
 * Fixes of the legacy version: mobs are matched by their SkyBlock name (nametag without level, health and decorations)
 * instead of only by entity type, so adding one "Zombie" no longer highlights every zombie-model mob; the add/remove key
 * is a dedicated keybind instead of the vanilla pick-block mapping and works at any distance up to Max Distance; the
 * feature has a toggle key; rendering uses LayBalt's batched renderer (outline colour alpha, fill, line width, through
 * walls) or the vanilla glow outline, with a distance limit; the list is saved with the config.
 */
object CustomESP: Feature("Outlines entities. Press the add key while looking at a mob to add or remove it.", name = "Custom ESP"), ICommandProvider {
    private val addKey by KeybindSetting("Add/Remove Key", InputConstants.MOUSE_BUTTON_MIDDLE).apply { isMouse = true }
        .withDescription("Press while looking at a mob or player (up to Max Distance away) to add it to or remove it from the ESP list. Middle click by default; the click is not passed on to the game (no pick block).")
    private val matchBy by DropdownSetting("Match By", 0, listOf("Name", "Entity Type"))
        .withDescription("What is added when you press the add key. Name = the mob's SkyBlock name (its nametag without level, health and symbols; the player name for players). Entity Type = the Minecraft type (minecraft:zombie), which highlights every mob of that model. Entries of both kinds always stay active.")
    private val toggleKey by KeybindSetting("Toggle Key")
        .withDescription("Turns Custom ESP on or off. Works in game only (not while a screen is open). Unbound by default.")

    private val mode by DropdownSetting("Render", 0, listOf("Box", "Glow")).section("Rendering")
        .withDescription("Box = outline box drawn around the entity. Glow = the vanilla glowing outline in the ESP colour (always visible through walls, ignores line width and fill).")
    private val color by ColorSetting("ESP Color", Color(255, 0, 0, 255), true)
        .withDescription("Colour of the outline. The alpha value makes the box outline transparent.")
    private val lineWidth by SliderSetting("Line Width", 2.0, 1.0, 5.0, 0.5)
        .withDescription("Thickness of the box outline.")
        .showIf { mode.value == 0 }
    private val fill by ToggleSetting("Fill", false)
        .withDescription("Also fills the box with the ESP colour.")
        .showIf { mode.value == 0 }
    private val fillAlpha by SliderSetting("Fill Alpha", 60, 0, 255, 5)
        .withDescription("Opacity of the box fill (0 = invisible, 255 = solid).")
        .showIf { mode.value == 0 && fill.value }
    private val throughWalls by ToggleSetting("Through Walls", true)
        .withDescription("Draws the boxes through blocks. The Glow mode is always visible through walls.")
        .showIf { mode.value == 0 }
    private val maxDistance by SliderSetting("Max Distance", 64, 8, 128, 4, " blocks")
        .withDescription("Entities further away are not outlined. Also the reach of the add key.")

    private val players by ToggleSetting("Players", true).section("Targets")
        .withDescription("Allows players (and player-model NPCs) on the list to be outlined and added.")
    private val mobs by ToggleSetting("Mobs", true)
        .withDescription("Allows mobs on the list to be outlined and added.")
    private val items by ToggleSetting("Items", false)
        .withDescription("Outlines every dropped item. Items are not added to the list.")

    private val entries by ListSetting<String>("Entries")
    private val clearList by ButtonSetting("Clear List") { clearEntries() }
        .withDescription("Removes every entry from the ESP list. You can also use /lbq esp list|remove|clear.")

    private const val PREFIX = "§5[CustomESP] §r"
    private const val NAME_CACHE_MS = 1000L
    private const val TYPE_PREFIX = "type:"

    private val levelPrefix = Regex("^\\[Lv\\s*[\\d,]+]\\s*")
    private val healthSuffix = Regex("\\s+[\\d.,]+[kKmMbB]?(?:/[\\d.,]+[kKmMbB]?)?❤.*$")
    private val leadingDecoration = Regex("^[^\\p{L}\\p{N}\\[(]+")
    private val trailingDecoration = Regex("[^\\p{L}\\p{N})\\]!?.']+$")
    private val ignoredTags = listOf("CLICK", "Spawned by", "RIGHT CLICK", "LEFT CLICK")
    private val timerTag = Regex("^\\d{1,2}:\\d{2}$")

    private class CachedName(val name: String?, val time: Long)

    private val nameCache = HashMap<Int, CachedName>()

    /** Lower-cased entries, rebuilt when the list changes (checked by size + hash every frame, the list is tiny). */
    private var lookup = emptySet<String>()
    private var lookupHash = 0

    override fun init() {
        // The toggle key must work while the feature is off, so it is registered globally (not feature-scoped).
        EventBus.register<KeyboardEvent.KeyPressed> {
            if (UMinecraft.currentScreenObj != null || event.action != InputConstants.PRESS) return@register
            if (! toggleKey.matches(event.keyEvent.key, mouse = false)) return@register
            toggle()
            event.isCanceled = true
        }

        EventBus.register<MouseClickEvent> {
            if (UMinecraft.currentScreenObj != null || event.action != InputConstants.PRESS) return@register
            if (! toggleKey.matches(event.button, mouse = true)) return@register
            toggle()
            event.isCanceled = true
        }

        register<KeyboardEvent.KeyPressed> {
            if (UMinecraft.currentScreenObj != null || event.action != InputConstants.PRESS) return@register
            if (! addKey.matches(event.keyEvent.key, mouse = false)) return@register
            if (handleAddKey()) event.isCanceled = true
        }

        register<MouseClickEvent> {
            if (UMinecraft.currentScreenObj != null || event.action != InputConstants.PRESS) return@register
            if (! addKey.matches(event.button, mouse = true)) return@register
            if (handleAddKey()) event.isCanceled = true
        }

        register<RenderWorldEvent> {
            if (mode.value != 0) return@register
            renderBoxes(event.ctx)
        }

        register<CheckEntityGlowEvent> {
            if (mode.value != 1) return@register
            val self = mc.player ?: return@register
            if (shouldHighlight(event.entity, self)) event.color = color.value
        }

        register<EntityUnloadEvent> { nameCache.remove(event.entity.id) }
        register<WorldChangeEvent> { nameCache.clear() }
    }

    override fun toggle() {
        super.toggle()
        if (mc.player != null && LayBaltQOL.isLoaded) ChatUtils.modMessage("$PREFIX§7CustomESP is now ${if (enabled) "§aon" else "§coff"}")
    }

    override fun onDisable() {
        super.onDisable()
        nameCache.clear()
    }

    // ---------------------------------------------------------------- add / remove

    /** @return true when the key press was consumed (something was looked at). */
    private fun handleAddKey(): Boolean {
        val self = mc.player ?: return false
        val target = findLookedAtEntity(self) ?: return false

        when {
            target is ItemEntity -> {
                message(if (items.value) "§eItems cannot be added to the ESP list, every dropped item is already outlined" else "§eESP for items is disabled in settings")
                return true
            }

            target !is LivingEntity -> {
                message("§eThis entity cannot be added to ESP list")
                return true
            }

            target is Player && ! players.value || target !is Player && ! mobs.value -> {
                message("§eESP for this entity type is disabled in settings")
                return true
            }
        }

        val displayName = nameOf(target, cached = false) ?: target.name.unformattedText
        val existing = entries.value.filter { matchesEntry(it, target) }
        if (existing.isNotEmpty()) {
            entries.value.removeAll(existing.toSet())
            save()
            message("§cRemoved §f${existing.joinToString("§c, §f") { describe(it) }}§c from your ESP list")
            return true
        }

        val key = if (matchBy.value == 1) TYPE_PREFIX + typeKey(target) else displayName
        entries.value.add(key)
        save()
        message("§aAdded §f${describe(key)}§a to your ESP list")
        return true
    }

    private fun findLookedAtEntity(self: Player): Entity? {
        (mc.hitResult as? EntityHitResult)?.entity?.let { return it }

        val partialTicks = mc.deltaTracker.getGameTimeDeltaPartialTick(true)
        val range = maxDistance.value.toDouble()
        val eye = self.getEyePosition(partialTicks)
        val end = eye.add(self.getViewVector(partialTicks).scale(range))

        // Do not select entities behind a wall.
        val blockHit = self.pick(range, partialTicks, false)
        val maxSq = if (blockHit.type == HitResult.Type.MISS) range * range else eye.distanceToSqr(blockHit.location)

        var best: Entity? = null
        var bestSq = maxSq
        for (entity in level.entitiesForRendering()) {
            if (entity === self || entity.isRemoved || entity.isSpectator) continue
            if (entity is ArmorStand && (entity.isInvisible || entity.isMarker)) continue
            if (entity !is LivingEntity && entity !is ItemEntity) continue
            val hit = entity.boundingBox.inflate(entity.pickRadius.toDouble().coerceAtLeast(0.1)).clip(eye, end).orElse(null) ?: continue
            val sq = eye.distanceToSqr(hit)
            if (sq < bestSq) {
                bestSq = sq
                best = entity
            }
        }
        return best
    }

    // ---------------------------------------------------------------- matching

    private fun shouldHighlight(entity: Entity, self: Player): Boolean {
        if (entity === self || entity.isRemoved) return false
        val max = maxDistance.value.toDouble()
        if (entity.distanceToSqr(self) > max * max) return false

        if (entity is ItemEntity) return items.value
        if (entity !is LivingEntity) return false
        if (entity is Player && ! players.value) return false
        if (entity !is Player && ! mobs.value) return false
        if (entity is ArmorStand && entity.isInvisible) return false
        if (entries.value.isEmpty()) return false

        refreshLookup()
        if ((TYPE_PREFIX + typeKey(entity)) in lookup) return true
        val name = nameOf(entity, cached = true) ?: return false
        return name.lowercase() in lookup
    }

    private fun matchesEntry(entry: String, entity: Entity): Boolean {
        val lower = normalizeEntry(entry).lowercase()
        if (lower.startsWith(TYPE_PREFIX)) return lower == TYPE_PREFIX + typeKey(entity)
        return nameOf(entity, cached = false)?.lowercase() == lower
    }

    private fun refreshLookup() {
        val hash = entries.value.hashCode()
        if (hash == lookupHash && lookup.size == entries.value.size) return
        lookupHash = hash
        lookup = entries.value.mapTo(HashSet()) { normalizeEntry(it).lowercase() }
    }

    /** Legacy entries (imported from CustomESP.txt) are bare type ids like `minecraft:zombie`. */
    private fun normalizeEntry(entry: String): String {
        if (entry.startsWith(TYPE_PREFIX, true)) return entry
        return if (legacyTypeId.matches(entry)) TYPE_PREFIX + entry else entry
    }

    private val legacyTypeId = Regex("^[a-z0-9_.-]+:[a-z0-9_./-]+$")

    private fun typeKey(entity: Entity) = EntityType.getKey(entity.type).toString()

    private fun nameOf(entity: Entity, cached: Boolean): String? {
        val now = System.currentTimeMillis()
        if (cached) nameCache[entity.id]?.let { if (now - it.time < NAME_CACHE_MS) return it.name }
        val name = lookupName(entity)
        nameCache[entity.id] = CachedName(name, now)
        return name
    }

    private fun lookupName(entity: Entity): String? {
        if (entity is ArmorStand) return entity.customName?.unformattedText?.let(::cleanName)?.ifEmpty { null }

        val box = entity.boundingBox
        val search = AABB(box.minX - 0.75, box.maxY - 0.75, box.minZ - 0.75, box.maxX + 0.75, box.maxY + 2.5, box.maxZ + 0.75)
        val tags = level.getEntitiesOfClass(ArmorStand::class.java, search) { it !== entity && it.hasCustomName() }
            .mapNotNull { stand ->
                val raw = stand.customName?.unformattedText ?: return@mapNotNull null
                if (ignoredTags.any { raw.contains(it, true) } || timerTag.matches(raw.trim())) return@mapNotNull null
                val clean = cleanName(raw).ifEmpty { return@mapNotNull null }
                Triple(stand, raw, clean)
            }

        val best = tags.filter { '❤' in it.second }.minByOrNull { horizontalDistSq(it.first, entity) }
            ?: tags.minByOrNull { it.first.y }

        best?.let { return it.third }
        return cleanName(entity.name.unformattedText).ifEmpty { null }
    }

    private fun horizontalDistSq(a: Entity, b: Entity): Double {
        val dx = a.x - b.x
        val dz = a.z - b.z
        return dx * dx + dz * dz
    }

    /** `§c✯ [Lv15] §cSea Walker §a1,234§f/§a1.5k§c❤` -> `Sea Walker`. */
    private fun cleanName(raw: String): String {
        var s = raw.removeFormatting().trim()
        s = s.replace(leadingDecoration, "").trim()
        s = s.replace(levelPrefix, "").trim()
        s = s.replace(healthSuffix, "").trim()
        s = s.replace(trailingDecoration, "").trim()
        return s
    }

    // ---------------------------------------------------------------- rendering

    private fun renderBoxes(ctx: RenderContext) {
        val self = mc.player ?: return
        if (entries.value.isEmpty() && ! items.value) return

        val c = color.value
        val r = c.red / 255f
        val g = c.green / 255f
        val b = c.blue / 255f
        val outlineAlpha = c.alpha / 255f
        val fillA = fillAlpha.value / 255f
        val width = lineWidth.value.toFloat()
        val phase = throughWalls.value

        var pushed = false
        for (entity in level.entitiesForRendering()) {
            if (! shouldHighlight(entity, self)) continue

            if (! pushed) {
                ctx.matrixStack.pushPose()
                ctx.matrixStack.translate(ctx.camera.pos.reverse())
                pushed = true
            }

            val box = entity.boundingBox.move(entity.renderX - entity.x, entity.renderY - entity.y, entity.renderZ - entity.z)
            val pose = ctx.uMatrixStack()
            if (fill.value && fillA > 0f) RenderBatcher.filledBatch(phase)
                .addFilledBoxVertices(pose, box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ, r, g, b, fillA)
            if (outlineAlpha > 0f) RenderBatcher.lineBatch(phase)
                .addLineBoxVertices(pose, box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ, r, g, b, outlineAlpha, width)
        }

        if (pushed) ctx.matrixStack.popPose()
    }

    // ---------------------------------------------------------------- list helpers / command

    private fun describe(entry: String): String {
        val normalized = normalizeEntry(entry)
        return if (normalized.startsWith(TYPE_PREFIX, true)) "${normalized.substring(TYPE_PREFIX.length)} §7(type)" else entry
    }

    private fun message(text: String) = ChatUtils.modMessage(PREFIX + text)

    private fun save() {
        lookupHash = 0
        ConfigManager.save()
    }

    private fun clearEntries() {
        entries.value.clear()
        save()
        if (mc.player != null) message("§cCleared your ESP list")
    }

    private fun listEntries() {
        if (entries.value.isEmpty()) return message("§7Your ESP list is empty. Press the add key while looking at a mob to add it.")
        message("§7ESP list (${entries.value.size}):")
        entries.value.forEach { ChatUtils.chat("§8- §f${describe(it)}") }
    }

    private fun removeEntry(input: String) {
        val wanted = input.trim()
        val removed = entries.value.filter { it.equals(wanted, true) || describe(it).removeFormatting().removeSuffix(" (type)").equals(wanted, true) }
        if (removed.isEmpty()) return message("§c\"$wanted\" is not on your ESP list")
        entries.value.removeAll(removed.toSet())
        save()
        message("§cRemoved §f${removed.joinToString("§c, §f") { describe(it) }}§c from your ESP list")
    }

    private fun addEntry(input: String) {
        val wanted = input.trim()
        if (wanted.isEmpty()) return
        if (entries.value.any { it.equals(wanted, true) }) return message("§e\"$wanted\" is already on your ESP list")
        entries.value.add(wanted)
        save()
        message("§aAdded §f${describe(wanted)}§a to your ESP list")
    }

    /** Registered as `/lbq esp ...`: Brigadier merges this tree into the existing `/lbq` root without replacing it. */
    override fun CommandBuilder.command() {
        setName("lbq", "laybalt")

        literal("esp") {
            description("Custom ESP list: list, add <name>, remove <name>, clear")
            runs { listEntries() }

            literal("list") {
                description("Shows the Custom ESP list")
                runs { listEntries() }
            }

            literal("add") {
                argument("name", StringArgumentType.greedyString()) {
                    description("Adds a mob name (or type:minecraft:zombie) to the Custom ESP list")
                    runs { addEntry(StringArgumentType.getString(it, "name")) }
                }
            }

            literal("remove") {
                argument("name", StringArgumentType.greedyString()) {
                    description("Removes an entry from the Custom ESP list")
                    suggests { entries.value.toList() }
                    runs { removeEntry(StringArgumentType.getString(it, "name")) }
                }
            }

            literal("clear") {
                description("Clears the Custom ESP list")
                runs { clearEntries() }
            }
        }
    }
}
