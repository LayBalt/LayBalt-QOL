package com.laybalt.utils.render.world

import com.laybalt.LayBaltQOL
import com.laybalt.init.types.ISelfInit
import com.laybalt.utils.render.world.batches.FilledBatch
import com.laybalt.utils.render.world.iris.IrisCompatibility
import com.laybalt.utils.render.world.iris.IrisShaderType
import com.mojang.blaze3d.PrimitiveTopology
import com.mojang.blaze3d.pipeline.BlendFunction
import com.mojang.blaze3d.pipeline.ColorTargetState
import com.mojang.blaze3d.pipeline.DepthStencilState
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.platform.CompareOp
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.rendertype.RenderSetup
import net.minecraft.client.renderer.rendertype.RenderType
import net.minecraft.resources.Identifier
import java.util.*

/**
 * Position-colour quad pipelines for decorative effects (particles, glows, ribbons, rings).
 *
 * Unlike [LayBaltRenderTypes] these never cull back faces (a billboard or ribbon must be visible from both
 * sides) and never write depth (overlapping soft sprites must not cut holes into each other).
 * "Additive" brightens what is behind it, which gives glowing sprites without any texture.
 *
 * Routed into the after-terrain / always-on-top phases by [com.laybalt.mixin.MixinSubmitNodeCollectionEffects].
 * Geometry is collected with [EffectBatcher] and must be flushed by the caller with [EffectBatcher.flush].
 */
object EffectRenderTypes: ISelfInit {
    private fun pipeline(name: String, blend: BlendFunction, depthTest: Boolean) = RenderPipelines.register(
        RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET).apply {
            withLocation(Identifier.fromNamespaceAndPath(LayBaltQOL.MOD_ID, "pipeline/effect_$name"))
            withVertexBinding(0, DefaultVertexFormat.POSITION_COLOR)
            withPrimitiveTopology(PrimitiveTopology.QUADS)
            withCull(false)
            withColorTargetState(ColorTargetState(blend))
            if (depthTest) withDepthStencilState(DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false))
            else withDepthStencilState(Optional.empty())
        }.build()
    )

    private val ADDITIVE_PIPELINE = pipeline("additive", BlendFunction.ADDITIVE, true)
    private val ADDITIVE_SEE_THROUGH_PIPELINE = pipeline("additive_see_through", BlendFunction.ADDITIVE, false)
    private val TRANSLUCENT_PIPELINE = pipeline("translucent", BlendFunction.TRANSLUCENT, true)
    private val TRANSLUCENT_SEE_THROUGH_PIPELINE = pipeline("translucent_see_through", BlendFunction.TRANSLUCENT, false)

    val ADDITIVE: RenderType = RenderType.create("LBQ_EFFECT_ADDITIVE", RenderSetup.builder(ADDITIVE_PIPELINE).createRenderSetup())
    val ADDITIVE_SEE_THROUGH: RenderType = RenderType.create("LBQ_EFFECT_ADDITIVE_SEE_THROUGH", RenderSetup.builder(ADDITIVE_SEE_THROUGH_PIPELINE).createRenderSetup())
    val TRANSLUCENT: RenderType = RenderType.create("LBQ_EFFECT_TRANSLUCENT", RenderSetup.builder(TRANSLUCENT_PIPELINE).sortOnUpload().createRenderSetup())
    val TRANSLUCENT_SEE_THROUGH: RenderType = RenderType.create("LBQ_EFFECT_TRANSLUCENT_SEE_THROUGH", RenderSetup.builder(TRANSLUCENT_SEE_THROUGH_PIPELINE).sortOnUpload().createRenderSetup())

    @JvmField val alwaysOnTopTypes = setOf(ADDITIVE_SEE_THROUGH, TRANSLUCENT_SEE_THROUGH)
    @JvmField val afterTerrainTypes = setOf(ADDITIVE, TRANSLUCENT)

    fun of(additive: Boolean, seeThrough: Boolean) = when {
        additive && seeThrough -> ADDITIVE_SEE_THROUGH
        additive -> ADDITIVE
        seeThrough -> TRANSLUCENT_SEE_THROUGH
        else -> TRANSLUCENT
    }

    override fun init() {
        IrisCompatibility.registerPipeline(ADDITIVE_PIPELINE, IrisShaderType.BASIC)
        IrisCompatibility.registerPipeline(ADDITIVE_SEE_THROUGH_PIPELINE, IrisShaderType.BASIC)
        IrisCompatibility.registerPipeline(TRANSLUCENT_PIPELINE, IrisShaderType.BASIC)
        IrisCompatibility.registerPipeline(TRANSLUCENT_SEE_THROUGH_PIPELINE, IrisShaderType.BASIC)
    }
}

/**
 * Per-frame vertex collector for [EffectRenderTypes]. Vertices are transformed by the pose at insert time
 * (same contract as [RenderBatcher]); call [flush] once at the end of the RenderWorldEvent handler that filled it.
 */
object EffectBatcher {
    private val batches = HashMap<RenderType, FilledBatch>()
    private val identity = PoseStack()

    fun batch(additive: Boolean, seeThrough: Boolean): FilledBatch {
        val type = EffectRenderTypes.of(additive, seeThrough)
        return batches.getOrPut(type) { FilledBatch(type) }
    }

    fun flush(collector: SubmitNodeCollector) {
        if (batches.isEmpty()) return
        for (batch in batches.values) {
            if (batch.data.isEmpty()) continue
            collector.submitCustomGeometry(identity, batch.type) { _, consumer ->
                for (v in batch.data) {
                    consumer.addVertex(v.x, v.y, v.z)
                    consumer.setColor(v.r, v.g, v.b, v.a)
                }
            }
        }
        batches.clear()
    }
}
