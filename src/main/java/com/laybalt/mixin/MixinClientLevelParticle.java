// Ported from Odin (https://github.com/odtheking/Odin), BSD-3-Clause, Copyright (c) 2025 odtheking
package com.laybalt.mixin;

import com.laybalt.event.EventBus;
import com.laybalt.event.impl.ParticleEvent;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.ParticleOptions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Posts a cancellable {@link ParticleEvent} for every particle the client adds (Odin 1acdc46d / b06b9d2a). */
@Mixin(ClientLevel.class)
public class MixinClientLevelParticle {
    @Inject(method = "doAddParticle", at = @At("HEAD"), cancellable = true)
    private void onAddParticle(ParticleOptions particle, boolean overrideLimiter, boolean alwaysShowParticles, double x, double y, double z, double xd, double yd, double zd, CallbackInfo ci) {
        if (EventBus.post(new ParticleEvent(particle, overrideLimiter, alwaysShowParticles, x, y, z, xd, yd, zd))) ci.cancel();
    }
}
