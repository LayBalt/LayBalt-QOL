package com.laybalt.mixin;

import com.laybalt.features.impl.render.NoRender;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** NoRender particle toggles: every particle passes through ParticleEngine.add, rejected ones are never spawned. */
@Mixin(ParticleEngine.class)
public abstract class MixinParticleEngineNoRender {
    @Inject(method = "add", at = @At("HEAD"), cancellable = true)
    private void lbqmod$filterParticle(Particle particle, CallbackInfo ci) {
        if (NoRender.shouldHideParticle(particle)) ci.cancel();
    }
}
