package com.laybalt.mixin;

import com.laybalt.features.impl.render.WorldTweaks;
import net.minecraft.client.ClientClockManager;
import net.minecraft.core.Holder;
import net.minecraft.world.clock.WorldClock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * WorldTweaks "Change Time": timelines (sun angle, sky colour, light) read the time through
 * ClockManager.getTotalTicks, so the chosen hour is substituted there. The real clock state is untouched.
 */
@Mixin(ClientClockManager.class)
public abstract class MixinClientClockManagerWorldTweaks {
    @Inject(method = "getTotalTicks", at = @At("RETURN"), cancellable = true)
    private void lbqmod$fixedTime(Holder<WorldClock> clock, CallbackInfoReturnable<Long> cir) {
        if (!WorldTweaks.INSTANCE.enabled) return;
        cir.setReturnValue(WorldTweaks.modifyClockTicks(cir.getReturnValueJ()));
    }
}
