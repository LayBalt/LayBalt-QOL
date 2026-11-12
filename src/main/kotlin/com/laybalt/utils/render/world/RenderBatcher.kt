package com.laybalt.utils.render.world

import com.laybalt.utils.render.world.batches.FilledBatch
import com.laybalt.utils.render.world.batches.LineBatch
import com.mojang.blaze3d.vertex.PoseStack
import net.fabricmc.fabric.api.client.rendering.v1.SubmitRenderPhases
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.feature.CustomFeatureRenderer
import net.minecraft.client.renderer.rendertype.RenderType
import org.joml.Vector3f

object RenderBatcher {
    private val filledBatches = mutableMapOf<RenderType, FilledBatch>()
    private val lineBatches = mutableMapOf<RenderType, LineBatch>()
    private val poseStack = PoseStack()

    val tmpVec = Vector3f()
    val tmpDir = Vector3f()

    fun filledBatch(phase: Boolean): FilledBatch {
        val type = if (phase) LayBaltRenderTypes.DEBUG_FILLED else LayBaltRenderTypes.FILLED
        return filledBatches.getOrPut(type) { FilledBatch(type) }
    }

    fun circleBatch(phase: Boolean): FilledBatch {
        val type = if (phase) LayBaltRenderTypes.DEBUG_CIRCLE_FILLED else LayBaltRenderTypes.CIRCLE_FILLED
        return filledBatches.getOrPut(type) { FilledBatch(type) }
    }

    fun lineBatch(phase: Boolean): LineBatch {
        val type = if (phase) LayBaltRenderTypes.DEBUG_LINES else LayBaltRenderTypes.LINES
        return lineBatches.getOrPut(type) { LineBatch(type) }
    }

    fun flush(collector: SubmitNodeCollector) {
        if (filledBatches.isEmpty() && lineBatches.isEmpty()) return

        for (batchData in filledBatches.values) collector.submitBatch(batchData.type) { _, consumer ->
            for (state in batchData.data) {
                consumer.addVertex(state.x, state.y, state.z)
                consumer.setColor(state.r, state.g, state.b, state.a)
            }
        }

        for (batchData in lineBatches.values) collector.submitBatch(batchData.type) { _, consumer ->
            for (state in batchData.data) {
                consumer.addVertex(state.x, state.y, state.z)
                consumer.setColor(state.r, state.g, state.b, state.a)
                consumer.setNormal(state.nx, state.ny, state.nz)
                consumer.setLineWidth(state.lineWidth)
            }
        }

        filledBatches.clear()
        lineBatches.clear()
    }

    // NoammAddons b9beda78: 26.2 has no OIT pipelines, so blended custom geometry submitted the plain way lands in the
    // translucent phase and see-through blocks (glass, leaves, ...) draw over it. Submit straight into Fabric's phases
    // (see-through types on top of everything, the rest after terrain) instead of rerouting with a SubmitNodeCollection mixin.
    private fun SubmitNodeCollector.submitBatch(type: RenderType, renderer: SubmitNodeCollector.CustomGeometryRenderer) {
        val phase = if (type in LayBaltRenderTypes.alwaysOnTopTypes) SubmitRenderPhases.ALWAYS_ON_TOP else SubmitRenderPhases.AFTER_TERRAIN
        submitCustom(phase, CustomFeatureRenderer.Submit(poseStack.last().copy(), type, renderer))
    }
}