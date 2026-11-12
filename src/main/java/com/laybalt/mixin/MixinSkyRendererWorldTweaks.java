package com.laybalt.mixin;

import com.laybalt.features.impl.render.WorldTweaks;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SkyRenderer;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** WorldTweaks "Custom Sky": the sky disc colour is replaced after vanilla sampled it for this frame. */
@Mixin(SkyRenderer.class)
public abstract class MixinSkyRendererWorldTweaks {
    @Inject(method = "extractRenderState", at = @At("RETURN"))
    private void lbqmod$skyColor(ClientLevel level, float partialTicks, Camera camera, SkyRenderState state, CallbackInfo ci) {
        state.skyColor = WorldTweaks.modifySkyColor(state.skyColor);
    }
}
