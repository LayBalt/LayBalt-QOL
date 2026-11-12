package com.laybalt.mixin;

import com.laybalt.features.impl.render.NoRender;
import com.laybalt.features.impl.render.WorldTweaks;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.environment.AtmosphericFogEnvironment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Open-world fog. FogRenderer uses the first applicable environment (lava, powder snow, blindness, darkness, water,
 * atmospheric), so this hook only runs when no fluid or effect fog is active.
 * Order: NoRender "Fog" lifts the fog first, then WorldTweaks "Custom Fog" (an explicit fog setup wins).
 */
@Mixin(AtmosphericFogEnvironment.class)
public abstract class MixinAtmosphericFogEnvironmentWorldVisuals {
    @Unique private static final float LBQMOD$FAR = 1.0E7f;

    @Inject(method = "setupFog", at = @At("RETURN"))
    private void lbqmod$openWorldFog(FogData fog, Camera camera, ClientLevel level, float renderDistance, DeltaTracker deltaTracker, CallbackInfo ci) {
        NoRender.atmosphericFogApplied = true;

        if (NoRender.active(NoRender.getFog())) {
            fog.environmentalStart = LBQMOD$FAR;
            fog.environmentalEnd = LBQMOD$FAR;
        }

        WorldTweaks.applyFog(fog);
    }
}
