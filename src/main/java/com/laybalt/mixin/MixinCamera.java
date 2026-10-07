package com.laybalt.mixin;

import com.laybalt.LayBaltQOL;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.Camera;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import static com.laybalt.features.impl.misc.Camera.*;

@Mixin(Camera.class)
public abstract class MixinCamera {
    //#if CHEAT
    @Redirect(method = "alignWithEntity", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;getAttributeValue(Lnet/minecraft/core/Holder;)D"))
    private double setCameraDistance(LivingEntity instance, Holder<Attribute> attribute) {
        if (INSTANCE.enabled && getCustomCameraDistance().getValue()) {
            return getCameraDistance().getValue().doubleValue();
        }

        return instance.getAttributeValue(attribute);
    }

    @Inject(method = "getMaxZoom", at = @At("HEAD"), cancellable = true)
    private void onGetMaxZoom(float cameraDist, CallbackInfoReturnable<Float> cir) {
        if (INSTANCE.enabled && getNoCameraClip().getValue()) {
            cir.setReturnValue(cameraDist);
        }
    }
    //#endif

    @Inject(method = "calculateFov", at = @At(value = "RETURN"), cancellable = true)
    private void calculateFovHook(float partialTicks, CallbackInfoReturnable<Float> cir) {
        cir.setReturnValue(INSTANCE.enabled && getCustomFOV().getValue() ? cir.getReturnValue() * lbqmod$getFOVRatio() : cir.getReturnValue());
    }

    @ModifyExpressionValue(method = "createProjectionMatrixForCulling", at = @At(value = "INVOKE", target = "Ljava/lang/Math;max(FF)F"))
    private float modifyProjectionFov(float original) {
        return INSTANCE.enabled && getCustomFOV().getValue() ? original * lbqmod$getFOVRatio() : original;
    }

    @Unique
    private float lbqmod$getFOVRatio() {
        // essential zoom changes the fov directly so we divide it to get the scale amount
        return getCustomFOVSlider().getValue().floatValue() / LayBaltQOL.getMc().options.fov().get().floatValue();
    }
}