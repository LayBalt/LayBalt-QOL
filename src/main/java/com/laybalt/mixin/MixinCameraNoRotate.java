package com.laybalt.mixin;

//#if CHEAT

import com.laybalt.features.impl.misc.NoRotate;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** No Rotate "Zero Ping Camera": moves the camera to the predicted teleport position before the server confirms it. */
@Mixin(Camera.class)
public abstract class MixinCameraNoRotate {
    @WrapOperation(method = "alignWithEntity", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;setPosition(DDD)V"))
    private void lbqmod$zeroPingCamera(Camera instance, double x, double y, double z, Operation<Void> original) {
        NoRotate.cameraHook(instance, x, y, z, original);
    }
}
//#endif
