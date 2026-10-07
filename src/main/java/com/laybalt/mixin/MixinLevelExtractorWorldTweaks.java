package com.laybalt.mixin;

import com.laybalt.features.impl.render.WorldTweaks;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * WorldTweaks clouds: the cloud colour and height are read from the camera's environment attributes into the
 * level render state during extraction; they are overridden right after. A colour with zero alpha makes the
 * level renderer skip the cloud pass entirely (Hide Clouds).
 */
@Mixin(LevelExtractor.class)
public abstract class MixinLevelExtractorWorldTweaks {
    @Shadow @Final private LevelRenderState levelRenderState;

    @Inject(method = "extract", at = @At("RETURN"))
    private void lbqmod$cloudTweaks(DeltaTracker deltaTracker, Camera camera, float partialTicks, CallbackInfo ci) {
        levelRenderState.cloudColor = WorldTweaks.modifyCloudColor(levelRenderState.cloudColor);
        levelRenderState.cloudHeight = WorldTweaks.modifyCloudHeight(levelRenderState.cloudHeight);
    }
}
