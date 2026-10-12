package com.laybalt.features.impl.render

import com.laybalt.commands.CommandBuilder
import com.laybalt.config.types.ButtonSetting
import com.laybalt.config.types.ColorSetting
import com.laybalt.config.types.SliderSetting
import com.laybalt.config.types.TextInputSetting
import com.laybalt.config.types.ToggleSetting
import com.laybalt.event.impl.RenderWorldEvent
import com.laybalt.event.impl.TickEvent
import com.laybalt.event.impl.WorldChangeEvent
import com.laybalt.features.Feature
import com.laybalt.init.types.ICommandProvider
import com.laybalt.utils.ChatUtils
import com.laybalt.utils.ColorUtils.withAlpha
import com.laybalt.utils.PlayerUtils
import com.laybalt.utils.ThreadUtils
import com.laybalt.utils.render.world.Render3D.renderBlock
import com.laybalt.utils.render.world.RenderBatcher
import com.mojang.brigadier.arguments.StringArgumentType
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.status.ChunkStatus
import net.minecraft.world.phys.Vec3
import java.awt.Color
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Highlights chosen block types around you. The world is scanned once per second on a background
 * thread (chunk sections that cannot contain a wanted block are skipped); the result is drawn every frame.
 *
 * Blocks are chosen with the "Blocks" text field (comma separated ids) or `/blockesp add|remove|list|clear`.
 */
object BlockESP: Feature("Highlights the block types you choose (ores, spawners, ...) around you. Pick blocks with /blockesp add <id>."), ICommandProvider {
    private val blocks by TextInputSetting("Blocks", "minecraft:diamond_ore, minecraft:deepslate_diamond_ore")
        .withDescription("Comma separated block ids to highlight, e.g. minecraft:diamond_ore, spawner. Also editable with /blockesp add|remove|list|clear")
    private val range by SliderSetting("Range", 64, 8, 128, 4, " blocks")
        .withDescription("Scan radius around you. Large values are slow to scan (the scan runs once per second in the background)")
    private val limit by ToggleSetting("Limit", true)
        .withDescription("Only highlights the nearest blocks, up to Limit Count")
    private val limitCount by SliderSetting("Limit Count", 50, 1, 2048, 1)
        .withDescription("How many of the nearest matching blocks are highlighted").showIf { limit.value }
    private val illegals by ToggleSetting("Illegals", false)
        .withDescription("Also highlights blocks players cannot normally get: command blocks, barriers, and bedrock away from the world floor/ceiling. Off by default because Hypixel maps use barriers and bedrock a lot")

    private val color by ColorSetting("Color", Color(0x00, 0xFF, 0xFF, 0x50))
        .withDescription("Box colour; the outline uses it at full opacity").section("Render")
    private val fill by ToggleSetting("Fill", true)
        .withDescription("Fills each block with the colour")
    private val outline by ToggleSetting("Outline", true)
        .withDescription("Draws the block's edges")
    private val throughWalls by ToggleSetting("Through Walls", true)
        .withDescription("Draws the boxes on top of other blocks")
    private val tracers by ToggleSetting("Tracers", false)
        .withDescription("Draws a line from your crosshair to every highlighted block")

    private val addLooked by ButtonSetting("Add Looked-At Block") { addLookedAt() }
        .withDescription("Adds the block type you are looking at to the list")

    @Volatile private var found: List<BlockPos> = emptyList()
    private val scanning = AtomicBoolean(false)
    private var tickCounter = 0
    private var lowFpsSeconds = 0

    /** Cached parse of [blocks] (rebuilt when the text changes). */
    @Volatile private var parsedFor: String? = null
    @Volatile private var parsed: Set<Block> = emptySet()

    override fun init() {
        register<WorldChangeEvent> { found = emptyList() }

        register<TickEvent.End> {
            if (tickCounter ++ % 20 != 0) return@register

            // Safety valve: a huge scan result can tank the frame rate.
            if (mc.fps < 8 && found.isNotEmpty()) {
                if (++ lowFpsSeconds >= 3) {
                    ChatUtils.modMessage("§cBlock ESP turned off: your FPS dropped below 8. Lower Range or Limit Count.")
                    lowFpsSeconds = 0
                    if (enabled) toggle()
                    return@register
                }
            } else lowFpsSeconds = 0

            startScan()
        }

        register<RenderWorldEvent> {
            val list = found
            if (list.isEmpty()) return@register
            val level = mc.level ?: return@register
            val ctx = event.ctx
            val cam = ctx.camera.pos
            val maxSq = range.value.toDouble().let { it * it }
            val max = if (limit.value) limitCount.value else Int.MAX_VALUE
            val targets = targetBlocks()
            val withIllegals = illegals.value
            val c = color.value
            val outlineColor = c.withAlpha(255)
            val phase = throughWalls.value

            val tracerTargets = ArrayList<BlockPos>()
            var drawn = 0
            for (pos in list) {
                if (drawn >= max) break
                if (cam.distanceToSqr(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5) > maxSq) continue
                val state = level.getBlockState(pos)
                if (! matches(state, pos.y, targets, withIllegals, level)) continue
                ctx.renderBlock(pos, outlineColor, c, outline.value, fill.value, phase, 2)
                if (tracers.value) tracerTargets.add(pos)
                drawn ++
            }

            if (tracerTargets.isEmpty()) return@register
            val start = cam.add(Vec3.directionFromRotation(ctx.camera.xRot, ctx.camera.yRot))
            ctx.matrixStack.pushPose()
            ctx.matrixStack.translate(cam.reverse())
            val pose = ctx.uMatrixStack()
            val batch = RenderBatcher.lineBatch(true)
            for (pos in tracerTargets) batch.line(
                pose,
                start.x.toFloat(), start.y.toFloat(), start.z.toFloat(),
                pos.x + 0.5f, pos.y + 0.5f, pos.z + 0.5f,
                c.red / 255f, c.green / 255f, c.blue / 255f, 1f, 1.5f
            )
            ctx.matrixStack.popPose()
        }
    }

    override fun onEnable() {
        super.onEnable()
        tickCounter = 0
        lowFpsSeconds = 0
    }

    override fun onDisable() {
        super.onDisable()
        found = emptyList()
    }

    private fun startScan() {
        val level = mc.level ?: return
        val player = mc.player ?: return
        val targets = targetBlocks()
        val withIllegals = illegals.value
        if (targets.isEmpty() && ! withIllegals) {
            found = emptyList()
            return
        }
        if (! scanning.compareAndSet(false, true)) return

        val px = player.blockX
        val py = player.blockY
        val pz = player.blockZ
        val r = range.value
        val keep = if (limit.value) limitCount.value else Int.MAX_VALUE

        ThreadUtils.async {
            try {
                found = scan(level, px, py, pz, r, targets, withIllegals, keep)
            } finally {
                scanning.set(false)
            }
        }
    }

    private fun scan(level: ClientLevel, px: Int, py: Int, pz: Int, r: Int, targets: Set<Block>, withIllegals: Boolean, keep: Int): List<BlockPos> {
        val hits = ArrayList<BlockPos>()
        val minSectionY = level.minSectionY
        val wanted = { s: BlockState -> s.block in targets || (withIllegals && isIllegalType(s)) }

        for (cx in ((px - r) shr 4)..((px + r) shr 4)) for (cz in ((pz - r) shr 4)..((pz + r) shr 4)) {
            if (level !== mc.level) return emptyList()
            val chunk = level.getChunk(cx, cz, ChunkStatus.FULL, false) ?: continue
            val sections = chunk.sections
            for (i in sections.indices) {
                val section = sections[i] ?: continue
                // off-thread read: a concurrent palette resize may throw; drop only this section for this pass
                try {
                    if (section.hasOnlyAir() || ! section.maybeHas(wanted)) continue
                    val baseY = (minSectionY + i) shl 4
                    for (ly in 0 until 16) for (lz in 0 until 16) for (lx in 0 until 16) {
                        val state = section.getBlockState(lx, ly, lz)
                        if (! wanted(state)) continue
                        val x = (cx shl 4) + lx
                        val y = baseY + ly
                        val z = (cz shl 4) + lz
                        if (x < px - r || x > px + r || z < pz - r || z > pz + r) continue
                        if (state.block !in targets && ! isIllegalAt(state, y, level)) continue
                        hits.add(BlockPos(x, y, z))
                    }
                } catch (_: Exception) {
                    continue
                }
            }
        }

        hits.sortBy { it.distSqr(BlockPos(px, py, pz)) }
        return if (hits.size > keep) hits.subList(0, keep).toList() else hits
    }

    private fun matches(state: BlockState, y: Int, targets: Set<Block>, withIllegals: Boolean, level: Level) =
        state.block in targets || (withIllegals && isIllegalAt(state, y, level))

    private fun isIllegalType(state: BlockState) = state.`is`(Blocks.BEDROCK) || state.`is`(Blocks.BARRIER) ||
        state.`is`(Blocks.COMMAND_BLOCK) || state.`is`(Blocks.CHAIN_COMMAND_BLOCK) || state.`is`(Blocks.REPEATING_COMMAND_BLOCK)

    /** Bedrock only counts when it is away from the generated floor (and, in the Nether, the roof). */
    private fun isIllegalAt(state: BlockState, y: Int, level: Level): Boolean {
        if (! isIllegalType(state)) return false
        if (! state.`is`(Blocks.BEDROCK)) return true
        val floor = level.minY
        return when (level.dimension()) {
            Level.OVERWORLD -> y > floor + 4
            Level.NETHER -> y > floor + 4 && (y < 123 || y > 127)
            else -> false
        }
    }

    private fun targetBlocks(): Set<Block> {
        val text = blocks.value
        if (parsedFor != text) {
            parsed = parseIds(text).mapNotNull { id ->
                Identifier.tryParse(id)?.takeIf(BuiltInRegistries.BLOCK::containsKey)?.let(BuiltInRegistries.BLOCK::getValue)
            }.filter { it != Blocks.AIR }.toSet()
            parsedFor = text
        }
        return parsed
    }

    private fun parseIds(text: String) = text.split(',', ' ', ';').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        .map { if (':' in it) it else "minecraft:$it" }

    private fun setIds(ids: List<String>) {
        blocks.value = ids.distinct().joinToString(", ")
        parsedFor = null
    }

    private fun addId(raw: String): Boolean {
        val id = parseIds(raw).firstOrNull() ?: return false
        val key = Identifier.tryParse(id)
        if (key == null || ! BuiltInRegistries.BLOCK.containsKey(key)) {
            ChatUtils.modMessage("§cUnknown block: §f$id")
            return false
        }
        val current = parseIds(blocks.value)
        if (id in current) {
            ChatUtils.modMessage("§e$id §7is already in the list.")
            return false
        }
        setIds(current + id)
        ChatUtils.modMessage("§aAdded §f$id §ato Block ESP.")
        return true
    }

    private fun addLookedAt() {
        val level = mc.level ?: return
        val pos = PlayerUtils.getSelectionBlock() ?: return ChatUtils.modMessage("§cYou must be looking at a block!")
        addId(BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).block).toString())
    }

    override fun CommandBuilder.command() {
        setName("blockesp")
        alsoUnderLbq("blockesp")
        category(com.laybalt.commands.LbqHelp.Category.MISC)
        description("Manage the Block ESP block list")
        runs { ChatUtils.modMessage("§bUsage: /blockesp <add|remove|list|clear> [block id]") }

        literal("add") {
            description("Adds a block id, or the block you are looking at")
            runs { addLookedAt() }
            argument("block", StringArgumentType.greedyString()) {
                suggests { BuiltInRegistries.BLOCK.keySet().map { it.toString() } }
                runs { ctx -> StringArgumentType.getString(ctx, "block").split(' ', ',').filter { it.isNotBlank() }.forEach { addId(it) } }
            }
        }

        literal("remove") {
            description("Removes a block id from the list")
            argument("block", StringArgumentType.greedyString()) {
                suggests { parseIds(blocks.value) }
                runs { ctx ->
                    val id = parseIds(StringArgumentType.getString(ctx, "block")).firstOrNull() ?: return@runs
                    val current = parseIds(blocks.value)
                    if (id !in current) return@runs ChatUtils.modMessage("§e$id §7is not in the list.")
                    setIds(current - id)
                    ChatUtils.modMessage("§cRemoved §f$id §cfrom Block ESP.")
                }
            }
        }

        literal("list") {
            description("Lists the highlighted block ids")
            runs {
                val ids = parseIds(blocks.value)
                if (ids.isEmpty()) ChatUtils.modMessage("§7Block ESP list is empty.")
                else ChatUtils.modMessage("§bBlock ESP (${ids.size}): §f${ids.joinToString("§7, §f")}")
            }
        }

        literal("clear") {
            description("Empties the list")
            runs {
                setIds(emptyList())
                ChatUtils.modMessage("§cBlock ESP list cleared.")
            }
        }
    }
}
