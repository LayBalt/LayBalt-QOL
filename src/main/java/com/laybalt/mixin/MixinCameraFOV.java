package com.laybalt.mixin;

import com.laybalt.features.impl.render.FOV;
import net.minecraft.client.Camera;
import net.minecraft.util.Mth;
import net.minecraft.world.level.material.FogType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * FOV: absolute world FOV (calculateFov) and a separate hand / held item FOV (calculateHudFov).
 * The value still goes through the vanilla death / fluid modifier so dying and swimming look normal.
 */
@Mixin(Camera.class)
public abstract class MixinCameraFOV {
    @Shadow private boolean isPanoramicMode;
    @Shadow private float fovModifier;
    @Shadow private float oldFovModifier;

    @Shadow public abstract FogType getFluidInCamera();

    @Shadow
    private float modifyFovBasedOnDeathOrFluid(float partialTicks, float fov) {
        throw new AssertionError();
    }

    @Unique
    private boolean lbqmod$keepVanilla() {
        if (!FOV.getVanillaInFluids().getValue()) return false;
        FogType fluid = getFluidInCamera();
        return fluid == FogType.WATER || fluid == FogType.LAVA;
    }

    @Inject(method = "calculateFov", at = @At("RETURN"), cancellable = true)
    private void lbqmod$customFov(float partialTicks, CallbackInfoReturnable<Float> cir) {
        if (!FOV.INSTANCE.enabled || isPanoramicMode || lbqmod$keepVanilla()) return;

        float fov = FOV.getFov().getValue().floatValue();
        if (FOV.getDynamicFov().getValue()) fov *= Mth.lerp(partialTicks, oldFovModifier, fovModifier);
        cir.setReturnValue(modifyFovBasedOnDeathOrFluid(partialTicks, fov));
    }

    @Inject(method = "calculateHudFov", at = @At("RETURN"), cancellable = true)
    private void lbqmod$customItemFov(float partialTicks, CallbackInfoReturnable<Float> cir) {
        if (!FOV.INSTANCE.enabled || !FOV.getItemFov().getValue() || lbqmod$keepVanilla()) return;
        cir.setReturnValue(modifyFovBasedOnDeathOrFluid(partialTicks, FOV.getItemFovValue().getValue().floatValue()));
    }
}
