package com.laybalt.mixin;

import com.laybalt.features.impl.misc.TimeChanger;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.environment.AtmosphericFogEnvironment;
import org.spongepowered.asm.mixin.Mixin;

/** NoammAddons 04d91dcd: the fog colour follows the sky TimeChanger shows, so the horizon matches it. */
@Mixin(AtmosphericFogEnvironment.class)
public class MixinAtmosphericFogEnvironmentTimeChanger {
    @WrapMethod(method = "getBaseColor")
    private int lbqmod$timeChangerFog(ClientLevel level, Camera camera, int renderDistance, float partialTicks, Operation<Integer> original) {
        TimeChanger.extractingSky = TimeChanger.INSTANCE.enabled;
        try {
            return original.call(level, camera, renderDistance, partialTicks);
        } finally {
            TimeChanger.extractingSky = false;
        }
    }
}
