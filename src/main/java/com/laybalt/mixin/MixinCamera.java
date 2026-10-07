package com.laybalt.mixin;

import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import static com.laybalt.features.impl.misc.Camera.*;

/**
 * Camera Helper: third-person distance (Custom Camera Distance + Smooth Zoom) and Camera Clip.
 * The only hooks on Camera.getMaxZoom and its distance argument; FOV is in MixinCameraFOV.
 */
@Mixin(Camera.class)
public abstract class MixinCamera {
    @ModifyArg(method = "alignWithEntity", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;getMaxZoom(F)F"), index = 0)
    private float lbqmod$cameraDistance(float distance) {
        return thirdPersonDistance(distance);
    }

    //#if CHEAT
    @Inject(method = "getMaxZoom", at = @At("HEAD"), cancellable = true)
    private void onGetMaxZoom(float cameraDist, CallbackInfoReturnable<Float> cir) {
        if (INSTANCE.enabled && getNoCameraClip().getValue()) {
            cir.setReturnValue(cameraDist);
        }
    }
    //#endif
}
