package com.laybalt.mixin;

import com.laybalt.features.impl.render.Weather;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Weather: client-side rain / thunder strength. Only the client level is affected (the integrated server's levels
 * share this class), and only the values read back change; the server weather state is untouched.
 */
@Mixin(Level.class)
public abstract class MixinLevelWeather {
    @Inject(method = "getRainLevel", at = @At("HEAD"), cancellable = true)
    private void lbqmod$rainLevel(float partialTicks, CallbackInfoReturnable<Float> cir) {
        if (!((Object) this instanceof ClientLevel) || !Weather.overridesWeather()) return;
        cir.setReturnValue(Weather.rainLevel());
    }

    @Inject(method = "getThunderLevel", at = @At("HEAD"), cancellable = true)
    private void lbqmod$thunderLevel(float partialTicks, CallbackInfoReturnable<Float> cir) {
        if (!((Object) this instanceof ClientLevel) || !Weather.overridesWeather()) return;
        cir.setReturnValue(Weather.thunderLevel());
    }
}
