package com.laybalt.mixin;

import com.laybalt.features.impl.render.NoBob;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** NoBob: replaces the vanilla bobView call for the world camera and (optionally) for the hand. */
@Mixin(GameRenderer.class)
public abstract class MixinGameRendererNoBob {
    @WrapOperation(method = "renderLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;bobView(Lnet/minecraft/client/renderer/state/level/CameraRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;)V"))
    private void lbqmod$worldBob(GameRenderer instance, CameraRenderState state, PoseStack poseStack, Operation<Void> original) {
        if (NoBob.INSTANCE.enabled) NoBob.applyBob(state, poseStack);
        else original.call(instance, state, poseStack);
    }

    @WrapOperation(method = "renderItemInHand", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;bobView(Lnet/minecraft/client/renderer/state/level/CameraRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;)V"))
    private void lbqmod$handBob(GameRenderer instance, CameraRenderState state, PoseStack poseStack, Operation<Void> original) {
        if (NoBob.INSTANCE.enabled && NoBob.getAffectHand().getValue()) NoBob.applyBob(state, poseStack);
        else original.call(instance, state, poseStack);
    }
}
