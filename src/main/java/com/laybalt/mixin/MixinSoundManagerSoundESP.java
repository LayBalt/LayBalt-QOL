package com.laybalt.mixin;

import com.laybalt.features.impl.render.SoundESP;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Sound ESP: records every sound the client starts playing (server sounds and client-side ones).
 * Kept separate from {@link MixinSoundManager} so that mixin stays untouched.
 */
@Mixin(SoundManager.class)
public class MixinSoundManagerSoundESP {
    @Inject(method = "play", at = @At("HEAD"))
    private void lbqmod$recordForSoundEsp(SoundInstance instance, CallbackInfoReturnable<SoundEngine.PlayResult> cir) {
        if (SoundESP.INSTANCE.enabled) SoundESP.onSound(instance);
    }
}
