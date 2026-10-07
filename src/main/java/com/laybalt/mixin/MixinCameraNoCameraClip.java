package com.laybalt.mixin;

import com.laybalt.features.impl.render.NoCameraClip;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * NoCameraClip: the requested third-person distance is passed to getMaxZoom, which normally shortens it
 * when blocks are in the way; here it is returned unchanged.
 */
@Mixin(Camera.class)
public abstract class MixinCameraNoCameraClip {
    @ModifyArg(method = "alignWithEntity", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;getMaxZoom(F)F"), index = 0)
    private float lbqmod$cameraDistance(float distance) {
        return NoCameraClip.cameraDistance(distance);
    }

    @Inject(method = "getMaxZoom", at = @At("HEAD"), cancellable = true)
    private void lbqmod$noClip(float distance, CallbackInfoReturnable<Float> cir) {
        if (NoCameraClip.INSTANCE.enabled) cir.setReturnValue(distance);
    }
}
