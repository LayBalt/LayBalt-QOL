package com.laybalt.utils.render.world

import com.laybalt.LayBaltQOL
import com.laybalt.init.types.ISelfInit
import com.laybalt.utils.render.world.iris.IrisCompatibility
import com.laybalt.utils.render.world.iris.IrisShaderType
import com.mojang.blaze3d.PrimitiveTopology
import com.mojang.blaze3d.pipeline.BlendFunction
import com.mojang.blaze3d.pipeline.ColorTargetState
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.rendertype.RenderSetup
import net.minecraft.client.renderer.rendertype.RenderType
import net.minecraft.resources.Identifier
import java.util.*

object LayBaltRenderTypes: ISelfInit {
    private val MC_FILLED = RenderPipelines.register(
        RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET).apply {
            withLocation(id("pipeline/filled"))
            withVertexBinding(0, DefaultVertexFormat.POSITION_COLOR)
            withPrimitiveTopology(PrimitiveTopology.QUADS)
            withCull(true)
        }.build()
    )

    private val MC_FILLED_THROUGH_WALLS = RenderPipelines.register(
        RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET).apply {
            withLocation(id("pipeline/filled_through_walls"))
            withVertexBinding(0, DefaultVertexFormat.POSITION_COLOR)
            withPrimitiveTopology(PrimitiveTopology.QUADS)
            withCull(true)
            withDepthStencilState(Optional.empty())
        }.build()
    )

    private val MC_CIRCLE_FILLED = RenderPipelines.register(
        RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET).apply {
            withLocation(id("pipeline/circle_filled"))
            withVertexBinding(0, DefaultVertexFormat.POSITION_COLOR)
            withPrimitiveTopology(PrimitiveTopology.QUADS)
        }.build()
    )

    private val MC_CIRCLE_FILLED_THROUGH_WALLS = RenderPipelines.register(
        RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET).apply {
            withLocation(id("pipeline/circle_filled_through_walls"))
            withVertexBinding(0, DefaultVertexFormat.POSITION_COLOR)
            withPrimitiveTopology(PrimitiveTopology.QUADS)
            withDepthStencilState(Optional.empty())
        }.build()
    )

    private val MC_LINES = RenderPipelines.register(
        RenderPipeline.builder(RenderPipelines.LINES_SNIPPET).apply {
            withLocation(id("pipeline/lines"))
            withColorTargetState(ColorTargetState(BlendFunction.TRANSLUCENT))
        }.build()
    )

    private val MC_LINES_THROUGH_WALLS = RenderPipelines.register(
        RenderPipeline.builder(RenderPipelines.LINES_SNIPPET).apply {
            withLocation(id("pipeline/lines_through_walls"))
            withColorTargetState(ColorTargetState(BlendFunction.TRANSLUCENT))
            withDepthStencilState(Optional.empty())
        }.build()
    )

    // 26.2 has no OIT pipeline sets, so the render types use the plain pipelines only and
    // RenderBatcher submits them into Fabric's phases drawn after translucent terrain (NoammAddons b9beda78).
    val DEBUG_CIRCLE_FILLED = RenderType.create("LBQ_DEBUG_CIRCLE_FILLED", RenderSetup.builder(MC_CIRCLE_FILLED_THROUGH_WALLS).sortOnUpload().createRenderSetup())
    val DEBUG_FILLED = RenderType.create("LBQ_DEBUG_FILLED", RenderSetup.builder(MC_FILLED_THROUGH_WALLS).sortOnUpload().createRenderSetup())
    val DEBUG_LINES = RenderType.create("LBQ_DEBUG_LINES", RenderSetup.builder(MC_LINES_THROUGH_WALLS).createRenderSetup())

    val CIRCLE_FILLED = RenderType.create("LBQ_CIRCLE_FILLED", RenderSetup.builder(MC_CIRCLE_FILLED).sortOnUpload().createRenderSetup())
    val FILLED = RenderType.create("LBQ_FILLED", RenderSetup.builder(MC_FILLED).sortOnUpload().createRenderSetup())
    val LINES = RenderType.create("LBQ_LINES", RenderSetup.builder(MC_LINES).createRenderSetup())

    /** Submitted into Fabric's always-on-top phase; every other type goes after terrain (see [RenderBatcher]). */
    val alwaysOnTopTypes = setOf(DEBUG_FILLED, DEBUG_CIRCLE_FILLED, DEBUG_LINES)

    override fun init() {
        // NoammAddons b9beda78: the filled pipeline uses Iris' basic shader like the other filled ones, not the lines one.
        IrisCompatibility.registerPipeline(MC_FILLED, IrisShaderType.BASIC)
        IrisCompatibility.registerPipeline(MC_FILLED_THROUGH_WALLS, IrisShaderType.BASIC)

        IrisCompatibility.registerPipeline(MC_CIRCLE_FILLED, IrisShaderType.BASIC)
        IrisCompatibility.registerPipeline(MC_CIRCLE_FILLED_THROUGH_WALLS, IrisShaderType.BASIC)

        IrisCompatibility.registerPipeline(MC_LINES, IrisShaderType.LINES)
        IrisCompatibility.registerPipeline(MC_LINES_THROUGH_WALLS, IrisShaderType.LINES)
    }

    private fun id(path: String) = Identifier.fromNamespaceAndPath(LayBaltQOL.MOD_ID, path)
}