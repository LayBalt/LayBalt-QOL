package com.laybalt.mixin;

import com.laybalt.features.impl.render.NoRender;
import net.minecraft.client.renderer.fog.environment.DarknessFogEnvironment;
import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffect;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * NoRender "Darkness": without its effect the darkness fog environment never applies
 * (same approach NA uses for blindness). The light-map pulse is removed in MixinLightmapFullbright.
 */
@Mixin(DarknessFogEnvironment.class)
public abstract class MixinDarknessFogEnvironmentNoRender {
    @Inject(method = "getMobEffect()Lnet/minecraft/core/Holder;", at = @At("HEAD"), cancellable = true)
    private void lbqmod$noDarknessFog(CallbackInfoReturnable<Holder<MobEffect>> cir) {
        if (NoRender.active(NoRender.getDarkness())) cir.setReturnValue(null);
    }
}
