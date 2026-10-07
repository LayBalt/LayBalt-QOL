package com.laybalt.features.impl.render

import com.laybalt.config.types.ColorSetting
import com.laybalt.config.types.SliderSetting
import com.laybalt.config.types.ToggleSetting
import com.laybalt.event.impl.RenderWorldEvent
import com.laybalt.event.impl.TickEvent
import com.laybalt.event.impl.WorldChangeEvent
import com.laybalt.features.Feature
import com.laybalt.utils.ColorUtils.withAlpha
import com.laybalt.utils.WorldUtils
import com.laybalt.utils.render.RenderHelper.renderBoundingBox
import com.laybalt.utils.render.world.Render3D.renderBoxBounds
import net.minecraft.world.entity.decoration.ItemFrame
import net.minecraft.world.entity.vehicle.minecart.MinecartChest
import net.minecraft.world.item.BlockItem
import net.minecraft.world.level.block.ChestBlock
import net.minecraft.world.level.block.ShulkerBoxBlock
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity
import net.minecraft.world.level.block.entity.BarrelBlockEntity
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.ChestBlockEntity
import net.minecraft.world.level.block.entity.DispenserBlockEntity
import net.minecraft.world.level.block.entity.EnderChestBlockEntity
import net.minecraft.world.level.block.entity.HopperBlockEntity
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity
import net.minecraft.world.level.block.entity.TrappedChestBlockEntity
import net.minecraft.world.level.block.state.properties.ChestType
import net.minecraft.world.phys.AABB
import java.awt.Color

/**
 * Highlights storage blocks (chests, shulkers, barrels, ...) and storage entities (chest minecarts,
 * item frames). The list of block entities is rebuilt every 10 ticks; boxes are drawn every frame.
 */
object StorageESP: Feature("Highlights chests, shulker boxes, barrels and other containers around you.") {
    private enum class Kind { CHEST, TRAPPED_CHEST, ENDER_CHEST, SHULKER, FURNACE, HOPPER, DISPENSER, BARREL }

    private val outline by ToggleSetting("Outline", true)
        .withDescription("Draws the edges of each box")
    private val fill by ToggleSetting("Fill", true)
        .withDescription("Fills each box with a see-through colour")
    private val throughWalls by ToggleSetting("Through Walls", true)
        .withDescription("Boxes are drawn on top of blocks so containers are visible behind walls")
    private val lineWidth by SliderSetting("Line Width", 1f, 0.5f, 5f, 0.5f)
        .withDescription("Thickness of the outline").showIf { outline.value }
    private val maxDistance by SliderSetting("Range", 64, 8, 256, 8, " blocks")
        .withDescription("Containers farther away than this are not highlighted")

    private val chest by ToggleSetting("Chest", true).withDescription("Highlights normal chests").section("Blocks")
    private val trappedChest by ToggleSetting("Trapped Chest", true).withDescription("Highlights trapped chests")
    private val enderChest by ToggleSetting("Ender Chest", true).withDescription("Highlights ender chests")
    private val shulker by ToggleSetting("Shulker", true).withDescription("Highlights placed shulker boxes")
    private val dispenser by ToggleSetting("Dispenser", false).withDescription("Highlights dispensers and droppers")
    private val furnace by ToggleSetting("Furnace", false).withDescription("Highlights furnaces, blast furnaces and smokers")
    private val hopper by ToggleSetting("Hopper", false).withDescription("Highlights hoppers")
    private val barrel by ToggleSetting("Barrel", false).withDescription("Highlights barrels")

    private val minecart by ToggleSetting("Minecart", false).withDescription("Highlights minecarts with chests").section("Entities")
    private val itemFrame by ToggleSetting("Item Frame", false).withDescription("Highlights item frames (and glow item frames)")

    private val chestColor by ColorSetting("Chest Color", Color(0xFF, 0xAA, 0x00, 0x88)).withDescription("Colour of chests").section("Colors")
    private val trappedChestColor by ColorSetting("Trapped Chest Color", Color(0xFF, 0x33, 0x33, 0x88)).withDescription("Colour of trapped chests")
    private val eChestColor by ColorSetting("Ender Chest Color", Color(0xAA, 0x33, 0xFF, 0x88)).withDescription("Colour of ender chests")
    private val shulkerColor by ColorSetting("Shulker Color", Color(0xFF, 0x55, 0xCC, 0x88)).withDescription("Colour of shulker boxes")
    private val dispenserColor by ColorSetting("Dispenser Color", Color(0x99, 0x99, 0x99, 0x88)).withDescription("Colour of dispensers and droppers")
    private val furnaceColor by ColorSetting("Furnace Color", Color(0x66, 0x66, 0x66, 0x88)).withDescription("Colour of furnaces")
    private val hopperColor by ColorSetting("Hopper Color", Color(0x44, 0x44, 0x55, 0x88)).withDescription("Colour of hoppers")
    private val barrelColor by ColorSetting("Barrel Color", Color(0x99, 0x66, 0x33, 0x88)).withDescription("Colour of barrels")
    private val minecartColor by ColorSetting("Minecart Color", Color(0x00, 0xFF, 0x00, 0x88)).withDescription("Colour of chest minecarts")
    private val frameColor by ColorSetting("Frame Color", Color(0x00, 0xFF, 0x00, 0x88)).withDescription("Colour of item frames")
    private val shulkFrameColor by ColorSetting("Shulker Frame Color", Color(0xFF, 0x55, 0xCC, 0x88))
        .withDescription("Colour of item frames that hold a shulker box")

    private class Entry(val box: AABB, val kind: Kind)

    @Volatile private var cache: List<Entry> = emptyList()
    private var tickCounter = 0

    override fun init() {
        register<WorldChangeEvent> { cache = emptyList() }

        register<TickEvent.End> {
            if (tickCounter ++ % 10 == 0) rebuild()
        }

        register<RenderWorldEvent> {
            if (mc.gui.hud.isHidden) return@register
            val level = mc.level ?: return@register
            val ctx = event.ctx
            val cam = ctx.camera.pos
            val maxSq = maxDistance.value.toDouble().let { it * it }

            for (entry in cache) {
                if (! enabledFor(entry.kind)) continue
                val c = entry.box.center
                if (c.distanceToSqr(cam) > maxSq) continue
                draw(ctx, entry.box, colorFor(entry.kind))
            }

            if (! minecart.value && ! itemFrame.value) return@register
            for (entity in level.entitiesForRendering()) {
                val color = when (entity) {
                    is MinecartChest -> if (minecart.value) minecartColor.value else null
                    is ItemFrame -> if (! itemFrame.value) null else {
                        val block = (entity.item.item as? BlockItem)?.block
                        if (block is ShulkerBoxBlock) shulkFrameColor.value else frameColor.value
                    }
                    else -> null
                } ?: continue
                if (entity.distanceToSqr(cam) > maxSq) continue
                draw(ctx, entity.renderBoundingBox, color)
            }
        }
    }

    override fun onEnable() {
        super.onEnable()
        tickCounter = 0
    }

    override fun onDisable() {
        super.onDisable()
        cache = emptyList()
    }

    private fun draw(ctx: com.laybalt.utils.render.world.RenderContext, box: AABB, color: Color) {
        ctx.renderBoxBounds(box, color.withAlpha(255), color, outline.value, fill.value, throughWalls.value, lineWidth.value)
    }

    private fun rebuild() {
        val level = mc.level ?: return
        val out = ArrayList<Entry>()
        for (pos in WorldUtils.getBlockEntityList()) {
            val be = level.getBlockEntity(pos) ?: continue
            val kind = kindOf(be) ?: continue
            val x = pos.x.toDouble()
            val y = pos.y.toDouble()
            val z = pos.z.toDouble()

            val box = if (kind == Kind.CHEST || kind == Kind.TRAPPED_CHEST || kind == Kind.ENDER_CHEST) {
                var box = AABB(x + 0.06, y, z + 0.06, x + 0.94, y + 0.875, z + 0.94)
                val state = be.blockState
                if (kind != Kind.ENDER_CHEST && state.hasProperty(ChestBlock.TYPE) && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
                    // Draw a double chest as one box: only the LEFT half emits it, stretched over the other half.
                    if (state.getValue(ChestBlock.TYPE) == ChestType.RIGHT) continue
                    val other = pos.relative(ChestBlock.getConnectedDirection(state))
                    box = box.minmax(AABB(other.x + 0.06, y, other.z + 0.06, other.x + 0.94, y + 0.875, other.z + 0.94))
                }
                box
            } else AABB(x, y, z, x + 1.0, y + 1.0, z + 1.0)

            out.add(Entry(box, kind))
        }
        cache = out
    }

    private fun kindOf(be: BlockEntity): Kind? = when (be) {
        is TrappedChestBlockEntity -> Kind.TRAPPED_CHEST
        is ChestBlockEntity -> Kind.CHEST
        is EnderChestBlockEntity -> Kind.ENDER_CHEST
        is ShulkerBoxBlockEntity -> Kind.SHULKER
        is AbstractFurnaceBlockEntity -> Kind.FURNACE
        is HopperBlockEntity -> Kind.HOPPER
        is DispenserBlockEntity -> Kind.DISPENSER
        is BarrelBlockEntity -> Kind.BARREL
        else -> null
    }

    private fun enabledFor(kind: Kind) = when (kind) {
        Kind.CHEST -> chest.value
        Kind.TRAPPED_CHEST -> trappedChest.value
        Kind.ENDER_CHEST -> enderChest.value
        Kind.SHULKER -> shulker.value
        Kind.FURNACE -> furnace.value
        Kind.HOPPER -> hopper.value
        Kind.DISPENSER -> dispenser.value
        Kind.BARREL -> barrel.value
    }

    private fun colorFor(kind: Kind) = when (kind) {
        Kind.CHEST -> chestColor.value
        Kind.TRAPPED_CHEST -> trappedChestColor.value
        Kind.ENDER_CHEST -> eChestColor.value
        Kind.SHULKER -> shulkerColor.value
        Kind.FURNACE -> furnaceColor.value
        Kind.HOPPER -> hopperColor.value
        Kind.DISPENSER -> dispenserColor.value
        Kind.BARREL -> barrelColor.value
    }
}
