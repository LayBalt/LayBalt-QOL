package com.laybalt.mixin;

import com.laybalt.features.impl.misc.Camera;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Entity.class)
public abstract class MixinEntityCamera {
    @Shadow
    public abstract float getYRot();

    /**
     * Fixes the high mouse input delay when riding an entity (MC-206540), the same fix Debugify makes. While you ride, the
     * rider's body follows the vehicle and its head stays on your own view. This is vanilla AbstractBoat.clampRotation
     * without its turn limit: the yaw difference is already wrapped to ±180°, so the rider's yaw itself is never changed.
     */
    @Inject(method = "onPassengerTurned", at = @At("HEAD"))
    private void fixCameraMovement(Entity passenger, CallbackInfo ci) {
        if (!Camera.INSTANCE.enabled || !Camera.getInputFix().getValue()) return;
        if (!passenger.isAlwaysTicking()) return;
        passenger.setYBodyRot(getYRot());
        passenger.setYHeadRot(passenger.getYRot());
    }
}
