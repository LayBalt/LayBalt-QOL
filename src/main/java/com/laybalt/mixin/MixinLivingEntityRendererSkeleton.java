package com.laybalt.mixin;

import com.laybalt.features.impl.render.Skeleton;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Skeleton: right before {@code submit} pops its model-space pose, the pose stack holds the entity's
 * model transform (position, body rotation, scale). The humanoid model parts are posed for this
 * render state, and {@link Skeleton#collect} turns them into bone segments drawn later this frame.
 */
@Mixin(LivingEntityRenderer.class)
public abstract class MixinLivingEntityRendererSkeleton {
    @Shadow protected EntityModel<?> model;

    @Inject(
        method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
        at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;popPose()V", ordinal = 0)
    )
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void lbqmod$collectSkeleton(LivingEntityRenderState state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera, CallbackInfo ci) {
        if (!Skeleton.INSTANCE.enabled) return;
        if (!(state instanceof HumanoidRenderState humanoidState)) return;
        if (!(this.model instanceof HumanoidModel humanoid)) return;
        if (!Skeleton.isWorldCamera(camera) || !Skeleton.shouldDraw(humanoidState)) return;

        // Pose the parts for exactly this state (layers may not have done it, e.g. with no layers).
        humanoid.setupAnim(humanoidState);
        Skeleton.collect(poseStack, humanoid, camera.pos, humanoidState);
    }
}
