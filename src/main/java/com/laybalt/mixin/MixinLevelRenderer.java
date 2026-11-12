package com.laybalt.mixin;

import com.laybalt.features.impl.misc.BlockOverlay;
import com.laybalt.utils.render.world.RenderContext;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public class MixinLevelRenderer {
    /**
     * NoammAddons 0c926c3b (Lunar Client compatibility): Block Overlay replaces the vanilla block outline from the head
     * of submitBlockOutline instead of relying on Fabric's BEFORE_BLOCK_OUTLINE event. The overlay is submitted in the
     * same pass from this frame's outline state, before RenderBatcher is flushed at the end of submitFeatures.
     */
    @Inject(method = "submitBlockOutline", at = @At("HEAD"), cancellable = true)
    private void lbqmod$onSubmitBlockOutline(PoseStack poseStack, SubmitNodeCollector submitNodeCollector, LevelRenderState levelRenderState, CallbackInfo ci) {
        if (! BlockOverlay.INSTANCE.enabled) return;
        var state = levelRenderState.blockOutlineRenderState;
        if (state == null) return;

        var ctx = new RenderContext(poseStack, levelRenderState.cameraRenderState, submitNodeCollector);
        if (BlockOverlay.renderOverlay(ctx, state)) ci.cancel();
    }
}
