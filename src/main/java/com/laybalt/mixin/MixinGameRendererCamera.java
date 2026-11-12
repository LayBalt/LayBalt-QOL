package com.laybalt.mixin;

import com.laybalt.features.impl.misc.Camera;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Camera Helper "Disable Nausea": zeroes the nausea distortion strength (restored from NoammAddons' MixinGameRenderer). */
@Mixin(GameRenderer.class)
public class MixinGameRendererCamera {
    @ModifyVariable(method = "renderLevel", at = @At("STORE"), name = "nauseaIntensity")
    public float zeroNauseaIntensity(float nauseaIntensity) {
        return Camera.INSTANCE.enabled && Camera.getDisableNausea().getValue() ? 0F : nauseaIntensity;
    }
}
