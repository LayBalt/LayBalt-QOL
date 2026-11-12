package com.laybalt.mixin;

import com.laybalt.features.impl.misc.TimeChanger;
import net.minecraft.client.ClientClockManager;
import net.minecraft.core.Holder;
import net.minecraft.world.clock.ClockManager;
import net.minecraft.world.clock.WorldClock;
import net.minecraft.world.clock.WorldClocks;
import net.minecraft.world.timeline.AttributeTrackSampler;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * NoammAddons 375e7e53 / 04d91dcd: TimeChanger samples the client's overworld time tracks at the chosen time, but only
 * while the sky or the fog colour is extracted (see MixinSkyRendererTimeChanger, MixinAtmosphericFogEnvironmentTimeChanger).
 */
@Mixin(AttributeTrackSampler.class)
public class MixinAttributeTrackSampler {
    @Shadow @Final private Holder<WorldClock> clock;
    @Shadow @Final private ClockManager clockManager;

    @ModifyArg(method = "applyTimeBased", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/KeyframeTrackSampler;sample(J)Ljava/lang/Object;"), index = 0)
    private long lbqmod$changeTime(long ticks) {
        return clockManager instanceof ClientClockManager && clock.is(WorldClocks.OVERWORLD) && TimeChanger.extractingSky ? TimeChanger.getTime() : ticks;
    }
}
