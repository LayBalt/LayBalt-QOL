package com.laybalt.mixin;

import com.laybalt.features.impl.render.NoCameraClip;
import net.minecraft.client.CameraType;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** NoCameraClip "Anti Front": the front-facing perspective is replaced by first person. */
@Mixin(Options.class)
public abstract class MixinOptionsNoCameraClip {
    @ModifyVariable(method = "setCameraType", at = @At("HEAD"), argsOnly = true)
    private CameraType lbqmod$antiFront(CameraType type) {
        return NoCameraClip.filterCameraType(type);
    }
}
