package com.laybalt.features.impl.misc

import com.laybalt.config.types.ColorSetting
import com.laybalt.config.types.DropdownSetting
import com.laybalt.config.types.SliderSetting
import com.laybalt.config.types.ToggleSetting
import com.laybalt.event.impl.MainThreadPacketReceivedEvent
import com.laybalt.event.impl.RenderOverlayEvent
import com.laybalt.event.impl.TickEvent
import com.laybalt.event.impl.WorldChangeEvent
import com.laybalt.features.Feature
import com.laybalt.features.impl.render.Tracers
import com.laybalt.features.impl.render.effects.EffectColors
import com.laybalt.utils.WorldUtils
import com.laybalt.utils.render.Render2D.drawBorder
import com.laybalt.utils.render.Render2D.drawHorizontalGradient
import com.laybalt.utils.render.Render2D.drawRect
import com.laybalt.utils.render.Render2D.drawVerticalGradient
import com.laybalt.utils.render.RenderHelper.renderX
import com.laybalt.utils.render.RenderHelper.renderY
import com.laybalt.utils.render.RenderHelper.renderZ
import com.laybalt.utils.render.WorldToScreen
import net.minecraft.ChatFormatting
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.core.BlockPos
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.OwnableEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.GameType
import net.minecraft.world.level.block.entity.SpawnerBlockEntity
import java.awt.Color
import java.util.Locale

object NameTagTweaks: Feature(name = "Nametag Tweaks") {
    private val showOwnNametag by ToggleSetting("Show Own Nametag").withDescription("Renders your own nametag above your head.")
    @JvmStatic val disableNametagBackground by ToggleSetting("Hide Nametag Background").withDescription("Disable Nametag's black background.")
    @JvmStatic val addNameTagTextShadow by ToggleSetting("Shadowed Nametag").withDescription("Adds a text shadow to the nametag label.")
    private val hideDinnerboneNametag by ToggleSetting("Hide Dinnerbone Nametag").withDescription("Hides the nametag of any entity named \"Dinnerbone\".")

    // ---------------------------------------------------------------- custom player plates
    private val customNametags by ToggleSetting("Custom Nametags")
        .withDescription("Replaces the vanilla name tags of players with plates drawn on the HUD that can show health, ping, distance, armour, enchantments, potion effects and more.")
        .section("Custom Nametags")
    private val ignoreBots by ToggleSetting("Ignore Bots", true)
        .withDescription("Keeps the vanilla tag on player-shaped NPCs (fake players with a non-standard UUID) instead of giving them a plate").showIf { customNametags.value }
    private val scale by SliderSetting("Scale", 1f, 0.1f, 10f, 0.1f)
        .withDescription("Size of the plates").showIf { customNametags.value }
    private val resize by ToggleSetting("Resize", false)
        .withDescription("Plates get smaller the farther away the player is").showIf { customNametags.value }
    private val height by SliderSetting("Height", 0.3f, 0f, 2f, 0.05f, " blocks")
        .withDescription("How far above the player's head the plate floats").showIf { customNametags.value }
    private val fontMode by DropdownSetting("Font Mode", 0, listOf("Fancy", "Fast"))
        .withDescription("Fancy draws text with a shadow; Fast draws it flat").showIf { customNametags.value }

    private val gamemode by ToggleSetting("Gamemode", false)
        .withDescription("Shows the player's game mode: [S]urvival, [C]reative, [A]dventure, [SP]ectator").showIf { customNametags.value }
    private val ping by ToggleSetting("Ping", false)
        .withDescription("Shows the player's ping from the tab list").showIf { customNametags.value }
    private val hp by ToggleSetting("HP", true)
        .withDescription("Shows health plus absorption").showIf { customNametags.value }
    private val healthMode by DropdownSetting("Health", 0, listOf("Number", "Hearts", "Dots"))
        .withDescription("Number: health points. Hearts: health in hearts (HP / 2). Dots: a 10-dot bar")
        .showIf { customNametags.value && hp.value }
    private val distance by ToggleSetting("Distance", true)
        .withDescription("Shows how far away the player is").showIf { customNametags.value }
    private val totemPops by ToggleSetting("Totem Pops", false)
        .withDescription("Counts how many Totems of Undying the player has used since they were last seen dying").showIf { customNametags.value }
    private val potions by ToggleSetting("Potions", true)
        .withDescription("Lists active potion effects under the plate (the server only sends other players' effects in some cases)").showIf { customNametags.value }
    private val armorMode by DropdownSetting("Armor Mode", 1, listOf("None", "Full", "Durability"))
        .withDescription("Above the plate: None shows nothing, Full shows held items and armour, Durability shows the armour's remaining durability in percent")
        .showIf { customNametags.value }
    private val enchants by ToggleSetting("Enchants", true)
        .withDescription("Shows shortened enchantment names above each item").showIf { customNametags.value && armorMode.value == 1 }
    private val onlyHands by ToggleSetting("Only Hands", false)
        .withDescription("Only shows enchantments of held items").showIf { customNametags.value && armorMode.value == 1 && enchants.value }
    private val shulkers by ToggleSetting("Shulkers", true)
        .withDescription("When the player holds a shulker box (or another item with contents), shows its items under the plate").showIf { customNametags.value }

    private val fill by ColorSetting("Fill", Color(0, 0, 0, 0x80))
        .withDescription("Plate background").showIf { customNametags.value }
    private val friendFill by ColorSetting("Friend Fill", Color(0, 0, 0, 0x80))
        .withDescription("Plate background for members of your party").showIf { customNametags.value }
    private val outlineType by DropdownSetting("Outline Type", 2, listOf("Sync", "Custom", "None", "New"))
        .withDescription("Plate border. Sync: animated rainbow. Custom: Outline Color. None: no border. New: a single accent line along the top")
        .showIf { customNametags.value }
    private val outlineColor by ColorSetting("Outline Color", Color(0x55, 0x55, 0xFF))
        .withDescription("Border colour for Custom and New outlines").showIf { customNametags.value && (outlineType.value == 1 || outlineType.value == 3) }
    private val friendOutline by ToggleSetting("Friend Outline", true)
        .withDescription("Party members get their own border colour").showIf { customNametags.value }
    private val friendOutlineColor by ColorSetting("Friend Outline Color", Color(0x17, 0xDE, 0x5D))
        .withDescription("Border colour for party members").showIf { customNametags.value && friendOutline.value }

    // ---------------------------------------------------------------- other world labels
    private val spawnerNameTag by ToggleSetting("Spawner Name Tag", false)
        .withDescription("Shows which mob a monster spawner spawns above it").section("Extra Labels")
    private val entityOwner by ToggleSetting("Entity Owner", false)
        .withDescription("Shows the owner's name above tamed animals (wolves, cats, horses, ...)")

    private val pops = HashMap<Int, Int>()
    private val spawners = ArrayList<Pair<BlockPos, String>>()
    private var tickCounter = 0

    override fun init() {
        register<WorldChangeEvent> {
            pops.clear()
            spawners.clear()
        }

        register<MainThreadPacketReceivedEvent.Pre> {
            val packet = event.packet as? ClientboundEntityEventPacket ?: return@register
            val level = mc.level ?: return@register
            val entity = packet.getEntity(level) as? Player ?: return@register
            when (packet.eventId.toInt()) {
                35 -> pops[entity.id] = (pops[entity.id] ?: 0) + 1 // totem of undying used
                3 -> pops.remove(entity.id) // death
            }
        }

        register<TickEvent.End> {
            if (tickCounter ++ % 20 != 0) return@register
            spawners.clear()
            if (! spawnerNameTag.value) return@register
            val level = mc.level ?: return@register
            val player = mc.player ?: return@register
            for (pos in WorldUtils.getBlockEntityList()) {
                if (pos.distSqr(player.blockPosition()) > 64 * 64) continue
                val be = level.getBlockEntity(pos) as? SpawnerBlockEntity ?: continue
                val mob = be.spawner.getOrCreateDisplayEntity(level, pos) ?: continue
                spawners.add(pos to mob.type.description.string)
            }
        }

        register<RenderOverlayEvent> {
            val gui = event.context
            val proj = WorldToScreen.begin(gui) ?: return@register
            if (customNametags.value) drawPlates(gui, proj)
            if (spawnerNameTag.value) for ((pos, name) in spawners) {
                drawSimpleLabel(gui, proj, pos.x + 0.5, pos.y + 1.3, pos.z + 0.5, Component.literal(name).withStyle(ChatFormatting.YELLOW))
            }
            if (entityOwner.value) drawOwners(gui, proj)
        }
    }

    @JvmStatic
    fun shouldShowNametag(entity: Entity): Boolean {
        if (! enabled) return false
        if (! showOwnNametag.value) return false
        if (player != entity) return false
        return true
    }

    @JvmStatic
    fun shouldHideDinnerbone(entity: Entity): Boolean {
        if (! enabled) return false
        if (! hideDinnerboneNametag.value) return false
        return entity.customName?.string.equals("Dinnerbone", ignoreCase = true)
    }

    /** Called by `MixinAvatarRendererNameTags`: hide the vanilla tag of players that get a custom plate. */
    @JvmStatic
    fun hideVanillaTag(entityId: Int): Boolean {
        if (! enabled || ! customNametags.value) return false
        val entity = mc.level?.getEntity(entityId) as? Player ?: return false
        return hasPlate(entity)
    }

    private fun hasPlate(p: Player): Boolean {
        if (p.isRemoved) return false
        if (p === mc.player) return showOwnNametag.value && ! mc.options.cameraType.isFirstPerson
        val self = mc.player ?: return false
        if (p.isInvisibleTo(self)) return false
        if (ignoreBots.value && Tracers.isNpc(p)) return false
        return true
    }

    // ---------------------------------------------------------------- plates

    private fun shadow() = fontMode.value == 0

    private fun drawPlates(gui: GuiGraphicsExtractor, proj: WorldToScreen.Projection) {
        val level = mc.level ?: return
        val cam = mc.gameRenderer.mainCamera().position()
        val players = level.players().filter(::hasPlate)
            .map { it to cam.distanceTo(it.position()) }
            .sortedByDescending { it.second }

        for ((p, dist) in players) {
            val wx = p.renderX
            val wy = p.renderY + p.bbHeight + height.value + if (p.isCrouching) - 0.1 else 0.0
            val wz = p.renderZ
            val sp = proj.project(wx, wy, wz) ?: continue
            if (sp.depth >= 1f || ! proj.onScreen(sp, 100f)) continue

            var s = scale.value
            if (resize.value) s *= (1f - dist.toFloat() / 80f).coerceIn(0.35f, 1f)

            gui.pose().pushMatrix()
            gui.pose().translate(sp.x, sp.y)
            gui.pose().scale(s, s)
            drawPlate(gui, p, dist)
            gui.pose().popMatrix()
        }
    }

    private fun drawPlate(gui: GuiGraphicsExtractor, p: Player, dist: Double) {
        val friend = p !== mc.player && Tracers.isFriend(p)
        val text = buildLine(p, dist)
        val w = mc.font.width(text) + 6
        val h = 11
        val left = - w / 2
        val top = - h

        gui.drawRect(left, top, w, h, if (friend) friendFill.value else fill.value)
        drawOutline(gui, left.toFloat(), top.toFloat(), w.toFloat(), h.toFloat(), friend)
        gui.text(mc.font, text, left + 3, top + 2, - 1, shadow())

        var above = top - 2
        if (armorMode.value != 0) above = drawEquipment(gui, p, above)

        var below = 2
        if (potions.value) below = drawPotions(gui, p, below)
        if (shulkers.value) drawContents(gui, p.mainHandItem, below)
    }

    private fun buildLine(p: Player, dist: Double): MutableComponent {
        val line = Component.empty()
        val info = mc.connection?.getPlayerInfo(p.uuid)

        if (gamemode.value && info != null) {
            val gm = when (info.gameMode) {
                GameType.CREATIVE -> "C"
                GameType.ADVENTURE -> "A"
                GameType.SPECTATOR -> "SP"
                else -> "S"
            }
            line.append(Component.literal("[$gm] ").withStyle(ChatFormatting.GRAY))
        }
        if (ping.value && info != null) {
            val ms = info.latency
            val c = when {
                ms < 80 -> ChatFormatting.GREEN
                ms < 160 -> ChatFormatting.YELLOW
                else -> ChatFormatting.RED
            }
            line.append(Component.literal("${ms}ms ").withStyle(c))
        }

        line.append(p.displayName ?: Component.literal(p.gameProfile.name))

        if (hp.value) line.append(Component.literal(" ")).append(healthText(p))
        if (distance.value) line.append(Component.literal(" %.1fm".format(Locale.ROOT, dist)).withStyle(ChatFormatting.GRAY))
        if (totemPops.value) {
            val n = pops[p.id] ?: 0
            if (n > 0) line.append(Component.literal(" -$n").withStyle(ChatFormatting.GOLD))
        }
        return line
    }

    private fun healthText(p: Player): MutableComponent {
        val health = p.health + p.absorptionAmount
        val max = p.maxHealth.coerceAtLeast(1f)
        val frac = (p.health / max).coerceIn(0f, 1f)
        val c = when {
            frac > 0.66f -> ChatFormatting.GREEN
            frac > 0.33f -> ChatFormatting.YELLOW
            else -> ChatFormatting.RED
        }
        return when (healthMode.value) {
            1 -> Component.literal("%.1f❤".format(Locale.ROOT, health / 2f)).withStyle(c)
            2 -> {
                val filled = (frac * 10f + 0.5f).toInt().coerceIn(0, 10)
                Component.literal("•".repeat(filled)).withStyle(c)
                    .append(Component.literal("•".repeat(10 - filled)).withStyle(ChatFormatting.DARK_GRAY))
            }
            else -> Component.literal(if (health % 1f == 0f) "${health.toInt()}" else "%.1f".format(Locale.ROOT, health)).withStyle(c)
        }
    }

    private fun drawOutline(gui: GuiGraphicsExtractor, x: Float, y: Float, w: Float, h: Float, friend: Boolean) {
        if (friend && friendOutline.value) {
            gui.drawBorder(x, y, w, h, friendOutlineColor.value, 1f)
            return
        }
        when (outlineType.value) {
            0 -> {
                val tl = EffectColors.sync(0)
                val tr = EffectColors.sync(90)
                val br = EffectColors.sync(180)
                val bl = EffectColors.sync(270)
                gui.drawHorizontalGradient(x, y, w, 1f, tl, tr)
                gui.drawHorizontalGradient(x, y + h - 1f, w, 1f, bl, br)
                gui.drawVerticalGradient(x, y, 1f, h, tl, bl)
                gui.drawVerticalGradient(x + w - 1f, y, 1f, h, tr, br)
            }
            1 -> gui.drawBorder(x, y, w, h, outlineColor.value, 1f)
            3 -> gui.drawRect(x, y, w, 1f, outlineColor.value)
        }
    }

    /** Draws held items and armour above the plate; returns the new top edge. */
    private fun drawEquipment(gui: GuiGraphicsExtractor, p: Player, bottom: Int): Int {
        val armor = listOf(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET).map(p::getItemBySlot)

        if (armorMode.value == 2) {
            val parts = Component.empty()
            for (stack in armor) {
                if (stack.isEmpty || ! stack.isDamageableItem) continue
                val left = 1f - stack.damageValue.toFloat() / stack.maxDamage.coerceAtLeast(1)
                val c = when {
                    left > 0.66f -> ChatFormatting.GREEN
                    left > 0.33f -> ChatFormatting.YELLOW
                    else -> ChatFormatting.RED
                }
                if (! parts.siblings.isEmpty()) parts.append(Component.literal(" "))
                parts.append(Component.literal("${(left * 100).toInt()}%").withStyle(c))
            }
            if (parts.siblings.isEmpty()) return bottom
            val w = mc.font.width(parts)
            gui.text(mc.font, parts, - w / 2, bottom - 9, - 1, shadow())
            return bottom - 10
        }

        val items = buildList {
            add(p.mainHandItem to true)
            armor.forEach { add(it to false) }
            add(p.offhandItem to true)
        }.filter { ! it.first.isEmpty }
        if (items.isEmpty()) return bottom

        val iconTop = bottom - 16
        var x = - items.size * 16 / 2
        var highest = iconTop
        for ((stack, held) in items) {
            gui.item(stack, x, iconTop)
            gui.itemDecorations(mc.font, stack, x, iconTop)
            if (enchants.value && (held || ! onlyHands.value)) {
                val lines = enchantLines(stack)
                if (lines.isNotEmpty()) {
                    gui.pose().pushMatrix()
                    gui.pose().translate(x + 8f, iconTop.toFloat())
                    gui.pose().scale(0.5f, 0.5f)
                    lines.forEachIndexed { i, line ->
                        val lw = mc.font.width(line)
                        gui.text(mc.font, line, - lw / 2, - 10 - i * 9, - 1, shadow())
                    }
                    gui.pose().popMatrix()
                    highest = minOf(highest, iconTop - 5 - lines.size * 5)
                }
            }
            x += 16
        }
        return highest - 1
    }

    private fun enchantLines(stack: ItemStack): List<Component> {
        val enchantments = stack.get(DataComponents.ENCHANTMENTS) ?: return emptyList()
        if (enchantments.isEmpty) return emptyList()
        return enchantments.entrySet().take(6).map { entry ->
            val name = entry.key.value().description().string.filter { it.isLetter() }.take(3)
            Component.literal("$name${entry.intValue}").withStyle(ChatFormatting.LIGHT_PURPLE)
        }
    }

    /** Lists active effects under the plate; returns the next free y. */
    private fun drawPotions(gui: GuiGraphicsExtractor, p: Player, top: Int): Int {
        val effects = p.activeEffects
        if (effects.isEmpty()) return top
        var y = top
        gui.pose().pushMatrix()
        gui.pose().scale(0.75f, 0.75f)
        for (effect in effects.take(8)) {
            val ticks = effect.duration
            val time = if (effect.isInfiniteDuration) "**:**" else "%d:%02d".format(ticks / 1200, ticks / 20 % 60)
            val line = Component.empty().append(effect.effect.value().displayName)
                .append(Component.literal(" ${effect.amplifier + 1} "))
                .append(Component.literal(time).withStyle(ChatFormatting.GRAY))
            val colored = line.withColor(effect.effect.value().color)
            val lw = mc.font.width(colored)
            gui.text(mc.font, colored, - lw / 2, (y / 0.75f).toInt(), - 1, shadow())
            y += 7
        }
        gui.pose().popMatrix()
        return y + 1
    }

    /** Shows the contents of a held shulker box (or any item with container contents) as an item grid. */
    private fun drawContents(gui: GuiGraphicsExtractor, stack: ItemStack, top: Int) {
        val contents = stack.get(DataComponents.CONTAINER) ?: return
        val items = contents.nonEmptyItemCopyStream().toList()
        if (items.isEmpty()) return
        val cols = 9
        val rows = (items.size + cols - 1) / cols
        val w = cols * 16 + 4
        val left = - w / 2
        gui.drawRect(left, top, w, rows * 16 + 4, Color(0, 0, 0, 0x90))
        items.forEachIndexed { i, item ->
            val x = left + 2 + (i % cols) * 16
            val y = top + 2 + (i / cols) * 16
            gui.item(item, x, y)
            gui.itemDecorations(mc.font, item, x, y)
        }
    }

    // ---------------------------------------------------------------- small labels

    private fun drawOwners(gui: GuiGraphicsExtractor, proj: WorldToScreen.Projection) {
        val level = mc.level ?: return
        val cam = mc.gameRenderer.mainCamera().position()
        for (entity in level.entitiesForRendering()) {
            if (entity !is OwnableEntity) continue
            if (entity.distanceToSqr(cam) > 48 * 48) continue
            val uuid = entity.ownerReference?.uuid ?: continue
            val name = mc.connection?.getPlayerInfo(uuid)?.profile?.name
                ?: level.getPlayerByUUID(uuid)?.gameProfile?.name
                ?: uuid.toString().take(8)
            drawSimpleLabel(
                gui, proj, entity.renderX, entity.renderY + entity.bbHeight + 0.5, entity.renderZ,
                Component.literal("Owner: ").withStyle(ChatFormatting.GRAY).append(Component.literal(name).withStyle(ChatFormatting.WHITE))
            )
        }
    }

    private fun drawSimpleLabel(gui: GuiGraphicsExtractor, proj: WorldToScreen.Projection, x: Double, y: Double, z: Double, text: Component) {
        val sp = proj.project(x, y, z) ?: return
        if (sp.depth >= 1f || ! proj.onScreen(sp, 50f)) return
        val w = mc.font.width(text) + 4
        gui.pose().pushMatrix()
        gui.pose().translate(sp.x, sp.y)
        gui.drawRect(- w / 2, - 11, w, 11, fill.value)
        gui.text(mc.font, text, - w / 2 + 2, - 9, - 1, shadow())
        gui.pose().popMatrix()
    }
}
