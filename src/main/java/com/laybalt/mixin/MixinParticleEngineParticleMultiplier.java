package com.laybalt.mixin;

import com.laybalt.features.impl.render.ParticleMultiplier;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.particle.ParticleGroup;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.core.particles.ParticleOptions;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;
import java.util.Queue;

/**
 * Particle Multiplier: every particle the world spawns goes through createParticle (blacklist, chance, size,
 * extra copies); every particle that is actually added goes through add (alive particle limit).
 */
@Mixin(ParticleEngine.class)
public abstract class MixinParticleEngineParticleMultiplier {
    @Shadow @Final private Map<ParticleRenderType, ParticleGroup<?>> particles;
    @Shadow @Final private Queue<Particle> particlesToAdd;

    @Inject(method = "createParticle", at = @At("HEAD"), cancellable = true)
    private void lbqmod$filterSpawn(ParticleOptions options, double x, double y, double z, double xd, double yd, double zd, CallbackInfoReturnable<Particle> cir) {
        if (ParticleMultiplier.shouldSkip(options)) cir.setReturnValue(null);
    }

    @Inject(method = "createParticle", at = @At("RETURN"))
    private void lbqmod$multiply(ParticleOptions options, double x, double y, double z, double xd, double yd, double zd, CallbackInfoReturnable<Particle> cir) {
        Particle particle = cir.getReturnValue();
        if (particle != null) ParticleMultiplier.onCreated((ParticleEngine) (Object) this, options, particle, x, y, z, xd, yd, zd);
    }

    @Inject(method = "add", at = @At("HEAD"), cancellable = true)
    private void lbqmod$limit(Particle particle, CallbackInfo ci) {
        if (!ParticleMultiplier.hasLimit()) return;
        int count = particlesToAdd.size();
        for (ParticleGroup<?> group : particles.values()) count += group.size();
        if (count >= ParticleMultiplier.limit()) ci.cancel();
    }
}
