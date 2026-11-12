package com.laybalt.mixin;

import com.laybalt.features.impl.render.GlintColor;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.renderer.rendertype.TextureTransform;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Glint Color: glint scroll speed (on top of the vanilla accessibility option) and pattern size. */
@Mixin(TextureTransform.class)
public abstract class MixinTextureTransformGlintColor {
    @ModifyExpressionValue(
        method = "setupGlintTexturing",
        at = @At(value = "FIELD", target = "Lnet/minecraft/client/renderer/state/OptionsRenderState;glintSpeed:D")
    )
    private static double lbqmod$glintSpeed(double speed) {
        return speed * GlintColor.speedMultiplier();
    }

    @ModifyVariable(method = "setupGlintTexturing", at = @At("HEAD"), argsOnly = true)
    private static float lbqmod$glintScale(float scale) {
        return scale / GlintColor.sizeMultiplier();
    }
}
