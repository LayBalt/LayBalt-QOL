package com.laybalt.mixin;

//#if CHEAT

import com.laybalt.features.impl.general.FarmKeys;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Farm Keys "Lock Camera": stops mouse movement from turning the player.
 */
@Mixin(MouseHandler.class)
public class MixinMouseHandlerFarmKeys {
    @Inject(method = "turnPlayer", at = @At("HEAD"), cancellable = true)
    private void laybalt$lockCamera(CallbackInfo ci) {
        if (FarmKeys.getLock()) ci.cancel();
    }
}
//#endif
