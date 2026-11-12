package com.laybalt.mixin;

import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Auto Fishing "Lock Mouse": drops mouse look input while the option is active. Cancelling {@code turnPlayer} is safe:
 * {@code handleAccumulatedMovement} resets the accumulated deltas right after the call, and the sensitivity option is
 * never modified (the legacy implementation set it to 0 and could leave it there).
 * Auto Mob Killer reads the same deltas first: your own mouse movement hands a fight to you, Lock Mouse or not.
 */
@Mixin(MouseHandler.class)
public abstract class MixinMouseHandlerAutoFishing {
    //#if CHEAT
    @Shadow private double accumulatedDX;
    @Shadow private double accumulatedDY;
    //#endif

    @Inject(method = "turnPlayer", at = @At("HEAD"), cancellable = true)
    private void lbq$lockMouseWhileFishing(double timeDelta, CallbackInfo ci) {
        //#if CHEAT
        com.laybalt.features.impl.fishing.AutoMobKiller.onMouseTurn(accumulatedDX, accumulatedDY);
        if (com.laybalt.features.impl.fishing.AutoFishing.shouldLockMouse()) ci.cancel();
        //#endif
    }
}
