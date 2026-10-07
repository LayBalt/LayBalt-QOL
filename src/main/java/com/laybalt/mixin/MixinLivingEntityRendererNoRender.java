package com.laybalt.mixin;

import com.laybalt.features.impl.render.NoRender;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.util.ARGB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * NoRender "Translucent Players": nearby players get the translucent body render type and a reduced
 * model alpha. The alpha is computed once at the start of submit (layers, which may submit other
 * entities, run after both uses).
 */
@Mixin(LivingEntityRenderer.class)
public abstract class MixinLivingEntityRendererNoRender {
    @Unique private static float lbqmod$bodyAlpha = 1f;

    @Inject(method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V", at = @At("HEAD"))
    private void lbqmod$computeAlpha(LivingEntityRenderState state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera, CallbackInfo ci) {
        lbqmod$bodyAlpha = NoRender.bodyAlpha(state);
    }

    @ModifyArg(
        method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/entity/LivingEntityRenderer;getRenderType(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;ZZZ)Lnet/minecraft/client/renderer/rendertype/RenderType;"),
        index = 2
    )
    private boolean lbqmod$forceTranslucent(boolean translucent) {
        return translucent || lbqmod$bodyAlpha < 1f;
    }

    @ModifyArg(
        method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/util/ARGB;multiply(II)I", ordinal = 0),
        index = 0
    )
    private int lbqmod$fadeTint(int baseColor) {
        if (lbqmod$bodyAlpha >= 1f) return baseColor;
        return ARGB.multiplyAlpha(baseColor, lbqmod$bodyAlpha);
    }
}
