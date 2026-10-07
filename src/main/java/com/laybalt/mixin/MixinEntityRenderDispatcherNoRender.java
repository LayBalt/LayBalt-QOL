package com.laybalt.mixin;

import com.laybalt.features.impl.render.NoRender;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** NoRender "Entity Fire": skips the flame layer drawn on burning entities. */
@Mixin(EntityRenderDispatcher.class)
public abstract class MixinEntityRenderDispatcherNoRender {
    @WrapOperation(method = "submit", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/SubmitNodeCollector;submitFlame(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/entity/state/EntityRenderState;Lorg/joml/Quaternionf;)V"))
    private void lbqmod$hideEntityFire(SubmitNodeCollector collector, PoseStack poseStack, EntityRenderState state, Quaternionf rotation, Operation<Void> original) {
        if (NoRender.active(NoRender.getEntityFire())) return;
        original.call(collector, poseStack, state, rotation);
    }
}
