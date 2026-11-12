package com.laybalt.mixin;

import com.laybalt.LayBaltQOL;
import org.spongepowered.asm.mixin.Dynamic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "moe.nea.firmament.features.misc.ModAnnouncer", remap = false)
public class FuckFirmament {
    @Dynamic
    @Inject(method = "onServerJoin", at = @At("HEAD"), cancellable = true)
    private void stopFeddingEveryone(@Coerce Object event, CallbackInfo ci) {
        ci.cancel();
        LayBaltQOL.logger.info("FUCK FIRMAMENT");
    }
}