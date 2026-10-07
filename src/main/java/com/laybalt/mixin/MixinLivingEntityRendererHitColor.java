package com.laybalt.mixin;

import com.laybalt.features.impl.render.HitColor;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Hit Color: points hurt entities at the custom overlay row (or at no red overlay at all). */
@Mixin(LivingEntityRenderer.class)
public abstract class MixinLivingEntityRendererHitColor {
    @Inject(method = "getOverlayCoords", at = @At("HEAD"), cancellable = true)
    private static void lbqmod$hitColor(LivingEntityRenderState state, float whiteOverlayProgress, CallbackInfoReturnable<Integer> cir) {
        int row = HitColor.overlayRow(state);
        if (row >= 0) cir.setReturnValue(OverlayTexture.pack(OverlayTexture.u(whiteOverlayProgress), row));
    }
}
