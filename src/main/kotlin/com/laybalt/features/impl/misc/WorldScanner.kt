// Ported from OdinClient (https://github.com/skies-starred/OdinClient), BSD-3-Clause, Copyright (c) 2026 Starred
package com.laybalt.features.impl.misc

//#if CHEAT

import com.laybalt.config.types.DropdownSetting
import com.laybalt.config.types.SliderSetting
import com.laybalt.config.types.ToggleSetting
import com.laybalt.event.impl.RenderWorldEvent
import com.laybalt.event.impl.TickEvent
import com.laybalt.event.impl.WorldChangeEvent
import com.laybalt.features.Feature
import com.laybalt.utils.ChatUtils
import com.laybalt.utils.ColorUtils.withAlpha
import com.laybalt.utils.location.LocationUtils
import com.laybalt.utils.location.WorldType
import com.laybalt.utils.render.world.Render3D.renderBoxBounds
import com.laybalt.utils.render.world.Render3D.renderString
import com.laybalt.utils.render.world.RenderContext
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.level.chunk.status.ChunkStatus
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.awt.Color
import java.util.concurrent.ConcurrentHashMap

object WorldScanner: Feature("Scans loaded Crystal Hollows chunks for structures (crystal spots, mob spots, Fairy Grottos, Golden Dragon nest, worm fishing) and marks them with waypoints. Optional lava/water ESP.") {
    private val scanCrystals by ToggleSetting("Scan Crystals", true)
        .withDescription("Finds the crystal locations: King (Goblin Queen's Den entrance), Queen, Divan's Mines, Lost Precursor City, Jungle Temple and Bal.")
    private val scanMobSpots by ToggleSetting("Scan Mob Spots", true)
        .withDescription("Finds Corleone (dock and hole), the Key Guardian (spiral and tower), Xalx, Pete and Odawa.")
    private val scanFairyGrottos by ToggleSetting("Scan Fairy Grottos", true)
        .withDescription("Finds a Fairy Grotto by its magenta stained glass (outside the Nucleus).")
    private val scanDragonNest by ToggleSetting("Scan Dragon Nest", true)
        .withDescription("Finds the Golden Dragon nest.")
    private val scanWormFishing by ToggleSetting("Scan Worm Fishing", false)
        .withDescription("Finds an open lava pool in the Precursor Remnants / Magma Fields area above Y 63, usable for worm fishing.")
    private val lavaEsp by ToggleSetting("Lava ESP", false)
        .withDescription("Highlights surface lava blocks (lava with air above) within the ESP range. Only chunks loaded after enabling this are scanned.")
    private val waterEsp by ToggleSetting("Water ESP", false)
        .withDescription("Highlights surface water blocks (water with air above) within the ESP range. Only chunks loaded after enabling this are scanned.")
    private val espRange by SliderSetting("ESP Range", 32, 8, 128, 1, "m")
        .withDescription("Maximum distance at which lava and water blocks are highlighted.")
        .showIf { lavaEsp.value || waterEsp.value }
    private val renderStyle by DropdownSetting("Render Style", 1, listOf("Filled", "Outline", "Filled Outline"))
        .withDescription("How waypoint boxes are drawn.")
    private val renderText by ToggleSetting("Render Text", true)
        .withDescription("Shows the structure name and its distance above each waypoint.")
    private val sendCoordsInChat by ToggleSetting("Send Coords in Chat", true)
        .withDescription("Prints the coordinates to chat when a structure is found.")

    private val GOLD = Color(255, 170, 0)
    private val AQUA = Color(85, 255, 255)
    private val GREEN = Color(85, 255, 85)
    private val DARK_PURPLE = Color(170, 0, 170)
    private val LIGHT_PURPLE = Color(255, 85, 255)

    private const val CRYSTAL = 0
    private const val MOB = 1
    private const val GROTTO = 2
    private const val DRAGON = 3
    private const val WORM = 4

    private const val MAX_SCAN_Y = 170

    private data class WaypointData(val pos: BlockPos, val category: Int, val color: Color)

    private val waypoints = ConcurrentHashMap<String, WaypointData>()
    private val scannedChunks = HashSet<Long>()
    private val lavaBlocks = ConcurrentHashMap.newKeySet<Long>()
    private val waterBlocks = ConcurrentHashMap.newKeySet<Long>()
    private var sweepTicks = 0

    private enum class Quarter {
        NUCLEUS, JUNGLE, PRECURSOR, GOBLIN, MITHRIL, MAGMA, ANY;

        fun test(x: Int, y: Int, z: Int) = when (this) {
            NUCLEUS -> x in 449 .. 576 && z in 449 .. 576
            JUNGLE -> x <= 576 && z <= 576
            PRECURSOR -> x > 448 && z > 448
            GOBLIN -> x <= 576 && z > 448
            MITHRIL -> x > 448 && z <= 576
            MAGMA -> y < 80
            ANY -> true
        }
    }

    /** [blocks]`[i]` is the block expected at (x, y + i, z), null = any block. The waypoint is placed at the entry block + [offset]. */
    private enum class Structure(
        val displayName: String,
        val blocks: List<Block?>,
        val color: Color,
        val quarter: Quarter,
        val offset: BlockPos,
        val category: Int
    ) {
        KING("King", listOf(Blocks.WOOL.red(), Blocks.DARK_OAK_STAIRS, Blocks.DARK_OAK_STAIRS, Blocks.DARK_OAK_STAIRS), GOLD, Quarter.GOBLIN, BlockPos(1, - 1, 2), CRYSTAL),
        QUEEN("Queen", listOf(Blocks.STONE, Blocks.ACACIA_WOOD, Blocks.ACACIA_WOOD, Blocks.ACACIA_WOOD, Blocks.ACACIA_WOOD, Blocks.CAULDRON), GOLD, Quarter.ANY, BlockPos(0, 5, 0), CRYSTAL),
        DIVAN("Divan", listOf(Blocks.QUARTZ_PILLAR, Blocks.QUARTZ_STAIRS, Blocks.STONE_BRICK_STAIRS, Blocks.CHISELED_STONE_BRICKS), GREEN, Quarter.MITHRIL, BlockPos(0, 5, 0), CRYSTAL),
        CITY("City", listOf(Blocks.STONE_BRICKS, Blocks.COBBLESTONE, Blocks.COBBLESTONE, Blocks.COBBLESTONE, Blocks.COBBLESTONE, Blocks.COBBLESTONE_STAIRS, Blocks.POLISHED_ANDESITE, Blocks.POLISHED_ANDESITE, Blocks.DARK_OAK_STAIRS), AQUA, Quarter.PRECURSOR, BlockPos(24, 0, - 17), CRYSTAL),
        TEMPLE("Temple", listOf(Blocks.BEDROCK, Blocks.BEDROCK, Blocks.BEDROCK, Blocks.BEDROCK, Blocks.STONE, Blocks.CLAY, Blocks.CLAY, Blocks.CLAY, Blocks.OAK_LEAVES, Blocks.OAK_LEAVES, Blocks.DYED_TERRACOTTA.lime(), Blocks.DYED_TERRACOTTA.lime(), Blocks.DYED_TERRACOTTA.green()), DARK_PURPLE, Quarter.ANY, BlockPos(- 45, 47, - 18), CRYSTAL),
        BAL("Bal", listOf(Blocks.LAVA) + List(10) { Blocks.BARRIER }, GOLD, Quarter.MAGMA, BlockPos(0, 1, 0), CRYSTAL),
        CORLEONE_DOCK("Corleone Dock", List(4) { Blocks.STONE_BRICKS } + List<Block?>(20) { null } + listOf(Blocks.STONE_BRICKS, Blocks.STONE_BRICKS, Blocks.FIRE, Blocks.STONE_BRICKS), GREEN, Quarter.MITHRIL, BlockPos(23, 11, 17), MOB),
        CORLEONE_HOLE("Corleone Hole", listOf(Blocks.SMOOTH_STONE_SLAB, Blocks.POLISHED_ANDESITE, Blocks.STONE_BRICKS, Blocks.POLISHED_GRANITE), GREEN, Quarter.MITHRIL, BlockPos(- 18, - 1, 29), MOB),
        KEY_GUARDIAN_SPIRAL("Key Guardian Spiral", listOf(Blocks.JUNGLE_STAIRS, Blocks.JUNGLE_PLANKS, Blocks.GLOWSTONE), DARK_PURPLE, Quarter.JUNGLE, BlockPos.ZERO, MOB),
        KEY_GUARDIAN_TOWER("Key Guardian Tower", listOf(Blocks.STONE, Blocks.POLISHED_GRANITE, Blocks.JUNGLE_SLAB), DARK_PURPLE, Quarter.JUNGLE, BlockPos.ZERO, MOB),
        XALX("Xalx", listOf(Blocks.STONE, Blocks.COAL_BLOCK, Blocks.FIRE, Blocks.NETHER_QUARTZ_ORE) + List(7) { Blocks.AIR }, GREEN, Quarter.GOBLIN, BlockPos(- 2, 1, - 2), MOB),
        PETE("Pete", listOf(Blocks.NETHERRACK, Blocks.FIRE, Blocks.IRON_BARS) + List(7) { Blocks.AIR }, GOLD, Quarter.GOBLIN, BlockPos.ZERO, MOB),
        ODAWA("Odawa", listOf(Blocks.JUNGLE_LOG, Blocks.SPRUCE_STAIRS, Blocks.SPRUCE_STAIRS, Blocks.JUNGLE_LOG), GREEN, Quarter.JUNGLE, BlockPos.ZERO, MOB),
        GOLDEN_DRAGON("Golden Dragon", listOf(Blocks.STONE, Blocks.DYED_TERRACOTTA.red(), Blocks.DYED_TERRACOTTA.red(), Blocks.DYED_TERRACOTTA.red(), Blocks.PLAYER_HEAD, Blocks.WOOL.red()), Color.WHITE, Quarter.ANY, BlockPos(0, - 3, 5), DRAGON);

        fun enabled() = categoryEnabled(category)
    }

    /** Structures to test, keyed by their entry-point (bottom) block. */
    private val byEntryBlock: Map<Block, List<Structure>> = Structure.entries.groupBy { it.blocks.first() !! }
    private val grottoGlass = setOf(Blocks.STAINED_GLASS.magenta(), Blocks.STAINED_GLASS_PANE.magenta())

    private fun categoryEnabled(category: Int) = when (category) {
        CRYSTAL -> scanCrystals.value
        MOB -> scanMobSpots.value
        GROTTO -> scanFairyGrottos.value
        DRAGON -> scanDragonNest.value
        WORM -> scanWormFishing.value
        else -> false
    }

    private val inCrystalHollows get() = LocationUtils.world == WorldType.CrystalHollows

    override fun init() {
        // Fabric chunk event (NA has no chunk event); fires on the client thread when a chunk arrives.
        ClientChunkEvents.CHUNK_LOAD.register { _, chunk ->
            if (! enabled || ! inCrystalHollows) return@register
            scanIfNew(chunk)
        }

        // The area is usually detected a moment after the first chunks arrive: sweep loaded chunks once a second
        // so chunks that loaded before that (or before the feature was enabled) are still scanned.
        register<TickEvent.Start> {
            if (++ sweepTicks < 20) return@register
            sweepTicks = 0
            if (! inCrystalHollows) return@register
            val level = mc.level ?: return@register
            val center = mc.player?.chunkPosition() ?: return@register
            val radius = mc.options.renderDistance().get() + 1
            for (cx in center.x() - radius .. center.x() + radius) for (cz in center.z() - radius .. center.z() + radius) {
                if (chunkKey(cx, cz) in scannedChunks) continue
                val chunk = level.chunkSource.getChunk(cx, cz, ChunkStatus.FULL, false) ?: continue
                scanIfNew(chunk)
            }
        }

        register<WorldChangeEvent> { clearAll() }

        register<RenderWorldEvent> {
            if (! inCrystalHollows) return@register

            for ((name, data) in waypoints) {
                if (! categoryEnabled(data.category)) continue
                event.ctx.renderWaypoint(name, data.pos, data.color, true)
            }

            if (! lavaEsp.value && ! waterEsp.value) return@register
            val playerPos = mc.player?.position() ?: return@register
            val rangeSq = espRange.value.toDouble().let { it * it }
            val mutable = BlockPos.MutableBlockPos()

            if (lavaEsp.value) for (packed in lavaBlocks) {
                mutable.set(packed)
                if (centerDistSq(mutable, playerPos) <= rangeSq) event.ctx.renderWaypoint("Lava", mutable, GOLD, false)
            }
            if (waterEsp.value) for (packed in waterBlocks) {
                mutable.set(packed)
                if (centerDistSq(mutable, playerPos) <= rangeSq) event.ctx.renderWaypoint("Water", mutable, AQUA, false)
            }
        }
    }

    override fun onEnable() {
        super.onEnable()
        clearAll()
    }

    private fun RenderContext.renderWaypoint(name: String, pos: BlockPos, color: Color, showText: Boolean) {
        val aabb = AABB(pos.x.toDouble(), pos.y.toDouble(), pos.z.toDouble(), pos.x + 1.0, pos.y + 1.0, pos.z + 1.0)
        when (renderStyle.value) {
            0 -> renderBoxBounds(aabb, color, color, outline = false, fill = true, phase = true)
            1 -> renderBoxBounds(aabb, color, outline = true, fill = false, phase = true)
            else -> renderBoxBounds(aabb.inflate(0.00005), color, color.withAlpha((color.alpha * 0.5f).toInt()), outline = true, fill = true, phase = true)
        }

        if (! renderText.value || ! showText) return
        val center = Vec3.atCenterOf(pos)
        val dist = mc.player?.position()?.distanceTo(center) ?: 1.0
        val scale = (dist / 10.0).coerceAtLeast(1.0).toFloat()
        renderString(name, center.add(0.0, 1.5, 0.0), Color.WHITE, scale, phase = true)
        renderString("§7${dist.toInt()}m", center.add(0.0, 1.5 - 0.2 * scale, 0.0), Color.WHITE, scale * 0.8f, phase = true)
    }

    private fun centerDistSq(pos: BlockPos, from: Vec3): Double {
        val dx = pos.x + 0.5 - from.x
        val dy = pos.y + 0.5 - from.y
        val dz = pos.z + 0.5 - from.z
        return dx * dx + dy * dy + dz * dz
    }

    private fun clearAll() {
        waypoints.clear()
        scannedChunks.clear()
        lavaBlocks.clear()
        waterBlocks.clear()
    }

    private fun chunkKey(x: Int, z: Int) = (x.toLong() shl 32) or (z.toLong() and 0xFFFFFFFFL)

    private fun scanIfNew(chunk: LevelChunk) {
        val pos = chunk.pos
        if (! scannedChunks.add(chunkKey(pos.x(), pos.z()))) return
        scanChunk(chunk)
    }

    private fun scanChunk(chunk: LevelChunk) {
        val sections = chunk.sections
        val baseX = chunk.pos.x() * 16
        val baseZ = chunk.pos.z() * 16
        val mutable = BlockPos.MutableBlockPos()

        for (y in maxOf(0, chunk.minY) .. MAX_SCAN_Y) {
            val sectionIndex = chunk.getSectionIndex(y)
            if (sectionIndex !in sections.indices) continue
            val section = sections[sectionIndex]
            if (section.hasOnlyAir()) continue
            val localY = y and 15

            for (x in 0 .. 15) for (z in 0 .. 15) {
                val state = section.getBlockState(x, localY, z)
                if (state.isAir) continue
                val block = state.block
                val worldX = baseX + x
                val worldZ = baseZ + z

                when {
                    block === Blocks.LAVA -> {
                        val airAbove = blockAt(chunk, x, y + 1, z).isAir
                        if (lavaEsp.value && airAbove) lavaBlocks.add(BlockPos.asLong(worldX, y, worldZ))
                        if (scanWormFishing.value && airAbove && y > 63 && ! waypoints.containsKey("Worm Fishing") &&
                            ((worldX >= 564 && worldZ >= 513) || (worldX >= 513 && worldZ >= 564))
                        ) addWaypoint("Worm Fishing", mutable.set(worldX, y, worldZ), GOLD, WORM)
                    }
                    block === Blocks.WATER -> {
                        if (waterEsp.value && blockAt(chunk, x, y + 1, z).isAir) waterBlocks.add(BlockPos.asLong(worldX, y, worldZ))
                    }
                    block in grottoGlass -> {
                        if (scanFairyGrottos.value && ! waypoints.containsKey("Fairy Grotto") && ! Quarter.NUCLEUS.test(worldX, y, worldZ))
                            addWaypoint("Fairy Grotto", mutable.set(worldX, y, worldZ), LIGHT_PURPLE, GROTTO)
                    }
                }

                val candidates = byEntryBlock[block] ?: continue
                for (structure in candidates) {
                    if (! structure.enabled() || waypoints.containsKey(structure.displayName)) continue
                    if (! structure.quarter.test(worldX, y, worldZ)) continue
                    if (! matches(chunk, x, y, z, structure.blocks)) continue
                    val target = mutable.set(worldX, y, worldZ).offset(structure.offset)
                    addWaypoint(structure.displayName, target, structure.color, structure.category)
                }
            }
        }
    }

    private fun matches(chunk: LevelChunk, x: Int, y: Int, z: Int, sequence: List<Block?>): Boolean {
        for (i in sequence.indices) {
            val expected = sequence[i] ?: continue
            val py = y + i
            if (py > 255) return false
            if (blockAt(chunk, x, py, z).block !== expected) return false
        }
        return true
    }

    private fun blockAt(chunk: LevelChunk, x: Int, y: Int, z: Int): BlockState {
        val sections = chunk.sections
        val index = chunk.getSectionIndex(y)
        if (index !in sections.indices) return Blocks.AIR.defaultBlockState()
        val section = sections[index]
        if (section.hasOnlyAir()) return Blocks.AIR.defaultBlockState()
        return section.getBlockState(x, y and 15, z)
    }

    private fun addWaypoint(name: String, pos: BlockPos, color: Color, category: Int) {
        if (waypoints.putIfAbsent(name, WaypointData(pos.immutable(), category, color)) != null) return
        if (sendCoordsInChat.value) ChatUtils.modMessage("&b$name &ffound at &e${pos.x}, ${pos.y}, ${pos.z}")
    }
}
//#endif
