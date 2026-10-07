package com.laybalt.mixin;

import com.laybalt.features.impl.render.NoRender;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * NoRender "Fog": the render-distance (chunk edge) fog is computed by FogRenderer after the environment ran;
 * it is pushed out to four times its distance, but only when the open-world environment was the one applied.
 */
@Mixin(FogRenderer.class)
public abstract class MixinFogRendererWorldVisuals {
    @Inject(method = "setupFog", at = @At("HEAD"))
    private void lbqmod$resetFogFlag(Camera camera, int renderDistanceChunks, DeltaTracker deltaTracker, float darkness, ClientLevel level, CallbackInfoReturnable<FogData> cir) {
        NoRender.atmosphericFogApplied = false;
    }

    @Inject(method = "setupFog", at = @At("RETURN"))
    private void lbqmod$liftRenderDistanceFog(Camera camera, int renderDistanceChunks, DeltaTracker deltaTracker, float darkness, ClientLevel level, CallbackInfoReturnable<FogData> cir) {
        if (!NoRender.atmosphericFogApplied || !NoRender.active(NoRender.getFog())) return;
        FogData fog = cir.getReturnValue();
        if (fog == null) return;
        fog.renderDistanceStart = fog.renderDistanceEnd * 4f;
        fog.renderDistanceEnd = fog.renderDistanceEnd * 4f + 1f;
    }
}
