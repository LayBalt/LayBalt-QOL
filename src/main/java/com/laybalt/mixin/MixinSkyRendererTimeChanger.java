package com.laybalt.mixin;

import com.laybalt.features.impl.misc.TimeChanger;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SkyRenderer;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import org.spongepowered.asm.mixin.Mixin;

/** NoammAddons 04d91dcd: TimeChanger only changes the time the sky is extracted at. */
@Mixin(SkyRenderer.class)
public class MixinSkyRendererTimeChanger {
    @WrapMethod(method = "extractRenderState")
    private void lbqmod$timeChangerSky(ClientLevel level, float partialTicks, Camera camera, SkyRenderState state, Operation<Void> original) {
        TimeChanger.extractingSky = TimeChanger.INSTANCE.enabled;
        try {
            original.call(level, partialTicks, camera, state);
        } finally {
            TimeChanger.extractingSky = false;
        }
    }
}
