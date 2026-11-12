package com.laybalt.mixin;

import com.laybalt.features.impl.render.NoRender;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.SpawnerRenderer;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** NoRender "Spawner Mob": skips the spinning entity inside spawners (also used by trial spawners). */
@Mixin(SpawnerRenderer.class)
public abstract class MixinSpawnerRendererNoRender {
    @Inject(method = "submitEntityInSpawner", at = @At("HEAD"), cancellable = true)
    private static void lbqmod$hideSpawnerEntity(PoseStack poseStack, SubmitNodeCollector collector, EntityRenderState state, EntityRenderDispatcher dispatcher, float spin, float scale, CameraRenderState camera, CallbackInfo ci) {
        if (NoRender.active(NoRender.getSpawnerEntity())) ci.cancel();
    }
}
